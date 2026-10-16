import fs from "node:fs";
import path from "node:path";
import { Readable, Transform } from "node:stream";
import { pipeline } from "node:stream/promises";
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
  const cfg = ctx.config;
  fs.mkdirSync(cfg.attachmentDir, { recursive: true });
  removeStalePartials(cfg.attachmentDir);
  const fileFor = (id: string) => path.join(cfg.attachmentDir, id.replace(/[^A-Za-z0-9_-]/g, ""));
  const sum = (sql: string, ...p: unknown[]) => (ctx.db.prepare(sql).get(...p) as { n: number | null }).n ?? 0;

  // Uploads in progress: bounds concurrency and the temporary disk space they can claim.
  const inFlight = { count: 0, bytes: 0, users: new Map<string, { count: number; bytes: number }>() };

  app.post("/v1/attachments", auth, async (req) => {
    const { userId } = me(req);
    const { conversationId } = parse(z.object({ conversationId: z.string().max(64) }), req.query);
    const conv = ctx.authz.requireMember(conversationId, userId);
    if (!ctx.authz.canPost(conv, userId)) throw new ApiError(403, "forbidden", "You can't post in this conversation");
    const body = req.body as Readable | undefined;
    if (!body || typeof (body as Readable).pipe !== "function") throw badRequest("Expected application/octet-stream body");
    const lengthHeader = req.headers["content-length"];
    const declared = lengthHeader === undefined ? null : Number(lengthHeader);
    if (declared !== null && (!Number.isSafeInteger(declared) || declared <= 0)) throw badRequest("Expected a non-empty body");
    if (declared !== null && declared > cfg.attachmentMaxBytes) throw new ApiError(413, "too_large", "Attachment is too large");

    // Quotas are checked before reading the body. A body of unknown length reserves the maximum size.
    const t = now();
    const reserve = declared ?? cfg.attachmentMaxBytes;
    const mine = inFlight.users.get(userId) ?? { count: 0, bytes: 0 };
    const today = sum("SELECT SUM(size) n FROM attachments WHERE uploader_user_id = ? AND created_at > ?", userId, t - 86_400_000);
    if (today + mine.bytes + reserve > cfg.attachmentUserDailyBytes) {
      throw new ApiError(429, "quota_exceeded", "You've reached today's upload limit. Try again tomorrow.");
    }
    const stored = sum("SELECT SUM(size) n FROM attachments WHERE uploader_user_id = ? AND expires_at > ?", userId, t);
    if (stored + mine.bytes + reserve > cfg.attachmentUserStoredBytes) {
      throw new ApiError(429, "quota_exceeded", "You've reached your file storage limit. Older files expire after 7 days.");
    }
    if (mine.count >= cfg.attachmentMaxConcurrentPerUser || inFlight.count >= cfg.attachmentMaxConcurrent || inFlight.bytes + reserve > cfg.attachmentMaxPendingBytes) {
      throw new ApiError(429, "too_many_uploads", "Too many uploads at once. Try again in a moment.");
    }
    const total = sum("SELECT SUM(size) n FROM attachments WHERE expires_at > ?", t);
    if (total + inFlight.bytes + reserve > cfg.attachmentTotalBytes) {
      throw new ApiError(507, "storage_full", "The server's file storage is full. Try again later.");
    }

    mine.count++;
    mine.bytes += reserve;
    inFlight.users.set(userId, mine);
    inFlight.count++;
    inFlight.bytes += reserve;
    const id = newId("a");
    const tmp = `${fileFor(id)}.part`;
    let size = 0;
    try {
      const limit = new Transform({
        transform(chunk: Buffer, _enc, cb) {
          size += chunk.length;
          if (size > cfg.attachmentMaxBytes || (declared !== null && size > declared)) cb(new ApiError(413, "too_large", "Attachment is too large"));
          else cb(null, chunk);
        },
      });
      await pipeline(body, limit, fs.createWriteStream(tmp, { mode: 0o600 }));
      if (size === 0) throw badRequest("Expected a non-empty body");
      if (declared !== null && size !== declared) throw badRequest("Upload ended early");
      await fs.promises.rename(tmp, fileFor(id));
    } catch (e) {
      await fs.promises.rm(tmp, { force: true });
      if (e instanceof ApiError) throw e;
      throw badRequest("Upload was interrupted");
    } finally {
      mine.count--;
      mine.bytes -= reserve;
      if (mine.count === 0) inFlight.users.delete(userId);
      inFlight.count--;
      inFlight.bytes -= reserve;
    }
    const expiresAt = t + cfg.attachmentTtlSeconds * 1000;
    ctx.db
      .prepare("INSERT INTO attachments (id, conversation_id, uploader_user_id, size, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)")
      .run(id, conv.id, userId, size, t, expiresAt);
    // Federated conversation: the ciphertext is pushed to the other people's servers, so they download it from
    // their own server. Queued before the message that references it, and delivered in order.
    ctx.federation?.pushAttachment(conv.id, { id, conversationId: conv.id, uploaderUserId: userId, size, expiresAt });
    return { id, size, expiresAt };
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
    reply.header("cache-control", "private, no-store");
    reply.header("accept-ranges", "bytes");
    // Resumable downloads (T2.6): a single "bytes=start-[end]" range, checked after membership.
    const range = /^bytes=(\d+)-(\d*)$/.exec(String(req.headers.range ?? ""));
    if (req.headers.range !== undefined && range) {
      const start = Number(range[1]);
      const end = range[2] === "" ? a.size - 1 : Math.min(Number(range[2]), a.size - 1);
      if (start >= a.size || start > end) {
        reply.header("content-range", `bytes */${a.size}`);
        return reply.code(416).send({ error: { code: "range_not_satisfiable", message: "Requested range is outside the file" } });
      }
      reply.code(206);
      reply.header("content-type", "application/octet-stream");
      reply.header("content-range", `bytes ${start}-${end}/${a.size}`);
      reply.header("content-length", String(end - start + 1));
      return reply.send(fs.createReadStream(file, { start, end }));
    }
    reply.header("content-type", "application/octet-stream");
    reply.header("content-length", String(a.size));
    return reply.send(fs.createReadStream(file));
  });
}

/** Deletes `.part` files left by uploads interrupted by a restart. Called once at startup, before any upload runs. */
export function removeStalePartials(dir: string): number {
  let n = 0;
  for (const f of fs.readdirSync(dir)) {
    if (!f.endsWith(".part")) continue;
    fs.rmSync(path.join(dir, f), { force: true });
    n++;
  }
  return n;
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
