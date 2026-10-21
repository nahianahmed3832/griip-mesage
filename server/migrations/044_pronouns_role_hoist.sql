-- Batch B12: a person's pronouns (shown with the bio, under the same privacy setting), and roles whose members are
-- listed separately in the community's member list ([hoist], like a heading per role). Additive.
ALTER TABLE users ADD COLUMN pronouns TEXT;
ALTER TABLE org_roles ADD COLUMN hoist INTEGER NOT NULL DEFAULT 0;
