import type { FastifyInstance, FastifyRequest } from "fastify";
import { z } from "zod";
import type { ConversationRow } from "../authz.js";
import { type AppContext, me, parse, requireAuth } from "../context.js";
import { API_SCOPES, type ApiCaller, type ApiScope, DEV_LIMITS, WEBHOOK_EVENTS } from "../developer/service.js";
import { badRequest, conflict, notFound, now } from "../util.js";

/**
 * Developer platform routes (docs/DEVELOPER_API.md, round 7 slice C6).
 * - /v1/orgs/:orgId/api-keys and /webhooks: management, for people with `developers.manage`, signed in as usual.
 * - /ext/v1/…: the supported external API, for API keys only (never a session token; keys never work on /v1).
 */
export default async function developerRoutes(app: FastifyInstance, ctx: AppContext) {
  const auth = { preHandler: requireAuth(ctx) };
  const dev = ctx.developer;
  const zOrg = z.object({ orgId: z.string().max(64) });
  const zOne = z.object({ orgId: z.string().max(64), id: z.string().max(64) });

  // ───────────── Management ─────────────
  app.get("/v1/developer/catalog", auth, async () => ({ scopes: API_SCOPES, events: WEBHOOK_EVENTS, limits: DEV_LIMITS }));

  app.get("/v1/orgs/:orgId/api-keys", auth, async (req) => ({ keys: dev.listKeys(parse(zOrg, req.params).orgId, me(req).userId) }));

  app.post("/v1/orgs/:orgId/api-keys", auth, async (req) => {
    const body = parse(
      z.object({ name: z.string().trim().min(1).max(60), scopes: z.array(z.enum(API_SCOPES)).min(1).max(API_SCOPES.length), expiresInDays: z.number().int().min(1).max(DEV_LIMITS.keyMaxDays).default(90) }),
      req.body,
    );
    return dev.createKey(parse(zOrg, req.params).orgId, me(req).userId, body);
  });

  app.post("/v1/orgs/:orgId/api-keys/:id/rotate", auth, async (req) => {
    const p = parse(zOne, req.params);
    return dev.rotateKey(p.orgId, me(req).userId, p.id);
  });

  app.delete("/v1/orgs/:orgId/api-keys/:id", auth, async (req) => {
    const p = parse(zOne, req.params);
    dev.revokeKey(p.orgId, me(req).userId, p.id);
    return { ok: true };
  });

  const zHook = z.object({ name: z.string().trim().min(1).max(60), url: z.string().trim().max(1000), events: z.array(z.enum(WEBHOOK_EVENTS)).min(1).max(WEBHOOK_EVENTS.length) });

  app.get("/v1/orgs/:orgId/webhooks", auth, async (req) => ({ webhooks: dev.listHooks(parse(zOrg, req.params).orgId, me(req).userId) }));

  app.post("/v1/orgs/:orgId/webhooks", auth, async (req) => dev.createHook(parse(zOrg, req.params).orgId, me(req).userId, parse(zHook, req.body)));

  app.put("/v1/orgs/:orgId/webhooks/:id", auth, async (req) => {
    const p = parse(zOne, req.params);
    return { webhook: dev.updateHook(p.orgId, me(req).userId, p.id, parse(zHook.extend({ enabled: z.boolean() }), req.body)) };
  });

  app.post("/v1/orgs/:orgId/webhooks/:id/rotate-secret", auth, async (req) => {
    const p = parse(zOne, req.params);
    return dev.rotateHookSecret(p.orgId, me(req).userId, p.id);
  });

  app.post("/v1/orgs/:orgId/webhooks/:id/test", auth, async (req) => {
    const p = parse(zOne, req.params);
    return { webhook: dev.testHook(p.orgId, me(req).userId, p.id) };
  });

  app.get("/v1/orgs/:orgId/webhooks/:id/deliveries", auth, async (req) => {
    const p = parse(zOne, req.params);
    return { deliveries: dev.deliveries(p.orgId, me(req).userId, p.id) };
  });

  app.delete("/v1/orgs/:orgId/webhooks/:id", auth, async (req) => {
    const p = parse(zOne, req.params);
    dev.deleteHook(p.orgId, me(req).userId, p.id);
    return { ok: true };
  });

  // ───────────── External API (API keys) ─────────────
  const caller = (req: FastifyRequest, scope: ApiScope): ApiCaller => dev.authenticate(req.headers.authorization?.replace(/^Bearer\s+/i, ""), scope);
  const page = <T>(all: T[], cursor: string | undefined, limit: number) => {
    const offset = Math.max(0, Number.parseInt(cursor ?? "0", 10) || 0);
    const items = all.slice(offset, offset + limit);
    return { items, next: offset + items.length < all.length ? String(offset + items.length) : null };
  };
  const zPage = z.object({ cursor: z.string().max(10).optional(), limit: z.coerce.number().int().min(1).max(100).default(50) });
  /** Channels an API key may see: public and announcement channels, never private ones. */
  const openChannel = (c: ConversationRow | undefined, orgId: string): c is ConversationRow =>
    !!c && c.type === "CHANNEL" && c.org_id === orgId && c.kind !== "PRIVATE";

  app.get("/ext/v1/workspace", async (req) => {
    const k = caller(req, "workspace:read");
    const o = ctx.db.prepare("SELECT id, name, slug, created_at FROM orgs WHERE id = ?").get(k.orgId) as { id: string; name: string; slug: string; created_at: number };
    const members = (ctx.db.prepare("SELECT COUNT(*) n FROM org_members WHERE org_id = ?").get(k.orgId) as { n: number }).n;
    return { workspace: { id: o.id, name: o.name, slug: o.slug, createdAt: o.created_at, memberCount: members } };
  });

  /** Names, usernames, roles and titles: no email, phone, presence or profile details. */
  app.get("/ext/v1/members", async (req) => {
    const k = caller(req, "members:read");
    const q = parse(zPage, req.query);
    const rows = ctx.db
      .prepare("SELECT u.id, u.username, u.display_name, m.role, m.title, m.joined_at FROM org_members m JOIN users u ON u.id = m.user_id WHERE m.org_id = ? ORDER BY m.joined_at")
      .all(k.orgId) as { id: string; username: string; display_name: string; role: string; title: string | null; joined_at: number }[];
    return page(rows.map((r) => ({ id: r.id, username: r.username, displayName: r.display_name, role: r.role, title: r.title, joinedAt: r.joined_at })), q.cursor, q.limit);
  });

  app.get("/ext/v1/channels", async (req) => {
    const k = caller(req, "channels:read");
    const q = parse(zPage, req.query);
    const rows = (ctx.db.prepare("SELECT * FROM conversations WHERE org_id = ? AND type = 'CHANNEL' AND kind != 'PRIVATE' ORDER BY name").all(k.orgId) as ConversationRow[]).map((c) => ({
      id: c.id, name: c.name, topic: c.topic, kind: c.kind, voice: !!c.voice, archived: c.archived_at !== null,
      memberCount: (ctx.db.prepare("SELECT COUNT(*) n FROM conversation_members WHERE conversation_id = ?").get(c.id) as { n: number }).n,
    }));
    return page(rows, q.cursor, q.limit);
  });

  /** Documents shared with the whole workspace only: never restricted or channel documents. */
  app.get("/ext/v1/documents", async (req) => {
    const k = caller(req, "documents:read");
    const q = parse(zPage, req.query);
    const rows = ctx.db
      .prepare("SELECT id, title, updated_at, created_at FROM documents WHERE org_id = ? AND access = 'workspace' AND archived_at IS NULL ORDER BY updated_at DESC")
      .all(k.orgId) as { id: string; title: string; updated_at: number; created_at: number }[];
    return page(rows.map((d) => ({ id: d.id, title: d.title, createdAt: d.created_at, updatedAt: d.updated_at })), q.cursor, q.limit);
  });

  app.get("/ext/v1/documents/:id", async (req) => {
    const k = caller(req, "documents:read");
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const d = ctx.db.prepare("SELECT id, title, content, updated_at FROM documents WHERE id = ? AND org_id = ? AND access = 'workspace' AND archived_at IS NULL").get(id, k.orgId) as
      | { id: string; title: string; content: string; updated_at: number }
      | undefined;
    if (!d) throw notFound("Document not found");
    return { document: { id: d.id, title: d.title, content: d.content, updatedAt: d.updated_at } };
  });

  /** Upcoming meetings in rooms that aren't private: title, times and room; never who was invited. */
  app.get("/ext/v1/meetings", async (req) => {
    const k = caller(req, "meetings:read");
    const q = parse(zPage, req.query);
    const rows = ctx.db
      .prepare(
        `SELECT m.id, m.title, m.starts_at, m.ends_at, m.time_zone, m.cancelled_at, c.id AS room_id, c.name AS room_name FROM meetings m
         JOIN conversations c ON c.id = m.room_id WHERE m.org_id = ? AND c.kind != 'PRIVATE' AND m.ends_at > ? ORDER BY m.starts_at`,
      )
      .all(k.orgId, now()) as { id: string; title: string; starts_at: number; ends_at: number; time_zone: string; cancelled_at: number | null; room_id: string; room_name: string }[];
    return page(rows.map((m) => ({ id: m.id, title: m.title, startsAt: m.starts_at, endsAt: m.ends_at, timeZone: m.time_zone, cancelled: m.cancelled_at !== null, room: { id: m.room_id, name: m.room_name } })), q.cursor, q.limit);
  });

  /**
   * Posts an update into a public channel, shown as from "API · <key name>" and labelled as not end-to-end encrypted,
   * like other app updates. An Idempotency-Key header makes a retried request post once.
   */
  app.post("/ext/v1/channels/:id/updates", async (req) => {
    const k = caller(req, "updates:write");
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const body = parse(z.object({ title: z.string().trim().min(1).max(200), text: z.string().trim().max(300).optional(), url: z.string().url().max(1000).optional() }), req.body);
    const c = ctx.authz.conversation(id);
    if (!openChannel(c, k.orgId)) throw notFound("Channel not found");
    if (c.kind !== "PUBLIC") throw badRequest("API keys post in public channels only");
    if (c.archived_at) throw conflict("This channel is archived");
    if (body.url && !body.url.startsWith("https://")) throw badRequest("Links must be https");
    const idem = String(req.headers["idempotency-key"] ?? "").slice(0, 100) || `${now()}:${Math.random()}`;
    const posted = await ctx.integrations.publishApiUpdate({ orgId: k.orgId, conversationId: c.id, keyId: k.keyId, keyName: k.name, title: body.title, text: body.text ?? null, url: body.url ?? null, dedupeKey: idem, at: now() });
    return { posted };
  });
}
