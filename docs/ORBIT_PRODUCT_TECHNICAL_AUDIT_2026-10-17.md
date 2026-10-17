# Orbit product and technical audit (2026-10-17)

**Status: read-only audit of commit `0c504c5` (`internal-beta`), with production running API 0.2.0.** Nothing in the
code, configuration, production or data was changed; this report is the only new file. It supersedes the status
columns of `FEATURE_INVENTORY.md` and `TECHNICAL_DEBT.md` (both 2026-10-16), which predate the 0.2.0 deployment.

## How the audit was done

| Evidence | Result |
|---|---|
| Server source read | `server/src` (8,497 lines of TypeScript, 114 HTTP routes and one WebSocket), migrations 001–010 |
| Android source read | `android/app/src/main` (126 Kotlin files, 22,441 lines), manifest, resources, Gradle catalogue |
| Deployment and docs read | `deploy/` (Compose, scripts, CloudFormation), 57 docs |
| Server tests, rerun for this audit | **210 passed**, 9 skipped (the live S3 tests run only on request), typecheck pass. The working tree was unchanged afterwards |
| Android tests (not rerun) | **268/268** JVM tests, lint 0 errors / 79 warnings, debug and R8 release builds pass. Run earlier today on `570ad81`; the Android sources are unchanged since `f590b21` |
| On-device tests | `ConversationOpenTest` (instrumented, emulator) 10/10 on 2026-10-17 |
| Production checks (earlier today) | Storage acceptance 16/16, then 10/10 after a restart; bot messaging and E2EE calls; the app on an emulator against production (docs/ORBIT_AWS_PRODUCTION_2026-10-17.md) |
| Physical phone | **Never connected during these sessions.** No result in this report is a physical-device result |
| Secret scan | No credentials in tracked files or Git history. The only key-shaped string is AWS's published example key in a test. `.env` files, `local.properties` and the signing key are untracked |

**Status key**

- **IMPLEMENTED:** code plus a test or a recorded run supports it. Where it hasn't run on a real device, the notes say
  so.
- **PARTIALLY IMPLEMENTED:** works with a stated limitation, or only part of the flow has evidence.
- **MISSING:** no code.
- **NEEDS INVESTIGATION:** code exists, but nothing shows it works, or it depends on something unverified.

There are no placeholder or mock implementations. Every screen action calls a real repository and API, which matches
the 2026-10-16 audit's finding. The gaps below are unverified integrations, not stubs.

## 1. Technology stack

| Layer | Technology |
|---|---|
| Android app | Kotlin 2.2.21, AGP 8.13.2, Jetpack Compose (BOM 2025.10.01), Navigation 2.9.3, Lifecycle 2.9.4, Room 2.8.3 with SQLCipher 4.12, OkHttp 4.12, kotlinx.serialization 1.9, coroutines 1.10, Bouncy Castle 1.79, LiveKit Android 2.29.0, Media3 1.9, CameraX 1.4.2 and ZXing 3.5.3, WorkManager 2.10.5, ML Kit translation 17.0.3 |
| Server | Node 22, TypeScript 5.9, Fastify 5.12.7 (`@fastify/websocket`, `@fastify/rate-limit`), better-sqlite3 13 (SQLite in WAL mode), zod 3.25, livekit-server-sdk 2.19.1, AWS SDK v3 3.1144.0 (S3) |
| Tests | vitest 4.1 with a reference protocol client (`test/support/griipClient.ts`), a fake S3 that checks real SigV4 signatures, JUnit JVM tests on Android, one Espresso/Compose instrumented test |
| Calls | LiveKit SFU v1.13.10 (self-hosted), frame encryption with keys sent in E2EE messages |
| Hosting | Hostinger VPS: 2 vCPU, 8 GB, shared with about 17 other Compose projects. Host nginx with Let's Encrypt; Compose project `griip-internal-messaging` (`api`, `livekit`); Amazon S3 in Singapore for files |
| Tooling | `tools/griip-bot` (a headless peer for chat and calls), `tools/device/tap.sh`, deploy scripts, CloudFormation templates |

## 2. Architecture and data flow

