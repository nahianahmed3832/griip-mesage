-- 2026-10-16: creating an organisation is idempotent. The app sends one request id per "Create workspace" form, so a
-- retry or a double tap returns the organisation the first request created instead of making another one with the
-- same name (seen on two phones: one person ended up owning five identical "GriipBuddy" workspaces).
-- NULL for every existing row and for older apps that don't send an id.
ALTER TABLE orgs ADD COLUMN create_request_id TEXT;
CREATE UNIQUE INDEX idx_orgs_create_request ON orgs(created_by, create_request_id) WHERE create_request_id IS NOT NULL;
