# Physical-device fixes and Orbit rebrand (2026-10-16)

Two real Android phones on the production server (`msg.griipbuddy.cloud`) turned up the defects below. This round fixes
them in the source; **nothing here is deployed** and **nothing is committed**. Phone checks that need two physical
devices are listed under "Still to verify on phones".

## What production runs

Production still runs the image built on 2026-10-15 from commit `c99c860` (only migration 001; no invite gate, no
federation). A local server built from `c99c860` reproduced both server-visible defects exactly:

| Check against `c99c860` code | Result |
|---|---|
| `POST /v1/invites/preview` → `org` keys | `id, name, description`: **no `slug`** |
| Creating "GriipBuddy" twice as the same person | **2 workspaces** |

## Defects, causes and fixes

| # | Defect | Root cause (verified) | Fix | Tests |
|---|---|---|---|---|
| 1 | Valid invite code → "Unexpected server response" | The app's `OrgDto.slug` was required; the server's invite preview omits `slug`, so decoding a successful (200) reply failed | **App:** `slug` optional (works with today's production server). **Server:** preview includes `slug`. Refusals now say why: `invite_not_found`, `invite_revoked`, `invite_expired`, `invite_used_up`, `invite_unavailable` (still HTTP 404). Accept is idempotent: a retry or double tap succeeds without using the invite again. Typed codes are normalised (case, spaces, dashes) but never reinterpreted (O/0/I/1/L are reported, not swapped). A pasted share message yields its code | `InviteContractTest` (6), `InviteScanTest` (+4), `invites_workspaces.test.ts` (8 invite tests incl. a race for the last use) |
| 2 | No way to read an invite QR from Gallery | Only the camera scanner existed | "From Gallery" (Android photo picker, no permission), decoded on the phone with both zxing binarizers and the inverted image; only `griip://invite/…` or a bare code is accepted, web links are never opened | `InviteScanTest` (screenshot pixels, light-on-dark, web-link QR refused) |
| 3 | Tapping a photo opened "Open with" (Gallery, Music, OCR apps…) | `openAttachment` sent every attachment to an `ACTION_VIEW` chooser | In-app full-screen viewer: swipe between the chat's photos/videos, pinch and double-tap zoom, video player with seek; Save to Gallery (MediaStore, `Pictures/Orbit` / `Movies/Orbit`; Android ≤ 9 asks for storage), Save to Files (system picker), Share — all explicit (⋮ or long press), never on tap. Bubbles show EXIF-rotated photos and video thumbnails. Decrypted copies only; deleted messages drop out of the viewer | `MediaGalleryTest` (4) |
| 4 | Five identical "GriipBuddy" workspaces | Creating a workspace wasn't idempotent and accepted a second one with the same name; the join failure (#1) sent the person to "Create" repeatedly. Each card is a real organisation (membership has a primary key, so the list can't repeat a row) | **Server:** migration 006 + `requestId` (one per create form) returns the first result; a same-name workspace for the same owner is refused (`409 org_name_taken`, with the existing id; the app offers "Open it"). Operator repair, dry run first: `admin.js org-duplicates`, `admin.js org-delete-empty <orgId> --yes` (refuses anything with other members, teams, shared channels, used invites, files, calls or queued messages). **App:** list normalised by id, stable order; switcher keyed by id, same-named workspaces shown with handle and creation date and distinct colours; Discord-style quick-switch rail | `invites_workspaces.test.ts` (4), `WorkspaceListTest` (3) |
| 5 | Echo (the other person hears their own voice) | LiveKit Android 2.29 enables the phone's hardware echo canceller on Android 10+, and WebRTC then switches its own (AEC3) off: echo cancelling depended on the phone maker | WebRTC's AEC3 by default (hardware AEC not requested); "Use the phone's echo canceller" setting to compare. Audio route tracked and logged (device types only); a new call waits for the previous call's audio device to be released | `CallMediaPolicyTest` |
| 6 | Blurry camera video | Not a capture setting: the SDK already sends 720p/360p/180p simulcast. A receiver on a weak link (one phone used a VPN) gets the low layer | Policy made explicit (720p30 simulcast, adaptive stream). The call now says "Weak connection: video quality is reduced" (LiveKit quality POOR/LOST) and **Connection details** shows real sent/received resolution, fps, kbit/s and the limiting factor | `CallMediaPolicyTest` (stats parsing) |
| 7 | Unreadable screen share | SDK default: native resolution (e.g. 1200×2664), 30 fps, 7 Mbit/s, simulcast, default degradation (drops resolution first) | ≤1920×1080 with the aspect kept, 15 fps, ≤2.5 Mbit/s, one layer, `MAINTAIN_RESOLUTION` (congestion lowers fps, not sharpness) | `CallMediaPolicyTest` |
| 8 | Call controls misplaced / over the video | Fixed padding; controls permanently covered the remote video and shared screens | Safe-drawing insets (nav bar, gesture area, cutouts), width capped in landscape, 48 dp targets; over video the controls hide after 4 s and come back on tap; never while ringing, connecting, on voice calls or with a menu open | `CallMediaPolicyTest` (`CallChrome`) |
| 9 | Navigation animations | Plain fades/slides, not RTL-aware | Fade-through between tabs (state kept), slide + parallax for pushed screens, reversed on Back, RTL-aware, none when system animations are off | `NavMotionTest` |
| 10 | Missing notification sound | Incoming-call ring played once (notification sound, not insistent). No visibility into what Android/the phone maker silenced. No push service by design: delivery needs the background connection | Ring loops until answered/declined/timeout (`FLAG_INSISTENT`; ringer mode still respected). Settings → Notifications and the Chats screen show what is wrong (permission, app switch, blocked/silent channel, background connection off, battery restriction) with a button to the exact Android settings page | `NotificationHealthTest` (4) |
| 11 | Swipe to reply | Not implemented | Swipe a message toward the end edge (haptic at the threshold) → reply preview above the composer, keyboard up; horizontal-only (vertical scroll, the voice seek bar, long press and Back unaffected); accessibility "Reply" action; RTL-aware | `SwipeToReplyTest` |

