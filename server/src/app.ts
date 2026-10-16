import rateLimit from "@fastify/rate-limit";
import websocket from "@fastify/websocket";
import Fastify, { type FastifyInstance } from "fastify";
import { Authz } from "./authz.js";
import type { Config } from "./config.js";
import { type AppContext, authenticateToken, bearer } from "./context.js";
import { type DB, openDatabase } from "./db.js";
import { Hub } from "./hub.js";
import { LiveKitService } from "./livekit.js";
import attachmentRoutes from "./routes/attachments.js";
import authRoutes from "./routes/auth.js";
import callRoutes from "./routes/calls.js";
import conversationRoutes from "./routes/conversations.js";
import messageRoutes from "./routes/messages.js";
import orgRoutes from "./routes/orgs.js";
import userRoutes from "./routes/users.js";
import { ApiError, now } from "./util.js";
import wsRoutes from "./ws.js";

export const VERSION = "0.1.0";

export interface BuiltApp {
  app: FastifyInstance;
  ctx: AppContext;
}

export async function buildApp(config: Config, opts: { db?: DB; logger?: boolean } = {}): Promise<BuiltApp> {
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
  const ctx: AppContext = {
    db,
    config,
    authz: new Authz(db),
    hub: new Hub(db),
    livekit: config.livekit ? new LiveKitService(config.livekit) : null,
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
  };

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
      return auth ? `d:${auth.deviceId}` : `ip:${req.ip}`;
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
    return { ok: true, version: VERSION, time: now(), livekit: !!ctx.livekit, onlineDevices: ctx.hub.onlineDeviceCount() };
  });

  // Public, non-sensitive server info for client setup screens.
  app.get("/v1/server-info", async () => ({
    name: "Griip Message relay",
    version: VERSION,
    calling: !!ctx.livekit,
    queueTtlSeconds: config.queueTtlSeconds,
    attachmentMaxBytes: config.attachmentMaxBytes,
    attachmentTtlSeconds: config.attachmentTtlSeconds,
    encryption: "griip-envelope-v1 (HPKE X25519/HKDF-SHA256/AES-256-GCM + Ed25519)",
  }));

  for (const r of [authRoutes, userRoutes, orgRoutes, conversationRoutes, messageRoutes, callRoutes, attachmentRoutes, wsRoutes]) {
    await app.register(async (scope) => r(scope, ctx));
  }
  return { app, ctx };
}
