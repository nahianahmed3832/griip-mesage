# Orbit UI-003: chat attachments, large files and calling (2026-10-18)

Worktree `.claude/worktrees/orbit-ui-003`, branch `claude/orbit-ui-003`, cut from `origin/internal-beta` 5e3b72f (Orbit 2.0).
Phase A (code, tests, build) and Phase B (the owner's HONOR phone, side-by-side Orbit Test build, local server and
LiveKit over `adb reverse`, Griip Bots as the other party) are done; results in §9. **Production was only read** (the
public `GET /v1/server-info`) and still runs 0.2.0 with the old limits: what it needs is in §3. No emulator was used.
**Owner's decision during Phase B: 1 GB per file and no other file upload limits** (§3a).

## 1. Summary

| Problem | Cause found | Fixed in code | Still needs |
|---|---|---|---|
| 283.8 MB video fails ("still too large after compression", "Couldn't send this file") | Production advertises a **25 MiB** limit (`attachmentMaxBytes` 26214400, API 0.2.0). The Play build also lacks Orbit 2.0's 1 GiB (built from the main checkout at 6f355a1). The app's own logic was right for a 25 MB server, but it warned too late and its messages were vague | Warn before compressing when the estimate won't fit; name the quality that fits; specific compression-failure reason; clipped error text fixed; limit re-read every 10 min | Owner-approved production deploy (§3); ship an app built from internal-beta. Phone: verified (§9) |
| Attach sheet copy "25 MB each", "Can't send", "Nothing to send" | Correct for a 25 MB server; it showed the server's limit | New attach panel shows the server's limit and, on a 25 MB server, says long videos only fit compressed | — |
| Speaker control missing in video calls and screen sharing | `CallOverlay` **replaced the Speaker button with "Flip camera" whenever the camera was on**, and every control auto-hid after 4 s over video or a shared screen | Same five controls in every call (mic, camera, output, share, end/leave); flip moved onto your own preview; no timer hides anything | Verified on the phone in 1:1 video, group video and while sharing (§9) |
| Remote audio too quiet or inconsistent | Measurable causes in code: LiveKit's default route order put the **speaker before the earpiece** until Orbit's late `setSpeaker`, so a call could start on one and jump to the other; group calls started from a chat went to the earpiece while joined ones went to the speaker; the button claimed a route even when no device list existed yet. Device volume and network loss couldn't be measured without the phone | Route order set before connecting; one policy for all calls; button shows the route the phone reports (Android 12+: the communication device) and verifies changes; volume, level, loss, jitter and concealment now measured and shown | Measured on the phone (§9): remote audio −14/−15 dBFS with 0 % loss; **the speaker's call volume was 4 of 15 while the earpiece's was 15 of 15** (Android keeps one per output), now flagged as low. A two-person human check is still pending |
| Choppy audio/video | No TURN; UDP-blocking networks fall back to TCP 7881 (head-of-line blocking under loss). The owner's screenshots show a **VPN active**, a common cause of UDP loss. Not measured yet | Loss/jitter/concealment and the network path visible in Connection details | On the test LAN: `udp · direct`, 0 % loss, 1–6 ms jitter; Wi-Fi off for 10 s stalled media for 10 s and it recovered without a drop. Real-world networks (VPN, mobile) still to measure; TURN only on evidence (§6) |
| Unreliable group calls; sessions left after a participant leaves | Server: a replaced session's late `participant_left` webhook marked a live device as gone (1:1 calls then ended as "lost"); records of devices LiveKit no longer had stayed "connected"; no participant cap. App: `finish()` could run twice at once (hang-up + "ended" frame + disconnect on different threads), disconnecting/releasing one Room twice | Server fixes + tests (§5); teardown serialized | Group calls with three bots on the phone: joins, a leave, the phone leaving (call stays active for the rest), the last ones leaving (ends "empty") (§9) |
| Screen share interfering with controls/media | Controls auto-hid; 1:1 shared screens were drawn under the controls; a share ended by Android left no message; a reconnect dropped a share silently | Controls persistent; shared screens laid out clear of them; own share notification with Stop; "Screen sharing stopped" notices; Stop share ≠ Leave | Verified on the phone: start, Stop in Orbit, Stop from the notification, a stop by Android, capture released at call end (§9) |
| No way to minimise while using the app | The minimise bar sat over app headers with only End; minimised state was local to the call screen; picture-in-picture replaced the whole app tree (navigation lost on return); the notification's "open call" extra was never handled | Persistent call panel at the top that pushes the app down (mute, output, end, reopen); state in `CallManager`; PiP drawn over the app; notification returns to the call | Verified on the phone: panel across Home, DMs and a chat; mute from the panel; PiP for video and none for voice or while sharing; return from PiP and from the notification (§9) |

