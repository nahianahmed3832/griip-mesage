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
- **Isolated local instance (2026-10-16, §7):** the server from this checkout (`node dist/index.js`) with its own data directory, plus
  Homebrew `livekit-server` 1.13.10, on the development Mac; reached from devices through `adb reverse`. Never connected to production.

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
| Release (R8) build | NOT VERIFIED | `assembleRelease` succeeds (2026-10-16, again after this release's changes, 65 MB unsigned); never run on a device |
| Android unit tests | PASS | `./gradlew :app:testDebugUnitTest` → `VideoSinkBindingTest` 8/8 (renderer lifetime, sink ordering, track switching, stop/start, release), `FrameCryptorGuardTest` 8/8 (observer never throws, late callbacks dropped, callback failures reported, map copy survives concurrent changes, state mapping, LiveKit internals still where the guard looks), `MediaSecurityTrackerTest` 16/16 (badge needs decryption evidence; D-17) |

## 2. Backend (vitest: 129 tests, all PASS on 2026-10-16)

`cd server && npm test` gives 20 files and 129 tests passed, and `tsc --noEmit` is clean. Earlier results on this list were recorded on older builds (32, 36, 69, 81, 87, 98).
Federation suites (§8): `federation_registry.test.ts` (9) and `federation_e2e.test.ts` (22).
Added for calls, self-hosting and backups (2026-10-16, second pass):
- `call_reachability.test.ts` (4): ringing calls are listed for an offline callee; `alerted` once per call, refused for the caller,
  non-members and ended calls; `busy` decline ends the call and tells the caller; invalid reason rejected; late decline keeps `hangup`.
- `server_info.test.ts` (3): `/v1/server-info` states `infoVersion`, `api`, `minClientApi`, `version`, `instanceName` (trimmed) and
  `registration`, and contains no secret or path; `/healthz` works without authentication.
- `backup.test.ts` (4): a live snapshot is owner-only and verifies (integrity, schema, user count); existing files are never overwritten;
  a restored snapshot opens and migrates; missing, corrupt, foreign and newer-schema files are rejected.
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
| Background/locked incoming calls | NOT VERIFIED | Needs the "stay connected" service. Push wake-ups exist since 2026-10-17 but aren't deployed or phone-tested (`BACKGROUND_DELIVERY.md` § Verification) |
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
  6 → 8) was run on the phone afterwards: §5c.

## 5c. Hardened frame-cryptor guard on the phone (2026-10-16, build `d8f327f1…`, commit b97c09c)

Phone over Wi-Fi debugging (USB had dropped), production SFU, bot options as in §5b. Each call ran in its own process session
with logcat captured. App in the background, except A1 and R1–R3, which were in the foreground so the badge could be read
from the UI tree.

| Scenario | Duration | Cryptors guarded | Subscribe / unsubscribe | Badge problem / active | Late | Failed | Result |
|---|---|---|---|---|---|---|---|
| A1 voice | 30 s | 2 | 1 / 0 | 0 / 1 | 0 | 0 | PASS: UI showed "End-to-end encrypted" |
| A2 video | 30 s | 4 | 2 / 2 | 0 / 1 | 0 | 0 | PASS: 175 frames at 640×360 decoded on the phone, 550 on the bot |
| B key-index flap every 40 ms | 30 s | 4 | 2 / 1 | 203 / 203 | 0 | 0 | PASS |
| C 6 extra tracks re-published every 1.5 s, end-first | 45 s | 101 | 99 / 92 | 0 / 1 | 0 | 0 | PASS |
| D1 combined (§5b settings) | 30 s | 70 | 68 / 61 | 15 / 15 | 0 | 0 | PASS |
| D2 combined | 60 s | 123 | 121 / 115 | 42 / 42 | 0 | 0 | PASS (one `SUBSCRIPTION FAILED` for a track unpublished before it was subscribed; no cryptor existed) |
| D3 combined | 60 s | 132 | 130 / 122 | 18 / 18 | 0 | 0 | PASS |
| R1 voice after the stress | 30 s | 2 | 1 / 0 | 0 / 1 | 0 | 0 | PASS |
| R2 missing key held 5 s at a time | 40 s | 2 | 1 / 0 | 4 / 5 | 0 | 0 | PASS: UI alternated "Encryption problem" and "End-to-end encrypted" |
| R3 wrong-key peer | 30 s | 2 | 1 / 1 | 0 / 1 | 0 | 0 | **FAIL (badge): UI showed "End-to-end encrypted"**, while the bot decoded 0 audible of 2,484 frames. Fixed in §5d |

