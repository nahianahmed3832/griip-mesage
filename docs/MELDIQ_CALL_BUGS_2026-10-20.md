# Meldiq call and channel bugs (2026-10-20)

The owner's critical bug list (voice and video quality, camera switching, screen sharing, loudness, workspace
channels, call setup time), worked in the round 7 worktree on top of slice A. Stack: LiveKit Android 2.29.0
(WebRTC, VP8 simulcast, Opus), self-hosted LiveKit SFU on the VPS (no TURN), Meldiq server 0.7.1 + these changes.
**Nothing here is phone-verified yet**: every item is "fixed in code, awaiting physical-device validation" until the
phone test below runs.

| # | Bug | Root cause found | Fix | Tests | Status |
|---|---|---|---|---|---|
| 1 | Voice clarity | WebRTC's JavaAudioDeviceModule turns on the phone's **hardware noise suppressor** whenever the phone has one, and WebRTC's own suppressor also runs: two suppressors in a row eat into speech (muffled). Codec settings were already sound (Opus 48 kbit/s, DTX, RED; read from the SDK) | Hardware noise suppressor off; WebRTC NS, AEC3, AGC and high-pass filter on, set explicitly; typing-noise detection off; Opus settings explicit | `CallMediaPolicyTest` (policy constants) | Fixed in code, awaiting phone |
| 5 | Too quiet | Same double suppression lowers speech level. Also: the phone's call-volume stream is separate from media volume and is often low; Meldiq measured it (Connection details) but never said so on the call screen | As bug 1; plus a one-time notice on the call screen when call volume is at or below 30 % ("press volume-up"). Meldiq never changes the volume itself | — | Fixed in code, awaiting phone (needs a loudness comparison on speaker, earpiece and a headset) |
| 2 | Camera quality | **Not determined from code.** Settings are already reasonable: 1280×720 at 30 fps, simulcast 180p/360p/720p, 1.7 Mbit/s top layer, adaptive stream on the receiver. Possible causes (bandwidth ramp-up after joining, CPU-limited encoding, the receiver's chosen layer) can only be told apart from the sender's `qualityLimitationReason` and the receiver's frame size | No blind change. The call already logs sender and receiver frame size, fps, bitrate and the limitation reason every 6 s (`CallDiag`); the phone test reads them | — | Needs measurement on the phone |
| 3 | Back camera preview moves wrongly (host only) | The local preview was **always mirrored** (`mirror = isLocal`), including the back camera, so its picture moved opposite to the phone. Mirroring is local drawing only, which is why the receiver never saw it. A second tap during a switch also aimed at the wrong camera: LiveKit updates the track's camera position only on the new camera's first frame | Mirror only your own front camera (`CallMediaPolicy.mirrorPreview`), from the position LiveKit reports, so it changes with the new camera's first frame. One switch at a time, confirmed within 3 s and logged | `CallMediaPolicyTest` | Fixed in code, awaiting phone |
| 4 | Shared game not fully visible | LiveKit resizes the screen capture only from **motion-sensor** events (`OrientationEventListener`). A game that turns the display to landscape by itself (the HONOR has auto-rotate off; or the phone already sideways) sends none, so landscape frames went into a portrait-sized capture. The receiver already letterboxes (fit, never crop) | `ScreenShareRotation` follows the display itself (`DisplayManager` reports every rotation) and resizes the capture to the display's shape, same size limits (1920×1080 max, aspect kept). Capture consent is unchanged | `CallMediaPolicyTest` (landscape 2664×1200 → 1920×864) | Fixed in code, awaiting phone |
| 6 | Members can't see or post in channels | Server: org-wide public channels created without an explicit `autoJoin` were stored as **opt-in**, and neither the Android nor the web app sends `autoJoin`. So people already in the workspace when a channel was made, and everyone who joined later, were never added; Home lists only joined channels, so to them the channels didn't exist. Tapping a channel you hadn't joined in Workspace › Channels also did nothing (only the small Join button worked) | Public channels are auto-joined unless the creator opts out (`autoJoin: false`). Existing channels: admin CLI `channel-access-repair` (dry run, then `--yes`) marks the old opt-in public channels auto-joined and adds eligible members, skipping anyone who left a channel themselves. App: a tap on an unjoined channel joins it. Private channels, guests and announcement rules unchanged | `channel_access.test.ts` (10): new member sees and posts, later channels, opt-in join, live message and attachment, private stays hidden (list, join, read, upload), invitation opens it, outsiders see nothing, members can't manage, guest demotion and removal, the repair | Fixed and server-tested; app awaiting phone |
| 7 | Calls take 3–5 s to connect | Every step ran one after another. Caller: create the call › **send the encrypted key message** › create the media engine › join the room › publish the mic › publish the camera. Callee: **ask to join › then start waiting for the key**, checking only every 250 ms › engine › join › mic › camera | Caller sends the key message alongside its own connection (it already has the key; only the callee needs it). Callee asks to join and waits for the key at the same time, checking every 50 ms. Mic and camera publish in parallel (LiveKit locks each source separately). Stage timings are logged, names and milliseconds only, up to the first remote audio bytes (`CallTimeline`) | `CallTimelineTest` | Fixed in code; **before/after timings not measured yet** (the baseline needs the old build on the same rig) |

## Files

- Server: `src/routes/orgs.ts` (auto-join default), `src/channelRepair.ts` + `src/admin.ts` (`channel-access-repair`),
  `test/channel_access.test.ts`.
- Android: `call/CallManager.kt` (audio set-up, camera switch, screen-share rotation, timings, parallel set-up),
  `call/CallMediaPolicy.kt`, `call/ScreenShareRotation.kt`, `call/CallTimeline.kt`,
  `ui/screens/calls/CallOverlay.kt` (mirroring), `ui/screens/workspace/WorkspaceScreen.kt` (tap to join), tests.
- Not changed: LiveKit server config, codecs, the media stack, TURN, any production resource. Web client: its own
  session (the same preview-mirroring and audio questions apply there).

## Checks run

- Server: `tsc --noEmit` clean; `npm test` 62 files, **600 passed**, 10 skipped.
- Android: `testDebugUnitTest` **490 passed**, 0 failed; `compileDebugKotlin` clean.

## Phone test still to run (HONOR ELI-AN00, local test server, never the emulator)

1. Voice call on earpiece, speaker and a headset: clarity, echo, loudness; read `audio recv … mic … volume` lines.
2. Video call: picture sharpness after 10–20 s; read the `stats send cam=… limited by …` lines (bug 2 decision).
3. Flip to the back camera and back, quickly and slowly: preview steady, not mirrored on the back camera; receiver correct.
4. Share the screen, open a landscape game: the receiver sees the whole game; log shows "screen capture resized".
5. Call timings: `call timing (caller)` and `(callee)` lines, compared with the same rig on the Play build.
6. Workspace: a member added after channels were made sees and posts in them; a private channel stays hidden.

## Production

Needs the server deploy (API with the auto-join default) and, after a backup, `channel-access-repair` dry run
reviewed by the owner before `--yes`. The Android fixes ship with the next Play build.
