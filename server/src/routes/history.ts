import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { hasNewAccountLimits } from "../abuse.js";
import { type AppContext, me, parse, requireAuth } from "../context.js";
import { ApiError, now } from "../util.js";

/**
 * Encrypted message history (docs/MESSAGE_HISTORY.md). An account's devices upload each message they hold, encrypted
 * with a key derived from the account identity key (which never reaches a server), under opaque tags. Any device of
 * the account, or one restored with the recovery key, downloads and decrypts them. The server only sees how many
 * items an account has, their sizes, times and update order: never content, senders, conversation ids or message ids.
 *
 * Only the account itself can read or change its history; deleting the account deletes it (foreign keys).
 */

const TAG = /^[A-Za-z0-9_-]{16,64}$/;
const MAX_BATCH = 200;
const MAX_BATCH_BYTES = 4 * 1024 * 1024;

interface ItemRow {
  tag: string;
  conv_tag: string;
  seq: number;
  ts: number;
  body: Buffer | null;
}

export default async function historyRoutes(app: FastifyInstance, ctx: AppContext) {
  const auth = { preHandler: requireAuth(ctx) };
  const cfg = ctx.config.history;
  const { db } = ctx;

  const requireEnabled = () => {
    if (!cfg.enabled) throw new ApiError(403, "history_off", "This server doesn't keep message history.");
  };
  const newAccountBytes = ctx.config.abuse.newAccountHistoryBytes;
  /** The account's allowance: smaller while it's under new-account limits (docs/ABUSE_CONTROLS.md). */
  const maxBytesFor = (userId: string) =>
    newAccountBytes > 0 && hasNewAccountLimits(db, userId) ? Math.min(newAccountBytes, cfg.maxBytesPerUser) : cfg.maxBytesPerUser;
  const account = (userId: string) =>
    db.prepare("SELECT next_seq, items, bytes FROM history_accounts WHERE user_id = ?").get(userId) as
      | { next_seq: number; items: number; bytes: number }
      | undefined;

  app.get("/v1/history/summary", auth, async (req) => {
    const { userId } = me(req);
    const a = account(userId);
    return {
      enabled: cfg.enabled,
      items: a?.items ?? 0,
      bytes: a?.bytes ?? 0,
      seq: (a?.next_seq ?? 1) - 1,
      maxItems: cfg.maxItemsPerUser,
      maxBytes: maxBytesFor(userId),
      maxItemBytes: cfg.maxItemBytes,
    };
  });

  const putSchema = z.object({
    items: z
      .array(
        z.object({
          tag: z.string().regex(TAG),
          conv: z.string().regex(TAG),
          ts: z.number().int().min(0).max(8_640_000_000_000_000),
          // base64 ciphertext, or null to mark the item deleted (other devices then drop it too).
          body: z.string().max(Math.ceil((cfg.maxItemBytes * 4) / 3) + 4).nullable(),
        }),
      )
      .min(1)
      .max(MAX_BATCH),
  });

  // Adds or replaces items (an edited message replaces its item). All or nothing: a batch over the account's quota is
  // refused whole, with 429.
  app.put("/v1/history/items", { ...auth, bodyLimit: 6 * 1024 * 1024 }, async (req) => {
    requireEnabled();
    const { userId } = me(req);
    const { items } = parse(putSchema, req.body);
    const decoded = items.map((i) => {
      const body = i.body === null ? null : Buffer.from(i.body, "base64");
      if (body && (body.length === 0 || body.length > cfg.maxItemBytes)) throw new ApiError(413, "too_large", "A history item is too large");
      return { ...i, body };
    });
    if (decoded.reduce((n, i) => n + (i.body?.length ?? 0), 0) > MAX_BATCH_BYTES) throw new ApiError(413, "too_large", "Send fewer history items at once");
    const t = now();
    const result = db
      .transaction(() => {
        const a = account(userId) ?? { next_seq: 1, items: 0, bytes: 0 };
        let items = a.items;
        let bytes = a.bytes;
        let seq = a.next_seq;
        // A tag sent twice in one batch: the last copy counts.
        const plan = [...new Map(decoded.map((i) => [i.tag, i] as const)).values()];
        const get = db.prepare("SELECT size, body IS NULL AS gone FROM history_items WHERE user_id = ? AND tag = ?");
        for (const i of plan) {
          const old = get.get(userId, i.tag) as { size: number; gone: number } | undefined;
          if (old && !old.gone) {
            items--;
            bytes -= old.size;
          }
          if (i.body) {
            items++;
            bytes += i.body.length;
          }
        }
        if (items > cfg.maxItemsPerUser || bytes > maxBytesFor(userId)) return "account" as const;
        // Every account's history shares the server's disk: growth stops at the server-wide cap (changes that free
        // space always go through).
        if (cfg.maxTotalBytes > 0 && bytes > a.bytes) {
          const total = (db.prepare("SELECT SUM(bytes) n FROM history_accounts").get() as { n: number | null }).n ?? 0;
          if (total - a.bytes + bytes > cfg.maxTotalBytes) return "server" as const;
        }
        const upsert = db.prepare(
          `INSERT INTO history_items (user_id, tag, conv_tag, seq, ts, body, size, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
           ON CONFLICT(user_id, tag) DO UPDATE SET conv_tag = excluded.conv_tag, seq = excluded.seq, ts = excluded.ts,
             body = excluded.body, size = excluded.size, updated_at = excluded.updated_at`,
        );
        for (const p of plan) upsert.run(userId, p.tag, p.conv, seq++, p.ts, p.body, p.body?.length ?? 0, t);
        db.prepare(
          `INSERT INTO history_accounts (user_id, next_seq, items, bytes) VALUES (?, ?, ?, ?)
           ON CONFLICT(user_id) DO UPDATE SET next_seq = excluded.next_seq, items = excluded.items, bytes = excluded.bytes`,
        ).run(userId, seq, items, bytes);
        return { seq: seq - 1, items, bytes };
      })
      .immediate();
    if (result === "account") throw new ApiError(429, "quota_exceeded", "Your message history on this server is full.");
    if (result === "server") throw new ApiError(507, "history_full", "This server's message history storage is full. Try again later.");
    return result;
  });

  // Items written after [after] (a seq this device has seen), oldest write first, for a restore or to catch up with
  // the account's other devices.
  app.get("/v1/history/items", auth, async (req) => {
    requireEnabled();
    const { userId } = me(req);
    const q = parse(
      z.object({ after: z.coerce.number().int().min(0).default(0), limit: z.coerce.number().int().min(1).max(1000).default(500) }),
      req.query,
    );
    const rows = db
      .prepare("SELECT tag, conv_tag, seq, ts, body FROM history_items WHERE user_id = ? AND seq > ? ORDER BY seq LIMIT ?")
      .all(userId, q.after, q.limit + 1) as ItemRow[];
    const more = rows.length > q.limit;
    const page = rows.slice(0, q.limit);
    return {
      items: page.map((r) => ({ tag: r.tag, conv: r.conv_tag, seq: r.seq, ts: r.ts, body: r.body ? r.body.toString("base64") : null })),
      next: page.length ? page[page.length - 1].seq : q.after,
      more,
    };
  });

  // Deletes this account's whole history from the server (the person's choice in Settings). Devices keep their copies.
  app.delete("/v1/history", auth, async (req) => {
    const { userId } = me(req);
    const deleted = db
      .transaction(() => {
        const n = db.prepare("DELETE FROM history_items WHERE user_id = ?").run(userId).changes;
        db.prepare("DELETE FROM history_accounts WHERE user_id = ?").run(userId);
        return n;
      })
      .immediate();
    ctx.audit({ actorId: userId, action: "history.deleted", target: userId, detail: String(deleted) });
    return { deleted };
  });
}

/** Deletion markers only need to live until every device has caught up; then they go. */
export function purgeHistoryTombstones(ctx: AppContext, t: number): number {
  return ctx.db.prepare("DELETE FROM history_items WHERE body IS NULL AND updated_at <= ?").run(t - ctx.config.history.tombstoneDays * 86_400_000).changes;
}
