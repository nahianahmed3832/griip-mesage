-- 2026-10-20 (round 7 slice C6): API keys and outgoing webhooks (docs/DEVELOPER_API.md). Additive only.
--
-- API keys: workspace-scoped, with explicit scopes and an expiry; only a hash of the secret is stored (the secret is
-- shown once). A key works only while the person who made it can still manage developer access.
-- Webhooks: signed POSTs of workspace events (never message content: messages are end-to-end encrypted) to an https
-- address the workspace chose. The signing secret is sealed with the integrations key (INTEGRATIONS_TOKEN_KEY).

CREATE TABLE api_keys (
  id            TEXT PRIMARY KEY,
  org_id        TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  name          TEXT NOT NULL,
  hint          TEXT NOT NULL,                  -- last 4 characters of the secret, for telling keys apart
  secret_hash   TEXT NOT NULL,                  -- sha256 of the secret
  scopes        TEXT NOT NULL,                  -- JSON list
  created_by    TEXT NOT NULL,
  created_at    INTEGER NOT NULL,
  expires_at    INTEGER NOT NULL,
  last_used_at  INTEGER,
  revoked_at    INTEGER
);
CREATE INDEX idx_api_keys_org ON api_keys(org_id);

CREATE TABLE webhooks (
  id                    TEXT PRIMARY KEY,
  org_id                TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  name                  TEXT NOT NULL,
  url                   TEXT NOT NULL,
  events                TEXT NOT NULL,          -- JSON list of event types
  secret_sealed         TEXT NOT NULL,
  enabled               INTEGER NOT NULL DEFAULT 1,
  disabled_reason       TEXT,
  consecutive_failures  INTEGER NOT NULL DEFAULT 0,
  verified_at           INTEGER,                -- first successful delivery
  created_by            TEXT NOT NULL,
  created_at            INTEGER NOT NULL,
  updated_at            INTEGER NOT NULL
);
CREATE INDEX idx_webhooks_org ON webhooks(org_id);

CREATE TABLE webhook_deliveries (
  id           TEXT PRIMARY KEY,
  webhook_id   TEXT NOT NULL REFERENCES webhooks(id) ON DELETE CASCADE,
  org_id       TEXT NOT NULL,
  event_id     TEXT NOT NULL,                   -- the same across retries: receivers de-duplicate on it
  event_type   TEXT NOT NULL,
  payload      TEXT NOT NULL,
  state        TEXT NOT NULL CHECK (state IN ('queued','sending','delivered','failed')),
  attempts     INTEGER NOT NULL DEFAULT 0,
  next_at      INTEGER NOT NULL,
  lease_until  INTEGER,
  last_status  INTEGER,
  last_error   TEXT,
  created_at   INTEGER NOT NULL,
  delivered_at INTEGER,
  UNIQUE (webhook_id, event_id)
);
CREATE INDEX idx_webhook_deliveries_due ON webhook_deliveries(state, next_at);
CREATE INDEX idx_webhook_deliveries_hook ON webhook_deliveries(webhook_id, created_at);
