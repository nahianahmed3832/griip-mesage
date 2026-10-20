import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { Readable, Transform } from "node:stream";
import { pipeline } from "node:stream/promises";
import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { hasNewAccountLimits, HOUR_MS, SlidingWindow } from "../abuse.js";
import { type AppContext, me, parse, requireAuth } from "../context.js";
import type { BlobKind } from "../storage/index.js";
import { ApiError, badRequest, newId, notFound, now } from "../util.js";

/**
 * Encrypted attachment relay. Clients encrypt files locally (AES-256-GCM, key travels inside the
 * E2EE message), so the server only ever stores opaque ciphertext. Downloads require membership of
 * the conversation the blob was uploaded to; ids are 128-bit random and never listed.
 *
 * Bytes live in ctx.blobs (the server's disk or S3, docs/STORAGE_AND_DATA.md). An upload is received into a spool
 * file first (size and quota enforced while reading), then moved into the store; S3 checks it against its SHA-256.
 */
export default async function attachmentRoutes(app: FastifyInstance, ctx: AppContext) {
  const auth = { preHandler: requireAuth(ctx) };
  const cfg = ctx.config;
  const blobs = ctx.blobs;
  fs.mkdirSync(blobs.spoolDir, { recursive: true });
  removeStalePartials(blobs.spoolDir);
  const spoolFor = (id: string) => path.join(blobs.spoolDir, `${id.replace(/[^A-Za-z0-9_-]/g, "")}.part`);
  const sum = (sql: string, ...p: unknown[]) => (ctx.db.prepare(sql).get(...p) as { n: number | null }).n ?? 0;

  const abuse = cfg.abuse;
  // Uploads in progress: bounds concurrency and the temporary disk space they can claim. Their bytes also count for
  // their workspace and, for accounts under new-account limits, for those accounts together, so uploads running in
  // parallel can't pass those limits either.
  const inFlight = { count: 0, bytes: 0, users: new Map<string, { count: number; bytes: number }>(), orgs: new Map<string, number>(), newAccounts: 0 };
  // Uploads started per person per hour, finished or not (docs/ABUSE_CONTROLS.md › Uploads).
  const starts = new SlidingWindow(abuse.uploadStartsPerHour, HOUR_MS);
  /** A new-account allowance, never above the general one (0: no separate allowance; a general 0 means no limit). */
  const newAccountCap = (limit: number, general: number) => (limit > 0 ? (general > 0 ? Math.min(limit, general) : limit) : general);
  /** Whether [used] bytes pass a quota of [limit] bytes; 0 means no limit (the default since UI-003). */
  const exceeds = (limit: number, used: number) => limit > 0 && used > limit;

  interface Uploader {
    userId: string;
    /** The workspace of the conversation, if any. */
    orgId: string | null;
    /** Under new-account limits. */
    limited: boolean;
  }
  const uploaderOf = (userId: string, orgId: string | null): Uploader => ({ userId, orgId, limited: hasNewAccountLimits(ctx.db, userId) });

  /** Admits one more upload start this hour, or refuses with when to try again. */
  const admitStart = (userId: string, reply: { header: (k: string, v: string) => unknown }) => {
    if (starts.allows(userId)) return;
    reply.header("retry-after", String(Math.ceil(starts.retryAfterMs(userId) / 1000)));
    throw new ApiError(429, "too_many_uploads", "You've started a lot of uploads in the last hour. Try again later.");
  };

  /**
   * The upload quotas, checked before anything is received. [reserve] is the new upload's size; [streaming] the bytes
   * this server is receiving for the person right now. Open direct uploads count as if they were stored already.
   */
  const checkQuotas = (who: Uploader, reserve: number, streaming: number) => {
    const t = now();
    const { userId } = who;
    const pending = (where: string, ...p: unknown[]) => sum(`SELECT SUM(size) n FROM attachment_uploads WHERE ${where}`, ...p);
    const today =
      sum("SELECT SUM(size) n FROM attachments WHERE uploader_user_id = ? AND created_at > ?", userId, t - 86_400_000) +
      pending("user_id = ? AND created_at > ?", userId, t - 86_400_000);
    const daily = who.limited ? newAccountCap(abuse.newAccountDailyBytes, cfg.attachmentUserDailyBytes) : cfg.attachmentUserDailyBytes;
    if (exceeds(daily, today + streaming + reserve)) {
      // UI-003: on the phone a new account's first 249 MB video got "today's upload limit" while the server advertised
      // 1 GB per file. Say which allowance it is, what's left, and what lifts it.
      if (who.limited) {
        const mb = (n: number) => `${Math.floor(Math.max(n, 0) / (1024 * 1024))} MB`;
        throw new ApiError(
          429,
          "quota_exceeded",
          `New accounts can upload ${mb(daily)} a day for their first ${abuse.newAccountDays} days (${mb(daily - today - streaming)} left today). ` +
            "An invitation from a member, or an administrator, lifts this limit.",
        );
      }
      throw new ApiError(429, "quota_exceeded", "You've reached today's upload limit. Try again tomorrow.");
    }
    if (who.limited) {
      // New accounts: kept files count too, and all new accounts together share one allowance.
      const stored = sum("SELECT SUM(size) n FROM attachments WHERE uploader_user_id = ? AND expires_at > ?", userId, t) + pending("user_id = ?", userId);
      if (exceeds(newAccountCap(abuse.newAccountStoredBytes, cfg.attachmentUserStoredBytes), stored + streaming + reserve)) {
        throw new ApiError(429, "quota_exceeded", "You've reached your file storage limit. Older files expire after 7 days.");
      }
      const pool =
        sum("SELECT SUM(a.size) n FROM attachments a JOIN users u ON u.id = a.uploader_user_id WHERE u.limited_until > ? AND a.expires_at > ?", t, t) +
        sum("SELECT SUM(p.size) n FROM attachment_uploads p JOIN users u ON u.id = p.user_id WHERE u.limited_until > ?", t);
      if (exceeds(abuse.newAccountsTotalBytes, pool + inFlight.newAccounts + reserve)) {
        throw new ApiError(507, "storage_full", "File storage for new accounts is full right now. Try again later.");
      }
    } else {
      // Files kept for someone's history have their own quota (POST /v1/attachments/:id/keep).
      const stored = sum("SELECT SUM(size) n FROM attachments WHERE uploader_user_id = ? AND expires_at > ? AND kept = 0", userId, t) + pending("user_id = ?", userId);
      if (exceeds(cfg.attachmentUserStoredBytes, stored + streaming + reserve)) {
        throw new ApiError(429, "quota_exceeded", "You've reached your file storage limit. Older files expire after 7 days.");
      }
    }
    const storageLimit = who.orgId ? ctx.entitlements.limitFor("storage.bytes", who.orgId).limit : null;
    if (who.orgId && (abuse.orgStoredBytes > 0 || storageLimit !== null)) {
      const org =
        sum("SELECT SUM(a.size) n FROM attachments a JOIN conversations c ON c.id = a.conversation_id WHERE c.org_id = ? AND a.expires_at > ?", who.orgId, t) +
        sum("SELECT SUM(p.size) n FROM attachment_uploads p JOIN conversations c ON c.id = p.conversation_id WHERE c.org_id = ?", who.orgId);
      if (abuse.orgStoredBytes > 0 && org + (inFlight.orgs.get(who.orgId) ?? 0) + reserve > abuse.orgStoredBytes) {
        throw new ApiError(429, "org_quota_exceeded", "This workspace has reached its file storage limit. Older files expire after 7 days.");
      }
      // Round 7: the workspace's plan limit for stored files, if one applies (docs/USAGE_AND_PLANS.md).
      ctx.entitlements.check("storage.bytes", org + (inFlight.orgs.get(who.orgId) ?? 0), who.orgId, reserve);
    }
    const total = sum("SELECT SUM(size) n FROM attachments WHERE expires_at > ?", t) + pending("1 = 1");
    if (exceeds(cfg.attachmentTotalBytes, total + inFlight.bytes + reserve)) {
      throw new ApiError(507, "storage_full", "The server's file storage is full. Try again later.");
    }
  };

  /** Counts an upload being received (+1) or finished (-1) everywhere checkQuotas looks. */
  const track = (who: Uploader, bytes: number, sign: 1 | -1) => {
    const mine = inFlight.users.get(who.userId) ?? { count: 0, bytes: 0 };
    mine.count += sign;
    mine.bytes += sign * bytes;
    if (mine.count === 0) inFlight.users.delete(who.userId);
    else inFlight.users.set(who.userId, mine);
    inFlight.count += sign;
    inFlight.bytes += sign * bytes;
    if (who.orgId) {
      const org = (inFlight.orgs.get(who.orgId) ?? 0) + sign * bytes;
      if (org === 0) inFlight.orgs.delete(who.orgId);
      else inFlight.orgs.set(who.orgId, org);
    }
    if (who.limited) inFlight.newAccounts += sign * bytes;
  };

  app.post("/v1/attachments", auth, async (req, reply) => {
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

    // Quotas are checked before reading the body. A body of unknown length reserves the maximum size. Checking and
    // reserving happen with no await in between, so parallel uploads see each other's reservations.
    const t = now();
    const reserve = declared ?? cfg.attachmentMaxBytes;
    const who = uploaderOf(userId, conv.org_id);
    const mine = inFlight.users.get(userId) ?? { count: 0, bytes: 0 };
    admitStart(userId, reply);
    checkQuotas(who, reserve, mine.bytes);
    if (mine.count >= cfg.attachmentMaxConcurrentPerUser || inFlight.count >= cfg.attachmentMaxConcurrent || exceeds(cfg.attachmentMaxPendingBytes, inFlight.bytes + reserve)) {
      throw new ApiError(429, "too_many_uploads", "Too many uploads at once. Try again in a moment.");
    }

    starts.record(userId, t);
    track(who, reserve, 1);
    const id = newId("a");
    const tmp = spoolFor(id);
    let size = 0;
    const hash = crypto.createHash("sha256");
    try {
      const limit = new Transform({
        transform(chunk: Buffer, _enc, cb) {
          size += chunk.length;
          hash.update(chunk);
          if (size > cfg.attachmentMaxBytes || (declared !== null && size > declared)) cb(new ApiError(413, "too_large", "Attachment is too large"));
          else cb(null, chunk);
        },
      });
      try {
        await pipeline(body, limit, fs.createWriteStream(tmp, { mode: 0o600 }));
        if (size === 0) throw badRequest("Expected a non-empty body");
        if (declared !== null && size !== declared) throw badRequest("Upload ended early");
      } catch (e) {
        await fs.promises.rm(tmp, { force: true });
        if (e instanceof ApiError) throw e;
        throw badRequest("Upload was interrupted");
      }
      try {
        await blobs.putFile("attachments", id, tmp, size, hash.digest("hex"));
      } catch (e) {
        await fs.promises.rm(tmp, { force: true });
        ctx.log.error({ err: e }, "attachment store write failed");
        throw new ApiError(503, "storage_unavailable", "The server couldn't store the file. Try again in a moment.");
      }
    } finally {
      track(who, reserve, -1);
    }
    const expiresAt = t + cfg.attachmentTtlSeconds * 1000;
    ctx.db
      .prepare("INSERT INTO attachments (id, conversation_id, uploader_user_id, size, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)")
      .run(id, conv.id, userId, size, t, expiresAt);
    ctx.usage?.add(conv.org_id, "files.upload_bytes", size);
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
    reply.header("cache-control", "private, no-store");
    // Usage (round 7): bytes asked for, whether served here or from S3 (a range counts its own length).
    const orgOf = (ctx.authz.conversation(a.conversation_id)?.org_id ?? null) as string | null;
    const askedRange = /^bytes=(\d+)-(\d*)$/.exec(String(req.headers.range ?? ""));
    ctx.usage?.add(orgOf, "files.download_bytes", askedRange ? Math.max(0, (askedRange[2] === "" ? a.size - 1 : Math.min(Number(askedRange[2]), a.size - 1)) - Number(askedRange[1]) + 1) : a.size);
    // S3 with presigned downloads: after the checks above, a link valid for a few minutes (the client follows it; its
    // Range header still applies). The bucket itself stays private.
    const direct = await blobs.presignedGet("attachments", a.id);
    if (direct) return reply.redirect(direct, 302);
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
      const part = await blobs.get("attachments", a.id, { start, end });
      if (!part) throw notFound("Attachment not found or expired");
      reply.code(206);
      reply.header("content-type", "application/octet-stream");
      reply.header("content-range", `bytes ${start}-${end}/${a.size}`);
      reply.header("content-length", String(end - start + 1));
      return reply.send(part);
    }
    const all = await blobs.get("attachments", a.id);
    if (!all) throw notFound("Attachment not found or expired");
    reply.header("content-type", "application/octet-stream");
    reply.header("content-length", String(a.size));
    return reply.send(all);
  });

  // Keep a file for encrypted history (docs/MESSAGE_HISTORY.md): any member may ask for a file of their conversation to
  // stay beyond the 7-day relay window, for ATTACHMENT_KEEP_DAYS from now. It stays end-to-end encrypted; its key is
  // only in members' messages and history. Charged to the uploader's quota for kept files.
  app.post("/v1/attachments/:id/keep", auth, async (req) => {
    const { userId } = me(req);
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const a = ctx.db.prepare("SELECT * FROM attachments WHERE id = ?").get(id) as
      | { id: string; conversation_id: string; uploader_user_id: string; size: number; expires_at: number; kept: number }
      | undefined;
    const t = now();
    if (!a || a.expires_at <= t || !ctx.authz.isMember(a.conversation_id, userId)) throw notFound("Attachment not found or expired");
    if (cfg.attachmentKeepDays === 0) return { kept: false, expiresAt: a.expires_at };
    const until = t + cfg.attachmentKeepDays * 86_400_000;
    if (!a.kept) {
      const kept = sum("SELECT SUM(size) n FROM attachments WHERE uploader_user_id = ? AND kept = 1 AND expires_at > ?", a.uploader_user_id, t);
      if (exceeds(cfg.attachmentUserKeptBytes, kept + a.size)) {
        throw new ApiError(429, "quota_exceeded", "This file can't be kept on the server: its sender's storage for kept files is full.");
      }
    }
    const expiresAt = Math.max(a.expires_at, until);
    ctx.db.prepare("UPDATE attachments SET kept = 1, expires_at = ? WHERE id = ?").run(expiresAt, a.id);
    return { kept: true, expiresAt };
  });

  // ───────────── Direct uploads to S3 (2026-10-17, docs/STORAGE_AND_DATA.md › Direct uploads) ─────────────
  // 1. The app asks to upload [size] bytes of ciphertext with SHA-256 [sha256] to a conversation it may post in.
  // 2. The server checks membership and quotas, picks a random id and presigns a PUT bound to that size and hash that
  //    can't overwrite anything (or, above the multipart threshold, starts a multipart upload with per-part hashes).
  // 3. The app sends the bytes straight to S3, then asks the server to complete. 4. The server checks the stored object
  //    (size, hash) and only then records the attachment. Apps on servers storing on disk keep using POST /v1/attachments.
  const sha256B64 = z.string().regex(/^[A-Za-z0-9+/]{43}=$/, "Expected a base64 SHA-256");
  const direct = blobs.direct;
  interface UploadRow {
    id: string;
    user_id: string;
    conversation_id: string;
    size: number;
    sha256: string;
    multipart_id: string | null;
    part_size: number | null;
    expires_at: number;
  }
  const requireDirect = () => {
    if (!direct) throw new ApiError(409, "direct_upload_unavailable", "This server receives uploads itself: use POST /v1/attachments.");
    return direct;
  };
  const partCount = (u: UploadRow) => Math.ceil(u.size / (u.part_size ?? u.size));
  const partSizeOf = (u: UploadRow, n: number) => (n < partCount(u) ? u.part_size! : u.size - u.part_size! * (partCount(u) - 1));
  // Only the person who started an upload can see or continue it; anyone else gets the same 404 as for no upload.
  const loadUpload = (id: string, userId: string): UploadRow => {
    const u = ctx.db.prepare("SELECT * FROM attachment_uploads WHERE id = ?").get(id) as UploadRow | undefined;
    if (!u || u.user_id !== userId) throw notFound("Upload not found");
    if (u.expires_at <= now()) throw new ApiError(410, "upload_expired", "This upload took too long. Start it again.");
    return u;
  };
  const storeFailure = (e: unknown, what: string): never => {
    ctx.log.error({ err: e, what }, "direct upload: store request failed");
    throw new ApiError(503, "storage_unavailable", "The server couldn't reach its file storage. Try again in a moment.");
  };
  const abandon = (u: Pick<UploadRow, "id" | "multipart_id">) => {
    ctx.db.prepare("INSERT OR IGNORE INTO abandoned_uploads (id, multipart_id, attempts, next_at) VALUES (?, ?, 0, 0)").run(u.id, u.multipart_id);
  };

  app.post("/v1/attachments/uploads", auth, async (req, reply) => {
    const { userId, deviceId } = me(req);
    const d = requireDirect();
    const body = parse(z.object({ conversationId: z.string().max(64), size: z.number().int().positive(), sha256: sha256B64 }), req.body);
    const conv = ctx.authz.requireMember(body.conversationId, userId);
    if (!ctx.authz.canPost(conv, userId)) throw new ApiError(403, "forbidden", "You can't post in this conversation");
    if (body.size > cfg.attachmentMaxBytes) throw new ApiError(413, "too_large", "Attachment is too large");
    const open = sum("SELECT COUNT(*) n FROM attachment_uploads WHERE user_id = ? AND expires_at > ?", userId, now());
    if (open >= cfg.directUploads.maxPendingPerUser) throw new ApiError(429, "too_many_uploads", "Too many uploads at once. Try again in a moment.");
    admitStart(userId, reply);
    checkQuotas(uploaderOf(userId, conv.org_id), body.size, 0);
    const id = newId("a");
    const t = now();
    const multipart = body.size > d.multipartThreshold;
    const expiresAt = t + cfg.directUploads.sessionSeconds * 1000;
    // The upload is recorded before S3 is asked for anything: it then counts against the quotas and the open-upload
    // limit at once, so requests sent in parallel can't all pass the checks above while S3 answers.
    starts.record(userId, t);
    ctx.db
      .prepare(
        "INSERT INTO attachment_uploads (id, user_id, device_id, conversation_id, size, sha256, multipart_id, part_size, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
      )
      .run(id, userId, deviceId, conv.id, body.size, body.sha256, null, multipart ? d.partSize : null, t, expiresAt);
    if (multipart) {
      const multipartId = await d.createMultipart("attachments", id).catch((e) => {
        ctx.db.prepare("DELETE FROM attachment_uploads WHERE id = ?").run(id);
        return storeFailure(e, "create-multipart");
      });
      ctx.db.prepare("UPDATE attachment_uploads SET multipart_id = ? WHERE id = ?").run(multipartId, id);
      return { uploadId: id, mode: "multipart", partSize: d.partSize, partCount: Math.ceil(body.size / d.partSize), expiresAt };
    }
    const upload = await d.presignPut("attachments", id, body.size, body.sha256, cfg.directUploads.urlTtlSeconds).catch((e) => storeFailure(e, "presign"));
    return { uploadId: id, mode: "single", upload, expiresAt };
  });

  // A fresh URL for a single upload whose URL expired before the app could start it.
  app.post("/v1/attachments/uploads/:id/url", auth, async (req) => {
    const d = requireDirect();
    const u = loadUpload(parse(z.object({ id: z.string().max(64) }), req.params).id, me(req).userId);
    if (u.multipart_id) throw badRequest("This is a multipart upload: ask for its parts");
    return { upload: await d.presignPut("attachments", u.id, u.size, u.sha256, cfg.directUploads.urlTtlSeconds).catch((e) => storeFailure(e, "presign")) };
  });

  // URLs for parts of a multipart upload: each bound to its exact size and SHA-256.
  app.post("/v1/attachments/uploads/:id/parts", auth, async (req) => {
    const d = requireDirect();
    const u = loadUpload(parse(z.object({ id: z.string().max(64) }), req.params).id, me(req).userId);
    if (!u.multipart_id) throw badRequest("This is a single upload: ask for its URL");
    const { parts } = parse(
      z.object({ parts: z.array(z.object({ n: z.number().int().min(1).max(10_000), size: z.number().int().positive(), sha256: sha256B64 })).min(1).max(100) }),
      req.body,
    );
    for (const p of parts) {
      if (p.n > partCount(u) || p.size !== partSizeOf(u, p.n)) throw badRequest(`Part ${p.n} must be ${p.n > partCount(u) ? "within the upload" : `${partSizeOf(u, p.n)} bytes`}`);
    }
    const signed = await Promise.all(
      parts.map(async (p) => ({ n: p.n, upload: await d.presignPart("attachments", u.id, u.multipart_id!, p.n, p.size, p.sha256, cfg.directUploads.urlTtlSeconds) })),
    ).catch((e) => storeFailure(e, "presign-part"));
    return { parts: signed };
  });

  // What the store already has of a multipart upload (an app resuming after an interruption).
  app.get("/v1/attachments/uploads/:id/parts", auth, async (req) => {
    const d = requireDirect();
    const u = loadUpload(parse(z.object({ id: z.string().max(64) }), req.params).id, me(req).userId);
    if (!u.multipart_id) throw badRequest("This is a single upload");
    const have = await d.listParts("attachments", u.id, u.multipart_id).catch((e) => storeFailure(e, "list-parts"));
    return { parts: have.map((p) => ({ n: p.partNumber, etag: p.etag, size: p.size, sha256: p.sha256 })) };
  });

  // Checks the stored object and records the attachment. Safe to repeat: a completed upload answers the same again.
  app.post("/v1/attachments/uploads/:id/complete", auth, async (req) => {
    const { userId } = me(req);
    const d = requireDirect();
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const done = ctx.db.prepare("SELECT id, size, expires_at FROM attachments WHERE id = ? AND uploader_user_id = ?").get(id, userId) as
      | { id: string; size: number; expires_at: number }
      | undefined;
    if (done) return { id: done.id, size: done.size, expiresAt: done.expires_at };
    const u = loadUpload(id, userId);
    const body = parse(z.object({ parts: z.array(z.object({ n: z.number().int().min(1), etag: z.string().max(200), sha256: sha256B64 })).max(10_000).optional() }), req.body ?? {});
    // Membership is checked again: someone removed while uploading can't add a file afterwards.
    const conv = ctx.authz.conversation(u.conversation_id);
    if (!conv || !ctx.authz.isMember(conv.id, userId) || !ctx.authz.canPost(conv, userId)) {
      ctx.db.prepare("DELETE FROM attachment_uploads WHERE id = ?").run(u.id);
      abandon(u);
      throw new ApiError(403, "forbidden", "You can't post in this conversation any more");
    }
    if (u.multipart_id) {
      const parts = body.parts ?? [];
      const expected = partCount(u);
      if (parts.length !== expected || parts.some((p, i) => p.n !== i + 1)) throw badRequest(`Send all ${expected} parts, in order`);
      const have = new Map((await d.listParts("attachments", u.id, u.multipart_id).catch((e) => storeFailure(e, "list-parts"))).map((p) => [p.partNumber, p]));
      for (const p of parts) {
        const s3 = have.get(p.n);
        if (!s3 || s3.etag !== p.etag || s3.size !== partSizeOf(u, p.n) || (s3.sha256 && s3.sha256 !== p.sha256)) {
          throw new ApiError(409, "upload_incomplete", `Part ${p.n} isn't uploaded yet, or differs. Upload it again.`);
        }
      }
      await d
        .completeMultipart("attachments", u.id, u.multipart_id, parts.map((p) => ({ partNumber: p.n, etag: p.etag, sha256: p.sha256 })))
        .catch((e) => storeFailure(e, "complete-multipart"));
    }
    const stored = await d.head("attachments", u.id).catch((e) => storeFailure(e, "head"));
    if (!stored) throw new ApiError(409, "not_uploaded", "The file hasn't reached the server's storage yet. Upload it again.");
    // The presigned request already bound size and hash; this catches anything else (a misbehaving store or app).
    if (stored.size !== u.size || (!u.multipart_id && stored.sha256 !== u.sha256)) {
      ctx.db.prepare("DELETE FROM attachment_uploads WHERE id = ?").run(u.id);
      abandon(u);
      throw new ApiError(400, "upload_corrupt", "The stored file doesn't match what was announced. Send it again.");
    }
    const t = now();
    const expiresAt = t + cfg.attachmentTtlSeconds * 1000;
    // Exactly one of this and the expiry job wins: the attachment exists only if this upload session was still open.
    const recorded = ctx.db.transaction(() => {
      if (ctx.db.prepare("DELETE FROM attachment_uploads WHERE id = ?").run(u.id).changes === 0) return false;
      ctx.db
        .prepare("INSERT INTO attachments (id, conversation_id, uploader_user_id, size, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)")
        .run(u.id, conv.id, userId, u.size, t, expiresAt);
      return true;
    })();
    if (!recorded) throw new ApiError(410, "upload_expired", "This upload took too long. Start it again.");
    ctx.usage?.add(conv.org_id, "files.upload_bytes", u.size);
    ctx.federation?.pushAttachment(conv.id, { id: u.id, conversationId: conv.id, uploaderUserId: userId, size: u.size, expiresAt });
    return { id: u.id, size: u.size, expiresAt };
  });

  // The app gave up (cancelled, or failed for good): whatever reached the store is removed.
  app.delete("/v1/attachments/uploads/:id", auth, async (req) => {
    const u = loadUpload(parse(z.object({ id: z.string().max(64) }), req.params).id, me(req).userId);
    ctx.db.prepare("DELETE FROM attachment_uploads WHERE id = ?").run(u.id);
    abandon(u);
    await processAbandonedUploads(ctx).catch(() => 0);
    return { ok: true };
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
  for (const r of rows) queueBlobDelete(ctx, "attachments", r.id);
  return rows.length;
}

/**
 * Schedules stored bytes for deletion once their row is gone (nobody can fetch them any more). The retention job
 * deletes them from the disk or S3 and retries failures with a growing delay.
 */
export function queueBlobDelete(ctx: AppContext, kind: BlobKind, id: string) {
  ctx.db.prepare("INSERT OR IGNORE INTO pending_blob_deletes (kind, blob_id, attempts, next_at) VALUES (?, ?, 0, 0)").run(kind, id);
}

/** Deletes stored bytes now; if the store can't be reached, the retention job retries. */
export async function deleteBlobNow(ctx: AppContext, kind: BlobKind, id: string): Promise<void> {
  try {
    await ctx.blobs.delete(kind, id);
  } catch {
    queueBlobDelete(ctx, kind, id);
  }
}

export async function processBlobDeletes(ctx: AppContext, limit = 500): Promise<number> {
  const t = now();
  const due = ctx.db.prepare("SELECT kind, blob_id, attempts FROM pending_blob_deletes WHERE next_at <= ? LIMIT ?").all(t, limit) as {
    kind: BlobKind;
    blob_id: string;
    attempts: number;
  }[];
  let done = 0;
  for (const d of due) {
    try {
      await ctx.blobs.delete(d.kind, d.blob_id);
      ctx.db.prepare("DELETE FROM pending_blob_deletes WHERE kind = ? AND blob_id = ?").run(d.kind, d.blob_id);
      done++;
    } catch (e) {
      const attempts = d.attempts + 1;
      ctx.log.warn({ err: e, kind: d.kind, attempts }, "stored file not deleted yet; will retry");
      ctx.db
        .prepare("UPDATE pending_blob_deletes SET attempts = ?, next_at = ? WHERE kind = ? AND blob_id = ?")
        .run(attempts, t + Math.min(3_600_000, 30_000 * 2 ** Math.min(attempts, 7)), d.kind, d.blob_id);
    }
  }
  return done;
}

/** Direct uploads past their session time become abandoned: their object or parts are removed by [processAbandonedUploads]. */
export function expireDirectUploads(ctx: AppContext): number {
  const rows = ctx.db.prepare("DELETE FROM attachment_uploads WHERE expires_at <= ? RETURNING id, multipart_id").all(now()) as { id: string; multipart_id: string | null }[];
  const add = ctx.db.prepare("INSERT OR IGNORE INTO abandoned_uploads (id, multipart_id, attempts, next_at) VALUES (?, ?, 0, 0)");
  for (const r of rows) add.run(r.id, r.multipart_id);
  return rows.length;
}

/** Aborts abandoned multipart uploads and deletes abandoned single uploads' objects (never a recorded attachment's). */
export async function processAbandonedUploads(ctx: AppContext, limit = 200): Promise<number> {
  const direct = ctx.blobs.direct;
  if (!direct) return 0;
  const t = now();
  const due = ctx.db.prepare("SELECT * FROM abandoned_uploads WHERE next_at <= ? LIMIT ?").all(t, limit) as { id: string; multipart_id: string | null; attempts: number }[];
  let done = 0;
  for (const a of due) {
    try {
      // A recorded attachment with this id owns the object: only an unfinished multipart upload is aborted then.
      const recorded = ctx.db.prepare("SELECT 1 FROM attachments WHERE id = ?").get(a.id);
      if (a.multipart_id) await direct.abortMultipart("attachments", a.id, a.multipart_id);
      if (!recorded) await ctx.blobs.delete("attachments", a.id);
      ctx.db.prepare("DELETE FROM abandoned_uploads WHERE id = ?").run(a.id);
      done++;
    } catch (e) {
      const attempts = a.attempts + 1;
      ctx.log.warn({ err: e, attempts }, "abandoned upload not cleaned up yet; will retry");
      ctx.db.prepare("UPDATE abandoned_uploads SET attempts = ?, next_at = ? WHERE id = ?").run(attempts, t + Math.min(3_600_000, 30_000 * 2 ** Math.min(attempts, 7)), a.id);
    }
  }
  return done;
}