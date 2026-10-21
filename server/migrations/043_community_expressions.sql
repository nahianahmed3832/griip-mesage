-- Batch B9 (matrix EXP-04/05, Discord reference EXPR-CTRL-002): a community's own emoji and stickers. The image (PNG,
-- GIF or WebP, at most 256 KiB) is stored like profile photos under [id]; [sha256] lets every app check the image it
-- downloaded is the one the message meant. Plaintext, like the community's name. Additive.
CREATE TABLE community_expressions (
  id TEXT PRIMARY KEY,
  org_id TEXT NOT NULL REFERENCES orgs (id) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK (kind IN ('emoji', 'sticker')),
  name TEXT NOT NULL,
  mime TEXT NOT NULL,
  animated INTEGER NOT NULL DEFAULT 0,
  sha256 TEXT NOT NULL,
  size INTEGER NOT NULL,
  created_by TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX community_expressions_org ON community_expressions (org_id, kind, name);
