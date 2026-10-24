-- 2026-10-24: AI Workforce autonomy, limits and triggers (docs/AI_WORKFORCE.md §5b). Additive only.

-- How much an installed agent does on its own, enforced by the server:
--   manual      only people's own requests (Ask, @mention); never Agent Manager delegation or triggers; results reviewed
--   assisted    people's requests and Agent Manager delegation; results reviewed by people before delivery
--   guarded     as assisted, but internal results are delivered without review; restricted actions still need approval
--   continuous  as guarded, and its triggers (schedules, webhooks, handoffs) may start work with nobody present
-- Existing installs keep what they did: review → assisted, auto → guarded. execution_mode stays, derived from it.
ALTER TABLE ai_agent_installs ADD COLUMN autonomy TEXT NOT NULL DEFAULT 'assisted' CHECK (autonomy IN ('manual', 'assisted', 'guarded', 'continuous'));
UPDATE ai_agent_installs SET autonomy = CASE execution_mode WHEN 'auto' THEN 'guarded' ELSE 'assisted' END;
-- Per-install limits within the skill's own (NULL: the skill's).
ALTER TABLE ai_agent_installs ADD COLUMN max_runtime_sec INTEGER;
ALTER TABLE ai_agent_installs ADD COLUMN max_attempts INTEGER;
-- When triggers may start work: JSON {"from":"09:00","to":"18:00","timeZone":"Asia/Dhaka"}; NULL: any time.
ALTER TABLE ai_agent_installs ADD COLUMN active_hours TEXT;

-- What started a job besides a person: the trigger, the job whose result it hands on, and how many handoffs deep it is.
ALTER TABLE ai_jobs ADD COLUMN trigger_id TEXT;
ALTER TABLE ai_jobs ADD COLUMN source_job_id TEXT;
ALTER TABLE ai_jobs ADD COLUMN chain INTEGER NOT NULL DEFAULT 0;
CREATE INDEX idx_ai_jobs_trigger ON ai_jobs(trigger_id, created_at);

-- Durable triggers: work an agent starts with nobody present, as the person who set it up (their permissions are
-- checked again each time it fires). Only for agents in continuous autonomy.
CREATE TABLE ai_triggers (
  id                TEXT PRIMARY KEY,
  org_id            TEXT NOT NULL,
  install_id        TEXT NOT NULL,
  kind              TEXT NOT NULL CHECK (kind IN ('schedule', 'webhook', 'after_agent')),
  enabled           INTEGER NOT NULL DEFAULT 1,
  label             TEXT NOT NULL,
  brief             TEXT NOT NULL,              -- the request each run makes
  conversation_id   TEXT,                       -- channel the request and answers show in (the agent must be assigned)
  config            TEXT NOT NULL,              -- JSON, per kind (schedule: when; after_agent: on which outcome)
  source_install_id TEXT,                       -- after_agent: the agent whose finished work starts this one
  secret_hash       TEXT,                       -- webhook: SHA-256 of its secret (the secret is shown once)
  run_as            TEXT NOT NULL,              -- whose permissions its jobs use: the person who set it up
  next_run_at       INTEGER,                    -- schedule: the next slot (claimed atomically, so a slot fires once)
  last_fired_at     INTEGER,
  last_job_id       TEXT,
  last_error        TEXT,
  failures          INTEGER NOT NULL DEFAULT 0, -- consecutive; 5 switch it off
  rev               INTEGER NOT NULL,
  created_by        TEXT NOT NULL,
  created_at        INTEGER NOT NULL,
  updated_at        INTEGER NOT NULL
);
CREATE INDEX idx_ai_triggers_due ON ai_triggers(kind, enabled, next_run_at);
CREATE INDEX idx_ai_triggers_install ON ai_triggers(install_id);
CREATE INDEX idx_ai_triggers_source ON ai_triggers(source_install_id) WHERE source_install_id IS NOT NULL;