```
Android app ──HTTPS/WSS──▶ host nginx ──▶ api (Fastify, one process) ──▶ SQLite (WAL) on a Docker volume
     │                                         │  └─ in-memory hub: sockets, presence, live pushes
     │                                         ├──▶ LiveKit (RoomService, webhooks)
     │                                         └──▶ S3: presigns uploads and downloads, deletes, lists
     ├──HTTPS (presigned URLs only)──▶ S3 bucket griipbuddy-orbit-media-sg (ciphertext only)
     └──UDP 7882 / TCP 7881──▶ LiveKit SFU (E2EE media frames)
```

- **App structure:** MVVM with repositories and manual DI (`AppContainer`), one container per server profile. Layers
  are `crypto/`, `data/api` (REST and WebSocket), `data/db` (Room v7 with SQLCipher), `data/repo`, `call/`,
  `service/`, `notify/` and `ui/`.
- **Sending a message:** Room (SENDING) → outbox loop → fetch member devices → `griip-envelope-v1` (HPKE X25519,
  HKDF-SHA256, AES-256-GCM, Ed25519 signature) → `POST /v1/messages`. The server checks the signature and that the
  recipient set equals the members' active devices, then queues one copy per device for up to 7 days.
- **Receiving:** WebSocket `queue` frames go through a bounded `FrameInbox`. `QueueProcessor` verifies, decrypts,
  applies and then acknowledges; nothing is acknowledged on local errors. Live pushes wait until the backlog has
  drained.
- **Files:** each file is encrypted on the phone with its own key and SHA-256. With S3, the app uploads straight to the
  bucket with a presigned PUT bound to the size, hash and no-overwrite condition. Downloads are a 302 to a 5-minute
  link after a membership check. Files stored before the switch are still read from disk (`STORAGE_READ_FALLBACK`).
- **History:** devices upload their messages encrypted under a key derived (HKDF) from the account's identity seed.
  A new or restored device merges them back. The server stores ciphertext under opaque tags.
- **Calls:** the server creates the call and issues a 10-minute, room-scoped LiveKit token. The media key travels in
  an encrypted `call_invite` message and is accepted only from the call's creator. Webhooks plus a 60-second
  reconciliation keep call state right.
- **Trust boundary:**
  - **The server sees:** who talks to whom and when, sizes, membership, profiles and presence.
  - **The server can't read:** messages, files or call media.
  - **The server decides:** group membership. Clients pin identity keys on first use.
- **Federation:** `griip-fed/1` server-to-server delivery and a core-node registry exist with tests
  (`federation_e2e`, `federation_registry`). They're **off in production** (`FEDERATION_DOMAIN` empty).

**Dependencies between parts:**

- Messaging depends on device keys and the directory, so device revocation affects message verification (finding S7).
- Calls depend on messaging for key delivery, on LiveKit reachability (no TURN) and on push for ringing a closed app.
- History restore depends on the recovery key, because the history key is derived from the identity seed.
- File keep-for-history depends on server history being enabled.
- All realtime features depend on one process and its in-memory hub.

## 3. Feature inventory

### 3.1 Accounts and devices