There were no app crashes, ANRs or tombstones, and the process stayed the same throughout. **Assessment: stable but unverified.**
No late callback occurred, so the guard's protective path wasn't exercised in these runs.

## 5d. E2EE badge fix on the phone (2026-10-16, builds `081372c1…` and final `3b6f7fee…`: b97c09c + `MediaSecurityTracker`)

USB, production SFU, installed with `adb install -r` (app data kept). The badge was read from the UI tree every 3–5 s.

| Scenario | Result | Evidence |
|---|---|---|
| V1 valid voice | PASS | "Verifying encryption…" while connecting, then "End-to-end encrypted" 0.68 s after the room connected. The bot decoded 1,480 encrypted frames |
| V2 valid video | INCONCLUSIVE | The LiveKit join stalled until the call ended (`connect failed: JobCancellationException`); no tracks |
| V2b valid video (retry) | PASS | "End-to-end encrypted" 0.23 s after connecting (4 tracks proven). The phone decoded 83 frames at 640×360; the bot decoded 166 camera and 2,490 audio frames |
| R2 missing key held 5 s at a time | PASS | UI alternated "Encryption problem" / "End-to-end encrypted" with the holds (25/25 changes, brief flicker at each switch) |
| R3 wrong-key peer | **PASS** | **"Verifying encryption…" for the whole call, never "End-to-end encrypted".** The bot decoded 0 audible of 1,885 frames. **Guard: 1 late callback** (bot mic, during `disconnect()`, dropped; app unaffected) |
| V3 valid voice right after R3 | PASS | "End-to-end encrypted" 2 ms after connecting; no state carried over |
| R3b wrong-key peer (repeat) | PASS | Badge never left "not verified". **Guard: 1 late callback** again during `disconnect()`, dropped |
| R3c wrong-key video, background | INCONCLUSIVE | LiveKit `JOIN_FAILURE` (timeout) |
| V4 valid voice, **final build `3b6f7fee…`** (badge computed inside the publishing lock) | PASS | Joined slowly (about 25 s), then "End-to-end encrypted" 4 ms after connecting; the bot decoded 125 encrypted frames |
| R3d wrong-key, final build | INCONCLUSIVE | Not answered on the phone; no call |

The wrong-key result (R3, R3b) comes from build `081372c1…`. The final build differs only in computing the badge inside its
publishing lock (an ordering fix). On the final build only the valid path (V4) was re-checked on the device.

- There were no app crashes, ANRs or tombstones. The only exit was the install at 17:07:05, and the process stayed the same for
  every call.
- The four "FATAL EXCEPTION" lines in R2 and V3 came from the `uiautomator` tool (two dumps overlapping), not from the app.

## 5e. UI redesign on the phone (2026-10-16, uncommitted build)

`adb install -r` (app data kept). Navigation was driven by `tools/device/tap.sh`, with screenshots from `screencap`.

| Check | Result |
|---|---|
| Tabs: Chats, Calls, Workspace, Settings (dark, then light) | PASS. Docked bar, active indicator, unread badge, FABs; rows aligned to a 16 dp gutter |
| Conversation (dark and light) | PASS. Header, call actions, date chip, call entries, jump-to-latest, composer. **Text bubbles not seen** (no text history on the phone) |
| Theme switch (Settings → Light, then back to System) | PASS. Applied immediately and persisted |
| Incoming and connected voice call from Griip Bot | PASS. Neutral "Verifying encryption…" before media; "End-to-end encrypted" once connected; the bot decoded 2,230 frames, 864 audible |
| TalkBack, font scaling, rotation | NOT VERIFIED |

## 5f. Calls, update and profiles on the phone (2026-10-16, second pass)

Build: this release's working tree (call reachability, voice messages, server profiles), installed with `adb install -r` over the
redesign build; the owner confirmed the install on the phone. App data kept.

