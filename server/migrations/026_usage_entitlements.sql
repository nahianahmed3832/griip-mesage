-- 2026-10-20 (round 7 slice C7): usage metering and per-workspace limits (docs/USAGE_AND_PLANS.md). Additive only.
--
-- usage_daily: one row per workspace, UTC day and metric. Counters (API requests, webhook deliveries, workflow runs,
-- file transfer…) are added to as they happen; gauges (seats, stored bytes, call minutes for a day) are recomputed and
-- replaced. Only counts and sizes: never content, names or who did what.
CREATE TABLE usage_daily (
  org_id   TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  day      TEXT NOT NULL,                    -- "YYYY-MM-DD", UTC
  metric   TEXT NOT NULL,
  value    INTEGER NOT NULL,
  PRIMARY KEY (org_id, day, metric)
);

-- A workspace's own limits, set by platform admins (never by the workspace itself), over the server-wide ones.
CREATE TABLE org_entitlements (
  org_id       TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  key          TEXT NOT NULL,
  limit_value  INTEGER NOT NULL,
  set_by       TEXT NOT NULL,
  set_at       INTEGER NOT NULL,
  PRIMARY KEY (org_id, key)
);
