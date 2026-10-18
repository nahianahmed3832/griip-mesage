import crypto from "node:crypto";
import { ApiError, newId, sha256Hex } from "../util.js";
import type { AccountsConfig } from "./config.js";
import type { AccountStore, ActiveSession, SessionRow } from "./store.js";

const TOKEN_BYTES = 32;
/** A refresh token replaced this recently is a retry (a lost answer, or a race), not a stolen copy. */
const REFRESH_RACE_MS = 30_000;

export interface IssuedSession {
  accessToken: string;
  accessTokenExpiresAt: number;
  refreshToken: string;
  refreshTokenExpiresAt: number;
  session: SessionRow;
}

export type SessionEnd = "session_expired" | "session_revoked" | "account_disabled";

const END_MESSAGES: Record<SessionEnd, string> = {
  session_expired: "Your session has ended. Sign in again.",
  session_revoked: "You were signed out on this device. Sign in again.",
  account_disabled: "This account is disabled.",
};

export const sessionEnded = (why: SessionEnd) => new ApiError(401, why, END_MESSAGES[why]);

const newToken = () => crypto.randomBytes(TOKEN_BYTES).toString("base64url");
/** "<session id>.<secret>": the session is found by its id, the secret is compared by hash. */
const refreshTokenFor = (sessionId: string) => `${sessionId}.${newToken()}`;
const sessionIdOf = (refreshToken: string): string | null => {
  const dot = refreshToken.indexOf(".");
  return dot > 0 ? refreshToken.slice(0, dot) : null;
};

/** Why [s] can't be used at time [t], or null when it can (access-token expiry aside). */
export function sessionProblem(s: ActiveSession, t: number): SessionEnd | null {
  if (s.revoked_at !== null) return "session_revoked";
  if (s.account_disabled_at !== null || s.user_disabled_at !== null) return "account_disabled";
  if (s.device_id !== null && s.device_revoked_at !== null) return "session_revoked";
  if (s.expires_at <= t || s.refresh_expires_at <= t) return "session_expired";
  return null;
}

/**
 * Access and refresh tokens (docs/ORBIT_2_ARCHITECTURE §2). Only SHA-256 hashes are stored. Every refresh replaces both
 * tokens; an older refresh token of the session coming back means someone copied it, so the session is revoked.
 */
export class SessionService {
  constructor(
    private readonly store: AccountStore,
    private readonly config: AccountsConfig,
  ) {}

  issue(accountId: string, label: string, deviceId: string | null, t: number): IssuedSession {
    const id = newId("s");
    const accessToken = newToken();
    const refreshToken = refreshTokenFor(id);
    const expiresAt = t + this.config.sessionMaxMs;
    const session: SessionRow = {
      id,
      account_id: accountId,
      device_id: deviceId,
      label,
      access_hash: sha256Hex(accessToken),
      access_expires_at: t + this.config.accessTokenTtlMs,
      refresh_hash: sha256Hex(refreshToken),
      previous_refresh_hash: null,
      refresh_expires_at: Math.min(t + this.config.refreshIdleMs, expiresAt),
      expires_at: expiresAt,
      created_at: t,
      refreshed_at: t,
      last_used_at: t,
      revoked_at: null,
      revoke_reason: null,
    };
    this.store.insertSession(session);
    return { accessToken, accessTokenExpiresAt: session.access_expires_at, refreshToken, refreshTokenExpiresAt: session.refresh_expires_at, session };
  }

  /**
   * Swaps a refresh token for new tokens. The token names its session, so every refresh token the session ever had is
   * recognised:
   * - the current one rotates;
   * - the one it just replaced rotates too, for [REFRESH_RACE_MS] after the swap (the app didn't get the answer, or two
   *   of its requests raced), which ends the pair handed out in between;
   * - any other one means a copy is in use: the session is revoked, and [onReuse] disconnects its device and records it.
   */
  refresh(refreshToken: string, t: number, onReuse: (s: SessionRow) => void): IssuedSession {
    const s = this.store.sessionById(sessionIdOf(refreshToken) ?? "");
    if (!s) throw sessionEnded("session_expired");
    const hash = sha256Hex(refreshToken);
    const current = hash === s.refresh_hash;
    const justReplaced = hash === s.previous_refresh_hash && t - s.refreshed_at <= REFRESH_RACE_MS;
    if (!current && !justReplaced) {
      if (s.revoked_at === null && this.store.revokeSession(s.id, "refresh_reuse", t)) onReuse(s);
      throw sessionEnded("session_revoked");
    }
    const problem = sessionProblem(s, t);
    if (problem) throw sessionEnded(problem);
    const accessToken = newToken();
    const next = refreshTokenFor(s.id);
    const rotation = {
      access_hash: sha256Hex(accessToken),
      access_expires_at: Math.min(t + this.config.accessTokenTtlMs, s.expires_at),
      refresh_hash: sha256Hex(next),
      // A just-replaced token stays the "previous" one: the pair it replaced is what gets dropped.
      previous_refresh_hash: current ? hash : s.previous_refresh_hash,
      refresh_expires_at: Math.min(t + this.config.refreshIdleMs, s.expires_at),
      refreshed_at: current ? t : s.refreshed_at,
      last_used_at: t,
    };
    this.store.rotateSession(s.id, rotation);
    return {
      accessToken,
      accessTokenExpiresAt: rotation.access_expires_at,
      refreshToken: next,
      refreshTokenExpiresAt: rotation.refresh_expires_at,
      session: { ...s, ...rotation },
    };
  }

  /** The session an access token belongs to, if the token is current and the session may be used. */
  authenticate(accessToken: string | undefined, t: number): ActiveSession | null {
    if (!accessToken || accessToken.length < 20 || accessToken.length > 200) return null;
    const s = this.store.sessionByAccessHash(sha256Hex(accessToken));
    if (!s || s.access_expires_at <= t || sessionProblem(s, t)) return null;
    this.store.touchSession(s.id, t);
    return s;
  }

  /** Whether a session may still be used from [deviceId], whatever happened to its access token since (open WebSockets). */
  stillValid(sessionId: string, deviceId: string, t: number): boolean {
    const s = this.store.sessionById(sessionId);
    return !!s && s.device_id === deviceId && sessionProblem(s, t) === null;
  }
}
