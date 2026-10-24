-- 2026-10-23: the Platform Admin Console (admin.meldiq.app; meldiq_central_admin/docs/ARCHITECTURE_PROPOSAL.md).
-- Additive only: new tables, nothing existing changes, so an older server image runs on this schema unchanged.
--
-- A platform admin is an existing account (accounts.id) with a grant here. No foreign keys to accounts: with
-- MONGODB_DOMAINS=accounts the account rows live in MongoDB (the same reason as account_passkeys, migration 045).

-- Who may use the console, and with which role. A revoked grant keeps its row (revoked_at) for the record.
CREATE TABLE platform_admins (
  account_id  TEXT PRIMARY KEY,
  role        TEXT NOT NULL CHECK (role IN ('super_admin', 'operations_admin', 'support_admin', 'billing_admin', 'auditor')),
  granted_by  TEXT,                -- the granting admin's account id; NULL when granted with the operator CLI
  granted_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL,
  revoked_at  INTEGER,
  revoked_by  TEXT,
  note        TEXT
);
CREATE INDEX idx_platform_admins_role ON platform_admins(role);

-- Console passkeys (WebAuthn, relying party = the console's host). Separate from account_passkeys: an app passkey
-- can't open the console and a console passkey can't sign in to the app.
CREATE TABLE platform_admin_passkeys (
  id            TEXT PRIMARY KEY,  -- credential id (base64url)
  account_id    TEXT NOT NULL,
  public_key    TEXT NOT NULL,     -- COSE public key (base64url)
  counter       INTEGER NOT NULL DEFAULT 0,
  transports    TEXT,
  name          TEXT NOT NULL,
  backed_up     INTEGER NOT NULL DEFAULT 0,
  created_at    INTEGER NOT NULL,
  last_used_at  INTEGER
);
CREATE INDEX idx_platform_admin_passkeys_account ON platform_admin_passkeys(account_id);

-- One-time enrolment codes for registering a console passkey (issued by the CLI or a super admin). Hash only.
CREATE TABLE platform_admin_enrolments (
  account_id  TEXT PRIMARY KEY,
  code_hash   TEXT NOT NULL,
  attempts    INTEGER NOT NULL DEFAULT 0,
  issued_by   TEXT,
  created_at  INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL
);

-- WebAuthn ceremonies in progress: single use, minutes. [id] is the secret the browser holds in an HttpOnly cookie
-- between the password step and the passkey step; [session_id] ties a re-authentication to one admin session.
CREATE TABLE platform_admin_challenges (
  id          TEXT PRIMARY KEY,
  purpose     TEXT NOT NULL CHECK (purpose IN ('login', 'register', 'reauth')),
  account_id  TEXT NOT NULL,
  session_id  TEXT,
  challenge   TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL
);
CREATE INDEX idx_platform_admin_challenges_expiry ON platform_admin_challenges(expires_at);

-- Console sessions: the cookie's token as SHA-256 only. Idle and absolute limits; [reauth_at] is the last passkey check.
CREATE TABLE platform_admin_sessions (
  id               TEXT PRIMARY KEY,
  token_hash       TEXT NOT NULL UNIQUE,
  account_id       TEXT NOT NULL,
  created_at       INTEGER NOT NULL,
  last_used_at     INTEGER NOT NULL,
  idle_expires_at  INTEGER NOT NULL,
  expires_at       INTEGER NOT NULL,
  reauth_at        INTEGER NOT NULL,
  network          TEXT NOT NULL,     -- networkKey() of the sign-in address (IPv4, or the IPv6 /64)
  revoked_at       INTEGER,
  revoke_reason    TEXT
);
CREATE INDEX idx_platform_admin_sessions_account ON platform_admin_sessions(account_id);
CREATE INDEX idx_platform_admin_sessions_expiry ON platform_admin_sessions(expires_at);

-- The platform audit trail: append-only (the triggers below refuse any change) and hash-chained, so an edited or
-- removed row is detectable (GET /admin/v1/audit/verify, `admin.js platform-audit-verify`). Never pruned by the
-- retention job. Never holds passwords, tokens, codes, passkey material or message content.
CREATE TABLE platform_audit (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  at                INTEGER NOT NULL,
  request_id        TEXT,
  actor_account_id  TEXT,              -- NULL: the operator CLI, or a sign-in attempt by an unknown address
  actor_role        TEXT,
  action            TEXT NOT NULL,
  target_type       TEXT,
  target_id         TEXT,
  outcome           TEXT NOT NULL CHECK (outcome IN ('success', 'denied', 'failed')),
  reason            TEXT,
  network           TEXT,
  detail            TEXT,              -- JSON object of allowlisted fields
  prev_hash         TEXT NOT NULL,
  hash              TEXT NOT NULL
);
CREATE INDEX idx_platform_audit_at ON platform_audit(at);
CREATE INDEX idx_platform_audit_actor ON platform_audit(actor_account_id, at);
CREATE INDEX idx_platform_audit_target ON platform_audit(target_type, target_id, at);
CREATE INDEX idx_platform_audit_action ON platform_audit(action, at);

CREATE TRIGGER platform_audit_no_update BEFORE UPDATE ON platform_audit
BEGIN
  SELECT RAISE(ABORT, 'platform_audit is append-only');
END;

CREATE TRIGGER platform_audit_no_delete BEFORE DELETE ON platform_audit
BEGIN
  SELECT RAISE(ABORT, 'platform_audit is append-only');
END;

-- Incidents and service notices, tracked by platform admins (no support system existed). Never deleted.
CREATE TABLE platform_incidents (
  id                   TEXT PRIMARY KEY,
  title                TEXT NOT NULL,
  severity             TEXT NOT NULL CHECK (severity IN ('sev1', 'sev2', 'sev3', 'sev4')),
  status               TEXT NOT NULL CHECK (status IN ('open', 'investigating', 'mitigated', 'resolved')),
  kind                 TEXT NOT NULL CHECK (kind IN ('incident', 'maintenance', 'support')),
  assignee_account_id  TEXT,
  user_ref             TEXT,           -- a users.id this concerns, if any
  org_ref              TEXT,           -- an orgs.id this concerns, if any
  notice               TEXT,           -- text for a service announcement (not published anywhere automatically)
  created_by           TEXT NOT NULL,
  created_at           INTEGER NOT NULL,
  updated_at           INTEGER NOT NULL,
  resolved_at          INTEGER
);
CREATE INDEX idx_platform_incidents_status ON platform_incidents(status, updated_at);
CREATE INDEX idx_platform_incidents_org ON platform_incidents(org_ref);
CREATE INDEX idx_platform_incidents_user ON platform_incidents(user_ref);

CREATE TABLE platform_incident_events (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  incident_id       TEXT NOT NULL REFERENCES platform_incidents(id),
  at                INTEGER NOT NULL,
  actor_account_id  TEXT NOT NULL,
  kind              TEXT NOT NULL CHECK (kind IN ('note', 'status', 'severity', 'assign', 'created')),
  body              TEXT NOT NULL
);
CREATE INDEX idx_platform_incident_events_incident ON platform_incident_events(incident_id, at);