| Feature | Status | Evidence and notes |
|---|---|---|
| Registration with keys made on the phone (Ed25519 identity and device keys, X25519 encryption key, certificate) | IMPLEMENTED | `auth.test`, `registration_quotas.test`; production accounts; emulator onboarding today |
| Registration gating (invite or open) | IMPLEMENTED | `REGISTRATION_MODE`, `reg-invite` CLI, org invite codes. **Production is `open` by the owner's choice** |
| Challenge login, 7-day sessions, 32-byte tokens stored hashed, re-auth on 401 | IMPLEMENTED | `auth.test`, `routes/auth.ts`, `ApiClient` |
| Profiles with per-field privacy, username change, profile photos | IMPLEMENTED | `profile_privacy.test`; photo upload on the emulator. Photos aren't end-to-end encrypted (by design) |
| Several accounts or servers on one phone | IMPLEMENTED | `ServerProfilesTest`. Only the active profile is connected; the others don't receive in the background |
| Linking a second device with a pairing code | PARTIALLY IMPLEMENTED | Server relay tested (`auth.test`; 10-character code, 10-minute TTL, rate-limited). Never run app-to-app on two devices |
| Recovery onto a new phone with the recovery key | PARTIALLY IMPLEMENTED | Server path tested. A full restore including history was verified on the emulator against a local server, not on a phone |
| Device list and revocation | PARTIALLY IMPLEMENTED | Works (`auth.test`). **Messages a device sent before it was revoked can no longer be verified and are lost** (P7; the planned fix T1.5 isn't done: `routes/users.ts` returns only active devices) |
| Sign out and erase (revoke on the server first) | IMPLEMENTED | `device_erase.test`. Not run on a phone |
| Self-service account deletion | MISSING | No `DELETE /v1/users/me`. Only the admin CLI's `disable` exists |
| Blocking | IMPLEMENTED | `/v1/blocks`, covered in `profile_privacy.test` |
| At-rest protection: Keystore-sealed keys, SQLCipher database, backups excluded | NEEDS INVESTIGATION | Correct on reading (`KeystoreSealer`, `allowBackup=false`, extraction rules). Hardware backing and an unreadable database file were never checked. Test APKs are **debuggable** debug builds (finding S3) |

### 3.2 Organisations, teams, memberships and permissions

| Feature | Status | Evidence and notes |
|---|---|---|
| Orgs, branches, departments, teams; branch and cross-branch teams | IMPLEMENTED | `orgs.test`, `departments.test`, `invites_workspaces.test` |
| Six roles enforced on the server (owner, admin, branch manager, team lead, member, guest) | IMPLEMENTED | `authz.ts` is the single authorisation module; `orgs.test`, `authz_edges.test`. Demotion to guest now revokes access (T1.1) |
| Invitations: hashed codes, expiry, maximum uses, scope, role capped at the inviter's; QR and link | IMPLEMENTED | `orgs.test`, `authz_edges.test` (invites die with the creator's rights), `InviteScanTest`. The camera scanner hasn't run on a phone |
| Archiving branches and teams (read-only) | IMPLEMENTED | `authz_edges.test` |
| Activity feed limited to what the viewer may see | IMPLEMENTED | `activity_scope.test` |
| Real workspace deletion (owner only, immediate) | IMPLEMENTED | `org_delete.test`; deployed in 0.2.0 |
| Org management in the app (roles, invites, channels, archive, delete) | IMPLEMENTED | `OrgScreens`, `ManageSheets`, `DeleteWorkspace`; rule tests (`ScopeRequestsTest`, `DeleteWorkspaceRulesTest`, `WorkspaceListTest`) |
| Web admin console, SSO, directory sync (SCIM) | MISSING | Administration is the in-app screens plus `admin.js` over SSH |

### 3.3 Direct and group messaging

| Feature | Status | Evidence and notes |
|---|---|---|
| End-to-end encrypted DMs (`griip-envelope-v1`) | IMPLEMENTED | `messaging.test` (the relay holds only ciphertext), shared Kotlin and Node vectors (`interop_vectors.test`, `EnvelopeInteropTest`), production bots today |
| Groups of up to 50 with admins | IMPLEMENTED | Server tests (`MAX_GROUP = 50`). The app's group creation and member screens have no recorded device run |
| Copies to the sender's other devices, idempotent resubmission | IMPLEMENTED | `messaging.test` |
| Offline queue (7-day hard cap), ordered delivery, de-duplication, acknowledgement only after commit | IMPLEMENTED | `ordering.test`, `QueueProcessorTest`, `FrameInboxTest`, `PendingControlsTest` |
| Delivery receipts (server) and read receipts (encrypted; DMs and groups up to 20; can be turned off) | IMPLEMENTED | `messaging.test` |
| Typing indicators (none above 200 members) | IMPLEMENTED | `messaging.test`, `ws.ts` |
| Replies, reactions, edits, deletes, pins, forwarding, @mentions, @everyone with a policy | IMPLEMENTED | `PayloadVectorsTest`, `mentions.test`, `MentionRulesTest`, `ReplyTargetTest`. Edits and deletes are cooperative: a modified client can ignore them |
| Contact cards, one-time location, link previews (fetched by the sender), safer links, animated emoji | IMPLEMENTED | `RichRulesTest` (including the link rules). Not run on a phone |
| On-device translation (ML Kit) | NEEDS INVESTIGATION | The model download goes through Google. China-market HONOR phones without Google Play services may not get it |
| Encrypted server history and restore | IMPLEMENTED | `history_keep.test`, `HistorySyncTest`, `HistoryCryptoTest`. Restore verified on the emulator. Live in production since 0.2.0 |
| Encrypted local backups (`ORBITBK1`) | IMPLEMENTED | `BackupCryptoTest`. Not run on a phone |
| Drafts; chat list swipe actions, archive, mute | IMPLEMENTED | `ChatSwipeRulesTest`, `ChatListRulesTest`; emulator recordings |
| Disappearing messages, polls, scheduled messages | MISSING | — |

