-- 2026-10-20 (round 7 slice C3): workspace workflows (docs/WORKFLOWS.md). Additive only.
--
-- A workflow: when <trigger> happens in a workspace and its conditions hold, run its actions. Triggers are real server
-- events (a member joined or left, a role changed, a channel was created, a schedule); actions are a fixed list
-- (post an app update in a channel, add the member to channels, email workspace admins). Nothing runs arbitrary code.
-- A run is one workflow's handling of one event: unique per (workflow, event), so an event retried or seen twice runs
-- once. Runs are durable: queued, claimed with a lease, retried with backoff, then done, failed, skipped or cancelled.

CREATE TABLE workflows (
  id             TEXT PRIMARY KEY,
  org_id         TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  name           TEXT NOT NULL,
  trigger_type   TEXT NOT NULL,
  trigger_json   TEXT NOT NULL,                 -- trigger settings (a schedule's time and zone; empty object otherwise)
  conditions_json TEXT NOT NULL,                -- list of { field, op, value }
  actions_json   TEXT NOT NULL,                 -- list of { type, ... }
  enabled        INTEGER NOT NULL DEFAULT 1,
  owner_id       TEXT NOT NULL,                 -- runs with this person's rights, checked on every run
  next_run_at    INTEGER,                       -- schedule triggers only: UTC ms of the next occurrence
  rev            INTEGER NOT NULL DEFAULT 1,
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL
);
CREATE INDEX idx_workflows_org ON workflows(org_id, trigger_type);
CREATE INDEX idx_workflows_schedule ON workflows(next_run_at) WHERE next_run_at IS NOT NULL;

CREATE TABLE workflow_runs (
  id            TEXT PRIMARY KEY,
  workflow_id   TEXT NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
  org_id        TEXT NOT NULL,
  event_key     TEXT NOT NULL,                  -- the event's own id: idempotency
  event_json    TEXT NOT NULL,                  -- ids and names the actions need (no message content exists here)
  depth         INTEGER NOT NULL DEFAULT 0,     -- 0 for a person's action; +1 for events a workflow caused (loop guard)
  state         TEXT NOT NULL CHECK (state IN ('queued','running','done','failed','skipped','cancelled')),
  attempts      INTEGER NOT NULL DEFAULT 0,
  next_at       INTEGER NOT NULL,
  lease_until   INTEGER,
  dry_run       INTEGER NOT NULL DEFAULT 0,
  outcome_json  TEXT,                           -- per action: done / skipped / failed and why (no content)
  error_kind    TEXT,
  created_at    INTEGER NOT NULL,
  finished_at   INTEGER,
  UNIQUE (workflow_id, event_key)
);
CREATE INDEX idx_workflow_runs_due ON workflow_runs(state, next_at);
CREATE INDEX idx_workflow_runs_wf ON workflow_runs(workflow_id, created_at);
CREATE INDEX idx_workflow_runs_org ON workflow_runs(org_id, created_at);
