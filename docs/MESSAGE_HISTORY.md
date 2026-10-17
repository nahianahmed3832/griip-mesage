# Encrypted message history on the server (2026-10-17)

Until this change, history existed only on phones. The relay deleted each encrypted copy once it was delivered, or after
7 days. Reinstalling the app, or moving to a new phone (even with the recovery key), started with empty chats.
History is now kept on the account's own server, end-to-end encrypted, so a phone that signs in to the account gets
it back.

## Design

| | |
|---|---|
| Keys | Derived on the phone from the **account identity seed** with HKDF-SHA256 (salt `orbit-history-v1`, info `enc|<userId>` and `tag|<userId>`). Every device of the account has the seed: the first phone, a linked phone, and a phone restored with the recovery key. Servers never have it. |
| Items | One per message and one per chat, encrypted with AES-256-GCM (random nonce). The associated data binds the item to the account and its tag, so the server can't move items between tags or accounts. |
| Tags | HMAC-SHA256 of `m|<message id>` or `c|<conversation id>` under the derived tag key. The server can replace an item and group a chat's items without learning message or conversation ids. |
| What's in an item | The phone's own row for the message (text, attachment references including each file's key, replies, reactions, edit/delete state, call entries, contact cards, locations) or for the chat (type, name, mute/archive preferences). Drafts, unread counts and error text stay on the phone. |
| What the server sees | How many items an account has, their sizes, when they were written, the message time each item gives for ordering, and which items belong to the same (opaque) chat. Never content, senders, or real ids. |
| Code | Server: `server/src/routes/history.ts`, migration 009. App: `crypto/HistoryCrypto.kt` (`HistoryKeys`, `Hkdf`), `data/repo/HistorySync.kt`, triggers in `data/db/Migrations.kt` (`HistoryTriggers`), Room table `history_outbox` (schema v7). |

## How it runs on the phone

1. **Queue.** SQLite triggers record every new message and chat, and every meaningful change: text, edits, deletions
   for everyone, reactions, pins, delivery status, attachments, mute and archive. Receipts, member presence, drafts
   and unread counts don't count, and nor does Room rewriting a row with the same values. No code path that writes a
   message can be missed. Deleting a row never queues anything, so nothing on the phone can delete server history by
   accident.
2. **Upload.** A worker woken by the queue waits 3 s to batch, encrypts and sends up to 150 items or 3 MB at a time
   (`PUT /v1/history/items`), and clears only entries nothing has changed since. It skips messages still sending, failed
   or undecryptable; they queue again when they settle. On failure it retries with backoff (30 s up to 15 min).
3. **Files.** For a message from the last 7 days, the worker asks the server to keep each of its files
   (`POST /v1/attachments/:id/keep`). If that fails transiently, the message stays queued and both are retried.
4. **Download.** Each time the account signs in on the phone (new install, recovery-key restore, linked device, app
   start), the worker fetches everything written since its position (`GET /v1/history/items?after=…`). It adds messages
   and chats the phone doesn't have. An existing message changes only for a later edit or a deletion for everyone.
   On the first download, a new phone also takes the account's mute and archive choices. What it adds is taken off
   the upload queue (it's already on the server), and the chat list gets the newest restored message as its preview.
5. **First run after updating.** Everything already on the phone is queued once, before the first download, so an
   existing install backs up its history.

Data & Storage › **Message history on the server**:
- the switch: on by default; off stops uploading and deletes nothing;
- the status: items, size, last backup, or what is pending or failing;
- **Restore history from the server**: adds whatever this phone is missing, replacing nothing;
- **Delete my history from the server**: turns history off and deletes the encrypted copy; phones keep theirs.

## Server

| Endpoint | |
|---|---|
| `GET /v1/history/summary` | items, bytes, position, limits |
| `PUT /v1/history/items` | up to 200 items per batch, all or nothing. A `null` body marks an item deleted (deletion markers are pruned after `HISTORY_TOMBSTONE_DAYS`, default 90). The account's items and bytes are checked against `HISTORY_MAX_ITEMS_PER_USER` / `HISTORY_MAX_BYTES_PER_USER` (500 000 / 1 GiB); over the quota the whole batch gets 429. Items: `HISTORY_MAX_ITEM_BYTES` (64 KiB). |
| `GET /v1/history/items?after=&limit=` | in write order, paged |
| `DELETE /v1/history` | everything for this account (audited without content) |

Only the account itself can read or write its history. Deleting a user row deletes it (foreign keys).
`HISTORY_ENABLED=false` turns history off for a server; apps then keep history only on phones and say so.
`server-info` lists `history` (and `attachment-keep`) in `features` when they are available.

## Files kept with history

The relay keeps files 7 days. A member's phone asks the server to keep a file from its history:

- **How long.** The file stays `ATTACHMENT_KEEP_DAYS` (default 365) from the latest request; any member can ask again.
- **Quota.** Kept files count against the uploader's `ATTACHMENT_USER_KEPT_BYTES` (default 10 GiB) instead of the 7-day
  quota. `ATTACHMENT_TOTAL_BYTES` still caps everything stored.
- **Access.** Files stay end-to-end encrypted, and their keys are only inside messages and history. Downloads still
  require membership of the conversation, so someone who has left a chat can't fetch its files any more. Their
  restored messages then show the file as expired.
- **Older files.** Files older than 7 days when history was first switched on (an existing install's backlog) had
  already expired on the server. Those messages come back without their files.

## Limits and honest caveats

- **Lost everything.** History can be decrypted only with the identity seed. If every device **and** the recovery key
  are lost, nobody can decrypt it, including the server operator.
- **New identity.** An account re-registered with a new identity key (a lost phone without the recovery key) can't
  read history made under the old one. Restore reports such items as unreadable.
- **The recovery key matters more now.** It unlocks the account's whole stored history, so it must be kept offline.
- **Metadata.** The server sees counts, sizes and timing (above). It sees similar metadata from relaying messages
  anyway.
- **Older apps.** Apps from before this change don't upload history. Their messages are added to it when a newer app
  of the same account sees them, because each device uploads what it holds.

## Verified (2026-10-17)

- **Server.** `test/history_keep.test.ts` covers:
  - paging and isolation between accounts;
  - replacing and deleting items, and pruning deletion markers;
  - item, batch and account quotas;
  - deleting all history, and turning history off;
  - keeping files and the kept-file quota;
  - the `server-info` feature list.
- **App (unit tests).**
  - `HistoryCryptoTest`: round trip; another device of the same account; tampered, moved, foreign and wrong-key items
    refused; the RFC 5869 HKDF vector.
  - `HistorySyncTest`: what is uploaded, how downloads merge, record round trip, previews.
  - `MigrationsTest`: v6→v7, with the triggers queueing only real changes.
- **Emulator against a local server.**
  1. 20 existing items uploaded after updating.
  2. A photo and a video sent and kept (`kept=1`).
  3. The test account's app data wiped (`pm clear`) and the account restored with its recovery key.
  4. All chats, messages, reactions and read states came back, and the restored photo downloaded and decrypted.
- **Not verified yet:** a physical phone; the production server (not deployed).