### 3.4 Channels, topics and threads

| Feature | Status | Evidence and notes |
|---|---|---|
| Org, branch and team channels (public, private, announcement); auto-join; browse and join | IMPLEMENTED | `orgs.test`, `messaging.test`, `authz.canPost`, `GET /v1/orgs/:id/channels`, `POST /v1/conversations/:id/join` |
| Channel topic (a description field) | IMPLEMENTED | `conversations.topic`, editable with `PATCH /v1/conversations/:id` |
| Topic streams inside a channel (Zulip-style) | MISSING | — |
| Threads (replies grouped under a root, thread view, reply counts) | PARTIALLY IMPLEMENTED | Entirely on the client: `threadRootId` is inside the encrypted payload, with `MessageDao.observeThread` and the conversation screen's thread mode. No thread notifications or following; voice messages can't be sent in threads; not run on a phone |
| Large channels | PARTIALLY IMPLEMENTED | Works, but every message is stored once per recipient device (up to 1,000 recipients and 128 KiB each; debt D14). A key change by one member pauses sending to the channel until accepted |

### 3.5 File sharing

| Feature | Status | Evidence and notes |
|---|---|---|
| Encrypted attachments (AES-256-GCM with a per-file key, SHA-256, membership-checked download) | IMPLEMENTED | `calls_attachments.test`, `AttachmentFileCryptoTest`; production acceptance |
| Chunked format above 25 MB, up to 500 MB | IMPLEMENTED | `ChunkedAttachmentCryptoTest`. **Production allows 25 MB** (`ATTACHMENT_MAX_BYTES`) |
| S3 storage: presigned direct upload, multipart with resume, cleanup, 5-minute download links, disk fallback | IMPLEMENTED | `direct_uploads.test`, `storage_s3.test`, live tests 9/9, production acceptance 16/16 and 10/10, the app on the emulator against production |
| Photos, videos, albums, media viewer, quality choice; voice messages | IMPLEMENTED | `MediaGalleryTest`, `VoiceNotesTest`; voice played from production S3 on the emulator |
| Files kept for history (365 days after the last keep) | IMPLEMENTED | `history_keep.test` |
| Server-side file scanning (antivirus) | MISSING | Impossible on ciphertext by design. Only the client can check files |

### 3.6 Voice and video calls

