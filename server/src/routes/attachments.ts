import fs from "node:fs";
import path from "node:path";
import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { type AppContext, me, parse, requireAuth } from "../context.js";
import { ApiError, badRequest, newId, notFound, now } from "../util.js";

/**
 * Encrypted attachment relay. Clients encrypt files locally (AES-256-GCM, key travels inside the
 * E2EE message), so the server only ever stores opaque ciphertext. Downloads require membership of
 * the conversation the blob was uploaded to; ids are 128-bit random and never listed.
 */
export default async function attachmentRoutes(app: FastifyInstance, ctx: AppContext) {
  const auth = { preHandler: requireAuth(ctx) };
  fs.mkdirSync(ctx.config.attachmentDir, { recursive: true });
  const fileFor = (id: string) => path.join(ctx.config.attachmentDir, id.replace(/[^A-Za-z0-9_-]/g, ""));

  app.post("/v1/attachments", { ...auth, bodyLimit: ctx.config.attachmentMaxBytes + 1024 }, async (req) => {
    const { userId } = me(req);
    const { conversationId } = parse(z.object({ conversationId: z.string().max(64) }), req.query);
    const conv = ctx.authz.requireMember(conversationId, userId);
    if (!ctx.authz.canPost(conv, userId)) throw new ApiError(403, "forbidden", "You can't post in this conversation");
    const body = req.body;
    if (!Buffer.isBuffer(body) || body.length === 0) throw badRequest("Expected application/octet-stream body");
    if (body.length > ctx.config.attachmentMaxBytes) throw new ApiError(413, "too_large", "Attachment is too large");
    const id = newId("a");
    const t = now();
    const expiresAt = t + ctx.config.attachmentTtlSeconds * 1000;
    const tmp = `${fileFor(id)}.part`;
    await fs.promises.writeFile(tmp, body, { mode: 0o600 });
    await fs.promises.rename(tmp, fileFor(id));
    ctx.db
      .prepare("INSERT INTO attachments (id, conversation_id, uploader_user_id, size, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)")
      .run(id, conv.id, userId, body.length, t, expiresAt);
    return { id, size: body.length, expiresAt };
  });

  app.get("/v1/attachments/:id", auth, async (req, reply) => {
    const { userId } = me(req);
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const a = ctx.db.prepare("SELECT * FROM attachments WHERE id = ?").get(id) as
      | { id: string; conversation_id: string; size: number; expires_at: number }
      | undefined;
    // Same 404 for unknown, expired and unauthorised: no oracle for guessing ids.
    if (!a || a.expires_at <= now() || !ctx.authz.isMember(a.conversation_id, userId)) throw notFound("Attachment not found or expired");
    const file = fileFor(a.id);
    if (!fs.existsSync(file)) throw notFound("Attachment not found or expired");
    reply.header("content-type", "application/octet-stream");
    reply.header("content-length", String(a.size));
    reply.header("cache-control", "private, no-store");
    return reply.send(fs.createReadStream(file));
  });
}

export function purgeExpiredAttachments(ctx: AppContext): number {
  const rows = ctx.db.prepare("DELETE FROM attachments WHERE expires_at <= ? RETURNING id").all(now()) as { id: string }[];
  for (const r of rows) {
    try {
      fs.unlinkSync(path.join(ctx.config.attachmentDir, r.id));
    } catch {
      /* already gone */
    }
  }
  return rows.length;
}
