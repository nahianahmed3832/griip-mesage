-- 2026-10-23: scoped provisioning of synthetic load-test accounts (docs/LOADTEST_PROVISIONING.md). Additive only.
--
-- A registration invite with purpose 'loadtest' is issued only by the operator CLI (admin.js loadtest-invite). An
-- account registered with one is synthetic: it is counted against LOADTEST_REGISTRATION_HOURLY_LIMIT / _DAILY_LIMIT
-- instead of the public per-network limits, and it can only join communities created by synthetic accounts.
-- Numbered 047 so it can land before or after 046 (migrations apply by version, gaps allowed).

ALTER TABLE registration_invites ADD COLUMN purpose TEXT NOT NULL DEFAULT 'registration';
ALTER TABLE users ADD COLUMN synthetic INTEGER NOT NULL DEFAULT 0;
ALTER TABLE orgs ADD COLUMN synthetic INTEGER NOT NULL DEFAULT 0;

-- One row per synthetic account created. The quota counts rows in the last hour and the last 24 hours, so it survives
-- restarts and deploys (unlike the public limits, which are in memory). Rows hold no credentials.
CREATE TABLE loadtest_registrations (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id     TEXT NOT NULL,
  invite_id   TEXT NOT NULL,
  network     TEXT NOT NULL,
  created_at  INTEGER NOT NULL
);
CREATE INDEX idx_loadtest_registrations_time ON loadtest_registrations(created_at);
