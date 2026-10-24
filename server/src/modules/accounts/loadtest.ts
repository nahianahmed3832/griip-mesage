/**
 * Scoped provisioning of synthetic load-test accounts (docs/LOADTEST_PROVISIONING.md).
 *
 * Authorization is the invite, not anything the client says: only the operator CLI creates a 'loadtest' registration
 * invite (its code is shown once, only the hash is stored), and only such an invite takes the load-test path. That
 * path skips the public per-network registration limits and is limited instead by its own hourly and daily quotas,
 * counted in SQLite (persistent across restarts; checked inside the insert transaction, so concurrent requests can't
 * overshoot). Accounts made this way are synthetic and confined to synthetic communities.
 */
import type { DB } from "../../infrastructure/database/db.js";
import { DAY_MS, HOUR_MS, networkKey } from "../../app/middleware/abuse.js";
import { ApiError } from "../../shared/errors.js";
import { humanCode, newId, normalizeCode, now, sha256Hex } from "../../shared/utilities/util.js";

export const LOADTEST_PURPOSE = "loadtest";
/** Upper bounds for one operator invite. */
export const LOADTEST_INVITE_MAX_USES = 5000;
export const LOADTEST_INVITE_MAX_DAYS = 365;

export interface LoadtestPolicy {
  /** Synthetic accounts in any rolling hour / rolling 24 hours. Both must be above 0 for the path to be open. */
  hourly: number;
  daily: number;
  /** Networks (IPv4 addresses or IPv6 /64s, as networkKey writes them) allowed to use it; empty: any. */
  networks: string[];
}

export interface LoadtestInviteRow {
  id: string;
  max_uses: number;
  uses: number;
  expires_at: number;
  revoked_at: number | null;
}

export const loadtestEnabled = (p: LoadtestPolicy): boolean => p.hourly > 0 && p.daily > 0;

/** The usable load-test invite for this code, or undefined (also for ordinary registration invites). */
export function findLoadtestInvite(db: DB, code: string): LoadtestInviteRow | undefined {
  const r = db
    .prepare("SELECT id, max_uses, uses, expires_at, revoked_at FROM registration_invites WHERE code_hash = ? AND purpose = ?")
    .get(sha256Hex(normalizeCode(code)), LOADTEST_PURPOSE) as LoadtestInviteRow | undefined;
  return r && !r.revoked_at && r.expires_at > now() && r.uses < r.max_uses ? r : undefined;
}

/** Operator-only (admin CLI and tests): a load-test invite. The code is returned once and never stored. */
export function createLoadtestInvite(db: DB, opts: { maxUses: number; days: number; note?: string | null }): { id: string; code: string } {
  if (!Number.isInteger(opts.maxUses) || opts.maxUses < 1 || opts.maxUses > LOADTEST_INVITE_MAX_USES) throw new Error(`uses must be 1–${LOADTEST_INVITE_MAX_USES}`);
  if (!(opts.days > 0 && opts.days <= LOADTEST_INVITE_MAX_DAYS)) throw new Error(`days must be more than 0 and at most ${LOADTEST_INVITE_MAX_DAYS}`);
  const code = humanCode(12);
  const id = newId("rinv");
  const t = now();
  db.prepare(
    "INSERT INTO registration_invites (id, code_hash, code_hint, note, max_uses, created_at, expires_at, purpose) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
  ).run(id, sha256Hex(code), code.slice(-4), opts.note ?? null, opts.maxUses, t, t + Math.round(opts.days * DAY_MS), LOADTEST_PURPOSE);
  return { id, code: `${code.slice(0, 4)}-${code.slice(4, 8)}-${code.slice(8)}` };
}

export interface QuotaState {
  lastHour: number;
  lastDay: number;
  /** When the next account fits again (0: it fits now). */
  retryAfterMs: number;
}

