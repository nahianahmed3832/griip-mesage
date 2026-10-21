-- Batch B6b: a group's photo (like a community's, not end-to-end encrypted: its members' server can see it), and the
-- presence a person chooses. [presence_mode]: NULL (automatic: online while connected), 'idle', 'dnd' or 'invisible'
-- (appears offline); [presence_until]: when the choice ends (NULL: until changed). Additive.
ALTER TABLE conversations ADD COLUMN photo_rev INTEGER;
ALTER TABLE users ADD COLUMN presence_mode TEXT;
ALTER TABLE users ADD COLUMN presence_until INTEGER;
