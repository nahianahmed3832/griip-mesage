import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { type AppContext, me, parse, requireAuth, zName } from "../context.js";
import { keyFingerprint } from "../crypto.js";
import { notFound, now } from "../util.js";
import { userSummary } from "./auth.js";

export interface DeviceKeyRow {
  user_id: string;
  identity_key: string;
  id: string;
  signing_key: string;
  encryption_key: string;
  certificate: string;
}

/** Public key directory entries for a set of users (active devices only). */
export function deviceDirectory(ctx: AppContext, userIds: string[]) {
  if (userIds.length === 0) return [];
  const placeholders = userIds.map(() => "?").join(",");
  const rows = ctx.db
    .prepare(
      `SELECT u.id AS user_id, u.identity_key, d.id, d.signing_key, d.encryption_key, d.certificate
       FROM users u LEFT JOIN devices d ON d.user_id = u.id AND d.revoked_at IS NULL
       WHERE u.id IN (${placeholders}) AND u.disabled_at IS NULL`,
    )
    .all(...userIds) as (DeviceKeyRow & { id: string | null })[];
  const byUser = new Map<string, { userId: string; identityKey: string; devices: object[] }>();
  for (const r of rows) {
    let e = byUser.get(r.user_id);
    if (!e) byUser.set(r.user_id, (e = { userId: r.user_id, identityKey: r.identity_key, devices: [] }));
    if (r.id) {
      e.devices.push({ id: r.id, signingKey: r.signing_key, encryptionKey: r.encryption_key, certificate: r.certificate });
    }
  }
  return [...byUser.values()];
}

export default async function userRoutes(app: FastifyInstance, ctx: AppContext) {
  const auth = { preHandler: requireAuth(ctx) };

  app.get("/v1/users/me", auth, async (req) => {
    const { userId, deviceId } = me(req);
    const u = ctx.db.prepare("SELECT * FROM users WHERE id = ?").get(userId) as any;
    return {
      user: { ...userSummary(u), identityKey: u.identity_key, fingerprint: keyFingerprint(u.identity_key), createdAt: u.created_at },
      deviceId,
    };
  });

  app.patch("/v1/users/me", auth, async (req) => {
    const { userId } = me(req);
    const body = parse(
      z.object({
        displayName: zName.optional(),
        statusText: z.string().trim().max(140).nullable().optional(),
        avatarColor: z.number().int().min(0).max(15).optional(),
      }),
      req.body,
    );
    const u = ctx.db.prepare("SELECT * FROM users WHERE id = ?").get(userId) as any;
    ctx.db
      .prepare("UPDATE users SET display_name = ?, status_text = ?, avatar_color = ?, updated_at = ? WHERE id = ?")
      .run(
        body.displayName ?? u.display_name,
        body.statusText === undefined ? u.status_text : body.statusText,
        body.avatarColor ?? u.avatar_color,
        now(),
        userId,
      );
    const updated = ctx.db.prepare("SELECT * FROM users WHERE id = ?").get(userId) as any;
    return { user: userSummary(updated) };
  });

  // Find colleagues: people who share an organisation with you (prefix match on username or
  // display name), plus an exact username match for anyone (Telegram-style @username lookup).
  app.get("/v1/users/search", auth, async (req) => {
    const { userId } = me(req);
    const { q } = parse(z.object({ q: z.string().trim().min(1).max(40) }), req.query);
    const term = q.replace(/^@/, "").toLowerCase();
    const like = `${term.replace(/[%_\\]/g, (c) => "\\" + c)}%`;
    const rows = ctx.db
      .prepare(
        `SELECT DISTINCT u.* FROM users u
         WHERE u.disabled_at IS NULL AND u.id != ? AND (
           u.username = ? COLLATE NOCASE OR (
             (u.username LIKE ? ESCAPE '\\' OR lower(u.display_name) LIKE ? ESCAPE '\\' OR lower(u.display_name) LIKE ? ESCAPE '\\')
             AND EXISTS (
               SELECT 1 FROM org_members a JOIN org_members b ON a.org_id = b.org_id
               WHERE a.user_id = ? AND b.user_id = u.id AND a.role != 'GUEST'
             )
           )
         ) ORDER BY u.username LIMIT 30`,
      )
      .all(userId, term, like, like, `% ${like}`, userId) as any[];
    // Exact-username matches can be strangers: their presence stays hidden (null).
    return { users: rows.map((u) => ({ ...userSummary(u), online: ctx.authz.canSeePresence(userId, u.id) ? ctx.hub.isUserOnline(u.id) : null })) };
  });

  app.get("/v1/users/:id", auth, async (req) => {
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const u = ctx.db.prepare("SELECT * FROM users WHERE id = ? AND disabled_at IS NULL").get(id) as any;
    if (!u) throw notFound("User not found");
    const visible = ctx.authz.canSeePresence(me(req).userId, u.id);
    return {
      user: {
        ...userSummary(u),
        identityKey: u.identity_key,
        fingerprint: keyFingerprint(u.identity_key),
        online: visible ? ctx.hub.isUserOnline(u.id) : null,
        lastSeenAt: visible ? ctx.hub.lastSeenAt(u.id) : null,
      },
    };
  });

  app.post("/v1/users/devices", auth, async (req) => {
    const { userIds } = parse(z.object({ userIds: z.array(z.string().max(64)).min(1).max(500) }), req.body);
    return { users: deviceDirectory(ctx, [...new Set(userIds)]) };
  });

  // Users the caller may not see are left out (not nulled): released apps parse `online` as non-null.
  app.get("/v1/presence", auth, async (req) => {
    const { userId } = me(req);
    const { ids } = parse(z.object({ ids: z.string().max(5000) }), req.query);
    const list = [...new Set(ids.split(",").filter(Boolean))].slice(0, 200).filter((u) => ctx.authz.canSeePresence(userId, u));
    return {
      presence: list.map((u) => ({ userId: u, online: ctx.hub.isUserOnline(u), lastSeenAt: ctx.hub.lastSeenAt(u) })),
    };
  });
}
