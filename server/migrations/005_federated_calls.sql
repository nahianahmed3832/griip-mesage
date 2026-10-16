-- 2026-10-16: calls with people on another server (docs/CALLING_AND_E2EE.md §6). A call is hosted by the caller's
-- server (its LiveKit issues every token); the other server keeps a mirror row with the same id and the host's server
-- id here. NULL: hosted on this server (every existing call).
ALTER TABLE calls ADD COLUMN host_server_id TEXT;
