-- 2026-10-19 (round 6): reminders kept and fired by the server (docs/REMINDERS.md). Additive only.
--
-- A reminder belongs to the person's encryption identity (users), like their saved items, so it reaches every device
-- they're signed in on. What it says (title, note, the message or task it points at) is sealed on the phone with a key
-- derived from the identity seed, which servers never have: the server only sees when it is due and how it repeats.

CREATE TABLE reminders (
  id               TEXT PRIMARY KEY,                     -- chosen by the app, so a retried create can't make two
  user_id          TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  sealed           TEXT NOT NULL,                        -- base64url, opaque to the server
  due_at           INTEGER NOT NULL,                     -- UTC ms of the next (or only) occurrence
  time_zone        TEXT NOT NULL,                        -- IANA zone the person set it in
  recurrence       TEXT CHECK (recurrence IN ('daily','weekdays','weekly','monthly')),
  local_time       TEXT,                                 -- "HH:MM" of a repeating reminder (kept across DST)
  anchor_date      TEXT,                                 -- "YYYY-MM-DD" of its first occurrence
  -- scheduled: waiting for due_at; fired: a one-off that went off and wasn't dealt with yet (overdue);
  -- done / dismissed: finished by the person (dismissing never completes what the reminder points at).
  state            TEXT NOT NULL CHECK (state IN ('scheduled','fired','done','dismissed')),
  fired_at         INTEGER,                              -- when it last went off
  delivered_at     INTEGER,                              -- when a device last confirmed it showed it
  attempts         INTEGER NOT NULL DEFAULT 0,           -- failed delivery attempts of the current occurrence
  next_attempt_at  INTEGER,                              -- retry time after a failed attempt (bounded backoff)
  last_error       TEXT,                                 -- the failure's kind only, never content
  rev              INTEGER NOT NULL DEFAULT 1,           -- every change bumps it; edits name the rev they started from
  created_at       INTEGER NOT NULL,
  updated_at       INTEGER NOT NULL,
  completed_at     INTEGER
);
CREATE INDEX idx_reminders_due ON reminders(state, due_at);
CREATE INDEX idx_reminders_user ON reminders(user_id, state, due_at);

-- One row per occurrence that went off: firing is idempotent (a restart or a second tick can't deliver it twice).
CREATE TABLE reminder_occurrences (
  reminder_id    TEXT NOT NULL REFERENCES reminders(id) ON DELETE CASCADE,
  occurrence_at  INTEGER NOT NULL,
  fired_at       INTEGER NOT NULL,
  PRIMARY KEY (reminder_id, occurrence_at)
);
CREATE INDEX idx_reminder_occurrences_fired ON reminder_occurrences(fired_at);
