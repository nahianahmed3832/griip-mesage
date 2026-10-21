-- Batch B6 (docs/product/MELDIQ_FRIENDS_AND_DMS_SPEC.md): connections, message requests and who may message you.
-- [connections]: one row per pair of people (ids sorted, joined by "|"); pending until the other accepts. Declining or
-- removing deletes the row, and the other person isn't told. [dm_requests]: a DM from someone your settings don't let
-- message you directly waits here; [sent] counts the sender's messages before an answer (at most 3).
-- [messaging_settings]: who can message you directly and who can send you requests; no row means the defaults.
-- All three belong to the "relationships" domain (MongoDB-ready). Additive: older servers ignore them.
CREATE TABLE connections (
  pair TEXT PRIMARY KEY,
  a TEXT NOT NULL,
  b TEXT NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('pending', 'accepted')),
  requested_by TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  accepted_at INTEGER
);
CREATE INDEX connections_a ON connections (a, state);
CREATE INDEX connections_b ON connections (b, state);

CREATE TABLE dm_requests (
  conversation_id TEXT PRIMARY KEY,
  recipient TEXT NOT NULL,
  sender TEXT NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('pending', 'declined')),
  sent INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  answered_at INTEGER
);
CREATE INDEX dm_requests_recipient ON dm_requests (recipient, state, created_at);
CREATE INDEX dm_requests_sender ON dm_requests (sender);

CREATE TABLE messaging_settings (
  user_id TEXT PRIMARY KEY,
  dm_policy TEXT NOT NULL DEFAULT 'communities' CHECK (dm_policy IN ('everyone', 'communities', 'connections')),
  request_policy TEXT NOT NULL DEFAULT 'everyone' CHECK (request_policy IN ('everyone', 'communities', 'nobody')),
  updated_at INTEGER NOT NULL
);
