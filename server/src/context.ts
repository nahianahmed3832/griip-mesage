import type { FastifyBaseLogger, FastifyRequest } from "fastify";
import { z } from "zod";
import type { Authz } from "./authz.js";
import type { Config } from "./config.js";
import type { DB } from "./db.js";
import type { Hub } from "./hub.js";
import type { Federation } from "./federation/service.js";
import type { LiveKitService } from "./livekit.js";
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
  log: FastifyBaseLogger;
  audit: (e: { actorId?: string | null; orgId?: string | null; action: string; target?: string | null; detail?: string | null }) => void;
}

export interface AuthInfo {
  userId: string;
  deviceId: string;
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

/** Resolve a bearer session token → (user, device). Rejects revoked devices and disabled users. */
export function authenticateToken(ctx: AppContext, token: string | undefined): AuthInfo | null {
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
    const auth = authenticateToken(ctx, bearer(req));
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
