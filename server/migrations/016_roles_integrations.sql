-- 2026-10-19: custom workspace roles and the integrations framework (docs/WORKSPACE_ROLES.md, docs/INTEGRATIONS.md).
-- Additive only: no existing row changes. No message content, provider password or plaintext token is ever stored here.

-- ───────────── Custom roles (workspace domain: SQLite, like the rest of authorisation) ─────────────
-- A workspace's own roles on top of the protected base roles (Owner, Admin, Member, Guest). A role grants exactly the
-- permissions it lists (keys from src/permissions/catalog.ts) and may restrict a few (`denies`); its name grants nothing.
CREATE TABLE org_roles (
  id          TEXT PRIMARY KEY,
  org_id      TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  description TEXT,
  color       TEXT,                    -- "#RRGGBB", or NULL for the default
  icon        TEXT,                    -- one of a fixed set of icon names, or NULL
  permissions TEXT NOT NULL,           -- JSON array of granted permission keys
  denies      TEXT NOT NULL,           -- JSON array of restricted permission keys (deny wins)
  template    TEXT,                    -- the starter template it was made from, if any
  position    INTEGER NOT NULL,
  rev         INTEGER NOT NULL,        -- optimistic concurrency: an edit names the revision it was based on
  created_by  TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL,
  archived_at INTEGER
);
CREATE INDEX idx_org_roles_org ON org_roles(org_id);

-- Who holds which custom role. Removing someone from the workspace removes their assignments (composite foreign key).
CREATE TABLE org_role_members (
  role_id     TEXT NOT NULL REFERENCES org_roles(id) ON DELETE CASCADE,
  org_id      TEXT NOT NULL,
  user_id     TEXT NOT NULL,
  assigned_by TEXT NOT NULL,
  assigned_at INTEGER NOT NULL,
  PRIMARY KEY (role_id, user_id),
  FOREIGN KEY (org_id, user_id) REFERENCES org_members(org_id, user_id) ON DELETE CASCADE
);
CREATE INDEX idx_org_role_members_user ON org_role_members(org_id, user_id);

-- ───────────── Integrations (integrations domain: SQLite, or MongoDB with MONGODB_DOMAINS=integrations) ─────────────
-- No foreign keys to other domains: this domain can live in another database. Rows are removed with their workspace
-- explicitly (src/integrations/service.ts › forgetWorkspace).

-- (A) A connector installed for a workspace: one row per workspace and connector, reused if it is installed again.
CREATE TABLE integration_installs (
  id                TEXT PRIMARY KEY,
  workspace_id      TEXT NOT NULL,
  connector_id      TEXT NOT NULL,
  connector_version INTEGER NOT NULL,
  state             TEXT NOT NULL,     -- requested | installed | disabled | rejected | uninstalled
  requested_caps    TEXT NOT NULL,     -- JSON array of capability keys asked for
  granted_caps      TEXT NOT NULL,     -- JSON array of capability keys the approving admin allowed
  config            TEXT NOT NULL,     -- JSON object: access (everyone or roles), connector settings
  requested_by      TEXT,
  request_note      TEXT,
  installed_by      TEXT,
  decided_by        TEXT,
  decided_at        INTEGER,
  rev               INTEGER NOT NULL,
  created_at        INTEGER NOT NULL,
  updated_at        INTEGER NOT NULL,
  last_event_at     INTEGER,
  last_error        TEXT,
  last_error_at     INTEGER
);
CREATE UNIQUE INDEX idx_integration_installs_ws ON integration_installs(workspace_id, connector_id);

-- (B) An external account connected through a connector: a member's own, or the workspace's shared connection.
-- owner_key is "member:<userId>" or "workspace", so each owner has at most one account per connector and workspace.
CREATE TABLE integration_accounts (
  id                TEXT PRIMARY KEY,
  workspace_id      TEXT NOT NULL,
  connector_id      TEXT NOT NULL,
  owner_type        TEXT NOT NULL,     -- member | workspace
  owner_key         TEXT NOT NULL,
  user_id           TEXT NOT NULL,     -- the member (owner_type member) or the admin who connected it (workspace)
  external_id       TEXT NOT NULL,     -- the provider's stable account id
  external_label    TEXT,              -- email or login: shown to the owner and to integration admins only
  scopes            TEXT NOT NULL,     -- JSON array of the scopes the provider actually granted
  meta              TEXT NOT NULL,     -- JSON object: provider ids that aren't secrets (tenant, cloud id, installation)
  status            TEXT NOT NULL,     -- active | expired | revoked | error
  rev               INTEGER NOT NULL,
  created_at        INTEGER NOT NULL,
  updated_at        INTEGER NOT NULL,
  last_validated_at INTEGER,
  last_used_at      INTEGER,
  last_error        TEXT,
  last_error_at     INTEGER
);
CREATE UNIQUE INDEX idx_integration_accounts_owner ON integration_accounts(workspace_id, connector_id, owner_key);
CREATE INDEX idx_integration_accounts_user ON integration_accounts(user_id);
-- Webhook deliveries find their workspace links, and revocation looks for other connections of one provider account.
CREATE INDEX idx_integration_accounts_external ON integration_accounts(external_id, connector_id);

-- Encrypted credentials, apart from the account record (different access and lifecycle). Sealed with AES-256-GCM under
-- INTEGRATIONS_TOKEN_KEY (src/integrations/tokens.ts); never returned by the API, never logged.
CREATE TABLE integration_credentials (
  account_id    TEXT PRIMARY KEY,
  key_id        TEXT NOT NULL,         -- which token key sealed them (rotation)
  access_token  TEXT NOT NULL,
  refresh_token TEXT,
  expires_at    INTEGER,
  updated_at    INTEGER NOT NULL
);

