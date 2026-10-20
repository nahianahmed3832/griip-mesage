-- 2026-10-20 (Meldiq 2.0, docs/product/MELDIQ_2_DECISIONS.md). Additive only; rolling back to 0.8.x ignores both.

-- A workspace is either a team (the default, every existing one) or a community: the same model, members, roles and
-- channels, with community defaults in the apps (no branches, teams or HR tools up front; open invitations; a welcome
-- channel).
ALTER TABLE orgs ADD COLUMN kind TEXT NOT NULL DEFAULT 'team' CHECK (kind IN ('team', 'community'));

-- Product analytics as daily counts only (docs/product/EXPERIMENTS.md): no user id, no device id, no content. [dims]
-- is the event's allowed properties as canonical JSON; [experiment]/[variant] are '' on the overall row and set on one
-- extra row per experiment the sender was in.
CREATE TABLE analytics_daily (
  day         TEXT NOT NULL,            -- YYYY-MM-DD, UTC
  event       TEXT NOT NULL,
  dims        TEXT NOT NULL,
  platform    TEXT NOT NULL,
  experiment  TEXT NOT NULL DEFAULT '',
  variant     TEXT NOT NULL DEFAULT '',
  count       INTEGER NOT NULL DEFAULT 0,
  value_count INTEGER NOT NULL DEFAULT 0,
  value_sum   REAL NOT NULL DEFAULT 0,
  PRIMARY KEY (day, event, dims, platform, experiment, variant)
);
