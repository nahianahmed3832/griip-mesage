import type { FastifyInstance } from "fastify";
import { mentionAllPermission } from "../mentions.js";
import { z } from "zod";
import type { ConversationRow } from "../authz.js";
import { type AppContext, me, parse, requireAuth, zText } from "../context.js";
import { canSeeLastSeen, memberView, type ProfileRow } from "../profile.js";
import { ApiError, badRequest, conflict, forbidden, newId, notFound, now } from "../util.js";
import { fedDmId } from "../federation/service.js";
import { userSummary } from "./auth.js";
import { processBlobDeletes, queueBlobDelete } from "./attachments.js";
import { evictRemovedMember } from "./calls.js";
import { reconcileAccess } from "./community.js";
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
    ).map((u) => memberView(ctx, userId, u, { ...userSummary(u), role: u.conv_role }));
  }
  const call = db.prepare("SELECT id, video, state, created_at FROM calls WHERE conversation_id = ? AND state != 'ENDED' ORDER BY created_at DESC LIMIT 1").get(c.id) as
    | { id: string; video: number; state: string; created_at: number }
    | undefined;
  // Who is in a voice room right now (shown on the room before joining), at most 12 people.
  const inRoom =
    c.voice && call
      ? (db.prepare("SELECT DISTINCT user_id FROM call_participants WHERE call_id = ? AND left_at IS NULL LIMIT 12").all(call.id) as { user_id: string }[]).map((r) => r.user_id)
      : undefined;
  // Older apps group channels by scope: a channel in a category is shown to them as a team named after the category,
  // so they list categories too. Newer apps read categoryId and position (docs/COMMUNITY_REBUILD_2026-10-21.md §7).
  const category = c.category_id ? (db.prepare("SELECT id, name FROM channel_categories WHERE id = ?").get(c.category_id) as { id: string; name: string } | undefined) : undefined;
  const orgName = c.org_id ? ((db.prepare("SELECT name FROM orgs WHERE id = ?").get(c.org_id) as { name: string } | undefined)?.name ?? null) : null;
  const channel = c.type === "CHANNEL";
  return {
    id: c.id,
    type: c.type,
    orgId: c.org_id,
    scope: channel ? (category ? "TEAM" : "ORG") : null,
    scopeId: channel ? (category?.id ?? null) : null,
    scopeName: channel ? (category?.name ?? orgName) : null,
    categoryId: category?.id ?? null,
    categoryName: category?.name ?? null,
    position: c.position ?? 0,
    format: c.format ?? "text",
    forumTags: c.format === "forum" ? forumTags(c) : [],
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
    // While this person is timed out in the community: when it ends (the app says why it can't post).
    ...(c.org_id && authz.timeoutUntil(c.org_id, userId) ? { timedOutUntil: authz.timeoutUntil(c.org_id, userId) } : {}),
    canAttach: authz.canAttach(c, userId),
    canManage: authz.canManageConversation(c, userId),
    // Whether this person may switch the channel between public, private and announcement (scope managers only).
    canManageAccess: authz.canManageChannelAccess(c, userId),
    // Whether the app should offer @everyone here (the hourly limit is checked when sending).
    canMentionAll: mentionAllPermission(ctx, c, userId) === null,
    activeCall: call ? { id: call.id, video: !!call.video, state: call.state, startedAt: call.created_at, ...(inRoom ? { participantIds: inRoom } : {}) } : null,
    voice: !!c.voice,
    // Voice channels: whether this person may join the call, and talk in it (listen-only otherwise).
    ...(c.voice ? { canConnect: authz.canConnectVoice(c, userId), canSpeak: authz.canSpeakInVoice(c, userId) } : {}),
    federation: federationInfo(ctx, c.id),
  };
}

export interface ForumTag {
  id: string;
  name: string;
}

/** Up to this many tags per forum (the reference forum allows the same). */
export const FORUM_TAG_LIMIT = 20;

/** A forum's tags as stored; anything malformed reads as no tags. */
export function forumTags(c: Pick<ConversationRow, "forum_tags">): ForumTag[] {
  try {
    const v: unknown = JSON.parse(c.forum_tags ?? "[]");
    if (!Array.isArray(v)) return [];
    return v.filter((t): t is ForumTag => typeof t?.id === "string" && typeof t?.name === "string").slice(0, FORUM_TAG_LIMIT);
  } catch {
    return [];
  }
}

/**
 * The tags to store from a manager's list: names trimmed, empty and repeated names (ignoring case) dropped, existing ids
 * kept for names that already had one (so posts keep their labels), new ids for new names.
 */
