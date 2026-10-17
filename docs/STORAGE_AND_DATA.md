# What is stored where (audit, 2026-10-17)

Orbit is local-first: message history and files live on the phones; servers route and briefly queue ciphertext.

## On the phone (per account)
Encrypted database (SQLCipher; key sealed by the Android Keystore): chats, messages, people, call history, settings
state. Identity and device private keys: sealed by the Keystore, never exported (the recovery key is shown only on
request). Decrypted copies of downloaded files: app-private storage. Caches (thumbnails, profile photos): app cache.

## On a server

| Data | Content visible to the server | Kept |
|---|---|---|
| Accounts: username, display name, avatar colour, bio, names, birthday, phone, privacy settings | yes (published profile data, shown per the person's settings) | until the account is removed |
| Profile photos (`DATA_DIR/profile-photos`) | yes (not end-to-end encrypted) | until replaced or removed |
| Devices (public keys, certificates), sessions | public keys only | sessions expire; devices until revoked |
| Conversations, members, roles, workspaces | names and membership | while they exist |
| Delivery queue (`delivery_queue`) | **ciphertext only** | until the device acknowledges it, at most 7 days |
| Attachments (`DATA_DIR/attachments`) | **ciphertext only** | 7 days (`ATTACHMENT_TTL_SECONDS`, capped) |
| Call records (who started, answered, connected, times) | metadata, no media | 90 days (`CALL_RECORD_RETENTION_DAYS`) |
| Call media | relayed by LiveKit, end-to-end encrypted when on | not stored |
| Audit log (security events, never message content) | metadata | 365 days (`AUDIT_RETENTION_DAYS`) |
| Federation outbox/inbox | signed envelopes with ciphertext | until delivered or expired |
| Blocks | who blocked whom | until unblocked |

So servers do keep metadata (who talks to whom and when, sizes, membership, profiles people publish) and briefly queue
ciphertext; they never hold plaintext messages, files, call media or private keys. Nothing in this round deletes or
migrates existing production data. Migration 008 only adds nullable profile columns.
