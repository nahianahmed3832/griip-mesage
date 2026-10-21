# Voice, video, camera switching and screen sharing: audit (2026-10-21)

The owner's additional brief, parts 6–9. Batches: **B10** (voice and video, camera) and **B11** (screen sharing and game
streaming). Earlier work: `docs/MELDIQ_CALL_BUGS_2026-10-20.md` (root causes and fixes, all "awaiting phone"),
`MELDIQ_REALTIME_MEDIA_PLAN.md`, `docs/CALLING_AND_E2EE.md`, `docs/GROUP_CALL_TILES.md`.

## 1. What could be inspected in Discord, honestly

Discord's calls weren't observed in use. Starting a call, joining a voice channel or sharing a screen would ring or
show the owner to real people, and the brief forbids that without a designated test setup. These were observed:

| ID | Where | Control / content | Ev |
|---|---|---|---|
| CALL-CTRL-001 | DM and group header | Start Voice Call, Start Video Call (separate buttons) | O `2-dm-header`, `2-group-dm-header` |
| CALL-CTRL-002 | Friend row ⋯, DM menu, member menu | Start Video Call, Start Voice Call / Start a Call | O `friend-row-menu`, `2-dm-list-menu`, `2-group-member-menu` |
| CALL-CTRL-003 | Voice channel row | Hover: Open Chat, Invite; click joins at once; right-click: Invite to Voice, Open Chat, Hide Names, Mute Channel | O `voice-row-hover`, `voice-channel-menu` |
| CALL-CTRL-004 | User panel | Mic toggle, deafen toggle, input and output menus (⌄) | O (toggles seen; menus not opened) |
| CALL-CTRL-005 | Settings › Voice & Video › Voice | Microphone and speaker selects; volume sliders; Mic Test with a level meter; Input Profile: Voice Isolation, Studio (no processing), Custom (advanced); automatic input sensitivity with a threshold bar | O `voice-video`, `voice-input-profile` |
| CALL-CTRL-006 | … › Camera | Test Video (local preview); Always preview video (preview before turning the camera on); camera select; Video Background: None, Blur, Custom, animated presets | O `camera-settings`, `camera-backgrounds` |
| CALL-CTRL-007 | … › Streaming | Show Stream Previews; advanced stream settings (attenuation of other audio while streaming) | O `streaming-sounds` |
| CALL-CTRL-008 | … › Sounds | Deafen, Undeafen, Mute, Unmute… each with Preview | O `streaming-sounds` |
| CALL-CTRL-009 | Event creation | "Voice Channel: hang out with voice, video, screenshare, and Go Live" | O `event-location` |

From Discord's documentation (not observed), not repeated here: in-call controls, Go Live quality options
(resolution and frame rate tiers), sharing a window or a screen on desktop, and sharing the screen and its audio on
mobile. The rows are in `DISCORD_FEATURE_AUDIT.md` H (CALL-01 to 10).

**To observe these:**
- the owner's go-ahead;
- two test accounts in a test server, with one of them on this Mac and one on a phone;
- then: voice and video calls, a voice channel, camera switching from the phone, a screen share from both, leaving
  and rejoining, and pulling the network.

## 2. Meldiq's calling engine (checked in the code)

