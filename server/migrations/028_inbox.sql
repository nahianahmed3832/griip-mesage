-- 2026-10-20 (round 8): the Unified Inbox (docs/UNIFIED_INBOX.md). Customer conversations from connected business
-- accounts: Facebook Messenger, Instagram, WhatsApp Business (Meta's official APIs) and TikTok (awaiting access).
-- These messages are NOT end-to-end encrypted (the provider delivers them to the server). Bodies, provider tokens and
-- raw webhook payloads are sealed at rest with the integrations key (AES-256-GCM, bound to their row); everything
-- else here is metadata. Additive only.

-- A connected business account: a Facebook Page, an Instagram professional account, a WhatsApp business number.
CREATE TABLE inbox_accounts (
  id               TEXT PRIMARY KEY,
  org_id           TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  provider         TEXT NOT NULL,                       -- messenger | instagram | whatsapp | tiktok
  external_id      TEXT NOT NULL,                       -- Page id, Instagram account id, WhatsApp phone number id
  name             TEXT NOT NULL,
  meta_json        TEXT NOT NULL DEFAULT '{}',          -- provider ids (WhatsApp business account id, display number)
  token_sealed     TEXT,
  token_expires_at INTEGER,
  status           TEXT NOT NULL DEFAULT 'active',      -- active | needs_reauth | error | disconnected
  live_key         TEXT,                                -- provider:external_id while connected; NULL once disconnected
  last_error       TEXT,                                -- an error kind, never a provider message
  last_event_at    INTEGER,
  connected_by     TEXT NOT NULL,
  created_at       INTEGER NOT NULL,
  updated_at       INTEGER NOT NULL
);
CREATE INDEX inbox_accounts_org ON inbox_accounts(org_id);
-- One workspace per live provider account, so another workspace can never receive its messages.
CREATE UNIQUE INDEX inbox_accounts_live ON inbox_accounts(live_key) WHERE live_key IS NOT NULL;

CREATE TABLE inbox_queues (
  id          TEXT PRIMARY KEY,
  org_id      TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  members_json TEXT NOT NULL DEFAULT '[]',
  created_at  INTEGER NOT NULL
);
CREATE INDEX inbox_queues_org ON inbox_queues(org_id);

-- A customer as one connected account knows them. Never merged across accounts or providers automatically.
CREATE TABLE inbox_contacts (
  id               TEXT PRIMARY KEY,
  org_id           TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  account_id       TEXT NOT NULL REFERENCES inbox_accounts(id) ON DELETE CASCADE,
  external_user_id TEXT NOT NULL,                       -- PSID, IGSID, WhatsApp number (wa_id)
  display_name     TEXT,                                -- only what the provider supplied
  created_at       INTEGER NOT NULL,
  UNIQUE (account_id, external_user_id)
);

CREATE TABLE inbox_conversations (
  id               TEXT PRIMARY KEY,
  org_id           TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  account_id       TEXT NOT NULL REFERENCES inbox_accounts(id) ON DELETE CASCADE,
  contact_id       TEXT NOT NULL REFERENCES inbox_contacts(id) ON DELETE CASCADE,
  provider         TEXT NOT NULL,
  status           TEXT NOT NULL DEFAULT 'waiting_agent', -- waiting_agent | waiting_customer | resolved
  priority         TEXT NOT NULL DEFAULT 'normal',        -- low | normal | high | urgent
  assignee_id      TEXT,
  queue_id         TEXT,
  handling         TEXT NOT NULL DEFAULT 'human',         -- human | ai_draft | ai_auto | awaiting_review | escalated
  escalation_reason TEXT,
  tags_json        TEXT NOT NULL DEFAULT '[]',
  rev              INTEGER NOT NULL DEFAULT 1,             -- compare-and-set guard (AI and people never both send)
  unread           INTEGER NOT NULL DEFAULT 0,
  last_message_at  INTEGER NOT NULL,
  last_inbound_at  INTEGER,
  first_response_at INTEGER,
  sla_notified_at  INTEGER,                             -- when agents were told it is overdue (once per wait)
  auto_replies_json TEXT NOT NULL DEFAULT '[]',          -- times of recent automatic replies (rate guard)
  resolved_at      INTEGER,
  created_at       INTEGER NOT NULL,
  updated_at       INTEGER NOT NULL,
  UNIQUE (account_id, contact_id)
);
CREATE INDEX inbox_conversations_org ON inbox_conversations(org_id, last_message_at DESC);

-- Customer messages, replies and internal notes (direction 'note' is never sent to anyone).
CREATE TABLE inbox_messages (
  id               TEXT PRIMARY KEY,
  org_id           TEXT NOT NULL,
  conversation_id  TEXT NOT NULL REFERENCES inbox_conversations(id) ON DELETE CASCADE,
  direction        TEXT NOT NULL,                       -- in | out | note
  external_id      TEXT,                                -- the provider's message id
  author_id        TEXT,                                -- the member who replied or wrote the note
  origin           TEXT NOT NULL DEFAULT 'human',       -- human | ai_auto | ai_draft (sent by a person after review)
  kind             TEXT NOT NULL DEFAULT 'text',        -- text | template | attachment | unsupported
  body_sealed      TEXT,
  attachments_json TEXT NOT NULL DEFAULT '[]',          -- type, mime, size, sealed provider reference
  status           TEXT NOT NULL,                       -- received | queued | sent | delivered | read | failed
  error_kind       TEXT,
  attempts         INTEGER NOT NULL DEFAULT 0,
  next_attempt_at  INTEGER,
  provider_ts      INTEGER,
  created_at       INTEGER NOT NULL
);
CREATE INDEX inbox_messages_conv ON inbox_messages(conversation_id, created_at);
CREATE UNIQUE INDEX inbox_messages_external ON inbox_messages(conversation_id, external_id) WHERE external_id IS NOT NULL;
CREATE INDEX inbox_messages_outbox ON inbox_messages(status, next_attempt_at) WHERE status = 'queued';

-- Every verified webhook delivery is stored (sealed) before it's acknowledged, then processed: a worker or provider
-- outage never loses a message, and a redelivery is recognised by its hash.
CREATE TABLE inbox_events (
  id             TEXT PRIMARY KEY,                       -- sha256 of provider + body
  provider       TEXT NOT NULL,
  payload_sealed TEXT NOT NULL,
  state          TEXT NOT NULL DEFAULT 'pending',        -- pending | done | failed
  attempts       INTEGER NOT NULL DEFAULT 0,
  next_attempt_at INTEGER NOT NULL,
  error_kind     TEXT,
  received_at    INTEGER NOT NULL
);
CREATE INDEX inbox_events_pending ON inbox_events(state, next_attempt_at);

-- A workspace's inbox policy: AI mode, automation scope, response target, retention.
CREATE TABLE inbox_settings (
  org_id      TEXT PRIMARY KEY REFERENCES orgs(id) ON DELETE CASCADE,
  json        TEXT NOT NULL,
  updated_by  TEXT,
  updated_at  INTEGER NOT NULL
);