| Feature | Status | Evidence and notes |
|---|---|---|
| 1:1 voice and video with end-to-end media encryption | IMPLEMENTED | `calls_attachments.test`; bot-to-bot E2EE voice and video calls on production today; phone runs on 2026-10-16 |
| Badge that shows "encrypted" only with decryption evidence; key accepted only from the creator | IMPLEMENTED | `MediaSecurityTrackerTest`, `CallKeyVerifierTest`; the badge was verified on a phone on 2026-10-16 |
| Ring recovery, alerted and busy states, reconnecting after a dropped connection, call history | IMPLEMENTED | `call_reachability.test`, `call_reconnect.test`, `call_history.test`, `CallHistoryTest` |
| Removing a member evicts them from a live call | IMPLEMENTED | `call_eviction.test` (8) |
| Group calls (up to 12) | PARTIALLY IMPLEMENTED | Server tests and multi-bot runs. Never run with 3 or more phones; capacity never measured |
| Screen sharing | NEEDS INVESTIGATION | Worked before a crash fix; the re-test is still pending (`KNOWN_ISSUES.md`) |
| Picture-in-picture, verification code | IMPLEMENTED | `CallVerificationTest`. Not run on a phone |
| Incoming call while the app is killed; full-screen call on the lock screen | MISSING / NEEDS INVESTIGATION | No push (§3.7). The full-screen intent path is unverified, and Android 14+ needs the user to grant it |
| TURN or relaying over port 443 | MISSING | No TURN in the LiveKit config. Networks that allow only 443 can't carry calls |
| Media key rotation when people join or leave | MISSING | Eviction limits the impact |
| Native WebRTC abort (SIGABRT on `signaling_thread`) | NEEDS INVESTIGATION | Mitigated, never reproduced on demand, not proven fixed |

### 3.7 Notifications and search

| Feature | Status | Evidence and notes |
|---|---|---|
| Push wake-up (FCM or UnifiedPush) | **MISSING** | No push code. `BackgroundDelivery` is an interface prepared for it, and `BACKGROUND_DELIVERY.md` evaluates the options |
| "Stay connected" foreground service, restarted after reboot or update | PARTIALLY IMPLEMENTED | `BackgroundDeliveryTest`. OEM battery managers (MagicOS) can kill it; a force-stop stops delivery until the app is reopened |
| Message notifications, private on the lock screen, with Reply and Mark as read | PARTIALLY IMPLEMENTED | `NotificationHistoryTest`. Not run on a phone |
| Notification health (warns when notifications are off) | IMPLEMENTED | `NotificationHealthTest`; shown on the emulator today |
| Per-chat mute, Orbit tones | IMPLEMENTED | In code and settings |
| Message search | PARTIALLY IMPLEMENTED | Local SQL `LIKE` on decrypted text, at most 100 results, no full-text index, file names not searched. No server search (by design) |
| People search; phone-number lookup with a privacy setting | IMPLEMENTED | `/v1/users/search` (shared orgs, plus exact username), `/v1/users/by-phone` (rate-limited, privacy-checked) |

### 3.8 Privacy and end-to-end encryption

| Feature | Status | Evidence and notes |
|---|---|---|
| Message encryption with shared test vectors between Kotlin and Node | IMPLEMENTED | `interop_vectors.test`, `EnvelopeInteropTest`, `PayloadVectorsTest` |
| Identity pinning on first use; key-change quarantine | IMPLEMENTED | `KeyDirectoryTest`, `identity_pinning.test` (malicious-relay key swap) |
| Safety numbers and "verified" | PARTIALLY IMPLEMENTED | The number matches the server's. "Verified" is a flag on the person, not bound to a key |
| Forward secrecy and post-compromise security | MISSING | Long-term device keys wrap every message key (`SECURITY.md` §4, D-04) |
| Signed group membership (clients verify membership) | MISSING | The server asserts membership, so a malicious server could add a member |
| Presence and last-seen limited to people with a relationship | IMPLEMENTED | `presence.test` |
| Call media encryption | IMPLEMENTED | Wrong-key tests on production's SFU and on a phone (2026-10-16) |
| Screenshot protection, app lock (PIN or biometric) | MISSING | No `FLAG_SECURE` or `BiometricPrompt` |
| Federation (`griip-fed/1`, core registry) | IMPLEMENTED, **off in production** | `federation_e2e.test`, `federation_registry.test`. DMs, receipts, attachments, voice and 1:1 calls only |

### 3.9 Server retention and data cleanup

