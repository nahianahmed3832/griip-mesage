-- 2026-10-20 (round 7 slice C5): user groups, guest expiry and policy, scheduled meetings (docs/WORKSPACE_C5.md).
-- Additive only: existing rows keep their meaning (no expiry, guests allowed, no groups, no meetings).

-- Groups: named sets of members (Marketing, Engineering…) to add to channels or meetings in one go. A group grants no
-- access by itself: adding it to a channel adds its current members, checked like adding them one by one.
CREATE TABLE user_groups (
  id           TEXT PRIMARY KEY,
  org_id       TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  name         TEXT NOT NULL,
  handle       TEXT NOT NULL,                  -- "marketing": unique per workspace, for @mentions in the apps
  description  TEXT,
  created_by   TEXT NOT NULL,
  created_at   INTEGER NOT NULL,
  updated_at   INTEGER NOT NULL,
  UNIQUE (org_id, handle)
);

CREATE TABLE user_group_members (
  group_id   TEXT NOT NULL REFERENCES user_groups(id) ON DELETE CASCADE,
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  added_by   TEXT NOT NULL,
  added_at   INTEGER NOT NULL,
  PRIMARY KEY (group_id, user_id)
);
CREATE INDEX idx_user_group_members_user ON user_group_members(user_id);

-- Guests: an optional end date per guest membership (set from the invitation, or by an admin), and a workspace policy.
ALTER TABLE org_members ADD COLUMN expires_at INTEGER;
ALTER TABLE invitations ADD COLUMN guest_days INTEGER;
ALTER TABLE orgs ADD COLUMN guests_allowed INTEGER NOT NULL DEFAULT 1;
ALTER TABLE orgs ADD COLUMN guest_max_days INTEGER;

-- Meetings: scheduled in a voice room (the workspace's dedicated meeting channels), never on a text channel. Joining is
-- joining that room's call. Title and times are readable by the server, like a channel's name.
CREATE TABLE meetings (
  id            TEXT PRIMARY KEY,
  org_id        TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  room_id       TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  title         TEXT NOT NULL,
  description   TEXT,
  starts_at     INTEGER NOT NULL,
  ends_at       INTEGER NOT NULL,
  time_zone     TEXT NOT NULL,
  organizer_id  TEXT NOT NULL,
  cancelled_at  INTEGER,
  notified_at   INTEGER,                        -- when the "starting soon" notice went out
  rev           INTEGER NOT NULL DEFAULT 1,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);
CREATE INDEX idx_meetings_org ON meetings(org_id, starts_at);
CREATE INDEX idx_meetings_soon ON meetings(starts_at) WHERE cancelled_at IS NULL AND notified_at IS NULL;

CREATE TABLE meeting_participants (
  meeting_id  TEXT NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
  user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  response    TEXT NOT NULL DEFAULT 'pending' CHECK (response IN ('pending','accepted','declined','tentative')),
  PRIMARY KEY (meeting_id, user_id)
);
CREATE INDEX idx_meeting_participants_user ON meeting_participants(user_id);
