import type { DB } from "../db.js";

export interface AccountRow {
  id: string;
  email: string;
  name: string;
  password_hash: string;
  password_changed_at: number;
  email_verified_at: number | null;
  user_id: string | null;
  legacy_linked_at: number | null;
  selected_workspace_id: string | null;
  admitted_via: string | null;
  created_at: number;
  updated_at: number;
  disabled_at: number | null;
}

export interface SessionRow {
  id: string;
  account_id: string;
  device_id: string | null;
  label: string;
  access_hash: string;
  access_expires_at: number;
  refresh_hash: string;
  previous_refresh_hash: string | null;
  refresh_expires_at: number;
  expires_at: number;
  created_at: number;
  refreshed_at: number;
  last_used_at: number;
  revoked_at: number | null;
  revoke_reason: string | null;
}

/** A session with what decides whether it may still be used: its account, encryption identity and device. */
export interface ActiveSession extends SessionRow {
  user_id: string | null;
  account_disabled_at: number | null;
  user_disabled_at: number | null;
  device_revoked_at: number | null;
}

export type CodePurpose = "verify_email" | "reset_password";

export interface CodeRow {
  account_id: string;
  purpose: CodePurpose;
  code_hash: string;
  attempts: number;
  created_at: number;
  expires_at: number;
}

export interface TicketRow {
  ticket_hash: string;
  user_id: string;
  created_at: number;
  expires_at: number;
  used_at: number | null;
  account_id: string | null;
}

export type AccountPatch = Partial<
  Pick<AccountRow, "name" | "password_hash" | "password_changed_at" | "email_verified_at" | "user_id" | "legacy_linked_at" | "selected_workspace_id" | "admitted_via">
>;

export type SessionRotation = Pick<
  SessionRow,
  "access_hash" | "access_expires_at" | "refresh_hash" | "previous_refresh_hash" | "refresh_expires_at" | "refreshed_at" | "last_used_at"
>;

/**
 * Where the account module keeps its rows (migration 014). Everything account-related goes through here, so the module
 * can move to MongoDB (docs/ORBIT_2_ARCHITECTURE §8) by adding an implementation, without touching the routes. SQLite
 * calls are synchronous; a MongoDB store would make these methods async.
 */
export interface AccountStore {
  transaction<T>(fn: () => T): T;

  accountByEmail(email: string): AccountRow | undefined;
  accountById(id: string): AccountRow | undefined;
  accountByUserId(userId: string): AccountRow | undefined;
  /** False when the email is taken. */
  insertAccount(row: AccountRow): boolean;
  updateAccount(id: string, patch: AccountPatch, at: number): void;
  /** Changes the address of an account whose address isn't verified yet (a legacy link redone). */
  updateEmail(id: string, email: string, at: number): void;
  /** Removes an account nobody verified and no identity is linked to; false when it doesn't qualify. */
  deleteUnverifiedAccount(id: string): boolean;

  insertSession(row: SessionRow): void;
  sessionById(id: string): ActiveSession | undefined;
  sessionByAccessHash(hash: string): ActiveSession | undefined;
  rotateSession(id: string, rotation: SessionRotation): void;
  bindSessionDevice(id: string, deviceId: string): void;
  touchSession(id: string, at: number): void;
  revokeSession(id: string, reason: string, at: number): boolean;
  revokeAccountSessions(accountId: string, reason: string, at: number, exceptSessionId?: string): string[];
  revokeDeviceSessions(deviceId: string, reason: string, at: number): void;
  activeSessions(accountId: string, at: number): SessionRow[];

  putCode(row: CodeRow): void;
  code(accountId: string, purpose: CodePurpose): CodeRow | undefined;
  countCodeAttempt(accountId: string, purpose: CodePurpose): void;
  deleteCode(accountId: string, purpose: CodePurpose): void;

  insertTicket(row: TicketRow): void;
  ticket(hash: string): TicketRow | undefined;
  useTicket(hash: string, accountId: string, at: number): void;

  /** Expired sessions, codes and tickets (the retention job). Returns how many rows went. */
  purgeExpired(at: number): number;
}

const ACTIVE_SESSION = `SELECT s.*, a.user_id, a.disabled_at AS account_disabled_at, u.disabled_at AS user_disabled_at, d.revoked_at AS device_revoked_at
  FROM account_sessions s JOIN accounts a ON a.id = s.account_id
  LEFT JOIN users u ON u.id = a.user_id LEFT JOIN devices d ON d.id = s.device_id`;

