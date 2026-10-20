-- 2026-10-20 (round 7 slice C8): a workspace's AI switch and provider choice (docs/AI.md). Additive only: AI stays off
-- everywhere until a workspace's admins turn it on, and the server has no provider until the operator configures one.
-- ai_provider: anthropic | openai | gemini, or NULL for the server's default.
ALTER TABLE orgs ADD COLUMN ai_enabled INTEGER NOT NULL DEFAULT 0;
ALTER TABLE orgs ADD COLUMN ai_provider TEXT;
ALTER TABLE orgs ADD COLUMN ai_changed_by TEXT;
ALTER TABLE orgs ADD COLUMN ai_changed_at INTEGER;