| Feature | Status | Evidence and notes |
|---|---|---|
| Retention job every 60 s: queue (7-day cap), idempotency records, challenges, sessions, pairing, attachments, abandoned uploads, S3 deletes with retry, history tombstones, call records (90 days), audit log (365 days) | IMPLEMENTED | `jobs.ts`; `messaging.test`, `calls_attachments.test`, `history_keep.test`, `direct_uploads.test`, `storage_s3.test` |
| `secure_delete` on SQLite | IMPLEMENTED | `db.ts` (debt D17 is closed) |
| Workspace deletion removes its data at once | IMPLEMENTED | `org_delete.test` |
| S3 lifecycle: never expires a current object; old versions kept 30 days; incomplete multipart uploads aborted after 1 day | IMPLEMENTED | Verified on the production bucket today. A deleted file stays recoverable for 30 days, which deletion promises should mention |
| Account deletion and its data | MISSING | No route (§3.1) |
| Backups | PARTIALLY IMPLEMENTED | `admin.js backup`, `backup.sh` and `restore.sh` exist, and a backup was taken before the 0.2.0 deploy. **No schedule, no off-site copy, and backups aren't encrypted** (root-only on the same VPS) |

### 3.10 Administration, testing and deployment

| Feature | Status | Evidence and notes |
|---|---|---|
| Operator CLI (`stats`, `users`, `disable`/`enable`, invites, `cleanup`, `backup`, `verify-backup`, `storage-check`, `storage-orphans`, federation) | IMPLEMENTED | `admin.ts`, `backup.test`, `ops_hygiene.test` |
| Server automated tests | IMPLEMENTED | 210 tests in 29 files, plus live S3 and production-acceptance scripts that run on request |
| Android automated tests | PARTIALLY IMPLEMENTED | 268 JVM tests (rules, crypto, queue, migrations). No Room DAO tests (debt D36), no Compose UI or screenshot tests, one instrumented test |
| Continuous integration | MISSING | No `.github` workflows. Tests run only by hand |
| Deployment: Compose, preflight, validation, verify, backup and restore scripts; CloudFormation for S3 and IAM | IMPLEMENTED | Used for the 0.2.0 deployment today |
| Staging environment | MISSING | Changes go from a local test server straight to production |
| Monitoring and alerting (uptime, errors, disk, queue depth) | MISSING | Only `/healthz`, `verify.sh` and the AWS budget alarm |
| Release distribution | PARTIALLY IMPLEMENTED | The app is registered on Play, but testers get **debug-signed, debuggable** APKs. The release signing setup lives outside the repo. The R8 build ran on the emulator, never on a phone |

## 4. Security risks

Severity: **High** can expose data or stop the service for everyone. **Medium** affects correctness, availability or
privacy for some users. **Low** is hygiene.

| # | Risk | Severity | Detail |
|---|---|---|---|
| S1 | No forward secrecy, plus server history keyed from the identity seed | High (by design, documented) | A stolen device key decrypts captured envelopes. A stolen recovery key or identity seed decrypts the whole server-stored history. `SECURITY.md` §4 still says ciphertext stays "at most 7 days"; history now stays until deleted |
| S2 | Server-asserted group membership | High (by design) | A compromised server could add a member and receive future group or channel messages. Clients don't verify membership |
| S3 | Test builds are debuggable debug builds | High for a staff rollout | The debug network config allows cleartext to any host, and a debuggable app exposes its data through `run-as` and debuggers. Distribute minified release builds through Play internal testing instead |
| S4 | Open registration on the internet-facing server, with paid storage behind it | High | Anyone can register and upload within the default quotas: 2 GiB a day and 4 GiB stored per person, 10 GiB server-wide. Phone lookup is rate-limited per device, so many accounts can enumerate numbers faster |
| S5 | Credentials | High | The AWS root access key is still active, used by the local `griipbuddy` profile and the AWS Agent Toolkit. The VPS holds a long-lived IAM key (scoped and boundary-limited; rotation is manual) |
| S6 | Single VPS and SQLite, with backups on the same machine | High (availability) | No off-site or encrypted backups, no schedule. The VPS is shared with about 17 other projects |
| S7 | Messages from a device revoked later are lost | Medium | P7 / T1.5 still open |
| S8 | No push and no TURN | Medium (availability) | Missed messages and calls when the app is killed. Calls fail on networks that allow only port 443 |
| S9 | No CI | Medium | The chat-open crash shipped in a commit and was caught later by hand. Nothing gates regressions |
| S10 | What the server can see | Medium (by design) | Social graph, timing, sizes, profiles and presence. Profile photos aren't E2EE. Request logs carry attachment and conversation ids (5 × 10 MB rotation) |
| S11 | Public repository with infrastructure detail | Low | IPs, hostnames, ports, Compose layout, test usernames and AWS resource names are in the docs. No secrets |
| S12 | No screenshot protection or app lock | Low | Matters for lost or shared phones |
| S13 | Call media key not rotated on leave | Low | Eviction from the SFU limits it |
| S14 | Crypto and network dependencies trail current releases | Medium | SQLCipher 4.12, Bouncy Castle 1.79, OkHttp 4.12 (debt D37). Upgrade in a dedicated, device-tested task |

