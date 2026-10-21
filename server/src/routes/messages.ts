import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { type AppContext, me, parse, requireAuth, zB64, zKey, zSig } from "../context.js";
import { canonical, verifyEd25519 } from "../crypto.js";
import { type QueueItemRow, queueItemToWire } from "../hub.js";
import { ApiError, conflict, forbidden, now } from "../util.js";
import { MENTION_ALL_MESSAGES, mentionAllPermission, mentionAllRateLimited } from "../mentions.js";

const MAX_CIPHERTEXT_B64 = 128 * 1024; // ≈ 96 KiB of ciphertext per message (attachments go via /v1/attachments)

const zEnvelope = z.object({
  conversationId: z.string().max(64),
  messageId: z.string().min(8).max(64).regex(/^[A-Za-z0-9_-]+$/),
  cls: z.enum(["message", "control"]),
  sentAt: z.number().int().positive(),
  nonce: z.string().length(16).regex(/^[A-Za-z0-9+/]+={0,2}$/), // 12 bytes
  ciphertext: zB64.max(MAX_CIPHERTEXT_B64),
  signature: zSig,
  recipients: z
    .array(z.object({ deviceId: z.string().max(64), enc: zKey, wrappedKey: z.string().length(64).regex(/^[A-Za-z0-9+/]+={0,2}$/) }))
    .max(1000),
  /**
   * The sender asks for @everyone (docs/MENTIONS.md). Content stays encrypted: the server only checks its policy and
   * marks the recipients' copies, and recipients treat @everyone as a mention only with that mark.
   */
  mentionAll: z.boolean().optional(),
});

