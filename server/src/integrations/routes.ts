import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import { z } from "zod";
import { type AppContext, me, parse, requireAuth } from "../context.js";
import { ApiError, notFound } from "../util.js";
import { accounts, type Integrations, lifecycle, notifications, resources } from "./index.js";
import { BROWSER_COOKIE, type BrowserOutcome, callbackUrl } from "./accounts.js";
import * as hooks from "./hooks.js";
import { zAccess } from "./policy.js";
import { SANDBOX_PEOPLE } from "./providers/sandbox.js";

/**
 * Integration routes (docs/INTEGRATIONS.md › API). Authenticated workspace routes return JSON; the browser routes
 * (OAuth begin and callback, the sandbox's consent page) return small self-contained HTML pages with a strict content
 * security policy; webhooks get the raw body so signatures are checked over the exact bytes.
 */
const zId = z.string().min(1).max(64).regex(/^[A-Za-z0-9_-]+$/);
const zConnector = z.string().min(1).max(64).regex(/^[a-z0-9-]+$/);
const zCaps = z.array(z.string().max(64)).max(32).optional();
const WEBHOOK_BODY_LIMIT = 1024 * 1024;

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);

function sendPage(reply: FastifyReply, status: number, title: string, body: string) {
  // Cocoa-like neutral page: no scripts, no external resources, never framed, never cached, no referrer (the URL held
  // a code a moment ago).
  const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(title)} · Meldiq</title>
