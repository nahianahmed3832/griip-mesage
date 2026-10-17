-- 2026-10-17: encrypted message history, files kept for it, the storage abstraction (disk or S3) and organization
-- deletion. Additive only: new tables and a defaulted column; no existing row is changed or removed.

-- An attachment a member asked to keep for their history (POST /v1/attachments/:id/keep): kept files stay
-- ATTACHMENT_KEEP_DAYS and count against the uploader's quota for kept files instead of the 7-day one.
ALTER TABLE attachments ADD COLUMN kept INTEGER NOT NULL DEFAULT 0;
CREATE INDEX idx_attachments_uploader_kept ON attachments(uploader_user_id, kept, expires_at);

-- Stored bytes whose row is already gone (expired, or its conversation deleted) but whose deletion from the store
-- failed or was deferred; the retention job retries them.
CREATE TABLE pending_blob_deletes (
  kind        TEXT NOT NULL CHECK (kind IN ('attachments','profile-photos')),
  blob_id     TEXT NOT NULL,
  attempts    INTEGER NOT NULL DEFAULT 0,
  next_at     INTEGER NOT NULL,
  PRIMARY KEY (kind, blob_id)
);

-- Encrypted message history (docs/MESSAGE_HISTORY.md). Each account's devices encrypt their messages with a key
-- derived from the account identity key, which the server never has; the server stores opaque items under opaque
-- tags (HMACs of message and conversation ids under another derived key) so any of the account's devices, or a
-- restore with the recovery key, can fetch and decrypt them.
CREATE TABLE history_items (
  user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  tag         TEXT NOT NULL,              -- opaque item id (base64url HMAC)
  conv_tag    TEXT NOT NULL,              -- opaque conversation id (base64url HMAC)
  seq         INTEGER NOT NULL,           -- per-account sync cursor, increases on every write
  ts          INTEGER NOT NULL,           -- the message's time as the client gives it (ordering only)
  body        BLOB,                       -- ciphertext; NULL for a deletion marker
  size        INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL,
  PRIMARY KEY (user_id, tag)
);
CREATE UNIQUE INDEX idx_history_seq ON history_items(user_id, seq);
CREATE INDEX idx_history_conv ON history_items(user_id, conv_tag, ts);
CREATE INDEX idx_history_tombstones ON history_items(updated_at) WHERE body IS NULL;

CREATE TABLE history_accounts (
  user_id     TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  next_seq    INTEGER NOT NULL,
  items       INTEGER NOT NULL,
  bytes       INTEGER NOT NULL
);

-- Organizations deleted by their owners: kept after the organization itself is gone, so a retried request gets the
-- same answer and administrators can see what was removed. No content, names hashed.
CREATE TABLE org_deletions (
  org_id         TEXT PRIMARY KEY,
  name_sha256    TEXT NOT NULL,
  deleted_by     TEXT NOT NULL,
  deleted_at     INTEGER NOT NULL,
  request_id     TEXT,
  members        INTEGER NOT NULL,
  channels       INTEGER NOT NULL,
  files          INTEGER NOT NULL
);