## 5. Incomplete work, constraints and technical debt

- **Physical-device verification is the largest evidence gap.** Most UI, notification, pairing, call and
  background-delivery behaviour has only emulator or JVM evidence. The HONOR phone wasn't connected in either
  2026-10-17 session.
- **Architectural constraints:**
  - one Node process with an in-memory hub and SQLite, so no horizontal scaling (D31);
  - per-device ciphertext fan-out for channels (D14);
  - server-asserted membership;
  - the history key derived from the identity seed;
  - federation present but off.
- **Code structure:**
  - `MessageRepository.kt` is 1,155 lines, mixing outbox, receiving, attachments, receipts and call keys (D32).
    `ConversationScreen.kt` (886) and `CallManager.kt` (805) are also over the 800-line guideline;
  - `routes/orgs.ts` is 917 lines (D33);
  - `ApiClient` has no interface (D35), which makes repository tests harder.
- **Documentation drift:**
  - `README.md` and `ARCHITECTURE.md` §3 still say history lives only on the device, and the README names old APKs;
  - `KNOWN_ISSUES.md` says history doesn't move between devices;
  - `FEATURE_INVENTORY.md` and `TECHNICAL_DEBT.md` mark many items "not deployed" that 0.2.0 deployed. D2 is still
    listed although T1.1 fixed it;
  - the root `.env.example` duplicates an old version of `deploy/.env.example`;
  - an empty, untracked `ui/nav/` folder exists locally.
- **Housekeeping:**
  - Git lists a worktree at `.claude/worktrees/ecc-project-setup-62b88d` whose folder no longer exists (prunable);
  - production holds test accounts (`e2e.*`, `s3chk*` disabled; `griipbot*`, `s3emucheck1017` active);
  - the test bucket holds the shared APKs until deleted.

## 6. UI and design components

**Preserve:**

- **The design system in `ui/theme` and `ui/components`.** Tokens for spacing, shapes and motion; light and dark
  colour schemes with accents checked for contrast (`AccentsTest`); Plus Jakarta Sans.
- **About 35 reusable components:** `GriipTopBar`, `GriipNavigationBar`, `GlassCard`, `PillButton`,
  `CircleIconButton`, `GriipTextField`, `MenuRow`, `StatusBanner`, `EmptyState`, `UndoBar`, `UserAvatar`, and more.
- **Good accessibility defaults:**
  - `CircleIconButton` requires a label and enforces a 48 dp touch target;
  - `Modifier.pressable` sets roles and click labels;
  - chat rows expose swipe actions as custom accessibility actions;
  - there are 50 `semantics` uses, live regions and state descriptions;
  - a text-size setting, an Appearance setting (System, Light or Dark), and `adjustResize`.
- **The screen structure:** Chats, Calls, Contacts and Settings tabs, with an overlay tab bar that keeps scroll
  position (fixed today).

**Refine:**

- **Localisation is partial.** `values` has 159 strings (Bengali and Chinese 157), but about 105 `Text("…")` literals
  and other inline labels are hard-coded English. The most are in `OrgScreens`, `ProfileScreens`, `Onboarding`,
  `ConversationScreen` and `CallOverlay`. Avatar labels are built as `"$name avatar"`: English-only, and they read
  "You avatar" in onboarding.
- **Unlabelled icons.** 47 `Icon(…, null)` calls; most sit inside labelled buttons, but they need a TalkBack pass to
  confirm.