| Layer | Implementation | Notes |
|---|---|---|
| Signalling and rooms | Meldiq server `routes/calls.ts` (call records, ringing, LiveKit tokens, admission with voice permissions and timeouts, eviction on removal), `livekit.ts`, LiveKit webhook | One room per call or voice channel |
| Media server | Self-hosted LiveKit 1.13.10 on the VPS, no TURN | **No TURN is a known reachability risk** on strict networks (media plan) |
| Android media | LiveKit Android 2.29.0: Opus 48 kbit/s with DTX and RED; camera 1280×720 at 30 fps with VP8 simulcast (180p, 360p, 720p); screen at most 1920×1080 at 15 fps, 2.5 Mbit/s (`call/CallMediaPolicy.kt`) | — |
| Encryption | Frame encryption with a per-call key sent in an end-to-end encrypted message (`FrameCryptorGuard.kt`, `MediaSecurityTracker.kt`, `CallVerification.kt` emoji check) | — |
| Controls | `ui/screens/calls/CallControls.kt`: Mute / Unmute, Camera, Flip camera, Audio output (route picker), Share / Stop share, Leave; raise hand; host moderation (`moderate`) | — |
| Layout | `CallTiles.kt` (group tiles), `LocalPreview.kt` (draggable self view), `MiniCallPanel.kt`, `CallPip.kt` (picture in picture) | — |
| Lifecycle | `CallService.kt` (foreground service), `IncomingCallNotifier.kt` (full-screen ring), `rejoin()` with attempts and a notice that a screen share has to be restarted after reconnecting (Android's capture consent is single use) | — |
| Diagnostics | `CallDiagnostics.kt`: every 6 s, sender and receiver frame size, fps, bitrate and `qualityLimitationReason`; `CallTimeline.kt` stage timings; audio route and level lines | No user-visible quality indicator yet |

### Compared with Discord

| Discord capability | Meldiq | Class | Batch |
|---|---|---|---|
| Separate voice and video call buttons | Same | KEEP | — |
| Mute and deafen anywhere (user panel) | Mute in the call; no deafen | BUILD deafen (B10) | B10 |
| Input profiles (voice isolation / studio) | Fixed processing (WebRTC NS, AEC3, AGC on; hardware NS off) | ADAPT: "Noise reduction: Standard / Off (music)" | B10 |
| Mic test with level meter; device selects | Route picker in calls; no pre-call test | BUILD pre-call check (mic level, camera preview) | B10 |
| "Always preview video" before turning the camera on | Camera starts straight away | BUILD preview step (optional) | B10 |
| Video backgrounds, blur | — | DEFER (P3, on-device segmentation costs battery) | — |
| Speaking indicators, participant list | Tiles with speaking ring | KEEP (verify on phones) | B10 |
| Reconnect | `rejoin()` with attempts | KEEP; measure (B10) | B10 |
| Stream previews, Go Live quality tiers, sharing with audio | 15 fps presentation-grade share | BUILD game mode (B11) | B11 |
| Hide names in voice channels | — | DEFER (P3) | — |

## 3. The camera problem: how B10 will trace it (not guessed)

**What the owner reported:**
- the back camera's picture moved the wrong way when the phone moved;
- switching cameras sometimes didn't change what the other person saw.

The fixes of 2026-10-20 address two causes found in code: the preview was always mirrored, and a quick second tap aimed
at the wrong camera. Neither fix has been checked on a phone. B10 follows the camera source through each stage and
records a verdict for each one:

| Stage | Question | How it is checked | Where |
|---|---|---|---|
| 1. Selection | Did the intended physical camera open (front or back, the lens id)? | Log the camera id, facing and capture size from LiveKit's `CameraCapturerUtils` on every switch; Camera2 metadata | `CallManager.flipCamera()` |
| 2. Local preview | Does the preview show that camera, mirrored only for the front camera? | `CallMediaPolicy.mirrorPreview` from the position LiveKit reports after the first frame; screenshot on the phone | `CallOverlay.kt`, `LocalPreview.kt` |
| 3. Published track | Is the same `LocalVideoTrack` still published after the switch, with frames from the new camera? Is there no second track or stale sender? | Log track sid, the capturer's `isFrontFacing`, sender frames encoded before and after the switch (`framesEncoded` keeps increasing, `frameWidth` matches the new camera) | `CallDiagnostics` (add the track sid and `framesEncoded`) |
| 4. Transport | Do frames reach the server? | LiveKit server room stats for the publisher (the owner's VPS, read only) | LiveKit admin API |
| 5. Remote render | Does the receiver render the current track: subscribed sid = published sid, `framesDecoded` increasing, frame size updated, no frozen frame? | Receiver lines from `CallDiagnostics`; screenshot on the second device | `CallTiles.kt`, `VideoSinkBinding.kt` |
| 6. Orientation | Is the picture upright on both ends in portrait and landscape, with the back camera? | Rotation metadata on frames (`VideoFrame.rotation`), receiver letterboxing | — |
| 7. Lifecycle | Does the camera survive background, PiP, screen lock and the permission dialog, and is it released when you leave? | Logs and Android's camera-in-use indicator after hang-up | `CallService.kt`, `teardown()` |
| 8. Group | With three people, does each tile show its own person's current track after switches and joins? | Three devices, or two phones plus the emulator | — |

**Test rig for B10:**
- the owner's phone, a second Android phone or the emulator (its virtual front and back cameras give a scene that
  moves), and a test account pair on a local or staging server;
- logs read from both ends (`adb logcat -s CallDiag`).
- A unit test covers the switch state machine (one switch at a time, confirmed by the first frame).
- A diagnostics line `camera switch: facing … id … track … frames …` is added so a phone test can show which stage
  fails.

## 4. Screen sharing and game streaming (B11)

| Stage | Today | B11 work |
|---|---|---|
| Consent | MediaProjection consent per share; ends with the room on reconnect (and the app says so) | Keep; also handle the Android 14 single-app-capture choice and say so |
| Foreground service | `CallService` with the media projection type | Verify the type on Android 14+ (crash otherwise) |
| Capture size and rotation | Display-following rotation (`ScreenShareRotation.kt`), ≤ 1920×1080 | Keep |
| Encoding | 15 fps, 2.5 Mbit/s, VP8, presentation-grade | **Game mode:** 30 or 60 fps where the encoder and network allow, H.264 hardware when available, bitrate up to the uplink estimate, `degradationPreference = MAINTAIN_FRAMERATE` for games and `MAINTAIN_RESOLUTION` for documents |
| Audio | None | Android 10+ `AudioPlaybackCapture` of game audio, with consent, mixed into a second audio track; apps that forbid capture are respected |
| Camera and screen together | Allowed in code | Verify that no duplicate or stale track is published when switching between camera and screen |
| Viewer | Tile with letterbox, fit not crop | Full-screen viewer, quality label, "stream ended" state |
| Measurement | `CallDiagnostics` | End-to-end latency (timestamp overlay test), fps received, freezes per minute |

**Acceptance for B11:**
- Another device in the same call receives the shared screen at the stated frame rate (from the receiver's
  `CallDiagnostics` lines), in both orientations.
- The share stops cleanly when you stop it, leave, or lose the network.
- Rejoining doesn't republish an old track.
- All of this is on the owner's phone, not only the emulator.

## 5. What B10 and B11 need from the owner

- Two Meldiq test accounts (or permission to create them on the local or staging server).
- A second Android device, or acceptance of the emulator as the second participant for first runs.
- The physical phone test after each batch (camera switching, rotation and screen capture can't be verified by a
  build).
- Optional: a Discord test server with two accounts, to observe Discord's call screens (§1).
