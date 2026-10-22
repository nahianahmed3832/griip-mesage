import type { DB } from "../../infrastructure/database/db.js";

/** An account's encryption keys sealed under its password (migration 031, docs/KEY_BACKUP.md). */
export interface KeyBackupRow {
  account_id: string;
  identity_key: string;
  /** The account's password_changed_at when the backup was made: a later password change makes it stale. */
  password_changed_at: number;
  version: number;
  iterations: number;
  salt: string;
  nonce: string;
  ciphertext: string;
  created_at: number;
  updated_at: number;
}

/** A code emailed to unlock the backup for one session. */
export interface KeyUnlockRow {
  account_id: string;
  session_id: string;
  code_hash: string;
  attempts: number;
  created_at: number;
  expires_at: number;
}

/**
 * Where key backups live: SQLite (migration 031) or MongoDB when MONGODB_DOMAINS includes "accounts"
 * (src/mongo/keyBackups.ts). Using an unlock code is one conditional delete, so two requests can't both use it.
 */
export interface KeyBackupStore {
  backup(accountId: string): Promise<KeyBackupRow | undefined>;
  /** Replaces the account's backup (one per account). */
  putBackup(row: KeyBackupRow): Promise<void>;
  deleteBackup(accountId: string): Promise<boolean>;
  /** Replaces any unlock in progress (a new code replaces the previous one). */
  putUnlock(row: KeyUnlockRow): Promise<void>;
  unlock(accountId: string): Promise<KeyUnlockRow | undefined>;
  countUnlockAttempt(accountId: string): Promise<void>;
  /** Removes the unlock with this code hash; false when another request removed it first. */
  useUnlock(accountId: string, codeHash: string): Promise<boolean>;
  /** Account deletion: the backup and any unlock in progress. */
  deleteForAccount(accountId: string): Promise<void>;
  purgeExpired(at: number): Promise<number>;
}

export class SqliteKeyBackupStore implements KeyBackupStore {
  constructor(private readonly db: DB) {}

  async backup(accountId: string): Promise<KeyBackupRow | undefined> {
    return this.db.prepare("SELECT * FROM account_key_backups WHERE account_id = ?").get(accountId) as KeyBackupRow | undefined;
  }

  async putBackup(r: KeyBackupRow): Promise<void> {
    this.db
      .prepare(
        `INSERT INTO account_key_backups (account_id, identity_key, password_changed_at, version, iterations, salt, nonce, ciphertext, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(account_id) DO UPDATE SET identity_key = excluded.identity_key, password_changed_at = excluded.password_changed_at,
           version = excluded.version, iterations = excluded.iterations, salt = excluded.salt, nonce = excluded.nonce,
           ciphertext = excluded.ciphertext, updated_at = excluded.updated_at`,
      )
      .run(r.account_id, r.identity_key, r.password_changed_at, r.version, r.iterations, r.salt, r.nonce, r.ciphertext, r.created_at, r.updated_at);
  }

  async deleteBackup(accountId: string): Promise<boolean> {
    return this.db.prepare("DELETE FROM account_key_backups WHERE account_id = ?").run(accountId).changes === 1;
  }

  async putUnlock(r: KeyUnlockRow): Promise<void> {
    this.db
      .prepare(
        `INSERT INTO account_key_unlocks (account_id, session_id, code_hash, attempts, created_at, expires_at) VALUES (?, ?, ?, 0, ?, ?)
         ON CONFLICT(account_id) DO UPDATE SET session_id = excluded.session_id, code_hash = excluded.code_hash, attempts = 0,
           created_at = excluded.created_at, expires_at = excluded.expires_at`,
      )
      .run(r.account_id, r.session_id, r.code_hash, r.created_at, r.expires_at);
  }

  async unlock(accountId: string): Promise<KeyUnlockRow | undefined> {
    return this.db.prepare("SELECT * FROM account_key_unlocks WHERE account_id = ?").get(accountId) as KeyUnlockRow | undefined;
  }

  async countUnlockAttempt(accountId: string): Promise<void> {
    this.db.prepare("UPDATE account_key_unlocks SET attempts = attempts + 1 WHERE account_id = ?").run(accountId);
  }

  async useUnlock(accountId: string, codeHash: string): Promise<boolean> {
    return this.db.prepare("DELETE FROM account_key_unlocks WHERE account_id = ? AND code_hash = ?").run(accountId, codeHash).changes === 1;
  }

  async deleteForAccount(accountId: string): Promise<void> {
    this.db.prepare("DELETE FROM account_key_backups WHERE account_id = ?").run(accountId);
    this.db.prepare("DELETE FROM account_key_unlocks WHERE account_id = ?").run(accountId);
  }

  async purgeExpired(at: number): Promise<number> {
    return this.db.prepare("DELETE FROM account_key_unlocks WHERE expires_at <= ?").run(at).changes;
  }
}
