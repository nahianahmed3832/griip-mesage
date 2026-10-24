-- 2026-10-24: Meldiq AI Workforce 1.0 (docs/AI_WORKFORCE.md). Additive only: new tables and new nullable columns.
-- No message content is ever stored here. Briefs, submitted files and agent output are content people chose to send to
-- an agent (not end-to-end encrypted, said so in every client); they're kept with their job and expire with it.

-- ───────────────────────── Model gateway (module ai) ─────────────────────────
-- What an operator turned on per model, and the last real verification of each capability. Routing uses only
-- capabilities whose last verification passed.
CREATE TABLE ai_model_capabilities (
  provider_id   TEXT NOT NULL,
  model         TEXT NOT NULL,
  capability    TEXT NOT NULL CHECK (capability IN ('text', 'vision', 'transcription', 'speech', 'image_generation', 'embeddings')),
  verified_at   INTEGER,
  verify_ok     INTEGER,
  verify_detail TEXT,                       -- status and error kind only
  PRIMARY KEY (provider_id, model, capability)
);

-- The operator's rank for "quality" routing (lower first) and whether this model may be chosen by `auto` routing.
ALTER TABLE ai_models ADD COLUMN quality_rank INTEGER;
ALTER TABLE ai_models ADD COLUMN auto_routable INTEGER NOT NULL DEFAULT 1;
-- Prices for capabilities not billed per token (operator-entered like the token prices; estimates only).
ALTER TABLE ai_models ADD COLUMN request_price_micro INTEGER;  -- per image or per speech request
ALTER TABLE ai_models ADD COLUMN minute_price_micro INTEGER;   -- per minute of transcribed audio

-- Routing policy per task category: a default model, ordered fallbacks, a preference, and a cost ceiling.
CREATE TABLE ai_routing (
  category        TEXT PRIMARY KEY,
  provider_id     TEXT,
  model           TEXT,
  fallbacks       TEXT NOT NULL DEFAULT '[]', -- JSON [{providerId, model}]
  prefer          TEXT NOT NULL DEFAULT 'quality' CHECK (prefer IN ('quality', 'cost', 'latency')),
  max_price_micro INTEGER,                    -- per million output tokens; NULL: no ceiling
  updated_by      TEXT,
  updated_at      INTEGER NOT NULL
);

-- Usage rows of workforce jobs name the job, the installed agent and the capability, and how the model was chosen.
ALTER TABLE ai_usage ADD COLUMN job_id TEXT;
ALTER TABLE ai_usage ADD COLUMN install_id TEXT;
ALTER TABLE ai_usage ADD COLUMN agent_id TEXT;
ALTER TABLE ai_usage ADD COLUMN capability TEXT;
ALTER TABLE ai_usage ADD COLUMN route TEXT;    -- e.g. "auto:reasoning default" or "auto:reasoning fallback 1"
CREATE INDEX idx_ai_usage_job ON ai_usage(job_id);
CREATE INDEX idx_ai_usage_org ON ai_usage(org_id, at);

-- ───────────────────────── Workforce: platform side ─────────────────────────
-- Publication state of each agent version. The definitions themselves (skill, schemas, tools) are code.
CREATE TABLE ai_agent_templates (
  agent_id     TEXT NOT NULL,
  version      INTEGER NOT NULL,
  status       TEXT NOT NULL CHECK (status IN ('draft', 'published', 'disabled', 'retired')),
  availability TEXT NOT NULL DEFAULT 'all' CHECK (availability IN ('all', 'entitled')),
  policy       TEXT NOT NULL DEFAULT '{}',    -- JSON baseline: maxRuntimeSec, maxJobsPerDay, forceReview
  updated_by   TEXT,
  created_at   INTEGER NOT NULL,
  updated_at   INTEGER NOT NULL,
  PRIMARY KEY (agent_id, version)
);

-- The platform's ceiling per tool: allowed, allowed with approval, or disabled.
CREATE TABLE ai_tool_policies (
  tool       TEXT PRIMARY KEY,
  mode       TEXT NOT NULL CHECK (mode IN ('allowed', 'approval', 'disabled')),
  updated_by TEXT,
  updated_at INTEGER NOT NULL
);

-- What one community may use: agents, categories, models, tools, concurrency. JSON document with a revision.
CREATE TABLE ai_org_entitlements (
  org_id     TEXT PRIMARY KEY,
  doc        TEXT NOT NULL,
  rev        INTEGER NOT NULL,
  updated_by TEXT,
  updated_at INTEGER NOT NULL
);

