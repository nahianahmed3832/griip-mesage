-- 2026-10-21: community-first rebuild (docs/COMMUNITY_REBUILD_2026-10-21.md). Additive only: no row is changed or
-- removed here. Branches and teams are converted to roles and categories by src/communityMigration.ts, which records
-- what it converted in community_migration_runs; their tables stay until a later, separate cleanup.

-- Community profile: listed in discovery (public) or not (private, every community before this), how people get in
-- (invite only, or open: anyone may join from discovery), and rules shown before joining and on the community home.
ALTER TABLE orgs ADD COLUMN visibility TEXT NOT NULL DEFAULT 'private' CHECK (visibility IN ('private', 'public'));
ALTER TABLE orgs ADD COLUMN join_policy TEXT NOT NULL DEFAULT 'invite' CHECK (join_policy IN ('invite', 'open'));
ALTER TABLE orgs ADD COLUMN rules TEXT;
CREATE INDEX idx_orgs_public ON orgs(visibility, name) WHERE visibility = 'public';

-- Categories group a community's channels (at most 50 channels each). Deleting one leaves its channels uncategorised.
CREATE TABLE channel_categories (
  id          TEXT PRIMARY KEY,
  org_id      TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  position    INTEGER NOT NULL,
  created_by  TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);
CREATE INDEX idx_channel_categories_org ON channel_categories(org_id, position);

-- Where a channel sits (category, order) and what it is for: an ordinary text channel or a forum (posts with titles
-- and their replies, both inside the end-to-end encrypted messages).
ALTER TABLE conversations ADD COLUMN category_id TEXT REFERENCES channel_categories(id) ON DELETE SET NULL;
ALTER TABLE conversations ADD COLUMN position INTEGER NOT NULL DEFAULT 0;
ALTER TABLE conversations ADD COLUMN format TEXT NOT NULL DEFAULT 'text' CHECK (format IN ('text', 'forum'));
CREATE INDEX idx_conversations_category ON conversations(category_id);

-- Permission overwrites on a category or a channel, for @everyone ('everyone') or a custom role (its id). Allow and
-- deny are JSON arrays of channel capability keys (src/permissions/channel.ts). Removing the role removes its rows.
CREATE TABLE channel_overwrites (
  target_type TEXT NOT NULL CHECK (target_type IN ('category', 'channel')),
  target_id   TEXT NOT NULL,
  org_id      TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  subject     TEXT NOT NULL,
  allow       TEXT NOT NULL,
  deny        TEXT NOT NULL,
  updated_by  TEXT NOT NULL,
  updated_at  INTEGER NOT NULL,
  PRIMARY KEY (target_type, target_id, subject)
);
CREATE INDEX idx_channel_overwrites_org ON channel_overwrites(org_id);

-- Bans: removed from the community and kept out of every way back in (invites, open joining) until lifted.
CREATE TABLE org_bans (
  org_id     TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  reason     TEXT,
  banned_by  TEXT NOT NULL,
  banned_at  INTEGER NOT NULL,
  PRIMARY KEY (org_id, user_id)
);

-- An invitation may hand out one custom role on joining (branch and team invitations become this).
ALTER TABLE invitations ADD COLUMN grant_role_id TEXT REFERENCES org_roles(id) ON DELETE SET NULL;

-- What src/communityMigration.ts converted: one row per branch or team, with the role and category it became.
CREATE TABLE community_migration_runs (
  source_type TEXT NOT NULL CHECK (source_type IN ('branch', 'team')),
  source_id   TEXT NOT NULL,
  org_id      TEXT NOT NULL,
  role_id     TEXT,
  category_id TEXT,
  channels    INTEGER NOT NULL,
  members     INTEGER NOT NULL,
  converted_at INTEGER NOT NULL,
  PRIMARY KEY (source_type, source_id)
);
