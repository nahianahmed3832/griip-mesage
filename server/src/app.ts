import rateLimit from "@fastify/rate-limit";
import websocket from "@fastify/websocket";
import Fastify, { type FastifyInstance } from "fastify";
import { MongoNetworkError, MongoServerSelectionError } from "mongodb";
import { networkKey } from "./abuse.js";
import { Accounts } from "./accounts/index.js";
import type { Mailer } from "./accounts/mailer.js";
import accountRoutes from "./accounts/routes.js";
import { Authz } from "./authz.js";
import { type Config, effectiveFileMaxBytes } from "./config.js";
import { federationClientRoutes, federationPeerRoutes } from "./federation/routes.js";
import { Federation } from "./federation/service.js";
import { type AppContext, authenticateRequest } from "./context.js";
import { type DB, openDatabase } from "./db.js";
import { Hub } from "./hub.js";
import { LiveKitService } from "./livekit.js";
import type { MeldiqMongo } from "./mongo/client.js";
import { openMongo } from "./mongo/index.js";
import { MongoPreferenceStore } from "./mongo/preferences.js";
import { type PushDeps, PushService } from "./push/service.js";
import { createBlobStore } from "./storage/index.js";
import attachmentRoutes from "./routes/attachments.js";
import authRoutes from "./routes/auth.js";
import callRoutes from "./routes/calls.js";
import conversationRoutes from "./routes/conversations.js";
import historyRoutes from "./routes/history.js";
import messageRoutes from "./routes/messages.js";
import orgRoutes from "./routes/orgs.js";
import roleRoutes from "./routes/roles.js";
import type { FetchLike } from "./integrations/http.js";
import { Integrations } from "./integrations/index.js";
import { Entitlements } from "./entitlements.js";
import integrationRoutes from "./integrations/routes.js";
import opsRoutes from "./ops/routes.js";
import searchRoutes from "./routes/search.js";
import workflowRoutes from "./routes/workflows.js";
import documentRoutes from "./routes/documents.js";
import aiRoutes from "./routes/ai.js";
import inboxRoutes from "./routes/inbox.js";
import experimentRoutes from "./experiments/routes.js";
import { InboxService } from "./inbox/service.js";
import { AiService } from "./ai/service.js";
import { DocumentService } from "./documents/service.js";
import workspaceC5Routes from "./routes/workspaceC5.js";
import developerRoutes from "./routes/developer.js";
import { DeveloperService } from "./developer/service.js";
import { UsageMeter } from "./usage/meter.js";
import { GroupService } from "./workspace/groups.js";
import { MeetingService } from "./workspace/meetings.js";
import { WorkflowService } from "./workflows/service.js";
import preferenceRoutes from "./preferences/routes.js";
import reminderRoutes from "./reminders/routes.js";
import { ReminderService } from "./reminders/service.js";
import { FcmService } from "./push/fcm.js";
import { SqlitePreferenceStore } from "./preferences/store.js";
import pushRoutes from "./routes/push.js";
import userRoutes from "./routes/users.js";
import { Metrics } from "./ops/metrics.js";
import { ApiError, now } from "./util.js";
import wsRoutes from "./ws.js";

export const VERSION = "0.10.0";
/**
 * The client API this server speaks and the oldest client API it accepts. Clients refuse a server whose
 * minClientApi is newer than theirs; servers from before these fields existed count as API 1.
 */
export const API_VERSION = 2;
export const MIN_CLIENT_API = 1;
/** Version of the /v1/server-info document itself (fields are only ever added). */
export const SERVER_INFO_VERSION = 1;

export interface BuiltApp {
  app: FastifyInstance;
  ctx: AppContext;
}

