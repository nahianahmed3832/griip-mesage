import type { DB } from "../../infrastructure/database/db.js";

/**
 * What the Platform Admin Console (modules/platformAdmin) may read about workspaces and communities: names, settings,
 * member and role counts, and one person's memberships. Never member lists, channel contents or reports.
 */
export type OrgKind = "team" | "community";

export interface OrgSummaryRow {
  id: string;
  name: string;
  slug: string;
  kind: OrgKind;
  visibility: string;
  created_at: number;
  members: number;
}

export interface OrgRecordRow extends OrgSummaryRow {
  join_policy: string;
  created_by: string;
  updated_at: number;
  invites_paused_until: number | null;
  dms_paused_until: number | null;
  guests_allowed: number;
  activity_alerts: number;
  raid_alert_at: number | null;
}

export interface MembershipRow {
  org_id: string;
  name: string;
  kind: OrgKind;
  role: string;
  joined_at: number;
  custom_roles: string | null;
}

const DAY_MS = 86_400_000;
const SUMMARY = `SELECT o.id, o.name, o.slug, o.kind, o.visibility, o.created_at,
    (SELECT COUNT(*) FROM org_members m WHERE m.org_id = o.id) AS members
  FROM orgs o`;
const likeEscape = (s: string) => s.replace(/[\\%_]/g, (c) => `\\${c}`);

/** Workspaces and communities, newest first, a page at a time ([after]: the previous page's last row). */
export function searchOrgs(db: DB, opts: { q?: string; kind?: OrgKind; after?: { createdAt: number; id: string }; limit: number }): OrgSummaryRow[] {
  const where: string[] = ["1 = 1"];
  const args: (string | number)[] = [];
  if (opts.q) {
    where.push("(o.name LIKE ? ESCAPE '\\' OR o.slug LIKE ? ESCAPE '\\' OR o.id = ?)");
    args.push(`%${likeEscape(opts.q)}%`, `${likeEscape(opts.q)}%`, opts.q);
  }
  if (opts.kind) {
    where.push("o.kind = ?");
    args.push(opts.kind);
  }
  if (opts.after) {
    where.push("(o.created_at < ? OR (o.created_at = ? AND o.id < ?))");
    args.push(opts.after.createdAt, opts.after.createdAt, opts.after.id);
  }
  return db.prepare(`${SUMMARY} WHERE ${where.join(" AND ")} ORDER BY o.created_at DESC, o.id DESC LIMIT ?`).all(...args, opts.limit) as OrgSummaryRow[];
}

export function orgRecord(db: DB, orgId: string): OrgRecordRow | undefined {
  return db
    .prepare(
      `SELECT o.id, o.name, o.slug, o.kind, o.visibility, o.join_policy, o.created_by, o.created_at, o.updated_at, o.invites_paused_until,
         o.dms_paused_until, o.guests_allowed, o.activity_alerts, o.raid_alert_at,
         (SELECT COUNT(*) FROM org_members m WHERE m.org_id = o.id) AS members
       FROM orgs o WHERE o.id = ?`,
    )
    .get(orgId) as OrgRecordRow | undefined;
}

/** Members per base role, custom roles, bans and live invitations: counts only. */
export function orgComposition(db: DB, orgId: string, t: number) {
  const roles = db.prepare("SELECT role, COUNT(*) AS n FROM org_members WHERE org_id = ? GROUP BY role").all(orgId) as { role: string; n: number }[];
  const one = (sql: string, ...args: (string | number)[]) => (db.prepare(sql).get(...args) as { n: number }).n;
  return {
    byRole: Object.fromEntries(roles.map((r) => [r.role, r.n])) as Record<string, number>,
    customRoles: one("SELECT COUNT(*) n FROM org_roles WHERE org_id = ?", orgId),
    bans: one("SELECT COUNT(*) n FROM org_bans WHERE org_id = ?", orgId),
    activeInvites: one("SELECT COUNT(*) n FROM invitations WHERE org_id = ? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > ?) AND uses < max_uses", orgId, t),
    owners: (db.prepare("SELECT user_id FROM org_members WHERE org_id = ? AND role = 'OWNER' ORDER BY joined_at LIMIT 10").all(orgId) as { user_id: string }[]).map((r) => r.user_id),
  };
}

