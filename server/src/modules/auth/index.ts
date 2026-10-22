import type { FastifyRequest } from "fastify";
import type { AppContext } from "../../app/context.js";
import { ApiError, unauthorized } from "../../shared/errors.js";
import { now, sha256Hex } from "../../shared/utilities/util.js";

/**
 * The auth module's public API: who a request or a WebSocket is. A bearer token is either a v1 session token (device
 * key sign-in, routes.ts) or an accounts v2 access token bound to a device (modules/accounts).
 */
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
