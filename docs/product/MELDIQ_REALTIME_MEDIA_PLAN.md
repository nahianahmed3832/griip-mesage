# Meldiq realtime media: calls, voice channels, screen sharing, game streaming (2026-10-21)

Master prompt §12. Root causes and fixes found so far are in `docs/MELDIQ_CALL_BUGS_2026-10-20.md`. Tile layout is
in `docs/GROUP_CALL_TILES.md`. Voice channels are in `docs/VOICE_ROOMS.md`. Encryption is in `docs/CALLING_AND_E2EE.md`.
This file is the plan and the honest status.

## 1. Architecture as it is

- **SDK:** LiveKit Android 2.29.0, `livekit-client` 2.22.3 on the web, and LiveKit server v1.13.10 self-hosted on the
  VPS (Docker; UDP 7882, TCP 7881; `rtc.griipbuddy.cloud` for signalling).
- **End-to-end encryption:** LiveKit frame encryption with per-call keys sent inside Meldiq's encrypted messages. The
  server never has them.
- **Camera:** 1280×720, 30 fps, simulcast (720p, 360p, 180p), VP8. Receivers use adaptive stream to pick a layer.
- **Screen share:**
  - capture at most 1920×1080 (aspect kept), 15 fps, up to 2.5 Mbit/s, one layer;
  - "maintain resolution", so text stays sharp;
  - the capture follows display rotation (`ScreenShareRotation`).
- **Audio:**
  - Opus 48 kbit/s with DTX and RED;
  - WebRTC noise suppression, AEC3, AGC and high-pass filter;
  - the phone's own noise suppressor is off, because two suppressors in a row muffled speech (`CallMediaPolicy`).
- **Voice channels:** server-enforced `voice.connect` and `voice.speak`. Without `voice.speak` the person listens only
  (the token can't publish). New 2026-10-21: losing `voice.connect` (a timeout, or a changed permission) removes the
  person from the live call (`callAdmission`).

## 2. Camera switching and the remote picture (§12.2)

| Question from the brief | Finding (code, 2026-10-20) | Status |
|---|---|---|
| Which camera is selected; does the switch succeed | LiveKit `switchCamera` through `CameraCapturerUtils`. A second tap during a switch aimed at the wrong camera, because LiveKit updates the track's position only on the new camera's first frame | Fixed: one switch at a time, confirmed within 3 s, logged |
| Does the local preview show the selected camera | Yes. But the preview was **always mirrored**, so with the back camera the picture moved the opposite way to the phone. That was the reported "movement is wrong" | Fixed: only the front camera is mirrored, decided from the position LiveKit reports |
| Does the receiver get the current track | Yes. Mirroring is local drawing only, which is why the receiver always looked right | No change needed |
| Orientation, crop | Camera frames carry rotation; the receiver fits without cropping | — |
| Lifecycle, camera contention | The camera is released when the call ends or the app's camera permission is revoked | Check on the phone |

**Device validation is still to do.** The fixes are in the code and unit-tested (`CallMediaPolicyTest`). The owner's
HONOR ELI-AN00 run is listed in `MELDIQ_CALL_BUGS_2026-10-20.md` ("Phone test still to run"). No device was connected
in this session.

## 3. Quality and latency (§12.3)

- **Already done:**
  - simulcast with adaptive stream;
  - degradation preferences chosen per source (camera balanced, screen maintains resolution);
  - DTX and RED;
  - parallel call setup (the caller sends the key alongside joining, and the callee waits for the key while joining).
    It is timed by `CallTimeline`, but the before and after numbers are not measured yet.
- **To measure on phones**, using LiveKit stats as the source (`CallMediaPolicy` already reads the outbound layer):
  - time to first remote audio;
  - packet loss and jitter;
  - frozen frames;
  - CPU and battery on a 10-minute call;
  - each of these on Wi-Fi and on mobile data from Bangladesh to the Singapore-region server.
- **Audio-first under bad networks:** LiveKit's adaptive stream drops video layers first. A "low data" setting that
  turns off incoming video is next (P2).

## 4. Group calls and voice channels (§12.4)

Join and leave, participant identity, mute and camera states, tiles (`GROUP_CALL_TILES.md`), raise hand, host
moderation, call history and reconnect are implemented. Phone-verified on 2026-10-21 (`MELDIQ_DISCORD_REFERENCE_AUDIT.md`
§5):
- voice channel speak and listen-only, with the bot as the second participant.

Still to verify: multi-person video (3 or more real phones).

## 5. Screen sharing and game streaming (§12.5)

Today's screen share is tuned for documents: 15 fps, sharp text. A game needs the opposite: frame rate before
sharpness. Plan (status: **specified, not built**):

| Piece | Design |
|---|---|
| Choice | When sharing, "Documents" (today's profile) or "Game / video": 720p at 60 fps where the encoder can (else 30), 4–6 Mbit/s, `maintainFramerate` degradation, one layer, H.264 with the hardware encoder preferred (VP8 software at 60 fps costs too much CPU on mid-range phones) |
| System audio | Android 10+ `AudioPlaybackCapture` from the same MediaProjection consent, published as a second audio track ("Game audio"). Apps that opt out of capture are silent, as the OS rules require. The microphone stays a separate track |
| Viewer control | Viewers pick the screen share's quality (auto / data saver). Adaptive stream already lowers it when the view is small |
| Server | LiveKit on a dedicated EC2 instance (`MELDIQ_AWS_ARCHITECTURE.md`) before game streams are offered widely: 6 Mbit/s per stream times the viewers is egress the shared VPS can't carry |
| Desktop | Real PC game streaming needs a desktop capture client. The web's `getDisplayMedia` captures a window or tab at up to 30–60 fps, with tab audio in Chromium only |
| Consent | Every capture starts from the OS consent dialog and shows the OS indicator. Nothing is captured in the background |

**Claim, stated plainly:** Meldiq has ordinary Android screen sharing that keeps landscape games fully visible. It does
**not** have a game-streaming mode yet.

## 6. Media deployment (§12.6)

Self-hosted LiveKit on the shared VPS (1.5 CPU, 1 GB limit) is fine for a beta. Next:
1. Measure the real path from users to the server: latency, loss, and whether TURN/TCP gets used.
2. Move LiveKit to a dedicated EC2 instance with an Elastic IP, the full UDP range and TURN/TLS on 443.
3. Compare with LiveKit Cloud before high concurrency.

Media never goes through an HTTP load balancer.

## 7. Acceptance status (§12.7)

| Check | Status |
|---|---|
| Front and back camera switching | Fixed in code, unit-tested; **phone pending** |
| Correct local preview and remote picture after switching | Fixed in code; **phone pending** |
| Stable video while moving the phone | **Phone pending** |
| Orientation and mirroring | Fixed in code; **phone pending** |
| Group participants rendered | Implemented; 3+ phones **pending** |
| Screen share received | Implemented (bot checks frames); landscape game fix **phone pending** |
| Quality under realistic networks | **Not measured** |
| Interruption and reconnect | Implemented; **not measured** |
| Permissions and lifecycle | Voice permissions server-tested; timeout eviction server-tested (2026-10-21) |
| Game streaming | **Specified only** |
