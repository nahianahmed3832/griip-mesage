-- T1.6: invite-only registration and attachment quotas.

-- Registration invites are issued only by the operator CLI (dist/admin.js reg-invite); no HTTP
-- endpoint creates them. The code is shown once; only its SHA-256 is stored.
CREATE TABLE registration_invites (
  id          TEXT PRIMARY KEY,
  code_hash   TEXT NOT NULL UNIQUE,
  code_hint   TEXT NOT NULL,                -- last 4 characters, for listing and revoking
  note        TEXT,
  max_uses    INTEGER NOT NULL,
  uses        INTEGER NOT NULL DEFAULT 0,
  created_at  INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL,
  revoked_at  INTEGER
);

-- Per-user quota checks sum recent uploads.
CREATE INDEX idx_attachments_uploader ON attachments(uploader_user_id, created_at);
