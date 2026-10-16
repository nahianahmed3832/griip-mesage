-- 2026-10-17: @everyone (docs/MENTIONS.md). Message content stays end-to-end encrypted; a sender who wants everyone
-- notified says so in the clear, the server checks its policy, and recipients only treat @everyone as a mention when
-- the server attested it. These rows only count @everyone messages per person and conversation for the hourly
-- limit; the retention job deletes them after a day.
CREATE TABLE mention_all_events (
  conversation_id TEXT NOT NULL,
  user_id         TEXT NOT NULL,
  message_id      TEXT NOT NULL,
  at              INTEGER NOT NULL
);
CREATE INDEX idx_mention_all_events ON mention_all_events(conversation_id, user_id, at);
