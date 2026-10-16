# Testing & acceptance report

Status key:
- **PASS:** executed and passed.
- **FAIL:** executed and failed.
- **BLOCKED:** a prerequisite was unavailable.
- **NOT IMPLEMENTED:** the feature doesn't exist yet.
- **NOT VERIFIED:** the code exists but there isn't enough evidence that it works.

Test environments:
- **Unit/integration:** vitest, with the server in-process.
- **Production:** `https://msg.griipbuddy.cloud` and `wss://rtc.griipbuddy.cloud` on the VPS.
- **Emulator:** Android 16 (API 36.1), arm64.
- **Physical phone:** HONOR ELI-AN00, MagicOS 10 / Android 16, arm64, 1200×2664, connected over USB and wireless debugging.
- **Second participant:** the headless Griip Bot (`tools/griip-bot`), using the TypeScript reference client and the official `@livekit/rtc-node` SDK.

There was no second physical phone. Every two-party test used the phone plus the bot.

## 1. Android build

| Test | Status | Evidence |
|---|---|---|
| Clean debug build | PASS | `./gradlew :app:assembleDebug`, exit 0 |
| Package id | PASS | `aapt2 dump badging` → `package: name='com.griipbuddy.message'`, versionName `0.1.0-beta`, targetSdk 36 |
| APK exists, non-empty, signed | PASS | `artifacts/GriipMessage-debug.apk`, about 85 MB; `apksigner verify` OK (debug key) |
| ABIs | PASS | arm64-v8a, armeabi-v7a, x86_64 |
| Installs and launches (emulator) | PASS | No crash. Cold start 4.7–6.6 s on the emulator (debug build) |
| Installs and launches (physical phone) | PASS | No crash. Cold start **1.97 s** (`am start -W`), container init 190 ms |
| Release (R8) build | NOT VERIFIED | `assembleRelease` succeeds (2026-10-16, about 65 MB unsigned); never run on a device |
| Android unit tests | PASS | `./gradlew :app:testDebugUnitTest` → `VideoSinkBindingTest` 8/8 (renderer lifetime, sink ordering, track switching, stop/start, release), `FrameCryptorGuardTest` 8/8 (observer never throws, late callbacks dropped, callback failures reported, map copy survives concurrent changes, state mapping, LiveKit internals still where the guard looks) |

## 2. Backend (vitest: 87 tests, all PASS on 2026-10-16)

`cd server && npm test` gives 15 files and 87 tests passed, and `tsc --noEmit` is clean. Earlier results on this list were recorded on older builds (32, 36, 69, 81).
Suites added in the 2026-10-16 feature pass. For each, the tests failed with the fix removed, except where noted:

| Suite | Tests | Covers |
|---|---|---|
| `orgs.test.ts` › demotion to guest | 4 | T1.1, plus non-org group retained |
| `call_eviction.test.ts` | 8 | T1.3 (recording LiveKit stub; 7 of 8 fail without the fix) |
| `registration_quotas.test.ts` | 12 | T1.6 invite gate and upload limits (new behaviour) |
| `presence.test.ts` | 4 | T1.7 (2 fail without the fix) |
| `activity_scope.test.ts` | 4 | T1.8 (3 fail without the fix) |
| `interop_vectors.test.ts` | 1 | Shared Kotlin/Node crypto vectors (D22) |
| `device_erase.test.ts` | 1 | T1.9: a self-revoked device leaves contacts' recipient lists, its queue is emptied, and its token and login are refused |
| `ordering.test.ts` | 2 | T2.1/P6: 105 queued + live mid-flush arrive once and in queue order; burst without duplicates (both fail without the fix) |
| `authz_edges.test.ts` | 4 | T3.1 invites stop working when their creator loses the right to issue them; T3.3 CLI-disabled accounts are disconnected; T3.2 archived scopes are read-only until unarchived (all fail without the fix) |
| `ops_hygiene.test.ts` | 3 | Tier 7: `/healthz` shows no usage numbers; `secure_delete` on; old call and audit records pruned, recent and live ones kept |
| `identity_pinning.test.ts` | 4 | T1.2 against a relay that swaps a contact's identity and device keys (the reference client refuses to send, holds forged messages, and resumes only for the accepted key) |
| `messaging.test.ts` › announcement controls | 1 | Phase 14: members' reactions and pins are refused in announcement channels; the manager's pin is accepted (contract the app now mirrors) |
| `departments.test.ts` | 5 | Tier 7 department editing: manager-only rename, regrouping limited to the team's branch, delete keeps teams, archived branch refused, audit visible to admins only (4 of 5 fail without the change) |
The 2026-10-16 audit also ran 7 probe scenarios against the API. All confirmed defects: see `FEATURE_INVENTORY.md`.

