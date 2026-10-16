# Progress log

## Feature-first phase (from 2026-10-16)

The UI/UX redesign is postponed until the prioritised functionality is implemented and verified. These identifiers are unchanged:
- Android package `com.griipbuddy.message`;
- Compose project `griip-internal-messaging`.

Plan: `FEATURE_ROADMAP.md`. Status of every feature: `FEATURE_INVENTORY.md`. Debt register: `TECHNICAL_DEBT.md`.

| Step | Status | Evidence |
|---|---|---|
| Audit of the whole prototype (server, schema, Docker, transport, crypto, LiveKit, org system, Android, tests) | ✅ | `FEATURE_INVENTORY.md`. It found 6 BROKEN rows, many UNVERIFIED rows and no MOCKED features |
| Audit probes P1–P7 against the real API | ✅ | All 7 suspected defects confirmed. The probe file was deleted after recording (results in the inventory) |
| Baseline checks | ✅ | Server 32/32 tests, `tsc` clean; Android `compileDebugKotlin` and `assembleRelease` (R8, 64 MB unsigned) succeed. The release APK hasn't been run on a device. Production `/healthz` OK |
| **T1.1 Demotion to Guest revokes access** (P1) | ✅ server | See below |
| **T1.2 Enforce pinned identity keys** | ✅ (device check pending) | See below |
| **T1.3 Evict removed members from live calls** (P3) | ✅ server | See below. Not deployed |
| **T1.4 Never discard messages on local failure** | ✅ | See below |
| **T1.6 Registration gating, quotas, limits** (P5) | ✅ server + app | See below. Not deployed |
| **T1.7 Presence privacy** (P4) | ✅ server | See below. Not deployed |
| **T1.8 Activity feed scope** (P2) | ✅ server | See below. Not deployed |
| **T2.4 Android test harness** | ✅ foundation | See below. 25 JVM tests; device check blocked |
| **T2.3 Room migration policy** | ✅ | See below |
| **Task 1: Android video calls and screen sharing** | ✅ phone + bot | See below. Two-phone Android ↔ Android still needs testers |
| **T5.2 Call key authenticity** | ✅ code + JVM (device check pending) | See below |
| **Phase 14 collaboration check** | ✅ 2 fixes + wire-format tests (device check pending) | See below |
| **T4.2 In-app invite QR scanner** | ✅ code + JVM (device check pending) | See below |
| **T5.3 Late joiners get the call key** | ✅ code + JVM (device check pending) | See below |
| **T4.4 Notification history and actions** | ✅ code + JVM (device check pending) | See below |
| **Department editing** | ✅ server + app (not deployed; device check pending) | See below |
| **Phase 17 decentralisation readiness** | ✅ evaluated (nothing implemented; D-16 proposed) | See below |
| **E2EE badge needs decryption evidence (wrong-key fix)** | ✅ code + JVM + phone | See below |
| **Android UI/UX redesign** | ✅ code + JVM + phone screenshots (uncommitted) | See below |

### Android UI/UX redesign (2026-10-16, uncommitted, awaiting review)

- **Brief:** a polished, consistent messaging UI inspired by WhatsApp and Telegram usability, with no backend or crypto
  changes and no fake features.
- **Done:** a new design system (emerald on neutral, flat surfaces) and Chats / Calls / Workspace / Settings navigation. The
  Home dashboard was removed and its actions moved. New chat list, Calls tab, conversation, composer with an attachment
  sheet, merged Settings, and search scope and loading. Details and known gaps are in `UI_REDESIGN.md`.
- **Files:** `ui/` only (23 changed, 1 removed, 2 added, plus 2 test files) and docs.
- **Tests:** Android **118/118** (8 new), lint no errors, debug and release builds succeed. Phone screenshots in both themes
  and a live call check, the call showing "End-to-end encrypted" only after media decrypted.
- **Not committed** (the brief asks for approval first). Not deployed (app-only).

### E2EE badge no longer shows "encrypted" for a wrong-key peer (2026-10-16)

- **Defect (device test R3):** with a peer using the wrong media key, the call badge said "End-to-end encrypted", although
  neither side could decrypt the other.
- **Root cause, confirmed in source:**
  - LiveKit Android 2.29.0's `BaseKeyProvider()` passes `failureTolerance = -1` (`E2EEOptionsKt.defaultFailureTolerance`)
    to WebRTC's key provider.
  - In webrtc-sdk M144 `FrameCryptorTransformer::decryptFrame`, a frame that fails AES-GCM (after up to 16 ratchet tries) is
    dropped, and `DecryptionFailure()` returns false at tolerance below 0, so nothing is reported.
  - States are reported per cryptor and only when they change: `kOk` after a real decrypt, `kMissingKey` for an unknown key
    index; the initial `kNew` is never reported. A wrong-key peer's cryptor therefore stays silent.
  - The app's rule ("every state heard is OK") counted only the phone's own outgoing OK.
- **Fix (app only):**
  - New `MediaSecurityTracker`, one per call, fed by room events and the guard. It shows "End-to-end encrypted" only when every
    live track (remote subscribed or local published, not muted) has reported OK/KEY_RATCHETED and at least one is remote.
  - A failure on a current track shows "Encryption problem"; otherwise "Verifying encryption…", with a neutral shield instead
    of the check icon.
  - Removed tracks and participants are forgotten, their late callbacks ignored, and an ended call can't publish into the
    next one.
  - `failureTolerance`, keys, cryptors, the envelope and the crash guard are unchanged (D-17 explains why the tolerance stays).
- **Files:** `call/MediaSecurityTracker.kt` (new), `call/CallManager.kt` (tracker wiring replaces the old state map),
  `ui/screens/calls/CallOverlay.kt` (unverified wording), `MediaSecurityTrackerTest.kt` (new, 16).
- **Tests:**
  - `MediaSecurityTrackerTest` 16/16; 8 of 16 fail when the old rule is put back.
  - Android **110/110**, `lintDebug` no errors, `assembleDebug` and `assembleRelease` succeed.
