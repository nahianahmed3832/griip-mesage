-- 2026-10-19 (round 6): voice rooms (docs/VOICE_ROOMS.md). Additive only: existing rows keep their meaning.
--
-- A voice room is a workspace channel marked `voice`: it stays in the sidebar, and joining it starts or joins its call
-- without ringing anyone. Calls in a voice room are marked `room`; raised hands belong to the call.

ALTER TABLE conversations ADD COLUMN voice INTEGER NOT NULL DEFAULT 0;
ALTER TABLE calls ADD COLUMN room INTEGER NOT NULL DEFAULT 0;

CREATE TABLE call_hands (
  call_id    TEXT NOT NULL REFERENCES calls(id) ON DELETE CASCADE,
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  raised_at  INTEGER NOT NULL,
  PRIMARY KEY (call_id, user_id)
);