const PATCHABLE = new Set<keyof AccountPatch>([
  "name",
  "password_hash",
  "password_changed_at",
  "email_verified_at",
  "user_id",
  "legacy_linked_at",
  "selected_workspace_id",
  "admitted_via",
]);

export class SqliteAccountStore implements AccountStore {
  constructor(private readonly db: DB) {}

  transaction<T>(fn: () => T): T {
    return this.db.transaction(fn)();
  }

  accountByEmail(email: string): AccountRow | undefined {
    return this.db.prepare("SELECT * FROM accounts WHERE email = ?").get(email) as AccountRow | undefined;
  }

  accountById(id: string): AccountRow | undefined {
    return this.db.prepare("SELECT * FROM accounts WHERE id = ?").get(id) as AccountRow | undefined;
  }

  accountByUserId(userId: string): AccountRow | undefined {
    return this.db.prepare("SELECT * FROM accounts WHERE user_id = ?").get(userId) as AccountRow | undefined;
  }

  insertAccount(r: AccountRow): boolean {
    const res = this.db
      .prepare(
        `INSERT INTO accounts (id, email, name, password_hash, password_changed_at, email_verified_at, user_id, legacy_linked_at, selected_workspace_id, admitted_via, created_at, updated_at, disabled_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING`,
      )
      .run(r.id, r.email, r.name, r.password_hash, r.password_changed_at, r.email_verified_at, r.user_id, r.legacy_linked_at, r.selected_workspace_id, r.admitted_via, r.created_at, r.updated_at, r.disabled_at);
    return res.changes === 1;
  }

  updateAccount(id: string, patch: AccountPatch, at: number): void {
    const keys = (Object.keys(patch) as (keyof AccountPatch)[]).filter((k) => PATCHABLE.has(k));
    if (keys.length === 0) return;
    const sets = keys.map((k) => `${k} = ?`).join(", ");
    this.db.prepare(`UPDATE accounts SET ${sets}, updated_at = ? WHERE id = ?`).run(...keys.map((k) => patch[k] ?? null), at, id);
  }

  updateEmail(id: string, email: string, at: number): void {
    this.db.prepare("UPDATE accounts SET email = ?, updated_at = ? WHERE id = ? AND email_verified_at IS NULL").run(email, at, id);
  }

  deleteUnverifiedAccount(id: string): boolean {
    return this.db.prepare("DELETE FROM accounts WHERE id = ? AND email_verified_at IS NULL AND user_id IS NULL").run(id).changes === 1;
  }

