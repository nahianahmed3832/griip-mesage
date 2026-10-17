-- Profile fields and per-field privacy (2026-10-17, docs/PROFILE_AND_PRIVACY.md).
-- Every column is optional: existing accounts keep exactly what they had, and the new fields start empty. Privacy null
-- means the defaults in src/profile.ts (birthday and phone visible to nobody).
ALTER TABLE users ADD COLUMN first_name TEXT;
ALTER TABLE users ADD COLUMN last_name TEXT;
ALTER TABLE users ADD COLUMN birthday TEXT;            -- "YYYY-MM-DD", or "--MM-DD" without the year
ALTER TABLE users ADD COLUMN phone TEXT;               -- E.164 as entered by the person; not verified
ALTER TABLE users ADD COLUMN photo_rev INTEGER;        -- revision of the profile photo (null: none)
ALTER TABLE users ADD COLUMN privacy TEXT;             -- JSON {field: "everyone" | "contacts" | "nobody"}
ALTER TABLE users ADD COLUMN username_changed_at INTEGER;
CREATE INDEX idx_users_phone ON users(phone) WHERE phone IS NOT NULL;