| Check | Status | Evidence |
|---|---|---|
| Existing account survives the update | PASS | Servers screen after the update: `msg.griipbuddy.cloud`, "@nahian · first account on this phone", Active. No registry existed before; storage names unchanged |
| Voice call from Griip Bot (production) | PASS | `media room joined in 1241 ms`, `media security: E2eeActive`, `media Flowing via udp · direct` 4 s after answering; bot decoded 2,806 audio frames with encryption on; same app process before and after |
| Video call from Griip Bot (production) | PASS | `media room joined in 1166 ms`, E2EE active, `udp · direct`, media flowing; same process |
| Slow join seen earlier (about 11 s inside LiveKit connect, 2 of 18 calls with `JOIN_FAILURE`) | NOT REPRODUCED | both joins took about 1.2 s over UDP. The new join-time and path diagnostics will show whether a slow join is a TCP fallback when it happens again |
| `alerted`, busy, ring recovery against production | NOT APPLICABLE | the production server doesn't have this release's server changes (not deployed); verified on the local instance (§7) |
| Adding a server profile on the phone | PASS | Add a server → `http://localhost:18090` → "Griip local test · server 0.1.0 · calls available · anyone can sign up" plus the http warning → Add and switch; separate `griip-<id>.db` and `griip_identity_<id>` created next to the untouched originals. The owner then created `@nahian2` on the local instance |
| Voice message on the phone (play, record, send) | NOT VERIFIED | waiting for the owner (the bot sent a voice message to `@nahian2`); see the final report |

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
| `call/FrameCryptorGuardTest` | 8 | PASS | Frame-cryptor abort mitigation (see §1) |
| `ui/screens/chats/ChatPreviewTest` | 4 | PASS | Chat-list second line: typing beats draft beats last message; sender naming; empty and channel-topic fallbacks |
| `ui/screens/calls/CallLogTextTest` | 4 | PASS | Calls tab: missed vs not answered, durations only for answered calls |
| `call/MediaSecurityTrackerTest` | 16 | PASS | D-17: verified only with OK/KEY_RATCHETED from every live track (at least one remote); a silent wrong-key peer stays unverified; missing key shows a problem and recovers; key changes leave nothing stale; removed tracks and participants are forgotten and their late callbacks ignored; muted tracks need no proof; a new call starts fresh; SDK default `failureTolerance` is still -1. **8 of 16 fail with the old rule** |

**Totals (2026-10-16, federation pass):** **142/142** with `data/repo/GlobalAddressTest` (2); `lintDebug` 0 errors; debug and release builds succeed.
**Totals (2026-10-16, second pass):** **140/140**, with `call/CallReachabilityTest` (7), `voice/VoiceNotesTest` (3),
`voice/VoiceDraftControllerTest` (6) and `data/repo/ServerProfilesTest` (6) added, and a voice vector in `PayloadVectorsTest`. `lintDebug`: 0 errors.
Before: 118/118 with the UI redesign's tests (110/110 before it; the frame-cryptor guard's tests are committed since b97c09c). Earlier on 2026-10-16: 92 in the working tree, 86 committed. The outbox and the repository's receive wiring still have no JVM tests (D35, D36).
**Device check of the Base64 change: BLOCKED.** The phone refused the USB install (`INSTALL_FAILED_ABORTED: User rejected permissions`,
which needs the owner to confirm on screen).

## 7. Self-hosting and two-client acceptance on an isolated instance (2026-10-16)

Setup: `server/dist` started with `DATA_DIR` in a scratch folder, `REGISTRATION_MODE=open`, `INSTANCE_NAME="Griip local test"`, LiveKit
keys generated for the test; Homebrew `livekit-server` with `node_ip 127.0.0.1`, TCP 7891, UDP 7892. A second instance ("Community B",
no LiveKit) on another port. Clients: the emulator (Android 16) and Griip Bot identities created on the instance.

