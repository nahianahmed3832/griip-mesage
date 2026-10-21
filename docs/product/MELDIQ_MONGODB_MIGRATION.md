# MongoDB as Meldiq's only server database: migration plan and status (2026-10-21)

Master prompt §6. The mechanism is in `docs/MONGODB.md`: collections, validators, indexes, the copy-verify-cutover
tools, rollback and authority guards. This file is the programme: which data is where, the order of the remaining
moves, the recipe, the measured size of the work, and the cutover.

**Rule:** MongoDB becomes the sole canonical server-side database. The phone's Room/SQLCipher database stays: it is
the device's encrypted local store, not a server database. No permanent dual writes and no hidden SQLite fallback. A
domain lives in exactly one store at a time, and the server refuses to start otherwise (`src/mongo/index.ts`).

## 1. Where everything is today

| Domain | Tables | SQL statements outside `src/mongo` (counted 2026-10-21) | Can run on MongoDB | Production |
|---|---|---|---|---|
| accounts | accounts, sessions, codes, legacy tickets, recovery codes and tickets, key backups and unlocks | 55 (4 files) | **Yes** (`MongoAccountStore`, `MongoKeyBackupStore`) | SQLite |
| preferences | nav_preferences | 6 (1 file) | **Yes** (`MongoPreferenceStore`) | SQLite |
| history | history_items, history_accounts | 11 → 0 in routes (2026-10-21) | **Yes, new 2026-10-21** (`MongoHistoryStore`) | SQLite |
| files | attachments, uploads, abandoned uploads, blob deletes | 37 (7 files) | Copy and verify only | SQLite |
| federation | peers, domains, inbox, outbox, blocks | 26 (4 files) | Copy and verify only | SQLite |
| relay | delivery_queue, submitted_messages, calls, call participants | 73 (10 files) | Copy and verify only | SQLite |
| conversations | conversations, members, mention events | 106 (19 files) | Copy and verify only | SQLite |
| identity | users, devices, blocks, push endpoints, FCM tokens, reminders, invites | 177 (32 files) | Copy and verify only | SQLite |
| workspace | orgs, members, roles, categories, overwrites, bans, invitations, audit | 202 (25 files) | Copy and verify only | SQLite |
| integrations | retired with the apps | — | Ignored | Kept for `admin retired-data` only |

**Total:** 724 statements, of which about 650 are in the seven domains not yet movable. Every table can already be
copied to MongoDB and verified byte for byte (`admin mongo-dryrun`), so the data model is proven for all of them.
What remains is moving the code that reads and writes them.

## 2. Why the remaining domains take real work

- **Synchronous permission checks.** `authz.ts` answers synchronously on the message hot path. It reads
  conversations, members, roles and overwrites. Moving those domains means making authorisation asynchronous and
  passing a per-request snapshot. Otherwise every message would wait on several MongoDB round trips.