| Area | Status | Covered by |
|---|---|---|
| Two users register with device certificates | PASS | auth.test |
| Signed challenge login; nonce replay rejected | PASS | auth.test |
| Wrong-key proof rejected; username alone grants nothing | PASS | auth.test |
| Certificate not signed by identity rejected | PASS | auth.test |
| Unique usernames, availability check | PASS | auth.test |
| Protected routes require auth | PASS | auth.test |
| Recovery onto new device; revocation kills session immediately | PASS | auth.test |
| Device pairing (relay of sealed identity) | PASS | auth.test |
| Org, branch, department, team creation | PASS | orgs.test |
| Invitations: preview, max uses, revoke | PASS | orgs.test |
| Role enforcement through direct API calls (members, admins, branch managers, team leads, guests, outsiders) | PASS | orgs.test |
| Last owner can't be demoted | PASS | orgs.test |
| Cross-branch isolation (no access to other branch's members/teams/channels) | PASS | orgs.test |
| Public, private and announcement channels; guest explicit-only access | PASS | orgs.test |
| Moving between teams needs rights on both teams; removal revokes team channels | PASS | orgs.test |
| Org removal revokes all channels | PASS | orgs.test |
| Demotion to Guest removes branch, team and all org channel memberships; stale envelopes to the guest refused; explicit re-grant kept; promotion restores scope-wide channels only | PASS (failed before the fix) | orgs.test › demotion to guest |
| Activity feed hides private channel names from members | PASS | orgs.test |
| Real-time two-way E2EE delivery; **server holds only ciphertext** (DB and WAL scanned for plaintext) | PASS | messaging.test |
| Ack deletes queue rows; delivery receipt to sender | PASS | messaging.test |
| Offline queue delivered in order after reconnect | PASS | messaging.test |
| Duplicate submission idempotent | PASS | messaging.test |
| Bad signature / stale device list rejected (409 device_mismatch) | PASS | messaging.test |
| Group delivery only to members; non-member can't get keys or post | PASS | messaging.test |
| Typing relay only to members | PASS | messaging.test |
| Multi-device copies (recipient's and sender's other devices) | PASS | messaging.test |
| Announcement channels: only managers post | PASS | messaging.test |
| **Expiry:** 1 s TTL → not served after expiry, deleted by cleanup; TTL capped at 7 d | PASS | messaging.test |
| Call token: room-scoped, ≤10 min; callee rings; non-member blocked; decline/missed/end states | PASS | calls_attachments.test |
| Group call: one live call per conversation; only the starter or a manager can end it for all | PASS | calls_attachments.test |
| Attachments: opaque bytes, member-only download, unguessable ids, expiry purge, size limit | PASS | calls_attachments.test |

## 3. Production deployment (from outside, over TLS)

`npx tsx test/e2e/prod-smoke.ts https://msg.griipbuddy.cloud` → **8/8 PASS**: registration, WSS through nginx,
A→B delivery (515 ms), delivery receipt, B→A, offline queue after reconnect, LiveKit token for `wss://rtc.griipbuddy.cloud`, ring event.

| Test | Status | Evidence |
|---|---|---|
| Compose config validates | PASS | `docker compose -p griip-internal-messaging config -q` |
| Project name and isolation | PASS | Only `griip-internal-messaging_default` network and `griip-internal-messaging_api-data` volume |
| Containers healthy | PASS | api `healthy`; livekit up, advertising node IP 72.60.107.88 |
| `/healthz`, `/v1/server-info`, LiveKit `OK`, 401 without auth, HSTS | PASS | curl from the workstation |
| Existing apps still work after deploy | PASS | griipbuddy.cloud 307, api/admin/devapi.griipbuddy.com 200, exprobd.com 200 |
| Queue cleared after acks | PASS | `admin.js stats` → 0 queued envelopes |

## 4. Media (production SFU)

| Test | Status | Evidence |
|---|---|---|
| Real room join through firewall (7881/tcp, 7882/udp) | PASS | Two `@livekit/rtc-node` peers exchanged media |
| **Media E2EE, correct key** decodes | PASS | 84 video and 877 audible audio frames received |
| **Media E2EE, wrong key** decodes nothing | PASS | 0 video, 0 audio frames → media is E2EE, not only transport-encrypted |

## 5. Physical phone (HONOR, Android 16) with Griip Bot as the second participant

| Test | Status | Evidence |
|---|---|---|
| Identity creation on device against production | PASS | User `@nahian` registered from the phone |
| Workspace creation + channel (`#test`) from the phone | PASS | Done by the tester on the device |
| **Node → Kotlin decrypt** (bot → phone) | PASS | Greeting shown on phone; phone's encrypted read receipt decrypted by bot |
| **Kotlin → Node decrypt** (phone → bot) | PASS | "Hello" and others decrypted and signature-verified by bot; echo shown on phone |
| Typing indicators, delivery and read receipts both ways | PASS | Bot log |
| Encrypted attachment upload from phone | PASS | Phone sent a 452 KB JPEG; relay stored ciphertext; bot got the encrypted reference |
| **Attachment decrypt + integrity on recipient** | PASS | Phone-encrypted 386 KB JPEG: bot downloaded ciphertext, SHA-256 matched, AES-GCM decrypted to a valid JPEG (magic `ffd8ffe1`) |
| **Call crash** (UnsatisfiedLinkError when creating the E2EE key provider before WebRTC loaded) | FAIL → fixed | Crashed on outgoing, accept and join. Fixed with `LiveKit.init()` before creating the key provider; every native init failure now shows an error instead of crashing |
| Voice call phone → bot after fix | PASS | Bot answered, received phone microphone; E2EE status true |
| **Video call phone ↔ bot** | PASS | Bot received phone camera 360×640 (348 frames) + mic; phone rendered bot's test pattern with "End-to-end encrypted" badge |
| Mute, camera toggle, speaker controls rendered | PASS | Screenshot during call |
| **Screen share from phone** (MediaProjection consent → publish) | PASS | Bot subscribed `SOURCE_SCREENSHARE` at 1200×2664; "You're sharing your screen" banner + Stop shown; Stop unpublished the track |
| Screen-share track E2EE | PASS (2026-10-16) | Wrong-key participant decoded 0 screen-share frames; correct-key participant decoded 2,262 at 1200×2664 (§5a) |
| Call end/cleanup propagates | PASS | Server `ENDED reason=hangup`; bot room torn down |
| **Incoming call → Accept on phone** (bot calls phone) | PASS | After fix: phone joined, showed bot video + self-preview + E2EE badge; bot received phone camera 360×640 + mic |
| Background/locked incoming calls | NOT VERIFIED | Needs the "stay connected" service; no push wake-up |
| 3-party call (phone + 2 bot devices) | PASS | Phone rendered a grid (You, bot test pattern, undecodable wrong-key bot tile) |
| **Wrong-key participant vs phone's camera/mic** | PASS | Second bot device with a deliberately wrong media key: **0 video frames, 0 audible audio frames** from the phone (1,584 silent frames), while the correct-key bot decoded camera and mic normally; phone couldn't decode the wrong-key bot either |
| Native crash during screen-share consent / after call end | FAIL → fixed (re-tested 2026-10-16, §5a) | `ApplicationExitInfo`: 2× SIGSEGV in `libjingle_peerconnection_so`. One was on the main thread when the consent dialog covered the call, the other on the audio playout thread after release. Fixed by ordered teardown (UI detaches sinks, then disconnect, then delayed release) and lifecycle-aware video sinks |
| Group calls with 3+ physical phones | NOT VERIFIED | Only one phone was available |

## 5a. Video and screen-share fix (2026-10-16)

The testers reported that remote camera video and screen share weren't visible. It was reproduced on the phone with the
diagnostic build: the phone decoded the bot's video (`framesDecoded` rising, stream ACTIVE, E2EE OK, renderer attached at
1200×2664), but the screen stayed black, and the self-preview was blank too. The bot received the phone's camera throughout.
Root cause and fix: `KNOWN_ISSUES.md` › Fixed during testing. Every row below ran on the fixed build, the phone against the
production SFU, with Griip Bot as the other participant. Debug builds log per-track pipeline state under the `CallDiag` tag.

