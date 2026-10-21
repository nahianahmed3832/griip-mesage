-- Batch B8b (matrix COM-07, docs/product/MELDIQ_FULLSTACK_SPEC.md §5): onboarding questions. [prompts]: JSON
-- [{id, title, single, required, options: [{id, title, emoji, roleIds, channelIds}]}], plaintext configuration like
-- roles. [onboarding_answers]: what each member chose, and the roles and channels onboarding gave them (so changing
-- answers takes back only what onboarding gave). Additive.
CREATE TABLE community_onboarding (
  org_id TEXT PRIMARY KEY REFERENCES orgs (id) ON DELETE CASCADE,
  enabled INTEGER NOT NULL DEFAULT 0,
  prompts TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE onboarding_answers (
  org_id TEXT NOT NULL REFERENCES orgs (id) ON DELETE CASCADE,
  user_id TEXT NOT NULL,
  option_ids TEXT NOT NULL,
  role_ids TEXT NOT NULL,
  answered_at INTEGER NOT NULL,
  PRIMARY KEY (org_id, user_id)
);

-- The community guide (Discord reference COMM-CTRL-003/004/005 "Server Guide"): a welcome line, "Get started" tasks
-- that each point to a channel, and resources (channels worth knowing). JSON {welcome, tasks: [{id, title, channelId}],
-- resources: [{id, title, channelId}]}; NULL: no guide. Which tasks a person did is kept on their phone.
ALTER TABLE orgs ADD COLUMN guide TEXT;
