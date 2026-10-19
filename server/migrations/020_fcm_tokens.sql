-- 2026-10-19 (round 6): Firebase Cloud Messaging registration tokens (docs/PUSH_FCM.md). Additive only.
--
-- A second, optional way to wake a device, beside UnifiedPush (push_endpoints). Used only when the server has a
-- Firebase project configured (FCM_PROJECT_ID + FCM_SERVICE_ACCOUNT_FILE). Wake-ups carry no content: the app connects
-- and fetches its encrypted messages, calls and reminders itself. One token per device.

CREATE TABLE fcm_tokens (
  device_id      TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
  token          TEXT NOT NULL UNIQUE,
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL,
  last_wake_at   INTEGER,
  failing_since  INTEGER
);
