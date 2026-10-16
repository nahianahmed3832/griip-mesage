import type { FastifyInstance } from "fastify";
import { z } from "zod";
import type { ConversationRow } from "../authz.js";
import { type AppContext, me, parse, requireAuth, zText } from "../context.js";
import { ApiError, badRequest, conflict, forbidden, newId, notFound, now } from "../util.js";
import { fedDmId } from "../federation/service.js";
import { userSummary } from "./auth.js";
import { evictRemovedMember } from "./calls.js";
import { notifyConversation } from "./orgs.js";
import { deviceDirectory } from "./users.js";

const MAX_GROUP = 50;

export function conversationView(ctx: AppContext, c: ConversationRow, userId: string) {
  const { db, authz } = ctx;
  const memberCount = (db.prepare("SELECT COUNT(*) n FROM conversation_members WHERE conversation_id = ?").get(c.id) as { n: number }).n;
  const myRole = authz.conversationRole(c.id, userId);
  let members: object[] | undefined;
  if (c.type !== "CHANNEL" || memberCount <= 12) {
    members = (
      db
        .prepare("SELECT u.*, m.role AS conv_role FROM conversation_members m JOIN users u ON u.id = m.user_id WHERE m.conversation_id = ? ORDER BY m.joined_at LIMIT 12")
        .all(c.id) as any[]
    ).map((u) => ({ ...userSummary(u), role: u.conv_role, online: ctx.hub.isUserOnline(u.id) }));
  }
  const call = db.prepare("SELECT id, video, state, created_at FROM calls WHERE conversation_id = ? AND state != 'ENDED' ORDER BY created_at DESC LIMIT 1").get(c.id) as
    | { id: string; video: number; state: string; created_at: number }
    | undefined;
  let scopeName: string | null = null;
  if (c.scope === "BRANCH" && c.scope_id) scopeName = (db.prepare("SELECT name FROM branches WHERE id = ?").get(c.scope_id) as any)?.name ?? null;
  if (c.scope === "TEAM" && c.scope_id) scopeName = (db.prepare("SELECT name FROM teams WHERE id = ?").get(c.scope_id) as any)?.name ?? null;
  if (c.scope === "ORG" && c.org_id) scopeName = (db.prepare("SELECT name FROM orgs WHERE id = ?").get(c.org_id) as any)?.name ?? null;
  return {
    id: c.id,
    type: c.type,
    orgId: c.org_id,
    scope: c.scope,
    scopeId: c.scope_id,
    scopeName,
    kind: c.kind,
    name: c.name,
    topic: c.topic,
    archived: !!c.archived_at,
    createdAt: c.created_at,
    updatedAt: c.updated_at,
    memberCount,
    members,
    joined: myRole !== null,
    myRole,
    canPost: authz.canPost(c, userId),
    canManage: authz.canManageConversation(c, userId),
    activeCall: call ? { id: call.id, video: !!call.video, state: call.state, startedAt: call.created_at } : null,
    federation: federationInfo(ctx, c.id),
  };
}

/** For a conversation with people on other servers: each server's domain, directory state and queued deliveries. */
function federationInfo(ctx: AppContext, convId: string) {
  const rows = ctx.db
    .prepare(
      `SELECT DISTINCT u.home_server_id AS id, p.domain, p.state FROM conversation_members m JOIN users u ON u.id = m.user_id
       LEFT JOIN federation_peers p ON p.server_id = u.home_server_id
       WHERE m.conversation_id = ? AND u.home_server_id IS NOT NULL`,
    )
    .all(convId) as { id: string; domain: string | null; state: string | null }[];
  if (rows.length === 0) return null;
  return {
    servers: rows.map((r) => ({ serverId: r.id, domain: r.domain, state: r.state ?? "unknown", pending: ctx.federation?.pendingFor(r.id) ?? 0 })),
  };
}

