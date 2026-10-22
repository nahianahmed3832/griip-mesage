import type { AppContext } from "../../app/context.js";
import type { DB } from "../../infrastructure/database/db.js";
import { EVERYONE, USER_PREFIX } from "./permissions/channel.js";
import { newId, now } from "../../shared/utilities/util.js";

/**
 * Branches and teams become roles and categories (docs/COMMUNITY_REBUILD_2026-10-21.md §6). Idempotent: every branch
 * and team converted is recorded in community_migration_runs and never converted twice, so this runs at every start-up
 * and does work only once. It never removes a member, a channel membership, a branch or a team; the old tables stay.
 *
 * For each branch or team:
 * - a custom role with its name and the same people (no powers of its own: it decides who sees the category);
 * - when it has channels, a category holding them, with the overwrites that keep exactly the same people able to see
 *   them: @everyone deny `channels.view`, the role allow it, and member overwrites for the people who used to reach
 *   the scope without being in it (branch managers over their branch and its teams, holders of the retired
 *   `teams.manage` power) and for those who ran it (branch managers, team leads: `channels.manage`, `channels.create`);
 * - invitations into it grant the role;
 * - an archived branch or team archives its channels (they were read-only already).
 * Channels' old scope columns are left as they were, so an older server rolled back to still reads them.
 */

interface ScopeRow {
  id: string;
  org_id: string;
  name: string;
  description: string | null;
  archived_at: number | null;
  branch_id?: string | null;
}

const RUN_INSERT =
  "INSERT INTO community_migration_runs (source_type, source_id, org_id, role_id, category_id, channels, members, converted_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

function tableExists(db: DB, name: string): boolean {
  return !!db.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").get(name);
}

/** Holders of the retired `teams.manage` power through a custom role (owners and admins see everything anyway). */
function teamManagers(db: DB, orgId: string): string[] {
  const rows = db
    .prepare(
      `SELECT DISTINCT m.user_id, r.permissions, r.denies, om.role FROM org_role_members m JOIN org_roles r ON r.id = m.role_id
       JOIN org_members om ON om.org_id = m.org_id AND om.user_id = m.user_id
       WHERE m.org_id = ? AND r.archived_at IS NULL`,
    )
    .all(orgId) as { user_id: string; permissions: string; denies: string; role: string }[];
  const has = (json: string) => {
    try {
      const v: unknown = JSON.parse(json);
      return Array.isArray(v) && v.includes("teams.manage");
    } catch {
      return false;
    }
  };
  return [...new Set(rows.filter((r) => r.role === "MEMBER" && has(r.permissions)).map((r) => r.user_id))];
}

/** A role name not taken yet in the community ("Design", then "Design (team)", then "Design (team 2)"). */
function freeRoleName(db: DB, orgId: string, name: string, kind: "branch" | "team"): string {
  const taken = (n: string) => !!db.prepare("SELECT 1 FROM org_roles WHERE org_id = ? AND lower(name) = lower(?) AND archived_at IS NULL").get(orgId, n);
  if (!taken(name)) return name;
  for (let i = 1; ; i++) {
    const candidate = i === 1 ? `${name} (${kind})` : `${name} (${kind} ${i})`;
    if (!taken(candidate)) return candidate;
  }
}

function nextPosition(db: DB, table: "org_roles" | "channel_categories", orgId: string): number {
  return ((db.prepare(`SELECT MAX(position) AS p FROM ${table} WHERE org_id = ?`).get(orgId) as { p: number | null }).p ?? -1) + 1;
}

function putOverwrite(db: DB, categoryId: string, orgId: string, subject: string, allow: string[], deny: string[], t: number): void {
  db.prepare(
    `INSERT INTO channel_overwrites (target_type, target_id, org_id, subject, allow, deny, updated_by, updated_at)
     VALUES ('category', ?, ?, ?, ?, ?, 'migration', ?)
     ON CONFLICT (target_type, target_id, subject) DO UPDATE SET allow = excluded.allow, deny = excluded.deny, updated_at = excluded.updated_at`,
  ).run(categoryId, orgId, subject, JSON.stringify(allow), JSON.stringify(deny), t);
}

/** Adds [caps] to a member overwrite already on the category (or creates it). */
function allowForUser(db: DB, categoryId: string, orgId: string, userId: string, caps: string[], t: number): void {
  const row = db.prepare("SELECT allow FROM channel_overwrites WHERE target_type = 'category' AND target_id = ? AND subject = ?").get(categoryId, USER_PREFIX + userId) as
    | { allow: string }
    | undefined;
  const allow = [...new Set([...(row ? (JSON.parse(row.allow) as string[]) : []), ...caps])];
  putOverwrite(db, categoryId, orgId, USER_PREFIX + userId, allow, [], t);
}

