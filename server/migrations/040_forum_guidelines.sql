-- Batch B8 (Discord reference FORUM-CTRL-006 "Post Guidelines"): what a forum asks of new posts, shown to people
-- writing one. Not end-to-end encrypted, like the channel's topic. Additive.
ALTER TABLE conversations ADD COLUMN guidelines TEXT;
