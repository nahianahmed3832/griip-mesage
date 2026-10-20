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
  - ~~Any local error while processing a delivery discards the message (T1.4).~~ Fixed 2026-10-16: acknowledgement only after a local commit, with bounded retries and a local set-aside.
  - ~~Messages from a device revoked before delivery are lost (P7, T1.5).~~ Fixed (remediation round 1, 2026-10-20): the key directory lists devices revoked within the queue lifetime under `revokedDevices`, and the apps accept such a device's signature only on envelopes the server accepted at or before its `revokedAt`. Server, Android and reference client; the web app's part is in meldiq-web. **Not deployed; no device check yet.**
  - ~~Open registration with no upload quotas on a shared VPS (P5, T1.6).~~ Fixed on the server 2026-10-16 (invite-only registration, streamed uploads with quotas, container CPU and process limits). **Not yet deployed**: testers need an app build with the invitation-code field (added 2026-10-16) before it goes live (`RELEASE_PLAN.md`).
  - ~~Presence is readable by anyone (P4, T1.7).~~ Fixed on the server 2026-10-16, **not yet deployed**.
  - ~~The activity feed leaks other branches' team and channel names (P2, T1.8).~~ Fixed on the server 2026-10-16, **not yet deployed**.
  - ~~Erasing a device doesn't revoke it (T1.9).~~ Fixed 2026-10-16: it revokes first and asks before erasing if the server can't confirm. The device check is pending.
  - ~~Deleted messages leave decrypted files on the device (T1.10).~~ Fixed 2026-10-16.
  - ~~Backlog and live deliveries interleave and duplicate (P6, T2.1).~~ Fixed 2026-10-16. The server change (not yet deployed) keeps live items behind the backlog. The app holds controls that arrive before their target.
  - ~~Realtime frames can be dropped under load (T2.2).~~ Fixed 2026-10-16 (bounded ordered inbox; a stall reconnects instead of dropping frames).
  - ~~Any member could set the media key for someone else's call (D8).~~ Fixed in the app 2026-10-16 (T5.2): keys only from the call's creator. Device check pending.
  - ~~Thread view showed replies from other conversations; reactions, pins, edits and deletes in announcement channels changed only this phone.~~ Fixed in the app 2026-10-16 (Phase 14). Device check pending.
- The release (R8) build compiles but has never been run on a device.

## Calls (open, 2026-10-16)
- **Native abort (SIGABRT) on WebRTC's `signaling_thread`: mitigated in the app, never reproduced on demand, not proven fixed.**
  It happened once, at 02:50 on 2026-10-16 on the test phone, with the app in the background 35 s after start.
  - **Suspected upstream failure (LiveKit Android 2.29.0):**
    - The tombstone analysis places the abort in WebRTC's JNI exception check, right after the Java
      `FrameCryptor.Observer.onFrameCryptionStateChanged` threw.
    - LiveKit's own observer only logs and emits `RoomEvent.TrackE2EEStateEvent`. Its bytecode, checked again, does run
      `checkNotNull(publication.getTrack())` inside that callback.
    - `RemoteParticipant.unpublishTrack` clears a remote track while its cryptor can still report:
      - a state change already queued on `signaling_thread` when the SDK disposes the cryptor (remote unpublish or leave);
      - on disconnect, `Room.cleanupRoom()` drops its `E2EEManager` before unpublishing, so the remote cryptors are never
        disposed and keep reporting until the peer connection closes.
    - Local tracks are never cleared, so stopping your own screen share can't trigger it.
    - This is a strong hypothesis, not a confirmed cause: the abort has never been reproduced. There's no upstream fix;
      2.29.0 is the newest release, and LiveKit's `main` still has the same observer and teardown order.
  - **App-side mitigation (`FrameCryptorGuard`):**
    - When the SDK reports a track (`TrackSubscribed`/`TrackPublished`, posted only after it has created the cryptor), and
      again before disconnecting, the guard gives each cryptor an observer that can't throw. That observer ignores
      callbacks for cleared tracks and feeds the E2EE badge.
    - Encryption is unchanged. `setObserver` swaps only the status observer, not the cryptor, key provider, key index or
      enabled flag.
    - Failures are still reported: missing-key, encryption, decryption and internal errors still turn the badge into
      `E2eeProblem`. Late callbacks and callbacks whose own handling failed are counted and logged in debug builds.
    - The guard can't crash the app itself (hardened 2026-10-16). Errors while copying the SDK's cryptor map or swapping
      an observer are caught. In that case the SDK's own observer simply stays in place.
  - **Remaining uncertainty:**
    - The original crash was never reproduced on demand, and the stress test also passed without the guard. The stress
      runs don't prove the guard removes the crash.
    - **Protective path exercised on a device (2026-10-16, `TESTING.md` §5d):**
      - In 2 of 2 wrong-key calls (R3, R3b), the bot's microphone cryptor reported once during `disconnect()`, 350–440 ms after
        the guard's sweep, with its track already cleared. The guard dropped the callback and the app kept running.
      - LiveKit's own observer would have evaluated `publication.track` at that point. That cryptor had never changed state
        during the call (wrong-key failures are silent), so its first change came during teardown.
      - The 10 stress calls and all valid-key calls counted 0 late callbacks.
      - Whether the *unpatched* build aborts in that wrong-key teardown hasn't been tested. It would confirm the cause.
    - A callback queued between the SDK creating a cryptor and the guard swapping its observer still goes to LiveKit's
      observer.
    - `FrameCryptor.setObserver` and the SDK's `dispose` aren't synchronised in WebRTC's Java code, and the SDK changes
      its cryptor map without a lock. A swap racing a disposal of the same cryptor (a track unpublished within
      milliseconds of being subscribed) can't be excluded app-side.
    - The guard reaches the cryptors through LiveKit's private `frameCryptors` field. `FrameCryptorGuardTest` fails if a
      LiveKit upgrade moves it; re-check then whether LiveKit fixed the observer, and remove the guard if so.
    - The hardened guard has run on the phone (2026-10-16) with no crash: 10 stress calls (`TESTING.md` §5c, *stable but
      unverified*) and 8 calls with the badge fix (§5d; 6 connected).
