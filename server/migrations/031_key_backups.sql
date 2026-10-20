-- 2026-10-21: encryption keys backed up under the account password (docs/KEY_BACKUP.md). Additive only.
--
-- A new device signs in with email and password, proves the inbox with an emailed code, and gets back the identity
-- seed sealed on another device with a key derived from the password (PBKDF2-HMAC-SHA256, AES-256-GCM). The server
-- stores only that ciphertext; it can't open it without the password. A backup counts only while the account's
-- password and encryption identity are the ones it was made for (password_changed_at, identity_key), so a reset
-- password or a fresh identity leaves the recovery key as the way in.
CREATE TABLE account_key_backups (
  account_id           TEXT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
  identity_key         TEXT NOT NULL,
  password_changed_at  INTEGER NOT NULL,
  version              INTEGER NOT NULL,
  iterations           INTEGER NOT NULL,
  salt                 TEXT NOT NULL,
  nonce                TEXT NOT NULL,
  ciphertext           TEXT NOT NULL,
  created_at           INTEGER NOT NULL,
  updated_at           INTEGER NOT NULL
);

-- An unlock in progress: a code was emailed for one signed-in session; single use, short-lived, stored hashed.
CREATE TABLE account_key_unlocks (
  account_id   TEXT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
  session_id   TEXT NOT NULL,
  code_hash    TEXT NOT NULL,
  attempts     INTEGER NOT NULL DEFAULT 0,
  created_at   INTEGER NOT NULL,
  expires_at   INTEGER NOT NULL
);
CREATE INDEX idx_account_key_unlocks_expiry ON account_key_unlocks(expires_at);