## 2. Attachments: what the screenshots show, verified

- `curl https://msg.griipbuddy.cloud/v1/server-info` (public, read-only) → `"version":"0.2.0"`, `"attachmentMaxBytes":26214400`,
  features include `chunked-attachments` and `direct-upload`. So every app talking to production, including an Orbit 2.0
  build, is limited to 25 MiB per file today.
- **Screenshot 3 (Original, "Can't send", "Nothing to send"):** correct for that limit. `AttachmentPolicy.check` refuses
  an uncompressed file over the server's limit before anything is uploaded.
- **Screenshot 1 ("still too large after compression"):** the video was sent compressed. At "Compress (1080p)" a 56 s
  clip is about 34 MB (5 Mbit/s + audio), over 25 MB, so the phone compressed the whole clip and then failed. The
  message also told the person to "Try Standard quality", a label the video chips don't use ("Compress (720p)").
- **Screenshot 4 ("Couldn't send this file", 283.8 MB):** the size didn't change, so preparing failed before upload.
  The name `video_20261013_142706.mp4` (instead of the photo picker's `1000082412.mp4`) shows it came from "Files".
  In 0.2.0 a Media3 Transformer failure surfaced as this generic text; the cause class was only in logcat. Logs from
  that attempt weren't available (no phone in Phase A); Phase B can reproduce it with the same file.
- **The left edge of the failure text was cut off** in screenshots 1 and 4: the text was clipped with a pill shape
  (corners of half its height), which cuts the first and last letters of a reason that wraps onto several lines.
- **Play build:** made in Android Studio from the main checkout (6f355a1 + UI-002 files), whose
  `AttachmentPolicy.APP_MAX_FILE_BYTES` is 500 MB and which has no `fileMaxBytes` support. Internal-beta 5e3b72f has
  1 GiB end to end. Even a 1 GiB server would cap that build at 500 MB.

### Attachment changes (app)

- `AttachmentPolicy`: new `Verdict.MayBeTooLarge` for a compressed video whose estimate (length × the quality's bitrate)
  is over the limit, with a hint ("Compress (720p) should fit." or "Try a shorter video."). It stays sendable (an
  estimate); `Verdict.sendable` replaces `== Ok` checks. Unknown length: no guess, as before.
- `BatchComposer`: the tile says "May be too big" and the reason is shown; the limit line reads "this server takes up to
  N per file".
- `MediaPrep`: `TooLargeAfterCompression` names the limit and the preview's own quality label; new `CompressionFailed`
  ("couldn't be compressed on this phone. Try the other quality, or Original if it fits."). `MessageRepository` stores
  that reason and logs the cause chain by class name only.
- `MessageRepository.maxAttachmentBytes`: the server's limit is cached for 10 minutes instead of for the life of the
  process, so raising it on the server applies without restarting Orbit.
- `MessageBubble`: the failure reason uses an 8 dp shape and horizontal padding (no clipped letters).
- `AttachPanel` (new): six tiles (Photos and videos, Camera, Record video, Files, Contact, Location) in the Cocoa palette
  with Orbit's `IconTile`s, the server's limit and the encryption note. The Discord reference was used for the idea (big
  targets in one panel), not copied. **Not done, owner's decision:** an inline gallery grid like Discord's needs
  READ_MEDIA_IMAGES/READ_MEDIA_VIDEO; Orbit uses the system photo picker and sees only what is picked, and Google Play
  restricts those permissions to apps whose core use needs broad photo access (a declaration form).
- Kept as they were: Original vs compressed is the person's choice; up to 10 items in one message; saving is explicit
  (Save to Gallery, Save to Files, Share); files are encrypted on the phone before upload.

## 3. Production: what's wrong there today and what fixes it (needs the owner's go-ahead)

Production (msg.griipbuddy.cloud, API 0.2.0, read through the public `/v1/server-info` and the 0.2.0 code at 570ad81):

| Found in UI-003 | In production today | Fixed by |
|---|---|---|
| Files over 25 MB refused | `.env` pins `ATTACHMENT_MAX_BYTES=26214400` (25 MiB); 0.2.0 has no `fileMaxBytes`; the Play app (6f355a1) caps at 500 MB | Server ≥ 0.4.0 (this branch) with the pin removed; a Play build from internal-beta |
| Upload quotas | 0.2.0 defaults: 2 GiB a day, 4 GiB stored per person, 10 GiB server-wide, 10 GiB kept, 1 GiB in flight; the deploy template it came from pins **250 MiB a day, 1 GiB stored, 5 GiB total, 200 MiB in flight** | This branch's defaults (0 = no limit, §3a) **and** removing those lines from production's `.env` |
| A 1 GB file can never pass through the server | In-flight cap (1024 MiB default, 200 MiB in the template) below one maximum file | Code now keeps it at ≥ 2 maximum files; direct S3 uploads (production's path) weren't affected |
| Calls: a replaced session's late "left" ended live 1:1 calls; stale participant records; no participant cap | Same code in 0.2.0 | Server from this branch |
| App-side calling, attachment and minimise fixes | Play has 0.2.0-beta (7) | A new Play build (versionCode above 7) from this branch |

### 3a. Owner's decision (2026-10-18): 1 GB per file, no upload restrictions

Server defaults since this branch: `FILE_MAX_BYTES` 1 GiB (unchanged); `ATTACHMENT_USER_DAILY_BYTES`, `_USER_STORED_BYTES`,
`_TOTAL_BYTES`, `_USER_KEPT_BYTES`, `NEW_ACCOUNT_DAILY_UPLOAD_BYTES`, `NEW_ACCOUNT_STORED_BYTES`,
`NEW_ACCOUNTS_TOTAL_STORED_BYTES` and `ATTACHMENT_UPLOAD_STARTS_PER_HOUR` all **0 = no limit** (each can still be set).
`ATTACHMENT_MAX_PENDING_BYTES` is never below two maximum files. Kept: 10 items per message (grouping), 2 uploads at a
time per person and 8 on the server (flow control, the app uploads 2 at a time anyway), the phone's free-space check,
and the encrypted-history limits (messages, not files). `deploy/.env.example` updated to match. Found on the phone: an
open-registration account's first 249 MB video was refused with "today's upload limit" (256 MiB a day for new
accounts) while the server advertised 1 GB per file; after the change the same retry uploaded (§9).

Risk the owner accepted: with `STORAGE_BACKEND=local` nothing stops files filling the VPS disk (production uses S3,
where the bucket holds files; the cost is per GB stored and downloaded, below).

### 3b. Steps (each needs the owner's approval; order matters)

1. **Back up** as in the 0.2.0 deploy (`deploy/scripts/backup.sh`: database snapshot, volume, `.env` copy).
2. **Deploy the server from this branch** (0.4.0 + UI-003). This is a jump from 0.2.0 that also ships everything since:
   push endpoints (migration 011; wake-ups stay off until configured), abuse controls (012; `TRUST_PROXY` must name
   the proxy), Orbit 2.0 accounts (014; email codes need the SES key in `.env`, so new-style sign-up stays unavailable
   until then, while legacy sign-in and registration keep working for the Play app). Account deletion (013) isn't on
   internal-beta. Only
   `docker compose -p griip-internal-messaging ... up -d --no-deps api` (never LiveKit).
3. **Edit `/opt/griip-internal-messaging/.env`**: remove `ATTACHMENT_MAX_BYTES=26214400`; set or remove the quota lines
   (`ATTACHMENT_USER_DAILY_BYTES`, `ATTACHMENT_USER_STORED_BYTES`, `ATTACHMENT_TOTAL_BYTES`, `ATTACHMENT_MAX_PENDING_BYTES`,
   `ATTACHMENT_USER_KEPT_BYTES`, `NEW_ACCOUNT_DAILY_UPLOAD_BYTES`, `NEW_ACCOUNT_STORED_BYTES`,
   `NEW_ACCOUNTS_TOTAL_STORED_BYTES`, `ATTACHMENT_UPLOAD_STARTS_PER_HOUR`) to 0 or delete them; optionally
   `CALL_MAX_PARTICIPANTS=12`. Restart the api container.
4. **Check**: `curl https://msg.griipbuddy.cloud/v1/server-info` shows `"fileMaxBytes":1073741824`; `verify.sh` passes.
5. **Reverse proxy**: direct uploads go from the phone to S3 and don't pass nginx. Uploads through the server (fallback)
   need nginx `client_max_body_size` for the msg site at about 1100m.
6. **Cost (S3, ap-southeast-1)**, approximate list prices, to confirm on AWS's pricing page: storage about
   $0.025/GB-month (files expire after 7 days unless kept for history, up to 365 days); data out about $0.12/GB per
   download. A 283.8 MB video to one person ≈ $0.035; a 1 GB file to a group of 10 ≈ $1.20. With no quotas the account
   budget `orbit-monthly-10usd` (≈ 80 GB of downloads a month) is the only alarm: watch it.
7. **App**: Play needs a build from this branch with a versionCode above 7 (the orbit-2-mobile worktree prepares 0.2.1-beta
   (8)). The owner builds and signs it in Android Studio; the main checkout's local `internal-beta` (6f355a1) must be
   moved to origin first, which drops its uncommitted UI-002 copies (they equal 25d606b except `AppRoot.kt`).

## 4. Calling architecture, verified in code

- **App:** LiveKit Android SDK 2.29.0 (WebRTC `io.github.webrtc-sdk:android-prefixed` 144.7559.14). One `Room` per
  call, owned by `CallManager` (app-scoped, survives navigation). E2EE: LiveKit frame encryption with a per-call shared
  key the caller makes and sends inside Orbit's end-to-end encrypted messages (`call_invite`), never through the call
  API; `FrameCryptorGuard` replaces only the cryptors' status observer. Audio: `JavaAudioDeviceModule` (audio source
  VOICE_COMMUNICATION, hardware noise suppressor on Android 10+, WebRTC AEC3 unless the hardware-AEC setting is on);
  routing by LiveKit's `AudioSwitchHandler` (twilio audioswitch fork): `MODE_IN_COMMUNICATION`, audio focus, and
  `setCommunicationDevice` on Android 12+. Camera 1280×720@30 with simulcast; screen share ≤1920×1080@15, 2.5 Mbit/s,
  maintain-resolution, through LiveKit's mediaProjection `ScreenCaptureService`. Foreground `CallService`
  (microphone|camera). Picture-in-picture via `CallPip`.
- **Server:** Fastify routes in `server/src/routes/calls.ts`: create (one live call per conversation), join, connected,
  alerted, decline, end (1:1 either side; group: creator or manager), leave; membership checked on every call
  (`memberCall`, `callAdmission` for revoked devices and disabled accounts). LiveKit access tokens: 10 minutes, one room,
  identity `<userId>.<deviceId>`. LiveKit webhooks (`participant_joined/left`, `room_finished`) keep participant records;
  a cleanup job reconciles with LiveKit's participant list every minute; 1:1 calls wait `CALL_PEER_GRACE_MS` (30 s) for a
  dropped person. Federation forwards actions to the hosting server.
- **Media server:** self-hosted LiveKit `v1.13.10` (`deploy/compose.yaml`), signalling on 127.0.0.1:17880 behind nginx
  TLS, media on 7881/tcp and 7882/udp on the VPS address; `room.max_participants` 12, `empty_timeout` 60 s,
  `departure_timeout` 20 s. **No TURN** (template has no `turn:` section).
- **Lifecycle:** start (`POST /v1/calls`, ring over WebSocket and push) → key over E2EE → join (`/join` issues a
  token) → LiveKit signalling + ICE (UDP, else TCP; no relay) → DTLS/SRTP + frame E2EE → tracks subscribed → media flow
  measured from inbound bytes → `/connected` → share (MediaProjection consent, separate track) → minimise/restore (UI
  only) → a participant leaves (webhook / `/leave`) → end (`/end`, room deleted) → app teardown (sinks detached,
  disconnect after 600 ms, release after 2 s; the next call waits for it).

## 5. Changes

### App (android/)

| File | Change |
|---|---|
| `call/CallAudio.kt` (new) | `AudioRoute`, `CallAudioPolicy` (defaults, toggle vs list, headset takeover, speaker for video, route matching, low volume), `AudioStats` + `AudioStatsSampler` (level dBFS, loss, jitter, concealment) |
| `call/CallManager.kt` | Route preference set before connecting; `onAudioDevices` (actual route, headset takeover, choice re-applied after a rejoin); `selectAudioRoute` with a 1.5 s check against the phone's communication device and a recoverable error; `toggleSpeaker`; camera-on moves an earpiece call to the speaker; remote-audio flow separate from video; call volume and audio stats; `finish`/`fail` serialized; screen share with Orbit's notification, stop notices, no restart; `minimized` state, `minimize`/`expand`; notification kept in step |
| `call/CallService.kt` | Notification with state, timer and Mute / Hang up (Leave) / Stop sharing; share notification for LiveKit's capture service; updates only while running; cancelled on stop |
| `call/CallPip.kt`, `MainActivity.kt` | PiP for video calls only and never while sharing; leaving PiP or tapping the notification opens the full call |
| `call/CallMediaPolicy.kt` | `CallMediaStats` gains audio stats, mic level and call volume |
| `notify/NotificationActionReceiver.kt` | `ACTION_STOP_SHARE` |
| `ui/screens/calls/CallControls.kt` (new) | `CallControlBar` (5 controls everywhere), `AudioRouteButton`, `AudioRouteSheet` (routes, error, volume, "no sound arriving"), `FlipCameraButton`, `CallControlsRules` |
| `ui/screens/calls/MiniCallPanel.kt` (new) | The in-app call panel and its status text |
| `ui/screens/calls/LocalPreview.kt` (new) | Your preview, draggable, snaps to a corner |
| `ui/screens/calls/CallOverlay.kt` | Uses the above; controls hidden only by a tap over video (no timer); status line (name · state/time · count) and reconnecting / no-sound chips in every layout; 1:1 shared screens laid out clear of the controls; flip on your own tiles; route error banner; old mini bar removed |
| `ui/AppRoot.kt` | Call panel above the app (status-bar inset consumed below it); PiP view drawn over the still-composed app; call notices as toasts |
| `data/model/AttachmentPolicy.kt`, `ui/screens/conversation/BatchComposer.kt`, `ConversationViewModel.kt`, `media/MediaPrep.kt`, `data/repo/MessageRepository.kt`, `ui/screens/conversation/MessageBubble.kt` | Attachments (§2) |
| `ui/screens/conversation/AttachPanel.kt` (new), `ConversationScreen.kt` | The attach panel |
| `data/db/Database.kt` | `setActiveCall` / `clearActiveCall` (the live-call banner follows call events; no schema change) |
| Phase B fixes | `CallService` (serialized update/stop), `CallOverlay` (keyboard, chips vs preview), `CallManager` (answered calls, live-call banner), `CallAudio` (averaged levels), `LocalPreview` (bottom banners), `AttachmentPolicy.sizeLabel` (rounding) |

### Server (server/)

| File | Change |
|---|---|
| `src/routes/calls.ts` | Webhook ignores a `participant_left` while LiveKit still lists that identity (a replaced session); `assertCallHasRoom` → `409 call_full` past `CALL_MAX_PARTICIPANTS`, reconnects exempt (join and start-joins-existing); `call started` / `call ended` logs (id, kind, reason, length); `scheduleLost` exported |
| `src/jobs.ts` | `reconcileParticipants`: records of devices LiveKit no longer has → left after the grace (`callStaleParticipants` in the cleanup report); an active 1:1 with fewer than two people in LiveKit gets the lost-grace timer. Nothing changes while LiveKit is unreachable |
| `src/config.ts` | `CALL_MAX_PARTICIPANTS` (default 12, min 2); upload quotas default to 0 = no limit; in-flight cap ≥ 2 maximum files (§3a) |
| `src/routes/attachments.ts`, `src/federation/service.ts` | Quotas only when set (`exceeds`); a clearer message when a new-account allowance is set and reached |
| `deploy/.env.example`, `deploy/scripts/verify.sh` | No upload quotas in the template; disk note |

### Protocol / API

- New error: `409 {"error":{"code":"call_full"}}` from `POST /v1/calls/:id/join` and from `POST /v1/calls` when it joins a
  running call. Older apps show the server's message ("This call is full (12 people at most)").
- No other request, response, Room schema or migration change. `deploy/.env.example` wasn't edited (the orbit-2-mobile
  worktree has uncommitted edits to it); add `CALL_MAX_PARTICIPANTS=12` there when merging.