export default async function messageRoutes(app: FastifyInstance, ctx: AppContext) {
  const auth = { preHandler: requireAuth(ctx) };
  const { db, authz } = ctx;

  /**
   * Accept an end-to-end encrypted envelope and fan it out into per-device delivery queues.
   * The server verifies the sender's signature and the recipient device set, but cannot read
   * the content. Idempotent on (messageId, senderDevice).
   */
  app.post("/v1/messages", { ...auth, bodyLimit: 2 * 1024 * 1024 }, async (req) => {
    const { userId, deviceId } = me(req);
    const e = parse(zEnvelope, req.body);
    const conv = authz.requireMember(e.conversationId, userId);
    if (!authz.canPost(conv, userId)) {
      throw forbidden(
        conv.archived_at ? "This channel is archived" : conv.kind === "ANNOUNCEMENT" ? "Only managers can post in announcement channels" : "You can't post in this channel",
      );
    }
    // Clock sanity: sentAt is signed by the client; reject wildly wrong clocks (±1 day).
    if (Math.abs(e.sentAt - now()) > 24 * 3600_000) throw new ApiError(400, "bad_clock", "Device clock is too far off");

    const prior = db.prepare("SELECT * FROM submitted_messages WHERE message_id = ?").get(e.messageId) as
      | { sender_device_id: string; server_ts: number; recipient_count: number }
      | undefined;
    if (prior) {
      if (prior.sender_device_id !== deviceId) throw conflict("Message id collision");
      return { messageId: e.messageId, serverTs: prior.server_ts, recipientCount: prior.recipient_count, duplicate: true };
    }

    const sender = db.prepare("SELECT signing_key FROM devices WHERE id = ?").get(deviceId) as { signing_key: string };
    const signed = canonical.envelope({
      messageId: e.messageId,
      conversationId: e.conversationId,
      senderUserId: userId,
      senderDeviceId: deviceId,
      sentAt: e.sentAt,
      cls: e.cls,
      nonce: e.nonce,
      ciphertext: e.ciphertext,
    });
    if (!verifyEd25519(sender.signing_key, signed, e.signature)) {
      throw new ApiError(400, "bad_signature", "Envelope signature does not verify against the sending device");
    }

    // @everyone: only for messages, only where the policy allows, at most mentionAllPerHour new ones an hour. A retry of
    // an accepted message returned above (duplicate) and is never counted twice.
    const mentionAll = e.mentionAll === true && e.cls === "message";
    if (mentionAll) {
      const refusal = mentionAllPermission(ctx, conv, userId) ?? (mentionAllRateLimited(ctx, conv.id, userId) ? "mention_all_rate_limited" : null);
      if (refusal) throw new ApiError(refusal === "mention_all_rate_limited" ? 429 : 403, refusal, MENTION_ALL_MESSAGES[refusal]);
    }

    // The recipient set must equal the active devices of current members (minus this device).
    const expected = db
      .prepare(
        `SELECT d.id, d.user_id, u.home_server_id FROM devices d JOIN conversation_members m ON m.user_id = d.user_id
         JOIN users u ON u.id = d.user_id
         WHERE m.conversation_id = ? AND d.revoked_at IS NULL AND u.disabled_at IS NULL AND d.id != ?`,
      )
      .all(conv.id, deviceId) as { id: string; user_id: string; home_server_id: string | null }[];
    const expectedIds = new Map(expected.map((d) => [d.id, d.user_id]));
    const provided = new Set(e.recipients.map((r) => r.deviceId));
    const missing = expected.filter((d) => !provided.has(d.id)).map((d) => ({ deviceId: d.id, userId: d.user_id }));
    const extra = [...provided].filter((id) => !expectedIds.has(id));
    if (missing.length || extra.length || provided.size !== e.recipients.length) {
      throw new ApiError(409, "device_mismatch", "Recipient devices changed; refresh keys and retry", { missing, extra });
    }

    const t = now();
    const expiresAt = t + ctx.config.queueTtlSeconds * 1000;
    const inserted: QueueItemRow[] = [];
    // Devices of people on other servers go to the federation outbox (same ciphertext, same per-device key wrap).
    const homeOf = new Map(expected.map((d) => [d.id, d.home_server_id]));
    const ownerOf = new Map(expected.map((d) => [d.id, d.user_id]));
    if ([...homeOf.values()].some((h) => h) && !ctx.federation) throw new ApiError(503, "federation_disabled", "This server no longer federates");
    // People who blocked the sender don't get the message (the sender isn't told).
    const blockedBy = new Set(
      (db.prepare("SELECT user_id FROM user_blocks WHERE blocked_user_id = ?").all(userId) as { user_id: string }[]).map((r) => r.user_id),
    );
    db.transaction(() => {
      db.prepare(
        "INSERT INTO submitted_messages (message_id, sender_device_id, sender_user_id, conversation_id, recipient_count, server_ts, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
      ).run(e.messageId, deviceId, userId, conv.id, e.recipients.length, t, expiresAt);
      if (mentionAll) {
        db.prepare("INSERT INTO mention_all_events (conversation_id, user_id, message_id, at) VALUES (?, ?, ?, ?)").run(conv.id, userId, e.messageId, t);
      }
      const ins = db.prepare(
        `INSERT INTO delivery_queue (recipient_device_id, kind, conversation_id, message_id, sender_user_id, sender_device_id, payload, created_at, expires_at)
         VALUES (?, 'envelope', ?, ?, ?, ?, ?, ?, ?) RETURNING *`,
      );
      for (const r of e.recipients) {
        if (blockedBy.has(ownerOf.get(r.deviceId) ?? "")) continue;
        const payload = JSON.stringify({
          v: 1,
          cls: e.cls,
          sentAt: e.sentAt,
          serverTs: t,
          nonce: e.nonce,
          ciphertext: e.ciphertext,
          signature: e.signature,
          enc: r.enc,
          wrappedKey: r.wrappedKey,
          // Server-attested @everyone: omitted otherwise, so ordinary messages keep their exact queue payload.
          ...(mentionAll ? { mentionAll: true } : {}),
        });
        const home = homeOf.get(r.deviceId);
        if (home) {
          ctx.federation!.routeRemote(home, {
            key: `env:${e.messageId}:${deviceId}:${r.deviceId}`,
            kind: "envelope",
            conversationId: conv.id,
            messageId: e.messageId,
            senderUserId: userId,
            senderDeviceId: deviceId,
            recipientDeviceId: r.deviceId,
            payload,
            createdAt: t,
            expiresAt,
          });
          continue;
        }
        inserted.push(ins.get(r.deviceId, conv.id, e.messageId, userId, deviceId, payload, t, expiresAt) as QueueItemRow);
      }
      if (e.cls === "message") db.prepare("UPDATE conversations SET updated_at = ? WHERE id = ?").run(t, conv.id);
    })();
    for (const row of inserted) ctx.hub.pushQueued(row.recipient_device_id, [row]);
    return { messageId: e.messageId, serverTs: t, recipientCount: e.recipients.length, duplicate: false };
  });

  // REST fallback for the WebSocket queue (used after reconnect if the socket is unavailable).
  app.get("/v1/queue", auth, async (req) => {
    const { deviceId } = me(req);
    const { limit } = parse(z.object({ limit: z.coerce.number().int().min(1).max(200).default(100) }), req.query);
    const rows = db
      .prepare("SELECT * FROM delivery_queue WHERE recipient_device_id = ? AND expires_at > ? ORDER BY id LIMIT ?")
      .all(deviceId, now(), limit + 1) as QueueItemRow[];
    return { items: rows.slice(0, limit).map(queueItemToWire), more: rows.length > limit };
  });

  app.post("/v1/queue/ack", auth, async (req) => {
    const { userId, deviceId } = me(req);
    const { ids } = parse(z.object({ ids: z.array(z.number().int().positive()).min(1).max(500) }), req.body);
    const removed = acknowledge(ctx, userId, deviceId, ids);
    return { removed };
  });
}

