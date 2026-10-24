-- 2026-10-24: Platform Admin 2.0 (meldiq_central_admin/docs/ADMIN_2_ASSESSMENT.md). Additive only: new tables, new
-- nullable columns and new indexes. Nothing existing is changed or removed.
--
-- Telemetry holds counts, times and client kinds: never message content, file content, keys, tokens or IP addresses.

-- One row per person, UTC day and client kind: the source for DAU/WAU/MAU, retention and activation. Written from the
-- API's own request handling (trusted server-side signal), batched once a minute. Kept 400 days.
CREATE TABLE activity_daily (
  day             TEXT NOT NULL,             -- "YYYY-MM-DD", UTC
  user_id         TEXT NOT NULL,
  platform        TEXT NOT NULL,             -- android | ios | web | other | unknown (from the request's client)
  client_version  TEXT NOT NULL DEFAULT '',  -- only when the client sends one; '' otherwise
  first_at        INTEGER NOT NULL,
  last_at         INTEGER NOT NULL,
  requests        INTEGER NOT NULL DEFAULT 0,  -- qualifying requests
  actions         INTEGER NOT NULL DEFAULT 0,  -- meaningful actions (messages, uploads, calls, joins, creations)
  first_action_at INTEGER,                     -- the first meaningful action that day
  signup_at       INTEGER,                     -- the identity's creation time (for cohorts, age and new vs returning)
  PRIMARY KEY (day, user_id, platform, client_version)
);
CREATE INDEX idx_activity_daily_user ON activity_daily(user_id, day);
CREATE INDEX idx_activity_daily_signup ON activity_daily(signup_at, day);

-- When telemetry started collecting, per kind (so the console never shows days before it as zero activity).
CREATE TABLE telemetry_epochs (
  kind        TEXT PRIMARY KEY,
  started_at  INTEGER NOT NULL
);

-- Bytes per person and UTC day (API in/out, WebSocket in/out, file upload/download). Kept 400 days.
CREATE TABLE traffic_user_daily (
  day      TEXT NOT NULL,
  user_id  TEXT NOT NULL,
  metric   TEXT NOT NULL,
  value    INTEGER NOT NULL,
  PRIMARY KEY (day, user_id, metric)
);
CREATE INDEX idx_traffic_user_daily_user ON traffic_user_daily(user_id, day);

-- A per-minute sample of live connections (this API instance's hub). Kept 30 days; hourly rollups 400 days.
CREATE TABLE presence_samples (
  minute         INTEGER PRIMARY KEY,        -- epoch minute (ms / 60000), UTC
  users          INTEGER NOT NULL,           -- distinct people with a socket open
  active_users   INTEGER NOT NULL,           -- distinct people with qualifying activity in the last 5 minutes
  devices        INTEGER NOT NULL,
  sockets        INTEGER NOT NULL,
  connects       INTEGER NOT NULL,           -- sockets authenticated during the minute
  disconnects    INTEGER NOT NULL,
  abnormal       INTEGER NOT NULL,           -- closed without a close frame or by the heartbeat
  auth_failures  INTEGER NOT NULL,           -- sockets refused: bad, missing or expired credentials
  calls          INTEGER NOT NULL,           -- calls in progress
  call_people    INTEGER NOT NULL,           -- participants in them
  platforms      TEXT NOT NULL               -- JSON {"android": n, "web": n, ...}: distinct people per platform
);

CREATE TABLE presence_hourly (
  hour        INTEGER PRIMARY KEY,           -- epoch hour, UTC
  peak_users  INTEGER NOT NULL,
  avg_users   REAL NOT NULL,
  peak_sockets INTEGER NOT NULL,
  connects    INTEGER NOT NULL,
  disconnects INTEGER NOT NULL,
  abnormal    INTEGER NOT NULL,
  auth_failures INTEGER NOT NULL,
  samples     INTEGER NOT NULL
);

-- Resource telemetry, one row per measured series and minute (host, containers, this process). Kept 14 days; hourly
-- rollups 400 days. A series that couldn't be measured has no row (never a made-up zero).
CREATE TABLE resource_samples (
  minute  INTEGER NOT NULL,
  series  TEXT NOT NULL,
  value   REAL NOT NULL,
  PRIMARY KEY (series, minute)
);
CREATE INDEX idx_resource_samples_minute ON resource_samples(minute);

CREATE TABLE resource_hourly (
  hour     INTEGER NOT NULL,
  series   TEXT NOT NULL,
  avg      REAL NOT NULL,
  min      REAL NOT NULL,
  max      REAL NOT NULL,
  samples  INTEGER NOT NULL,
  PRIMARY KEY (series, hour)
);

-- The client each app session was last seen with (from its requests), for the device inventory.
CREATE TABLE session_clients (
  session_id      TEXT PRIMARY KEY,
  platform        TEXT NOT NULL,
  client          TEXT NOT NULL,             -- "Chrome 141", "OkHttp 4.12", … (derived, never the raw header)
  client_version  TEXT NOT NULL DEFAULT '',
  os              TEXT NOT NULL DEFAULT '',
  first_seen_at   INTEGER NOT NULL,
  last_seen_at    INTEGER NOT NULL
);

-- Per-user sign-in history comes from audit_log (account.login, account.login_failed, account.refresh_reuse…).
CREATE INDEX idx_audit_actor ON audit_log(actor_id, at);

-- ───────────────────────── Unified quotas ─────────────────────────
-- Plans: named sets of default policies. No prices; nothing here charges anyone.
CREATE TABLE quota_plans (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL UNIQUE,
  description TEXT NOT NULL DEFAULT '',
  created_by  TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  archived_at INTEGER
);

-- Which plan an organization or person is on (one at a time; history kept in the platform audit).
CREATE TABLE quota_plan_members (
  scope_type  TEXT NOT NULL CHECK (scope_type IN ('org', 'user')),
  scope_id    TEXT NOT NULL,
  plan_id     TEXT NOT NULL REFERENCES quota_plans(id),
  assigned_by TEXT NOT NULL,
  assigned_at INTEGER NOT NULL,
  PRIMARY KEY (scope_type, scope_id)
);

-- Versioned policies: a change inserts a new row and marks the old one superseded, so history is never lost.
CREATE TABLE quota_policies (
  id             TEXT PRIMARY KEY,
  scope_type     TEXT NOT NULL CHECK (scope_type IN ('global', 'pool', 'plan', 'org', 'user')),
  scope_id       TEXT NOT NULL DEFAULT '',   -- '' for global; pool name; plan id; org id; user id
  quota_key      TEXT NOT NULL,
  limit_value    INTEGER,                    -- NULL: measured, no limit
  mode           TEXT NOT NULL CHECK (mode IN ('observe', 'warn', 'soft', 'throttle', 'block')),
  hard           INTEGER NOT NULL DEFAULT 0, -- global/pool only: a ceiling no narrower policy can raise
  warn_pct       INTEGER NOT NULL DEFAULT 80,
  grace_pct      INTEGER NOT NULL DEFAULT 0, -- soft mode: allowed overage, % of the limit
  override       INTEGER NOT NULL DEFAULT 0, -- a temporary exception (needs expires_at)
  expires_at     INTEGER,
  reason         TEXT NOT NULL,
  created_by     TEXT NOT NULL,
  created_at     INTEGER NOT NULL,
  superseded_at  INTEGER,
  superseded_by  TEXT
);
CREATE INDEX idx_quota_policies_scope ON quota_policies(scope_type, scope_id, quota_key) WHERE superseded_at IS NULL;
CREATE INDEX idx_quota_policies_key ON quota_policies(quota_key) WHERE superseded_at IS NULL;

-- Usage per scope, quota and period. A new period is a new row: earlier periods are kept.
CREATE TABLE quota_counters (
  scope_type    TEXT NOT NULL,
  scope_id      TEXT NOT NULL,
  quota_key     TEXT NOT NULL,
  period_start  TEXT NOT NULL,               -- "YYYY-MM-DD" (day), "YYYY-MM" (month) or "all" (no reset)
  used          INTEGER NOT NULL DEFAULT 0,  -- committed
  reserved      INTEGER NOT NULL DEFAULT 0,  -- held by open reservations
  updated_at    INTEGER NOT NULL,
  PRIMARY KEY (scope_type, scope_id, quota_key, period_start)
);

CREATE TABLE quota_reservations (
  id               TEXT PRIMARY KEY,
  idempotency_key  TEXT UNIQUE,
  quota_key        TEXT NOT NULL,
  scopes           TEXT NOT NULL,            -- JSON [{type, id, period}] the amount was reserved against
  amount           INTEGER NOT NULL,
  state            TEXT NOT NULL CHECK (state IN ('reserved', 'committed', 'released', 'expired')),
  settled_amount   INTEGER,
  created_at       INTEGER NOT NULL,
  expires_at       INTEGER NOT NULL,
  settled_at       INTEGER
);
CREATE INDEX idx_quota_reservations_open ON quota_reservations(expires_at) WHERE state = 'reserved';

-- What the engine decided (warnings, throttles, blocks, observed overages, adjustments). Kept 180 days.
CREATE TABLE quota_events (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  at          INTEGER NOT NULL,
  quota_key   TEXT NOT NULL,
  scope_type  TEXT NOT NULL,
  scope_id    TEXT NOT NULL,
  outcome     TEXT NOT NULL CHECK (outcome IN ('warned', 'throttled', 'blocked', 'observed_over', 'soft_over', 'adjusted', 'expired_reservation')),
  amount      INTEGER NOT NULL,
  used        INTEGER NOT NULL,
  limit_value INTEGER,
  policy_id   TEXT,
  actor       TEXT,                           -- an admin account id for 'adjusted'; NULL otherwise
  detail      TEXT
);
CREATE INDEX idx_quota_events_at ON quota_events(at);
CREATE INDEX idx_quota_events_scope ON quota_events(scope_type, scope_id, at);

-- ───────────────────────── AI providers ─────────────────────────
-- Provider connections. The API key is stored only encrypted (AES-256-GCM, key from PLATFORM_SECRETS_KEY, which never
-- enters the database); it is never returned by any route. Base URLs are fixed per kind in code (no free-form URL).
CREATE TABLE ai_providers (
  id                 TEXT PRIMARY KEY,
  kind               TEXT NOT NULL CHECK (kind IN ('anthropic', 'openai', 'google')),
  name               TEXT NOT NULL UNIQUE,
  enabled            INTEGER NOT NULL DEFAULT 0,
  secret_ciphertext  TEXT,
  secret_set_at      INTEGER,
  timeout_ms         INTEGER NOT NULL DEFAULT 30000,
  max_retries        INTEGER NOT NULL DEFAULT 1,
  fallback_id        TEXT,
  last_test_at       INTEGER,
  last_test_ok       INTEGER,
  last_test_detail   TEXT,                   -- status and error kind only, never a response body
  created_by         TEXT NOT NULL,
  created_at         INTEGER NOT NULL,
  updated_at         INTEGER NOT NULL
);

-- Approved models per provider, with the prices an operator entered (per million tokens, in micro-USD) and where
-- they came from. Costs computed from them are estimates, never invoices.
CREATE TABLE ai_models (
  provider_id            TEXT NOT NULL REFERENCES ai_providers(id),
  model                  TEXT NOT NULL,
  enabled                INTEGER NOT NULL DEFAULT 1,
  input_price_micro      INTEGER,            -- micro-USD per million input tokens
  output_price_micro     INTEGER,
  price_source           TEXT NOT NULL DEFAULT '',
  price_updated_at       INTEGER,
  PRIMARY KEY (provider_id, model)
);

-- One row per AI request through the gateway: counts and costs, never prompts or answers. Kept 400 days.
CREATE TABLE ai_usage (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  at              INTEGER NOT NULL,
  provider_id     TEXT NOT NULL,
  model           TEXT NOT NULL,
  feature         TEXT NOT NULL,              -- 'console.test' for connection tests; a product feature id otherwise
  user_id         TEXT,
  org_id          TEXT,
  outcome         TEXT NOT NULL CHECK (outcome IN ('ok', 'error', 'blocked', 'timeout')),
  status          INTEGER,
  input_tokens    INTEGER,
  output_tokens   INTEGER,
  tokens_source   TEXT NOT NULL DEFAULT 'provider' CHECK (tokens_source IN ('provider', 'estimate', 'none')),
  cost_micro      INTEGER,                    -- estimate from ai_models prices; NULL without prices
  latency_ms      INTEGER,
  error_kind      TEXT
);
CREATE INDEX idx_ai_usage_at ON ai_usage(at);

-- ───────────────────────── Settings, alerts, support ─────────────────────────
-- Runtime platform settings changed from the console (audited). Values are JSON; secrets are never stored here.
CREATE TABLE platform_settings (
  key         TEXT PRIMARY KEY,
  value       TEXT NOT NULL,
  updated_by  TEXT NOT NULL,
  updated_at  INTEGER NOT NULL
);

-- Alert rules over measured series. Breaches open (or update) one incident per rule.
CREATE TABLE alert_rules (
  key             TEXT PRIMARY KEY,
  enabled         INTEGER NOT NULL DEFAULT 1,
  threshold       REAL NOT NULL,
  window_minutes  INTEGER NOT NULL,
  severity        TEXT NOT NULL CHECK (severity IN ('sev1', 'sev2', 'sev3', 'sev4')),
  updated_by      TEXT NOT NULL,
  updated_at      INTEGER NOT NULL
);

CREATE TABLE alert_state (
  key            TEXT PRIMARY KEY,
  breaching      INTEGER NOT NULL DEFAULT 0,
  since          INTEGER,
  last_value     REAL,
  last_eval_at   INTEGER NOT NULL,
  incident_id    TEXT
);

-- Notification attempts for alerts: 'sent' only when the mail server accepted it, never just because it was tried.
CREATE TABLE alert_deliveries (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  at           INTEGER NOT NULL,
  incident_id  TEXT NOT NULL,
  channel      TEXT NOT NULL,
  outcome      TEXT NOT NULL CHECK (outcome IN ('sent', 'failed', 'not_configured')),
  detail       TEXT
);
CREATE INDEX idx_alert_deliveries_incident ON alert_deliveries(incident_id, at);

-- Incident lifecycle (additive): acknowledgement, closing, the affected service, detection source and dedupe.
ALTER TABLE platform_incidents ADD COLUMN service TEXT;
ALTER TABLE platform_incidents ADD COLUMN source TEXT NOT NULL DEFAULT 'manual';
ALTER TABLE platform_incidents ADD COLUMN dedupe_key TEXT;
ALTER TABLE platform_incidents ADD COLUMN first_seen_at INTEGER;
ALTER TABLE platform_incidents ADD COLUMN last_seen_at INTEGER;
ALTER TABLE platform_incidents ADD COLUMN acknowledged_at INTEGER;
ALTER TABLE platform_incidents ADD COLUMN closed_at INTEGER;
ALTER TABLE platform_incidents ADD COLUMN resolution TEXT;
CREATE INDEX idx_platform_incidents_dedupe ON platform_incidents(dedupe_key) WHERE dedupe_key IS NOT NULL;

-- Temporary suspensions end on their own (the restore is audited by the job that does it).
CREATE TABLE platform_restrictions (
  user_id     TEXT PRIMARY KEY,
  kind        TEXT NOT NULL CHECK (kind IN ('suspension')),
  until       INTEGER,                         -- NULL: until lifted
  reason      TEXT NOT NULL,
  set_by      TEXT NOT NULL,
  set_at      INTEGER NOT NULL
);