- **Single-process atomicity.** Many routes rely on SQLite's synchronous transactions. Examples: creating a channel
  with its overwrites, removing a member from every channel, deleting an organisation. In MongoDB each of these needs
  a multi-document transaction (Atlas is a replica set, so they're available) or a redesign into single-document
  conditional updates.
- **Cross-domain cascades.** Account deletion and identity reset touch several domains in one SQLite transaction.
  While domains are split across stores, each cascade becomes "commit in the store that decides, then clean up the
  other". A retention sweep catches anything a failure left behind. History now works this way (§4).

## 3. Order of the remaining moves

From least to most coupled, so each step is small enough to test on both stores:

1. **history**: done 2026-10-21.
2. **files** (37). Attachment metadata and upload sessions. The S3 objects don't move. Upload completion is a
   conditional update. The retention job and the 1 GiB multipart path are the risky parts: run `files_1g.test.ts`
   and the direct-upload suites on both stores.
3. **federation** (26). Self-contained and off by default in production.
4. **relay** (73). The delivery queue is the hot path. It needs load testing (enqueue/ack throughput, the 7-day TTL
   sweep) before it moves. It depends on conversations only for fan-out membership, which is read once per message.
5. **conversations** (106) and **workspace** (202), together with `authz` going asynchronous. This is the largest
   step. Authorisation reads both domains, so they move as one. Plan:
   - an `AuthzSnapshot` per request (the member's roles, the conversation's overwrites and category), loaded with one
     query per domain and cached for the WebSocket frame or HTTP request;
   - the routes then await `ctx.authz.load(...)` once.
6. **identity** (177). Users and devices are read everywhere (key lookups, presence, push). It goes last, behind a
   small in-process cache of public keys and device lists, invalidated by the existing `devices.changed` events.

Rough size: 2 and 3 take a session each. 4 takes one session plus load tests. 5 takes two to three sessions.
6 takes one to two. These are estimates, not commitments.

## 4. The recipe (as done for history)

1. A store interface with the operations the routes need (`src/history/store.ts`). Every method is async. Every
   check-and-write is one method, so the store decides how to make it atomic.
2. `SqliteXStore`: the existing SQL moved behind the interface, unchanged, so the SQLite behaviour can't drift.
3. `MongoXStore`: same documents as the migration writes (`docs/MONGODB.md` §3: row fields plus `_id`, BLOBs as
   binary). Check-and-write runs in `MeldiqMongo.transaction`. History also queues each account's writes in-process,
   so a standalone development MongoDB without transactions stays correct too.
4. Wiring:
   - `ctx.<store>` is chosen by `ctx.mongo?.owns(domain)`;
   - the domain is added to `MONGO_DOMAINS` (`src/mongo/config.ts`), which also enables `admin mongo-migrate <domain>`
     and the authority guard.
5. Cross-domain cascades:
   - SQLite's copy is still deleted in the original transaction;
   - the MongoDB copy is deleted after the commit;
   - a retention sweep removes anything whose owner is gone (`purgeHistory`: orphaned accounts → deleted).
6. Tests:
   - every existing suite passes on SQLite and with `MELDIQ_TEST_MONGO_DOMAINS=…,<domain>`;
   - tests that poked SQLite directly do it in the active store;
   - a MongoDB suite covers concurrency, cascades, retention, and reading what `migrateToMongo` copied
     (`test/history_mongo.test.ts`).

## 5. History move (2026-10-21)

Files:
- `src/history/store.ts` (interface and SQLite store), `src/mongo/history.ts` (MongoDB store);
- `src/routes/history.ts` (routes use `ctx.history`; `purgeHistory`, `deleteHistoryAfterCommit`);
- `src/jobs.ts` (retention through the store; an unreachable MongoDB doesn't stop the rest);
- `src/accounts/deletion.ts`, `src/accounts/identity.ts` (MongoDB delete after the commit);
- `src/admin.ts` (says "in MongoDB" instead of reading a stale SQLite copy);
- `src/mongo/config.ts` (`history` added to `MONGO_DOMAINS`).

Behaviour kept:
- quotas per account and server-wide;
- all-or-nothing batches;
- per-account write order (`seq`), with an edit or deletion moving the item to the end;
- deletion markers purged after `HISTORY_TOMBSTONE_DAYS`.

New in MongoDB mode: history left by a deletion that couldn't reach MongoDB is removed by the next retention run, with
a warning in the log.

Tests (results in `MELDIQ_SESSION_HANDOFF.md`):
- `test/history_mongo.test.ts`: 6 tests. Parallel writes from one account; encryption reset; account deletion;
  retention of orphans; migrating SQLite history and reading it byte for byte.
- `history_keep`, `abuse_storage`, `account_delete` and `accounts_devices` on both stores.
- The full suite on both stores.

## 6. Hosting

Recommendation: **MongoDB Atlas on AWS, ap-southeast-1** (the region of the S3 bucket and closest to the users).
- Start with M10 (dedicated, continuous backups, replica set with transactions) for production.
- Use M0 or M2 only for a staging rehearsal.
- Allow only the API host's address (IP access list), or use AWS PrivateLink once the API moves to AWS
  (`MELDIQ_AWS_ARCHITECTURE.md`).

**Amazon DocumentDB is not recommended.** Meldiq uses `$jsonSchema` validators with `validationLevel: strict`,
collation-based case-insensitive unique indexes (`accounts.email`), partial unique indexes and multi-document
transactions. DocumentDB's support for collation and some validator features differs from MongoDB's, so it would
change the semantics. Choosing it would need each of those verified first.

## 7. Cutover (per domain), and what the owner must do

1. Owner: create the Atlas cluster and a `readWrite` user for the Meldiq database, and allow the API host. Put the URI
   in the server's `.env`, piped in and never shown.
2. Encrypted off-host backup of the SQLite database (`docs/MONGODB.md` §7).
3. `admin mongo-dryrun` against a snapshot, on a staging copy.
4. In a maintenance window:
   - `admin mongo-migrate <domain>`, which copies, verifies and records the authority;
   - add the domain to `MONGODB_DOMAINS`;
   - restart;
   - run the smoke checks (`docs/MONGODB.md` §8).
5. Rollback: `admin mongo-rollback <domain>` exports back to SQLite and moves the authority back. The SQLite copy is
   kept unchanged until the owner approves cleanup.
6. When all domains have moved: remove SQLite from the server runtime (`better-sqlite3` stays only in the migration
   and rollback tools) and delete the SQLite data after the owner approves.

**Blocked on the owner:** step 1. Nothing in production uses MongoDB today.
