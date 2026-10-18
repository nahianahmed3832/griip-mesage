import rateLimit from "@fastify/rate-limit";
import websocket from "@fastify/websocket";
import Fastify, { type FastifyInstance } from "fastify";
import { networkKey } from "./abuse.js";
import { Accounts } from "./accounts/index.js";
import type { Mailer } from "./accounts/mailer.js";
import accountRoutes from "./accounts/routes.js";
import { Authz } from "./authz.js";
import { type Config, effectiveFileMaxBytes } from "./config.js";
import { federationClientRoutes, federationPeerRoutes } from "./federation/routes.js";
import { Federation } from "./federation/service.js";
import { type AppContext, authenticateToken, bearer } from "./context.js";
import { type DB, openDatabase } from "./db.js";
import { Hub } from "./hub.js";
import { LiveKitService } from "./livekit.js";
import { type PushDeps, PushService } from "./push/service.js";
import { createBlobStore } from "./storage/index.js";
import attachmentRoutes from "./routes/attachments.js";
import authRoutes from "./routes/auth.js";
import callRoutes from "./routes/calls.js";
import conversationRoutes from "./routes/conversations.js";
import historyRoutes from "./routes/history.js";
import messageRoutes from "./routes/messages.js";
import orgRoutes from "./routes/orgs.js";
import pushRoutes from "./routes/push.js";
import userRoutes from "./routes/users.js";
import { ApiError, now } from "./util.js";
import wsRoutes from "./ws.js";

export const VERSION = "0.4.1";
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

export async function buildApp(config: Config, opts: { db?: DB; logger?: boolean; push?: PushDeps; mailer?: Mailer } = {}): Promise<BuiltApp> {
  const app = Fastify({
    logger:
      opts.logger === false
        ? false
        : {
            level: config.logLevel,
            // Never log credentials. Request bodies are not logged at all (default), so
            // message ciphertext and keys never reach the logs either.
            redact: { paths: ["req.headers.authorization", "req.headers.cookie"], remove: true },
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
  ctx.accounts = new Accounts(ctx, { mailer: opts.mailer });

  if (config.trustProxy === true) {
    app.log.warn("TRUST_PROXY=true believes X-Forwarded-For entries clients send themselves, so per-address limits can be dodged. Set the proxy's addresses instead, e.g. TRUST_PROXY=loopback,uniquelocal (docs/ABUSE_CONTROLS.md)");
  }
  if (config.federation) ctx.federation = new Federation(ctx, config.federation);
  if (config.push) {
    const push = new PushService(ctx, config.push, opts.push);
    ctx.push = push;
    ctx.hub.onQueuedOffline = (deviceId) => push.queued(deviceId);
    ctx.hub.onDeviceOffline = (deviceId, openedAt) => push.disconnected(deviceId, openedAt);
    // Added before the WebSocket plugin's own preClose, which closes every socket: shutting down wakes nobody.
    app.addHook("preClose", (done) => {
      push.stop();
      done();
    });
  }

  // Attachment uploads are streamed to disk by their route (size and quota enforced while reading),
  // so the raw request stream is passed through instead of being buffered in memory.
  app.addContentTypeParser("application/octet-stream", (_req, payload, done) => done(null, payload));
  // LiveKit webhooks: keep the raw string, the signature covers the exact bytes.
  app.addContentTypeParser("application/webhook+json", { parseAs: "string" }, (_req, body, done) => done(null, body));

  await app.register(rateLimit, {
    global: true,
    max: config.rateLimitPerMinute,
    timeWindow: "1 minute",
    keyGenerator: (req) => {
      const auth = authenticateToken(ctx, bearer(req));
      // Without a session: per network, an IPv4 address or an IPv6 /64 (one phone or home holds a whole /64).
      return auth ? `d:${auth.deviceId}` : `ip:${networkKey(req.ip)}`;
    },
  });
  await app.register(websocket, { options: { maxPayload: 64 * 1024 } });

  app.setErrorHandler((err, req, reply) => {
    if (err instanceof ApiError) {
      return reply.code(err.statusCode).send({ error: { code: err.code, message: err.message, details: err.details } });
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

  app.get("/healthz", async () => {
    db.prepare("SELECT 1").get();
    // Public: says nothing about usage (the online-device count was removed, D28).
    return { ok: true, version: VERSION, time: now(), livekit: !!ctx.livekit };
  });

  // Public, non-sensitive server info for client setup screens. Additive only: clients ignore unknown fields.
  app.get("/v1/server-info", async () => ({
    name: "Griip Message relay",
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

  for (const r of [authRoutes, userRoutes, orgRoutes, conversationRoutes, messageRoutes, callRoutes, attachmentRoutes, historyRoutes, pushRoutes, wsRoutes]) {
    await app.register(async (scope) => r(scope, ctx));
  }
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
  const f = ["profiles", "profile-photos", "privacy", "phone-lookup", "chunked-attachments", "call-reconnect", "org-delete", "accounts-v2", "workspaces-v2", "files-1g"];
  if (config.accounts.legacy.migration) f.push("legacy-migration");
  if (directUploads) f.push("direct-upload");
  if (config.history.enabled) f.push("history");
  if (config.attachmentKeepDays > 0) f.push("attachment-keep");
  if (config.push) f.push("push-unifiedpush");
  return f;
}
