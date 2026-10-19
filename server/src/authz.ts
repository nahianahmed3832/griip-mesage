import type { DB } from "./db.js";
import { isPermission, type Permission } from "./permissions/catalog.js";
import { type Actor, effectivePermissions, type RoleGrant } from "./permissions/rules.js";
import { forbidden, notFound } from "./util.js";

/**
 * Server-side authorisation. Every sensitive route calls into this module; the Android UI
 * merely mirrors the same rules to hide controls. See docs/ARCHITECTURE.md §5 for the matrix.
 */
export type OrgRole = "OWNER" | "ADMIN" | "MEMBER" | "GUEST";
export type BranchRole = "MANAGER" | "MEMBER";
export type TeamRole = "LEAD" | "MEMBER";

export interface ConversationRow {
  id: string;
  type: "DM" | "GROUP" | "CHANNEL";
  org_id: string | null;
  scope: "ORG" | "BRANCH" | "TEAM" | null;
  scope_id: string | null;
  kind: "PUBLIC" | "PRIVATE" | "ANNOUNCEMENT" | null;
  name: string | null;
  topic: string | null;
  dm_key: string | null;
  auto_join: number;
  created_by: string;
  created_at: number;
  updated_at: number;
  archived_at: number | null;
  /** 1: a voice room (migration 019, docs/VOICE_ROOMS.md). */
  voice?: number;
}

export interface TeamRow {
  id: string;
  org_id: string;
  branch_id: string | null;
  department_id: string | null;
  name: string;
  description: string | null;
  archived_at: number | null;
}

export interface BranchRow {
  id: string;
  org_id: string;
  name: string;
  description: string | null;
  location: string | null;
  archived_at: number | null;
}

/** A custom role as assigned to someone (src/permissions, docs/WORKSPACE_ROLES.md). */
export interface AssignedRole extends RoleGrant {
  id: string;
  name: string;
  color: string | null;
}

/** Stored permission lists are written by validated routes; anything unknown (a key from a newer server) is ignored. */
export function parsePermissionList(json: string): Permission[] {
  try {
    const v: unknown = JSON.parse(json);
    return Array.isArray(v) ? v.filter((k): k is Permission => typeof k === "string" && isPermission(k)) : [];
  } catch {
    return [];
  }
}

export class Authz {
  constructor(private db: DB) {}

  /** The custom roles [userId] holds in [orgId] (archived roles grant and restrict nothing). */
  assignedRoles(orgId: string, userId: string): AssignedRole[] {
    const rows = this.db
      .prepare(
        `SELECT r.id, r.name, r.color, r.permissions, r.denies FROM org_role_members m JOIN org_roles r ON r.id = m.role_id
         WHERE m.org_id = ? AND m.user_id = ? AND r.archived_at IS NULL ORDER BY r.position, r.name`,
      )
      .all(orgId, userId) as { id: string; name: string; color: string | null; permissions: string; denies: string }[];
    return rows.map((r) => ({ id: r.id, name: r.name, color: r.color, permissions: parsePermissionList(r.permissions), denies: parsePermissionList(r.denies) }));
  }

  /**
   * Effective permissions in a workspace: the base role's, plus custom roles' grants, minus their restrictions
   * (src/permissions/rules.ts). Empty for someone who isn't a member: deny by default.
   */
  permissions(orgId: string, userId: string): Set<Permission> {
    return this.actor(orgId, userId)?.perms ?? new Set();
  }

  can(orgId: string, userId: string, permission: Permission): boolean {
    return this.permissions(orgId, userId).has(permission);
  }

  /** The member's base role and effective permissions, or null for someone outside the workspace. */
  actor(orgId: string, userId: string): (Actor & { perms: Set<Permission> }) | null {
    const base = this.orgRole(orgId, userId);
    return base ? { base, perms: effectivePermissions(base, this.assignedRoles(orgId, userId)) } : null;
  }

  /**
   * What the member's custom roles would give them on another base role. A guest keeps their roles while only the
   * guest-safe parts count, so promoting them back brings the rest back: whoever promotes must reach that too.
   */
  permissionsAs(orgId: string, userId: string, base: OrgRole): Set<Permission> {
    return effectivePermissions(base, this.assignedRoles(orgId, userId));
  }

  /** Members of the workspace who hold [permission] now (to tell them about something waiting for them). */
  workspaceMembersWith(orgId: string, permission: Permission): string[] {
    const ids = (this.db.prepare("SELECT user_id FROM org_members WHERE org_id = ?").all(orgId) as { user_id: string }[]).map((r) => r.user_id);
    return ids.filter((id) => this.can(orgId, id, permission));
  }

  /** The workspace's active custom role ids. */
  workspaceRoleIds(orgId: string): string[] {
    return (this.db.prepare("SELECT id FROM org_roles WHERE org_id = ? AND archived_at IS NULL").all(orgId) as { id: string }[]).map((r) => r.id);
  }