export default async function conversationRoutes(app: FastifyInstance, ctx: AppContext) {
  const auth = { preHandler: requireAuth(ctx) };
  const { db, authz } = ctx;

  app.get("/v1/conversations", auth, async (req) => {
    const { userId } = me(req);
    const rows = db
      .prepare(
        `SELECT c.* FROM conversations c JOIN conversation_members m ON m.conversation_id = c.id
         WHERE m.user_id = ? ORDER BY c.updated_at DESC`,
      )
      .all(userId) as ConversationRow[];
    return { conversations: rows.map((c) => conversationView(ctx, c, userId)) };
  });

  app.get("/v1/conversations/:id", auth, async (req) => {
    const { userId } = me(req);
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const c = authz.conversation(id);
    if (!c) throw notFound("Conversation not found");
    const visible = c.type === "CHANNEL" ? authz.canViewChannel(c, userId) : authz.isMember(c.id, userId);
    if (!visible) throw notFound("Conversation not found");
    return { conversation: conversationView(ctx, c, userId) };
  });

  app.get("/v1/conversations/:id/members", auth, async (req) => {
    const { userId } = me(req);
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const c = authz.conversation(id);
    if (!c || !(c.type === "CHANNEL" ? authz.canViewChannel(c, userId) : authz.isMember(c.id, userId))) throw notFound("Conversation not found");
    const rows = db
      .prepare("SELECT u.*, m.role AS conv_role, m.joined_at FROM conversation_members m JOIN users u ON u.id = m.user_id WHERE m.conversation_id = ? ORDER BY m.role, u.display_name")
      .all(id) as any[];
    return {
      members: rows.map((u) => ({
        ...userSummary(u),
        role: u.conv_role,
        joinedAt: u.joined_at,
        online: ctx.hub.isUserOnline(u.id),
        orgRole: c.org_id ? authz.orgRole(c.org_id, u.id) : null,
      })),
    };
  });

  /** Public keys of every active device of every member: what a sender must encrypt to. */
  app.get("/v1/conversations/:id/devices", auth, async (req) => {
    const { userId } = me(req);
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    authz.requireMember(id, userId);
    const ids = (db.prepare("SELECT user_id FROM conversation_members WHERE conversation_id = ?").all(id) as { user_id: string }[]).map((r) => r.user_id);
    await ctx.federation?.refreshStale(ids); // remote members' keys come from their home server, refreshed when stale
    return { users: deviceDirectory(ctx, ids) };
  });

  app.post("/v1/conversations/dm", auth, async (req) => {
    const { userId } = me(req);
    const { userId: other } = parse(z.object({ userId: z.string().max(64) }), req.body);
    if (other === userId) throw badRequest("You can't start a direct conversation with yourself");
    const target = db.prepare("SELECT id, home_server_id FROM users WHERE id = ? AND disabled_at IS NULL").get(other) as { id: string; home_server_id: string | null } | undefined;
    if (!target) throw notFound("User not found");
    const key = [userId, other].sort().join("|");
    const t = now();
    let c = db.prepare("SELECT * FROM conversations WHERE dm_key = ?").get(key) as ConversationRow | undefined;
    if (!c) {
      // With someone on another server, both servers derive the same conversation id from the two user ids.
      if (target.home_server_id && !ctx.federation) throw new ApiError(503, "federation_disabled", "This server doesn't federate");
      const id = target.home_server_id ? fedDmId(key) : newId("c");
      db.transaction(() => {
        db.prepare("INSERT INTO conversations (id, type, dm_key, created_by, created_at, updated_at) VALUES (?, 'DM', ?, ?, ?, ?)").run(id, key, userId, t, t);
        const ins = db.prepare("INSERT INTO conversation_members (conversation_id, user_id, role, joined_at) VALUES (?, ?, 'MEMBER', ?)");
        ins.run(id, userId, t);
        ins.run(id, other, t);
      })();
      c = authz.conversation(id)!;
      notifyConversation(ctx, id);
    }
    return { conversation: conversationView(ctx, c, userId) };
  });

  app.post("/v1/conversations/group", auth, async (req) => {
    const { userId } = me(req);
    const body = parse(z.object({ name: z.string().trim().min(1).max(60), memberIds: z.array(z.string().max(64)).min(1).max(MAX_GROUP - 1) }), req.body);
    const ids = [...new Set(body.memberIds.filter((m) => m !== userId))];
    // Groups are server-local in this release: people on other servers can't be added (FEDERATION_PROTOCOL.md §7).
    const found = db.prepare(`SELECT id FROM users WHERE disabled_at IS NULL AND home_server_id IS NULL AND id IN (${ids.map(() => "?").join(",")})`).all(...ids) as { id: string }[];
    if (found.length !== ids.length) throw badRequest("One or more members do not exist or are on another server");
    const id = newId("c");
    const t = now();
    db.transaction(() => {
      db.prepare("INSERT INTO conversations (id, type, name, created_by, created_at, updated_at) VALUES (?, 'GROUP', ?, ?, ?, ?)").run(id, body.name, userId, t, t);
      db.prepare("INSERT INTO conversation_members (conversation_id, user_id, role, joined_at) VALUES (?, ?, 'ADMIN', ?)").run(id, userId, t);
      const ins = db.prepare("INSERT INTO conversation_members (conversation_id, user_id, role, joined_at) VALUES (?, ?, 'MEMBER', ?)");
      for (const m of ids) ins.run(id, m, t);
    })();
    notifyConversation(ctx, id);
    return { conversation: conversationView(ctx, authz.conversation(id)!, userId) };
  });

  app.patch("/v1/conversations/:id", auth, async (req) => {
    const { userId } = me(req);
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const c = authz.requireMember(id, userId);
    if (!authz.canManageConversation(c, userId)) throw forbidden();
    const body = parse(z.object({ name: z.string().trim().min(1).max(60).optional(), topic: zText.nullable().optional(), archived: z.boolean().optional() }), req.body);
    if (body.archived !== undefined && c.type !== "CHANNEL") throw badRequest("Only channels can be archived");
    db.prepare("UPDATE conversations SET name = ?, topic = ?, archived_at = ?, updated_at = ? WHERE id = ?").run(
      body.name ?? c.name,
      body.topic === undefined ? c.topic : body.topic,
      body.archived === undefined ? c.archived_at : body.archived ? now() : null,
      now(),
      id,
    );
    if (c.org_id) ctx.audit({ actorId: userId, orgId: c.org_id, action: body.archived ? "channel.archive" : "channel.update", target: id });
    notifyConversation(ctx, id);
    return { conversation: conversationView(ctx, authz.conversation(id)!, userId) };
  });

  app.post("/v1/conversations/:id/join", auth, async (req) => {
    const { userId } = me(req);
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const c = authz.conversation(id);
    if (!c || c.type !== "CHANNEL" || !authz.canViewChannel(c, userId)) throw notFound("Channel not found");
    if (c.kind === "PRIVATE") throw forbidden("Private channels require an invitation from a channel manager");
    if (c.archived_at) throw conflict("This channel is archived");
    if (!authz.scopeEligible(c, userId)) throw forbidden();
    db.prepare("INSERT OR IGNORE INTO conversation_members (conversation_id, user_id, role, joined_at) VALUES (?, ?, 'MEMBER', ?)").run(id, userId, now());
    notifyConversation(ctx, id);
    return { conversation: conversationView(ctx, c, userId) };
  });

  app.put("/v1/conversations/:id/members/:userId", auth, async (req) => {
    const actor = me(req).userId;
    const p = parse(z.object({ id: z.string().max(64), userId: z.string().max(64) }), req.params);
    const { role } = parse(z.object({ role: z.enum(["ADMIN", "MEMBER"]).default("MEMBER") }), req.body ?? {});
    const c = authz.conversation(p.id);
    if (!c) throw notFound("Conversation not found");
    if (c.type === "DM") throw badRequest("Direct conversations have fixed members");
    if (!authz.canManageConversation(c, actor)) throw forbidden("Only managers of this conversation can add members");
    const target = db.prepare("SELECT id FROM users WHERE id = ? AND disabled_at IS NULL").get(p.userId);
    if (!target) throw notFound("User not found");
    if (c.type === "CHANNEL") {
      const targetOrgRole = authz.orgRole(c.org_id!, p.userId);
      if (!targetOrgRole) throw conflict("That person is not a member of this organization");
      if (targetOrgRole !== "GUEST" && !authz.scopeEligible(c, p.userId)) {
        throw conflict(`That person has no access to this channel's ${c.scope?.toLowerCase()}. Add them there first.`);
      }
    } else {
      const n = (db.prepare("SELECT COUNT(*) n FROM conversation_members WHERE conversation_id = ?").get(c.id) as { n: number }).n;
      if (n >= MAX_GROUP && !authz.isMember(c.id, p.userId)) throw conflict(`Groups are limited to ${MAX_GROUP} people`);
    }
    db.prepare(
      "INSERT INTO conversation_members (conversation_id, user_id, role, joined_at) VALUES (?, ?, ?, ?) ON CONFLICT(conversation_id, user_id) DO UPDATE SET role = excluded.role",
    ).run(c.id, p.userId, role, now());
    if (c.org_id) ctx.audit({ actorId: actor, orgId: c.org_id, action: "channel.member.add", target: `${c.id}:${p.userId}` });
    notifyConversation(ctx, c.id);
    return { ok: true };
  });

  app.delete("/v1/conversations/:id/members/:userId", auth, async (req) => {
    const actor = me(req).userId;
    const p = parse(z.object({ id: z.string().max(64), userId: z.string().max(64) }), req.params);
    const c = authz.conversation(p.id);
    if (!c || !authz.isMember(c.id, actor)) throw notFound("Conversation not found");
    if (c.type === "DM") throw badRequest("You can't leave a direct conversation");
    if (actor !== p.userId && !authz.canManageConversation(c, actor)) throw forbidden();
    if (c.type === "CHANNEL" && c.kind === "ANNOUNCEMENT" && actor === p.userId && !authz.canManageConversation(c, actor)) {
      throw forbidden("Announcement channels can't be left");
    }
    db.prepare("DELETE FROM conversation_members WHERE conversation_id = ? AND user_id = ?").run(c.id, p.userId);
    if (c.type === "GROUP") {
      // Keep at least one admin in a group.
      const admins = (db.prepare("SELECT COUNT(*) n FROM conversation_members WHERE conversation_id = ? AND role = 'ADMIN'").get(c.id) as { n: number }).n;
      if (admins === 0) {
        db.prepare(
          "UPDATE conversation_members SET role = 'ADMIN' WHERE conversation_id = ? AND user_id = (SELECT user_id FROM conversation_members WHERE conversation_id = ? ORDER BY joined_at LIMIT 1)",
        ).run(c.id, c.id);
      }
    }
    if (c.org_id) ctx.audit({ actorId: actor, orgId: c.org_id, action: actor === p.userId ? "channel.leave" : "channel.member.remove", target: `${c.id}:${p.userId}` });
    notifyConversation(ctx, c.id, [p.userId]);
    await evictRemovedMember(ctx, p.userId, [c.id]);
    return { ok: true };
  });
}