export function nextForumTags(prev: readonly ForumTag[], wanted: readonly { id?: string; name: string }[], makeId: () => string): ForumTag[] {
  const seen = new Set<string>();
  const used = new Set<string>();
  const out: ForumTag[] = [];
  for (const w of wanted) {
    const name = w.name.trim();
    const key = name.toLowerCase();
    if (!name || seen.has(key)) continue;
    seen.add(key);
    // An id goes to one tag only: a renamed tag keeps its id, and its old name sent again becomes a new tag.
    const keep = prev.find((p) => p.id === w.id && !used.has(p.id)) ?? prev.find((p) => p.name.toLowerCase() === key && !used.has(p.id));
    const id = keep?.id ?? makeId();
    used.add(id);
    out.push({ id, name });
  }
  return out;
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
    // Calls are hosted by the caller's server: this server can start one if it has its own media server.
    calls: !!ctx.federation?.callsEnabled(),
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

  /**
   * How many of a conversation's members are online now (round 3: "# general · 3 online"), for members only. Counts
   * only people whose "last seen" setting lets the viewer see their online state, so the number reveals no one who
   * hides it; the viewer counts too.
   */
  app.get("/v1/conversations/:id/presence", { ...auth, config: { rateLimit: { max: 60, timeWindow: "1 minute" } } }, async (req) => {
    const { userId } = me(req);
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    authz.requireMember(id, userId);
    const members = ctx.db
      .prepare("SELECT u.* FROM conversation_members m JOIN users u ON u.id = m.user_id WHERE m.conversation_id = ? AND u.disabled_at IS NULL")
      .all(id) as ProfileRow[];
    const online = members.filter((u) => (u.id === userId || canSeeLastSeen(ctx, userId, u)) && ctx.hub.isUserOnline(u.id)).length;
    return { online, members: members.length };
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
      members: rows.map((u) =>
        memberView(ctx, userId, u, {
          ...userSummary(u),
          role: u.conv_role,
          joinedAt: u.joined_at,
          orgRole: c.org_id ? authz.orgRole(c.org_id, u.id) : null,
        }),
      ),
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
    const body = parse(
      z.object({
        name: z.string().trim().min(1).max(60).optional(),
        topic: zText.nullable().optional(),
        archived: z.boolean().optional(),
        kind: z.enum(["PUBLIC", "PRIVATE", "ANNOUNCEMENT"]).optional(),
        format: z.enum(["text", "forum"]).optional(),
        forumTags: z
          .array(z.object({ id: z.string().max(64).optional(), name: z.string().max(20) }))
          .max(FORUM_TAG_LIMIT)
          .optional(),
      }),
      req.body,
    );
    if (body.format !== undefined && (c.type !== "CHANNEL" || c.voice)) throw badRequest("Only text channels can become forums");
    if (body.archived !== undefined && c.type !== "CHANNEL") throw badRequest("Only channels can be archived");
    const nextFormat = body.format ?? c.format ?? "text";
    if (body.forumTags !== undefined && nextFormat !== "forum") throw badRequest("Only forum channels have tags");
    const tags = body.forumTags === undefined ? (c.forum_tags ?? null) : JSON.stringify(nextForumTags(forumTags(c), body.forumTags, () => newId("tag")));
    const kindChange = body.kind !== undefined && body.kind !== c.kind;
    if (body.kind !== undefined && c.type !== "CHANNEL") throw badRequest("Only channels have an access mode");
    if (kindChange) {
      // Access mode (docs/WORKSPACE_ADMINISTRATION.md): only the scope's managers, never a channel admin by role alone.
      if (!authz.canManageChannelAccess(c, userId)) throw forbidden("Only people who can change channel access can change who can see and post in it");
      if (c.archived_at && body.archived !== false) throw conflict("This channel is archived. Restore it before changing its access.");
    }
    // Private channels never auto-join; announcement channels always do; a channel made public again auto-joins the
    // people its permissions let see it, as a new channel would. Making a channel private removes nobody.
    const autoJoin = !kindChange ? c.auto_join : body.kind === "PRIVATE" ? 0 : body.kind === "ANNOUNCEMENT" ? 1 : c.kind === "PRIVATE" ? 1 : c.auto_join;
    db.prepare("UPDATE conversations SET name = ?, topic = ?, archived_at = ?, kind = ?, auto_join = ?, format = ?, forum_tags = ?, updated_at = ? WHERE id = ?").run(
      body.name ?? c.name,
      body.topic === undefined ? c.topic : body.topic,
      body.archived === undefined ? c.archived_at : body.archived ? now() : null,
      kindChange ? body.kind : c.kind,
      autoJoin,
      nextFormat,
      tags,
      now(),
      id,
    );
    if (c.org_id) {
      if (kindChange) ctx.audit({ actorId: userId, orgId: c.org_id, action: "channel.access", target: id, detail: `${c.kind}→${body.kind}` });
      if (body.archived !== undefined && body.archived !== !!c.archived_at) {
        ctx.audit({ actorId: userId, orgId: c.org_id, action: body.archived ? "channel.archive" : "channel.restore", target: id });
      }
      if (body.name !== undefined || body.topic !== undefined || body.format !== undefined || body.forumTags !== undefined) ctx.audit({ actorId: userId, orgId: c.org_id, action: "channel.update", target: id });
      if (kindChange && body.kind !== "PRIVATE") await reconcileAccess(ctx, c.org_id);
    }
    notifyConversation(ctx, id);
    return { conversation: conversationView(ctx, authz.conversation(id)!, userId) };
  });

  /**
   * Deleting a community channel (docs/product/MELDIQ_ANDROID_INTERACTION_MATRIX.md): people who manage it, after
   * typing its name. Its messages, files, calls and permissions go for everyone, in one transaction; the community and
   * its other channels are untouched. Archiving (PATCH `archived`) is the reversible alternative.
   */
  app.delete("/v1/conversations/:id", auth, async (req) => {
    const { userId } = me(req);
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const body = parse(z.object({ confirmName: z.string().max(200) }), req.body ?? {});
    const c = authz.requireMember(id, userId);
    if (c.type !== "CHANNEL" || !c.org_id) throw badRequest("Only community channels can be deleted; leave a group or conversation instead");
    if (!authz.canManageConversation(c, userId)) throw forbidden("Only people who manage this channel can delete it");
    if (body.confirmName.trim().replace(/^#/, "") !== (c.name ?? "").trim()) {
      throw new ApiError(400, "confirm_mismatch", "Type the channel's name exactly as it appears to delete it");
    }
    const orgId = c.org_id;
    const result = db
      .transaction(() => {
        if (!db.prepare("SELECT 1 FROM conversations WHERE id = ?").get(id)) return null;
        const members = (db.prepare("SELECT user_id FROM conversation_members WHERE conversation_id = ?").all(id) as { user_id: string }[]).map((r) => r.user_id);
        const files = (db.prepare("SELECT id FROM attachments WHERE conversation_id = ?").all(id) as { id: string }[]).map((r) => r.id);
        const rooms = (db.prepare("SELECT room_name FROM calls WHERE conversation_id = ? AND state != 'ENDED'").all(id) as { room_name: string }[]).map((r) => r.room_name);
        for (const f of files) queueBlobDelete(ctx, "attachments", f);
        db.prepare(
          "INSERT OR IGNORE INTO abandoned_uploads (id, multipart_id, attempts, next_at) SELECT id, multipart_id, 0, 0 FROM attachment_uploads WHERE conversation_id = ?",
        ).run(id);
        // Rows without a foreign key to the conversation (members, attachment rows and calls cascade), and its overwrites.
        for (const table of ["delivery_queue", "submitted_messages", "mention_all_events"]) db.prepare(`DELETE FROM ${table} WHERE conversation_id = ?`).run(id);
        db.prepare("DELETE FROM channel_overwrites WHERE target_type = 'channel' AND target_id = ?").run(id);
        db.prepare("DELETE FROM conversations WHERE id = ?").run(id);
        return { members, files, rooms };
      })
      .immediate();
    if (!result) throw notFound("Conversation not found");
    ctx.audit({ actorId: userId, orgId, action: "channel.delete", target: id, detail: `${c.name ?? ""} (${result.files.length} files)` });
    // Apps refresh the channel (now 404, shown as gone) and the community's channel list.
    ctx.hub.sendToUsers(new Set(result.members), { t: "conv.updated", conversationId: id });
    const orgMembers = (db.prepare("SELECT user_id FROM org_members WHERE org_id = ?").all(orgId) as { user_id: string }[]).map((r) => r.user_id);
    ctx.hub.sendToUsers(new Set(orgMembers), { t: "org.updated", orgId });
    for (const room of result.rooms) await ctx.livekit?.deleteRoom(room).catch(() => {});
    await processBlobDeletes(ctx).catch(() => 0);
    return { deleted: true, id, members: result.members.length, files: result.files.length };
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
      if (!targetOrgRole) throw conflict("That person is not a member of this community");
      if (targetOrgRole !== "GUEST" && !authz.scopeEligible(c, p.userId)) {
        throw conflict("That person can't see this channel: its permissions don't include them");
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
