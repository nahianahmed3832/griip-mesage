-- 2026-10-16: a recipient device confirmed it is ringing (call reachability). Additive and nullable:
-- older servers and clients ignore it.
ALTER TABLE calls ADD COLUMN alerted_at INTEGER;
