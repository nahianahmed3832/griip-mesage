-- Self-service account deletion (docs/ACCOUNT_DELETION.md). The number was kept free for it since 2026-10-18
-- (014_accounts.sql); the runner applies any missing version, so a database already at 028 gets it too.
-- Additive: one nullable column, so existing accounts and older servers are unaffected. A deleted account keeps only an
-- anonymous users row (its id, so direct conversations and records that name it stay consistent, its public identity
-- key, and this time). Every personal field is cleared, and `disabled_at` is set too, so every lookup that already
-- skips disabled accounts skips deleted ones. The email-and-password account row itself is removed.
ALTER TABLE users ADD COLUMN deleted_at INTEGER;