/**
 * Delete acknowledged queue items (only the caller's own) and emit "delivered" receipts to the
 * sender's devices for DM/group messages. Receipts are routing metadata, not content.
 */
export function acknowledge(ctx: AppContext, userId: string, deviceId: string, ids: number[]): number {
  const { db } = ctx;
  const t = now();
  const receipts: QueueItemRow[] = [];
  let removed = 0;
  db.transaction(() => {
    const del = db.prepare("DELETE FROM delivery_queue WHERE id = ? AND recipient_device_id = ? RETURNING *");
    const convType = db.prepare("SELECT type FROM conversations WHERE id = ?");
    const senderDevices = db.prepare("SELECT d.id, u.home_server_id FROM devices d JOIN users u ON u.id = d.user_id WHERE d.user_id = ? AND d.revoked_at IS NULL");
    const insReceipt = db.prepare(
      `INSERT OR IGNORE INTO delivery_queue (recipient_device_id, kind, conversation_id, message_id, sender_user_id, sender_device_id, payload, created_at, expires_at)
       VALUES (?, 'receipt', ?, ?, ?, ?, ?, ?, ?) RETURNING *`,
    );
    for (const id of ids) {
      const row = del.get(id, deviceId) as QueueItemRow | undefined;
      if (!row) continue;
      removed++;
      if (row.kind !== "envelope" || row.sender_user_id === userId) continue;
      const cls = (JSON.parse(row.payload) as { cls?: string }).cls;
      if (cls !== "message") continue;
      const type = (convType.get(row.conversation_id) as { type: string } | undefined)?.type;
      if (type !== "DM" && type !== "GROUP") continue;
      const payload = JSON.stringify({ status: "delivered", at: t });
      for (const d of senderDevices.all(row.sender_user_id) as { id: string; home_server_id: string | null }[]) {
        if (d.home_server_id) {
          // The sender is on another server: the receipt travels back through federation (best effort: a full
          // outbox drops the receipt rather than failing this acknowledgement).
          try {
            ctx.federation?.routeRemote(d.home_server_id, {
            key: `rcpt:${row.message_id}:${deviceId}:${d.id}`,
            kind: "receipt",
            conversationId: row.conversation_id,
            messageId: row.message_id,
            senderUserId: userId,
            senderDeviceId: deviceId,
            recipientDeviceId: d.id,
            payload,
            createdAt: t,
            expiresAt: t + ctx.config.queueTtlSeconds * 1000,
            });
          } catch {
            /* receipt dropped */
          }
          continue;
        }
        const r = insReceipt.get(d.id, row.conversation_id, row.message_id, userId, deviceId, payload, t, t + ctx.config.queueTtlSeconds * 1000) as
          | QueueItemRow
          | undefined;
        if (r) receipts.push(r);
      }
    }
  })();
  for (const r of receipts) ctx.hub.pushQueued(r.recipient_device_id, [r]);
  return removed;
}
