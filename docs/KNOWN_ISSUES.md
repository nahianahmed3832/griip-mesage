# Known issues & limitations

## Fixed during testing
- **Native WebRTC crashes (SIGSEGV)** on a physical phone.
  - One happened when the screen-share consent dialog covered the call, the other on the audio thread after a call ended.
  - The cause was freeing native call objects while video sinks and audio threads still used them.
  - Fixed with ordered teardown and lifecycle-aware sinks. **The re-test of screen share on the fixed build is still pending.**
- **The floating self-preview overlapped the "You" tile in grid (3+ participant) layouts.**
- **Every call crashed with `UnsatisfiedLinkError`.** The E2EE key provider was created before WebRTC's native library had loaded.
  The fix calls `LiveKit.init()` first, and native init errors now show an error instead of crashing. Verified on the phone.
- **A later `call_invite` could replace a call's media key.** The first key for a call now wins.
- **Low contrast in dark mode:** white text on light-teal hero cards and buttons. Fixed with dedicated dark-mode action tokens.
- **A sent message's text came back in the composer as a stale draft.** The draft is now cleared on send and restored only when the conversation first opens.
- **A config bug made `QUEUE_TTL_SECONDS` and other integer settings ignore the environment passed in.** Caught by the expiry test and fixed.

## Security gaps (see SECURITY.md §4)
- There is no forward secrecy or post-compromise security for messages (custom `griip-envelope-v1`, not Signal or MLS).
- Group membership is asserted by the server, and keys are trusted on first use. Users must compare safety numbers to detect a key-substitution attack.
- The call media key isn't rotated when participants join or leave.
- Screen-share E2EE hasn't been confirmed with a wrong-key test. The participant encryption status stayed true while sharing.
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
