import type { DB } from "../../infrastructure/database/db.js";
import type { PlatformRole } from "./permissions.js";

/** Rows of migration 046 (SQLite in every database mode), and the statements the console needs on them. */
export interface AdminGrantRow {
  account_id: string;
  role: PlatformRole;
  granted_by: string | null;
  granted_at: number;
  updated_at: number;
  revoked_at: number | null;
  revoked_by: string | null;
  note: string | null;
}

export interface AdminPasskeyRow {
  id: string;
  account_id: string;
  public_key: string;
  counter: number;
  transports: string | null;
  name: string;
  backed_up: number;
  created_at: number;
  last_used_at: number | null;
}

export interface EnrolmentRow {
  account_id: string;
  code_hash: string;
  attempts: number;
  issued_by: string | null;
  created_at: number;
  expires_at: number;
}

export type ChallengePurpose = "login" | "register" | "reauth";

export interface ChallengeRow {
  id: string;
  purpose: ChallengePurpose;
  account_id: string;
  session_id: string | null;
  challenge: string;
  created_at: number;
  expires_at: number;
}

export interface AdminSessionRow {
  id: string;
  token_hash: string;
  account_id: string;
  created_at: number;
  last_used_at: number;
  idle_expires_at: number;
  expires_at: number;
  reauth_at: number;
  network: string;
  revoked_at: number | null;
  revoke_reason: string | null;
}

export class PlatformAdminStore {
  constructor(readonly db: DB) {}

  // ───────────── Grants ─────────────

  grant(accountId: string): AdminGrantRow | undefined {
    return this.db.prepare("SELECT * FROM platform_admins WHERE account_id = ?").get(accountId) as AdminGrantRow | undefined;
  }

  activeGrant(accountId: string): AdminGrantRow | undefined {
    const g = this.grant(accountId);
    return g && g.revoked_at === null ? g : undefined;
  }

  grants(): AdminGrantRow[] {
    return this.db.prepare("SELECT * FROM platform_admins ORDER BY granted_at").all() as AdminGrantRow[];
  }

  activeSuperAdmins(): number {
    return (this.db.prepare("SELECT COUNT(*) n FROM platform_admins WHERE role = 'super_admin' AND revoked_at IS NULL").get() as { n: number }).n;
  }

  /** Grants [role] (again, after a revocation), or changes an active grant's role. */
  putGrant(accountId: string, role: PlatformRole, by: string | null, note: string | null, t: number): void {
    this.db
      .prepare(
        `INSERT INTO platform_admins (account_id, role, granted_by, granted_at, updated_at, note) VALUES (?, ?, ?, ?, ?, ?)
         ON CONFLICT(account_id) DO UPDATE SET role = excluded.role, updated_at = excluded.updated_at, note = excluded.note,
           granted_by = CASE WHEN revoked_at IS NULL THEN granted_by ELSE excluded.granted_by END,
           granted_at = CASE WHEN revoked_at IS NULL THEN granted_at ELSE excluded.granted_at END,
           revoked_at = NULL, revoked_by = NULL`,
      )
      .run(accountId, role, by, t, t, note);
  }

  revokeGrant(accountId: string, by: string | null, t: number): boolean {
    return this.db.prepare("UPDATE platform_admins SET revoked_at = ?, revoked_by = ?, updated_at = ? WHERE account_id = ? AND revoked_at IS NULL").run(t, by, t, accountId).changes > 0;
  }

  // ───────────── Console passkeys ─────────────

  passkeys(accountId: string): AdminPasskeyRow[] {
    return this.db.prepare("SELECT * FROM platform_admin_passkeys WHERE account_id = ? ORDER BY created_at").all(accountId) as AdminPasskeyRow[];
  }

  passkey(id: string): AdminPasskeyRow | undefined {
    return this.db.prepare("SELECT * FROM platform_admin_passkeys WHERE id = ?").get(id) as AdminPasskeyRow | undefined;
  }

  insertPasskey(row: AdminPasskeyRow): void {
    this.db
      .prepare(
        `INSERT INTO platform_admin_passkeys (id, account_id, public_key, counter, transports, name, backed_up, created_at, last_used_at)
         VALUES (@id, @account_id, @public_key, @counter, @transports, @name, @backed_up, @created_at, @last_used_at)`,
      )
      .run(row);
  }

  usedPasskey(id: string, counter: number, backedUp: boolean, t: number): void {
    this.db.prepare("UPDATE platform_admin_passkeys SET counter = ?, backed_up = ?, last_used_at = ? WHERE id = ?").run(counter, backedUp ? 1 : 0, t, id);
  }

  deletePasskeys(accountId: string): number {
    return this.db.prepare("DELETE FROM platform_admin_passkeys WHERE account_id = ?").run(accountId).changes;
  }

  // ───────────── Enrolment codes ─────────────

