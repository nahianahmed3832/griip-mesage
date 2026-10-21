-- Batch B4b (Discord reference ADMIN-CTRL-008, ADMIN-CTRL-002): join applications and activity alerts.
-- [join_questions]: JSON ["question", ...] (up to 5); NULL: the community doesn't take applications. A public community
-- with questions shows "Apply to join"; moderators approve or deny. [activity_alerts]: tell moderators when joins
-- spike; [raid_alert_at]/[raid_alert_count]: the last alert. Additive: older servers ignore the columns.
ALTER TABLE orgs ADD COLUMN join_questions TEXT;
ALTER TABLE orgs ADD COLUMN activity_alerts INTEGER NOT NULL DEFAULT 1;
ALTER TABLE orgs ADD COLUMN raid_alert_at INTEGER;
ALTER TABLE orgs ADD COLUMN raid_alert_count INTEGER;

CREATE TABLE join_applications (
  id TEXT PRIMARY KEY,
  org_id TEXT NOT NULL REFERENCES orgs (id) ON DELETE CASCADE,
  user_id TEXT NOT NULL,
  answers TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'denied', 'withdrawn')),
  created_at INTEGER NOT NULL,
  handled_by TEXT,
  handled_at INTEGER,
  note TEXT
);
CREATE INDEX join_applications_org ON join_applications (org_id, status, created_at);
-- One pending application per person and community is checked by the server in the same synchronous step that
-- inserts it (a unique partial index wouldn't map to MongoDB without a hand-written rule).
CREATE INDEX join_applications_person ON join_applications (org_id, user_id, status);
