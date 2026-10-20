import type { DB } from "../db.js";

/** One code of an account's current recovery-code set (migration 017). */
export interface RecoveryCodeRow {
  id: string;
  account_id: string;
  code_hash: string;
  created_at: number;
  used_at: number | null;
}

/** A recovery in progress: a code was accepted and a code was emailed to [new_email]. */
export interface RecoveryTicketRow {
  ticket_hash: string;
  account_id: string;
  new_email: string;
  email_code_hash: string;
  attempts: number;
  created_at: number;
  expires_at: number;
  used_at: number | null;
}

/**
 * Where recovery codes live: SQLite (migration 017) or MongoDB when MONGODB_DOMAINS includes "accounts"
 * (src/mongo/recovery.ts). Every check-and-write is one conditional statement, so two requests can't both use a code
 * or a ticket.
 */
export interface RecoveryStore {
  /** Replaces the account's whole set (generating new codes revokes the old ones). */
  replaceCodes(accountId: string, rows: RecoveryCodeRow[]): Promise<void>;
  deleteCodes(accountId: string): Promise<number>;
  /** Account deletion: the account's codes and its recovery tickets. */
  deleteForAccount(accountId: string): Promise<void>;
  codes(accountId: string): Promise<RecoveryCodeRow[]>;
  /** Marks the unused code with this hash used; false when it was already used (or isn't the account's). */
  useCode(accountId: string, codeHash: string, at: number): Promise<boolean>;

  insertTicket(row: RecoveryTicketRow): Promise<void>;
  ticket(hash: string): Promise<RecoveryTicketRow | undefined>;
  countTicketAttempt(hash: string): Promise<void>;
  /** Marks an unused, unexpired ticket used; false when it was already used or expired. */
  useTicket(hash: string, at: number): Promise<boolean>;
  purgeExpired(at: number): Promise<number>;
}

/** Finished or expired tickets are kept a day, for the audit trail's sake, then removed. */
export const RECOVERY_TICKET_GRACE_MS = 86_400_000;

export class SqliteRecoveryStore implements RecoveryStore {
  constructor(private readonly db: DB) {}

  async replaceCodes(accountId: string, rows: RecoveryCodeRow[]): Promise<void> {
    const insert = this.db.prepare("INSERT INTO account_recovery_codes (id, account_id, code_hash, created_at, used_at) VALUES (?, ?, ?, ?, NULL)");
    this.db.transaction(() => {
      this.db.prepare("DELETE FROM account_recovery_codes WHERE account_id = ?").run(accountId);
      for (const r of rows) insert.run(r.id, accountId, r.code_hash, r.created_at);
    })();
  }

  async deleteCodes(accountId: string): Promise<number> {
    return this.db.prepare("DELETE FROM account_recovery_codes WHERE account_id = ?").run(accountId).changes;
  }

  async deleteForAccount(accountId: string): Promise<void> {
    this.db.prepare("DELETE FROM account_recovery_codes WHERE account_id = ?").run(accountId);
    this.db.prepare("DELETE FROM account_recovery_tickets WHERE account_id = ?").run(accountId);
  }

  async codes(accountId: string): Promise<RecoveryCodeRow[]> {
    return this.db.prepare("SELECT * FROM account_recovery_codes WHERE account_id = ? ORDER BY created_at, id").all(accountId) as RecoveryCodeRow[];
  }

  async useCode(accountId: string, codeHash: string, at: number): Promise<boolean> {
    return (
      this.db.prepare("UPDATE account_recovery_codes SET used_at = ? WHERE account_id = ? AND code_hash = ? AND used_at IS NULL").run(at, accountId, codeHash)
        .changes === 1
    );
  }

  async insertTicket(r: RecoveryTicketRow): Promise<void> {
    this.db
      .prepare(
        "INSERT INTO account_recovery_tickets (ticket_hash, account_id, new_email, email_code_hash, attempts, created_at, expires_at, used_at) VALUES (?, ?, ?, ?, 0, ?, ?, NULL)",
      )
      .run(r.ticket_hash, r.account_id, r.new_email, r.email_code_hash, r.created_at, r.expires_at);
  }

  async ticket(hash: string): Promise<RecoveryTicketRow | undefined> {
    return this.db.prepare("SELECT * FROM account_recovery_tickets WHERE ticket_hash = ?").get(hash) as RecoveryTicketRow | undefined;
  }

  async countTicketAttempt(hash: string): Promise<void> {
    this.db.prepare("UPDATE account_recovery_tickets SET attempts = attempts + 1 WHERE ticket_hash = ?").run(hash);
  }

  async useTicket(hash: string, at: number): Promise<boolean> {
    return this.db.prepare("UPDATE account_recovery_tickets SET used_at = ? WHERE ticket_hash = ? AND used_at IS NULL AND expires_at > ?").run(at, hash, at).changes === 1;
  }

  async purgeExpired(at: number): Promise<number> {
    return this.db.prepare("DELETE FROM account_recovery_tickets WHERE expires_at <= ?").run(at - RECOVERY_TICKET_GRACE_MS).changes;
  }
}