  insertSession(r: SessionRow): void {
    this.db
      .prepare(
        `INSERT INTO account_sessions (id, account_id, device_id, label, access_hash, access_expires_at, refresh_hash, previous_refresh_hash, refresh_expires_at, expires_at, created_at, refreshed_at, last_used_at, revoked_at, revoke_reason)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(r.id, r.account_id, r.device_id, r.label, r.access_hash, r.access_expires_at, r.refresh_hash, r.previous_refresh_hash, r.refresh_expires_at, r.expires_at, r.created_at, r.refreshed_at, r.last_used_at, r.revoked_at, r.revoke_reason);
  }

  sessionById(id: string): ActiveSession | undefined {
    return this.db.prepare(`${ACTIVE_SESSION} WHERE s.id = ?`).get(id) as ActiveSession | undefined;
  }

  sessionByAccessHash(hash: string): ActiveSession | undefined {
    return this.db.prepare(`${ACTIVE_SESSION} WHERE s.access_hash = ?`).get(hash) as ActiveSession | undefined;
  }

  rotateSession(id: string, r: SessionRotation): void {
    this.db
      .prepare(
        "UPDATE account_sessions SET access_hash = ?, access_expires_at = ?, refresh_hash = ?, previous_refresh_hash = ?, refresh_expires_at = ?, refreshed_at = ?, last_used_at = ? WHERE id = ?",
      )
      .run(r.access_hash, r.access_expires_at, r.refresh_hash, r.previous_refresh_hash, r.refresh_expires_at, r.refreshed_at, r.last_used_at, id);
  }

  bindSessionDevice(id: string, deviceId: string): void {
    this.db.prepare("UPDATE account_sessions SET device_id = ? WHERE id = ?").run(deviceId, id);
  }

  touchSession(id: string, at: number): void {
    this.db.prepare("UPDATE account_sessions SET last_used_at = ? WHERE id = ? AND last_used_at < ?").run(at, id, at - 60_000);
  }

  revokeSession(id: string, reason: string, at: number): boolean {
    return this.db.prepare("UPDATE account_sessions SET revoked_at = ?, revoke_reason = ? WHERE id = ? AND revoked_at IS NULL").run(at, reason, id).changes === 1;
  }

  revokeAccountSessions(accountId: string, reason: string, at: number, exceptSessionId?: string): string[] {
    const rows = this.db
      .prepare("UPDATE account_sessions SET revoked_at = ?, revoke_reason = ? WHERE account_id = ? AND revoked_at IS NULL AND id IS NOT ? RETURNING device_id")
      .all(at, reason, accountId, exceptSessionId ?? null) as { device_id: string | null }[];
    return rows.flatMap((r) => (r.device_id ? [r.device_id] : []));
  }

  revokeDeviceSessions(deviceId: string, reason: string, at: number): void {
    this.db.prepare("UPDATE account_sessions SET revoked_at = ?, revoke_reason = ? WHERE device_id = ? AND revoked_at IS NULL").run(at, reason, deviceId);
  }

  activeSessions(accountId: string, at: number): SessionRow[] {
    return this.db
      .prepare("SELECT * FROM account_sessions WHERE account_id = ? AND revoked_at IS NULL AND expires_at > ? AND refresh_expires_at > ? ORDER BY last_used_at DESC")
      .all(accountId, at, at) as SessionRow[];
  }

  putCode(r: CodeRow): void {
    this.db
      .prepare(
        `INSERT INTO account_codes (account_id, purpose, code_hash, attempts, created_at, expires_at) VALUES (?, ?, ?, 0, ?, ?)
         ON CONFLICT (account_id, purpose) DO UPDATE SET code_hash = excluded.code_hash, attempts = 0, created_at = excluded.created_at, expires_at = excluded.expires_at`,
      )
      .run(r.account_id, r.purpose, r.code_hash, r.created_at, r.expires_at);
  }

  code(accountId: string, purpose: CodePurpose): CodeRow | undefined {
    return this.db.prepare("SELECT * FROM account_codes WHERE account_id = ? AND purpose = ?").get(accountId, purpose) as CodeRow | undefined;
  }

  countCodeAttempt(accountId: string, purpose: CodePurpose): void {
    this.db.prepare("UPDATE account_codes SET attempts = attempts + 1 WHERE account_id = ? AND purpose = ?").run(accountId, purpose);
  }

  deleteCode(accountId: string, purpose: CodePurpose): void {
    this.db.prepare("DELETE FROM account_codes WHERE account_id = ? AND purpose = ?").run(accountId, purpose);
  }

  insertTicket(r: TicketRow): void {
    this.db
      .prepare("INSERT INTO legacy_tickets (ticket_hash, user_id, created_at, expires_at, used_at, account_id) VALUES (?, ?, ?, ?, ?, ?)")
      .run(r.ticket_hash, r.user_id, r.created_at, r.expires_at, r.used_at, r.account_id);
  }

  ticket(hash: string): TicketRow | undefined {
    return this.db.prepare("SELECT * FROM legacy_tickets WHERE ticket_hash = ?").get(hash) as TicketRow | undefined;
  }

  useTicket(hash: string, accountId: string, at: number): void {
    this.db.prepare("UPDATE legacy_tickets SET used_at = ?, account_id = ? WHERE ticket_hash = ?").run(at, accountId, hash);
  }

  purgeExpired(at: number): number {
    return this.transaction(() => {
      // Revoked or expired sessions are kept a day so a late refresh still gets "session ended" instead of a generic 401.
      const grace = at - 86_400_000;
      const sessions = this.db
        .prepare("DELETE FROM account_sessions WHERE expires_at <= ? OR refresh_expires_at <= ? OR (revoked_at IS NOT NULL AND revoked_at <= ?)")
        .run(grace, grace, grace).changes;
      const codes = this.db.prepare("DELETE FROM account_codes WHERE expires_at <= ?").run(at).changes;
      const tickets = this.db.prepare("DELETE FROM legacy_tickets WHERE expires_at <= ?").run(grace).changes;
      return sessions + codes + tickets;
    });
  }
}
