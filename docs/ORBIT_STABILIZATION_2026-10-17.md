# Orbit stabilization, persistence and storage round (2026-10-17)

Brief: "Stabilization, Cloud Persistence, Product Redesign, and Physical-Device Verification".

Built on `internal-beta` at `0fa2b71`. Nothing is committed, pushed or deployed.

Verification so far: automated tests, an emulator (Android 16) and a local server. **The physical phone wasn't
connected during this round** (§10). **No AWS resource was created or contacted** (§5).

## 1. Chat crash: root cause and fix

- **Symptom.** Opening any conversation crashed the app.
- **Reproduced on the emulator**, logcat crash buffer:
  `NullPointerException: Attempt to invoke interface method 'void kotlinx.coroutines.flow.MutableStateFlow.setValue(java.lang.Object)' on a null object reference at ConversationViewModel$3.invokeSuspend(ConversationViewModel.kt:93)`.
- **Root cause.**
  - `ConversationViewModel`'s `init` block launched `maxFile.value = c.messages.maxAttachmentBytes()`, but `maxFile` was
    declared about 270 lines later. Kotlin initialises properties in declaration order.
  - `viewModelScope` runs on `Dispatchers.Main.immediate`, so the coroutine started inside the constructor and read the
    receiver `maxFile` while it was still null.
  - When the network call resumed, `setValue` ran on null and crashed. That happened on every chat open, not just once
    the value was cached.
  - It came in with the 500 MB attachment work (59047d0) and wasn't caught then, because no device or emulator test ran
    in that round.
- **Fix.** The `init` block is now the last thing in the class (with a comment explaining why). Refreshing the
  conversation when a chat opens is wrapped so a failure there can't crash the screen.
- **Regression tests.**
  - `InitOrderTest` (JVM, 3 tests) scans every class in the app for an `init` block that uses a property declared after
    it. It has a self-test on the original bug's shape, delegates, string templates and nested classes.
  - `ConversationOpenTest` (on-device, 10 tests) opens each kind of conversation in the real screen, leaves it and opens
    it again:
    - a personal chat with every message kind (text, contact, location, animated emoji, link preview, forwarded,
      edited, pinned and reacted, call, deleted, failed);
    - a group with mentions, and a thread;
    - an announcement channel where the user can't post;
    - photos, video, files, voice messages, a 300 MB chunked file and an upload in progress;
    - replies, including to deleted and missing messages;
    - a federated conversation and an empty one;
    - malformed stored data (bad JSON everywhere, unknown kinds and senders, a 30 000-character text, control and
      right-to-left characters, time zero);
    - switching between chats repeatedly.
- **Proof the tests catch it.** **Before the fix, 10/10 fail with this exact NPE. After it, 10/10 pass** (emulator, both
  runs). The same open/back/reopen also works by hand on the emulator.

## 2. Chat-list swipe jumping: causes and fixes (docs/CHAT_LIST_ACTIONS.md)

All three causes were reproduced on the emulator (screen recordings and position dumps) and fixed:

1. **Swipe right flew the row off and back.** The row was Material's `SwipeToDismissBox`, which always animates a
   dismissed row to the full width. `SwipeableChat` now uses its own drag: marking read or unread reveals the action, up
   to 45% of the row, and springs back. Archiving still slides out. Rules are in `ChatSwipe`, tested in
   `ChatSwipeRulesTest` (5).
2. **Back from a chat, the list jumped to the top.** The chats were collected with an empty starting value, so the
   saved scroll position was clamped. `ChatsViewModel` now keeps the data. Before the fix the first row moved from
   y=183 to y=811; after it, the same position is restored mid-list and at the end.
3. **Near the end, the list came back shifted by the tab bar's height.** The tab bar's space vanished when a chat
   opened. The tab bar is now an overlay, and tab screens get `LocalTabBarPadding`. The end position is restored exactly.

Also:

- the chat order breaks ties by id;
- drags starting in Android's Back-gesture edge are left to Android, in chat rows and in swipe-to-reply;
- vertical scrolling is unchanged;
- archive, mute and read/unread, plus their long-press and accessibility actions, are kept.

## 3. Profile photo "Not found": root cause and fix (docs/PROFILE_AND_PRIVACY.md)