| Check | Status | Evidence |
|---|---|---|
| Fresh database, migrations | PASS | `schema_migrations`: 001, 002, 003 applied at first start |
| `/healthz`, `/v1/server-info` | PASS | `{"ok":true,…,"livekit":true}`; `instanceName "Griip local test"`, `api 1`, `minClientApi 1`, `registration "open"`, `calling true`. Unquoted names with spaces break shell sourcing, now documented in `.env.example` |
| `bundle.sh` → `gen-config.sh` → `validate-env.sh` | PASS | bundle without `node_modules`/tests/`.DS_Store`; `.env` mode 600, 48-character secret; example URLs → 2 errors; fixed values → 0 errors; drifted key, duplicate port, bad mode → 4 errors; re-bundling kept `.env` and `livekit.yaml` |
| `docker compose up`, `backup.sh`, `restore.sh` | BLOCKED | Docker isn't installed on this machine; scripts pass `bash -n`. Their building blocks (`admin.js backup`/`verify-backup`) pass `backup.test.ts` |
| Server check from the app | PASS | production (older server, no new fields): "msg.griipbuddy.cloud · server 0.1.0 · calls available"; bare `localhost:18090` → https → "Can't reach the server…"; `http://localhost:18090` → instance name, calls, open sign-up |
| Onboarding on a self-hosted server (emulator) | PASS | server saved, `@emutest` created; footer "Server: http://localhost:18090" with the unencrypted-connection warning |
| Voice message received and played (emulator) | PASS | from the bot (`GRIIP_BOT_SEND_VOICE`, 3.7 s): chat list "🎤 Voice message (0:04)"; bubble with play, seek, `0:04`; play → download, decrypt, "Pause", `0:02 / 0:04`, back to play at the end, audio focus released; seek → `0:03 / 0:04` |
| Add a profile; duplicate refused | PASS | adding the active server: "You're already using this server."; Community B: "Community B · server 0.1.0 · calls not set up · anyone can sign up" |
| Switch after adding | **FAIL → fixed → PASS** | first try showed the old profile's (closed) chat list: `MainActivity` read the container outside the scope that recomposes on `generation`. Fixed (`remember(gen)`); afterwards Add and switch opened onboarding immediately. The same bug likely affected "erase this device" before |
| Isolation | PASS | `emub` exists only on B; `emutest` and the bots only on A; per-profile database and identity files |
| Switch back | PASS | Servers → Switch → confirm: `@emutest` chat list with the bot conversation and voice message intact, connected |
| Remove a profile | PASS | B's database, identity preferences and `serverUrl@<id>` removed, registry back to the original only; `emub` still exists on server B |
| Erase a non-original profile | PASS | device revoked on B; onboarding for B with its address; "Other servers on this phone · @emutest · switch back"; switching back worked |
| Ring + **alerted** (R1) | PASS | emulator showed "Incoming voice call"; caller got `alerted` about 1 s after ringing; decline → caller `ended (reason declined)`; `calls.alerted_at` set |
| **Ring recovery** (R2) | PASS | app force-stopped; ring sent at 19:45:56; app launched 19:46:04; `GET /v1/calls/active` at 19:46:09; incoming-call screen shown and `alerted` the same second |
| **Busy** (R3) | PASS | while a first call rang, a second 1:1 call ended `busy` within 1 s and never rang or alerted; the first kept ringing |
| Call media on the instance | NOT VERIFIED | the emulator has no microphone permission (granting it needs the owner); a phone ↔ bot call on the instance is pending |

Permission prompts: no permission was granted by tooling except one `pm grant` of notifications on the emulator, which was
immediately revoked (state restored). The notification prompt on the emulator was dismissed with Back (nothing granted).

## 8. Federation (2026-10-16)

### 8.1 Automated (vitest, real HTTP between in-process servers)

Three core nodes and three home servers (A `a.test`, B `b.test`, C `c.test`) per run; reference clients encrypt and verify exactly like the app.
A message counts as delivered only after the recipient's client verified the certificate against its pinned key, the signature, the HPKE
unwrap and the AES-GCM tag.