  /** 404 for outsiders (the workspace's existence stays hidden), 403 without the permission. Returns the base role. */
  requirePermission(orgId: string, userId: string, permission: Permission, message?: string): OrgRole {
    const role = this.requireOrgMember(orgId, userId);
    if (!this.can(orgId, userId, permission)) throw forbidden(message ?? "You don't have permission to do that in this workspace");
    return role;
  }

  orgRole(orgId: string, userId: string): OrgRole | null {
    const r = this.db.prepare("SELECT role FROM org_members WHERE org_id = ? AND user_id = ?").get(orgId, userId) as
      | { role: OrgRole }
      | undefined;
    return r?.role ?? null;
  }

  isOrgAdmin(orgId: string, userId: string): boolean {
    const r = this.orgRole(orgId, userId);
    return r === "OWNER" || r === "ADMIN";
  }

  branch(branchId: string): BranchRow | undefined {
    return this.db.prepare("SELECT * FROM branches WHERE id = ?").get(branchId) as BranchRow | undefined;
  }

  team(teamId: string): TeamRow | undefined {
    return this.db.prepare("SELECT * FROM teams WHERE id = ?").get(teamId) as TeamRow | undefined;
  }

  branchRole(branchId: string, userId: string): BranchRole | null {
    const r = this.db
      .prepare("SELECT role FROM branch_members WHERE branch_id = ? AND user_id = ?")
      .get(branchId, userId) as { role: BranchRole } | undefined;
    return r?.role ?? null;
  }

  teamRole(teamId: string, userId: string): TeamRole | null {
    const r = this.db.prepare("SELECT role FROM team_members WHERE team_id = ? AND user_id = ?").get(teamId, userId) as
      | { role: TeamRole }
      | undefined;
    return r?.role ?? null;
  }

  /** Require org membership (any role); 404 hides org existence from outsiders. */
  requireOrgMember(orgId: string, userId: string): OrgRole {
    const role = this.orgRole(orgId, userId);
    if (!role) throw notFound("Organization not found");
    return role;
  }

  requireOrgAdmin(orgId: string, userId: string): void {
    this.requireOrgMember(orgId, userId);
    if (!this.isOrgAdmin(orgId, userId)) throw forbidden("Only organization owners and admins can do that");
  }

  canManageBranch(branch: BranchRow, userId: string): boolean {
    return this.can(branch.org_id, userId, "teams.manage") || this.branchRole(branch.id, userId) === "MANAGER";
  }

  canManageTeam(team: TeamRow, userId: string): boolean {
    if (this.can(team.org_id, userId, "teams.manage")) return true;
    if (team.branch_id && this.branchRole(team.branch_id, userId) === "MANAGER") return true;
    return this.teamRole(team.id, userId) === "LEAD";
  }

  /** Users who are members of a branch (or org admins) may see the branch's content. */
  canAccessBranch(branch: BranchRow, userId: string): boolean {
    const role = this.orgRole(branch.org_id, userId);
    if (!role || role === "GUEST") return false;
    if (role === "OWNER" || role === "ADMIN" || this.branchRole(branch.id, userId) !== null) return true;
    return this.can(branch.org_id, userId, "teams.manage");
  }

  canAccessTeam(team: TeamRow, userId: string): boolean {
    const role = this.orgRole(team.org_id, userId);
    if (!role || role === "GUEST") return false;
    return this.teamRole(team.id, userId) !== null || this.canManageTeam(team, userId);
  }

  conversation(id: string): ConversationRow | undefined {
    return this.db.prepare("SELECT * FROM conversations WHERE id = ?").get(id) as ConversationRow | undefined;
  }

  conversationRole(convId: string, userId: string): "ADMIN" | "MEMBER" | null {
    const r = this.db
      .prepare("SELECT role FROM conversation_members WHERE conversation_id = ? AND user_id = ?")
      .get(convId, userId) as { role: "ADMIN" | "MEMBER" } | undefined;
    return r?.role ?? null;
  }

  isMember(convId: string, userId: string): boolean {
    return this.conversationRole(convId, userId) !== null;
  }

  /** May this user administer the channel/group (rename, members, archive)? */
  canManageConversation(conv: ConversationRow, userId: string): boolean {
    if (conv.type === "DM") return false;
    if (conv.type === "GROUP") return this.conversationRole(conv.id, userId) === "ADMIN";
    if (!conv.org_id) return false;
    if (this.can(conv.org_id, userId, "channels.manage")) return true;
    if (conv.scope === "BRANCH" && conv.scope_id) {
      const b = this.branch(conv.scope_id);
      if (b && this.branchRole(b.id, userId) === "MANAGER") return true;
    }
    if (conv.scope === "TEAM" && conv.scope_id) {
      const t = this.team(conv.scope_id);
      if (t && this.canManageTeam(t, userId)) return true;
    }
    return this.conversationRole(conv.id, userId) === "ADMIN";
  }