/** Synthetic accounts created in the rolling hour and rolling 24 hours before [at]. */
export function loadtestQuota(db: DB, policy: LoadtestPolicy, at: number): QuotaState {
  const count = (since: number) => (db.prepare("SELECT COUNT(*) n FROM loadtest_registrations WHERE created_at > ?").get(since) as { n: number }).n;
  const lastHour = count(at - HOUR_MS);
  const lastDay = count(at - DAY_MS);
  // The oldest row inside a full window leaves it at created_at + window: that's when one more fits.
  const freeAt = (window: number, limit: number) => {
    const r = db.prepare("SELECT created_at FROM loadtest_registrations WHERE created_at > ? ORDER BY created_at DESC LIMIT 1 OFFSET ?").get(at - window, limit - 1) as
      | { created_at: number }
      | undefined;
    return r ? r.created_at + window - at : 0;
  };
  const retryAfterMs = Math.max(lastHour >= policy.hourly ? freeAt(HOUR_MS, policy.hourly) : 0, lastDay >= policy.daily ? freeAt(DAY_MS, policy.daily) : 0);
  return { lastHour, lastDay, retryAfterMs };
}

/** Refuses the load-test path for this request unless it's enabled and the network is allowed (before key work). */
export function checkLoadtestRequest(policy: LoadtestPolicy, ip: string): string {
  if (!loadtestEnabled(policy)) throw new ApiError(403, "loadtest_disabled", "Load-test provisioning isn't enabled on this server");
  const network = networkKey(ip);
  if (policy.networks.length && !policy.networks.includes(network)) {
    throw new ApiError(403, "loadtest_network", "Load-test provisioning isn't allowed from this network");
  }
  return network;
}

/**
 * Inside the registration transaction: re-checks the invite and both quotas, then marks [userId] synthetic, uses the
 * invite once and records the registration. Throws (rolling the account back) when anything no longer holds.
 */
export function admitLoadtestAccount(db: DB, policy: LoadtestPolicy, inviteId: string, userId: string, network: string, at: number): QuotaState {
  const inv = db.prepare("SELECT id, max_uses, uses, expires_at, revoked_at FROM registration_invites WHERE id = ? AND purpose = ?").get(inviteId, LOADTEST_PURPOSE) as
    | LoadtestInviteRow
    | undefined;
  if (!inv || inv.revoked_at || inv.expires_at <= at || inv.uses >= inv.max_uses) {
    throw new ApiError(403, "invalid_invite", "This invitation code is invalid, expired or already used");
  }
  const q = loadtestQuota(db, policy, at);
  if (q.lastHour >= policy.hourly || q.lastDay >= policy.daily) {
    throw new ApiError(429, "loadtest_quota", "The load-test provisioning quota is used up for now", {
      retryAfter: Math.max(1, Math.ceil(q.retryAfterMs / 1000)),
      lastHour: q.lastHour,
      lastDay: q.lastDay,
    });
  }
  db.prepare("UPDATE users SET synthetic = 1, limited_until = NULL WHERE id = ?").run(userId);
  db.prepare("UPDATE registration_invites SET uses = uses + 1 WHERE id = ?").run(inviteId);
  db.prepare("INSERT INTO loadtest_registrations (user_id, invite_id, network, created_at) VALUES (?, ?, ?, ?)").run(userId, inviteId, network, at);
  return { lastHour: q.lastHour + 1, lastDay: q.lastDay + 1, retryAfterMs: 0 };
}

/** Whether [userId] is a synthetic load-test account. */
export const isSynthetic = (db: DB, userId: string): boolean =>
  (db.prepare("SELECT synthetic FROM users WHERE id = ?").get(userId) as { synthetic: number } | undefined)?.synthetic === 1;

/** A synthetic account may only start DMs and groups with other synthetic accounts. */
export function requireSyntheticScopeForPeople(db: DB, userId: string, others: string[]): void {
  if (!others.length || !isSynthetic(db, userId)) return;
  const real = db.prepare(`SELECT COUNT(*) n FROM users WHERE synthetic = 0 AND id IN (${others.map(() => "?").join(",")})`).get(...others) as { n: number };
  if (real.n > 0) throw new ApiError(403, "synthetic_scope", "Load-test accounts can only talk to other load-test accounts");
}
