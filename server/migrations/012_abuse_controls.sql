-- Abuse controls (docs/ABUSE_CONTROLS.md). Additive: existing accounts get no limits, and older servers ignore both.
-- An account made by open registration has smaller allowances until this time (null: none). Set at registration,
-- cleared by an invitation from an established member or by `admin.js limits-end`.
ALTER TABLE users ADD COLUMN limited_until INTEGER;
-- Counting the day's open registrations (audit rows "user.register" without an invitation) without a table scan.
CREATE INDEX idx_audit_action ON audit_log(action, at);