-- ───────────────────────── Workforce: a community's agents ─────────────────────────
CREATE TABLE ai_agent_installs (
  id               TEXT PRIMARY KEY,
  org_id           TEXT NOT NULL,
  agent_id         TEXT NOT NULL,
  live_agent       TEXT,                      -- agent_id while installed, NULL once uninstalled (one live install per agent)
  version          INTEGER NOT NULL,          -- pinned; upgrading is the community's own action
  state            TEXT NOT NULL CHECK (state IN ('active', 'disabled', 'uninstalled')),
  display_name     TEXT NOT NULL,
  avatar_blob      TEXT,                      -- blob key of an uploaded image
  avatar_sha256    TEXT,
  avatar_color     TEXT,
  description      TEXT NOT NULL,
  role_title       TEXT NOT NULL,
  language         TEXT,
  style            TEXT NOT NULL DEFAULT 'balanced',
  instructions     TEXT NOT NULL DEFAULT '',
  tools            TEXT NOT NULL,             -- JSON granted tool keys (a subset of the skill's)
  execution_mode   TEXT NOT NULL DEFAULT 'review' CHECK (execution_mode IN ('review', 'auto')),
  max_jobs_per_day INTEGER,
  max_concurrent   INTEGER,
  invokers         TEXT NOT NULL DEFAULT '{"everyone":true,"roles":[]}',
  rev              INTEGER NOT NULL,
  installed_by     TEXT NOT NULL,
  created_at       INTEGER NOT NULL,
  updated_at       INTEGER NOT NULL
);
CREATE UNIQUE INDEX idx_ai_agent_installs_live ON ai_agent_installs(org_id, live_agent) WHERE live_agent IS NOT NULL;
CREATE INDEX idx_ai_agent_installs_org ON ai_agent_installs(org_id, state);

-- Channels an installed agent works in. Never all channels by default.
CREATE TABLE ai_agent_channels (
  install_id      TEXT NOT NULL,
  conversation_id TEXT NOT NULL,
  org_id          TEXT NOT NULL,
  activation      TEXT NOT NULL CHECK (activation IN ('mention', 'command', 'explicit')),
  added_by        TEXT NOT NULL,
  added_at        INTEGER NOT NULL,
  PRIMARY KEY (install_id, conversation_id)
);
CREATE INDEX idx_ai_agent_channels_conv ON ai_agent_channels(conversation_id);

-- ───────────────────────── Workforce: jobs ─────────────────────────
CREATE TABLE ai_jobs (
  id               TEXT PRIMARY KEY,
  org_id           TEXT NOT NULL,
  install_id       TEXT NOT NULL,
  agent_id         TEXT NOT NULL,
  version          INTEGER NOT NULL,
  skill_id         TEXT NOT NULL,
  requested_by     TEXT NOT NULL,
  conversation_id  TEXT,
  thread_root      TEXT,
  parent_job_id    TEXT,
  root_job_id      TEXT NOT NULL,
  depth            INTEGER NOT NULL DEFAULT 0,
  depends_on       TEXT NOT NULL DEFAULT '[]', -- JSON job ids that must complete first
  waiting_on       TEXT,                       -- JSON job ids a coordinator waits for (NULL: not waiting)
  state            TEXT NOT NULL CHECK (state IN ('queued', 'planning', 'running', 'awaiting_input', 'awaiting_approval', 'completed', 'failed', 'cancelled')),
  input            TEXT NOT NULL,              -- JSON, validated against the skill's input schema
  output           TEXT,                       -- JSON, validated against the skill's output schema
  checkpoint       TEXT,                       -- JSON, the skill's own state between phases
  revision         INTEGER NOT NULL DEFAULT 0,
  progress         INTEGER NOT NULL DEFAULT 0,
  progress_note    TEXT,
  attempts         INTEGER NOT NULL DEFAULT 0,
  max_attempts     INTEGER NOT NULL,
  next_at          INTEGER NOT NULL,
  lease_until      INTEGER,
  worker           TEXT,
  deadline_at      INTEGER NOT NULL,
  idempotency_key  TEXT,
  cancel_requested INTEGER NOT NULL DEFAULT 0,
  error_code       TEXT,
  error_detail     TEXT,
  created_at       INTEGER NOT NULL,
  updated_at       INTEGER NOT NULL,
  started_at       INTEGER,
  finished_at      INTEGER,
  expires_at       INTEGER NOT NULL
);
CREATE UNIQUE INDEX idx_ai_jobs_idem ON ai_jobs(org_id, requested_by, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_ai_jobs_due ON ai_jobs(state, next_at);
CREATE INDEX idx_ai_jobs_org ON ai_jobs(org_id, created_at);
CREATE INDEX idx_ai_jobs_root ON ai_jobs(root_job_id);
CREATE INDEX idx_ai_jobs_expiry ON ai_jobs(expires_at);

-- What happened to a job, in order (states, steps, tool calls, model calls, errors). No content.
CREATE TABLE ai_job_events (
  id      INTEGER PRIMARY KEY AUTOINCREMENT,
  job_id  TEXT NOT NULL,
  at      INTEGER NOT NULL,
  kind    TEXT NOT NULL,
  detail  TEXT NOT NULL DEFAULT '{}'
);
CREATE INDEX idx_ai_job_events_job ON ai_job_events(job_id, id);

-- Files a person submitted to an agent, and files agents produced. Bytes in the blob store (disk or S3).
CREATE TABLE ai_files (
  id              TEXT PRIMARY KEY,
  org_id          TEXT NOT NULL,
  role            TEXT NOT NULL CHECK (role IN ('input', 'artifact', 'avatar')),
  job_id          TEXT,
  install_id      TEXT,
  agent_id        TEXT,
  skill_version   INTEGER,
  conversation_id TEXT,                        -- artifacts delivered to a channel: its members may read them
  uploaded_by     TEXT,
  kind            TEXT,                        -- artifacts: 'preview', 'final', 'document', 'image', 'data'…
  name            TEXT NOT NULL,
  mime            TEXT NOT NULL,
  size            INTEGER NOT NULL,
  sha256          TEXT NOT NULL,
  blob_key        TEXT NOT NULL,
  artifact_version INTEGER NOT NULL DEFAULT 1,
  supersedes      TEXT,
  approval_state  TEXT NOT NULL DEFAULT 'not_required' CHECK (approval_state IN ('not_required', 'pending', 'approved', 'rejected', 'superseded')),
  created_at      INTEGER NOT NULL,
  expires_at      INTEGER NOT NULL,
  deleted_at      INTEGER
);
CREATE INDEX idx_ai_files_job ON ai_files(job_id);
CREATE INDEX idx_ai_files_org ON ai_files(org_id, role, created_at);
CREATE INDEX idx_ai_files_expiry ON ai_files(expires_at);

-- Approvals, bound to one action and one exact artifact version (id and SHA-256) or payload hash.
CREATE TABLE ai_approvals (
  id            TEXT PRIMARY KEY,
  org_id        TEXT NOT NULL,
  job_id        TEXT NOT NULL,
  action        TEXT NOT NULL,
  artifact_id   TEXT,
  artifact_sha  TEXT,
  payload_hash  TEXT,
  summary       TEXT NOT NULL,
  state         TEXT NOT NULL CHECK (state IN ('pending', 'approved', 'rejected', 'invalidated')),
  requested_at  INTEGER NOT NULL,
  decided_by    TEXT,
  decided_at    INTEGER,
  note          TEXT
);
CREATE INDEX idx_ai_approvals_job ON ai_approvals(job_id);
CREATE INDEX idx_ai_approvals_org ON ai_approvals(org_id, state);

-- What agents post into channels: NOT end-to-end encrypted (agents never hold message keys). Expire with the job.
CREATE TABLE ai_channel_posts (
  id              TEXT PRIMARY KEY,
  org_id          TEXT NOT NULL,
  conversation_id TEXT NOT NULL,
  thread_root     TEXT,
  install_id      TEXT NOT NULL,
  job_id          TEXT,
  kind            TEXT NOT NULL CHECK (kind IN ('request', 'ack', 'progress', 'result', 'approval', 'error')),
  body            TEXT NOT NULL,
  file_ids        TEXT NOT NULL DEFAULT '[]',
  approval_id     TEXT,
  author_id       TEXT,                        -- 'request' posts: the person who asked
  created_at      INTEGER NOT NULL,
  expires_at      INTEGER NOT NULL
);
CREATE INDEX idx_ai_channel_posts_conv ON ai_channel_posts(conversation_id, created_at);
CREATE INDEX idx_ai_channel_posts_expiry ON ai_channel_posts(expires_at);
