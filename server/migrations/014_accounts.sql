-- 2026-10-18: Orbit 2.0 accounts (docs/ORBIT_2_ARCHITECTURE_2026-10-18.md). Email-and-password accounts sit on top of
-- the end-to-end encryption identity (users + devices): an account signs people in and holds their sessions; its
-- user_id points at the encryption identity once a device has set one up. Additive only: no existing row changes.
-- 013 is left for account deletion (branch claude/orbit-round-4-8f76de); the runner applies any missing version.

CREATE TABLE accounts (
  id                     TEXT PRIMARY KEY,
  email                  TEXT NOT NULL UNIQUE COLLATE NOCASE,  -- normalised to lower case before it is stored
  name                   TEXT NOT NULL,
  password_hash          TEXT NOT NULL,                         -- scrypt$logN$r$p$salt$hash (src/accounts/passwords.ts)
  password_changed_at    INTEGER NOT NULL,
  email_verified_at      INTEGER,
  -- The encryption identity (users row). NULL until the first device sets one up; a legacy link sets it at once.
  user_id                TEXT UNIQUE REFERENCES users(id) ON DELETE CASCADE,
  legacy_linked_at       INTEGER,
  selected_workspace_id  TEXT,                                  -- "personal" or an org id the person belongs to
  -- How the account was admitted: "registration-invite:<id>" or "org-invite:<id>" on an invite-only server (the org
  -- invite's use is taken at sign-up; the person joins its org when their encryption identity is made), "legacy" for a
  -- moved legacy account, NULL for an open sign-up.
  admitted_via           TEXT,
  created_at             INTEGER NOT NULL,
  updated_at             INTEGER NOT NULL,
  disabled_at            INTEGER
);

-- One row per signed-in app. Tokens are stored as SHA-256 only. A refresh token names its session ("<id>.<secret>"), so
-- any refresh token of a session other than the current one is recognised: presented again, it was copied, and the whole
-- session is revoked. The one just replaced is still accepted for a few seconds (a response lost on the way).
CREATE TABLE account_sessions (
  id                     TEXT PRIMARY KEY,
  account_id             TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  device_id              TEXT REFERENCES devices(id) ON DELETE CASCADE,  -- NULL until this app has encryption keys
  label                  TEXT NOT NULL,
  access_hash            TEXT NOT NULL UNIQUE,
  access_expires_at      INTEGER NOT NULL,
  refresh_hash           TEXT NOT NULL UNIQUE,
  previous_refresh_hash  TEXT,
  refresh_expires_at     INTEGER NOT NULL,                      -- idle limit, moved forward by every refresh
  expires_at             INTEGER NOT NULL,                      -- absolute limit
  created_at             INTEGER NOT NULL,
  refreshed_at           INTEGER NOT NULL,                      -- last rotation (a retry racing it isn't reuse)
  last_used_at           INTEGER NOT NULL,
  revoked_at             INTEGER,
  revoke_reason          TEXT
);
CREATE INDEX idx_account_sessions_account ON account_sessions(account_id);
CREATE INDEX idx_account_sessions_device ON account_sessions(device_id) WHERE device_id IS NOT NULL;
CREATE INDEX idx_account_sessions_expiry ON account_sessions(expires_at);

-- The current email-verification or password-reset code of an account (a new one replaces the old one).
CREATE TABLE account_codes (
  account_id   TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  purpose      TEXT NOT NULL CHECK (purpose IN ('verify_email','reset_password')),
  code_hash    TEXT NOT NULL,
  attempts     INTEGER NOT NULL DEFAULT 0,
  created_at   INTEGER NOT NULL,
  expires_at   INTEGER NOT NULL,
  PRIMARY KEY (account_id, purpose)
);
CREATE INDEX idx_account_codes_expiry ON account_codes(expires_at);

-- Legacy migration: issued after the app proved it holds a legacy account's identity key (it signed a challenge with
-- the key derived from the recovery key, which never leaves the phone). Single use, short-lived, stored hashed.
CREATE TABLE legacy_tickets (
  ticket_hash  TEXT PRIMARY KEY,
  user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at   INTEGER NOT NULL,
  expires_at   INTEGER NOT NULL,
  used_at      INTEGER,
  account_id   TEXT REFERENCES accounts(id) ON DELETE CASCADE
);
CREATE INDEX idx_legacy_tickets_expiry ON legacy_tickets(expires_at);
