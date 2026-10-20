-- 2026-10-20 (round 7 slice C4): workspace documents (docs/DOCUMENTS.md). Additive only.
--
-- Pages a workspace writes together: project briefs, meeting notes, onboarding guides, procedures, FAQs, policies.
-- Owner's decision (2026-10-20): documents are readable by the server (so they can be searched and, later and only on
-- request, summarised), and the apps label them clearly as not end-to-end encrypted, unlike messages.
--
-- Access: "workspace" (every member who isn't a guest gets default_role), "channel" (members of channel_id get
-- default_role; the channel's managers administer it), or "restricted" (only the people listed). People listed in
-- document_members get their own role whatever the access; the creator administers the document.

CREATE TABLE documents (
  id            TEXT PRIMARY KEY,
  org_id        TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  title         TEXT NOT NULL,
  content       TEXT NOT NULL,                  -- Markdown
  access        TEXT NOT NULL CHECK (access IN ('workspace','channel','restricted')),
  default_role  TEXT NOT NULL CHECK (default_role IN ('view','comment','edit')),
  channel_id    TEXT REFERENCES conversations(id) ON DELETE SET NULL,
  created_by    TEXT NOT NULL,
  updated_by    TEXT NOT NULL,
  rev           INTEGER NOT NULL DEFAULT 1,
  archived_at   INTEGER,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);
CREATE INDEX idx_documents_org ON documents(org_id, updated_at);
CREATE INDEX idx_documents_channel ON documents(channel_id);

CREATE TABLE document_members (
  document_id  TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role         TEXT NOT NULL CHECK (role IN ('view','comment','edit','admin')),
  added_by     TEXT NOT NULL,
  added_at     INTEGER NOT NULL,
  PRIMARY KEY (document_id, user_id)
);
CREATE INDEX idx_document_members_user ON document_members(user_id);

-- One row per saved revision, so earlier versions can be read and restored (the newest 50 per document are kept).
CREATE TABLE document_versions (
  id           TEXT PRIMARY KEY,
  document_id  TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  rev          INTEGER NOT NULL,
  title        TEXT NOT NULL,
  content      TEXT NOT NULL,
  author_id    TEXT NOT NULL,
  created_at   INTEGER NOT NULL,
  UNIQUE (document_id, rev)
);

CREATE TABLE document_comments (
  id           TEXT PRIMARY KEY,
  document_id  TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  author_id    TEXT NOT NULL,
  body         TEXT NOT NULL,
  quote        TEXT,                            -- the passage commented on, if any
  resolved_at  INTEGER,
  resolved_by  TEXT,
  created_at   INTEGER NOT NULL,
  updated_at   INTEGER NOT NULL
);
CREATE INDEX idx_document_comments_doc ON document_comments(document_id, created_at);
