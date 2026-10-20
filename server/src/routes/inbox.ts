import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { type AppContext, me, parse, requireAuth } from "../context.js";
import * as meta from "../inbox/meta.js";
import { HANDLING, INBOX_PROVIDERS, PRIORITIES, STATUSES, zInboxSettings } from "../inbox/model.js";
import { inboxPlatformHealth, inboxReport } from "../inbox/report.js";
import { ApiError, notFound } from "../util.js";

/**
 * The Unified Inbox's API (docs/UNIFIED_INBOX.md). Meta posts every product's webhooks to one URL; each delivery is
 * checked against the app secret before it's stored. Everything else needs a signed-in member with the inbox permission.
 */
const WEBHOOK_BODY_LIMIT = 2 * 1024 * 1024;

export default async function inboxRoutes(app: FastifyInstance, ctx: AppContext) {
  const auth = { preHandler: requireAuth(ctx) };
  const inbox = ctx.inbox;
  const zOrg = z.object({ orgId: z.string().max(64) });
  const zId = z.object({ id: z.string().max(64) });
  const zOrgId = z.object({ orgId: z.string().max(64), id: z.string().max(64) });
  const zConsent = z.object({ consent: z.boolean() });

  // ───────── Meta webhooks ─────────
  await app.register(async (scope) => {
    scope.removeAllContentTypeParsers();
    scope.addContentTypeParser("*", { parseAs: "buffer", bodyLimit: WEBHOOK_BODY_LIMIT }, (_req, body, done) => done(null, body));

    /** Meta's subscription check: echo the challenge only for our verify token. */
    scope.get("/v1/inbox/webhooks/meta", async (req, reply) => {
      const q = req.query as Record<string, string | undefined>;
      const token = ctx.config.inbox.metaVerifyToken;
      if (q["hub.mode"] !== "subscribe" || !token || q["hub.verify_token"] !== token || !q["hub.challenge"]) return reply.code(403).send("forbidden");
      return reply.type("text/plain").send(String(q["hub.challenge"]).slice(0, 200));
    });

    scope.post("/v1/inbox/webhooks/meta", { config: { rateLimit: { max: 6000, timeWindow: "1 minute" } } }, async (req, reply) => {
      const raw = req.body instanceof Buffer ? req.body : Buffer.alloc(0);
      let payload: unknown;
      try {
        payload = JSON.parse(raw.toString("utf8"));
      } catch {
        return reply.code(400).send({ error: { code: "bad_request", message: "Not JSON" } });
      }
      const provider = meta.providerOf(payload);
      if (!provider) return reply.code(200).send("ignored"); // not a product the inbox handles
      const secret = provider === "instagram" ? ctx.config.inbox.instagramAppSecret : ctx.config.inbox.metaAppSecret;
      if (!meta.verifySignature(raw, req.headers["x-hub-signature-256"] as string | undefined, secret)) {
        ctx.metrics.recordProvider("inbox.webhook", new Error("bad_signature"));
        return reply.code(401).send({ error: { code: "bad_signature", message: "Signature doesn't match" } });
      }
      if (!ctx.config.integrations.keyring) return reply.code(503).send({ error: { code: "inbox_needs_key", message: "Not configured" } });
      inbox.worker.accept(provider, raw);
      ctx.metrics.recordProvider("inbox.webhook");
      if (ctx.config.inbox.tickMs > 0) setImmediate(() => void inbox.worker.tick());
      return reply.code(200).send("EVENT_RECEIVED");
    });
  });

  // ───────── Setup (inbox admins) ─────────
  app.get("/v1/orgs/:orgId/inbox/providers", auth, async (req) => {
    const { orgId } = parse(zOrg, req.params);
    return { providers: inbox.providers(orgId, me(req).userId) };
  });

  app.get("/v1/orgs/:orgId/inbox/accounts", auth, async (req) => {
    const { orgId } = parse(zOrg, req.params);
    return { accounts: inbox.accounts(orgId, me(req).userId) };
  });

  app.post("/v1/orgs/:orgId/inbox/accounts", { ...auth, config: { rateLimit: { max: 20, timeWindow: "1 minute" } } }, async (req) => {
    const { orgId } = parse(zOrg, req.params);
    const body = parse(
      z.object({
        provider: z.enum(INBOX_PROVIDERS),
        externalId: z.string().trim().max(64).regex(/^[A-Za-z0-9_.-]*$/).default(""),
        token: z.string().trim().min(20).max(4096),
        businessAccountId: z.string().trim().max(64).regex(/^\d*$/).optional(),
        tokenExpiresInDays: z.number().int().min(1).max(365).optional(),
      }),
      req.body,
    );
    return { account: await inbox.connect(orgId, me(req).userId, body) };
  });

  app.delete("/v1/orgs/:orgId/inbox/accounts/:id", auth, async (req) => {
    const p = parse(zOrgId, req.params);
    inbox.disconnect(p.orgId, me(req).userId, p.id);
    return { ok: true };
  });

  app.post("/v1/orgs/:orgId/inbox/accounts/:id/check", auth, async (req) => {
    const p = parse(zOrgId, req.params);
    return { account: await inbox.checkHealth(p.orgId, me(req).userId, p.id) };
  });

  app.get("/v1/orgs/:orgId/inbox/accounts/:id/templates", auth, async (req) => {
    const p = parse(zOrgId, req.params);
    return { templates: await inbox.templates(p.orgId, me(req).userId, p.id) };
  });

  /** People who can work in the inbox (for assigning), with their display names. */
  app.get("/v1/orgs/:orgId/inbox/agents", auth, async (req) => {
    const { orgId } = parse(zOrg, req.params);
    inbox.requireView(orgId, me(req).userId);
    const ids = inbox.agents(orgId);
    const name = ctx.db.prepare("SELECT display_name FROM users WHERE id = ?");
    return { agents: ids.map((id) => ({ id, name: (name.get(id) as { display_name: string } | undefined)?.display_name ?? id, canReply: ctx.authz.can(orgId, id, "inbox.reply") })) };
  });

  app.get("/v1/orgs/:orgId/inbox/queues", auth, async (req) => {
    const { orgId } = parse(zOrg, req.params);
    return { queues: inbox.queues(orgId, me(req).userId) };
  });

  const zQueue = z.object({ name: z.string().trim().min(1).max(60), memberIds: z.array(z.string().max(64)).max(200).default([]) });
  app.post("/v1/orgs/:orgId/inbox/queues", auth, async (req) => {
    const { orgId } = parse(zOrg, req.params);
    return { queue: inbox.saveQueue(orgId, me(req).userId, parse(zQueue, req.body)) };
  });

  app.put("/v1/orgs/:orgId/inbox/queues/:id", auth, async (req) => {
    const p = parse(zOrgId, req.params);
    return { queue: inbox.saveQueue(p.orgId, me(req).userId, { ...parse(zQueue, req.body), id: p.id }) };
  });

  app.delete("/v1/orgs/:orgId/inbox/queues/:id", auth, async (req) => {
    const p = parse(zOrgId, req.params);
    inbox.deleteQueue(p.orgId, me(req).userId, p.id);
    return { ok: true };
  });

  app.get("/v1/orgs/:orgId/inbox/settings", auth, async (req) => {
    const { orgId } = parse(zOrg, req.params);
    inbox.requireView(orgId, me(req).userId);
    return { settings: inbox.settings(orgId), canManage: ctx.authz.can(orgId, me(req).userId, "inbox.manage") };
  });

  app.put("/v1/orgs/:orgId/inbox/settings", auth, async (req) => {
    const { orgId } = parse(zOrg, req.params);
    return { settings: inbox.setSettings(orgId, me(req).userId, parse(zInboxSettings, req.body)) };
  });

  app.get("/v1/orgs/:orgId/inbox/report", auth, async (req) => {
    const { orgId } = parse(zOrg, req.params);
    const { days } = parse(z.object({ days: z.coerce.number().int().min(1).max(90).default(30) }), req.query);
    return { report: inboxReport(inbox, orgId, me(req).userId, days) };
  });

  // ───────── Conversations ─────────
  app.get("/v1/orgs/:orgId/inbox/conversations", auth, async (req) => {
    const { orgId } = parse(zOrg, req.params);
    const q = parse(
      z.object({
        view: z.enum(["all", "unassigned", "mine", "waiting_customer", "waiting_agent", "ai", "resolved"]).default("all"),
        provider: z.enum(INBOX_PROVIDERS).optional(),
        queueId: z.string().max(64).optional(),
        q: z.string().trim().max(100).optional(),
        before: z.coerce.number().int().min(0).optional(),
      }),
      req.query,
    );
    return inbox.list(orgId, me(req).userId, q);
  });

  app.get("/v1/inbox/conversations/:id", auth, async (req) => {
    const { id } = parse(zId, req.params);
    return inbox.detail(id, me(req).userId);
  });

  app.patch("/v1/inbox/conversations/:id", auth, async (req) => {
    const { id } = parse(zId, req.params);
    const body = parse(
      z.object({
        rev: z.number().int().min(1),
        assigneeId: z.string().max(64).nullable().optional(),
        queueId: z.string().max(64).nullable().optional(),
        status: z.enum(STATUSES).optional(),
        priority: z.enum(PRIORITIES).optional(),
        tags: z.array(z.string().trim().min(1).max(30)).max(10).optional(),
        handling: z.enum(HANDLING).optional(),
      }),
      req.body,
    );
    // "" clears the assignee or queue (apps that drop null fields send an empty string).
    const clear = (v: string | null | undefined) => (v === "" ? null : v);
    return { conversation: inbox.update(id, me(req).userId, { ...body, assigneeId: clear(body.assigneeId), queueId: clear(body.queueId) }) };
  });

  app.post("/v1/inbox/conversations/:id/claim", auth, async (req) => {
    const { id } = parse(zId, req.params);
    return { conversation: inbox.claim(id, me(req).userId) };
  });

  app.post("/v1/inbox/conversations/:id/read", auth, async (req) => {
    const { id } = parse(zId, req.params);
    inbox.markRead(id, me(req).userId);
    return { ok: true };
  });

  app.post("/v1/inbox/conversations/:id/notes", auth, async (req) => {
    const { id } = parse(zId, req.params);
    const { text } = parse(z.object({ text: z.string().trim().min(1).max(4000) }), req.body);
    return { message: inbox.note(id, me(req).userId, text) };
  });

  app.post("/v1/inbox/conversations/:id/replies", { ...auth, config: { rateLimit: { max: 60, timeWindow: "1 minute" } } }, async (req) => {
    const { id } = parse(zId, req.params);
    const body = parse(
      z.discriminatedUnion("kind", [
        // fromAiDraft: the person reviewed an AI suggestion and is sending it (counted separately in reports).
        z.object({ kind: z.literal("text"), text: z.string().trim().min(1).max(4096), fromAiDraft: z.boolean().default(false) }),
        z.object({ kind: z.literal("template"), name: z.string().trim().min(1).max(512).regex(/^[a-z0-9_]+$/), language: z.string().trim().min(2).max(15) }),
      ]),
      req.body,
    );
    const userId = me(req).userId;
    const c = inbox.conversation(id, userId);
    inbox.requireReply(c.org_id, userId);
    const m =
      body.kind === "text"
        ? inbox.queueReply(c, userId, { kind: "text", text: body.text, humanAgentTag: false }, body.fromAiDraft ? "ai_draft" : "human")
        : inbox.queueReply(c, userId, { kind: "template", name: body.name, language: body.language }, "human");
    ctx.audit({ actorId: userId, orgId: c.org_id, action: "inbox.reply", target: c.id, detail: body.kind });
    await inbox.worker.sendDue(m.id);
    const fresh = ctx.db.prepare("SELECT * FROM inbox_messages WHERE id = ?").get(m.id) as typeof m;
    return { message: inbox.messageView(fresh) };
  });

  app.get("/v1/inbox/conversations/:id/export", auth, async (req) => {
    const { id } = parse(zId, req.params);
    return inbox.export(id, me(req).userId);
  });

  /** Erases the customer's data from this workspace (inbox admins). */
  app.delete("/v1/inbox/conversations/:id", auth, async (req) => {
    const { id } = parse(zId, req.params);
    inbox.erase(id, me(req).userId);
    return { ok: true };
  });

  app.get("/v1/inbox/messages/:id/attachments/:n", { ...auth, config: { rateLimit: { max: 60, timeWindow: "1 minute" } } }, async (req, reply) => {
    const p = parse(z.object({ id: z.string().max(64), n: z.coerce.number().int().min(0).max(20) }), req.params);
    const { a, ref } = inbox.requireAttachment(p.id, p.n, me(req).userId);
    try {
      const file = await meta.media(inbox.metaCfg, a.provider as meta.MetaProvider, inbox.token(a), ref);
      return reply.header("content-type", file.mime).header("cache-control", "private, no-store").header("x-content-type-options", "nosniff").send(file.bytes);
    } catch (e) {
      if (e instanceof meta.MetaError && (e.kind === "unauthorized" || e.kind === "permission")) inbox.markFailed(a, e);
      throw new ApiError(502, "provider_failed", "The file isn't available from the provider any more", { kind: e instanceof meta.MetaError ? e.kind : "unavailable" });
    }
  });

  // ───────── AI ─────────
  app.post("/v1/inbox/conversations/:id/ai/draft", auth, async (req) => {
    const { id } = parse(zId, req.params);
    return { result: await inbox.assist.draft(id, me(req).userId, parse(zConsent, req.body).consent) };
  });

  app.post("/v1/inbox/conversations/:id/ai/summary", auth, async (req) => {
    const { id } = parse(zId, req.params);
    return { result: await inbox.assist.summary(id, me(req).userId, parse(zConsent, req.body).consent) };
  });

  app.post("/v1/inbox/conversations/:id/ai/triage", auth, async (req) => {
    const { id } = parse(zId, req.params);
    return { result: await inbox.assist.triage(id, me(req).userId, parse(zConsent, req.body).consent) };
  });

  app.post("/v1/inbox/conversations/:id/handoff", auth, async (req) => {
    const { id } = parse(zId, req.params);
    return { conversation: inbox.assist.handoff(id, me(req).userId) };
  });

  // ───────── Platform admins: health only ─────────
  app.get("/v1/admin/inbox/health", auth, async (req) => {
    if (!(ctx.config.integrations.platformAdmins as ReadonlySet<string>).has(me(req).userId)) throw notFound();
    return { health: inboxPlatformHealth(inbox) };
  });
}
