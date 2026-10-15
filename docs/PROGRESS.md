# Progress log

| Milestone | Status | Notes |
|---|---|---|
| 1. Environment & design foundation | ✅ | Toolchain verified. Reference analysed with sampled colours (`DESIGN_SYSTEM.md`). Compose design system written: tokens, glass components, hero art. Isolated Compose project. |
| 2. Identity & real messaging | ✅ | Ed25519 identity and device certificates, Keystore-sealed keys, challenge auth, `griip-envelope-v1` E2EE, outbox with retry, offline queue, 7-day TTL with cleanup, receipts. |
| 3. Organisation & branch system | ✅ | Orgs, branches, departments, teams (including cross-branch), public/private/announcement channels, invites (codes, QR, links), 6 roles enforced on the server, directory, member and role management, activity feed. |
| 4. Messaging experience | ✅ (mostly) | Replies, threads, reactions, mentions, edit and delete, pins, read receipts, typing, presence, unread counts, mute, drafts, local search, attachments. Emulator/UI screenshots of every screen are still pending. |
| 5. Voice & video | ✅ | LiveKit 1.13.10 deployed. Short-lived room tokens, voice and video calls, controls, ring, decline and missed states, reconnect states. **Verified on a physical phone** after fixing a native-init crash. |
| 6. Screen sharing & security | ✅ (partial) | MediaProjection consent flow, sharing indicator and Stop verified on the phone, with the remote screen track received. Media E2EE verified on production with right and wrong keys (Node peers) and phone ↔ bot. Wrong-key test for the screen track is still to do. |
| 7. Visual & functional regression | 🟡 | Physical-phone run found and fixed four defects: call crash, dark-mode contrast, stale draft, media-key overwrite. Full per-screen visual pass remains. |
| 8. Packaging & report | ✅ | APK in `artifacts/`, docs complete, deployment verified and isolated. |

## Timeline (2026-10-16, local)
- Inspected the environment and the design reference, then wrote the design system.
- Built the backend and its 32 integration tests, all green. A config env-parsing bug was caught by the tests and fixed.
- Built the Android app, which compiles cleanly against verified dependency APIs.
- **The VPS couldn't be reached at first:** the only saved SSH host was an unrelated IP with a changed host key, and I didn't bypass that.
  Once Hostinger access was available, I found the real VPS (72.60.107.88), and with your approval added this Mac's SSH key and inspected the server read-only.
- With your approval, deployed:
  - DNS records for `msg` and `rtc`, and firewall rules for 7881/tcp and 7882/udp.
  - A new nginx site and certificate.
  - The `griip-internal-messaging` Compose project.
- Production smoke test 8/8, and the E2EE media right-key/wrong-key test passed.
- APK defaults to production.
- **Physical phone testing (HONOR, Android 16):**
  - Messaging interop both ways, attachments, receipts and typing.
  - Calls crashed. The cause was found and fixed, then voice, video and screen share were verified.
