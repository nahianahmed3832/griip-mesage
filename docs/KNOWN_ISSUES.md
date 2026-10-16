# Known issues & limitations

## Fixed during testing
- **Remote camera video and screen share never appeared; every video tile was black** (tester reports, fixed 2026-10-16).
  - Cause: `VideoTile` released its own renderer straight after creating it. A `DisposableEffect` keyed on renderer
    state was first keyed while the state was `null`, but its body read the renderer that the view factory had already
    set. The next recomposition disposed that effect, which called `release()` on the live renderer. Frames still
    decoded (stats showed rising `framesDecoded`, stream ACTIVE, E2EE OK) but the released EGL renderer dropped them.
  - The same cause hid the self-preview, remote cameras and remote screen shares, on every receiving Android device.
    Sending was never affected: peers received the phone's camera and screen share throughout.
  - Fix: `VideoSinkBinding` owns the renderer. The sink is attached only while the screen is started, a track switch
    removes the old sink first, and the renderer is released once, from `AndroidView(onRelease)`, after the sink is removed.
  - Unit-tested (`VideoSinkBindingTest`, 8 tests) and verified on the phone (`TESTING.md` §5a).
- **A received screen share was cropped instead of fitted.** The FIT scaling never took effect, because the renderer only
  letterboxes when measured with loose constraints. Fitted tiles are now centred in a `Box`.
- **Your own screen share filled your "You" tile in group calls**, so the share captured itself recursively and loaded
  the encoder. The "You" tile now always shows your camera.
- **Native WebRTC crashes (SIGSEGV)** on a physical phone.
  - One happened when the screen-share consent dialog covered the call, the other on the audio thread after a call ended.
  - The cause was freeing native call objects while video sinks and audio threads still used them.
  - Fixed with ordered teardown and lifecycle-aware sinks. Re-tested on 2026-10-16: no crash in six calls, including two
    phone screen shares with the consent dialog covering the call. One different crash remains open (below).
- **The floating self-preview overlapped the "You" tile in grid (3+ participant) layouts.**
- **Every call crashed with `UnsatisfiedLinkError`.** The E2EE key provider was created before WebRTC's native library had loaded.
  The fix calls `LiveKit.init()` first, and native init errors now show an error instead of crashing. Verified on the phone.
- **A later `call_invite` could replace a call's media key.** The first key for a call now wins.
- **Low contrast in dark mode:** white text on light-teal hero cards and buttons. Fixed with dedicated dark-mode action tokens.
- **A sent message's text came back in the composer as a stale draft.** The draft is now cleared on send and restored only when the conversation first opens.
- **A config bug made `QUEUE_TTL_SECONDS` and other integer settings ignore the environment passed in.** Caught by the expiry test and fixed.

## Found by the 2026-10-16 audit (open unless marked)

Details and evidence: `FEATURE_INVENTORY.md`. Fix plan: `FEATURE_ROADMAP.md`.

- **Fixed (server, not yet deployed):** demotion to Guest kept all channel access (P1).
- **Open:**
  - ~~Pinned identity keys aren't enforced (T1.2).~~ Enforced 2026-10-16: a changed key pauses messaging until the user accepts it. The device UI check is pending.
  - ~~Removed members stay in live calls (P3, T1.3).~~ Fixed on the server 2026-10-16, **not yet deployed** (`RELEASE_PLAN.md`).
  - Any local error while processing a delivery discards the message (T1.4).
  - Messages from a device revoked before delivery are lost (P7, T1.5).
  - ~~Open registration with no upload quotas on a shared VPS (P5, T1.6).~~ Fixed on the server 2026-10-16 (invite-only registration, streamed uploads with quotas, container CPU and process limits). **Not yet deployed**: it needs an Android build with an invitation-code field first (`RELEASE_PLAN.md`).
  - ~~Presence is readable by anyone (P4, T1.7).~~ Fixed on the server 2026-10-16, **not yet deployed**.
  - ~~The activity feed leaks other branches' team and channel names (P2, T1.8).~~ Fixed on the server 2026-10-16, **not yet deployed**.
  - Erasing a device doesn't revoke it (T1.9).
  - Deleted messages leave decrypted files on the device (T1.10).
  - Backlog and live deliveries interleave and duplicate (P6, T2.1).
  - Realtime frames can be dropped under load (T2.2).
