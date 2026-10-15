import crypto from "node:crypto";
import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { type AppContext, me, parse, requireAuth } from "../context.js";
import { ApiError, conflict, forbidden, newId, notFound, now } from "../util.js";

export interface CallRow {
  id: string;
  conversation_id: string;
  room_name: string;
  created_by: string;
  video: number;
  state: "RINGING" | "ACTIVE" | "ENDED";
  created_at: number;
  answered_at: number | null;
  ended_at: number | null;
  end_reason: string | null;
}

export function callView(ctx: AppContext, c: CallRow) {
  const participants = ctx.db
    .prepare("SELECT user_id, device_id, joined_at FROM call_participants WHERE call_id = ? AND left_at IS NULL")
    .all(c.id) as { user_id: string; device_id: string; joined_at: number }[];
  const conv = ctx.authz.conversation(c.conversation_id);
  return {
    id: c.id,
    conversationId: c.conversation_id,
    conversationType: conv?.type ?? null,
    createdBy: c.created_by,
    video: !!c.video,
    state: c.state,
    createdAt: c.created_at,
    answeredAt: c.answered_at,
    endedAt: c.ended_at,
    endReason: c.end_reason,
    participants: participants.map((p) => ({ userId: p.user_id, deviceId: p.device_id, joinedAt: p.joined_at })),
  };
}

export function conversationMemberIds(ctx: AppContext, convId: string): string[] {
  return (ctx.db.prepare("SELECT user_id FROM conversation_members WHERE conversation_id = ?").all(convId) as { user_id: string }[]).map((r) => r.user_id);
}

export function loadCall(ctx: AppContext, id: string): CallRow | undefined {
  return ctx.db.prepare("SELECT * FROM calls WHERE id = ?").get(id) as CallRow | undefined;
}

export function broadcastCall(ctx: AppContext, c: CallRow, event: string, extra: object = {}): void {
  ctx.hub.sendToUsers(conversationMemberIds(ctx, c.conversation_id), { t: "call", event, call: callView(ctx, c), ...extra });
}

export async function endCallRow(ctx: AppContext, c: CallRow, reason: string): Promise<void> {
  const t = now();
  const res = ctx.db.prepare("UPDATE calls SET state = 'ENDED', ended_at = ?, end_reason = ? WHERE id = ? AND state != 'ENDED'").run(t, reason, c.id);
  if (res.changes === 0) return;
  ctx.db.prepare("UPDATE call_participants SET left_at = ? WHERE call_id = ? AND left_at IS NULL").run(t, c.id);
  broadcastCall(ctx, loadCall(ctx, c.id)!, "ended");
  await ctx.livekit?.deleteRoom(c.room_name);
}