- **Device (`TESTING.md` §5d):**
  - Valid calls are verified within 0.7 s of connecting, with encrypted media decoded both ways.
  - The missing-key warning still works.
  - **A wrong-key peer now shows "Verifying encryption…" for the whole call (2 of 2).**
  - A new call after a failed one resets.
  - 2 of 8 calls failed to join LiveKit (an existing issue).
  - The final build (badge computed inside the publishing lock, so two threads can't publish out of order) was re-checked on
    the valid path only. Its wrong-key re-run wasn't answered (inconclusive).
- **Also observed:** the frame-cryptor guard's protective path ran on the device for the first time. In both wrong-key calls,
  a late callback for the cleared remote mic track arrived during `disconnect()` and was dropped (`KNOWN_ISSUES.md` › Calls).
- **Remaining:**
  - A wrong key is shown as unverified, not as an explicit error (no SDK signal).
  - Stats-based "media not decoding" detection is a possible follow-up (D-17).
  - Not pushed. Not deployed (app-only).

### Frame-cryptor abort mitigation isolated and validated (2026-10-16)

- **Defect:** one native abort (SIGABRT) on WebRTC's `signaling_thread` at 02:50, in the E2EE frame-cryptor callback path.
  The investigation itself was done in a separate session; this record covers the review and the commit.
- **Suspected cause:** LiveKit Android 2.29.0's frame-cryptor observer runs `checkNotNull(publication.getTrack())` inside a
  JNI callback, and remote tracks are cleared while their cryptors can still report. Re-checked in the SDK bytecode. The abort
  has not been reproduced.
- **Mitigation:** `FrameCryptorGuard` replaces each cryptor's status observer with one that can't throw and ignores callbacks
  for cleared tracks. `CallManager` feeds the E2EE badge from it, with its state map synchronised and a cross-call check
  (`room === r`). `CallDiagnostics.log` writes debug-only summaries. The bot gained stress options.
- **Review findings, fixed before committing:**
  - `guard()` ran unprotected on the room-event coroutine. Copying LiveKit's unsynchronised `LinkedHashMap` could fail with
    more than `ConcurrentModificationException` (only that one was caught), and an escaping exception would have crashed the
    app. Now every exception is caught, and the SDK's own observer stays in place.
  - Exceptions inside the state callback were swallowed without a trace. They're now counted and logged (still never rethrown
    into WebRTC).
- **Verified:** `setObserver` touches only the status observer (WebRTC bytecode), so E2EE isn't weakened, and failure states
  still reach the badge (unit tests, and the phone logs from the stress runs).
- **Tests:** `FrameCryptorGuardTest` 8/8 (6 original + 2 for the hardening). Android **94/94**, `lintDebug` no errors,
  `assembleDebug` and `assembleRelease` succeed.
- **Not verified:**
  - The stress-test re-run is BLOCKED (no phone connected). The earlier stress runs passed with and without the guard, and
    counted 0 late callbacks, so they don't prove the guard removes the crash.
  - The hardened build hasn't been on a device.
  - The remaining races are listed in `KNOWN_ISSUES.md` › Calls.
- **Deployment:** app-only, nothing to deploy.
- **Next:** re-run the §5b stress test on the phone with this build. Key rotation (T5.4), busy/declined handling (T5.6) and
  call-state restore are separate follow-ups.

### Phase 17: decentralisation readiness evaluated (2026-10-16)

- **Deliverable:** `DECENTRALISATION_READINESS.md` covers the nine items in the brief, each with current evidence from the code,
  the gap, a recommendation and a readiness rating.
- **Findings:**
  - Instances are already independent: there is no central account service, the server URL is chosen at onboarding, and the media
    server is set per instance. Every test file starts its own instance.
  - There is no federation and no relay failover. *(Superseded 2026-10-16: federation v1 implemented, see `FEDERATION_PROTOCOL.md`.)*
  - The main security gap is that group membership is server-asserted, as `SECURITY.md` §4 already stated.
- **Recorded:** D-16 (proposed: independent instances, no federation or blockchain; verifiable membership through a separate
  protocol proposal), roadmap Tier 6 order, and `ARCHITECTURE.md` §8 link.
- **Nothing implemented; no protocol or production change.** Next dependency: owner confirmation of D-16. After that, the
  member-list change lines are a safe app-only first step.

### Department editing (Tier 7, 2026-10-16)

- **Gap:** departments could be created but not renamed, described, deleted or used to regroup teams, from the app or the API.
- **Server (not deployed):**
  - `PATCH /v1/departments/:id` is now audited and refused in an archived branch.
  - New `DELETE /v1/departments/:id`: the department's teams stay in the branch, ungrouped. A department grants no access (D-15),
    so nobody loses any.
  - `PATCH /v1/teams/:id` accepts `departmentId` (or null). The department must belong to the team's own branch; cross-branch
    teams have none.
  - Team edits inside an archived branch are refused (D-14).
  - The new audit actions are visible to admins only.
- **App:** a *Departments* list in the workspace's Manage tab, with an edit sheet (rename, description, delete with confirmation) for
  branch managers, and department chips on the team screen for people who manage the team. `GriipJson` drops nulls, so the
  requests that clear a field send an explicit JSON null.
- **Tests:** `departments.test.ts` (5: permissions, regrouping limits, delete, archived branch, audit visibility). 4 of 5 fail
  against the previous server code. `ScopeRequestsTest` (3). Server **87/87**, Android **92/92**.
- **NOT VERIFIED on a device**, and the server part is in `RELEASE_PLAN.md`.

### T4.4 Message notifications: history, Reply, Mark as read (2026-10-16)

- **Gap:** a notification showed only the latest message and had no actions. "Sign out & erase" left decrypted message text in the
  notification shade.
- **Added:**
  - **History:** each conversation's notification shows its last 6 notified messages (`NotificationHistory`, in memory only,
    cleared when the conversation is opened or read from the notification).
  - **Reply** (`RemoteInput`) and **Mark as read**, handled by a non-exported `MessageActionReceiver`. Both require the phone to be
    unlocked. *Reply* appears only where the user may post. The reply goes through the normal encrypted outbox, then the
    conversation is marked read. If it can't be queued, a "Reply not sent" notification replaces the original, so a reply is never
    lost silently.
  - **Erase** now removes message notifications first. Call notifications are left to the call code.
- **Tests:** `NotificationHistoryTest` (4). Android **89/89**; `lintDebug` reports no errors (dependency-age warnings only, recorded as D37).
- **NOT VERIFIED on a device:** the actions, the unlock requirement and the "Reply not sent" path need the phone.

### T5.3 Late joiners and new devices get the call key (2026-10-16)

- **Gap:** the media key was sent once, when the call started, to the devices that existed then. Someone joining a running group
  call from a device that missed it, or from a newly linked device, waited 10 s and was refused.