- The release (R8) build compiles but has never been run on a device.

## Calls (open, 2026-10-16)
- **One native abort (SIGABRT) on WebRTC's `signaling_thread`.** It happened at 02:50 on the test phone, after the crash-fix
  commit, with the app in the background 35 s after start. The frames sit in the E2EE `FrameCryptor` observer JNI path.
  - Probable cause, not confirmed: LiveKit 2.29.0's `E2EEManager` runs `checkNotNull(publication.track)` inside the frame-cryptor
    state callback. If that callback arrives after the track was cleared (unpublish, screen-share stop, teardown), the
    exception is thrown inside a native callback and WebRTC aborts.
  - Not reproduced in six calls on 2026-10-16. A fix needs an SDK change or upgrade.
- **Android ↔ Android on two physical phones isn't verified.** Only one phone was available. Both directions were
  verified against the Node peer, which uses the same SFU, codec (VP8) and E2EE scheme.

## Security gaps (see SECURITY.md §4)
- There is no forward secrecy or post-compromise security for messages (custom `griip-envelope-v1`, not Signal or MLS).
- Group membership is asserted by the server, and keys are trusted on first use: a substitution *before* first contact isn't detected. A *later* key change now pauses messaging with that person until the user reviews and accepts it (T1.2).
- The call media key isn't rotated when participants join or leave.
- Screen-share E2EE is confirmed (2026-10-16). A wrong-key participant decoded 0 screen-share frames while the correct-key one decoded 2,262 (`TESTING.md` §5a).
- The debug APK allows `http://` servers, with a warning.

## Functional limitations
- **No push wake-up.** If the app is force-stopped or killed, it receives no messages or calls until reopened. The "stay connected" foreground service
  (on by default) keeps the socket alive in the background, but OEM battery managers (MagicOS included) may still kill it. Whitelist the app in
  battery settings for reliable background delivery. FCM or UnifiedPush isn't implemented.
- **No TURN server.** Calls need UDP 7882 or TCP 7881 to reach the VPS. Networks that only allow 443 (some corporate Wi-Fi) will fail to
  connect media, while messaging still works.
- **No message history migrates between devices.** A newly linked or recovered device starts with empty history (by design: local-first).
- **No conversation history fetch from the server.** If a device is offline for more than 7 days, it misses messages permanently (`RETENTION.md`).
- Read receipts are sent only in DMs and groups of 20 or fewer. Channels show "sent" and no per-reader receipts.
- Large channels fan out one envelope per member device. That's fine for tens to hundreds of members but isn't optimised for thousands.
- Attachments are limited to 25 MB and kept for 7 days. Uploads and downloads aren't resumable (an interrupted transfer restarts). Sent and received files are
  stored in app-private storage, not inside SQLCipher.
- There's no in-app QR *scanner* yet. Invites can be shared as a QR code image, a text code, or a `griip://invite/…` link.
- Group calls with three or more participants haven't been exercised on real devices. Capacity on the shared 2-vCPU VPS hasn't been benchmarked.
- Audio route selection is limited to a speaker toggle. Bluetooth and wired devices follow LiveKit's AudioSwitch defaults.
- Cold start on a debug build is about 2 s on the test phone. A release (R8) build hasn't been built yet.
- Call records (`calls`, `call_participants`) are never pruned on the server.

## Operations
- LiveKit warns that the host's UDP receive buffer is small (425 KB, recommended 5 MB). Raising `net.core.rmem_max` is a host-wide change and was left alone.
- The VPS has no swap and about 3.6 GB of RAM available, shared with about 20 other containers.
- Observed on the shared server, but not part of this project: four Redis containers publish ports on `0.0.0.0`. They're currently protected only by the
  Hostinger firewall, and binding them to `127.0.0.1` is recommended. Several exited `griipbuddy-restore-*` projects and volumes are using disk space.
- Four `e2e.*` test accounts and one `griipbot*` test account exist on production. The `e2e.*` accounts are disabled.

## Not done in this session
- Full screenshot-based visual review of every screen. Welcome, Home, Conversation and Call were reviewed on real hardware.
- An instrumented Android UI test suite. Verification was manual, on the phone with the bot as the second participant.
- Commits. The git repository is initialised but nothing has been committed.
