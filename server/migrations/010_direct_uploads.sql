-- 2026-10-17: direct uploads from the app to S3 (docs/STORAGE_AND_DATA.md). The server authorizes an upload and
-- presigns it; the app sends the ciphertext to S3; the server checks the stored object (size, SHA-256) before it
-- becomes an attachment. Until then the upload is a session here: it reserves its size against the uploader's quotas,
-- and when it expires unfinished its object or parts are deleted. Additive only.
CREATE TABLE attachment_uploads (
  id               TEXT PRIMARY KEY,         -- the attachment id the object will have (128-bit random)
  user_id          TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id        TEXT NOT NULL,
  conversation_id  TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  size             INTEGER NOT NULL,
  sha256           TEXT NOT NULL,            -- base64 SHA-256 of the whole ciphertext, as the app declared it
  multipart_id     TEXT,                     -- the store's upload id for a multipart upload
  part_size        INTEGER,
  created_at       INTEGER NOT NULL,
  expires_at       INTEGER NOT NULL
);
CREATE INDEX idx_attachment_uploads_user ON attachment_uploads(user_id);
CREATE INDEX idx_attachment_uploads_expiry ON attachment_uploads(expires_at);

-- Uploads that ended before completing (expired, cancelled, or their conversation deleted) and still have an object or
-- parts in the store: the retention job removes them, retrying with a growing delay.
CREATE TABLE abandoned_uploads (
  id               TEXT PRIMARY KEY,
  multipart_id     TEXT,
  attempts         INTEGER NOT NULL DEFAULT 0,
  next_at          INTEGER NOT NULL
);
