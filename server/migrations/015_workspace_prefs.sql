-- 2026-10-19: workspace organisation round (docs/WORKSPACE_ADMINISTRATION.md). Additive only: no existing row changes.

-- An organisation's avatar: a JPEG in the blob store under "<orgId>-<photo_rev>" (kind profile-photos), like people's
-- photos. NULL means no avatar.
ALTER TABLE orgs ADD COLUMN photo_rev INTEGER;

-- Personal navigation preferences, one document per person and workspace ("personal" or an organisation id): starred
-- conversations, custom sections and their order, collapsed groups and per-conversation notification levels. They
-- follow the person to every device. Only the SQLite store uses this table; with MONGODB_DOMAINS including
-- "preferences" the same documents live in MongoDB instead (docs/MONGODB.md). No message content is ever stored here.
CREATE TABLE nav_preferences (
  user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  workspace_id TEXT NOT NULL,
  doc          TEXT NOT NULL,          -- JSON, validated by src/preferences.ts before it is written
  rev          INTEGER NOT NULL,       -- optimistic concurrency: a write names the revision it was based on
  updated_at   INTEGER NOT NULL,
  PRIMARY KEY (user_id, workspace_id)
);
CREATE INDEX idx_nav_preferences_workspace ON nav_preferences(workspace_id);