  /**
   * May this user change who can see, join and post in a channel (its access mode: public, private or announcement)?
   * Only the people who manage the channel's scope: org owners and admins, the branch's managers for a branch channel,
   * and the team's leads and managers for a team channel. A channel admin by role alone may not, so nobody can turn a
   * private channel public just because they were made its admin (docs/WORKSPACE_ADMINISTRATION.md).
   */
  canManageChannelAccess(conv: ConversationRow, userId: string): boolean {
    if (conv.type !== "CHANNEL" || !conv.org_id) return false;
    if (this.can(conv.org_id, userId, "channels.visibility")) return true;
    if (conv.scope === "BRANCH" && conv.scope_id) {
      const b = this.branch(conv.scope_id);
      return !!b && this.branchRole(b.id, userId) === "MANAGER";
    }
    if (conv.scope === "TEAM" && conv.scope_id) {
      const t = this.team(conv.scope_id);
      return !!t && this.canManageTeam(t, userId);
    }
    return false;
  }

  /**
   * May this user archive or restore a team? Org owners and admins, and the managers of the team's branch. A team lead
   * runs their team but can't archive it, as only org admins archive branches.
   */
  canArchiveTeam(team: TeamRow, userId: string): boolean {
    if (this.can(team.org_id, userId, "teams.manage")) return true;
    return !!team.branch_id && this.branchRole(team.branch_id, userId) === "MANAGER";
  }

  /**
   * Scope eligibility for a channel: may this user be a member at all?
   * Guests are never eligible by scope; they can only be added explicitly by a manager.
   */
  scopeEligible(conv: ConversationRow, userId: string): boolean {
    if (conv.type !== "CHANNEL" || !conv.org_id) return false;
    const role = this.orgRole(conv.org_id, userId);
    if (!role || role === "GUEST") return false;
    if (role === "OWNER" || role === "ADMIN") return true;
    if (conv.scope === "ORG") return true;
    if (conv.scope === "BRANCH" && conv.scope_id) return this.branchRole(conv.scope_id, userId) !== null;
    if (conv.scope === "TEAM" && conv.scope_id) {
      const t = this.team(conv.scope_id);
      return !!t && this.canAccessTeam(t, userId);
    }
    return false;
  }

  /** Can the user see that this channel exists (browse / preview)? */
  canViewChannel(conv: ConversationRow, userId: string): boolean {
    if (this.isMember(conv.id, userId)) return true;
    if (conv.kind === "PRIVATE") return false;
    return this.scopeEligible(conv, userId);
  }

  /** Throws unless the user is a current member. Non-members get 404 to avoid leaking existence. */
  requireMember(convId: string, userId: string): ConversationRow {
    const conv = this.conversation(convId);
    if (!conv || !this.isMember(conv.id, userId)) throw notFound("Conversation not found");
    return conv;
  }

  /**
   * Whether a branch or team is archived (D-14). A team counts as archived if its branch is. Archived
   * scopes are read-only: no posts or calls in their channels, no new members, invites or channels.
   */
  scopeArchived(branchId: string | null, teamId: string | null): boolean {
    const team = teamId ? this.team(teamId) : undefined;
    if (team?.archived_at) return true;
    const b = branchId ?? team?.branch_id ?? null;
    return !!(b && this.branch(b)?.archived_at);
  }

  /** The archived state of the branch or team a channel belongs to. */
  conversationScopeArchived(conv: ConversationRow): boolean {
    if (conv.scope === "BRANCH") return this.scopeArchived(conv.scope_id, null);
    if (conv.scope === "TEAM") return this.scopeArchived(null, conv.scope_id);
    return false;
  }

  canPost(conv: ConversationRow, userId: string): boolean {
    if (conv.archived_at || this.conversationScopeArchived(conv)) return false;
    if (!this.isMember(conv.id, userId)) return false;
    if (conv.type !== "CHANNEL" || !conv.org_id) return true;
    // A workspace role may restrict posting (a read-only member); announcements need a manager or `messages.announce`.
    const perms = this.permissions(conv.org_id, userId);
    if (!perms.has("messages.post")) return false;
    if (conv.kind === "ANNOUNCEMENT") return perms.has("messages.announce") || this.canManageConversation(conv, userId);
    return true;
  }