| Test | Status | Evidence |
|---|---|---|
| Remote camera visible on the phone | PASS | Bot test pattern shown full-bleed (screenshot); inbound 640×360, `framesDecoded` rising, 0 dropped |
| Self-preview visible | PASS | Live camera in the floating preview (screenshot) |
| Phone camera visible to the peer | PASS | Bot decoded 720×1280 (1,519 frames in the final run) |
| Remote screen share visible on the phone | PASS | Bot published a 960×540 share 10 s into the call; phone switched the main tile to it, letterboxed in full (screenshot), E2EE OK, stream ACTIVE |
| Back to camera when the remote share stops | PASS | Main tile returned to the bot's camera, full-bleed (screenshot) |
| Phone screen share to peers (MediaProjection consent tapped by the tester) | PASS | Correct-key bot decoded the phone's screen at 1200×2664 (2,262 frames); Stop unpublished it and both peers unsubscribed |
| **Screen-share E2EE, wrong key** | PASS | Wrong-key bot device: 0 screen-share, 0 camera, 0 audible audio frames from the phone. Correct-key device decoded all three |
| 3-party grid (phone + correct-key + wrong-key bot devices) | PASS | You tile = camera, correct-key bot = test pattern, wrong-key bot = blank (undecodable) |
| Group-call "You" tile while sharing | NOT VERIFIED on device | Before the fix it showed the phone's own screen recursively (screenshot). The fix (camera instead) is built and installed, not re-run |
| Camera off/on during a call | PASS | Self-preview renderer released, then re-attached, with no errors |
| End call cleanup | PASS | Both renderers released; peers saw the participant leave; server `ENDED` |
| Crashes in these runs | PASS | `dumpsys activity exit-info`: only package-update restarts. No new `data_app_native_crash` entries |
| **Android ↔ Android, two physical phones** | BLOCKED | Only one phone was available. Needs two testers on this build |