export default async function callRoutes(app: FastifyInstance, ctx: AppContext) {
  const auth = { preHandler: requireAuth(ctx) };
  const { db, authz } = ctx;

  const load = (id: string) => loadCall(ctx, id);
  const broadcast = (c: CallRow, event: string, extra: object = {}) => broadcastCall(ctx, c, event, extra);
  const endCall = (c: CallRow, reason: string) => endCallRow(ctx, c, reason);
  const memberIds = (convId: string) => conversationMemberIds(ctx, convId);

  const issue = async (c: CallRow, userId: string, deviceId: string) => {
    if (!ctx.livekit) throw new ApiError(503, "media_unavailable", "Calling is not configured on this server");
    const u = db.prepare("SELECT display_name FROM users WHERE id = ?").get(userId) as { display_name: string };
    const token = await ctx.livekit.token({
      room: c.room_name,
      identity: `${userId}.${deviceId}`,
      name: u.display_name,
      metadata: JSON.stringify({ userId, deviceId }),
    });
    return { url: ctx.livekit.publicUrl, token, room: c.room_name };
  };

  app.post("/v1/calls", auth, async (req) => {
    const { userId, deviceId } = me(req);
    const body = parse(z.object({ conversationId: z.string().max(64), video: z.boolean().default(false) }), req.body);
    const conv = authz.requireMember(body.conversationId, userId);
    if (conv.archived_at) throw conflict("This channel is archived");
    if (!ctx.livekit) throw new ApiError(503, "media_unavailable", "Calling is not configured on this server");
    // One live call per conversation: starting while one exists joins it instead.
    const existing = db.prepare("SELECT * FROM calls WHERE conversation_id = ? AND state != 'ENDED' ORDER BY created_at DESC LIMIT 1").get(conv.id) as
      | CallRow
      | undefined;
    if (existing) {
      return { call: callView(ctx, existing), media: await issue(existing, userId, deviceId), joinedExisting: true };
    }
    const id = newId("call");
    const room = `griip-${crypto.randomBytes(12).toString("hex")}`;
    db.prepare("INSERT INTO calls (id, conversation_id, room_name, created_by, video, state, created_at) VALUES (?, ?, ?, ?, ?, 'RINGING', ?)").run(
      id,
      conv.id,
      room,
      userId,
      body.video ? 1 : 0,
      now(),
    );
    const c = load(id)!;
    if (conv.org_id) ctx.audit({ actorId: userId, orgId: conv.org_id, action: "call.start", target: conv.id, detail: body.video ? "video" : "voice" });
    // Ring everyone else. The media E2EE key is NOT in this event: the caller sends it as an
    // end-to-end encrypted control message through the normal messaging path.
    const callerName = (db.prepare("SELECT display_name FROM users WHERE id = ?").get(userId) as { display_name: string }).display_name;
    for (const m of memberIds(conv.id)) {
      if (m === userId) continue;
      ctx.hub.sendToUser(m, { t: "call", event: "ring", call: callView(ctx, c), from: { userId, displayName: callerName } });
    }
    ctx.hub.sendToUser(userId, { t: "call", event: "started", call: callView(ctx, c) }, deviceId);
    return { call: callView(ctx, c), media: await issue(c, userId, deviceId), joinedExisting: false };
  });

  app.get("/v1/calls/active", auth, async (req) => {
    const { userId } = me(req);
    const rows = db
      .prepare(
        `SELECT c.* FROM calls c JOIN conversation_members m ON m.conversation_id = c.conversation_id
         WHERE m.user_id = ? AND c.state != 'ENDED' ORDER BY c.created_at DESC`,
      )
      .all(userId) as CallRow[];
    return { calls: rows.map((c) => callView(ctx, c)) };
  });

  app.get("/v1/calls/:id", auth, async (req) => {
    const { userId } = me(req);
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const c = load(id);
    if (!c || !authz.isMember(c.conversation_id, userId)) throw notFound("Call not found");
    return { call: callView(ctx, c) };
  });

  app.post("/v1/calls/:id/join", auth, async (req) => {
    const { userId, deviceId } = me(req);
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const c = load(id);
    if (!c || !authz.isMember(c.conversation_id, userId)) throw notFound("Call not found");
    if (c.state === "ENDED") throw new ApiError(410, "call_ended", "This call has ended");
    return { call: callView(ctx, c), media: await issue(c, userId, deviceId) };
  });

  // Client-side confirmation that it is connected to the media room. Complements LiveKit
  // webhooks so call state stays correct even where webhooks can't reach the API.
  app.post("/v1/calls/:id/connected", auth, async (req) => {
    const { userId, deviceId } = me(req);
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const c = load(id);
    if (!c || !authz.isMember(c.conversation_id, userId)) throw notFound("Call not found");
    if (c.state === "ENDED") throw new ApiError(410, "call_ended", "This call has ended");
    db.prepare(
      "INSERT INTO call_participants (call_id, user_id, device_id, joined_at) VALUES (?, ?, ?, ?) ON CONFLICT(call_id, device_id) DO UPDATE SET left_at = NULL",
    ).run(c.id, userId, deviceId, now());
    const users = (db.prepare("SELECT COUNT(DISTINCT user_id) n FROM call_participants WHERE call_id = ? AND left_at IS NULL").get(c.id) as { n: number }).n;
    if (c.state === "RINGING" && users >= 2) db.prepare("UPDATE calls SET state = 'ACTIVE', answered_at = ? WHERE id = ?").run(now(), c.id);
    const cur = load(id)!;
    broadcast(cur, "updated");
    return { call: callView(ctx, cur) };
  });

  app.post("/v1/calls/:id/decline", auth, async (req) => {
    const { userId } = me(req);
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const c = load(id);
    if (!c || !authz.isMember(c.conversation_id, userId)) throw notFound("Call not found");
    if (c.state === "ENDED") return { call: callView(ctx, c) };
    const conv = authz.conversation(c.conversation_id)!;
    if (conv.type === "DM" && c.state === "RINGING") {
      await endCall(c, "declined");
    } else {
      ctx.hub.sendToUser(c.created_by, { t: "call", event: "declined", call: callView(ctx, c), userId });
    }
    // Stop ringing on the decliner's other devices.
    ctx.hub.sendToUser(userId, { t: "call", event: "dismissed", call: callView(ctx, load(id)!) });
    return { call: callView(ctx, load(id)!) };
  });

  app.post("/v1/calls/:id/end", auth, async (req) => {
    const { userId } = me(req);
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const c = load(id);
    if (!c || !authz.isMember(c.conversation_id, userId)) throw notFound("Call not found");
    const conv = authz.conversation(c.conversation_id)!;
    // In 1:1 calls either party ends the call for both. In group calls only the starter or a
    // conversation manager may end it for everyone; others just leave.
    if (conv.type !== "DM" && c.created_by !== userId && !authz.canManageConversation(conv, userId)) {
      throw forbidden("Only the person who started this call or a manager can end it for everyone");
    }
    await endCall(c, "hangup");
    return { call: callView(ctx, load(id)!) };
  });

  app.post("/v1/calls/:id/leave", auth, async (req) => {
    const { userId, deviceId } = me(req);
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const c = load(id);
    if (!c || !authz.isMember(c.conversation_id, userId)) throw notFound("Call not found");
    db.prepare("UPDATE call_participants SET left_at = ? WHERE call_id = ? AND device_id = ? AND left_at IS NULL").run(now(), id, deviceId);
    const conv = authz.conversation(c.conversation_id)!;
    const remaining = (db.prepare("SELECT COUNT(*) n FROM call_participants WHERE call_id = ? AND left_at IS NULL").get(id) as { n: number }).n;
    if (conv.type === "DM" || remaining === 0) await endCall(c, conv.type === "DM" ? "hangup" : "empty");
    else broadcast(load(id)!, "updated");
    await ctx.livekit?.removeParticipant(c.room_name, `${userId}.${deviceId}`);
    return { call: callView(ctx, load(id)!) };
  });

  // LiveKit → API webhook: authoritative participant join/leave and room lifecycle.
  app.post("/v1/livekit/webhook", { config: { rawBody: true } }, async (req, reply) => {
    if (!ctx.livekit) return reply.code(404).send();
    let event;
    try {
      event = await ctx.livekit.receiveWebhook(req.body as string, req.headers.authorization);
    } catch {
      return reply.code(401).send({ error: "invalid webhook signature" });
    }
    const roomName = event.room?.name;
    if (!roomName) return { ok: true };
    const c = db.prepare("SELECT * FROM calls WHERE room_name = ?").get(roomName) as CallRow | undefined;
    if (!c) return { ok: true };
    const identity = event.participant?.identity ?? "";
    const [pUser, pDevice] = identity.split(".");
    if (event.event === "participant_joined" && pUser && pDevice) {
      db.prepare(
        "INSERT INTO call_participants (call_id, user_id, device_id, joined_at) VALUES (?, ?, ?, ?) ON CONFLICT(call_id, device_id) DO UPDATE SET left_at = NULL, joined_at = excluded.joined_at",
      ).run(c.id, pUser, pDevice, now());
      const count = (db.prepare("SELECT COUNT(DISTINCT user_id) n FROM call_participants WHERE call_id = ? AND left_at IS NULL").get(c.id) as { n: number }).n;
      if (c.state === "RINGING" && count >= 2) db.prepare("UPDATE calls SET state = 'ACTIVE', answered_at = ? WHERE id = ?").run(now(), c.id);
      broadcast(load(c.id)!, "updated");
    } else if (event.event === "participant_left" && pDevice) {
      db.prepare("UPDATE call_participants SET left_at = ? WHERE call_id = ? AND device_id = ? AND left_at IS NULL").run(now(), c.id, pDevice);
      const cur = load(c.id)!;
      const remaining = (db.prepare("SELECT COUNT(*) n FROM call_participants WHERE call_id = ? AND left_at IS NULL").get(c.id) as { n: number }).n;
      const conv = authz.conversation(c.conversation_id);
      if (cur.state !== "ENDED" && (remaining === 0 || (conv?.type === "DM" && cur.state === "ACTIVE"))) await endCall(cur, remaining === 0 ? "empty" : "hangup");
      else broadcast(cur, "updated");
    } else if (event.event === "room_finished") {
      await endCall(c, "empty");
    }
    return { ok: true };
  });
}
