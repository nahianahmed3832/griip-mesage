-- 2026-10-19 (round 6): account recovery codes (docs/ACCOUNT_RECOVERY.md). Additive only: no existing row changes.
--
-- Recovery codes let someone back into their account after losing the email address it uses: a valid code plus a new
-- address that they prove with an emailed code. They restore account access only; end-to-end encrypted history still
-- needs the encryption recovery key or another signed-in device (nothing here can decrypt anything).

-- One row per code of the account's current set. Codes are random (12 characters of a 31-letter alphabet, about 59
-- bits) and stored as SHA-256 of "<account id>:recovery:<code>" only. Generating a new set deletes the old one.
CREATE TABLE account_recovery_codes (
  id           TEXT PRIMARY KEY,
  account_id   TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  code_hash    TEXT NOT NULL UNIQUE,
  created_at   INTEGER NOT NULL,
  used_at      INTEGER
);
CREATE INDEX idx_account_recovery_codes_account ON account_recovery_codes(account_id);

-- A recovery in progress: a recovery code was accepted (and used up) and a code was emailed to the new address. Single
-- use and short-lived; the ticket and the emailed code are stored hashed.
CREATE TABLE account_recovery_tickets (
  ticket_hash      TEXT PRIMARY KEY,
  account_id       TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  new_email        TEXT NOT NULL,
  email_code_hash  TEXT NOT NULL,
  attempts         INTEGER NOT NULL DEFAULT 0,
  created_at       INTEGER NOT NULL,
  expires_at       INTEGER NOT NULL,
  used_at          INTEGER
);
CREATE INDEX idx_account_recovery_tickets_expiry ON account_recovery_tickets(expires_at);
