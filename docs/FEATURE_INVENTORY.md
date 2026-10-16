# Feature inventory (audit, 2026-10-16)

This is the starting point for the feature-first phase. Every row was checked against the code, not against earlier docs. Where an earlier document claims something this audit could not confirm, the row says so.

## How this audit was done

| Evidence | Result |
|---|---|
| Read all server source (`server/src`, `migrations/001_init.sql`) and the Android core: crypto, data, repositories, call, services, navigation and notifications | Done |
| Server test suite (`cd server && npm test`) | **32 / 32 pass** (vitest, 2.9 s) |
| Server type check (`tsc --noEmit`) | Pass |
| **Audit probes:** 7 throwaway vitest scenarios that exercise suspected defects through the real API (results below as P1–P7) | **All 7 confirmed a defect.** The probe file was deleted after recording |
| Android debug compile (`./gradlew :app:compileDebugKotlin`) | Pass |
| Android release (R8) build | See `PROGRESS.md` |
| Android automated tests | **Started 2026-10-16 (T2.4):** `./gradlew :app:testDebugUnitTest` runs 25 JVM tests (crypto interop with shared Node vectors, recovery code, attachment crypto, video sinks, frame-cryptor guard). No Room or UI tests yet |
| Production | `GET https://msg.griipbuddy.cloud/healthz` → `ok:true, livekit:true`; unauthenticated `/v1/conversations` → 401. No SSH and no write was made |
| Device, emulator, live calls | **Not run in this audit.** Device results quoted below come from `TESTING.md` (same day, earlier session) and are marked as such |

### Status key

- **WORKING:** implemented, with test or recorded run evidence.
- **PARTIALLY WORKING:** works, but with a limitation or defect noted.
- **MOCKED:** simulated rather than connected to real functionality.
- **BROKEN:** implemented, but fails or contradicts its documented guarantee.
- **MISSING:** not implemented.
- **UNVERIFIED:** code exists, but there isn't enough evidence for its function or security.

**Nothing in the codebase is MOCKED.** The search for stubs, fakes, no-op handlers and placeholder data found none. Every screen action calls a real repository or API, and the app uses about 60 server endpoints.

### Confirmed defects (probe results)

| Probe | What was observed through the API |
|---|---|
| **P1** *(fixed by T1.1 after the audit, not yet deployed)* | An org member demoted to **Guest** kept every channel membership: their private team channel, branch channels, and their branch and team rows. Other members' clients keep encrypting private-channel messages to the guest's devices. |
| **P2** | A member of branch B saw, in `GET /v1/orgs/:id/activity`, the name of branch A's team ("Confidential Layoff Planning") and branch A's public channel. `GET /v1/orgs/:id` correctly hides that team. |
| **P3** | Removing a member from a conversation during a call did **not** call LiveKit `removeParticipant`. The member stays a call participant. `SECURITY.md` §7 says the server removes them. |
| **P4** | An unrelated account found a user by exact username and read their **online** state and **last-seen** time (`/v1/presence`, `/v1/users/:id`). |
| **P5** | A brand-new account with no invite and no organisation could DM any user, upload a 1 MiB attachment and create an org. Registration is open on the internet-facing relay. |
| **P6** | After reconnecting with 105 queued items, a new live message was pushed **before** queue items 101–105 and delivered **twice** (live push, then again in the next backlog batch). |
| **P7** | A message sent from a second device, which was then revoked before the recipient came online, could not be verified (`unknown or uncertified sender device`). The Android client acks such items as undecryptable, so a legitimate message is **permanently lost**. |

---

## 1. Identity, authentication, device security

