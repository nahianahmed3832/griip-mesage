-- Batch B4 (docs/product/MELDIQ_FULLSTACK_SPEC.md §2.2, MELDIQ_LIVE_DISCORD_INSPECTION.md §6.1): reports of a message or a
-- person, to a community's moderators (org_id) or to Meldiq's operators (org_id NULL). [content] is what the reporter
-- chose to share (the decrypted message and a little context), sealed with the server's reports key (AES-256-GCM);
-- never the raw encrypted message. Closed reports are deleted 90 days after they were handled.
CREATE TABLE reports (
  id TEXT PRIMARY KEY,
  org_id TEXT,
  reporter_id TEXT NOT NULL,
  target_user_id TEXT NOT NULL,
  conversation_id TEXT,
  message_id TEXT,
  reason TEXT NOT NULL,
  note TEXT,
  content TEXT,
  status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'actioned', 'dismissed')),
  handled_by TEXT,
  handled_note TEXT,
  created_at INTEGER NOT NULL,
  handled_at INTEGER
);
CREATE INDEX reports_org_status ON reports (org_id, status, created_at);
CREATE INDEX reports_reporter ON reports (reporter_id, created_at);
CREATE INDEX reports_target ON reports (target_user_id);

-- Security actions (Discord reference ADMIN-CTRL-007): until these times nobody joins by invitation, and no new direct
-- conversations start between people whose only shared communities have DMs paused. NULL or past: not paused.
ALTER TABLE orgs ADD COLUMN invites_paused_until INTEGER;
ALTER TABLE orgs ADD COLUMN dms_paused_until INTEGER;
