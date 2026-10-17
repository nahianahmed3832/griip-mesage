# Retention

The relay queue is **not** a message archive. Since 2026-10-17 each account's history is also kept on its server,
end-to-end encrypted (docs/MESSAGE_HISTORY.md); before that it existed only on users' devices.

| Data (server) | Kept for | Deleted by |
|---|---|---|
| Encrypted message envelopes (`delivery_queue`, kind `envelope`) | until the recipient device acknowledges, **max 7 days** (`QUEUE_TTL_SECONDS`, hard-capped at 604800 in code) | ack → immediate delete; expiry → cleanup job every 60 s; reads always filter `expires_at > now` |
| Delivery receipts (`delivery_queue`, kind `receipt`) | until acknowledged, max 7 days | same |
| Idempotency records (`submitted_messages`: id, sender, conversation, count) | 7 days | cleanup job |
| Encrypted attachments (file store: `/data/attachments` or S3, `attachments` table) | **7 days** (`ATTACHMENT_TTL_SECONDS`, capped at 7 d), even if already downloaded | cleanup job deletes the row; the bytes are queued in `pending_blob_deletes` and deleted (retried on failure) |
| Attachments kept for history (`attachments.kept = 1`) | `ATTACHMENT_KEEP_DAYS` (365) after the last request to keep them | same |
| Encrypted message history (`history_items`) | until the person deletes it (Data & Storage) or the account is removed; deletion markers `HISTORY_TOMBSTONE_DAYS` (90) | the person; account removal (foreign key); cleanup job for markers |
| A deleted workspace's rows and files | removed at once by the owner's deletion; `org_deletions` keeps id, hashed name, who, when and counts | `DELETE /v1/orgs/:id` (docs/WORKSPACES.md) |
| Auth challenges | 2 minutes | cleanup job / consumed on use |
| Sessions (hashed tokens) | 7 days | cleanup job / logout / device revocation |
| Pairing requests | 10 minutes | cleanup job |
| Call records (`calls`, `call_participants`) | 90 days after the call ended (`CALL_RECORD_RETENTION_DAYS`) | cleanup job, every 60 s (since 2026-10-16) |
| Audit log (no content) | 365 days (`AUDIT_RETENTION_DAYS`, minimum 30) | cleanup job, every 60 s (since 2026-10-16) |
| Org structure, memberships, directory | permanent while the org exists | admin actions |

**Backups (updated 2026-10-17).** The database now holds each account's encrypted message history, so back it up
(`scripts/backup.sh`); it is ciphertext under opaque tags. Kept files (`attachments.kept = 1`) belong to that history.
With `STORAGE_BACKEND=local` they are in `/data/attachments`, so back that folder up too (files there that are only in the
7-day window may be left out). With S3, turn on bucket versioning or replication instead. The delivery queue itself
doesn't need backing up.

**Message loss is still possible before a message reaches a device's history** (history keeps what a device has
received or sent and uploaded). If a recipient device doesn't come online within 7 days, or every copy is
lost first (the sender's device is wiped before sending, or the queue is lost), the message is gone. Senders see
"Sent" (accepted by the relay) and then "Delivered" when the recipient's device acknowledges it.

**On devices:**
- History stays in the SQLCipher database until the user deletes the conversation or uses "Sign out & erase this device".
- Media keys for calls are deleted 24 h after the call.
- Plaintext of unsent messages stays in the encrypted outbox until it is sent or fails.

Verified by `server/test/messaging.test.ts`. That test covers ack deletion, a 1-second TTL (items stop being served immediately and
are removed by cleanup), and the 7-day cap. Attachment expiry is covered by `server/test/calls_attachments.test.ts`.
On production, `admin.js stats` showed 0 queued envelopes after the smoke test, because all of them were acknowledged and deleted.

**Physical deletion (2026-10-16):**
- The server database runs with `PRAGMA secure_delete = ON`. Deleted rows (acknowledged envelopes, expired sessions, pruned records)
  are overwritten with zeros rather than left in free pages.
- In WAL mode the zeroed pages reach the main database file at the next checkpoint. Until then, the old page images can remain in the
  `-wal` file, which SQLite truncates and reuses.