<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#1c1714;color:#f6efe9;font:16px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:420px;margin:24px;padding:28px;border-radius:24px;background:#2a221d;box-shadow:0 10px 30px rgba(0,0,0,.35)}
h1{font-size:22px;margin:0 0 8px}p{color:#cdbfb5;margin:0 0 16px}.btn{display:inline-block;padding:12px 20px;border-radius:999px;background:linear-gradient(135deg,#f3a683,#e88ba3);color:#1c1714;text-decoration:none;font-weight:600;margin:4px 8px 4px 0}
.ghost{background:#3a2f28;color:#f6efe9}small{color:#9d8f85}</style></head><body><main>${body}</main></body></html>`;
  return reply
    .code(status)
    .header("content-type", "text/html; charset=utf-8")
    .header("content-security-policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'none'; frame-ancestors 'none'; base-uri 'none'")
    .header("x-frame-options", "DENY")
    .header("referrer-policy", "no-referrer")
    .header("cache-control", "no-store")
    .header("x-content-type-options", "nosniff")
    .send(html);
}

function readCookie(req: FastifyRequest, name: string): string | undefined {
  const header = req.headers.cookie ?? "";
  for (const part of header.split(";")) {
    const [k, ...v] = part.trim().split("=");
    if (k === name) return v.join("=");
  }
  return undefined;
}

export default async function integrationRoutes(app: FastifyInstance, ctx: AppContext, svc: Integrations) {
  const auth = { preHandler: requireAuth(ctx) };
  const k = svc.kernel;
  const P = (req: FastifyRequest) => parse(z.object({ orgId: zId, connectorId: zConnector }), req.params);
  const secure = (svc.config.publicUrl ?? "").startsWith("https://");

  // ───────────── Directory, details, policy ─────────────
  app.get("/v1/orgs/:orgId/integrations", auth, async (req) => {
    const { orgId } = parse(z.object({ orgId: zId }), req.params);
    return lifecycle.directory(k, orgId, me(req).userId);
  });
  app.get("/v1/orgs/:orgId/integrations/policy", auth, async (req) => {
    const { orgId } = parse(z.object({ orgId: zId }), req.params);
    return lifecycle.getPolicy(k, orgId, me(req).userId);
  });
  app.put("/v1/orgs/:orgId/integrations/policy", auth, async (req) => {
    const { orgId } = parse(z.object({ orgId: zId }), req.params);
    const body = parse(z.object({ baseRev: z.number().int().min(0), policy: z.unknown() }), req.body);
    return lifecycle.putPolicy(k, orgId, me(req).userId, body.baseRev, body.policy);
  });
  app.get("/v1/orgs/:orgId/integrations/activity", auth, async (req) => {
    const { orgId } = parse(z.object({ orgId: zId }), req.params);
    return notifications.activity(k, orgId, me(req).userId);
  });
  app.post("/v1/orgs/:orgId/integrations/unfurl", { ...auth, config: { rateLimit: { max: 60, timeWindow: "1 minute" } } }, async (req) => {
    const { orgId } = parse(z.object({ orgId: zId }), req.params);
    const { url } = parse(z.object({ url: z.string().url().max(2000) }), req.body);
    return resources.unfurl(k, orgId, me(req).userId, url);
  });
  app.get("/v1/orgs/:orgId/integrations/search", { ...auth, config: { rateLimit: { max: 30, timeWindow: "1 minute" } } }, async (req) => {
    const { orgId } = parse(z.object({ orgId: zId }), req.params);
    const { q } = parse(z.object({ q: z.string().trim().min(1).max(200) }), req.query);
    return resources.searchAll(k, orgId, me(req).userId, q);
  });
  app.get("/v1/orgs/:orgId/integrations/:connectorId", auth, async (req) => {
    const p = P(req);
    return lifecycle.detail(k, p.orgId, me(req).userId, p.connectorId);
  });

  // ───────────── Install lifecycle ─────────────
  app.post("/v1/orgs/:orgId/integrations/:connectorId/install", auth, async (req) => {
    const p = P(req);
    const body = parse(z.object({ capabilities: zCaps }), req.body ?? {});
    await lifecycle.install(k, p.orgId, me(req).userId, p.connectorId, body.capabilities);
    return lifecycle.detail(k, p.orgId, me(req).userId, p.connectorId);
  });
  app.post("/v1/orgs/:orgId/integrations/:connectorId/request", auth, async (req) => {
    const p = P(req);
    const body = parse(z.object({ note: z.string().trim().max(300).nullable().optional(), capabilities: zCaps }), req.body ?? {});
    await lifecycle.request(k, p.orgId, me(req).userId, p.connectorId, body.note ?? null, body.capabilities);
    ctx.hub.sendToUsers(new Set(ctx.authz.workspaceMembersWith(p.orgId, "integrations.install")), { t: "integration.updated", orgId: p.orgId, connectorId: p.connectorId });
    return lifecycle.detail(k, p.orgId, me(req).userId, p.connectorId);
  });
  app.post("/v1/orgs/:orgId/integrations/:connectorId/decline", auth, async (req) => {
    const p = P(req);
    await lifecycle.decline(k, p.orgId, me(req).userId, p.connectorId);
    return lifecycle.detail(k, p.orgId, me(req).userId, p.connectorId);
  });
  app.patch("/v1/orgs/:orgId/integrations/:connectorId", auth, async (req) => {
    const p = P(req);
    const body = parse(z.object({ baseRev: z.number().int().min(1), grantedCapabilities: zCaps, access: zAccess.optional(), enabled: z.boolean().optional() }), req.body);
    await lifecycle.configure(k, p.orgId, me(req).userId, p.connectorId, body);
    return lifecycle.detail(k, p.orgId, me(req).userId, p.connectorId);
  });
  app.delete("/v1/orgs/:orgId/integrations/:connectorId", auth, async (req) => {
    const p = P(req);
    return accounts.uninstall(k, p.orgId, me(req).userId, p.connectorId);
  });

  // ───────────── Connecting accounts ─────────────
  app.post("/v1/orgs/:orgId/integrations/:connectorId/connect", auth, async (req) => {
    const p = P(req);
    return accounts.startConnect(k, p.orgId, me(req).userId, p.connectorId);
  });
  app.get("/v1/integrations/flows/:flowId", auth, async (req) => {
    const { flowId } = parse(z.object({ flowId: zId }), req.params);
    return accounts.flowStatus(k, flowId, me(req).userId);
  });
  app.post("/v1/integrations/flows/:flowId/finish", { ...auth, config: { rateLimit: { max: 30, timeWindow: "1 minute" } } }, async (req) => {
    const { flowId } = parse(z.object({ flowId: zId }), req.params);
    const body = parse(z.object({ code: z.string().min(1).max(100) }), req.body);
    return accounts.finish(k, flowId, me(req).userId, body.code);
  });
  app.delete("/v1/orgs/:orgId/integration-accounts/:accountId", auth, async (req) => {
    const p = parse(z.object({ orgId: zId, accountId: zId }), req.params);
    return accounts.disconnect(k, p.orgId, me(req).userId, p.accountId);
  });
  app.post("/v1/orgs/:orgId/integration-accounts/:accountId/validate", auth, async (req) => {
    const p = parse(z.object({ orgId: zId, accountId: zId }), req.params);
    return accounts.validate(k, p.orgId, me(req).userId, p.accountId);
  });

  // The browser side of a sign-in: no Meldiq session here (the flow carries it), only small HTML pages.
  const browserRate = { config: { rateLimit: { max: 30, timeWindow: "1 minute" } } };
  const respond = (reply: FastifyReply, out: BrowserOutcome) => {
    if (out.kind === "redirect") {
      reply.header(
        "set-cookie",
        `${BROWSER_COOKIE}=${out.cookie}; Path=/v1/integrations/oauth; Max-Age=${Math.floor(svc.config.stateTtlMs / 1000)}; HttpOnly; SameSite=Lax${secure ? "; Secure" : ""}`,
      );
      return reply.header("referrer-policy", "no-referrer").header("cache-control", "no-store").redirect(out.location, 302);
    }
    reply.header("set-cookie", `${BROWSER_COOKIE}=; Path=/v1/integrations/oauth; Max-Age=0; HttpOnly; SameSite=Lax${secure ? "; Secure" : ""}`);
    const query = out.flowId ? new URLSearchParams({ flow: out.flowId, result: out.result, ...(out.finish ? { finish: out.finish } : {}) }) : null;
    const back = query ? `${svc.config.appReturnUrl}?${query}` : svc.config.appReturnUrl;
    return sendPage(
      reply,
      out.ok ? 200 : 400,
      out.title,
      `<h1>${esc(out.title)}</h1><p>${esc(out.message)}</p><a class="btn" href="${esc(back)}">Return to Meldiq</a><p><small>You can close this page.</small></p>`,
    );
  };
  app.get("/v1/integrations/oauth/begin/:flowId", browserRate, async (req, reply) => {
    const { flowId } = parse(z.object({ flowId: zId }), req.params);
    const { t } = parse(z.object({ t: z.string().max(100).default("") }), req.query);
    return respond(reply, await accounts.begin(k, flowId, t));
  });
  app.get("/v1/integrations/oauth/callback", browserRate, async (req, reply) => {
    const q = parse(z.object({ state: z.string().max(200).optional(), code: z.string().max(4096).optional(), error: z.string().max(100).optional() }).passthrough(), req.query);
    return respond(reply, await accounts.callback(k, q, readCookie(req, BROWSER_COOKIE)));
  });

  // ───────────── Using a connected account ─────────────
  const useRate = { ...auth, config: { rateLimit: { max: 120, timeWindow: "1 minute" } } };
  app.get("/v1/orgs/:orgId/integrations/:connectorId/search", useRate, async (req) => {
    const p = P(req);
    const q = parse(z.object({ q: z.string().max(200).default(""), cursor: z.string().max(4000).optional(), filter: z.string().max(30).optional() }), req.query);
    return resources.search(k, p.orgId, me(req).userId, p.connectorId, q.q, q.cursor ?? null, q.filter ?? null);
  });
  app.get("/v1/orgs/:orgId/integrations/:connectorId/browse", useRate, async (req) => {
    const p = P(req);
    const q = parse(z.object({ container: z.string().max(3200).optional(), cursor: z.string().max(4000).optional() }), req.query);
    return resources.browse(k, p.orgId, me(req).userId, p.connectorId, q.container ?? null, q.cursor ?? null);
  });
  app.get("/v1/orgs/:orgId/integrations/:connectorId/item", useRate, async (req) => {
    const p = P(req);
    const { id } = parse(z.object({ id: z.string().min(1).max(3200) }), req.query);
    return { resource: await resources.getResource(k, p.orgId, me(req).userId, p.connectorId, id) };
  });
  app.get("/v1/orgs/:orgId/integrations/:connectorId/details", useRate, async (req) => {
    const p = P(req);
    const { id } = parse(z.object({ id: z.string().min(1).max(3200) }), req.query);
    return resources.details(k, p.orgId, me(req).userId, p.connectorId, id);
  });
  app.post("/v1/orgs/:orgId/integrations/:connectorId/actions/:action", { ...auth, config: { rateLimit: { max: 20, timeWindow: "1 minute" } } }, async (req) => {
    const p = parse(z.object({ orgId: zId, connectorId: zConnector, action: z.string().regex(/^[a-z_]{1,40}$/) }), req.params);
    const body = parse(z.object({ resourceId: z.string().max(200).optional(), fields: z.record(z.string().max(64), z.string().max(6000)).default({}) }), req.body);
    return { resource: await resources.runAction(k, p.orgId, me(req).userId, p.connectorId, p.action, body) };
  });

  // ───────────── Workspace connections and channel notifications ─────────────
  app.get("/v1/orgs/:orgId/integrations/:connectorId/link-targets", auth, async (req) => {
    const p = P(req);
    return notifications.linkTargets(k, p.orgId, me(req).userId, p.connectorId);
  });
  app.post("/v1/orgs/:orgId/integrations/:connectorId/link", auth, async (req) => {
    const p = P(req);
    const { targetId } = parse(z.object({ targetId: z.string().min(1).max(100) }), req.body);
    return notifications.linkWorkspace(k, p.orgId, me(req).userId, p.connectorId, targetId);
  });
  app.get("/v1/orgs/:orgId/integrations/:connectorId/watchable", auth, async (req) => {
    const p = P(req);
    const { cursor } = parse(z.object({ cursor: z.string().max(10).optional() }), req.query);
    return notifications.subscriptionResources(k, p.orgId, me(req).userId, p.connectorId, cursor ?? null);
  });
  app.get("/v1/orgs/:orgId/integrations/:connectorId/subscriptions", auth, async (req) => {
    const p = P(req);
    return { subscriptions: await notifications.listSubscriptions(k, p.orgId, me(req).userId, p.connectorId) };
  });
  app.post("/v1/orgs/:orgId/integrations/:connectorId/subscriptions", auth, async (req) => {
    const p = P(req);
    const body = parse(z.object({ conversationId: zId, resource: z.string().min(1).max(200), events: z.array(z.string().max(64)).min(1).max(20) }), req.body);
    return { subscription: await notifications.createSubscription(k, p.orgId, me(req).userId, p.connectorId, body) };
  });
  app.delete("/v1/orgs/:orgId/integration-subscriptions/:subscriptionId", auth, async (req) => {
    const p = parse(z.object({ orgId: zId, subscriptionId: zId }), req.params);
    return notifications.deleteSubscription(k, p.orgId, me(req).userId, p.subscriptionId);
  });
  app.post("/v1/orgs/:orgId/integrations/:connectorId/test-event", auth, async (req) => {
    const p = P(req);
    if (p.connectorId !== "meldiq-sandbox") throw new ApiError(409, "unsupported", "Only the Meldiq Sandbox can send test events");
    const { resource } = parse(z.object({ resource: z.string().max(200).optional() }), req.body ?? {});
    return notifications.sendTestEvent(k, p.orgId, me(req).userId, resource ?? null);
  });
  // ───────────── Incoming webhooks and workflows ─────────────
  app.get("/v1/orgs/:orgId/integration-hooks", auth, async (req) => {
    const { orgId } = parse(z.object({ orgId: zId }), req.params);
    return { hooks: await hooks.listHooks(k, orgId, me(req).userId) };
  });
  app.post("/v1/orgs/:orgId/integration-hooks", auth, async (req) => {
    const { orgId } = parse(z.object({ orgId: zId }), req.params);
    const body = parse(z.object({ conversationId: zId, name: z.string().trim().min(1).max(80) }), req.body);
    return hooks.createHook(k, orgId, me(req).userId, body);
  });
  app.post("/v1/orgs/:orgId/integration-hooks/:hookId/rotate", auth, async (req) => {
    const p = parse(z.object({ orgId: zId, hookId: zId }), req.params);
    return hooks.rotateHook(k, p.orgId, me(req).userId, p.hookId);
  });
  app.delete("/v1/orgs/:orgId/integration-hooks/:hookId", auth, async (req) => {
    const p = parse(z.object({ orgId: zId, hookId: zId }), req.params);
    return hooks.deleteHook(k, p.orgId, me(req).userId, p.hookId);
  });
  app.get("/v1/orgs/:orgId/integrations/workflows", auth, async (req) => {
    const { orgId } = parse(z.object({ orgId: zId }), req.params);
    return { workflows: await notifications.workflows(k, orgId, me(req).userId) };
  });

  app.get("/v1/conversations/:id/integration-events", auth, async (req) => {
    const { id } = parse(z.object({ id: zId }), req.params);
    const q = parse(z.object({ after: z.coerce.number().int().min(0).default(0), limit: z.coerce.number().int().min(1).max(100).default(50) }), req.query);
    return { events: await notifications.channelEvents(k, id, me(req).userId, q.after, q.limit) };
  });

  // ───────────── Platform administration (never a workspace role) ─────────────
  app.get("/v1/platform/me", auth, async (req) => ({ platformAdmin: svc.config.platformAdmins.has(me(req).userId) }));
  app.get("/v1/platform/integrations", auth, async (req) => lifecycle.platformRegistry(k, me(req).userId));
  app.put("/v1/platform/integrations", auth, async (req) => {
    const body = parse(z.object({ baseRev: z.number().int().min(0), policy: z.unknown() }), req.body);
    return { policy: await lifecycle.putPlatformPolicy(k, me(req).userId, body.baseRev, body.policy) };
  });

  // ───────────── Webhooks (raw body, signature-checked) ─────────────
  await app.register(async (scope) => {
    scope.removeAllContentTypeParsers();
    scope.addContentTypeParser("*", { parseAs: "buffer", bodyLimit: WEBHOOK_BODY_LIMIT }, (_req, body, done) => done(null, body));
    scope.post("/v1/integrations/webhooks/:connectorId", { config: { rateLimit: { max: 600, timeWindow: "1 minute" } } }, async (req, reply) => {
      const { connectorId } = parse(z.object({ connectorId: zConnector }), req.params);
      const raw = Buffer.isBuffer(req.body) ? req.body : Buffer.alloc(0);
      const headers = Object.fromEntries(Object.entries(req.headers).map(([h, v]) => [h.toLowerCase(), Array.isArray(v) ? v[0] : v]));
      const out = await notifications.receiveWebhook(k, connectorId, raw, headers);
      return reply.code(out.status).send(out.body);
    });
    // A workspace's own tools posting to one of its incoming webhooks.
    scope.post("/v1/integrations/hooks/:hookId", { config: { rateLimit: { max: 600, timeWindow: "1 minute" } } }, async (req, reply) => {
      const { hookId } = parse(z.object({ hookId: z.string().max(64) }), req.params);
      const raw = Buffer.isBuffer(req.body) ? req.body : Buffer.alloc(0);
      const headers = Object.fromEntries(Object.entries(req.headers).map(([h, v]) => [h.toLowerCase(), Array.isArray(v) ? v[0] : v]));
      const out = await hooks.receiveHook(k, hookId, raw, headers);
      return reply.code(out.status).send(out.body);
    });
  });

  // ───────────── Meldiq Sandbox's simulated consent page (development and tests only) ─────────────
  if (k.sandbox) {
    const sandbox = k.sandbox;
    const zAuth = z.object({
      client_id: z.literal("meldiq-sandbox"),
      redirect_uri: z.string().max(500),
      state: z.string().min(20).max(200),
      scope: z.string().max(200).default(""),
      code_challenge: z.string().max(100).optional(),
    });
    app.get("/v1/integrations/sandbox/authorize", browserRate, async (req, reply) => {
      const r = zAuth.safeParse(req.query);
      if (!r.success || r.data.redirect_uri !== callbackUrl(k)) return sendPage(reply, 400, "Invalid request", "<h1>Invalid request</h1><p>This sandbox sign-in link isn't valid.</p>");
      const q = new URLSearchParams({ redirect_uri: r.data.redirect_uri, state: r.data.state, scope: r.data.scope, code_challenge: r.data.code_challenge ?? "" });
      const deny = `${r.data.redirect_uri}?${new URLSearchParams({ state: r.data.state, error: "access_denied" })}`;
      const people = Object.entries(SANDBOX_PEOPLE)
        .map(([key, p]) => `<a class="btn" href="/v1/integrations/sandbox/approve?${esc(`${q}&person=${key}`)}">Allow as ${esc(p.name)}</a>`)
        .join("");
      return sendPage(
        reply,
        200,
        "Meldiq Sandbox",
        `<h1>Meldiq Sandbox (test only)</h1><p>Meldiq is asking for: <b>${esc(r.data.scope || "basic access")}</b>. Everything here is made up; nothing real is connected.</p>${people}<a class="btn ghost" href="${esc(deny)}">Deny</a>`,
      );
    });
    app.get("/v1/integrations/sandbox/approve", browserRate, async (req, reply) => {
      const r = z
        .object({ redirect_uri: z.string().max(500), state: z.string().min(20).max(200), scope: z.string().max(200).default(""), code_challenge: z.string().max(100).default(""), person: z.enum(["sam", "alex"]) })
        .safeParse(req.query);
      if (!r.success || r.data.redirect_uri !== callbackUrl(k)) return sendPage(reply, 400, "Invalid request", "<h1>Invalid request</h1>");
      const code = sandbox.issueCode({ redirectUri: r.data.redirect_uri, challenge: r.data.code_challenge || null, scopes: r.data.scope, person: SANDBOX_PEOPLE[r.data.person].id });
      return reply.header("referrer-policy", "no-referrer").redirect(`${r.data.redirect_uri}?${new URLSearchParams({ state: r.data.state, code })}`, 302);
    });
  } else {
    app.get("/v1/integrations/sandbox/*", async () => {
      throw notFound();
    });
  }
}