No wire format, encryption, message id, reply id or federation change.

## Verification (2026-10-16)

| Check | Result |
|---|---|
| Server suite | 141 tests: 140 pass; `federation_e2e` "keeps queued messages while a peer refuses or is down…" is flaky and **fails the same way on unmodified `HEAD`** (3 of 5 runs), so it predates this work. 12 new tests pass |
| Server typecheck | pass |
| Android unit tests | 172/172 (was 142) |
| Android lint | 0 errors, 51 warnings, 3 hints |
| Same API script vs `c99c860` (production's code) and vs the fixed code | preview without/with `slug`; duplicate workspace created / refused with 409 |
| Emulator (API 36) + local server running `c99c860` | lowercase, undashed code → preview shown → joined (server: MEMBER, invite used once) → lands on Workspace; second same-named workspace shown separately with handle, date, colour and the selected tick |
| Emulator: post-join navigation | **found and fixed a second root cause**: on success the app restored the Workspace tab's saved stack with the join screen still on top, so success looked like nothing happened (why "Create" was tapped repeatedly) |
| Emulator: media | tapping a sent photo opened the in-app viewer (app stays in front, no chooser); double-tap zoom; Save to Gallery wrote `Pictures/Orbit/…` with the original's size (decrypted, not the ciphertext); Back closes |
| Emulator: swipe to reply | swipe sets "Replying to …" with Cancel and opens the keyboard; Cancel clears it |
| Emulator: notifications | denied permission → banner on Chats and Settings; "Fix" opens Android's Orbit notification settings |
| Emulator: brand | cold-start splash (symbol on near-black green), label "Orbit", adaptive icon in a circle mask; the old Griip Message app untouched beside it |
| Calls | not exercised on the emulator (no microphone, and permissions are never granted by script); needs two phones |

## Orbit rebrand

- App name **Orbit**, "Orbit by GriipBuddy", tagline "Private by design. Connected by everyone."
- Application id **`com.griipbuddy.orbit`** (was `com.griipbuddy.message`). It installs as a **separate app**: the old
  app, its identity keys, history and settings stay where they are; nothing migrates. On Orbit, sign in by linking
  the device or restoring with a recovery key, or register.
- Unchanged on purpose: Kotlin namespace `com.griipbuddy.message` (R/BuildConfig), server protocols, `griip://invite/`
  links (both apps register it; Android asks which to open if both are installed), notification channel ids,
  Docker project and service names.
- Launcher: adaptive icon (foreground, deep-green background, monochrome for themed icons), notification icon,
  AndroidX SplashScreen (system splash on Android 12+, same look on 8–11). Artwork generated as vectors from one
  geometry (`res/drawable/ic_launcher_*.xml`, `orbit_mark`, `orbit_tile`).
- FileProvider authority follows the id (`com.griipbuddy.orbit.files`). No Firebase, no push credentials.

## Deployment needed (not performed)

Backend changes: invite preview `slug` and reasons, idempotent accept, idempotent create (migration **006**), same-name
refusal, `org-duplicates` / `org-delete-empty`. Service: **`api`** of Compose project `griip-internal-messaging`.
LiveKit needs nothing.

The Android invite fix (#1) works against today's production without a deploy. Duplicate prevention (#4) needs the
deploy. Deploying `HEAD` + these changes also brings migrations 002–005 and invite-only registration (see
`SELF_HOSTING.md`); back up first.
