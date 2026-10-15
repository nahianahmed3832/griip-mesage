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
- **Physical phone:** HONOR ELI-AN00, MagicOS 10 / Android 16, arm64, 1200×2664, connected over USB.
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
| Release (R8) build | NOT VERIFIED | Configured but not built in this session |

## 2. Backend (vitest: 32 tests, all PASS)

`cd server && npm test` → 4 files, 32 tests passed.

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
| Screen-share track E2EE | NOT VERIFIED | Participant encryption status stayed `true` while sharing. The wrong-key run was interrupted by the native crash above and needs re-running on the fixed build |
| Call end/cleanup propagates | PASS | Server `ENDED reason=hangup`; bot room torn down |
| **Incoming call → Accept on phone** (bot calls phone) | PASS | After fix: phone joined, showed bot video + self-preview + E2EE badge; bot received phone camera 360×640 + mic |
| Background/locked incoming calls | NOT VERIFIED | Needs the "stay connected" service; no push wake-up |
| 3-party call (phone + 2 bot devices) | PASS | Phone rendered a grid (You, bot test pattern, undecodable wrong-key bot tile) |
| **Wrong-key participant vs phone's camera/mic** | PASS | Second bot device with a deliberately wrong media key: **0 video frames, 0 audible audio frames** from the phone (1,584 silent frames), while the correct-key bot decoded camera and mic normally; phone couldn't decode the wrong-key bot either |
| Native crash during screen-share consent / after call end | FAIL → fixed (re-test pending) | `ApplicationExitInfo`: 2× SIGSEGV in `libjingle_peerconnection_so`. One was on the main thread when the consent dialog covered the call, the other on the audio playout thread after release. Fixed by ordered teardown (UI detaches sinks, then disconnect, then delayed release) and lifecycle-aware video sinks |
| Group calls with 3+ physical phones | NOT VERIFIED | Only one phone was available |

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