function convert(
  db: DB,
  kind: "branch" | "team",
  scope: ScopeRow,
  members: { user_id: string; role: string }[],
  reach: { managers: string[]; runners: string[] },
  t: number,
): { channels: number } {
  const roleId = newId("role");
  const roleName = freeRoleName(db, scope.org_id, scope.name, kind);
  const owner = (db.prepare("SELECT user_id FROM org_members WHERE org_id = ? AND role = 'OWNER' ORDER BY joined_at LIMIT 1").get(scope.org_id) as { user_id: string } | undefined)?.user_id ?? "migration";
  db.prepare(
    `INSERT INTO org_roles (id, org_id, name, description, color, icon, permissions, denies, template, position, rev, created_by, created_at, updated_at)
     VALUES (?, ?, ?, ?, NULL, 'people', '[]', '[]', ?, ?, 1, ?, ?, ?)`,
  ).run(
    roleId,
    scope.org_id,
    roleName,
    scope.description ?? `Everyone who was in the ${kind} "${scope.name}" before communities.`,
    `migrated-${kind}`,
    nextPosition(db, "org_roles", scope.org_id),
    owner,
    t,
    t,
  );
  const addRole = db.prepare("INSERT OR IGNORE INTO org_role_members (role_id, org_id, user_id, assigned_by, assigned_at) VALUES (?, ?, ?, 'migration', ?)");
  // Guests never held branch or team memberships, but a member row could outlive its org membership in odd data.
  for (const m of members) {
    if (db.prepare("SELECT 1 FROM org_members WHERE org_id = ? AND user_id = ?").get(scope.org_id, m.user_id)) addRole.run(roleId, scope.org_id, m.user_id, t);
  }

  const scopeName = kind === "branch" ? "BRANCH" : "TEAM";
  const channels = db.prepare("SELECT id FROM conversations WHERE org_id = ? AND type = 'CHANNEL' AND scope = ? AND scope_id = ? ORDER BY lower(name)").all(scope.org_id, scopeName, scope.id) as { id: string }[];
  let categoryId: string | null = null;
  if (channels.length) {
    categoryId = newId("cat");
    db.prepare("INSERT INTO channel_categories (id, org_id, name, position, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, 'migration', ?, ?)").run(
      categoryId,
      scope.org_id,
      roleName,
      nextPosition(db, "channel_categories", scope.org_id),
      t,
      t,
    );
    channels.forEach((c, i) => db.prepare("UPDATE conversations SET category_id = ?, position = ? WHERE id = ?").run(categoryId, i, c.id));
    putOverwrite(db, categoryId, scope.org_id, EVERYONE, [], ["channels.view"], t);
    putOverwrite(db, categoryId, scope.org_id, roleId, ["channels.view"], [], t);
    for (const u of reach.managers) allowForUser(db, categoryId, scope.org_id, u, ["channels.view", "channels.manage", "channels.create"], t);
    for (const u of reach.runners) allowForUser(db, categoryId, scope.org_id, u, ["channels.manage", "channels.create"], t);
    if (scope.archived_at) {
      db.prepare("UPDATE conversations SET archived_at = COALESCE(archived_at, ?) WHERE category_id = ?").run(scope.archived_at, categoryId);
    }
  }
  // A team invitation names its branch too: the team's role wins, as the team is what the invite was for.
  const inviteFilter = kind === "branch" ? "branch_id = ? AND team_id IS NULL" : "team_id = ?";
  db.prepare(`UPDATE invitations SET grant_role_id = ? WHERE ${inviteFilter} AND grant_role_id IS NULL`).run(roleId, scope.id);
  db.prepare(RUN_INSERT).run(kind, scope.id, scope.org_id, roleId, categoryId, channels.length, members.length, t);
  return { channels: channels.length };
}

export interface CommunityMigrationResult {
  converted: number;
  branches: number;
  teams: number;
  channels: number;
}