- **Fix (app only, no `CallManager` change):**
  - `MessageRepository.callKey`, which `CallManager.waitForKey` polls, now asks for a missing key: after 1.5 s without it, it sends
    a `call_key_request` control to the call's conversation, then repeats at most every 5 s (`CallKeyRequests`).
  - On receiving a request, **only the call's creator** answers: its device holds the key it created, checks the call is live with
    the server, and re-sends the same key as a `call_invite`. Answers are paced per call. Devices that already hold the key ignore
    the re-send (first key wins), so nothing rings again. T5.2 still applies, because the re-send comes from the creator.
- **Limits:** if none of the creator's devices is online, the joiner is still refused after 10 s. The key isn't rotated when people
  leave (T5.4). The test bot doesn't answer requests.
- **Tests:** `CallKeyRequestsTest` (4: grace period, pacing over a 10 s wait, per-call answer pacing, bounded state). The payload
  vectors include `call_key_request`. Android **85/85**.
- **NOT VERIFIED on a device:** needs two Android devices or a newly linked device joining a running call.

### T4.2 In-app invite QR scanner (2026-10-16)

- **Gap:** the app drew invite QR codes but couldn't scan them. People had to rely on the system camera opening `griip://invite/…`.
- **Added:** a *Scan invite QR code* button on the join screen opens a full-screen camera scanner (`ui/scan/InviteScanner.kt`).
  - Frames are decoded on the phone with zxing (already a dependency). Nothing is uploaded.
  - **Only Griip invite codes are accepted** (`InviteCodes.fromScan`): the `griip://invite/CODE` link, the shared invite text that
    contains it, or a bare `XXXX-XXXX-XXXX` code. Web links and other QR codes are reported and ignored, so a QR code can never
    make the app open a URL. `griip://invite` deep links now use the same parser.
  - The camera permission is asked through Android's own dialog (already declared for video calls). If it's refused or the camera
    can't start, the screen says so and the code can still be typed.
- **New dependency:** CameraX 1.4.2 (`camera-camera2`, `camera-lifecycle`, `camera-view`), Apache-2.0, from Google Maven. No Play services.
- **Tests:** `InviteScanTest` (5): accepted and refused inputs, decoding the QR code the invite screen draws, a padded camera row
  stride, and a frame with no code. Android **81/81**; `assembleDebug` succeeds.
- **NOT VERIFIED on a device:** the camera preview, the permission dialog and real-world scanning need the phone.

### Phase 14: collaboration features checked; two fixes (2026-10-16)

- **Review** of replies, threads, mentions, reactions, edits, deletions, pins and read receipts, from the composer to `apply`.
- **Fixed, thread injection:** the thread view selected every message naming the root, from any conversation. A member of one
  conversation could make messages appear in the thread of a message in another. `MessageDao.observeThread` now takes the
  conversation id. Room validates the query at compile time; there's no DAO test (D36).
- **Fixed, local-only actions:** in announcement channels (for non-managers) and archived scopes, the server refuses every envelope,
  controls included. The app still applied reactions, pins, edits and deletes locally, then silently dropped the refused control, so
  only this phone saw them. The repository now refuses them where `canPost` is false, and the message sheet hides them.
  Read receipts were already skipped there.
