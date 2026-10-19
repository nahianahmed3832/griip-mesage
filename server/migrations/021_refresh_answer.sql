-- 2026-10-19: racing refreshes get one answer (docs/SECURITY.md › Sessions). Additive only.
--
-- The pair the session's last rotation handed out, AES-256-GCM sealed with a key derived from the refresh token that
-- rotation replaced. That token sent again within the race window (a lost answer, or two requests racing) gets the
-- same pair back instead of rotating again and orphaning the first answer. Only that token's holder can open it, and the
-- next rotation overwrites it. NULL until the session's next rotation (and for sessions rotated by older servers).

ALTER TABLE account_sessions ADD COLUMN previous_refresh_answer TEXT;