- **Root cause.** Production runs the **first server release (image `0.1.0`, only migration 001 applied)**, which has
  no photo endpoints. `PUT /v1/users/me/photo` got Fastify's own "Route … not found" 404, and the app showed it as
  "Not found.". Read-only check: `GET /v1/users/<id>/photo` returns `Route GET:/v1/users/u_test/photo not found`.
- **Fix in the app.**
  - A missing route is recognised and explained as "Your server doesn't support this yet…" (`ApiErrors`,
    `ApiErrorsTest`).
  - Edit profile reads the server's new `features` list. On an older server it explains up front what can't be saved
    and disables the photo button.
- **Fix on the server.**
  - Photos go through the new storage layer (disk or S3), under per-revision keys, so a replaced photo's old file is
    deleted.
  - The privacy check before serving a photo is unchanged.
- **Verified** on the emulator against a local server: select, upload, display, replace (the old file is gone), on
  disk. On S3 (fake): upload, replace, remove.
- **Production needs the server deployed (§13).**

## 4. Server persistence: architecture and status (docs/MESSAGE_HISTORY.md)

Before this round, history existed only on phones: the relay deleted delivered copies, so a reinstall started empty.

**What was added: an end-to-end encrypted, per-account message history on the server.**

- **Keys.** HKDF from the account identity seed. Every device of the account has it, and the recovery key restores it.
  The server never has it.
- **Items.** AES-256-GCM per message or chat, bound to the account and to an opaque HMAC tag.
- **Queue.** SQLite triggers on the phone queue every meaningful change. A worker encrypts and uploads them in batches,
  and asks the server to keep files referenced in the last 7 days (365 days, with their own quota).
- **Restore.** Each sign-in downloads and merges, adding what's missing and never replacing local data.
- **Settings.** Data & Storage has the switch, a status line, Restore, and Delete from server.
- **Server.** `history_items` (migration 009) with quotas, paging, deletion markers and account isolation.
- **Unchanged:** the relay queue, receipts, `griip-envelope-v1`, federation and account ids. Local caching and the
  encrypted backups (file / Google Drive picker) are unchanged too.

**Verified on the emulator, against the local server running this code:**

1. An existing install uploaded its 20 messages and chats.
2. A sent photo and a video were kept (`kept=1`).
3. The test account's app data was wiped (`pm clear`) and the account restored with its recovery key.
4. **Every chat, message, reaction and read state came back.** The restored photo downloaded from the server and
   decrypted with the key from the restored history.
5. The same flows work in the minified (R8) release build: upload, and a full restore with nothing unreadable.

**Honest limits.**

- If every device **and** the recovery key are lost, nobody can decrypt the history.
- The recovery key now unlocks the stored history, so it must be kept offline (SECURITY.md updated).
- The server sees item counts, sizes and times.
- Messages from before a device ran this version reach history only through a device that holds them.
- Files older than 7 days when history was turned on had already expired.

## 5. S3 integration: status, and what's needed

**Done in code** (`server/src/storage/`, docs/STORAGE_AND_DATA.md):

