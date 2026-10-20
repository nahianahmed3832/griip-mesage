import type { AppContext } from "../context.js";
import { notifyConversation } from "../routes/orgs.js";
import { badRequest, conflict, forbidden, newId, notFound, now } from "../util.js";

/**
 * User groups (docs/WORKSPACE_C5.md, round 7 slice C5): named sets of workspace members. Separate from accounts and
 * roles, and they grant nothing: adding a group to a channel or a meeting adds its *current* members, each checked as if
 * added one by one (scope, private channels, guests). Guests can't see groups.
 */
interface GroupRow {
  id: string;
  org_id: string;
  name: string;
  handle: string;
  description: string | null;
  created_by: string;
  created_at: number;
  updated_at: number;
}

export const GROUP_LIMITS = { perOrg: 100, membersPerGroup: 500 } as const;

/** "Marketing & Sales" → "marketing-sales". */
export function handleFor(name: string): string {
  return name.normalize("NFKD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 40) || "group";
}

export class GroupService {
  constructor(private readonly ctx: AppContext) {}

  private requireViewer(orgId: string, userId: string): void {
    const role = this.ctx.authz.requireOrgMember(orgId, userId);
    if (role === "GUEST") throw forbidden("Guests can't see the workspace's groups");
  }

  private requireManager(orgId: string, userId: string): void {
    this.ctx.authz.requireOrgMember(orgId, userId);
    if (!this.ctx.authz.can(orgId, userId, "groups.manage")) throw forbidden("Only people who can manage groups can do that");
  }

  private get(orgId: string, id: string): GroupRow {
    const g = this.ctx.db.prepare("SELECT * FROM user_groups WHERE id = ? AND org_id = ?").get(id, orgId) as GroupRow | undefined;
    if (!g) throw notFound("Group not found");
    return g;
  }

  /** Current members only (someone who left the workspace drops out). */
  memberIds(groupId: string, orgId: string): string[] {
    return (
      this.ctx.db
        .prepare("SELECT gm.user_id FROM user_group_members gm JOIN org_members m ON m.user_id = gm.user_id AND m.org_id = ? WHERE gm.group_id = ?")
        .all(orgId, groupId) as { user_id: string }[]
    ).map((r) => r.user_id);
  }

  private view(g: GroupRow) {
    const members = this.ctx.db
      .prepare(
        `SELECT u.id, u.display_name, u.username FROM user_group_members gm JOIN users u ON u.id = gm.user_id
         JOIN org_members m ON m.user_id = gm.user_id AND m.org_id = ? WHERE gm.group_id = ? ORDER BY u.display_name`,
      )
      .all(g.org_id, g.id) as { id: string; display_name: string; username: string }[];
    return {
      id: g.id, orgId: g.org_id, name: g.name, handle: g.handle, description: g.description,
      members: members.map((m) => ({ id: m.id, name: m.display_name, username: m.username })),
      createdAt: g.created_at, updatedAt: g.updated_at,
    };
  }

  list(orgId: string, userId: string) {
    this.requireViewer(orgId, userId);
    return (this.ctx.db.prepare("SELECT * FROM user_groups WHERE org_id = ? ORDER BY name").all(orgId) as GroupRow[]).map((g) => this.view(g));
  }

  private checkMembers(orgId: string, userIds: string[]): void {
    if (userIds.length > GROUP_LIMITS.membersPerGroup) throw badRequest(`A group has at most ${GROUP_LIMITS.membersPerGroup} members`);
    for (const u of userIds) if (!this.ctx.authz.orgRole(orgId, u)) throw badRequest("Everyone in a group must be in the workspace", { userId: u });
  }

  private writeMembers(groupId: string, actorId: string, userIds: string[], t: number): void {
    this.ctx.db.prepare("DELETE FROM user_group_members WHERE group_id = ?").run(groupId);
    const ins = this.ctx.db.prepare("INSERT OR IGNORE INTO user_group_members (group_id, user_id, added_by, added_at) VALUES (?, ?, ?, ?)");
    for (const u of new Set(userIds)) ins.run(groupId, u, actorId, t);
  }

  create(orgId: string, actorId: string, input: { name: string; description?: string | null; memberIds: string[] }) {
    this.requireManager(orgId, actorId);
    const count = (this.ctx.db.prepare("SELECT COUNT(*) n FROM user_groups WHERE org_id = ?").get(orgId) as { n: number }).n;
    if (count >= GROUP_LIMITS.perOrg) throw conflict(`A workspace can have at most ${GROUP_LIMITS.perOrg} groups`);
    this.ctx.entitlements.check("groups", count, orgId);
    const handle = handleFor(input.name);
    if (this.ctx.db.prepare("SELECT 1 FROM user_groups WHERE org_id = ? AND handle = ?").get(orgId, handle)) throw conflict(`There's already a group @${handle}`);
    this.checkMembers(orgId, input.memberIds);
    const id = newId("grp");
    const t = now();
    this.ctx.db.transaction(() => {
      this.ctx.db.prepare("INSERT INTO user_groups (id, org_id, name, handle, description, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)").run(id, orgId, input.name, handle, input.description ?? null, actorId, t, t);
      this.writeMembers(id, actorId, input.memberIds, t);
    })();
    this.ctx.audit({ actorId, orgId, action: "group.create", target: id });
    return this.view(this.get(orgId, id));
  }

  update(orgId: string, actorId: string, id: string, input: { name: string; description?: string | null; memberIds: string[] }) {
    this.requireManager(orgId, actorId);
    const g = this.get(orgId, id);
    const handle = handleFor(input.name);
    if (handle !== g.handle && this.ctx.db.prepare("SELECT 1 FROM user_groups WHERE org_id = ? AND handle = ?").get(orgId, handle)) throw conflict(`There's already a group @${handle}`);
    this.checkMembers(orgId, input.memberIds);
    const t = now();
    this.ctx.db.transaction(() => {
      this.ctx.db.prepare("UPDATE user_groups SET name = ?, handle = ?, description = ?, updated_at = ? WHERE id = ?").run(input.name, handle, input.description ?? null, t, id);
      this.writeMembers(id, actorId, input.memberIds, t);
    })();
    this.ctx.audit({ actorId, orgId, action: "group.update", target: id });
    return this.view(this.get(orgId, id));
  }

  remove(orgId: string, actorId: string, id: string): void {
    this.requireManager(orgId, actorId);
    this.get(orgId, id);
    this.ctx.db.prepare("DELETE FROM user_groups WHERE id = ?").run(id);
    this.ctx.audit({ actorId, orgId, action: "group.delete", target: id });
  }

  /**
   * Adds the group's current members to a channel, as its manager would one by one: public channels take everyone in
   * the channel's scope; private channels only from someone who manages them. Returns who was added and who couldn't be.
   */
  addToChannel(conversationId: string, actorId: string, groupId: string): { added: number; skipped: number } {
    const { authz, db } = this.ctx;
    const c = authz.conversation(conversationId);
    if (!c || c.type !== "CHANNEL" || !c.org_id || !authz.isMember(c.id, actorId)) throw notFound("Channel not found");
    if (!authz.canManageConversation(c, actorId)) throw forbidden("Only the channel's managers can add people");
    if (c.archived_at) throw conflict("This channel is archived");
    const g = this.get(c.org_id, groupId);
    let added = 0;
    let skipped = 0;
    const t = now();
    for (const u of this.memberIds(g.id, c.org_id)) {
      if (!authz.scopeEligible(c, u)) {
        skipped++;
        continue;
      }
      added += db.prepare("INSERT OR IGNORE INTO conversation_members (conversation_id, user_id, role, joined_at) VALUES (?, ?, 'MEMBER', ?)").run(c.id, u, t).changes;
    }
    if (added) notifyConversation(this.ctx, c.id);
    this.ctx.audit({ actorId, orgId: c.org_id, action: "channel.group.add", target: c.id, detail: `${added} added` });
    return { added, skipped };
  }
}
