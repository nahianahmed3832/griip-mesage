import type { FastifyBaseLogger, FastifyRequest } from "fastify";
import { z } from "zod";
import type { Accounts } from "./accounts/index.js";
import type { Authz } from "./authz.js";
import type { Config } from "./config.js";
import type { DB } from "./db.js";
import type { Hub } from "./hub.js";
import type { Federation } from "./federation/service.js";
import type { Entitlements } from "./entitlements.js";
import type { Integrations } from "./integrations/index.js";
import type { LiveKitService } from "./livekit.js";
import type { MeldiqMongo } from "./mongo/client.js";
import type { PreferenceStore } from "./preferences/store.js";
import type { PushService } from "./push/service.js";
import type { BlobStore } from "./storage/index.js";
import { ApiError, badRequest, now, sha256Hex, unauthorized } from "./util.js";

export interface AppContext {
  db: DB;
  config: Config;
  authz: Authz;
  hub: Hub;
  livekit: LiveKitService | null;
  /** Attachment and profile-photo bytes: the server's disk or S3 (docs/STORAGE_AND_DATA.md). */
  blobs: BlobStore;
  /** Present when FEDERATION_DOMAIN is configured (docs/FEDERATION_PROTOCOL.md). */
  federation?: Federation;
  /** Present when PUSH_UNIFIEDPUSH_HOSTS is configured (docs/BACKGROUND_DELIVERY.md). */
  push?: PushService;
  /** Orbit 2.0 email-and-password accounts (src/accounts). Set by buildApp right after the context is made. */
  accounts: Accounts;
  /** Personal navigation preferences (src/preferences): SQLite, or MongoDB when MONGODB_DOMAINS includes "preferences". */
  prefs: PreferenceStore;
  /** Present when MONGODB_URI is set: the domains in MONGODB_DOMAINS live there (docs/MONGODB.md). */
  mongo?: MeldiqMongo;
  /** Plan-based limits (src/entitlements.ts, docs/PLANS.md): none by default. */
  entitlements: Entitlements;
  /** Apps, connected accounts and their events (src/integrations, docs/INTEGRATIONS.md). */
  integrations: Integrations;
  log: FastifyBaseLogger;
  audit: (e: { actorId?: string | null; orgId?: string | null; action: string; target?: string | null; detail?: string | null }) => void;
}

export interface AuthInfo {
  userId: string;
  deviceId: string;
  /** Set when the token is an Orbit 2.0 access token: the account session it belongs to. */
  sessionId?: string;
}

declare module "fastify" {
  interface FastifyRequest {
    auth?: AuthInfo;
  }
}

export function parse<T extends z.ZodTypeAny>(schema: T, data: unknown): z.infer<T> {
  const r = schema.safeParse(data);
  if (!r.success) {
    throw badRequest(
      "Invalid request",
      r.error.issues.map((i) => ({ path: i.path.join("."), message: i.message })),
    );
  }
  return r.data;
}

/**
 * Resolve a bearer token → (user, device): a v1 session token, or an Orbit 2.0 access token whose session is bound to a
 * device. Rejects revoked devices, disabled users and ended sessions.
 */
export async function authenticateToken(ctx: AppContext, token: string | undefined): Promise<AuthInfo | null> {
  return authenticateV1Token(ctx, token) ?? (await ctx.accounts?.deviceAuth(token)) ?? null;
}

/** The authentication of a request, worked out once (the rate limiter and the route's preHandler both need it). */
const requestAuth = new WeakMap<FastifyRequest, Promise<AuthInfo | null>>();
export function authenticateRequest(ctx: AppContext, req: FastifyRequest): Promise<AuthInfo | null> {
  let p = requestAuth.get(req);
  if (!p) {
    p = authenticateToken(ctx, bearer(req)).catch((e) => {
      // The account store (MongoDB) can't be reached: say so, rather than a generic error or a sign-out.
      ctx.log.error({ err: e }, "authentication store unavailable");
      throw new ApiError(503, "unavailable", "The server can't check your session right now. Try again in a moment.");
    });
    requestAuth.set(req, p);
  }
  return p;
}

/**
 * For a connection that stays open (the WebSocket): whether what it authenticated with still holds. A v2 session is
 * checked by its id, so the access token being renewed every few minutes doesn't close the socket, while a revoked
 * session, device or account still does at once.
 */
export async function stillAuthenticated(ctx: AppContext, token: string | undefined, auth: AuthInfo): Promise<boolean> {
  if (auth.sessionId) return ctx.accounts.sessionStillValid(auth.sessionId, auth.deviceId);
  return authenticateV1Token(ctx, token) !== null;
}

function authenticateV1Token(ctx: AppContext, token: string | undefined): AuthInfo | null {
  if (!token || token.length < 20 || token.length > 200) return null;
  const row = ctx.db
    .prepare(
      `SELECT s.user_id, s.device_id, s.expires_at, d.revoked_at, u.disabled_at
       FROM sessions s JOIN devices d ON d.id = s.device_id JOIN users u ON u.id = s.user_id
       WHERE s.token_hash = ?`,
    )
    .get(sha256Hex(token)) as
    | { user_id: string; device_id: string; expires_at: number; revoked_at: number | null; disabled_at: number | null }
    | undefined;
  if (!row || row.expires_at <= now() || row.revoked_at || row.disabled_at) return null;
  return { userId: row.user_id, deviceId: row.device_id };
}

export function bearer(req: FastifyRequest): string | undefined {
  const h = req.headers.authorization;
  if (!h || !h.startsWith("Bearer ")) return undefined;
  return h.slice(7).trim();
}

export function requireAuth(ctx: AppContext) {
  return async (req: FastifyRequest) => {
    const auth = await authenticateRequest(ctx, req);
    if (!auth) throw unauthorized();
    req.auth = auth;
  };
}

export function me(req: FastifyRequest): AuthInfo {
  if (!req.auth) throw new ApiError(401, "unauthorized", "Authentication required");
  return req.auth;
}

// Shared validators
export const zId = z.string().min(3).max(64).regex(/^[A-Za-z0-9_-]+$/);
export const zB64 = z.string().min(4).max(400_000).regex(/^[A-Za-z0-9+/]+={0,2}$/);
export const zKey = z.string().length(44).regex(/^[A-Za-z0-9+/]+={0,2}$/);
export const zSig = z.string().length(88).regex(/^[A-Za-z0-9+/]+={0,2}$/);
export const zName = z.string().trim().min(1).max(80);
export const zText = z.string().trim().max(500);