- **One blob store** for attachments, profile photos and federation copies: local disk (default, unchanged paths) or S3.
- **S3 operations.** SigV4 signing without the SDK (it matches AWS's three published S3 examples), single PUT with a
  signed SHA-256, multipart above 64 MiB (16 MiB parts, aborted on failure), and Range reads.
- **Durability and access.** Retries on 5xx and 429, deletions queued and retried, and optional 5-minute presigned
  download redirects.
- **Encryption.** `S3_SSE` AES256 or aws:kms.
- **Object keys** carry no names or metadata.
- **Credentials** live only in the server's environment, and errors never contain them.
- **Startup** refuses to run with incomplete S3 settings.
- **Tests.** 14 tests against an in-process fake S3 that checks every signature.

**Not done (needs your approval and details):**

- creating the bucket and IAM user;
- turning `STORAGE_BACKEND=s3` on anywhere;
- moving existing files.

**S3 is not active on any server.** This Mac has no AWS CLI, no `~/.aws` profiles and no `AWS_*` variables, and no AWS
API was called.

**Information needed before setting up S3** (the final message asks for it):

- account and permissions;
- region;
- bucket name (or let me propose one);
- how the server should get credentials;
- retention;
- KMS or S3-managed keys;
- spending limits.

## 6. UI and account controls changed this round

- **Navigation and lists:** the chat-list swipe row and stable lists (§2); the tab bar as an overlay.
- **Clear messages for older servers:** Edit profile, Delete workspace and history.
- **Data & Storage:** a "Message history on the server" section.
- **Real Delete workspace** (§7).
- **Settings text:** the Data & Storage subtitle (English, 中文, বাংলা), and the security, restore and empty-chat texts
  now describe server history accurately.
- **Kept from earlier rounds:** navigation (Chats, Calls, Contacts, Settings; no floating button; workspace under
  Contacts), the settings structure, profiles and privacy, the languages, rich messages and links.
- **Not done this round:** a broad visual redesign beyond these screens. It's best done with your feedback on
  screenshots from the phone.

## 7. Organization deletion (docs/WORKSPACES.md)

- **Who and how.** An owner types the exact name; the server re-checks the role and the name.
- **One immediate transaction:**
  - removes memberships, branches, departments, teams and invitations;
  - removes channels with their calls, queued messages and file records;
  - queues stored files for deletion;
  - records `org_deletions` and an audit entry without names;
  - handles retries with a `requestId` (a repeated request gets the same answer).
- **Notification.** Devices are notified, and older apps fall back gracefully.
- **Untouched:** DMs, groups, accounts and other workspaces.
- **Tests.** `org_delete.test.ts` (4: authorization, exact name, what goes and what stays, files, events, audit, retry,
  two owners at once, name reuse) and `DeleteWorkspaceRulesTest` (4).
- **Emulator** (throwaway workspace on the local server): a wrongly-cased name deleted nothing; the exact name deleted it.

## 8. Calls and notifications

No call or notification code changed this round, and their existing tests pass (`CallVerificationTest` after moving its
HKDF into a shared helper, plus the server `call_*` suites).

**Not re-tested on devices this round:** calls need two devices, and notifications need the phone.

## 9. Automated tests, lint and builds

| Check | Result |
|---|---|
| Server tests (vitest) | **195/195** (28 files), incl. new `storage_s3` 14, `history_keep` 9, `org_delete` 4 |
| Server typecheck / build | pass / pass |
| Android unit tests | **261/261** (55 classes). New: `InitOrderTest` 3, `ChatSwipeRulesTest` 5, `HistoryCryptoTest` 5 (incl. RFC 5869 vector), `HistorySyncTest` 5, `ApiErrorsTest` 2, `MigrationsTest` +1 (v7 + triggers), `DeleteWorkspaceRulesTest` rewritten (4) |
| On-device tests (emulator, Android 16) | **10/10** `ConversationOpenTest` (and 10/10 failing on the unfixed code) |
| Android lint | 0 errors, 79 warnings, 5 hints |
| `assembleRelease` (R8) | pass, 115.4 MB unsigned; installed on the emulator signed with the debug key: chats open, history uploads and restores |
| `bundleRelease` | pass, 63.7 MB unsigned |
| `assembleDebug` (standard and side-by-side) | pass |

Test tooling added:

- `androidx.test` runner 1.7.0, ext-junit 1.3.0 and Espresso 3.7.0 (older Espresso fails on Android 16), plus Compose
  ui-test;
- the test APK id is `com.griipbuddy.orbit.androidtest`, so it doesn't clash with "Orbit Test".

Debug builds now include Compose's empty test activity (`ui-test-manifest`), as is usual for Compose testing.

## 10. Physical-device results

**Not done: the HONOR phone was never connected.** `adb devices` listed only the emulator, the Mac's USB bus showed no
device, and no wireless-debugging device was advertised.

What remains is the checklist in §11. Every result above comes from the emulator and the local server.

## 11. Not verified yet

- **Physical phone:** opening chats; swiping; profile photo; sending and receiving media; history after a restart and
  after a reinstall plus restore; voice messages; notifications in the foreground and background.
- **Two-device:** calls (start, accept, decline, audio, video, end, reconnect) and multi-device history catch-up.
- **Real environments:** production (needs the deploy, §13); real S3 (needs the AWS setup, §5); a federated server.

## 12. APKs

The target name `artifacts/Orbit-Federation-v1-testing.apk` already exists, so it was kept. New files:

| | `artifacts/Orbit-Federation-v1-testing-2026-10-17-stabilization.apk` | `artifacts/Orbit-Federation-v1-testing-2026-10-17-stabilization-standard-id.apk` |
|---|---|---|
| Package | `com.griipbuddy.Orbit.test` ("Orbit Test"), installs beside the Play app | `com.griipbuddy.Orbit` ("Orbit"), same id as Play (can't update the Play-signed app) |
| Version | 0.1.3-beta (4), debug build (debuggable) | same |
| Signature | Android Debug key, v2; cert SHA-256 `b602dcff91126b9b23738b483df09a09fbbe2c087f5bbd159179a1aaf90fe587` | same |
| Size | 141,791,213 bytes | 141,791,201 bytes |
| SHA-256 | `05cf82c1e7e5c3042ac4c4708c66cdba95354082a90de9c9c79108666607820b` | `bb4398349195c40ad63d3743ccdbc201e258b5174cc204188beab465935b4572` |
| Source | `0fa2b71` + this round's uncommitted changes (the working tree in §14) | same |

- **Tested build.** The emulator ran the standard-id build of this same source; the side-by-side build differs only in
  its id and label.
- **Package id.** The brief asks for `com.griipbuddy.orbit`. Play's app is locked to **`com.griipbuddy.Orbit`** (package
  names are case-sensitive and permanent on Play), so the builds keep that. The code namespace is
  `com.griipbuddy.orbit`. A lowercase id would be a different app on Play; that's your decision.

## 13. Docker deployment requirements (not performed)

Read-only inspection of `/opt/griip-internal-messaging` on the VPS:

- **Services:** `api` runs image `griip-internal-messaging-api:0.1.0` (created about 38 h ago; started 2026-10-16
  16:56 UTC); `livekit` v1.13.10.
- **Database:** **only migration 001 is applied**.
- **Settings:** `.env` has `ATTACHMENT_MAX_BYTES=26214400`.
- **Proxy:** nginx `client_max_body_size 27m` on the API site.
- **Disk:** 56 GB free; the data volume is 70 MB.

What a deploy involves:

- **Affected service:** `api` only (LiveKit unchanged).
- **Migrations 002–009 will run.** All are additive (new tables and columns); nothing is deleted.
- **Back up first:** `deploy/scripts/backup.sh`.
- **Registration changes.** The new server defaults to `REGISTRATION_MODE=invite`, and production's `.env` doesn't set
  it, so new sign-ups would then need an invite. Set `REGISTRATION_MODE` explicitly to the intended value before
  deploying.
- **New optional settings** (`deploy/.env.example`): `HISTORY_*`, `ATTACHMENT_KEEP_DAYS`, `ATTACHMENT_USER_KEPT_BYTES`,
  `STORAGE_BACKEND`, `S3_*`. The defaults keep files on the disk, with history on.
- **Larger uploads:** for 500 MB attachments, raise `ATTACHMENT_MAX_BYTES` and nginx `client_max_body_size` (≥ 520m).
  History uses bodies up to 6 MiB, which fits 27m.
- **Procedure** (needs your approval): back up, set `.env`, then run `deploy/scripts/deploy.sh` (project-scoped
  `docker compose -p griip-internal-messaging`), check `/healthz` and `server-info` `features`, then run the profile
  photo and history checks from the phone.

## 14. Git

- **Branch** `internal-beta`; **HEAD** `0fa2b71` (= `origin/internal-beta`). Nothing committed or pushed this round.
- **Working tree:** 49 modified files and 23 new ones, none staged:
  - server: storage layer, history routes, migration 009, 3 test files and the fake S3;
  - Android: history crypto and sync, `ServerFeatures`, Room schema 7, on-device test, 5 new unit tests;
  - docs: `MESSAGE_HISTORY.md`, `WORKSPACES.md` and this report.
- **Not in Git:** no build outputs, keys or local data are in the working tree. APKs are ignored by `.gitignore`.
- **Commit identity** when you approve committing: `MD.NAHIAN AHMED <254077813+nahianahmed3832@users.noreply.github.com>`,
  with no AI attribution.
