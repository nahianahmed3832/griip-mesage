-- Meldiq relay schema v1.
-- Permanent organisational metadata and temporary delivery data are kept in clearly
-- separated tables. No table stores message plaintext. Message envelopes exist only in
-- `delivery_queue` (ciphertext, max 7 days) and are deleted on acknowledgement or expiry.

-- ───────────────────────── Identity & devices (permanent) ─────────────────────────
CREATE TABLE users (
  id             TEXT PRIMARY KEY,
  username       TEXT NOT NULL UNIQUE COLLATE NOCASE,
  display_name   TEXT NOT NULL,
  identity_key   TEXT NOT NULL,              -- base64 Ed25519 public key (account identity)
  avatar_color   INTEGER NOT NULL DEFAULT 0,
  status_text    TEXT,
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL,
  disabled_at    INTEGER
);

CREATE TABLE devices (
  id              TEXT PRIMARY KEY,
  user_id         TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name            TEXT NOT NULL,
  signing_key     TEXT NOT NULL UNIQUE,      -- base64 Ed25519 public key of this device
  encryption_key  TEXT NOT NULL,             -- base64 X25519 public key (HPKE) of this device
  certificate     TEXT NOT NULL,             -- base64 Ed25519 signature by identity key
  created_at      INTEGER NOT NULL,
  last_seen_at    INTEGER,
  revoked_at      INTEGER
);
CREATE INDEX idx_devices_user ON devices(user_id);

-- ───────────────────────── Auth (short-lived) ─────────────────────────
CREATE TABLE auth_challenges (
  nonce       TEXT PRIMARY KEY,
  created_at  INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL
);

CREATE TABLE sessions (
  token_hash  TEXT PRIMARY KEY,              -- sha256(token); raw token never stored
  user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id   TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  created_at  INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL
);
CREATE INDEX idx_sessions_device ON sessions(device_id);

CREATE TABLE pairing_requests (
  id                 TEXT PRIMARY KEY,       -- secret polling id held by the new device
  code_hash          TEXT NOT NULL UNIQUE,   -- sha256(short code typed/scanned on existing device)
  device_name        TEXT NOT NULL,
  signing_key        TEXT NOT NULL,
  encryption_key     TEXT NOT NULL,
  transfer_key       TEXT NOT NULL,          -- ephemeral X25519 key for sealing identity material
  approved_user_id   TEXT,
  certificate        TEXT,
  sealed_identity    TEXT,                   -- HPKE ciphertext, opaque to the server
  sealed_enc         TEXT,
  created_at         INTEGER NOT NULL,
  expires_at         INTEGER NOT NULL
);

-- ───────────────────────── Organisation hierarchy (permanent) ─────────────────────────
CREATE TABLE orgs (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  slug        TEXT NOT NULL UNIQUE COLLATE NOCASE,
  description TEXT,
  created_by  TEXT NOT NULL REFERENCES users(id),
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);

CREATE TABLE org_members (
  org_id     TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role       TEXT NOT NULL CHECK (role IN ('OWNER','ADMIN','MEMBER','GUEST')),
  title      TEXT,
  joined_at  INTEGER NOT NULL,
  PRIMARY KEY (org_id, user_id)
);
CREATE INDEX idx_org_members_user ON org_members(user_id);

CREATE TABLE branches (
  id          TEXT PRIMARY KEY,
  org_id      TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  description TEXT,
  location    TEXT,
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL,
  archived_at INTEGER
);
CREATE INDEX idx_branches_org ON branches(org_id);

CREATE TABLE branch_members (
  branch_id  TEXT NOT NULL REFERENCES branches(id) ON DELETE CASCADE,
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role       TEXT NOT NULL CHECK (role IN ('MANAGER','MEMBER')),
  joined_at  INTEGER NOT NULL,
  PRIMARY KEY (branch_id, user_id)
);
CREATE INDEX idx_branch_members_user ON branch_members(user_id);