- **Responsive layout.** No window-size classes: tablets, foldables and landscape get a stretched phone layout. Only
  the call grid and media viewer adapt to their space.
- **Untested states.** No Compose UI tests or screenshot tests, and no TalkBack or Switch Access run is recorded.
  Several screens (Workspace, Team, Members, Invites, Settings) have had no visual review on a phone.

## 7. Incremental plan, reusing what exists

Each step is small, testable and builds on existing modules. Correctness and security come before new features.

1. **Make regressions visible.**
   - CI (GitHub Actions) running server tests and typecheck, Android unit tests and lint on every push;
   - run the physical-phone checklist (docs/ORBIT_AWS_PRODUCTION_2026-10-17.md §9);
   - correct the drifted docs (§5).
2. **Ship builds employees should hold.** Distribute minified release builds through Play internal testing (S3);
   keep debug APKs for developers.
3. **Close the correctness and security gaps in existing modules:**
   - T1.5, revoked-device messages (server `deviceDirectory` with `revokedAt`, `Directory.senderSigningKey`, a vitest
     and a JVM test);
   - self-service account deletion (a route plus `jobs.ts` cleanup);
   - lower quotas, or switch to invite-only once staff are registered (S4);
   - retire the AWS root key with the planned migration (S5);
   - scheduled, encrypted, off-site backups (S6), for example to the existing S3 account under a separate prefix and
     user.
4. **Reliable delivery:** push wake-up, the recommended next feature below.
5. **Calls on any network:**
   - TURN over TLS on 443 (LiveKit's built-in TURN or coturn) behind the existing nginx;
   - then a 3+ phone group-call test and capacity measurement;
   - media-key rotation on leave.
6. **Quality of the UI:**
   - move hard-coded strings into resources (`android/i18n/strings.py` exists for this);
   - a TalkBack pass and Compose UI tests for Chats, Conversation and Onboarding;
   - window-size classes for tablets and foldables.
7. **Product depth:**
   - a local full-text search index (Room FTS);
   - thread notifications and following;
   - a small web admin console for workspace owners (reusing `routes/orgs.ts`);
   - monitoring and alerts.
8. **Longer term:** forward secrecy (MLS or a ratchet) and signed group state (S1, S2); horizontal scaling; enabling
   federation.

## 8. Recommended next feature: push wake-up

**Push wake-up for messages and calls.** A self-hosted UnifiedPush endpoint (ntfy) first, with FCM as a second
transport for phones that have Google Play services.

**Why it's the most valuable:**

- **Core reliability.** Today a message or a call reaches a phone only while the app's process and its foreground
  service survive. MagicOS and other OEM battery managers kill it, and a force-stop ends delivery until the app is
  reopened. For employees this is the most visible failure: messages "arrive late" and calls don't ring.
- **It multiplies everything else.** Messaging, calls, mentions and notifications are implemented and tested, but
  only work while the app is alive. Push is what lets that work reach people.
- **It fits the design.**
  - `BackgroundDelivery` is already an interface meant for this.
  - The server already knows when it queues an item for an offline device.
  - The wake-up carries no content, sender or conversation id, so the E2EE model and metadata exposure don't change.
- **It's testable:**
  - server: vitest for endpoint registration, coalescing (about one per 30 s per device), host allow-listing (no SSRF)
    and payload-free pushes;
  - Android: JVM tests for registration and unregistration in `BackgroundDelivery`;
  - a device test: kill the app, send from the bot, see the notification.

**Dependencies:**

- **Your approval for a production change:** an ntfy container in the Compose project and a TLS route in the host
  nginx (`BACKGROUND_DELIVERY.md`, option B).
- **Phones:** a UnifiedPush distributor app on each (ntfy). FCM later needs a Firebase project and Play services,
  which China-market HONOR phones usually lack.
- **A connected physical phone** to verify wake-up under MagicOS's battery management.
- **Before or alongside it:** CI (§7 step 1), so the change lands with regression checks. Push also makes incoming
  calls on a locked or killed phone possible, which then needs the full-screen-intent check (§3.6).