| Area | Tests | Status |
|---|---|---|
| Server identity: self-certifying id, rotation keeps the id, tampering, expiry, forged chain | registry 1 | PASS |
| Join only with endpoint challenge; spoofed endpoint, taken domain, http/host mismatch in production | registry 2–3 | PASS |
| Invalid, expired, stale and **rolled-back** descriptors (a real stolen old key) and old-key heartbeats | registry 4 | PASS (mutation-checked: removing the key-chain check fails it) |
| Replication to 3 nodes with independent re-challenge; idempotent replays | registry 5 | PASS |
| Unreachable without deletion; revalidation on return; no duplicate registration | registry 6 | PASS |
| Decommission replicated, never re-registrable, domain freed for a new id | registry 7 | PASS |
| Revocation needs a threshold (1 signature refused, 2 accepted), replicated | registry 8 | PASS |
| Discovery continues with one node down; returning node catches up | registry 9 | PASS |
| Global lookup through a quorum; same ids and keys as on the home server; unknown names/servers; shadows can't sign in, aren't searchable, can't join groups | e2e 1–3 | PASS |
| E2EE messages both ways A ⇄ B; same conversation id on both; cross-server delivery receipts | e2e 4–5 | PASS |
| No plaintext in either server's database + WAL | e2e 6 | PASS |
| Offline recipient, delivery on reconnect; retried item deduplicated | e2e 7 | PASS |
| Bounded outbox (503 when full); expired items dropped | e2e 8 | PASS |
| Voice message (attachment) pushed to B, downloaded from B, hash-verified and decrypted; non-member refused | e2e 9 | PASS |
| Calls: ring across servers, alerted, token from A for B's user (verified JWT: identity and room), ACTIVE on both, end on both; first-contact ring creates the DM; remote decline (`busy`) ends the host's call; forged call actions and events refused | e2e 10–13 | PASS (mutation-checked) |
| Third server delivering as A's user refused (`sender_not_from_peer`, exact code) | e2e 14 | PASS (mutation-checked) |
| Unsigned (401), replayed (401), misdirected (421) and unregistered-server (403) requests | e2e 15 | PASS |
| Substituted identity key accepted by B's server but refused by Bob's client (pinning), restored afterwards | e2e 16 | PASS |
| Peer outage: messages kept, retried with backoff, delivered in order after recovery | e2e 17 | PASS |
| Live key rotation: delivery continues; old key refused (401) | e2e 18 | PASS |
| Operator block → 403 "peer_refused" | e2e 19 | PASS |
| Decommission: requests refused (410), id can't re-register | e2e 20 | PASS |
| **Acceptance:** with 1 of 3 core nodes down, a fresh lookup is verified by 2 nodes and the conversation continues; with 2 down, unknown domains fail closed (503) and known servers keep delivering from cache | e2e 21–22 | PASS |

### 8.2 Local network with the Android app (2026-10-16, this Mac)

Three core nodes (`node dist/core/index.js`, ports 19001–19003, peered), server A (`a.griip.test`, port 18090, existing data from §7, LiveKit) and
server B (`b.griip.test`, port 18091, existing data, no LiveKit), both restarted with federation on (migrations 004 and 005 applied to the
existing databases). Emulator (Android 16) with two profiles: `@emutest` on A (created **before** federation) and `@emuonb` on B. `adb reverse`
for 18090, 18091, 17890, 7891.

| Check | Status | Evidence |
|---|---|---|
| Both servers active on all three core nodes | PASS | `/core/v1/domains/*` → active on 19001–19003 |
| Existing account gets a global address with no re-registration | PASS | Settings on A: `@emutest:a.griip.test` |
| Bot on A → app on B: lookup through the quorum, text and voice message | PASS | bot: "verified by 3 directory nodes, quorum"; B mirrored conversation `cf_M_mfu…` and shadow `griipbot9251:a.griip.test`; app chat list "🎤 Voice message (0:04)"; header `@griipbot9251:a.griip.test`, no call buttons (B has no LiveKit); voice played `0:03 / 0:04` from B |
| App on B → bot on A | PASS | bot decrypted "Hello across servers" and the app's read receipt; the app showed the echo and **Read** |
| No plaintext on either server | PASS | database + WAL and attachment stores of A and B searched for the texts and the voice audio: none |
| **Acceptance on the device**, core node 3 stopped | PASS | New chat on B → `@emutest:a.griip.test` → sheet "Server a.griip.test: identity verified by 2 directory nodes"; `@emuonb` (B) → `@emutest` (A): delivered; switched to A: decrypted "Hi from server B", replied "Hi back from server A"; switched to B: reply decrypted, first message **Read** |
| Core node 3 rejoins and catches up | PASS | replication cursors from both peers; both domains active on node 3 |
| Cross-server call with real media | PASS (bots) | bot on A called bot on B by address: E2EE on both sides; ~2,420 audio frames and ~220 video frames (640×360) decoded each way through A's LiveKit; B's bot got its token via B → A |
| Cross-server call in the Android app | NOT VERIFIED | the emulator has no microphone permission (granting it needs the owner) and the phone wasn't available for this step |
| Physical phone in the federated run | NOT VERIFIED | the phone has the previous build; installing needs the owner's tap |

## 6. Visual review