CREATE TABLE departments (
  id          TEXT PRIMARY KEY,
  org_id      TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  branch_id   TEXT NOT NULL REFERENCES branches(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  description TEXT,
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);
CREATE INDEX idx_departments_branch ON departments(branch_id);

-- branch_id NULL  => cross-branch (organisation-level) team.
CREATE TABLE teams (
  id            TEXT PRIMARY KEY,
  org_id        TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  branch_id     TEXT REFERENCES branches(id) ON DELETE CASCADE,
  department_id TEXT REFERENCES departments(id) ON DELETE SET NULL,
  name          TEXT NOT NULL,
  description   TEXT,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL,
  archived_at   INTEGER
);
CREATE INDEX idx_teams_org ON teams(org_id);
CREATE INDEX idx_teams_branch ON teams(branch_id);

CREATE TABLE team_members (
  team_id    TEXT NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role       TEXT NOT NULL CHECK (role IN ('LEAD','MEMBER')),
  joined_at  INTEGER NOT NULL,
  PRIMARY KEY (team_id, user_id)
);
CREATE INDEX idx_team_members_user ON team_members(user_id);

CREATE TABLE invitations (
  id           TEXT PRIMARY KEY,
  org_id       TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  code_hash    TEXT NOT NULL UNIQUE,         -- sha256(code); the code itself is shown once
  code_hint    TEXT NOT NULL,                -- last 4 chars, for management UI
  role         TEXT NOT NULL CHECK (role IN ('ADMIN','MEMBER','GUEST')),
  branch_id    TEXT REFERENCES branches(id) ON DELETE CASCADE,
  team_id      TEXT REFERENCES teams(id) ON DELETE CASCADE,
  max_uses     INTEGER NOT NULL,
  uses         INTEGER NOT NULL DEFAULT 0,
  created_by   TEXT NOT NULL REFERENCES users(id),
  created_at   INTEGER NOT NULL,
  expires_at   INTEGER NOT NULL,
  revoked_at   INTEGER
);
CREATE INDEX idx_invitations_org ON invitations(org_id);

-- ───────────────────────── Conversations (metadata only) ─────────────────────────
-- type DM | GROUP | CHANNEL. Channels carry an org scope; DMs/groups do not.
CREATE TABLE conversations (
  id           TEXT PRIMARY KEY,
  type         TEXT NOT NULL CHECK (type IN ('DM','GROUP','CHANNEL')),
  org_id       TEXT REFERENCES orgs(id) ON DELETE CASCADE,
  scope        TEXT CHECK (scope IN ('ORG','BRANCH','TEAM')),
  scope_id     TEXT,
  kind         TEXT CHECK (kind IN ('PUBLIC','PRIVATE','ANNOUNCEMENT')),
  name         TEXT,
  topic        TEXT,
  dm_key       TEXT UNIQUE,                  -- "userA|userB" (sorted) for DMs
  auto_join    INTEGER NOT NULL DEFAULT 0,   -- channel: eligible users are added automatically
  created_by   TEXT NOT NULL REFERENCES users(id),
  created_at   INTEGER NOT NULL,
  updated_at   INTEGER NOT NULL,
  archived_at  INTEGER
);
CREATE INDEX idx_conversations_org ON conversations(org_id);

CREATE TABLE conversation_members (
  conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  user_id         TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role            TEXT NOT NULL CHECK (role IN ('ADMIN','MEMBER')),
  joined_at       INTEGER NOT NULL,
  PRIMARY KEY (conversation_id, user_id)
);
CREATE INDEX idx_conv_members_user ON conversation_members(user_id);

-- ───────────────────────── Temporary delivery (TTL ≤ 7 days) ─────────────────────────
CREATE TABLE delivery_queue (
  id                   INTEGER PRIMARY KEY AUTOINCREMENT,
  recipient_device_id  TEXT NOT NULL,
  kind                 TEXT NOT NULL CHECK (kind IN ('envelope','receipt')),
  conversation_id      TEXT NOT NULL,
  message_id           TEXT NOT NULL,
  sender_user_id       TEXT NOT NULL,
  sender_device_id     TEXT NOT NULL,
  payload              TEXT NOT NULL,         -- JSON: ciphertext envelope + per-device key wrap
  created_at           INTEGER NOT NULL,
  expires_at           INTEGER NOT NULL
);
CREATE INDEX idx_queue_recipient ON delivery_queue(recipient_device_id, id);
CREATE INDEX idx_queue_expiry ON delivery_queue(expires_at);
CREATE UNIQUE INDEX idx_queue_unique ON delivery_queue(recipient_device_id, kind, message_id, sender_device_id);

-- Idempotency / receipt routing record for submitted messages. No content.
CREATE TABLE submitted_messages (
  message_id        TEXT PRIMARY KEY,
  sender_device_id  TEXT NOT NULL,
  sender_user_id    TEXT NOT NULL,
  conversation_id   TEXT NOT NULL,
  recipient_count   INTEGER NOT NULL,
  server_ts         INTEGER NOT NULL,
  expires_at        INTEGER NOT NULL
);
CREATE INDEX idx_submitted_expiry ON submitted_messages(expires_at);

CREATE TABLE attachments (
  id               TEXT PRIMARY KEY,         -- 128-bit random, unguessable
  conversation_id  TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  uploader_user_id TEXT NOT NULL,
  size             INTEGER NOT NULL,
  created_at       INTEGER NOT NULL,
  expires_at       INTEGER NOT NULL
);
CREATE INDEX idx_attachments_expiry ON attachments(expires_at);

-- ───────────────────────── Calls (operational state) ─────────────────────────
CREATE TABLE calls (
  id               TEXT PRIMARY KEY,
  conversation_id  TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  room_name        TEXT NOT NULL UNIQUE,
  created_by       TEXT NOT NULL REFERENCES users(id),
  video            INTEGER NOT NULL DEFAULT 0,
  state            TEXT NOT NULL CHECK (state IN ('RINGING','ACTIVE','ENDED')),
  created_at       INTEGER NOT NULL,
  answered_at      INTEGER,
  ended_at         INTEGER,
  end_reason       TEXT
);
CREATE INDEX idx_calls_conversation ON calls(conversation_id, state);

CREATE TABLE call_participants (
  call_id     TEXT NOT NULL REFERENCES calls(id) ON DELETE CASCADE,
  user_id     TEXT NOT NULL,
  device_id   TEXT NOT NULL,
  joined_at   INTEGER NOT NULL,
  left_at     INTEGER,
  PRIMARY KEY (call_id, device_id)
);

-- Security-relevant audit trail. Never contains message content or secrets.
CREATE TABLE audit_log (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  at          INTEGER NOT NULL,
  actor_id    TEXT,
  org_id      TEXT,
  action      TEXT NOT NULL,
  target      TEXT,
  detail      TEXT
);
CREATE INDEX idx_audit_org ON audit_log(org_id, at);
