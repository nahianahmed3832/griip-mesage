-- 2026-10-16: federation between Griip servers (docs/FEDERATION_PROTOCOL.md). Additive only: every existing user
-- stays a local account (home_server_id NULL) and every existing conversation keeps its id.

-- A remote user's local stand-in ("shadow"): same user id and device ids as on their home server, bound to that
-- server's id. Shadows can't sign in; their keys come only from their home server.
ALTER TABLE users ADD COLUMN home_server_id TEXT;
ALTER TABLE users ADD COLUMN remote_synced_at INTEGER;
CREATE INDEX idx_users_home ON users(home_server_id) WHERE home_server_id IS NOT NULL;

-- Verified peer servers (cache of descriptors resolved through the core nodes).
CREATE TABLE federation_peers (
  server_id    TEXT PRIMARY KEY,
  domain       TEXT NOT NULL,
  descriptor   TEXT NOT NULL,
  seq          INTEGER NOT NULL,
  state        TEXT NOT NULL,
  final        TEXT,
  verified_at  INTEGER NOT NULL,
  agreeing     INTEGER NOT NULL,
  updated_at   INTEGER NOT NULL
);
-- Domain → server bindings that reached quorum (pinned; a different server for a known domain is reported).
CREATE TABLE federation_domains (
  domain       TEXT PRIMARY KEY,
  server_id    TEXT NOT NULL,
  verified_at  INTEGER NOT NULL
);

-- Outgoing encrypted items per destination server: bounded, ordered, retried, expiring (≤ 7 days). No content keys.
CREATE TABLE federation_outbox (
  id               INTEGER PRIMARY KEY AUTOINCREMENT,
  peer_server_id   TEXT NOT NULL,
  kind             TEXT NOT NULL CHECK (kind IN ('envelope','receipt','attachment')),
  item_key         TEXT NOT NULL UNIQUE,
  body             TEXT NOT NULL,
  size             INTEGER NOT NULL,
  created_at       INTEGER NOT NULL,
  expires_at       INTEGER NOT NULL,
  attempts         INTEGER NOT NULL DEFAULT 0,
  next_attempt_at  INTEGER NOT NULL,
  last_error       TEXT
);
CREATE INDEX idx_fed_outbox_peer ON federation_outbox(peer_server_id, id);
CREATE INDEX idx_fed_outbox_expiry ON federation_outbox(expires_at);

-- Incoming item keys already accepted (dedupe across retries, kept as long as a sender may retry).
CREATE TABLE federation_inbox (
  item_key        TEXT PRIMARY KEY,
  peer_server_id  TEXT NOT NULL,
  expires_at      INTEGER NOT NULL
);
CREATE INDEX idx_fed_inbox_expiry ON federation_inbox(expires_at);

-- Request nonces seen (replay protection for signed server-to-server requests).
CREATE TABLE federation_nonces (
  nonce           TEXT PRIMARY KEY,
  peer_server_id  TEXT NOT NULL,
  expires_at      INTEGER NOT NULL
);

-- Servers this server refuses to federate with (operator policy).
CREATE TABLE federation_blocks (
  server_id   TEXT PRIMARY KEY,
  reason      TEXT,
  created_at  INTEGER NOT NULL
);

-- People a user has blocked (local or remote). Their messages are dropped for that user.
CREATE TABLE user_blocks (
  user_id          TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  blocked_user_id  TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at       INTEGER NOT NULL,
  PRIMARY KEY (user_id, blocked_user_id)
);
