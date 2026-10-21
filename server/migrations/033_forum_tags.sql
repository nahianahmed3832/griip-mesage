-- Forum tags (docs/product/MELDIQ_COMMUNITY_CHAT_SPEC.md §6): the names a forum channel offers for labelling posts,
-- as JSON [{"id","name"}]. Readable by the server like a channel's name; which tags a post carries travels inside the
-- post's end-to-end encrypted message. Additive: older servers ignore the column.
ALTER TABLE conversations ADD COLUMN forum_tags TEXT;
