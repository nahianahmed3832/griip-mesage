-- 2026-10-17: push wake-ups (docs/BACKGROUND_DELIVERY.md, option B). A device may give the server one UnifiedPush
-- endpoint; when something is queued for it while it has no open socket, the server sends that endpoint an empty
-- request that only wakes the app, which then fetches its queue over its own authenticated connection. Nothing about
-- the message is ever sent to the push server. Additive only.
CREATE TABLE push_endpoints (
  device_id      TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
  kind           TEXT NOT NULL CHECK (kind IN ('unifiedpush')),
  endpoint       TEXT NOT NULL UNIQUE,      -- https URL on an allow-listed push host; a capability, never logged
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL,
  last_wake_at   INTEGER,                   -- last wake-up the push server accepted
  failing_since  INTEGER                    -- first failure of the current run of failures (NULL: last one worked)
);
