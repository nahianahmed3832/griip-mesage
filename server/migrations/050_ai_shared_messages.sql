-- 2026-10-24: channel messages a person shares with an agent request (docs/AI_WORKFORCE.md §2). The person's browser
-- decrypted and picked them, under a notice that they leave end-to-end encryption; the server never reads a channel.
-- Kept only for the job that got them (and the steps it delegates), never downloadable, deleted after 7 days.
CREATE TABLE ai_shared_messages (
  job_id          TEXT PRIMARY KEY,           -- the request they were shared with (a root job)
  org_id          TEXT NOT NULL,
  conversation_id TEXT NOT NULL,
  shared_by       TEXT NOT NULL,
  messages        TEXT NOT NULL,              -- JSON [{author, text, at}], oldest first
  count           INTEGER NOT NULL,
  created_at      INTEGER NOT NULL,
  expires_at      INTEGER NOT NULL
);
CREATE INDEX idx_ai_shared_messages_expiry ON ai_shared_messages(expires_at);