  /**
   * Re-validate a user's channel memberships inside an org after their org/branch/team
   * membership changed. Removes memberships the user is no longer eligible for.
   * Returns the ids of conversations the user was removed from.
   */
  revalidateChannelMemberships(orgId: string, userId: string): string[] {
    const role = this.orgRole(orgId, userId);
    const rows = this.db
      .prepare(
        `SELECT c.* FROM conversations c JOIN conversation_members m ON m.conversation_id = c.id
         WHERE c.org_id = ? AND m.user_id = ? AND c.type = 'CHANNEL'`,
      )
      .all(orgId, userId) as ConversationRow[];
    const removed: string[] = [];
    for (const conv of rows) {
      // Guests keep only explicit memberships granted by managers; removed members lose everything.
      const keep = role === null ? false : role === "GUEST" ? true : this.scopeEligible(conv, userId);
      if (!keep) {
        this.db.prepare("DELETE FROM conversation_members WHERE conversation_id = ? AND user_id = ?").run(conv.id, userId);
        removed.push(conv.id);
      }
    }
    return removed;
  }

  /**
   * Demotion to Guest. Guests have explicit-only access, so everything the person had through
   * their former role goes: branch and team memberships in this org and every channel
   * membership in it (public, private and announcement). Managers re-add the specific channels a
   * guest should keep. DMs and groups are not organisation-scoped and are left alone.
   * Returns the ids of conversations the user was removed from.
   */
  demoteToGuest(orgId: string, userId: string): string[] {
    this.db.prepare("DELETE FROM branch_members WHERE user_id = ? AND branch_id IN (SELECT id FROM branches WHERE org_id = ?)").run(userId, orgId);
    this.db.prepare("DELETE FROM team_members WHERE user_id = ? AND team_id IN (SELECT id FROM teams WHERE org_id = ?)").run(userId, orgId);
    const rows = this.db
      .prepare(
        `SELECT c.id FROM conversations c JOIN conversation_members m ON m.conversation_id = c.id
         WHERE c.org_id = ? AND c.type = 'CHANNEL' AND m.user_id = ?`,
      )
      .all(orgId, userId) as { id: string }[];
    const remove = this.db.prepare("DELETE FROM conversation_members WHERE conversation_id = ? AND user_id = ?");
    for (const r of rows) remove.run(r.id, userId);
    return rows.map((r) => r.id);
  }

  /**
   * Whether `userId` may issue an org invite with this role and scope: owners invite admins; owners
   * and admins invite anyone below; branch managers and team leads invite into what they manage.
   * Checked when an invite is created and again whenever it is used (T3.1), so an invite stops working
   * once its creator loses the right to issue it.
   */
  canIssueInvite(orgId: string, userId: string, role: string, branchId: string | null, teamId: string | null): boolean {
    const actorRole = this.orgRole(orgId, userId);
    if (!actorRole || actorRole === "GUEST") return false;
    if (role === "ADMIN") return actorRole === "OWNER";
    if (this.can(orgId, userId, "members.invite")) return true;
    const branch = branchId ? this.branch(branchId) : undefined;
    const team = teamId ? this.team(teamId) : undefined;
    return (!!branch && this.canManageBranch(branch, userId)) || (!!team && this.canManageTeam(team, userId));
  }

  /**
   * Online state and last-seen are visible to the person themself, to anyone who shares a
   * conversation with them, and to non-guest members of an organisation they belong to. Knowing a
   * username or id is not enough.
   */
  canSeePresence(viewerId: string, targetId: string): boolean {
    if (viewerId === targetId) return true;
    const shared = this.db
      .prepare("SELECT 1 FROM conversation_members a JOIN conversation_members b ON b.conversation_id = a.conversation_id WHERE a.user_id = ? AND b.user_id = ? LIMIT 1")
      .get(viewerId, targetId);
    if (shared) return true;
    return !!this.db
      .prepare("SELECT 1 FROM org_members a JOIN org_members b ON b.org_id = a.org_id WHERE a.user_id = ? AND a.role != 'GUEST' AND b.user_id = ? LIMIT 1")
      .get(viewerId, targetId);
  }

  /** Auto-join public + announcement channels of a scope the user just gained access to. */
  autoJoinScope(orgId: string, userId: string, now: number): string[] {
    const role = this.orgRole(orgId, userId);
    if (!role || role === "GUEST") return [];
    const rows = this.db
      .prepare(
        `SELECT * FROM conversations WHERE org_id = ? AND type = 'CHANNEL' AND archived_at IS NULL
         AND (kind = 'ANNOUNCEMENT' OR auto_join = 1)
         AND id NOT IN (SELECT conversation_id FROM conversation_members WHERE user_id = ?)`,
      )
      .all(orgId, userId) as ConversationRow[];
    const joined: string[] = [];
    for (const conv of rows) {
      if (this.scopeEligible(conv, userId)) {
        this.db
          .prepare("INSERT OR IGNORE INTO conversation_members (conversation_id, user_id, role, joined_at) VALUES (?, ?, 'MEMBER', ?)")
          .run(conv.id, userId, now);
        joined.push(conv.id);
      }
    }
    return joined;
  }
}