## 5b. Frame-cryptor abort stress test (2026-10-16)

Background and fix: `KNOWN_ISSUES.md` › Calls. The bot's stress options keep the phone's frame decryptors reporting
(`MISSING_KEY` ↔ `OK`, about 300 callbacks a second) while remote tracks are unpublished and the call is torn down. In every
run the phone answered, Home was pressed, and the phone stayed in the background. Phone against the production SFU.

| Test | Status | Evidence |
|---|---|---|
| Unpatched build (4073c51): key flap, 6 extra tone tracks, call ended through the API while the bot kept sending | Not reproduced | No crash. About 5,300 E2EE callbacks in 17 s, then the phone tore the room down under load |
| Unpatched build: same, plus tone tracks republished every 1.5 s for 60 s | Not reproduced | No crash. 12,496 callbacks, 116 remote unsubscribes, then the API-ended teardown |
| Patched build, normal video call | PASS | Badge fed by the guard (`media security: E2eeActive`); `4 observers replaced`, 0 late callbacks |
| Patched build, flap and churn as above (60 s, twice) | PASS | No crash. LiveKit emitted no E2EE events of its own (the guard had every cryptor, 121 in the last run); 112–119 remote unsubscribes; 0 late callbacks |
| Crash records after the runs | PASS | No new `data_app_native_crash` or tombstone; `exit-info` shows only package updates |