## 6. TURN relay: proposal only

**Evidence so far:** none that TURN is needed: no failed-call logs from production were read, and the phone wasn't
available. The deployment can't carry media for a phone whose network blocks both UDP 7882 and TCP 7881, and a TCP
path is choppier under loss. Decide from the "Network path" line in Connection details (`udp · direct`, `tcp · direct`)
on the networks where calls fail or stutter (with and without the VPN).

**Smallest change if needed:** LiveKit's built-in TURN, same container.

```yaml
turn:
  enabled: true
  domain: turn.msg.griipbuddy.cloud   # DNS A → 72.60.107.88 (Hostinger DNS)
  tls_port: 5349                      # 443 would need an nginx stream/SNI router; nginx owns 443
  udp_port: 3478
  cert_file: /etc/livekit/turn.crt    # Let's Encrypt for the turn subdomain (certbot on the host)
  key_file: /etc/livekit/turn.key
```

- Firewall: Hostinger group "web-only" needs 3478/udp and 5349/tcp (plus the relay port range LiveKit uses for TURN
  allocations, which must be checked against LiveKit's docs for v1.13 before opening anything).
- Clients receive TURN servers and short-lived credentials from LiveKit in the join response: no app change.
- Security: credentials are per session; the relay forwards only DTLS-SRTP packets whose frames are already end-to-end
  encrypted with the call key, the same ciphertext the SFU forwards, so the trust boundary doesn't change. TLS on 5349
  hides that it's TURN from simple filters; 443 is better for strict networks.
- Cost: no new service; relayed participants' media crosses the VPS twice (in and out). Hostinger KVM 2 bandwidth limits
  apply.
- Deploy: edit `deploy/livekit/livekit.yaml.template` and `gen-config.sh`, issue the certificate, open the ports,
  restart LiveKit (drops live calls; do it when idle). Needs the owner's explicit approval.

## 7. Tests and builds (this Mac)

| What | Result |
|---|---|
| Server `tsc --noEmit` | clean |
| Server vitest (all) | **325 passed**, 9 skipped (live S3), 40 files; includes `call_sessions.test.ts` (5) and the no-quota config test |
| Android JVM unit tests (`:app:testDebugUnitTest`) | **397 passed**, 0 failed (376 on internal-beta before + 21 new) |
| `:app:assembleDebug -Porbit.sideBySide=true` | built: `android/app/build/outputs/apk/debug/app-debug.apk`, applicationId `com.griipbuddy.Orbit.test`, "Orbit Test", 0.2.0-beta (7) |
| `:app:lintDebug` | 0 errors, 66 warnings; none new (the three in touched files, ExifInterface / ObsoleteSdkInt / UseKtx, were there before) |
| New/changed tests | `CallAudioTest` (6), `CallControlsRulesTest` (5) + `PreviewCornerTest` (1), `AttachPanelTextTest` (2), `AttachmentPolicyTest` (+1, the owner's video), `CallVerificationTest` (+1, PiP rules), `CallMediaPolicyTest` (chrome rule changed) |

Mocked tests don't prove that remote audio is audible or that a route change is heard: that needs §9.

## 8. What remains uncertain

- Audio quality as two people hear it, and Bluetooth routing: measured with bots, not with a second person or a headset.
- Choppiness on real networks (VPN, mobile, restrictive Wi-Fi): the test LAN gave `udp · direct` with 0 % loss.
- Older Android versions (route verification uses the Android 12+ API; older ones trust LiveKit's selection).
- Production behaviour after the 0.2.0 → 0.4.x jump (§3b) until it's deployed and checked.

## 9. Physical-device testing (Phase B, 2026-10-18 19:49–21:01)

**Rig.** The owner's HONOR ELI-AN00 (serial AREBVB4530006465, Android 16, `ro.hardware=qcom`, no emulator properties),
side-by-side **Orbit Test** (`com.griipbuddy.Orbit.test`) installed over the earlier test build (data kept); the Play
Orbit and its data untouched. A new account on this branch's server running on the Mac (127.0.0.1:18095, own data dir,
mail to a local file) with a local LiveKit, reached through `adb reverse`; the phone's server address was checked on
screen before saving (the field defaults to production). Griip Bots on that server as the other parties. The owner
granted the camera permission and tapped Android's screen-capture consent; nothing was granted with `pm grant`.
Screenshots stay in the session's scratchpad (some show the owner's camera and home screen) and aren't in the repo.

| Test | Result |
|---|---|
| 1:1 voice | Connected and end-to-end encrypted; defaults to the earpiece ("Phone"); the five controls; remote tone at −15 dBFS, 0 % loss, 1–6 ms jitter, no concealment |
| Speaker toggle | One tap to Speaker; Android reports `MODE_IN_COMMUNICATION` and the applied communication device = built-in speaker, one route owner (Orbit's `CommunicationRouteClient`) |
| Loudness | Call volume read per output: speaker 4 of 15 (later 1 of 15) vs earpiece 15 of 15, shown as "(low)" with a volume-up hint. Microphone level now averaged over each 2 s; it registered speech (−32 dBFS) when the bot heard audible frames, and silence otherwise |
| 1:1 video | Remote video full screen, status "Griip Bot · 0:08", preview with camera switch, **Speaker present with the camera on**; preview dragged to another corner; tap hides and shows the controls, nothing hides them on a timer; camera switched both ways (the bot kept receiving); sending 720×1280 at 30 fps (~2.2 Mbit/s) |
| PiP | Home during the video call → PiP with the remote video; tap → full screen, call uninterrupted |
| Minimise | Panel across Home, DMs and a chat (header and composer uncovered); mute from the panel (notification then read "Voice call · muted"); tap → full screen |
| Notification | Tapping it reopens the full call screen from the minimised state; Stop sharing works from it |
| Background voice | Home during a voice call: no PiP, `CallService` in the foreground, remote audio still arriving and the bot still receiving the phone's microphone |
| Screen share | Orbit's explanation, then Android's consent (owner): started, bot received 864×1920; status line "sharing your screen", banner with Stop, all five controls ("Stop share"); minimised panel says sharing; no PiP while sharing; Stop in Orbit, Stop from the notification, and Android stopping it (with a "Screen sharing stopped" notice) all released the capture and kept the call |
| Group video | Three bots + phone: 2×2 grid, "4 in call", speaking outlines from LiveKit, People panel ("speaking · camera"), Speaker default, **Leave**; a bot leaving re-laid out the grid; the phone leaving kept the call active for the others; the last ones leaving ended it "empty" |
| Group voice | Avatars, speaking outlines, Speaker default (same as started-from-chat now) |
| Busy | A 1:1 ring while in a group call was declined as busy |
| Network | Wi-Fi off for ~10 s (owner): media stalled at 20:41:21, flowed again at 20:41:31, call never dropped (signalling stayed up over USB) |
| Cleanup | After every call: no ongoing notification, `MODE_NORMAL`, camera released, no projection, server call ENDED with the right reason and no open participant records |
| Large video, 25 MB server | The synthetic 56 s, 261 MB test video (picked by the owner): Original → "Can't send" (the owner's screenshot reproduced); Compress (1080p) → **"May be too big · About 34 MB after compressing, over the 24 MB limit. Compress (720p) should fit."** before compressing; Compress (720p) → compressed on the phone in 15 s and sent (18.8 MB); the bot verified the hash and decrypted an MP4 |
| Large video, 1 GB server | Original 248.8 MB: first refused by the new-account daily quota (now removed, §3a); after the change the retry uploaded and sent (260.9 MB chunked ciphertext). The bot can't read the chunked format; receiving it needs a second Orbit phone |

**Defects found on the phone and fixed in this branch (rebuilt and re-checked):**

1. A stale ongoing-call notification stayed after the other person hung up: the "Reconnecting…" update raced the
   service's stop. Update and stop are now serialized, and the notification is swept when the call resets.
2. A chat keyboard open when a call came in pushed Accept/Decline up over the caller's name. The call screen now closes
   the keyboard and lays out against the system bars only.
3. Every answered incoming call was logged as **missed** (answered was recorded only when someone joined after you).
4. The chat's "call in progress · Join" banner stayed after the call ended (it changed only on a list reload); it now
   follows the call's events.
5. The microphone level read "silent" while speech was sent (one `audioLevel` sample every 2 s); now averaged.
6. A "Weak connection" chip ran under the top-corner preview; a bottom-corner preview could sit over the sharing banner.
7. "24 MB" shown for a 25 MB limit (truncation); now rounded.
8. Upload quotas (owner's decision) and the in-flight cap below one 1 GB file (§3a).

**Still not verified on a device:** two people talking (audio quality as heard), a Bluetooth headset, landscape, a real
mobile/VPN network, receiving a chunked file on a second Orbit phone, and the production servers (nothing deployed).
Observed but not explained: twice, HONOR's translator app opened at the moment a screen share started, and once the
system ended the share 2 s later; Orbit handled it (notice, no restart).

## 10. Git status

Committed on `claude/orbit-ui-003` under the owner's name and pushed to GitHub (see the commit list in the hand-over).
Not edited: the files the orbit-2-mobile worktree holds uncommitted (`AccountGate`, `MigrationWindow`,
`server/src/accounts/config.ts`, `server/src/app.ts`, `HomeViewModels`, `WorkspaceHomeRules`, the version in
`build.gradle.kts`), except two small overlaps: `deploy/.env.example` (this branch's quota lines; their edits there
are other lines) and the `VERSION` line in `server/src/app.ts` (0.4.1 here). The main checkout wasn't touched.
Server version 0.4.1 (`package.json`, `app.ts` and the compose image tag, which still said 0.3.0).