/** Converts every branch and team not converted yet, in one transaction. */
export function migrateBranchesAndTeams(db: DB): CommunityMigrationResult {
  const result: CommunityMigrationResult = { converted: 0, branches: 0, teams: 0, channels: 0 };
  if (!tableExists(db, "branches") || !tableExists(db, "community_migration_runs")) return result;
  const done = (kind: string, id: string) => !!db.prepare("SELECT 1 FROM community_migration_runs WHERE source_type = ? AND source_id = ?").get(kind, id);
  const t = now();
  db.transaction(() => {
    const managersByOrg = new Map<string, string[]>();
    const orgManagers = (orgId: string) => {
      if (!managersByOrg.has(orgId)) managersByOrg.set(orgId, teamManagers(db, orgId));
      return managersByOrg.get(orgId)!;
    };
    for (const b of db.prepare("SELECT * FROM branches ORDER BY created_at").all() as ScopeRow[]) {
      if (done("branch", b.id)) continue;
      const members = db.prepare("SELECT user_id, role FROM branch_members WHERE branch_id = ?").all(b.id) as { user_id: string; role: string }[];
      const managers = members.filter((m) => m.role === "MANAGER").map((m) => m.user_id);
      const r = convert(db, "branch", b, members, { managers: [...new Set([...orgManagers(b.org_id), ...managers])], runners: [] }, t);
      result.branches++;
      result.channels += r.channels;
    }
    for (const tm of db.prepare("SELECT * FROM teams ORDER BY created_at").all() as ScopeRow[]) {
      if (done("team", tm.id)) continue;
      const members = db.prepare("SELECT user_id, role FROM team_members WHERE team_id = ?").all(tm.id) as { user_id: string; role: string }[];
      const branchManagers = tm.branch_id
        ? (db.prepare("SELECT user_id FROM branch_members WHERE branch_id = ? AND role = 'MANAGER'").all(tm.branch_id) as { user_id: string }[]).map((r) => r.user_id)
        : [];
      const leads = members.filter((m) => m.role === "LEAD").map((m) => m.user_id);
      // A team in an archived branch counted as archived (D-14).
      const branchArchived = tm.branch_id ? (db.prepare("SELECT archived_at FROM branches WHERE id = ?").get(tm.branch_id) as { archived_at: number | null } | undefined)?.archived_at ?? null : null;
      const scope = { ...tm, archived_at: tm.archived_at ?? branchArchived };
      const r = convert(db, "team", scope, members, { managers: [...new Set([...orgManagers(tm.org_id), ...branchManagers])], runners: leads }, t);
      result.teams++;
      result.channels += r.channels;
    }
  })();
  result.converted = result.branches + result.teams;
  return result;
}

/** At start-up: convert, then let the people concerned refresh (no one gains or loses access, the structure changed). */
export function runCommunityMigration(ctx: AppContext): CommunityMigrationResult {
  const r = migrateBranchesAndTeams(ctx.db);
  if (r.converted > 0) ctx.audit({ action: "community.migration", detail: JSON.stringify(r) });
  return r;
}

/**
 * The old rule, for verification: may [userId] see [channel] as the branch/team model decided it? Mirrors the
 * pre-rebuild Authz.canViewChannel (membership, or a non-private channel in a scope the person could access).
 */
function legacyCanView(db: DB, conv: { id: string; org_id: string; scope: string | null; scope_id: string | null; kind: string | null }, userId: string): boolean {
  if (db.prepare("SELECT 1 FROM conversation_members WHERE conversation_id = ? AND user_id = ?").get(conv.id, userId)) return true;
  if (conv.kind === "PRIVATE") return false;
  const role = (db.prepare("SELECT role FROM org_members WHERE org_id = ? AND user_id = ?").get(conv.org_id, userId) as { role: string } | undefined)?.role;
  if (!role || role === "GUEST") return false;
  if (role === "OWNER" || role === "ADMIN") return true;
  if (conv.scope === "ORG" || !conv.scope) return true;
  const teamsManage = teamManagers(db, conv.org_id).includes(userId);
  if (conv.scope === "BRANCH") return teamsManage || !!db.prepare("SELECT 1 FROM branch_members WHERE branch_id = ? AND user_id = ?").get(conv.scope_id, userId);
  const team = db.prepare("SELECT branch_id FROM teams WHERE id = ?").get(conv.scope_id) as { branch_id: string | null } | undefined;
  if (!team) return false;
  if (teamsManage || db.prepare("SELECT 1 FROM team_members WHERE team_id = ? AND user_id = ?").get(conv.scope_id, userId)) return true;
  return !!team.branch_id && !!db.prepare("SELECT 1 FROM branch_members WHERE branch_id = ? AND user_id = ? AND role = 'MANAGER'").get(team.branch_id, userId);
}

export interface VerifyDifference {
  orgId: string;
  userId: string;
  channelId: string;
  before: boolean;
  after: boolean;
}

/**
 * Compares, for every community member and channel, who could see it under the branch/team rules with who can now.
 * Empty when the migration kept access exactly. Archived scopes are compared too: archiving changes posting, not
 * seeing. [canViewNow] is Authz.canViewChannel.
 */
export function verifyCommunityMigration(db: DB, canViewNow: (channelId: string, userId: string) => boolean): VerifyDifference[] {
  const diffs: VerifyDifference[] = [];
  const channels = db.prepare("SELECT id, org_id, scope, scope_id, kind FROM conversations WHERE type = 'CHANNEL' AND org_id IS NOT NULL").all() as {
    id: string;
    org_id: string;
    scope: string | null;
    scope_id: string | null;
    kind: string | null;
  }[];
  for (const c of channels) {
    const members = (db.prepare("SELECT user_id FROM org_members WHERE org_id = ?").all(c.org_id) as { user_id: string }[]).map((r) => r.user_id);
    for (const u of members) {
      const before = legacyCanView(db, c, u);
      const after = canViewNow(c.id, u);
      if (before !== after) diffs.push({ orgId: c.org_id, userId: u, channelId: c.id, before, after });
    }
  }
  return diffs;
}