**What these runs do and don't show (reviewed 2026-10-16 from the phone logs):**
- They do show that the guard takes over every cryptor without breaking calls, and that key problems still reach the badge.
  In the last patched run the badge flipped between `E2eeProblem` and `E2eeActive` 23 times during the key flap.
- They don't show that the guard prevents the 02:50 abort:
  - The unpatched build survived the same load (thousands of LiveKit E2EE events and 116 remote unsubscribes).
  - The guard counted **0 late callbacks** in every patched run. A state callback for an already-cleared track, the crash's
    precondition, never occurred, so the guard's protective path wasn't exercised on the device.
- The hardening added after these runs (map-copy and swap errors caught, callback failures reported, `FrameCryptorGuardTest`
  6 → 8) hasn't been run on the phone yet. Re-run is **BLOCKED**: no phone was connected.

## 6a. Android unit tests (JVM, T2.4 harness)

Run with `cd android && ./gradlew :app:testDebugUnitTest` (JDK: Android Studio's JBR). They're plain JUnit 4 + `kotlinx-coroutines-test` tests,
with no device or emulator. `crypto/Bytes.kt` uses `java.util.Base64`, so the real crypto code runs on the JVM.

| Suite | Tests | Status | Covers |
|---|---|---|---|
| `crypto/EnvelopeInteropTest` | 6 | PASS | Node vectors: identity and device public keys, device certificate and auth proof match **byte for byte**; fingerprint equals the server's; AES-GCM equals Node's; a Node-sealed envelope opens and verifies; forged signature, wrong signing key and wrong device rejected; a Kotlin-sealed envelope opens for every recipient |
| `crypto/RecoveryCodeTest` | 3 | PASS | Round trip for 200 random seeds; lowercase, space and no-dash input accepted; a typo or truncation is rejected |
| `crypto/AttachmentCryptoTest` | 2 | PASS | Fresh key per file; a modified ciphertext fails the integrity check |
| `ui/screens/calls/VideoSinkBindingTest` | 8 | PASS | Renderer lifetime and sink ordering (video fix) |
| `data/db/MigrationsTest` | 3 | PASS | D-13: schema versions contiguous and equal to `GriipDatabase.VERSION`; each migration step gives the next exported schema and keeps sample rows (sqlite-jdbc). Proven to fail when v2 had no step |
| `crypto/AttachmentFileCryptoTest` | 4 | PASS | T2.6: streamed encryption gives the one-shot format; a tampered file or wrong key leaves no output or temp file |
| `data/repo/TransferRetryTest` | 3 | PASS | T2.6: what is retried (network, busy, 5xx) and what isn't (size, quota, storage, access) |
| `data/api/ResumableDownloadTest` | 4 | PASS | T2.6 with MockWebServer: Range resume appends; a server ignoring the range restarts; fresh download; 404 |
| `service/BackgroundDeliveryTest` | 1 | PASS | T2.5: "stay connected" runs only for a signed-in user with the setting on (also after reboot or update) |
| `data/api/FrameInboxTest` | 3 | PASS | T2.2: a 1,000-frame burst arrives complete and in order with a slow consumer; a stalled consumer triggers a reconnect, not loss; bounded |
| `data/api/RegisterReqTest` | 2 | PASS | T1.6: `inviteCode` is sent when entered and omitted when blank |
| `data/repo/AttachmentFilesTest` | 3 | PASS | T1.10: a message's decrypted files are deleted with it; names from messages can't reach outside `filesDir/attachments` |
| `data/repo/PendingControlsTest` | 5 | PASS | T2.1: early edits, deletions, reactions and pins are replayed in order; duplicates held once; same-conversation only; bounded; expiring |
| `data/repo/QueueProcessorTest` | 8 | PASS | T1.4: acknowledge only after a local commit; database error, cancellation, malformed envelope, network error, quarantine, poison-item bound |
| `data/repo/KeyDirectoryTest` | 7 | PASS | T1.2 pinning: TOFU; a substituted key pauses sending to the whole conversation; a forged sender is held; an uncertified device is rejected; accepting the reviewed key resumes; a second swap is caught; reverting clears the warning |
| `data/repo/CallKeyVerifierTest` | 5 | PASS | T5.2: a call key is accepted only from the call's creator in its own conversation; unknown call refused; server failure propagates (redelivery) |
| `data/model/PayloadVectorsTest` | 3 | PASS | Phase 14: every payload type round-trips unchanged against `griip-payload-v1-vectors.json` (renaming `replyTo` failed 2 of 3); newer clients' unknown types still decode |
| `ui/scan/InviteScanTest` | 5 | PASS | T4.2: only Griip invite codes accepted (web links refused); decodes the QR the invite screen draws, including a padded camera row |
| `data/repo/CallKeyRequestsTest` | 4 | PASS | T5.3: grace period, request pacing over a 10 s wait, per-call answer pacing, bounded state |
| `notify/NotificationHistoryTest` | 4 | PASS | T4.4: last few lines per conversation, cleared on read or erase, bounded |
| `data/api/ScopeRequestsTest` | 3 | PASS | Clearing a team's department or a description sends an explicit JSON null |

**Totals (2026-10-16):** 92/92 in the working tree. That includes `call/FrameCryptorGuardTest` (6), which belongs to another session's uncommitted call work; the committed tree alone has 86. The outbox and the repository's receive wiring still have no JVM tests (D35, D36).
**Device check of the Base64 change: BLOCKED.** The phone refused the USB install (`INSTALL_FAILED_ABORTED: User rejected permissions`,
which needs the owner to confirm on screen).

## 6. Visual review

- Welcome screen, light theme (emulator): matches the reference. It has the gradient canvas, glossy hero, two-tone headline, teal pill with arrow knob and page dots.
- Home, dark theme (phone): good hierarchy. **Defect found:** white text on light-teal hero and buttons in dark mode had low contrast. It was fixed with deeper dark-mode action tokens, which are in the latest APK.
- Call screen (phone): full-bleed remote video, E2EE pill, glass control bar, red share banner, all consistent with the design system.
- Remaining screens weren't screenshot-reviewed in this session: Chats list, Conversation, Workspace, Team, Members, Invites, Profile, Settings.

## How to re-run

Backend tests:

```bash
cd server && npm test
```

Production smoke test:

```bash
cd server && npx tsx test/e2e/prod-smoke.ts https://msg.griipbuddy.cloud
```

Bot that echoes, verifies files and answers calls:

```bash
cd tools/griip-bot && npm run bot -- chat <yourUsername> 1800
```

Bot calls you:

```bash
cd tools/griip-bot && npm run bot -- call <yourUsername> video 120
```

Bot calls you and shares a landscape test screen from 10 s to 40 s:

```bash
cd tools/griip-bot && GRIIP_BOT_SCREEN=1 npm run bot -- call <yourUsername> video 60
```

Frame-cryptor stress test (§5b). Answer on the phone, press Home, and leave it until the bot exits:

```bash
cd tools/griip-bot && GRIIP_BOT_KEY_FLAP=1 GRIIP_BOT_KEY_FLAP_MS=20 GRIIP_BOT_EXTRA_AUDIO=6 GRIIP_BOT_CHURN_MS=1500 GRIIP_BOT_END_FIRST=1 npm run bot -- call <yourUsername> video 60
```

Afterwards, `CallDiag` logs a `frame-cryptor guard:` summary, and `adb shell dumpsys dropbox --print data_app_native_crash`
should have no new entry.

Wrong-key E2EE check: call the bot from the phone while two bot devices answer, one of them with a wrong key:

```bash
cd tools/griip-bot && GRIIP_BOT_ID=bot-identity-wrongkey.json GRIIP_BOT_WRONG_KEY=1 npm run bot -- answer 150
```

Android unit tests:

```bash
cd android && ./gradlew :app:testDebugUnitTest
```

Call pipeline logs from a debug build (use the SDK's `platform-tools/adb`. A second adb version on PATH keeps killing the server):

```bash
adb logcat -s CallDiag:I CallManager:I
```