| Feature | Status | Evidence | Code, API, tables, tests |
|---|---|---|---|
| Identity creation: Ed25519 identity and device keys generated on the phone; X25519 HPKE key; device certificate | WORKING | `auth.test` (register, cert must be signed by identity); phone registration recorded in `TESTING.md` §5 | `crypto/IdentityStore.kt` `LocalKeys`, `SessionRepository.register`; `POST /v1/auth/challenge`, `/v1/auth/register`; `users`, `devices` |
| Challenge–response login; automatic re-auth on 401; nonce single-use and 2-minute expiry | WORKING | `auth.test` (replay rejected, wrong key rejected, username alone grants nothing) | `routes/auth.ts`, `ApiClient.request`, `SessionRepository.reauth`; `auth_challenges`, `sessions` (hashed tokens) |
| Private keys and session sealed by a non-exportable Android Keystore AES-GCM key; backups disabled | UNVERIFIED | The code is correct on reading (`KeystoreSealer`, `allowBackup=false`, `data_extraction_rules.xml`). No test checks it, and no check was made that the key is hardware-backed | `crypto/KeystoreSealer.kt`, `IdentityStore.kt`, `AndroidManifest.xml` |
| Local database encrypted with SQLCipher (passphrase sealed by the Keystore) | UNVERIFIED | `SupportOpenHelperFactory(passphrase)` is wired. Nobody has checked that the `.db` file on a device is unreadable without the key | `data/db/Database.kt` |
| Recovery onto a new phone with the recovery key | PARTIALLY WORKING | Server path tested (`auth.test`). The Android `restore()` flow was never run on a device. The restored session uses the username as display name | `SessionRepository.restore`, `RecoveryCode`; `POST /v1/auth/add-device` |
| Linking a new device (pairing code, HPKE-sealed identity seed) | PARTIALLY WORKING | Server relay tested (`auth.test`). The app-to-app flow was never run: only one phone was available | `SessionRepository.startPairing/pollPairing/approvePairing`; `/v1/pairing*`; `pairing_requests` |
| Device revocation: sessions and queue dropped, socket closed, peers told to refresh keys | PARTIALLY WORKING | `auth.test`. **Defect P7:** messages the revoked device sent before revocation become unverifiable and are lost | `DELETE /v1/devices/:id`, `Directory.kt` `senderSigningKey`, `deviceDirectory()` excludes revoked devices |
| **TOFU identity pinning** (trust a contact's first identity key; detect later changes) | **WORKING (JVM + reference-client tests; device check pending)**. Was BROKEN | T1.2 (2026-10-16): certificates are checked against the *pinned* key. A change pauses sending (HELD) and quarantines incoming messages in every conversation type, until the user accepts the reviewed key. `KeyDirectoryTest` (7), `identity_pinning.test.ts` (4, malicious-relay key swap), `MigrationsTest` (v2 quarantine table) | `Directory.kt`, `MessageRepository`, `ConversationScreen`, `griipClient.ts` |
| Safety numbers and "mark as verified" | PARTIALLY WORKING | The number is computed from both identity keys and matches the server's derivation. "Verified" is a flag on the user, cleared on key change. It isn't bound to a key | `ProfileScreens.kt` `safetyNumber`, `Fingerprint` |
| Sign out & erase | **WORKING in code (device check pending)**. Was PARTIALLY WORKING | T1.9 (2026-10-16): the app first revokes this device on the server (`DELETE /v1/devices/<own id>`): sessions and queue are dropped and contacts stop encrypting to it. Only then does it wipe keys, the database and attachments. If the server doesn't confirm, a second dialog explains the risk and offers *Erase anyway*. `device_erase.test.ts` | `ProfileScreens` erase flow, `routes/auth.ts` |
| Registration gating (invite-only or allowlist for an internal tool) | **WORKING (server and app, not deployed)** | T1.6 (2026-10-16): `REGISTRATION_MODE=invite` default; registration or org invite required; operator CLI `reg-invite`. `registration_quotas.test.ts`. The app sends an invitation code from the onboarding screen (2026-10-16). Production is still open (P5) until deployed | `POST /v1/auth/register`, `invites.ts`, `admin.ts` |
| Session expiry enforced on open sockets | PARTIALLY WORKING | The server re-checks the session on each client frame. A socket that only receives (with OkHttp protocol pings) isn't re-checked until the client sends an app frame. Revocation and admin disable close sockets through `hub.disconnectDevice` (revoke only; admin `disable` runs in another process and can't) | `ws.ts`, `admin.ts` |

