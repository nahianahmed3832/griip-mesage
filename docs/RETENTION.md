# Retention

The relay is **not** a message archive. Conversation history exists only on users' devices.

| Data (server) | Kept for | Deleted by |
|---|---|---|
| Encrypted message envelopes (`delivery_queue`, kind `envelope`) | until the recipient device acknowledges, **max 7 days** (`QUEUE_TTL_SECONDS`, hard-capped at 604800 in code) | ack → immediate delete; expiry → cleanup job every 60 s; reads always filter `expires_at > now` |
| Delivery receipts (`delivery_queue`, kind `receipt`) | until acknowledged, max 7 days | same |
| Idempotency records (`submitted_messages`: id, sender, conversation, count) | 7 days | cleanup job |
| Encrypted attachments (`/data/attachments`, `attachments` table) | **7 days** (`ATTACHMENT_TTL_SECONDS`, capped at 7 d), even if already downloaded | cleanup job deletes the row and the file |
| Auth challenges | 2 minutes | cleanup job / consumed on use |
| Sessions (hashed tokens) | 7 days | cleanup job / logout / device revocation |
| Pairing requests | 10 minutes | cleanup job |
| Call records (`calls`, `call_participants`) | kept for operations (no media, no content) | not yet pruned; see KNOWN_ISSUES |
| Org structure, memberships, directory, audit log (no content) | permanent while the org exists | admin actions |

Queue backups are **not configured**. Don't back up `delivery_queue` or `/data/attachments`. If you back up the volume,
exclude `/data/attachments` and run `node dist/admin.js cleanup` first.

**Message loss is possible by design.** If a recipient device doesn't come online within 7 days, or every copy is
lost first (the sender's device is wiped before sending, or the queue is lost), the message is gone. Senders see
"Sent" (accepted by the relay) and then "Delivered" when the recipient's device acknowledges it.

**On devices:**
- History stays in the SQLCipher database until the user deletes the conversation or uses "Sign out & erase this device".
- Media keys for calls are deleted 24 h after the call.
- Plaintext of unsent messages stays in the encrypted outbox until it is sent or fails.

Verified by `server/test/messaging.test.ts`. That test covers ack deletion, a 1-second TTL (items stop being served immediately and
are removed by cleanup), and the 7-day cap. Attachment expiry is covered by `server/test/calls_attachments.test.ts`.
On production, `admin.js stats` showed 0 queued envelopes after the smoke test, because all of them were acknowledged and deleted.
