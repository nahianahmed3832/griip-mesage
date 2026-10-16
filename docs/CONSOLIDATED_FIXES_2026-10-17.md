# Consolidated fixes, UX improvements and testing APK (2026-10-17)

Source state: built on branch `internal-beta` from `bf50b1e`; this round was committed as one commit per feature and
pushed to `internal-beta` on 2026-10-17 (see the end). **Nothing is deployed.** Production still runs the API image built on
2026-10-15 (checked read-only tonight).

## Issues

### 1. Invitations, QR and joining — fixed earlier (`2241912`, `0d5cf5d`)
Root causes and fixes are in `PHYSICAL_DEVICE_FIXES_2026-10-16.md` (#1, #2): the app's invite decoding works against
today's production server; QR from Gallery; precise refusal reasons and idempotent accept need the server deploy.
Tests re-run tonight: all green. Phone check: tomorrow (join with a typed code, a camera QR and a Gallery QR).

### 2. Photo/video viewer and saving — fixed earlier (`0d5cf5d`), hardened tonight
In-app viewer and explicit Save/Share were done on 2026-10-16. New: Save to Gallery refuses files whose bytes aren't a
real photo/video (a disguised file can no longer land in Pictures). Files: `ConversationViewModel.kt`,
`AttachmentPolicy.kt`. Tests: `AttachmentPolicyTest`, `MediaGalleryTest`. Phone check: save a received photo and video.

### 3. Duplicate workspaces (fixed earlier) and Delete Workspace (UI only, new)
Duplicates: idempotent create and same-name refusal (`2241912`, needs the server deploy); repair tooling is the admin
CLI (`admin.js org-duplicates`, `org-delete-empty`, dry run first). Nothing was merged or deleted automatically.
Delete Workspace: owner only, at the end of Workspace → Manage under "Danger zone" (not reachable from the switcher).
The sheet explains what deletion would remove, requires typing the exact name, and then says **"Deleting a workspace
isn't available yet. Nothing was deleted."** It calls no API. Files: `DeleteWorkspace.kt`, `WorkspaceScreen.kt`.
Tests: `DeleteWorkspaceRulesTest` (owner only, exact name, and a check that the file makes no server call).
Risk: none to data. Real deletion needs a server design (members, channels, files, federation) first.

### 4. Call history — new (`CALL_HISTORY.md`)
Root cause: call lines were written once from the call key and never updated, so every call read "Incoming/Outgoing
call" with no outcome or duration, and calls whose key never arrived left nothing. Fix: the server reports who
connected (`attended`) on ended calls and lists a conversation's calls; the app keeps one entry per call id, updated from
live events, the decline response and a sync on open, with outcomes from the server's record only (no invented
durations). Files: `server/src/routes/calls.ts`, `CallHistory.kt`, `MessageRepository.kt`, `CallManager.kt`,
`ConversationViewModel.kt`, `MessageBubble.kt`, `ApiModels.kt`, `Models.kt`. Tests: `call_history.test.ts` (4),
`CallHistoryTest` (5). Real stack (local server + LiveKit + two bots): a 15 s call reported 15 s for both people; an
unanswered call had no answer time and only the caller connected (the callee's app shows "Missed").
Docker: `api` rebuild needed for attendance and sync; without it 1:1 outcomes still work from live events.

### 5. Batch attachments, quality, safety — new (`ATTACHMENTS.md`)
Up to 10 per message with a preview (remove, add more, caption), one album message, background queue (2 uploads at a
time), progress, resumable retry; Standard/HD/Original done for real (photos re-encoded and stripped of metadata;
videos re-encoded with Media3 Transformer 1.9.0, a new dependency, +3.3 MB); on-phone checks of real type, risky files,
names and sizes; no server-scan claim anywhere. Files: `AttachmentPolicy.kt`, `media/MediaPrep.kt`,
`MessageRepository.kt`, `ConversationViewModel.kt`, `BatchComposer.kt`, `AlbumGrid.kt`, `MessageBubble.kt`,
`ConversationScreen.kt`, `MediaGallery.kt`, `libs.versions.toml`, `build.gradle.kts`. Tests: `AttachmentPolicyTest` (7).
Risks: video compression and HD photo memory use are **unverified on devices**; a message whose files weren't prepared
before the app was closed must be re-sent (it says so). No wire-format or encryption change. Docker: none.

### 6. Chat-list swipe actions — new (`CHAT_LIST_ACTIONS.md`)
Swipe right read/unread, swipe left archive with Undo, long-press menu with mute, accessibility actions, archived list
with restore. Archive is local and reversible (DB v5: nullable `archivedAt`). Files: `ChatsScreen.kt`,
`SwipeableChat.kt`, `ChatListRules.kt`, `UndoBar.kt`, `Entities.kt`, `Database.kt`, `Migrations.kt`,
`ConversationRepository.kt`. Tests: `ChatListRulesTest` (4), `MigrationsTest` (4, incl. v4 → v5). Risk: swipe vs.
vertical scroll feel on real phones. Docker: none.

### 7. Stuck swipe-to-reply — fixed (`CHAT_LIST_ACTIONS.md`)
Causes: attachments and voice ignored and never cleared the target; it was read after the tap; it was cleared on
failure. Now captured at the tap, sent with every message kind, cleared only after success, kept on failure (text
restored), replaced by a newer choice, dropped when its message is deleted, never carried across conversations. Files:
`ReplyTarget.kt`, `ConversationViewModel.kt`, `ConversationScreen.kt`, `MessageRepository.kt`. Tests: `ReplyTargetTest` (3).

### 8–12. Mentions, group-call tiles, call media, notifications, navigation — done earlier
`08c1a0a` (mentions, `MENTIONS.md`), `9dbaec8` (tiles, `GROUP_CALL_TILES.md`), `0d5cf5d` (echo, camera and screen
share quality, controls, notifications, transitions). Re-verified by the suites tonight; nothing changed. There is still
no push service: notifications need the background connection, so a force-stopped app gets none.

### 13. Branding
Application id stays **`com.griipbuddy.Orbit`** (Play-locked, case-sensitive); the code namespace is
`com.griipbuddy.orbit`. The old `com.griipbuddy.message` app is untouched; nothing migrates.

## Verification tonight

| Check | Result |
|---|---|
| Server suite (vitest) | 153/153 (23 files), incl. 4 new |
| Server typecheck | pass |
| Android unit tests | 210/210 (44 classes), incl. 23 new |
| Android lint (debug) | 0 errors, 56 warnings (mostly newer library versions available), 3 hints |
| `assembleRelease` (R8, unsigned) | pass, 65.8 MB |
| `assembleDebug`, standard and side-by-side | pass |
| Local stack with bots | call history: completed 15 s and unanswered call, as above |
| Emulator UI smoke test | **not run**: the emulator window was closed shortly after it started |
| Production (read-only) | `api` healthy on image `griip-internal-messaging-api:0.1.0` created 2026-10-15; `GET /v1/conversations/x/calls` → 404 (route not deployed); `livekit` v1.13.10 healthy. Nothing restarted |

## Testing APK

| | `artifacts/Orbit-Federation-v1-testing.apk` | `artifacts/Orbit-Federation-v1-testing-standard-id.apk` |
|---|---|---|
| Package | `com.griipbuddy.Orbit.test` ("Orbit Test"), installs beside the Play app | `com.griipbuddy.Orbit` ("Orbit"), for phones without the Play install |
| Version | 0.1.1-beta (versionCode 2) | 0.1.1-beta (versionCode 2) |
| Variant | debug (debuggable) | debug (debuggable) |
| Signature | Android Debug key, v2; cert SHA-256 `b602dcff…0fe587` | same |
| Size | 91,318,471 bytes | 91,318,451 bytes |
| SHA-256 | `b53c82143d2e05b9363799e1a893cf78c31da5c6d78b9fcafe5bf4be86e53447` | `5e3f47800a24eb7ae35eec401b2962e6fec35291e798f1795e554fdb3775b0ad` |

Both were built from the same sources before they were committed, with versionCode 2. The build file was changed to
versionCode 3 / 0.1.2-beta afterwards, outside this work, and is committed that way; a new build would carry 3. The
standard-id APK can't update the Play-installed
Orbit (different signature): use "Orbit Test" on the HONOR. Older APKs in `artifacts/` are untouched.

## Tomorrow on phones (deferred, not a blocker)

1. Swipe a chat right/left, Undo, Archived list, mute from long press; check the Chats badge.
2. Reply to a message with a photo, a file and a voice note; reply while offline (target stays, text restored).
3. Send 10 photos at Standard, HD and Original; a 1080p/4K video at Standard and HD; an HDR clip; a portrait clip; a
   PDF, an APK (warning) and a mix; remove items in the preview; leave the chat during upload; airplane mode → retry.
4. Receive the album on the second phone: grid, viewer swipe, save to gallery.
5. Calls: answered (check duration), declined, missed, busy, cancelled, group call with a late joiner. Against
   production, group outcomes show "ended" until the server is deployed.
6. Workspace → Manage as owner and as admin: Delete Workspace visible only to the owner, and it deletes nothing.

## Deployment needed (not performed)

Service **`api`** of Compose project `griip-internal-messaging`: call attendance and the per-conversation call list
(this round), plus the invite/workspace changes from `2241912` (migration 006) and everything since `c99c860`. No
database migration in this round's server change. LiveKit: nothing. Back up the data volume first.

## Files in this round

Modified: `android/app/build.gradle.kts` (version 0.1.2-beta / 3 from the owner, plus Media3), `gradle/libs.versions.toml`,
`CallManager.kt`, `ApiModels.kt`, `Database.kt`, `Entities.kt`, `Migrations.kt`, `Models.kt`,
`ConversationRepository.kt`, `MessageRepository.kt`, `MediaGallery.kt`, `ChatsScreen.kt`, `ConversationScreen.kt`,
`ConversationViewModel.kt`, `MessageBubble.kt`, `WorkspaceScreen.kt`, `MigrationsTest.kt`, `server/src/routes/calls.ts`.
New: schema `5.json`, `AttachmentPolicy.kt`, `CallHistory.kt`, `media/MediaPrep.kt`, `UndoBar.kt`, `ChatListRules.kt`,
`SwipeableChat.kt`, `AlbumGrid.kt`, `BatchComposer.kt`, `ReplyTarget.kt`, `DeleteWorkspace.kt`, five Android test files,
`server/test/call_history.test.ts`, and the docs `CALL_HISTORY.md`, `ATTACHMENTS.md`, `CHAT_LIST_ACTIONS.md` and this file.