## 2. Direct and group messages

| Feature | Status | Evidence | Code, API, tables, tests |
|---|---|---|---|
| DM create, encrypt (`griip-envelope-v1`), send, receive | WORKING | `messaging.test` (two-way, relay holds only ciphertext, DB and WAL scanned); Kotlin↔Node interop on a phone (`TESTING.md` §5) | `EnvelopeCrypto.kt`, `MessageRepository`, `POST /v1/messages`; `delivery_queue`, `submitted_messages` |
| Server checks the signature and that the recipient device set equals members' active devices (409 `device_mismatch` → client refreshes) | WORKING | `messaging.test` | `routes/messages.ts` |
| Group conversations (≤ 50, group admins) | PARTIALLY WORKING | Delivery only to members is tested. The Android group-creation and member UI was never run on a device | `POST /v1/conversations/group`, `PUT/DELETE /v1/conversations/:id/members/:userId`, `NewChatScreen`, `ConversationInfoScreen` |
| Org channels (public, private, announcement; only managers post in announcement channels) | WORKING | `orgs.test`, `messaging.test` | `POST /v1/orgs/:id/channels`, `authz.canPost` |
| Multi-device fan-out (sender's other devices get a copy) | WORKING | `messaging.test` | `routes/messages.ts` |
| Idempotent resubmission | WORKING | `messaging.test` | `submitted_messages` |
| Delivery order and de-duplication | **WORKING (server not deployed; app JVM-tested)** | T2.1, server: no live push until the backlog has drained, and no empty-backlog loop (`ordering.test.ts`). App: edits, deletions, reactions and pins whose target hasn't arrived are held in `pending_controls` (Room v4) and replayed in order when it arrives. Held controls are unique by id, capped at 200 per conversation and expire after 7 days. Targets must be in the same conversation (T3.4). `PendingControlsTest` (5) | `hub.ts`, `ws.ts`, `PendingControls.kt`, `MessageRepository.apply` |
| Client never loses a message on local errors | **WORKING (JVM-tested)**. Was BROKEN | T1.4 (2026-10-16): `QueueProcessor` acknowledges only:
- committed items;
- quarantined items (identity change);
- recorded permanent crypto or format failures;
- items set aside locally after 5 unexpected failures (kept with a visible placeholder and retried at start-up).

Cancellation propagates. Database errors and bugs aren't acknowledged; network errors aren't counted. The REST fallback uses the same path. `QueueProcessorTest` (8), `MigrationsTest` (v3) | `QueueProcessor.kt`, `MessageRepository` |
| Realtime frames never dropped | **WORKING (JVM-tested)**. Was PARTIALLY WORKING | T2.2 (2026-10-16): `FrameInbox` is a bounded, ordered channel (1,024 frames). When full, the socket's reader waits, which is TCP backpressure. A consumer stalled for 20 s triggers a reconnect, and unacknowledged items are redelivered, so nothing is silently dropped. `FrameInboxTest`: a 1,000-frame burst with a slow consumer arrives complete and in order | `FrameInbox.kt`, `RealtimeClient` |

## 3. Offline delivery, acknowledgements, expiration

| Feature | Status | Evidence | Code, API, tables, tests |
|---|---|---|---|
| Offline queue, delivered in order after reconnect (single backlog) | WORKING | `messaging.test`; production smoke test 8/8 (`TESTING.md` §3) | `delivery_queue`, `hub.flushBacklog`, `GET /v1/queue` fallback |
| Ack deletes queue rows | WORKING | `messaging.test`; `admin.js stats` showed 0 after the smoke test | `acknowledge()` |
| Delivery receipts (server-generated, DMs and groups) | WORKING | `messaging.test` | `routes/messages.ts` `acknowledge` |
| Read receipts (encrypted control, DMs and groups ≤ 20, user can switch off) | PARTIALLY WORKING | Phone↔bot recorded in `TESTING.md`. Channels never show readers (by design) | `markConversationRead`, `Payload(type="read")` |
| 7-day TTL with a hard cap, cleanup every 60 s, reads filter expiry | WORKING | `messaging.test` (1 s TTL, cap) | `config.ts`, `jobs.ts` |
| Data older than 7 days for an offline device | MISSING (by design) | Lost permanently. There's no history fetch (`RETENTION.md`) | — |

## 4. Local persistence and reconnection

| Feature | Status | Evidence | Code |
|---|---|---|---|
| Local history in Room + SQLCipher; newest 60 shown, paging in steps of 60 | WORKING (basic) / UNVERIFIED (paging) | Used on the phone. Paging was never exercised | `Database.kt`, `ConversationViewModel.loadMore` |
| Outbox: SENDING → SENT/FAILED, 409 refresh-and-retry, backoff 2 s…120 s, waits while offline, manual retry | UNVERIFIED | Basic send worked on the phone. The retry and backoff paths have no test | `MessageRepository.outboxLoop/sendOne` |
| WebSocket reconnect (exponential backoff, jitter, network callback, re-auth on 4401) | UNVERIFIED | No test. Recorded only as "works on phone" | `RealtimeClient.kt` |
| Drafts | WORKING | A stale-draft bug was fixed on device | `ConversationViewModel.typingChanged`, `ConversationDao.setDraft` |
| Room schema migrations | **WORKING (policy and test in place, 2026-10-16, T2.3)** | `Migrations.kt` (explicit steps) registered in the builder, no destructive upgrade fallback. `MigrationsTest` checks contiguity and schema equality after each step, with data preserved. The first real step arrives with T1.2. Device check that SQLCipher still opens after an upgrade: pending | `Database.kt`, `Migrations.kt`, `app/schemas` |

## 5. Organisation, branches, departments, teams, channels, invitations, authorisation

| Feature | Status | Evidence | Code, API, tables, tests |
|---|---|---|---|
| Orgs, branches, departments, teams (branch and cross-branch) | WORKING | `orgs.test` | `routes/orgs.ts`; `orgs`, `branches`, `departments`, `teams` |
| Departments | PARTIALLY WORKING | Departments only group teams. There is no department membership or department-scoped channel, and no UI to edit a department (`PATCH /v1/departments/:id` is unused by the app) | `departments` |
| Branch and team archiving | PARTIALLY WORKING | `archived_at` hides branches and teams from lists, but changes **no access**: channels and memberships stay | `PATCH /v1/branches/:id`, `/v1/teams/:id` |
| Six roles enforced on the server (owner, admin, branch manager, team lead, member, guest) | WORKING (P1 fixed after the audit, server not yet redeployed) | `orgs.test` covers creation, invites, channel and member management, last-owner protection. **P1 (demotion to Guest removed nothing) was fixed by T1.1 on 2026-10-16**; 4 new tests in `orgs.test` › demotion to guest | `authz.ts` `demoteToGuest`, `PATCH /v1/orgs/:id/members/:userId` |
| Cross-branch isolation | **WORKING (server, not deployed)** | Structure, members and channels are filtered (`orgs.test`, P2 structure check). The activity-feed leak (P2) is fixed by T1.8 on 2026-10-16 (`activity_scope.test.ts`); production still leaks until deployed | `GET /v1/orgs/:id/activity` |
| Removal from org, branch or team revokes channel memberships immediately | WORKING | `orgs.test` | `authz.revalidateChannelMemberships` |
| Moving members between teams (needs rights on both) | WORKING | `orgs.test` | `POST /v1/teams/:id/members/:userId/move` |
| Invitations: hashed 60-bit codes, preview, max uses, expiry, revoke, scoped to a branch or team, role ≤ inviter | WORKING | `orgs.test` | `invitations` |
| Invites outlive the creator's rights | UNVERIFIED (likely defect) | An invite made by a branch manager stays valid after that person is demoted or removed. The code never re-checks the creator | `findInvite()` |
| Invite by QR (image), code, `griip://invite/…` link | PARTIALLY WORKING | QR image and link generation exist. **No in-app QR scanner** | `InvitesScreen`, `MainActivity.handleIntent` |
| Directory and people search | WORKING | Search by name within shared orgs, plus exact username globally | `GET /v1/users/search`, `/v1/orgs/:id/members` |
| Presence privacy | **WORKING (server, not deployed)**. Was BROKEN (P4) | T1.7 (2026-10-16): online and last-seen are visible only to the person, people sharing a conversation, and non-guest members of a shared org. Strangers get `null` from `/v1/users/:id` and search, and are omitted from `/v1/presence`. Realtime pushes are unchanged (DM and group peers only). `presence.test.ts` (2 of 4 fail without the fix) | `authz.canSeePresence`, `routes/users.ts` |
| Activity feed | **WORKING (server, not deployed)**. Was BROKEN (P2) | T1.8: non-admins see an event only if they can see its target in the org structure or the conversation itself. Targets are resolved live, so old rows are covered and deleted targets hidden. 4 tests, 3 fail without the fix | `routes/orgs.ts` `activityVisibleTo` |

## 6. Message interactions, attachments, search

| Feature | Status | Evidence | Code |
|---|---|---|---|
| Replies, threads, mentions, reactions, edit, delete, pins (all encrypted control messages) | UNVERIFIED | Implemented end to end in code. There's no automated test, and `TESTING.md` records no device run of these features. The sender check exists for edit and delete. Reaction and pin targets aren't checked against the conversation | `MessageRepository.react/edit/delete/setPinned/apply` |
| Edit and delete are cooperative | PARTIALLY WORKING (by design) | A modified client can ignore them (`SECURITY.md` §4) | — |
| Delete removes the local decrypted file | **WORKING (JVM-tested)**. Was BROKEN | T1.10 (2026-10-16): deleting your own message, or receiving the sender's delete, removes its decrypted files (downloaded, sent, and pending upload). Paths built from message data can't leave `filesDir/attachments`. There's no conversation-delete path in the app yet. `AttachmentFilesTest` | `AttachmentFiles.kt`, `MessageRepository.delete` / `apply("delete")` |
| Encrypted attachments (AES-GCM, key in the message, SHA-256 integrity, membership-checked download, ≤ 25 MB, 7-day expiry) | WORKING | `calls_attachments.test`; phone→bot decrypt and integrity check recorded in `TESTING.md` §5 | `AttachmentCrypto`, `routes/attachments.ts`; `attachments` |
| Attachment failure handling | **WORKING (JVM- and server-tested; device check pending)**. Was PARTIALLY WORKING | T2.6 (2026-10-16): files are streamed throughout (pick to storage, encrypt file to file, upload from disk; download to a resumable `.part` file, hash, decrypt to a temp file then rename). Transient errors are retried 3 times; size, quota and storage refusals fail with the server's message. Any failure leaves FAILED with a reason, never stuck SENDING. Retry re-uploads the kept local copy, and uploads interrupted by the app being killed become retryable at start-up. GCM decryption still buffers one file (≤ 25 MB) inside the cipher | `AttachmentCrypto.encryptFile/decryptFile`, `ApiClient.uploadFile/downloadTo`, `TransferRetry`, server `GET /v1/attachments/:id` Range |
| Message search | PARTIALLY WORKING | Local SQL `LIKE` over decrypted text (max 100 results), with no index. Attachment names aren't searched. There's no server search (by design) | `MessageDao.search/searchIn`, `SearchScreen` |
| Typing indicators | WORKING | `messaging.test`; phone↔bot | `ws.ts` |

## 7. Push notifications and Android background behaviour

| Feature | Status | Evidence | Code |
|---|---|---|---|
| Push wake-up (FCM or UnifiedPush) | MISSING | Decision D-08. A killed app gets nothing until reopened | — |
| "Stay connected" foreground service (`remoteMessaging`) | PARTIALLY WORKING | Implemented and on by default. It isn't restarted after reboot (no `BOOT_COMPLETED`), and OEM battery managers may kill it. Long-running behaviour was never measured | `RelayConnectionService.kt`, `GriipApp` |
| Message notifications (decrypted on device, private on the lock screen) | UNVERIFIED | Code is reasonable. No device evidence is recorded. Each conversation shows only its latest message | `notify/Channels.kt` `MessageNotifier` |
| Incoming call while backgrounded or locked (full-screen intent) | UNVERIFIED | `TESTING.md` §5: "NOT VERIFIED". Android 14+ needs the user to grant full-screen intents for non-default apps | `IncomingCallNotifier`, `MainActivity` show-when-locked |

## 8. Voice and video calling

| Feature | Status | Evidence | Code, API, tables |
|---|---|---|---|
| 1:1 voice and video over self-hosted LiveKit (room-scoped 10-minute token, ring, accept, decline, missed, end) | WORKING | `calls_attachments.test`; phone↔bot voice and video recorded in `TESTING.md` §5 | `routes/calls.ts`, `livekit.ts`, `CallManager.kt`; `calls`, `call_participants` |
| Group calls (≤ 12, one live call per conversation) | PARTIALLY WORKING | Server tests. Phone plus two bot devices rendered a grid. Never run with 3+ phones, and capacity was never benchmarked | — |
| Network reachability | PARTIALLY WORKING | Media needs UDP 7882 or TCP 7881 to the VPS. **There is no TURN server**, so networks that allow only port 443 fail | `deploy/livekit` |
| Removal from a conversation evicts the person from the live call | **WORKING (server, not deployed)**. Was BROKEN (P3) | T1.3 (2026-10-16): every removal path, device revocation, the LiveKit webhook and the 60 s reconciliation evict; `call_eviction.test.ts` (8 tests, recording LiveKit stub; 7 fail without the fix). Production still runs the old code | `routes/calls.ts` `evictFromCall`, `jobs.ts` |
| Busy handling | PARTIALLY WORKING | A busy callee silently ignores the ring. The caller rings until it's marked missed | `CallManager.onRing` |
| Late joiners and newly linked devices can join a running call | MISSING | The media key is sent once, at call start, to the devices that existed then. Later joiners wait 10 s and are refused | `waitForKey`, `sendCallKey` |
| Call records pruned | MISSING | `calls` and `call_participants` grow forever | `jobs.ts` |

## 9. Screen sharing and the MediaProjection lifecycle

| Feature | Status | Evidence | Code |
|---|---|---|---|
| Consent dialog → publish → "You're sharing" banner → Stop | UNVERIFIED | It worked on the phone **before** the SIGSEGV teardown fix. `KNOWN_ISSUES.md` says the re-test on the fixed build is still pending | `CallManager.startScreenShare`, LiveKit `ScreenCaptureService`, `CallOverlay` |
| System-initiated stop (projection revoked) updates the UI | UNVERIFIED | An `onStop` callback refreshes participants. Never tested | — |
| Screen-share track is E2EE | UNVERIFIED | The participant reported encryption as on, but the wrong-key test for the screen track was interrupted (`TESTING.md` §5) | — |

## 10. Media end-to-end encryption and key distribution

| Feature | Status | Evidence | Code |
|---|---|---|---|
| Per-call 256-bit key, sent inside an encrypted `call_invite` control message, never through the server API | WORKING | Production SFU right-key / wrong-key test; phone vs wrong-key bot: 0 frames decoded (`TESTING.md` §4–5) | `MessageRepository.sendCallKey`, `CallManager.connect` |
| Refuse to join when E2EE is on and no key arrives (no downgrade) | UNVERIFIED | Implemented (`waitForKey` throws after 10 s). No test | `CallManager.waitForKey` |
| Key authenticity | PARTIALLY WORKING | "First key wins", but any conversation member can send a `call_invite` for someone else's call id. The id is broadcast in the ring event, so a member can race the caller and split the call. The key isn't checked against the call's creator | `MessageRepository.apply("call_invite")` |
| Settings consistency | PARTIALLY WORKING | The caller always uses E2EE. A callee who turned "media E2EE" off joins without a key and can't decode | `startCall`, `waitForKey` |
| Key rotation when participants join or leave | MISSING | Decision D-06 gap | — |

## 11. Docker deployment, resource isolation, retention cleanup

| Feature | Status | Evidence | Code |
|---|---|---|---|
| Project-scoped Compose project `griip-internal-messaging`, its own volume and network, API and signalling on loopback only | WORKING | `deploy/compose.yaml`; health endpoint up today; isolation check recorded in `TESTING.md` §3 | `deploy/` |
| Resource limits on a shared 2-vCPU VPS | **WORKING in code, not deployed** (T1.6) | Compose adds `cpus` and `pids_limit`. Uploads stream to disk with per-user and global byte quotas and concurrency limits (tested). Compose syntax isn't validated locally (no Docker here): `deploy.sh` runs `config -q` first | `compose.yaml`, `routes/attachments.ts` |
| Retention job (queue, idempotency records, challenges, sessions, pairing requests, attachments) | WORKING | `messaging.test`, `calls_attachments.test` | `jobs.ts` |
| Pruning of call records and the audit log | MISSING | — | — |
| Physical erasure of deleted envelopes | UNVERIFIED | Without `PRAGMA secure_delete`, deleted ciphertext stays in SQLite free pages and the WAL until overwritten. It's ciphertext, but "deleted" is not literally guaranteed | `db.ts` |
| Backups | MISSING (documented) | `RETENTION.md` | — |
| Operator CLI (stats, invite, disable, cleanup) | WORKING | `admin.ts`. `disable` can't close live sockets (separate process) | `admin.ts` |

## 12. UI navigation and testability blockers

No route is unreachable, and no button is a no-op: every tab and route in `AppRoot.kt` is wired. These things limit feature testing:

| Blocker | Effect |
|---|---|
| No Android automated tests at all | No regression safety for the crypto pipeline, outbox, key directory or call state machine. Every Android row above relies on code reading or one recorded manual run |
| One physical phone; the emulator can't boot on this Mac | Pairing, multi-device, 3+ party calls and background behaviour can't be checked locally. Griip Bot covers only the second-participant role |
| Notification "Answer" opens the app instead of accepting | An extra tap. Doesn't block testing |
| `GET /v1/calls/active` is unused | After an app restart, a running group call is found only through a conversation's `activeCall` |
| No visual pass of Chats, Conversation, Workspace, Team, Members, Invites, Profile, Settings (`TESTING.md` §6) | Only Welcome, Home, Conversation and Call were reviewed |

## 13. Decentralisation readiness

| Item | Status | Notes |
|---|---|---|
| Clients own their keys; the relay sees only ciphertext | WORKING | Groundwork is in place |
| Server URL per session | PARTIALLY WORKING | Stored per session; set before sign-up |
| Group membership verified by clients (signed group state) | MISSING | The server asserts membership (`SECURITY.md` §4) |
| Federation and identity documents | MISSING | — |
| Horizontal scaling (shared pub/sub) | MISSING | In-memory hub (`ARCHITECTURE.md` §7) |

## Existing features that should not be rebuilt

These work, have evidence, and should only be extended:
- the server authorisation core (`authz.ts`) and org model;
- challenge auth and device certificates;
- the `griip-envelope-v1` pipeline behind `MessageCrypto`;
- the server queue, receipts and retention job;
- encrypted attachments;
- LiveKit token and call signalling;
- the media-key-in-encrypted-message design;
- the Compose design system and screens;
- the deployment scripts;
- the Node reference client (`test/support/griipClient.ts`);
- Griip Bot.

Fix the defects above inside these modules.