-- An authorisation in progress (10 minutes, single use). Only hashes of the state, the begin token and the browser
-- cookie are kept; the PKCE verifier is sealed like a token.
CREATE TABLE integration_oauth_states (
  id            TEXT PRIMARY KEY,
  state_hash    TEXT NOT NULL UNIQUE,
  begin_hash    TEXT,
  browser_hash  TEXT,
  finish_hash   TEXT,                  -- the app's one-time code for finishing an approved sign-in
  workspace_id  TEXT NOT NULL,
  connector_id  TEXT NOT NULL,
  user_id       TEXT NOT NULL,
  owner_type    TEXT NOT NULL,
  verifier      TEXT,
  redirect_uri  TEXT NOT NULL,
  scopes        TEXT NOT NULL,
  created_at    INTEGER NOT NULL,
  expires_at    INTEGER NOT NULL,
  used_at       INTEGER,
  result        TEXT,                  -- approved | finishing | connected | denied | failed
  error         TEXT,
  account_id    TEXT
);
CREATE INDEX idx_integration_oauth_states_expiry ON integration_oauth_states(expires_at);

-- Where a workspace connection's events go: a channel, for a resource (a repository, a project) and event types.
CREATE TABLE integration_subscriptions (
  id               TEXT PRIMARY KEY,
  workspace_id     TEXT NOT NULL,
  connector_id     TEXT NOT NULL,
  account_id       TEXT NOT NULL,
  conversation_id  TEXT NOT NULL,
  resource         TEXT NOT NULL,      -- connector-defined, e.g. "owner/repo"
  events           TEXT NOT NULL,      -- JSON array of event keys
  created_by       TEXT NOT NULL,
  created_at       INTEGER NOT NULL,
  updated_at       INTEGER NOT NULL,
  active           INTEGER NOT NULL,
  last_delivery_at INTEGER,
  last_error       TEXT
);
-- One subscription per app, channel and resource (two admins saving at once can't make every event post twice).
CREATE UNIQUE INDEX idx_integration_subscriptions_target ON integration_subscriptions(workspace_id, connector_id, conversation_id, resource);
CREATE INDEX idx_integration_subscriptions_account ON integration_subscriptions(account_id);

-- Inbound webhook deliveries already accepted (duplicates and replays are refused by id), kept for a few days.
CREATE TABLE integration_deliveries (
  id           TEXT PRIMARY KEY,       -- "<connector>:<provider delivery id>"
  connector_id TEXT NOT NULL,
  event        TEXT NOT NULL,
  status       TEXT NOT NULL,          -- queued | done | ignored | failed
  received_at  INTEGER NOT NULL,
  expires_at   INTEGER NOT NULL
);
CREATE INDEX idx_integration_deliveries_expiry ON integration_deliveries(expires_at);

-- Background work (event processing, refreshes): retried with backoff, claimed with a lease, idempotent by key.
CREATE TABLE integration_jobs (
  id              TEXT PRIMARY KEY,
  kind            TEXT NOT NULL,
  workspace_id    TEXT,
  idempotency_key TEXT NOT NULL UNIQUE,
  payload         TEXT NOT NULL,       -- JSON, never a credential
  state           TEXT NOT NULL,       -- queued | running | done | dead
  attempts        INTEGER NOT NULL,
  next_at         INTEGER NOT NULL,
  lease_until     INTEGER,
  last_error      TEXT,
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL
);
CREATE INDEX idx_integration_jobs_due ON integration_jobs(state, next_at);

-- Notifications an app posted into a channel (a pull request opened, an issue updated). They are NOT end-to-end
-- encrypted: the server received them from the provider. Only titles, links and short summaries; expire after 30 days.
CREATE TABLE integration_channel_events (
  id              TEXT PRIMARY KEY,
  workspace_id    TEXT NOT NULL,
  conversation_id TEXT NOT NULL,
  connector_id    TEXT NOT NULL,
  subscription_id TEXT NOT NULL,
  kind            TEXT NOT NULL,
  title           TEXT NOT NULL,
  url             TEXT,
  summary         TEXT,
  actor           TEXT,
  occurred_at     INTEGER NOT NULL,
  created_at      INTEGER NOT NULL,
  expires_at      INTEGER NOT NULL,
  dedupe_key      TEXT NOT NULL UNIQUE
);
CREATE INDEX idx_integration_channel_events_conv ON integration_channel_events(conversation_id, created_at);
CREATE INDEX idx_integration_channel_events_expiry ON integration_channel_events(expires_at);

-- Incoming webhooks: a secret URL per channel that a workspace's own tools post to (CI, monitoring, automation services).
-- The secret is sealed like a token (it must be compared, so it can't be only hashed).
CREATE TABLE integration_hooks (
  id               TEXT PRIMARY KEY,
  workspace_id     TEXT NOT NULL,
  conversation_id  TEXT NOT NULL,
  name             TEXT NOT NULL,
  secret           TEXT NOT NULL,      -- sealed (src/integrations/tokens.ts)
  created_by       TEXT NOT NULL,
  created_at       INTEGER NOT NULL,
  updated_at       INTEGER NOT NULL,
  active           INTEGER NOT NULL,
  last_delivery_at INTEGER,
  last_error       TEXT
);
CREATE INDEX idx_integration_hooks_ws ON integration_hooks(workspace_id);

-- A workspace's app policy (who may request, whether unapproved apps are blocked, which connectors are allowed), and
-- the platform's own restrictions (id "platform"). JSON documents with a revision.
CREATE TABLE integration_policies (
  id         TEXT PRIMARY KEY,         -- a workspace id, or "platform"
  doc        TEXT NOT NULL,
  rev        INTEGER NOT NULL,
  updated_by TEXT,
  updated_at INTEGER NOT NULL
);