- Welcome screen, light theme (emulator): matches the reference. It has the gradient canvas, glossy hero, two-tone headline, teal pill with arrow knob and page dots.
- Home, dark theme (phone): good hierarchy. **Defect found:** white text on light-teal hero and buttons in dark mode had low contrast. It was fixed with deeper dark-mode action tokens, which are in the latest APK.
- Call screen (phone): full-bleed remote video, E2EE pill, glass control bar, red share banner, all consistent with the design system.
- Remaining screens weren't screenshot-reviewed in this session: Chats list, Conversation, Workspace, Team, Members, Invites, Profile, Settings.

## How to re-run

On-device tests (2026-10-17: every kind of conversation opened, left and reopened in the real screen). **Run them on the
emulator only.** Gradle's `connectedAndroidTest` installs the debug build on every connected device and uninstalls it
afterwards, which would remove the Play app's data on a connected phone. Install and run them by hand instead:

```bash
cd android && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

```bash
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
```

```bash
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

```bash
adb -s emulator-5554 shell am instrument -w -e class com.meldiq.android.ConversationOpenTest com.meldiq.android.androidtest/androidx.test.runner.AndroidJUnitRunner
```

Backend tests:

```bash
cd server && npm test
```

The same suite with the accounts and preferences domains in MongoDB (docs/MONGODB.md). Every run starts a throwaway
local MongoDB replica set on 127.0.0.1 (test/support/mongoGlobal.ts); nothing shared or remote is ever used:

```bash
cd server && MELDIQ_TEST_MONGO_DOMAINS=accounts,preferences,integrations npm test
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

Badge checks (§5d), with the app in the foreground. Missing key held 5 s at a time, then a wrong-key peer (the phone gets the real
key; the bot encrypts with another):

```bash
cd tools/griip-bot && GRIIP_BOT_KEY_FLAP=1 GRIIP_BOT_KEY_FLAP_MS=5000 npm run bot -- call <yourUsername> voice 45
```

```bash
cd tools/griip-bot && GRIIP_BOT_WRONG_KEY=1 npm run bot -- call <yourUsername> voice 40
```

Expected: the first alternates "Encryption problem" / "End-to-end encrypted"; the second shows "Verifying encryption…" throughout.

Federation suites (§8.1):

```bash
cd server && npx vitest run test/federation_registry.test.ts test/federation_e2e.test.ts
```

Local federated network (§8.2): build the server, start three core nodes with `node dist/core/index.js` (`HOST`, `PORT`, `CORE_DATA_DIR`,
`CORE_PEERS`, `CORE_ALLOW_HTTP=true`; read each node's key from `/core/v1/info` first), then each server with `FEDERATION_DOMAIN`,
`FEDERATION_PUBLIC_URL`, `FEDERATION_CORE_NODES`, `FEDERATION_ALLOW_HTTP=true`. The bot takes a full address as the peer:

```bash
cd tools/griip-bot && GRIIP_SERVER=http://localhost:18090 GRIIP_BOT_ID=bot-identity-local.json npm run bot -- call someone:b.griip.test video 30
```

Local isolated instance (§7). Build the server, then start it with its own data directory and open registration, and run
`livekit-server --config <file>` with matching keys (`node_ip: 127.0.0.1`, TCP 7891, UDP 7892, webhook to the local API):

```bash
cd server && npm run build
```

```bash
cd server && DATA_DIR=/tmp/griip-local HOST=127.0.0.1 PORT=18090 REGISTRATION_MODE=open LIVEKIT_API_KEY=<key> LIVEKIT_API_SECRET=<secret of 32+ characters> LIVEKIT_PUBLIC_URL=ws://localhost:17890 LIVEKIT_API_URL=http://127.0.0.1:17890 node dist/index.js
```

Map the ports into each device with `adb reverse` (`ANDROID_SERVER_SETUP.md`), use `http://localhost:18090` in a debug build, and
point the bot at the instance with its own identity file:

```bash
cd tools/griip-bot && GRIIP_SERVER=http://localhost:18090 GRIIP_BOT_ID=bot-identity-local.json GRIIP_BOT_SEND_VOICE=/path/clip.m4a GRIIP_BOT_VOICE_MS=3700 npm run bot -- chat <username> 600
```

A second identity (`GRIIP_BOT_ID=bot-identity-local2.json`) calling while the first rings exercises busy; the bot logs its call's
`alerted`, `declined` and `ended (reason …)` events.

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