- **~~The badge said "End-to-end encrypted" while a peer's media couldn't be decrypted.~~ Fixed 2026-10-16 (D-17), with a remaining limit.**
  - Cause:
    - With LiveKit Android's default `failureTolerance` (-1), WebRTC drops a frame that fails to decrypt and reports nothing.
    - A wrong-key peer's cryptor therefore stays silent, and the old rule ("every state heard is OK") counted only the
      phone's own outgoing OK.
  - Fix: `MediaSecurityTracker`.
    - "End-to-end encrypted" now needs proof: every live track has encrypted or decrypted a frame (OK or KEY_RATCHETED),
      including at least one remote track.
    - A reported failure on a current track shows "Encryption problem". Anything else shows "Verifying encryption…".
  - Verified on the phone: a wrong-key peer now shows "Verifying encryption…" for the whole call (R3 in `TESTING.md` §5d).
  - **Limit:** a wrong key is shown as *not verified*, never as an explicit error, because the SDK gives no failure signal.
    `failureTolerance` stays at -1. With a tolerance of 0 or more, WebRTC counts failures for the whole call (a good frame
    doesn't reset the count) and, past the limit, drops all of that participant's media until a new key is set. That could
    break valid calls (D-17).
  - A video track that never sends a frame, for example one adaptive streaming paused before its first frame, also stays
    unverified until it does. Muted tracks need no proof.
- **The badge flickers for under a second when a peer switches key index.** Frames under the old and new index arrive
  interleaved, and WebRTC reports each change between missing key and OK (R2).
- **Joining a call's media room is slow and sometimes fails.** It takes 10–16 s from Accept to connected on the test phone (the
  same before and after 2026-10-16). In 2 of 18 calls on 2026-10-16 the LiveKit join timed out (`JOIN_FAILURE`) or stalled until
  the call ended. Not investigated; Wi-Fi to the phone was poor (60–75 ms LAN ping).
- **Open question (not investigated):** WebRTC's sender cryptor passes frames through *unencrypted* while it isn't enabled
  (`frame_crypto_transformer.cc`). Whether LiveKit ever sends media before enabling the cryptor (for example at track start)
  hasn't been checked.
- **Android ↔ Android on two physical phones isn't verified.** Only one phone was available. Both directions were
  verified against the Node peer, which uses the same SFU, codec (VP8) and E2EE scheme.

## Security gaps (see SECURITY.md §4)
- There is no forward secrecy or post-compromise security for messages (custom `griip-envelope-v1`, not Signal or MLS).
- Group membership is asserted by the server, and keys are trusted on first use: a substitution *before* first contact isn't detected. A *later* key change now pauses messaging with that person until the user reviews and accepts it (T1.2).
- The call media key isn't rotated when participants join or leave.
- Screen-share E2EE is confirmed (2026-10-16). A wrong-key participant decoded 0 screen-share frames while the correct-key one decoded 2,262 (`TESTING.md` §5a).
- The debug APK allows `http://` servers, with a warning.