export async function buildApp(
  config: Config,
  opts: {
    db?: DB;
    logger?: boolean;
    push?: PushDeps;
    mailer?: Mailer;
    mongo?: MeldiqMongo;
    /** Replaces the network for provider calls (tests: test/support/fakeProviders.ts). */
    integrationsFetch?: FetchLike;
  } = {},
): Promise<BuiltApp> {
  const app = Fastify({
    logger:
      opts.logger === false
        ? false
        : {
            level: config.logLevel,
            // Never log credentials. Request bodies are not logged at all (default), so
            // message ciphertext and keys never reach the logs either.
            redact: { paths: ["req.headers.authorization", "req.headers.cookie"], remove: true },
            // App sign-in pages carry one-time codes in the query (OAuth code and state, begin tokens): logged without it.
            serializers: {
              req: (r: { method: string; url: string; hostname?: string; ip?: string; socket?: { remotePort?: number } }) => ({
                method: r.method,
                url: r.url.startsWith("/v1/integrations/") ? r.url.split("?")[0] : r.url,
                hostname: r.hostname,
                remoteAddress: r.ip,
                remotePort: r.socket?.remotePort,
              }),
            },
          },
    trustProxy: config.trustProxy,
    bodyLimit: 256 * 1024,
  });

  const db = opts.db ?? openDatabase(config.databasePath);
  // `accounts` needs the finished context; it is set right below, before any route can run.
  const ctx = {
    db,
    config,
    authz: new Authz(db),
    hub: new Hub(db, config.abuse.wsMaxSocketsPerDevice),
    livekit: config.livekit ? new LiveKitService(config.livekit) : null,
    blobs: createBlobStore(config),
    log: app.log,
    metrics: new Metrics(),
    audit: (e) => {
      db.prepare("INSERT INTO audit_log (at, actor_id, org_id, action, target, detail) VALUES (?, ?, ?, ?, ?, ?)").run(
        now(),
        e.actorId ?? null,
        e.orgId ?? null,
        e.action,
        e.target ?? null,
        e.detail ?? null,
      );
    },
  } as AppContext;
  // MongoDB holds the domains in MONGODB_DOMAINS; it refuses to start if that would split a domain between stores.
  if (config.mongo) {
    const mongo = opts.mongo ?? (await openMongo(config.mongo, db, app.log));
    ctx.mongo = mongo;
    if (!opts.mongo) app.addHook("onClose", async () => mongo.close());
  }
  ctx.accounts = new Accounts(ctx, { mailer: opts.mailer });
  ctx.prefs = ctx.mongo?.owns("preferences") ? new MongoPreferenceStore(ctx.mongo) : new SqlitePreferenceStore(db);
  ctx.entitlements = new Entitlements(config.entitlements, db);
  ctx.usage = new UsageMeter(db);
  ctx.integrations = new Integrations({
    entitlements: ctx.entitlements,
    db,
    authz: ctx.authz,
    hub: ctx.hub,
    log: app.log,
    audit: ctx.audit,
    config: config.integrations,
    mongo: ctx.mongo,
    fetch: opts.integrationsFetch,
  });
  ctx.integrations.metrics = ctx.metrics;
  ctx.integrations.start();
  app.addHook("onClose", async () => ctx.integrations.stop());
  ctx.reminders = new ReminderService(ctx);
  ctx.reminders.start(config.reminderTickMs);
  app.addHook("onClose", async () => ctx.reminders.stop());
  ctx.developer = new DeveloperService(ctx);
  ctx.developer.start(config.workflowTickMs);
  app.addHook("onClose", async () => ctx.developer.stop());
  ctx.documents = new DocumentService(ctx);
  ctx.groups = new GroupService(ctx);
  ctx.meetings = new MeetingService(ctx);
  ctx.meetings.start(config.workflowTickMs > 0 ? 30_000 : 0);
  app.addHook("onClose", async () => ctx.meetings.stop());
  ctx.workflows = new WorkflowService(ctx);
  ctx.workflows.start(config.workflowTickMs);
  ctx.ai = new AiService(ctx);
  ctx.inbox = new InboxService(ctx, opts.integrationsFetch);
  ctx.inbox.worker.start(config.inbox.tickMs);
  app.addHook("onClose", async () => ctx.inbox.worker.stop());
  app.addHook("onClose", async () => ctx.workflows.stop());

  if (config.fcm && !config.push) ctx.hub.onQueuedOffline = (deviceId) => ctx.fcm?.queued(deviceId);
  if (config.trustProxy === true) {
    app.log.warn("TRUST_PROXY=true believes X-Forwarded-For entries clients send themselves, so per-address limits can be dodged. Set the proxy's addresses instead, e.g. TRUST_PROXY=loopback,uniquelocal (docs/ABUSE_CONTROLS.md)");
  }
  if (config.federation) ctx.federation = new Federation(ctx, config.federation);
  if (config.fcm) {
    // Firebase wake-ups (docs/PUSH_FCM.md) beside UnifiedPush; the hub's hooks call both (set before UnifiedPush's).
    const fcm = new FcmService(db, ctx.hub, config.fcm, app.log);
    fcm.metrics = ctx.metrics;
    ctx.fcm = fcm;
    app.addHook("preClose", (done) => {
      fcm.stop();
      done();
    });
  }
  if (config.push) {
    const push = new PushService(ctx, config.push, opts.push);
    ctx.push = push;
    ctx.hub.onQueuedOffline = (deviceId) => {
      push.queued(deviceId);
      ctx.fcm?.queued(deviceId);
    };
    ctx.hub.onDeviceOffline = (deviceId, openedAt) => push.disconnected(deviceId, openedAt);
    // Added before the WebSocket plugin's own preClose, which closes every socket: shutting down wakes nobody.
    app.addHook("preClose", (done) => {
      push.stop();
      done();
    });
  }

  // Every finished request, by route template (never the raw URL, which carries ids), for the health view.
  app.addHook("onResponse", async (req, reply) => {
    ctx.metrics.recordRequest(req.method, req.routeOptions?.url ?? "unmatched", reply.statusCode, reply.elapsedTime);
  });

  // Attachment uploads are streamed to disk by their route (size and quota enforced while reading),
  // so the raw request stream is passed through instead of being buffered in memory.
  app.addContentTypeParser("application/octet-stream", (_req, payload, done) => done(null, payload));
  // LiveKit webhooks: keep the raw string, the signature covers the exact bytes.
  app.addContentTypeParser("application/webhook+json", { parseAs: "string" }, (_req, body, done) => done(null, body));

  // Cross-origin browser apps (WEB_ORIGINS): only the listed origins get CORS answers; everyone else gets none, so their
  // pages can't read this API's responses. No cookies are used, so CORS here never carries ambient credentials.
  if (config.webOrigins.length) {
    const allowed = new Set(config.webOrigins);
    app.addHook("onRequest", async (req, reply) => {
      const origin = req.headers.origin;
      if (!origin || !allowed.has(origin)) return;
      reply.header("access-control-allow-origin", origin).header("vary", "Origin");
      if (req.method === "OPTIONS") {
        return reply
          .header("access-control-allow-methods", "GET, POST, PUT, PATCH, DELETE")
          .header("access-control-allow-headers", "authorization, content-type")
          .header("access-control-max-age", "600")
          .code(204)
          .send();
      }
    });
  }

  await app.register(rateLimit, {
    global: true,
    max: config.rateLimitPerMinute,
    timeWindow: "1 minute",
    keyGenerator: async (req) => {
      // Unavailable account store: limit by network for now; the route's own check answers 503.
      const auth = await authenticateRequest(ctx, req).catch(() => null);
      // Without a session: per network, an IPv4 address or an IPv6 /64 (one phone or home holds a whole /64).
      return auth ? `d:${auth.deviceId}` : `ip:${networkKey(req.ip)}`;
    },
  });
  await app.register(websocket, { options: { maxPayload: 64 * 1024 } });

  app.setErrorHandler((err, req, reply) => {
    if (err instanceof ApiError) {
      return reply.code(err.statusCode).send({ error: { code: err.code, message: err.message, details: err.details } });
    }
    // MongoDB unreachable (its domains can't be served): a clear, retryable answer, details only in the log.
    if (err instanceof MongoNetworkError || err instanceof MongoServerSelectionError) {
      req.log.error({ err: { name: err.name, message: err.message } }, "MongoDB unavailable");
      return reply.code(503).send({ error: { code: "unavailable", message: "The server can't reach its database right now. Try again in a moment." } });
    }
    const status = (err as { statusCode?: number }).statusCode ?? 500;
    if (status === 429) return reply.code(429).send({ error: { code: "rate_limited", message: "Too many requests, slow down" } });
    if (status === 413) return reply.code(413).send({ error: { code: "too_large", message: "Request is too large" } });
    if (status >= 400 && status < 500) {
      return reply.code(status).send({ error: { code: "bad_request", message: (err as Error).message } });
    }
    req.log.error({ err }, "unhandled error");
    return reply.code(500).send({ error: { code: "internal", message: "Something went wrong on the server" } });
  });

  app.get("/healthz", async (_req, reply) => {
    db.prepare("SELECT 1").get();
    // MongoDB, when the server uses it: unreachable means the domains it holds can't be served.
    const mongodb = ctx.mongo ? await ctx.mongo.ping() : null;
    if (mongodb === false) reply.code(503);
    // Public: says nothing about usage (the online-device count was removed, D28).
    return { ok: mongodb !== false, version: VERSION, time: now(), livekit: !!ctx.livekit, mongodb };
  });

  // Public, non-sensitive server info for client setup screens. Additive only: clients ignore unknown fields.
  app.get("/v1/server-info", async () => ({
    name: "Meldiq relay",
    infoVersion: SERVER_INFO_VERSION,
    api: API_VERSION,
    minClientApi: MIN_CLIENT_API,
    instanceName: config.instanceName,
    registration: config.registrationMode,
    // Federation (additive): this server's global identity, if it federates.
    federation: ctx.federation ? { domain: ctx.federation.domain, serverId: ctx.federation.serverId, protocols: ["griip-fed/1"] } : null,
    version: VERSION,
    calling: !!ctx.livekit,
    queueTtlSeconds: config.queueTtlSeconds,
    attachmentMaxBytes: config.attachmentMaxBytes,
    // Orbit 2.0: the largest file a person may send, in bytes of the file itself (apps check it before encrypting).
    fileMaxBytes: effectiveFileMaxBytes(config),
    // Orbit 2.0 accounts: whether new accounts can be verified (an email transport is set up) and the migration window.
    accounts: {
      registration: ctx.accounts.mailer.available,
      legacyLogin: config.accounts.legacy.login,
      legacyRegistration: config.accounts.legacy.registration,
      legacyMigration: config.accounts.legacy.migration,
      // Only when codes can be emailed: a required move with no email would trap people at the code step.
      legacyMigrationRequired: config.accounts.legacy.migrationRequired && ctx.accounts.mailer.available,
    },
    attachmentTtlSeconds: config.attachmentTtlSeconds,
    encryption: "griip-envelope-v1 (HPKE X25519/HKDF-SHA256/AES-256-GCM + Ed25519)",
    // What this server supports beyond API 1, so apps can say "this server needs an update" instead of failing
    // (servers before 2026-10-17 have no list). Additive: names are never reused.
    features: serverFeatures(config, ctx.blobs.direct !== null),
    // Encrypted message history and kept files (docs/MESSAGE_HISTORY.md). Says nothing about where files are stored.
    history: config.history.enabled ? { maxItemBytes: config.history.maxItemBytes } : null,
    attachmentKeepDays: config.attachmentKeepDays,
    // Push wake-ups (docs/BACKGROUND_DELIVERY.md): the push servers a device's distributor app must use.
    push: config.push ? { unifiedPushHosts: config.push.unifiedPushHosts } : null,
  }));

  for (const r of [authRoutes, userRoutes, orgRoutes, roleRoutes, conversationRoutes, messageRoutes, callRoutes, attachmentRoutes, historyRoutes, pushRoutes, preferenceRoutes, reminderRoutes, wsRoutes]) {
    await app.register(async (scope) => r(scope, ctx));
  }
  await app.register(async (scope) => integrationRoutes(scope, ctx, ctx.integrations));
  await app.register(async (scope) => opsRoutes(scope, ctx, VERSION));
  await app.register(async (scope) => searchRoutes(scope, ctx));
  await app.register(async (scope) => workflowRoutes(scope, ctx));
  await app.register(async (scope) => documentRoutes(scope, ctx));
  await app.register(async (scope) => workspaceC5Routes(scope, ctx));
  await app.register(async (scope) => developerRoutes(scope, ctx));
  await app.register(async (scope) => aiRoutes(scope, ctx));
  await app.register(async (scope) => inboxRoutes(scope, ctx));
  await app.register(async (scope) => experimentRoutes(scope, ctx));
  await app.register(async (scope) => accountRoutes(scope, ctx, ctx.accounts));
  const fed = ctx.federation;
  if (fed) {
    await app.register(async (scope) => federationPeerRoutes(scope, ctx, fed));
    await app.register(async (scope) => federationClientRoutes(scope, ctx, fed));
  }
  return { app, ctx };
}

