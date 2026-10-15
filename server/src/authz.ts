import type { DB } from "./db.js";
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

export class Authz {
  constructor(private db: DB) {}

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
    return this.isOrgAdmin(branch.org_id, userId) || this.branchRole(branch.id, userId) === "MANAGER";
  }

  canManageTeam(team: TeamRow, userId: string): boolean {
    if (this.isOrgAdmin(team.org_id, userId)) return true;
    if (team.branch_id && this.branchRole(team.branch_id, userId) === "MANAGER") return true;
    return this.teamRole(team.id, userId) === "LEAD";
  }

  /** Users who are members of a branch (or org admins) may see the branch's content. */
  canAccessBranch(branch: BranchRow, userId: string): boolean {
    const role = this.orgRole(branch.org_id, userId);
    if (!role || role === "GUEST") return false;
    return role === "OWNER" || role === "ADMIN" || this.branchRole(branch.id, userId) !== null;
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
    if (this.isOrgAdmin(conv.org_id, userId)) return true;
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

  canPost(conv: ConversationRow, userId: string): boolean {
    if (conv.archived_at) return false;
    if (!this.isMember(conv.id, userId)) return false;
    if (conv.type === "CHANNEL" && conv.kind === "ANNOUNCEMENT") return this.canManageConversation(conv, userId);
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