## Functional limitations
- **No push wake-up in production yet.** If the app is force-stopped or killed, it receives no messages or calls until reopened. The "stay connected" foreground service
  (on by default) keeps the socket alive in the background, but OEM battery managers (MagicOS included) may still kill it. Whitelist the app in
  battery settings for reliable background delivery. Since 2026-10-16 the service restarts after a reboot or app update (not yet verified on a device).
  - Self-hosted UnifiedPush wake-ups were built and tested locally on 2026-10-17 (server and app, `BACKGROUND_DELIVERY.md`).
  - They need the ntfy container, DNS name and TLS route, which wait for owner approval, and a check on a physical phone.
  - A force-stopped app still gets nothing, as on any Android push.
- **No TURN server.** Calls need UDP 7882 or TCP 7881 to reach the VPS. Networks that only allow 443 (some corporate Wi-Fi) will fail to
  connect media, while messaging still works.
- **No message history migrates between devices.** A newly linked or recovered device starts with empty history (by design: local-first).
- **No conversation history fetch from the server.** If a device is offline for more than 7 days, it misses messages permanently (`RETENTION.md`).
- Read receipts are sent only in DMs and groups of 20 or fewer. Channels show "sent" and no per-reader receipts.
- Large channels fan out one envelope per member device. That's fine for tens to hundreds of members but isn't optimised for thousands.
- Attachments are limited to 25 MB and kept for 7 days. Downloads resume after an interruption (2026-10-16, T2.6; the server half isn't deployed yet). Uploads retry automatically but restart from zero. Sent and received files are
  stored in app-private storage, not inside SQLCipher.
- The in-app QR scanner (T4.2, 2026-10-16) hasn't been run on a device. Invites can also be shared as a QR code image, a text code, or a `griip://invite/…` link.
- A late joiner gets a running call's key only while one of the call creator's devices is online (T5.3). Otherwise joining is still refused after 10 s.
- Message search is a plain `LIKE` scan (no index). FTS was deferred: SQLite's `unicode61` tokenizer splits Bengali words at vowel signs, and FTS5 availability in SQLCipher on the phone is unverified (`FEATURE_ROADMAP.md` T4.3).
- Group calls with three or more participants haven't been exercised on real devices. Capacity on the shared 2-vCPU VPS hasn't been benchmarked.
- Audio route selection is limited to a speaker toggle. Bluetooth and wired devices follow LiveKit's AudioSwitch defaults.
- Cold start on a debug build is about 2 s on the test phone. The release (R8) build compiles but hasn't been run on a device.
- ~~Call records are never pruned on the server.~~ Ended calls older than 90 days are pruned (2026-10-16, not yet deployed).

- **Server profiles (2026-10-16):** only the active server is connected. Messages and calls for other servers on the phone wait on those
  servers (messages ≤ 7 days) and arrive after switching back; there are no notifications for inactive profiles. Appearance and
  behaviour settings are shared by all profiles. (Since federation, one account can already reach people on other federated servers.)
- **Federation v1 (2026-10-16):** DMs, receipts, attachments, voice messages and 1:1 calls only; groups, channels, typing and presence stay
  server-local; exact-address lookup only (no global search); no account migration between servers; no sealed sender; the host's media
  server sees a remote callee's IP. Not deployed to production; the Docker variants of the core node and of federated backups weren't run.
- **Voice messages (2026-10-16):** not offered inside threads (they'd go to the main conversation, like files); no waveform, speed control or
  transcription. Recording on the phone hasn't been confirmed yet (`TESTING.md` §5f); receiving and playing were verified on the emulator.
- **Call reachability (2026-10-16):** ring recovery, `alerted` and busy need this release's server (not deployed to production). Against the
  current production server the app falls back to the old behaviour: `/alerted` returns 404 (ignored) and a busy device's decline is a plain decline.
- A second incoming 1:1 call while the phone is already ringing is declined as busy (there's no call waiting).

## Operations
- LiveKit warns that the host's UDP receive buffer is small (425 KB, recommended 5 MB). Raising `net.core.rmem_max` is a host-wide change and was left alone.
- The VPS has no swap and about 3.6 GB of RAM available, shared with about 20 other containers.
- Observed on the shared server, but not part of this project: four Redis containers publish ports on `0.0.0.0`. They're currently protected only by the
  Hostinger firewall, and binding them to `127.0.0.1` is recommended. Several exited `griipbuddy-restore-*` projects and volumes are using disk space.
- Four `e2e.*` test accounts and one `griipbot*` test account exist on production. The `e2e.*` accounts are disabled.

## Not done in this session
- Full screenshot-based visual review of every screen. Welcome, Home, Conversation and Call were reviewed on real hardware.
- An instrumented Android UI test suite. Verification was manual, on the phone with the bot as the second participant.
- ~~Commits.~~ Work is committed per task on `internal-beta` and pushed (2026-10-16).
