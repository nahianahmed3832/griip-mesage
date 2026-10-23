-- 2026-10-23: passkeys (WebAuthn) for signing in to an account (docs/PASSKEYS.md). Additive only.
--
-- A passkey replaces typing the password at sign-in; it does not unlock end-to-end encrypted history (that still needs
-- this phone's keys, another device, the key backup or the recovery key). No foreign key to accounts: with
-- MONGODB_DOMAINS=accounts the account rows live in MongoDB, so deleting an account deletes its passkeys explicitly.

-- One row per registered passkey. [id] is the credential id (base64url), [public_key] the COSE public key (base64url),
-- [counter] the authenticator's signature counter (0 for synced passkeys, which don't count).
CREATE TABLE account_passkeys (
  id            TEXT PRIMARY KEY,
  account_id    TEXT NOT NULL,
  public_key    TEXT NOT NULL,
  counter       INTEGER NOT NULL DEFAULT 0,
  transports    TEXT,
  name          TEXT NOT NULL,
  backed_up     INTEGER NOT NULL DEFAULT 0,
  created_at    INTEGER NOT NULL,
  last_used_at  INTEGER
);
CREATE INDEX idx_account_passkeys_account ON account_passkeys(account_id);

-- A ceremony in progress: the challenge the server issued, single use and short-lived. [account_id] is set for adding
-- a passkey (the signed-in account) and null for signing in (the passkey says whose it is).
CREATE TABLE account_passkey_challenges (
  id          TEXT PRIMARY KEY,
  purpose     TEXT NOT NULL CHECK (purpose IN ('register', 'login')),
  account_id  TEXT,
  challenge   TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL
);
CREATE INDEX idx_account_passkey_challenges_expiry ON account_passkey_challenges(expires_at);
