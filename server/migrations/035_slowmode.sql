-- Slowmode (batch B3, docs/product/MELDIQ_FULLSTACK_SPEC.md §2.3): a channel's interval between one person's new
-- messages (0: off), and when each member last posted a message there. The server sees who posts, not what, so this is
-- enforced on the server. Additive: older servers ignore the columns.
ALTER TABLE conversations ADD COLUMN slowmode_sec INTEGER NOT NULL DEFAULT 0;
ALTER TABLE conversation_members ADD COLUMN last_post_at INTEGER;
