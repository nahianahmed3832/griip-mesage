import { humanCode, normalizeCode, now, sha256Hex } from "../../shared/utilities/util.js";
import type { AccountRow } from "../accounts/index.js";
import type { PlatformAudit } from "./audit.js";
import { isPlatformRole, type PlatformRole } from "./permissions.js";
import type { PlatformAdminStore } from "./store.js";

/**
 * Granting, revoking and enrolling console admins (§3.7). The operator CLI (`admin.js platform-admin-*`) is the only
 * way to make the first admin: no HTTP route can. [actor] is null for the CLI, else the super admin acting.
 */
export const ENROLMENT_MS = 30 * 60_000;
const CODE_LENGTH = 10;

export interface Actor {
  accountId: string | null;
  role: PlatformRole | null;
  requestId?: string;
  network?: string;
}

export const CLI_ACTOR: Actor = { accountId: null, role: null };

/** "ABCDE-FGHJK": shown once, stored as a hash. */
export function issueEnrolment(store: PlatformAdminStore, accountId: string, actor: Actor, t = now()): string {
  const code = humanCode(CODE_LENGTH);
  store.putEnrolment({ account_id: accountId, code_hash: sha256Hex(`admin-enrol:${normalizeCode(code)}`), attempts: 0, issued_by: actor.accountId, created_at: t, expires_at: t + ENROLMENT_MS });
  return `${code.slice(0, 5)}-${code.slice(5)}`;
}

/** Why [account] can't be granted a console role, or null. */
export function grantProblem(account: AccountRow | undefined): string | null {
  if (!account) return "No account has that email address.";
  if (account.disabled_at) return "That account is disabled.";
  if (!account.email_verified_at) return "That account hasn't verified its email address yet.";
  return null;
}

/**
 * Grants [role] to [account] (or changes its role) and, when it has no console passkey yet, issues an enrolment code.
 * Returns the code to hand over, or null when the admin already has a passkey.
 */
export function grantAdmin(store: PlatformAdminStore, audit: PlatformAudit, account: AccountRow | undefined, role: string, actor: Actor, note: string | null): string | null {
  if (!isPlatformRole(role)) throw new Error(`Unknown role. Use one of: super_admin, operations_admin, support_admin, billing_admin, auditor`);
  const problem = grantProblem(account);
  if (problem || !account) throw new Error(problem ?? "No account has that email address.");
  const t = now();
  const before = store.activeGrant(account.id);
  return store.db.transaction(() => {
    store.putGrant(account.id, role, actor.accountId, note, t);
    // A role change ends the admin's sessions: the next sign-in carries the new role from the start.
    if (before && before.role !== role) store.revokeAccountSessions(account.id, "role_changed", t);
    const code = store.passkeys(account.id).length === 0 ? issueEnrolment(store, account.id, actor, t) : null;
    audit.record({
      action: before ? "admin.role_change" : "admin.grant",
      outcome: "success",
      actorAccountId: actor.accountId,
      actorRole: actor.role,
      requestId: actor.requestId,
      network: actor.network,
      targetType: "admin",
      targetId: account.id,
      reason: note,
      detail: { role, from: before?.role ?? null, enrolmentIssued: code !== null, via: actor.accountId ? "console" : "cli" },
    }, t);
    return code;
  })();
}

/** Revokes [accountId]'s grant and ends its console sessions. False when it had no active grant. */
export function revokeAdmin(store: PlatformAdminStore, audit: PlatformAudit, accountId: string, actor: Actor, reason: string | null): boolean {
  const t = now();
  return store.db.transaction(() => {
    const before = store.activeGrant(accountId);
    if (!before || !store.revokeGrant(accountId, actor.accountId, t)) return false;
    store.revokeAccountSessions(accountId, "grant_revoked", t);
    store.deleteEnrolment(accountId);
    audit.record({
      action: "admin.revoke",
      outcome: "success",
      actorAccountId: actor.accountId,
      actorRole: actor.role,
      requestId: actor.requestId,
      network: actor.network,
      targetType: "admin",
      targetId: accountId,
      reason,
      detail: { role: before.role, via: actor.accountId ? "console" : "cli" },
    }, t);
    return true;
  })();
}

/** A lost device: removes every console passkey, ends sessions, and issues a new enrolment code. */
export function resetAdminPasskeys(store: PlatformAdminStore, audit: PlatformAudit, accountId: string, actor: Actor, reason: string | null): string {
  const t = now();
  return store.db.transaction(() => {
    if (!store.activeGrant(accountId)) throw new Error("That account has no active console role.");
    const removed = store.deletePasskeys(accountId);
    store.revokeAccountSessions(accountId, "passkeys_reset", t);
    const code = issueEnrolment(store, accountId, actor, t);
    audit.record({
      action: "admin.passkeys_reset",
      outcome: "success",
      actorAccountId: actor.accountId,
      actorRole: actor.role,
      requestId: actor.requestId,
      network: actor.network,
      targetType: "admin",
      targetId: accountId,
      reason,
      detail: { removed, via: actor.accountId ? "console" : "cli" },
    }, t);
    return code;
  })();
}