/** Feature names in /v1/server-info (2026-10-17 onwards). */
export function serverFeatures(config: Config, directUploads = false): string[] {
  const f = [
    "profiles",
    "profile-photos",
    "privacy",
    "phone-lookup",
    "chunked-attachments",
    "call-reconnect",
    "org-delete",
    // Deleting your own account, DELETE /v2/account (docs/ACCOUNT_DELETION.md).
    "account-delete",
    // Meldiq 2.0: communities as a kind of workspace, experiments and content-free analytics (docs/product/).
    "communities",
    "experiments",
    "analytics-events",
    // Round 3: statuses with an emoji and an end time, pausing notifications, and "N online" for a conversation.
    "status-expiry",
    "notifications-pause",
    "conversation-presence",
    "accounts-v2",
    "workspaces-v2",
    "files-1g",
    "workspace-admin",
    "nav-preferences",
    // Saved messages (Later) in the personal preferences document (2026-10-19).
    "saved-items",
    "org-photos",
    // 2026-10-19: custom workspace roles (docs/WORKSPACE_ROLES.md) and the app directory (docs/INTEGRATIONS.md).
    "custom-roles",
    "integrations",
    // Round 7: universal search over server-readable content (docs/SEARCH.md).
    "search",
    // Round 7: workspace workflows (docs/WORKFLOWS.md).
    "workflows",
    // Round 7: workspace documents (docs/DOCUMENTS.md).
    "documents",
    // Round 7: workspace templates, user groups, guest end dates, scheduled meetings (docs/WORKSPACE_C5.md).
    "workspace-templates",
    "groups",
    "guest-expiry",
    "meetings",
    // Round 7: API keys, the external API and outgoing webhooks (docs/DEVELOPER_API.md).
    "developer-api",
    // Round 7: AI features, off until configured and turned on per workspace (docs/AI.md).
    "ai",
    // Round 8: the Unified Inbox for business messaging (docs/UNIFIED_INBOX.md).
    "unified-inbox",
    // Round 6: recovery codes for a lost email address (docs/ACCOUNT_RECOVERY.md).
    "recovery-codes",
    // 2026-10-21: keys backed up under the password, unlocked on a new device by an emailed code (docs/KEY_BACKUP.md).
    "key-backup",
    // Round 6: reminders kept and fired by the server (docs/REMINDERS.md).
    "reminders",
    // Round 6: voice rooms (docs/VOICE_ROOMS.md).
    "voice-rooms",
  ];
  if (config.accounts.legacy.migration) f.push("legacy-migration");
  if (directUploads) f.push("direct-upload");
  if (config.history.enabled) f.push("history");
  if (config.attachmentKeepDays > 0) f.push("attachment-keep");
  if (config.push) f.push("push-unifiedpush");
  if (config.fcm) f.push("push-fcm");
  return f;
}
