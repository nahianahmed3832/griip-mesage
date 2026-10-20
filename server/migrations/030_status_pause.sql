-- 2026-10-21 (Meldiq round 3, docs/product/MELDIQ_3_REFERENCE_MATRIX.md): statuses with an emoji and an end time
-- ("In a meeting, 1 hour"), and pausing notifications until a time. Additive only; 0.9.0 ignores the columns.
ALTER TABLE users ADD COLUMN status_emoji TEXT;
ALTER TABLE users ADD COLUMN status_expires_at INTEGER;
ALTER TABLE users ADD COLUMN notifications_paused_until INTEGER;