/** [userId]'s memberships with their base role and the names of their custom roles. */
export function membershipsOf(db: DB, userId: string): MembershipRow[] {
  return db
    .prepare(
      `SELECT m.org_id, o.name, o.kind, m.role, m.joined_at,
         (SELECT group_concat(r.name, '\u001f') FROM org_role_members rm JOIN org_roles r ON r.id = rm.role_id WHERE rm.org_id = m.org_id AND rm.user_id = m.user_id) AS custom_roles
       FROM org_members m JOIN orgs o ON o.id = m.org_id WHERE m.user_id = ? ORDER BY m.joined_at DESC LIMIT 200`,
    )
    .all(userId) as MembershipRow[];
}

export function orgCounts(db: DB, t: number, days: number) {
  const kinds = db.prepare("SELECT kind, COUNT(*) AS n FROM orgs GROUP BY kind").all() as { kind: string; n: number }[];
  const perDay = db
    .prepare("SELECT strftime('%Y-%m-%d', created_at / 1000, 'unixepoch') AS day, COUNT(*) AS count FROM orgs WHERE created_at > ? GROUP BY day ORDER BY day")
    .all(t - days * DAY_MS) as { day: string; count: number }[];
  const byKind = Object.fromEntries(kinds.map((k) => [k.kind, k.n])) as Record<string, number>;
  return { total: kinds.reduce((a, k) => a + k.n, 0), teams: byKind.team ?? 0, communities: byKind.community ?? 0, newPerDay: perDay };
}

/**
 * Sign-in and device events of a person from the security audit trail (account.*, device.*, user.*): what happened and
 * when, without the free-text detail. [ids]: their user id and account id.
 */
export function securityEvents(db: DB, ids: string[], limit: number): { at: number; action: string }[] {
  if (ids.length === 0) return [];
  const marks = ids.map(() => "?").join(",");
  return db
    .prepare(
      `SELECT at, action FROM audit_log WHERE (actor_id IN (${marks}) OR target IN (${marks}))
         AND (action LIKE 'account.%' OR action LIKE 'device.%' OR action LIKE 'user.%') ORDER BY id DESC LIMIT ?`,
    )
    .all(...ids, ...ids, limit) as { at: number; action: string }[];
}

/** Names of organizations [ids] (pages that list ids from other tables). */
export function orgNames(db: DB, ids: string[]): Map<string, string> {
  if (ids.length === 0) return new Map();
  const rows = db.prepare(`SELECT id, name FROM orgs WHERE id IN (${ids.map(() => "?").join(",")})`).all(...ids) as { id: string; name: string }[];
  return new Map(rows.map((r) => [r.id, r.name]));
}

/** How many audit_log entries with [action] were written since [since] (alert rules: failed sign-ins). */
export function auditActionCount(db: DB, action: string, since: number): number {
  return (db.prepare("SELECT COUNT(*) n FROM audit_log WHERE action = ? AND at > ?").get(action, since) as { n: number }).n;
}

/**
 * A person's sign-in history from the server's audit log (account.* entries: sign-ins, failed passwords, refresh-token
 * reuse, password changes and resets). Session ids and account ids only; never addresses or tokens.
 */
export function authHistory(db: DB, userId: string, limit: number): { at: number; action: string; target: string | null; detail: string | null }[] {
  return db
    .prepare("SELECT at, action, target, detail FROM audit_log WHERE actor_id = ? AND action LIKE 'account.%' ORDER BY at DESC LIMIT ?")
    .all(userId, limit) as { at: number; action: string; target: string | null; detail: string | null }[];
}

/** Organizations created in [from, to) (ms); synthetic (load-test) ones left out. */
export function orgsCreatedBetween(db: DB, from: number, to: number): number {
  return (db.prepare("SELECT COUNT(*) n FROM orgs WHERE created_at >= ? AND created_at < ? AND synthetic = 0").get(from, to) as { n: number }).n;
}

/** The biggest organizations (synthetic ones left out), with their member ids for activity figures. Bounded. */
export function orgsWithMembers(db: DB, limit: number): { id: string; name: string; kind: OrgKind; members: string[] }[] {
  const orgs = db.prepare(`${SUMMARY} WHERE o.synthetic = 0 ORDER BY members DESC, o.created_at LIMIT ?`).all(limit) as OrgSummaryRow[];
  const memberIds = db.prepare("SELECT user_id FROM org_members WHERE org_id = ?");
  return orgs.map((o) => ({ id: o.id, name: o.name, kind: o.kind, members: (memberIds.all(o.id) as { user_id: string }[]).map((r) => r.user_id) }));
}

/** The member ids of one organization (its activity figures). */
export function orgMemberIds(db: DB, orgId: string): string[] {
  return (db.prepare("SELECT user_id FROM org_members WHERE org_id = ?").all(orgId) as { user_id: string }[]).map((r) => r.user_id);
}