- **Wire format:** `griip-payload-v1-vectors.json` holds every payload type as the bot and reference client send it.
  `PayloadVectorsTest` (3 tests) decodes each and requires the same JSON back. Renaming `replyTo` in the vectors failed 2 of 3 tests.
  Unknown types and fields from newer clients still decode (they'd otherwise become "couldn't decrypt" placeholders).
- **Server contract test:** `messaging.test` now checks that members' reactions and pins are refused in announcement channels while
  the manager's pin is accepted. Server **82/82**, Android **76/76**.
- **Not done:** in-app QR scanning and an indexed local search (FTS) are separate tasks. **NOT VERIFIED on a device.**

### T5.2 Call keys are accepted only from the call's creator (2026-10-16)

- **Gap (D8):** the first `call_invite` key for a call id won, whoever sent it. The call id is broadcast in the ring event, so another
  member of the conversation could send a key for someone else's call first and split or break the call.
- **Fix:** `CallKeyVerifier` (app). Before storing a key, the app fetches the call (`GET /v1/calls/:id`) and requires:
  - the envelope's signed sender is the call's `createdBy`;
  - the key arrived in the call's own conversation.
  Anything else is ignored and logged. A 404 means the call doesn't exist for this user, so the key is dropped. Any other failure leaves
  the queue item unacknowledged, so it is checked again on redelivery; it is never accepted unchecked. The "first key wins" rule still applies.
- **No server change, no change to `CallManager`** (which has another session's uncommitted call work) or to `griip-envelope-v1`.
- **Tests:** `CallKeyVerifierTest` (5 JVM tests). Android **73/73**. The repository wiring itself isn't covered by a JVM test.
- **NOT VERIFIED on a device:** the planned check (a second bot races a fake key at the phone; the call must still use the creator's key)
  needs the phone. The test bot (`tools/griip-bot`) still accepts the first key it sees; it has uncommitted changes from another session, so I left it alone.

### Route audit and Tier 7 server hygiene (2026-10-16)

- **Route audit:** every server route was listed with its authorisation calls. No unauthenticated or unchecked resource route was found. Two deliberate openings are recorded in `SECURITY.md` §8: group creation without consent, and global device-key lookup.
- **Hygiene:**
  - Ended calls older than 90 days and audit rows older than 365 days are pruned by the cleanup job. Both periods are configurable and documented in `RETENTION.md`.
  - `PRAGMA secure_delete = ON`, with the WAL caveat documented.
  - `/healthz` no longer shows the online-device count.
  - The `lastTyping` map is bounded.
- **Tests:** `server/test/ops_hygiene.test.ts` has 3 tests: health output, the pragma, and pruning, which keeps recent and live records. `npm test` gives **81/81**.
- **Not deployed.**

### T3.2 Archived branches and teams are read-only; T3.5 departments decided (2026-10-16)

- **Gap:** archiving only hid a branch or team from lists. Its channels, memberships and invites kept working.
- **Decision D-14 (read-only, reversible):**
  - `Authz.scopeArchived` treats a team as archived if it or its branch is.
  - Its channels refuse posts (`canPost`) and calls.
  - Adding members, creating invites or creating channels in it returns 409, and pending invites into it stop working.
  - Existing members keep read access. Unarchiving restores everything.
- **Decision D-15:** departments only group teams (no membership, roles or channels). This already matches the code.
- **Tests:** `authz_edges.test.ts` › archived: a branch archive blocks the team channel's posts and calls, new team members, new invites and channels, and a pending invite. Unarchiving restores posting and adding. Fails without the change. `npm test` gives **78/78**.
- **Not deployed.** It changes behaviour for anything already archived (see `RELEASE_PLAN.md`).

### T3.1 Invites follow their creator's rights; T3.3 CLI disable closes sockets (2026-10-16)

- **T3.1 defect:** an invite created by a branch manager, team lead or owner stayed valid after that person was demoted or removed.
- **T3.1 change:** `Authz.canIssueInvite` holds the issuing rule: owners invite admins; owners and admins invite anyone below; managers and leads invite into what they manage. It's used at creation *and* by `findOrgInvite` whenever an invite is previewed, accepted or used to register. An invite whose creator lost the right behaves like an invalid code.
- **T3.3 defect:** `admin.js disable` runs in its own process. It deleted sessions, but a socket that only receives was never re-checked and stayed open.
- **T3.3 change:** the API's cleanup job (every 60 s) closes the sockets of connected users who are now disabled (`Hub.connectedUserIds`, `disconnectUser`).
- **Tests:** `server/test/authz_edges.test.ts` has 3 tests:
  - a manager's invite works, then stops after demotion;
  - an owner's admin invite stops after the owner becomes an admin, while another owner's member invite still works;
  - a CLI-disabled user's socket is closed at the next cleanup.

  All 3 fail without the fix. `npm test` gives **77/77**.
- **Not deployed.**

### T2.5 Background delivery: evaluation and restart after reboot or update (2026-10-16)

- **Gap:** a killed app receives nothing. The "stay connected" service also stayed off after a reboot or an app update until the user opened the app.
- **Evaluation:** `docs/BACKGROUND_DELIVERY.md` compares the persistent connection, self-hosted UnifiedPush (ntfy) and FCM on these points:
  - privacy;
  - phones without Google services (likely including the HONOR test phone);
  - OEM battery managers;
  - infrastructure and work.

  **Recommendation:** self-hosted UnifiedPush with payload-free wake-ups as the next mechanism. It needs **owner approval** for a new container, TLS route and nginx site. FCM would be optional, and only if the owner accepts Google seeing wake-up timing.
- **Change (app):**
  - `BackgroundDelivery` interface (with `PersistentConnection` as today's mechanism) and `BackgroundDelivery.sync`, used by `GriipApp`.
  - `BackgroundDeliveryRestartReceiver` handles `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` (not exported; `RECEIVE_BOOT_COMPLETED`).
  - The rule "only for a signed-in user with the setting on" is unit-tested (`BackgroundDeliveryTest`).
- **Not verified:** none of the device steps in the evaluation's verification plan have run. Background and killed-app delivery are **not** claimed.

### T2.6 Reliable encrypted attachment transfers (2026-10-16)

- **Defects:**
  - Files were read whole into memory on both sides.
  - Only `ApiException` was handled, so an I/O or memory error left a message stuck in SENDING.
  - Interrupted transfers restarted from zero, and a failed upload couldn't be retried properly: the retry re-sent a `pending` reference.
- **Telegram reference (behaviour only):** chunked transfers with part files that resume, and bounded retries. Adapted to Griip's existing format, which is unchanged: one AES-256-GCM blob plus the SHA-256 of the ciphertext inside the E2EE message. No Telegram code was used.
- **Change (app):**
  - Picking a file streams it into app storage, with the 25 MB cap enforced while copying.
  - `AttachmentCrypto.encryptFile` encrypts file to file in 64 KiB steps. The output is byte-identical to the one-shot format.
  - `ApiClient.uploadFile` streams from disk.
  - `TransferRetry` retries network errors, busy-server (`too_many_uploads`) and 5xx 3 times (2 s, 5 s, 15 s). It never retries size, quota or storage refusals, or lost access.
  - Every failure ends FAILED with a reason. *Retry* re-uploads the kept local copy.
  - At start-up, uploads cut off by the app being killed become *Upload interrupted · tap to retry*, and stray temporary files are deleted.
  - Downloads stream to `cacheDir/dl-<id>.part` and resume with a `Range` request. The SHA-256 is verified streaming, then `decryptFile` writes to a temp file that becomes the attachment only after the GCM tag verifies. A corrupt part file is discarded.
- **Change (server):** `GET /v1/attachments/:id` supports a single `bytes=start-[end]` range (206 or 416) after the membership check.
- **Tests:**
  - App, 11 new:
    - `AttachmentFileCryptoTest` (4): a 5 MB+ file round trip compatible with the one-shot format; a tampered file or wrong key gives no output and no temp file;
    - `TransferRetryTest` (3);
    - `ResumableDownloadTest` (4, MockWebServer): resume appends after the `Range` header; a server ignoring the range restarts; a fresh download sends no range; 404.
  - Server: `registration_quotas.test.ts` › resumable downloads (2): partial content is correct, membership is still enforced, and 416 is returned past the end.
  - Totals: Android **67/67**, server **74/74**.
- **Not done:**
  - Resumable *uploads* (needs an upload-session protocol).
  - Constant-memory GCM decryption (needs a chunked format version).
  - Device verification.

### T2.2 Realtime frames are never dropped (2026-10-16)

- **Defect:** `RealtimeClient` emitted frames into a `MutableSharedFlow` with a 256-frame buffer using `tryEmit`. A burst arriving while the app was busy, for example fetching keys, silently dropped frames, including `queue` frames.
- **Change:** `FrameInbox`, a bounded `Channel` (1,024) read by the single consumer:
  - it keeps order;
  - when full, the OkHttp reader thread waits, so backpressure reaches the relay over TCP and memory stays bounded;
  - if the consumer is stalled for 20 s, the socket is cancelled and reconnects. The relay redelivers unacknowledged items, so the worst case is a reconnect, never a lost message.
  - No other networking layer was added.
- **Tests:** `FrameInboxTest` has 3 tests:
  - a 1,000-frame burst from a reader thread with a deliberately slow consumer arrives complete and in order;
  - a stalled consumer makes `offer` fail after the timeout without losing accepted frames;
  - the buffer is bounded.

  Android **56/56**.

### T2.1 (app) Controls that arrive before their target, and T3.4 scope checks (2026-10-16)

- **Defect:** an edit, deletion, reaction or pin whose target message hadn't arrived yet was dropped, and acknowledged, so it was lost for good. A reaction could also be applied to a message in a *different* conversation (T3.4).
- **Change:**
  - `PendingControls`, with a Room-backed store (`pending_controls` table, **Room v4**, migration 3→4), holds such controls.
  - Each held control is unique by its own message id, capped at 200 per conversation (oldest dropped first), and expires with the relay's 7-day retention.
  - When the target message is stored, its held controls are replayed in server order.
  - Every control now resolves its target only within its own conversation. A target in another conversation is ignored, and a missing one is held.
  - Expired holds are purged at start-up.
- **Tests:** `PendingControlsTest` has 5 tests:
  - replay in original order;
  - a redelivered control is held once;
  - only the same conversation's controls replay;
  - the per-conversation bound;
  - expiry.

  `MigrationsTest` covers 1→2→3→4 with sample rows. Android **53/53**.

### T2.1 (server) Ordered, exactly-once delivery (2026-10-16)

- **Defect (probe P6):** with 105 items queued, a new message was pushed live before items 101–105 and then delivered again in the next backlog batch.
- **Telegram reference (behaviour only):** updates apply in sequence, and gaps are filled before newer updates. Applied as *no live push until the backlog has drained*. No code reused.
- **Root causes (two):**
  1. Live pushes ignored the backlog in progress. `Conn.flushing` was declared but never used.
  2. **Found by the new test:** both clients acknowledge every backlog frame with `backlog:true`, including the final empty one, and the server answered each with another flush. The connection looped through empty batches indefinitely, and each extra flush re-sent live items not yet acknowledged.
- **Change:**
  - A connection starts `flushing`, and `flushBacklog` clears it in the same synchronous step that sends the final batch.
  - `pushQueued` skips flushing sockets, whose items go out in the next batch, in queue order.
  - `backlog:true` acknowledgements flush only while still flushing.
- **Tests:** `server/test/ordering.test.ts`:
  - 105 queued plus one live message sent mid-flush: 106 unique ids, strictly in queue order, and the live message is last on the wire, with live pushes resuming after the drain;
  - a 60-message burst to a connected device with no duplicates.

  Both fail without the fix. Passed on 3 consecutive runs. `npm test` gives **72/72**.
- **Not deployed** (`RELEASE_PLAN.md`). The app's half of T2.1 (controls arriving before their target) and T2.2 follow.

### T1.4 No message lost during queue processing (2026-10-16)

- **Defect:** `processQueue` acknowledged an item after *any* exception: a database error, cancellation (for example the screen closing) or a bug in `apply`. The relay then deleted its only copy. The REST fallback (`pollQueueOnce`) had different rules again.
- **Telegram reference (behaviour only):** an update counts as handled once it's applied locally, and gaps are re-fetched. Applied here as *acknowledge only after a local commit*. No Telegram code was used.
- **Change:**
  - New `QueueProcessor`, shared by the socket and REST paths, acknowledges an item only when one of these holds:
    - it was applied;
    - it was quarantined (identity change, T1.2);
    - it was recorded as a permanent crypto or format failure (`EnvelopeException`, invalid decrypted payload, or a missing envelope field, which no longer throws an NPE);
    - it failed for an unexpected reason **5 times** and was **set aside**.
  - Set-aside items are stored locally with the reason (in the quarantine table, `reason` column, **Room v3**, migration 2→3), shown as a placeholder, and retried at start-up. A later successful retry replaces the placeholder.
  - `CancellationException` is rethrown. Network and key errors are retried without counting.
  - `handleItem` no longer "succeeds" without a session: that used to acknowledge items unprocessed.
  - One poisoned item never holds others back: the rest of the batch is still acknowledged.
- **Tests:** `QueueProcessorTest` has 8 tests:
  - in-order acknowledgement;
  - an injected database error leaves the item for redelivery while others proceed;
  - cancellation propagates and stops the batch;
  - crypto and format failures are recorded and acknowledged;
  - network errors don't count;
  - quarantine;
  - a poison item is set aside after the bound without blocking others;
  - success resets the count.

  `MigrationsTest` now checks 1→2→3 with sample rows, including a quarantine row. Android **48/48**.
- **Not verified on a device.**

### T1.10 Deleting a message deletes its decrypted files (2026-10-16)

- **Defect:** deleting a message cleared its attachment list, but the decrypted file in `filesDir/attachments` stayed until the whole device was erased (D18).
- **Change:**
  - New `AttachmentFiles` owns the attachment folder. Deleting your own message, and applying a sender's authorised remote `delete`, now removes that message's local files: downloaded or sent, and a pending upload's file.
  - **Found while doing this:** attachment ids and names come from other people's decrypted messages, and the old code built paths from the raw id. All names are now reduced to safe characters, and every path must resolve directly inside the folder. File names for real server ids and upload UUIDs are unchanged.
  - The app has no conversation-delete path, so there's nothing to clean up there yet.
- **Tests:** `AttachmentFilesTest` has 3 tests:
  - the right files are deleted and unrelated ones kept;
  - traversal attempts (`../griip.db`, absolute paths, `..`) touch nothing outside the folder;
  - existing names are preserved.

  Android unit tests: **40/40**.

### T1.9 "Erase this device" revokes it first (2026-10-16)

- **Defect:** erasing only logged out. The device stayed active on the account, so contacts kept encrypting to keys that no longer existed, and a failed logout was ignored silently.
- **Change:**
  - The erase confirmation now calls `DELETE /v1/devices/<own id>` first. The server revokes the device, drops its sessions and queued envelopes, and tells contacts to refresh keys.
  - Only on success does it wipe.
  - On failure, a second dialog says the server didn't confirm, explains what that means for contacts, and offers *Erase anyway* or *Cancel*. It never claims a revocation that didn't happen.
- **Tests:** `server/test/device_erase.test.ts`: after self-revocation, a contact's recipient list has no devices for that person, the queue for the device is empty, and both the token and login are refused. The UI flow itself isn't device-tested.

### T1.2 Pinned identities enforced (2026-10-16)

- **Defect:** certificates were verified against the identity key the server returned *now*. A relay that substituted a contact's key got future
  messages encrypted to its devices, and its forged messages were accepted. Only a DM banner warned (D1).
- **Owner decision:** block, don't just warn, until the user accepts the new key.
- **Change (Android):**
  - `KeyDirectory` pins on first contact, then trusts only devices certified by the pinned key. It now sits behind small `KeySource`/`PinStore` interfaces, so it's unit-testable.
  - A changed key gives `IdentityChangedException` when sending (the whole conversation pauses, because the server requires every member device), or `SenderKey.IdentityChanged` when receiving.
  - `MessageRepository`:
    - paused sends stay in the outbox with status **HELD** ("Paused: a security key changed") instead of FAILED;
    - envelopes from a changed sender are **quarantined**: new `quarantine` table, Room v2 with migration 1→2, acknowledged only after being stored locally;
    - `acceptIdentity(userId, reviewedKey)` pins the reviewed key, resumes held sends and opens quarantined envelopes.
  - **UI:** the conversation banner now covers DMs, groups and channels (members with a changed key, plus how many incoming messages are waiting), and held messages are marked. *I verified the new key* pins the key whose safety number is on screen.
  - A server that returns to the pinned key restores trust automatically.
- **Change (reference client and bot):**
  - `GriipClient` pins too. Sending to a changed identity throws `IdentityChangedError`, and receiving holds the envelope.
  - `acceptIdentity()` mirrors the app. Griip Bot inherits this (pins live for each bot process).
- **Tests:**
  - `KeyDirectoryTest`, 7 tests:
    - first-contact pin;
    - a substituted key pausing a 3-member conversation;
    - a forged sender held;
    - an uncertified device rejected;
    - accepting resumes;
    - a second swap after acceptance is caught;
    - reverting clears the warning.
  - `server/test/identity_pinning.test.ts`, 4 tests. The server's DB swaps Bob's identity and device keys for attacker keys with a valid certificate:
    - Alice posts nothing (no queue rows);
    - the attacker's message is held, not shown;
    - acceptance opens it and resumes sending;
    - a second swap is caught.
  - `MigrationsTest` failed with v2 and no step (2 failures), and passes with the 1→2 step.
  - Totals: Android **35/35**, server **69/69**, `assembleDebug` OK.
- **Not verified on a device:** the banner, held status and accept flow need the phone, where USB installs need the owner's approval. Not reviewed visually.

### T2.3 Local database migration protection (2026-10-16)

- **Gap:** Room `version = 1` with no migration strategy. The next schema change would crash or wipe history, which is the only copy (D13).
- **Change:**
  - `GriipDatabase.VERSION` constant.
  - `data/db/Migrations.kt`: explicit SQL steps, registered with `addMigrations`. Only a downgrade may recreate the database.
  - Policy recorded as **D-13**. Schema export was already on, and v1 is committed.
- **Tests:** `MigrationsTest`, using test-only `org.xerial:sqlite-jdbc`:
  - exported versions are contiguous, match `VERSION`, and each has a step;
  - each step applied to the previous schema with sample rows yields the next exported schema (columns, types, nullability, keys, indices), and the rows survive;
  - the v1 sample rows are valid.

  3/3 pass. With only v1 there's no step yet. The first one, and proof that a missing step is caught, come with T1.2.
- **Not verified:** SQLCipher opening an upgraded database on a device (needs an install on the phone).

### T2.4 Android unit-test harness (2026-10-16)

- **Gap:** no Android tests at all (D21), and Kotlin/Node crypto interop was proved only by live runs (D22).
- **Change:**
  - `testImplementation(kotlinx-coroutines-test)` (test-only).
  - `crypto/Bytes.kt` switched from `android.util.Base64` to `java.util.Base64`, which has existed since API 26 (minSdk). This is the only production change, and it lets the real crypto run on the JVM. Encoding is identical: standard alphabet, padded, no wrapping. Values already stored by the app decode unchanged.
  - **Shared vectors:** `server/test/interop_vectors.test.ts` generates (on request) and always verifies `android/app/src/test/resources/griip-envelope-v1-vectors.json`.
  - **New Kotlin tests:**
    - `EnvelopeInteropTest`: the same vectors, plus a Kotlin seal-and-open round trip;
    - `RecoveryCodeTest`;
    - `AttachmentCryptoTest`.
- **Results:** `./gradlew :app:testDebugUnitTest` gives **25/25** (11 new, 8 from the video fix, 6 from the E2EE investigation session's `FrameCryptorGuardTest`). `npm test` gives **65/65**. `assembleDebug` succeeds.
- **Not done:**
  - **Device check of the Base64 change is BLOCKED.** The phone refused the USB install because the on-screen install confirmation wasn't approved (`INSTALL_FAILED_ABORTED: User rejected permissions`).
  - Key-directory, outbox and queue tests come with T1.2 and T1.4, which add the small interfaces they need.

### T1.8 Activity feed respects branch isolation (2026-10-16)

- **Defect (probe P2):** a member of branch B saw, in the org activity feed, the names of branch A's teams, departments and public channels, plus `call.start` events for channels they couldn't open. The feed filtered by *action type* only.
- **Change:**
  - `activityVisibleTo` in `routes/orgs.ts` decides per event for non-admins:
    - teams: the same rule as the org structure view;
    - departments: access to their branch;
    - channels: `canViewChannel`, so private channels need membership;
    - calls: membership of the conversation;
    - branch creation, joins and leaves: always shown, as the structure view and member directory already show them;
    - anything else: hidden.
  - The target is looked up live, so older audit rows without scope data are filtered the same way, and a deleted target hides its row.
  - The scan window grew from 200 to 500 rows so filtered feeds still fill up.
  - Admins are unchanged.
- **Tests:** `server/test/activity_scope.test.ts` has 4 tests:
  - branch B learns nothing about branch A's team, department, channel or call, but still sees its own team, both branch names and joins;
  - the reverse holds;
  - admins see everything;
  - legacy rows and deleted targets are handled.

  3 fail without the fix. With it, `npm test` gives **64/64** and `tsc` is clean.
- **Not deployed** (`RELEASE_PLAN.md`). **Security:** closes P2 and D11 once deployed.

### T1.7 Presence privacy (2026-10-16)

- **Defect (probe P4):** any account could read anyone's online state and last-seen time. It only needed `/v1/presence`, `/v1/users/:id` or an exact-username search.
- **Change:**
  - `Authz.canSeePresence(viewer, target)` allows the person themself, anyone sharing a conversation, and non-guest members of a shared org.
  - `/v1/users/:id` and `/v1/users/search` return `online`/`lastSeenAt` as `null` otherwise.
  - `/v1/presence` leaves hidden users out, because released apps decode `online` as a non-null boolean.
  - Member lists already required a shared conversation or a non-guest org role, and the realtime push already went only to DM and group peers, so neither changed.
- **Tests:** `server/test/presence.test.ts` has 4 tests:
  - a stranger learns nothing, but the directory profile still works;
  - a DM peer, an org colleague and the person themself see presence;
  - a guest sees it only after sharing a channel;
  - realtime events reach the DM peer and not the stranger.

  Without the fix 2 fail (stranger, guest). With it, `npm test` gives **60/60** and `tsc` is clean.
- **Not deployed** (`RELEASE_PLAN.md`). **Security:** closes P4 and D10 once deployed.

### T1.6 Invite-only registration, upload quotas, container limits (2026-10-16)

- **Defect (probe P5):** anyone on the internet could register, DM anyone, upload 25 MB per request with no quota, and create orgs. Uploads were
  buffered whole in a 512 MB container. Compose limited memory only, on a VPS shared with production apps.
- **Owner decision:** invite-only registration for the internal beta.
- **Change (server):**
  - `REGISTRATION_MODE` defaults to `invite`. `POST /v1/auth/register` needs `inviteCode`, which is either:
    - a **registration invite** (new table `registration_invites`, migration 002; issued only by `admin.js reg-invite`; listed with `reg-invites`; revoked with `reg-invite-revoke`); or
    - a valid **org invite**, which also joins the person to that org, branch or team (shared `joinOrgByInvite`, extracted from `/v1/invites/accept`).
  - Lookup and use counting happen inside the registration transaction. Codes are stored as SHA-256 only.
  - Existing accounts are untouched, because login doesn't check invites.
  - **Attachments** stream from the request to a `.part` file through a byte counter (no buffering). Limits are checked before reading:
    - per-user bytes per 24 h and stored bytes (`429 quota_exceeded`);
    - per-user and global concurrent uploads and in-flight bytes (`429 too_many_uploads`);
    - a server-wide stored-byte cap (`507 storage_full`).

    Oversized or interrupted streams delete their `.part` file, and leftovers are removed at startup.
  - **Deploy:** Compose gets `cpus` (api 1.0, LiveKit 1.5) and `pids_limit` (256, 512). `.env.example` documents the new settings, and `verify.sh` warns below 5 GB free disk.
  - **Reference client and smoke test:** `register(inviteCode?)`. `prod-smoke.ts` reads `GRIIP_INVITE`. The test harness runs in `open` mode except for the gate's own suite.
- **Tests:** `server/test/registration_quotas.test.ts` has 12 tests:
  - no code or a bad code refused, with nothing created;
  - use limits, with loose typing accepted;
  - only a hash stored;
  - expired and revoked codes refused;
  - an org-invite registration that joins the org and team and consumes the use;
  - existing login unaffected;
  - no HTTP route mints registration invites;
  - a chunked upload of unknown length streamed and served back byte-exact;
  - oversized declared and streamed bodies refused, with no `.part` left;
  - the per-user concurrency limit;
  - the daily quota;
  - the global cap;
  - stale `.part` cleanup.
- **Results:** `npm test` gives **56/56** on three consecutive runs, and `tsc` is clean. Not run: `docker compose config` (Docker isn't installed on this workstation).
- **Not done:**
  - ~~Android onboarding needs an invitation-code field~~ **Done (same day):** an "Invitation code" field on *Create your identity*, sent as `inviteCode` (`RegisterReqTest` checks the wire format). On-device registration is not verified.
  - **Not deployed** (`RELEASE_PLAN.md`).
- **Security:** closes P5, D5 and D26 once deployed, and fixes the server half of D15. Residual: an org owner can invite people to register.

### T1.3 Evict removed members from live calls (2026-10-16)

- **Defect (probe P3):** removing someone from a conversation, or from the org, branch or team that gives them a channel, left them in its LiveKit room. They kept sending and receiving media.
- **Cause:** none of the membership-removal paths touched calls. `SECURITY.md` §7 claimed they did.
- **Change (`server/src`):**
  - `routes/calls.ts`:
    - `callAdmission` (member, own non-revoked device, account not disabled);
    - `evictFromCall`: removes non-admitted identities found in LiveKit *or* in `call_participants`, marks them left, sends `ended` with `endReason: "removed"` to the removed person's devices (only the revoked device when the person stays a member), then updates the call or ends it when empty;
    - `evictRemovedMember` and `evictUserDevicesFromCalls`.
  - **Called from:**
    - conversation member removal and leave;
    - org role change, including demotion to Guest;
    - org removal and leave;
    - branch and team removal (also covers team moves);
    - device revocation.
  - The LiveKit `participant_joined` webhook ejects a non-admitted identity at once. That covers someone rejoining with a pre-removal token, which stays valid for up to 10 minutes.
  - `jobs.ts` runs eviction over every live call each cleanup interval. This repairs removals missed while LiveKit was down and catches accounts disabled through the admin CLI (a separate process). It also gained an in-flight guard (D19).
  - `livekit.ts` lists identities, reports a removal as removed, absent or error, and bounds each LiveKit call to 5 s.
- **Tests:** `server/test/call_eviction.test.ts` has 8 tests against a recording LiveKit stub:
  - group removal, with a removal notice, 404 on join and connected, and others staying in the call;
  - team, branch, Guest-demotion and org-leave paths in one org;
  - an unrelated call left untouched;
  - nothing removed when membership doesn't shrink;
  - the last member's removal ending the call;
  - device revocation;
  - LiveKit unreachable, then repaired by cleanup;
  - a webhook rejoin;
  - a CLI-disabled account.
- **Results:** with the fix, `npm test` gives **44/44** and `tsc` is clean. With the `server/src` changes stashed, **7 of 8 fail**. The one that passes is the "nobody removed" negative test.
- **Not done:**
  - **Not deployed** (`RELEASE_PLAN.md`).
  - A production check with Griip Bot is part of the release smoke test.
  - The media key isn't rotated after a removal (T5.4). The removed person can't get SFU packets once ejected, but could decrypt any they had captured.
- **Security:** closes P3 and D3 once deployed. The Android app needs no change: it already tears down on an `ended` event for its current call.

### T1.1 Demotion to Guest (2026-10-16)

- **Defect (probe P1):** demoting an org member to Guest left all their branch, team and channel memberships in place, including private team channels. Other members' clients kept encrypting those channels' messages to the guest's devices.
- **Change:**
  - `Authz.demoteToGuest` (`server/src/authz.ts`) runs when the role changes *to* Guest. It deletes the person's branch and team memberships in that org and all of their channel memberships there.
  - DMs and groups aren't org-scoped and are kept.
  - The role-change handler (`server/src/routes/orgs.ts`) now sends `conv.updated` for every conversation the person was removed from, for any role change. Before, it re-validated silently. The Android client already reacts by refetching, getting 404 and marking the channel removed, so no app change was needed.
- **Unchanged:**
  - Existing guests' explicit grants.
  - Promotion back to Member auto-joins only scope-wide channels. Branch and team access must be granted again.
- **Tests:** a new `server/test/orgs.test.ts` › "demotion to guest" block covers four things:
  1. Removal from every channel kind, plus removal from branch, team and device lists, with `conv.updated` received on the person's socket.
  2. An envelope still addressed to the guest's device is refused with 409 `device_mismatch`.
  3. An explicit re-grant survives later re-validation.
  4. Promotion restores only scope-wide channels.
- **Results:** with the fix, `npm test` gives **36 / 36 pass** and `tsc --noEmit` is clean. With the two source changes temporarily stashed, the 4 new tests **fail**, confirming they detect the defect.
- **Not done:**
  - **Not deployed.** Production still runs the old behaviour until an approved redeploy (`deploy/scripts/deploy.sh`).
  - A demoted person who is in a live call stays in it. That's T1.3.
- **Phase 1 re-verification (2026-10-16, feature pass):**
  - The source and diff were re-read.
  - The test now also proves a non-org **group** membership survives demotion. Before, only the DM was checked.
  - `npm test` gives 36/36 and `tsc` is clean.
  - The release diff, deployment steps and rollback are in `RELEASE_PLAN.md`. **Still not deployed**: production needs explicit approval.

### Task 1: Android video calls and screen sharing (2026-10-16)

- **Reports:** testers couldn't see the other person's camera (A) or screen share (B).
- **Diagnosis:** a debug-only `CallDiagnostics` (`CallDiag` log tag) logs room and track events and, every 3 s, encode and
  decode counters per video track. It never logs keys, tokens or content. On the phone, frames were decoded with E2EE OK and
  the renderer was attached at full size, yet nothing was drawn. So the fault was after decode, in rendering.
- **Root cause (shared by A and B):** `VideoTile` released its renderer straight after creating it (an effect keyed on
  renderer state disposed with the live renderer). That hid every remote camera, every received screen share and the
  self-preview. Publishing, the SFU, signalling and E2EE were never at fault, and the server wasn't changed.
- **Changes:**
  - `VideoSinkBinding.kt` (new) owns the renderer's sink attachments and its single release.
  - `CallOverlay.kt`: `VideoTile` uses it, releases in `AndroidView(onRelease)`, and applies mirror and scaling on update.
    Fitted (screen-share) tiles letterbox instead of cropping. The group "You" tile shows the camera, not your own share.
  - `CallManager.kt` wires the diagnostics and logs when the system stops a screen capture.
  - `bot.ts`: `GRIIP_BOT_SCREEN=1` publishes a screen-share track mid-call.
- **Tests:** `VideoSinkBindingTest` 8/8. Phone acceptance against the bot is in `TESTING.md` §5a: camera both ways, screen share
  both ways, wrong-key screen-share E2EE (0 frames), 3-party grid, end-of-call cleanup, with no crashes.
- **Open:**
  - Two-phone Android ↔ Android verification.
  - The group "You" tile while sharing, on a device.
  - One post-fix SIGABRT in the E2EE frame-cryptor path from an earlier session (`KNOWN_ISSUES.md` › Calls).

---

## Internal beta build (2026-10-16, before the audit)

| Milestone | Status | Notes |
|---|---|---|
| 1. Environment & design foundation | ✅ | Toolchain verified. Reference analysed with sampled colours (`DESIGN_SYSTEM.md`). Compose design system written: tokens, glass components, hero art. Isolated Compose project. |
| 2. Identity & real messaging | ✅ | Ed25519 identity and device certificates, Keystore-sealed keys, challenge auth, `griip-envelope-v1` E2EE, outbox with retry, offline queue, 7-day TTL with cleanup, receipts. |
| 3. Organisation & branch system | ✅ | Orgs, branches, departments, teams (including cross-branch), public/private/announcement channels, invites (codes, QR, links), 6 roles enforced on the server, directory, member and role management, activity feed. |
| 4. Messaging experience | ✅ (mostly) | Replies, threads, reactions, mentions, edit and delete, pins, read receipts, typing, presence, unread counts, mute, drafts, local search, attachments. Emulator/UI screenshots of every screen are still pending. |
| 5. Voice & video | ✅ | LiveKit 1.13.10 deployed. Short-lived room tokens, voice and video calls, controls, ring, decline and missed states, reconnect states. **Verified on a physical phone** after fixing a native-init crash. |
| 6. Screen sharing & security | ✅ (partial) | MediaProjection consent flow, sharing indicator and Stop verified on the phone, with the remote screen track received. Media E2EE verified on production with right and wrong keys (Node peers) and phone ↔ bot. Wrong-key test for the screen track is still to do. |
| 7. Visual & functional regression | 🟡 | Physical-phone run found and fixed four defects: call crash, dark-mode contrast, stale draft, media-key overwrite. Full per-screen visual pass remains. |
| 8. Packaging & report | ✅ | APK in `artifacts/`, docs complete, deployment verified and isolated. |

## Timeline (2026-10-16, local)
- Inspected the environment and the design reference, then wrote the design system.
- Built the backend and its 32 integration tests, all green. A config env-parsing bug was caught by the tests and fixed.
- Built the Android app, which compiles cleanly against verified dependency APIs.
- **The VPS couldn't be reached at first:** the only saved SSH host was an unrelated IP with a changed host key, and I didn't bypass that.
  Once Hostinger access was available, I found the real VPS (72.60.107.88), and with your approval added this Mac's SSH key and inspected the server read-only.
- With your approval, deployed:
  - DNS records for `msg` and `rtc`, and firewall rules for 7881/tcp and 7882/udp.
  - A new nginx site and certificate.
  - The `griip-internal-messaging` Compose project.
- Production smoke test 8/8, and the E2EE media right-key/wrong-key test passed.
- APK defaults to production.
- **Physical phone testing (HONOR, Android 16):**
  - Messaging interop both ways, attachments, receipts and typing.
  - Calls crashed. The cause was found and fixed, then voice, video and screen share were verified.