  putEnrolment(row: EnrolmentRow): void {
    this.db
      .prepare(
        `INSERT OR REPLACE INTO platform_admin_enrolments (account_id, code_hash, attempts, issued_by, created_at, expires_at)
         VALUES (@account_id, @code_hash, @attempts, @issued_by, @created_at, @expires_at)`,
      )
      .run(row);
  }

  enrolment(accountId: string): EnrolmentRow | undefined {
    return this.db.prepare("SELECT * FROM platform_admin_enrolments WHERE account_id = ?").get(accountId) as EnrolmentRow | undefined;
  }

  countEnrolmentAttempt(accountId: string): void {
    this.db.prepare("UPDATE platform_admin_enrolments SET attempts = attempts + 1 WHERE account_id = ?").run(accountId);
  }

  deleteEnrolment(accountId: string): void {
    this.db.prepare("DELETE FROM platform_admin_enrolments WHERE account_id = ?").run(accountId);
  }

  // ───────────── Ceremonies ─────────────

  putChallenge(row: ChallengeRow): void {
    this.db.prepare("DELETE FROM platform_admin_challenges WHERE expires_at <= ?").run(row.created_at);
    this.db
      .prepare(
        `INSERT INTO platform_admin_challenges (id, purpose, account_id, session_id, challenge, created_at, expires_at)
         VALUES (@id, @purpose, @account_id, @session_id, @challenge, @created_at, @expires_at)`,
      )
      .run(row);
  }

  /** The ceremony [id], removed whatever happens next (single use). */
  takeChallenge(id: string): ChallengeRow | undefined {
    const row = this.db.prepare("SELECT * FROM platform_admin_challenges WHERE id = ?").get(id) as ChallengeRow | undefined;
    if (row) this.db.prepare("DELETE FROM platform_admin_challenges WHERE id = ?").run(id);
    return row;
  }

  // ───────────── Sessions ─────────────

  insertSession(row: AdminSessionRow): void {
    this.db
      .prepare(
        `INSERT INTO platform_admin_sessions (id, token_hash, account_id, created_at, last_used_at, idle_expires_at, expires_at, reauth_at, network, revoked_at, revoke_reason)
         VALUES (@id, @token_hash, @account_id, @created_at, @last_used_at, @idle_expires_at, @expires_at, @reauth_at, @network, @revoked_at, @revoke_reason)`,
      )
      .run(row);
  }

  sessionByTokenHash(hash: string): AdminSessionRow | undefined {
    return this.db.prepare("SELECT * FROM platform_admin_sessions WHERE token_hash = ?").get(hash) as AdminSessionRow | undefined;
  }

  session(id: string): AdminSessionRow | undefined {
    return this.db.prepare("SELECT * FROM platform_admin_sessions WHERE id = ?").get(id) as AdminSessionRow | undefined;
  }

  activeSessions(accountId: string, t: number): AdminSessionRow[] {
    return this.db
      .prepare("SELECT * FROM platform_admin_sessions WHERE account_id = ? AND revoked_at IS NULL AND expires_at > ? AND idle_expires_at > ? ORDER BY created_at DESC")
      .all(accountId, t, t) as AdminSessionRow[];
  }

  touchSession(id: string, t: number, idleExpiresAt: number): void {
    this.db.prepare("UPDATE platform_admin_sessions SET last_used_at = ?, idle_expires_at = ? WHERE id = ?").run(t, idleExpiresAt, id);
  }

  reauthenticated(id: string, t: number): void {
    this.db.prepare("UPDATE platform_admin_sessions SET reauth_at = ? WHERE id = ?").run(t, id);
  }

  revokeSession(id: string, reason: string, t: number): boolean {
    return this.db.prepare("UPDATE platform_admin_sessions SET revoked_at = ?, revoke_reason = ? WHERE id = ? AND revoked_at IS NULL").run(t, reason, id).changes > 0;
  }

  revokeAccountSessions(accountId: string, reason: string, t: number): number {
    return this.db.prepare("UPDATE platform_admin_sessions SET revoked_at = ?, revoke_reason = ? WHERE account_id = ? AND revoked_at IS NULL").run(t, reason, accountId).changes;
  }

  /** Retention: ended sessions a day after they ended, expired ceremonies and enrolment codes. */
  purgeExpired(t: number): number {
    const day = 86_400_000;
    return (
      this.db.prepare("DELETE FROM platform_admin_sessions WHERE (revoked_at IS NOT NULL AND revoked_at <= ?) OR expires_at <= ? OR idle_expires_at <= ?").run(t - day, t - day, t - day).changes +
      this.db.prepare("DELETE FROM platform_admin_challenges WHERE expires_at <= ?").run(t).changes +
      this.db.prepare("DELETE FROM platform_admin_enrolments WHERE expires_at <= ?").run(t).changes
    );
  }
}
