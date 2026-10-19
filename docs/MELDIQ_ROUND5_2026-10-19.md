# Meldiq round 5: design system, conversations, Later, polls, stickers, media, email and connectors (2026-10-19)

Worktree `.claude/worktrees/orbit-integrations` (branch `worktree-orbit-integrations`), on top of the uncommitted
integrations round and the first Meldiq rebrand ([report](MELDIQ_REBRAND_2026-10-19.md)). **Nothing is committed,
pushed or deployed. Production, DNS, AWS and databases are unchanged. No emulator was used.** Statuses: ✅ completed and
verified · 🟡 implemented, not yet verified on a phone · 🔑 waiting for credentials or an owner action · ⛔ blocked ·
⏸ deliberately deferred.

## 1. Architecture reviewed

| Part | What it is |
|---|---|
| Android | Kotlin + Jetpack Compose, one module (`android/app`), Room (SQLCipher) v7, manual DI (`AppContainer`), E2EE envelopes (`griip-envelope-v1`), LiveKit calls, WorkManager |
| Web | Separate repository `/Volumes/code_files/orbit_web` (Vite + React 19 + TS), already rebranded to Meldiq and live at `https://web.meldiq.app` (another session, 2026-10-19). Not changed in this round (§12) |
| Backend | Fastify + TypeScript (`server/`), SQLite with optional MongoDB domains (`accounts,preferences,integrations`), WebSocket `/v1/ws`, S3 storage, SES email, integrations kernel |
| Deployment | Docker Compose (`deploy/compose.yaml`, project `griip-internal-messaging`) on the Hostinger VPS 72.60.107.88, host nginx + Let's Encrypt; API at `msg.griipbuddy.cloud` (unchanged), web at `web.meldiq.app` |
| Domain | `meldiq.app` in the owner's Hostinger account (bought 2026-10-19, Hostinger DNS: `@` parking, `www`, `web` → VPS). `meldeq.app` (the brief's spelling) isn't owned |

## 2. Decisions taken with the owner

- **Package:** `com.meldiq.android` (not the brief's `com.meldeq.android`: the owner confirmed `meldeq` was a typo).
- **Sender:** `Meldiq <no-reply@meldiq.app>`.

## 3. Branding and package — ✅ built, 🟡 phone check pending

- Application id and namespace **`com.meldiq.android`** (287 files moved by `scratchpad/round5/package_move.py`; Room
  schema folder moved). A new id is a new app to Android and Play: it installs beside Orbit (`com.griipbuddy.Orbit`, the
  Play listing) and the first Meldiq test build (`com.griipbuddy.meldiq`); data never moves between them (accounts come
  back by signing in or the recovery key). A **new Play Console app** is needed for release.
- The **official logo** (`brand/meldiq-logo.webp`, same file as the web app's) is the only source, rendered by
  `android/scripts/render_brand.py`: adaptive launcher icon, themed monochrome icon, notification icon, launch screen
  (logo on navy `#071231`, only until the first frame), in-app tile (`MeldiqLogo`), welcome hero. Old drawn icons removed.

## 4. UI/UX — 🟡

Design system (docs/DESIGN_SYSTEM.md, top section): three presets from the reference — **Aurora** (default,
logo-matched), **Sunset**, **Meadow** — each light and dark; theme (System/Light/Dark), colour and message layout in
Settings › Chats › Appearance. The forced Ivory/Cocoa palettes are gone. Contrast of every text/surface pair, badge,
button, outline and banner is unit-tested (`MeldiqPalettesTest`). Faded text removed from the minimised call bar.

Home: gradient header, rounded content sheet, scrolling quick cards **Threads / Later / Drafts & sent / Calls**, solid
unread badges, action-colour New button, full-width tab bar with indicator pill, **Get started** checklist. Motion:
short, no idle loops, still with animations off (`rememberReducedMotion`).

## 5. Messaging

| Feature | Status |
|---|---|
| Already present before this round | reactions, replies, threads, edits, deletes, pins, forwarding, mentions with autocomplete, voice messages, files up to 1 GiB, albums, contact/location cards, animated emoji, link previews, translation, typing, read receipts, drafts, retry |
| **Stream layout** (default; bubbles optional) | 🟡 new |
| Context strip (encryption, pinned, files, saved here) and **search in conversation** (local, decrypted only on the phone; jumps to older results) | 🟡 new |
| **Save for later** + reminders, Home › Later (synced, server `saved-items`) | 🟡 new (server ✅ tested) |
| **Polls** (encrypted votes as controls, close by creator) | 🟡 new (rules ✅ unit-tested) |
| Drafts & sent sheet | 🟡 new |

## 6. Connectors

| Connector | Status |
|---|---|
| Google Drive/Calendar, OneDrive/Excel, SharePoint, Outlook Calendar, GitHub, Jira, Notion, Linear, Incoming webhooks | implemented earlier, mock-tested, 🔑 need OAuth app registrations (incoming webhooks ✅ end to end) |
| **Figma** (new): link previews from file metadata (`current_user:read file_metadata:read`, PKCE, Basic auth, separate refresh endpoint) | implemented, mock-tested, 🔑 needs a Figma OAuth app (and Figma review for outside organisations) |
| **GitLab** (new): issue/MR search and previews, gitlab.com or `GITLAB_BASE_URL` (`read_api read_user`, PKCE, revoke) | implemented, mock-tested, 🔑 needs a GitLab application |
| Microsoft 365 mail, Teams, Asana, Trello, ClickUp, Zoom, Meet… | ⏸ listed as planned (no capabilities claimed) |

No connector was tested against a real provider: no OAuth credentials exist yet.

## 7. Media and stickers

- **Music/video links** (YouTube, YouTube Music, Spotify, SoundCloud, Vimeo): cards recognised from the address, opening
  the official app or site; no embedded player, nothing fetched, no autoplay (docs/MEDIA.md). Shared listening is not
  offered: no supported official API for it. Workspace-wide switch ⏸.
- **Stickers**: 28 original stickers in 3 packs, drawn by the app from a shared JSON spec with the phone's emoji font;
  nothing third-party bundled (docs/STICKERS.md). Favourites, recent, search. 🟡

## 8. Email — 🔑

SMTP transport (`MAIL_TRANSPORT=smtp`, TLS required, nodemailer 10.0.14) next to SES; tested (`mail_smtp.test.ts`).
Hostinger: the Starter Business Email plan bought with `meldiq.app` is "pending setup" and attached to no domain; the
API can't attach it. **Owner action:** attach it to `meldiq.app` in hPanel. Then mailbox creation, SMTP settings,
SPF/DKIM/DMARC check and a delivery test (docs/EMAIL.md). Production stays on SES until switched.

## 9. Backend and infrastructure

- `saved` in the personal preferences document (expand-only, no migration; old clients can't wipe it); feature `saved-items`.
- Figma and GitLab connectors; `refreshEndpoint` in the OAuth helper.
- SMTP mail; `deploy/.env.example` and `validate-env.sh` know `smtp`, Figma and GitLab variables.
- No schema migration, no Docker/AWS change, no production action.

## 10. Automated tests

| Check | Command | Result |
|---|---|---|
| Server, SQLite | `npx vitest run` (server/) | **528 passed**, 10 skipped (MongoDB-only and S3-acceptance cases), 0 failed |
| Server, MongoDB domains | `MELDIQ_TEST_MONGO_DOMAINS=accounts,preferences,integrations npx vitest run` | **528 passed**, 10 skipped, 0 failed |
| Server typecheck and build | `npx tsc --noEmit`, `npm run build` | clean |
| Server dependency audit | `npm audit --omit=dev` | 0 vulnerabilities |
| New server tests | `nav_preferences` (saved items), `mail_smtp`, `integrations_figma_gitlab` | included above |
| Android unit tests | `./gradlew :app:testDebugUnitTest` | **451 passed**, 0 failed (new: `MeldiqPalettesTest` for 3 presets × 2 modes, `RichContentRulesTest` for polls, stickers and media links; `InitOrderTest` caught one init-order bug, fixed) |
| Android lint | `./gradlew :app:lintDebug` | 0 errors, 121 warnings |
| Android debug build | `./gradlew :app:assembleDebug` | built; `aapt2`: package `com.meldiq.android`, label "Meldiq", launcher `com.meldiq.android.MainActivity` |
| Local backend smoke test | `testenv/smoke.mts` against the restarted local API (0.6.0, `saved-items` advertised) | 9/9 |
| Secret scan of changed areas | credential patterns | none (only AWS's published example key in an existing test) |

Not run: Android instrumented tests (they need an emulator or the phone; never run with the phone attached), web app
checks (repository unchanged).

Test APK: `artifacts/meldiq-test-2026-10-19-r5.apk` (git-ignored), debug, `com.meldiq.android`, 0.2.1-beta (8), default
server `http://localhost:18098` (the local test backend through `adb reverse`), SHA-256
`37f599490c6a8f6c0b676ecc291651c7de91ff7208dfa45b88271f3b4dd72bc8`.

## 11. Physical-device tests

Device: the owner's **HONOR ELI-AN00** (serial AREBVB4530006465, Qualcomm hardware, `ro.kernel.qemu` empty: a physical
phone, not an emulator), Android 16 (SDK 36), USB debugging authorised. The test APK installed as a **new app**
(`com.meldiq.android`) beside Orbit and the first Meldiq build; nothing was uninstalled or replaced. Backend: the local
test server 0.6.0 through `adb reverse` (18098 API, 17898/7898 LiveKit). Tested 2026-10-19, 17:56–18:27 local time.

| Flow | Result |
|---|---|
| Install, launch (cold start ~2 s; launch screen only until the first frame), App info shows the official logo and "Meldiq" | ✅ |
| Welcome screen with the logo, dark Aurora palette, readable text | ✅ |
| Sign in with email and password, then unlock with the recovery key; encrypted history came back from the server | ✅ |
| Home: header gradient, rounded sheet, quick cards (Threads, Later 1 saved, Drafts & sent), Get started checklist from real state, New button, tab bar | ✅ (a one-off sideways offset of the card row was seen once and didn't come back after a cold start) |
| DMs list, conversation in the **Stream** layout (avatars, names, times, read ticks, reaction chips, quotes) | ✅ |
| Sending text; **Spotify link card** on sent and received messages | ✅ |
| **Poll**: compose, send, vote (100% / 1 vote); the vote reached the other member as an encrypted `vote` control (bot log); an older client got the readable fallback | ✅ |
| **Sticker** picker (Recent, packs), sending "Shipped!", motion then rest; older client got "🚀 Shipped!" | ✅ |
| **Save for later** with **Remind me in 1 hour**: Later card "1 saved", Later screen with reminder chip, WorkManager job scheduled (~59 min), server document holds ids and times only | ✅ (the reminder notification itself wasn't waited for) |
| **Search in conversation**: highlighted results, jump to an older message, "Saved · 1" chip | ✅ |
| Appearance: Light + **Sunset**, Light + **Meadow**, back to System + Aurora; **Bubbles** layout and back to Stream | ✅ |
| Voice call (E2EE, dark call screen in the preset, minimised bar, call record in the chat) | ✅ |
| Background notification (brand colour, Reply and Mark as read actions) | ✅ (checked in the notification record; the shade wasn't opened, so the owner's other notifications stayed private) |
| Reconnection: server stopped → "Connecting… Retry" banner, message waits (clock) → server back → sent | ✅ |
| Video call | ⏸ needs the camera permission for the new test app: the prompt was dismissed (Back grants nothing); owner's choice |

**Defects found on the phone and fixed** (then rebuilt, updated in place with `pm install -r`, and rechecked on the
phone): sticker captions cut off in the picker; your own search results showed "YO" instead of your initials; the poll
vote-share tint barely visible in dark mode (now tinted with the primary colour, contrast unit-tested); "Meadow" cut off
on its colour card; the text-size slider in an off-palette lavender.

**Observations:** microphone and notification permissions for the new test app were already granted without a tap from
me (as in the previous round's test); the camera permission did prompt. Scripted typing through Gboard drops words when
sent too fast (a test-driver issue, not the app). Not tested on the phone: connectors with real accounts (no OAuth
credentials), font scaling (it's a system setting I don't change), tablet layouts, screen sharing.

Final test APK after the fixes: SHA-256 `4ebe12e53de0b3f7546f4d0f18dd81ae753b44bde7bc0afd387b3f8021e990bb`
(Android unit tests: 451 passed, 0 failed, with the added poll-share contrast check).

## 12. Web app

Not changed in this round (separate repository). It keeps working against this backend (new fields are optional and
ignored). To match: the presets, Stream layout, poll/vote/poll_close and sticker rendering from the shared JSON, media
cards and Later — a follow-up session in `/Volumes/code_files/orbit_web`.

## 13. GitHub and production

**GitHub** ✅ (owner's go-ahead after the phone test): five commits under the owner's name on
`worktree-orbit-integrations`, fast-forwarded to **`internal-beta`** (`f92e59a..7e74ef9`, no force): `dac7bfc` server,
`b4607bb` Android, `a62e239` deploy settings, `57d4b7f` docs, `7e74ef9` image tag; GitHub attributes them to
`nahianahmed3832`. CI on `57d4b7f`: **success** (Server: typecheck, tests, build; Android: unit tests, lint, debug and release builds).

**Production** ✅ API **0.5.0 → 0.6.0** on 2026-10-19 at 10:37 UTC (the owner confirmed the 0.5.0 deploy 15 minutes
earlier was finished):

| Step | Result |
|---|---|
| Backup | `backups/pre-0.6.0-20261019T103347Z` on the VPS: verified snapshot (integrity ok, schema 15, 31 users) + attachments archive (71 MB), `.env` and `compose.yaml` copies; image tagged `0.5.0-rollback` |
| Build | `griip-internal-messaging-api:0.6.0` built on the VPS from the pushed sources |
| Rehearsal | migration 016 applied to a copy of the snapshot in a network-less throwaway container: schema 16, integrity ok, same counts; copy deleted |
| Switch | `up -d --no-deps --no-build api` only; LiveKit and ntfy not restarted; `validate-env.sh` 0 errors (2 pre-existing warnings: open registration, APP_ENV unset) |
| Checks | healthy at once, 0 restarts, clean logs; `/healthz` 0.6.0; features `custom-roles`, `integrations`, `saved-items`; live schema 16, still 31 users; production test bot signed in and the relay accepted its encrypted message; `web.meldiq.app` loads and reaches 0.6.0 |
| Not changed | mail stays SES (SMTP needs the Hostinger mailbox), no integration credentials (connectors show "needs configuration"), no MongoDB, no DNS, no AWS, LiveKit/ntfy untouched |

Rollback: `image: griip-internal-messaging-api:0.5.0-rollback` in `compose.yaml`, then
`docker compose -p griip-internal-messaging -f compose.yaml up -d --no-deps --no-build api` (no data restore needed:
migration 016 is additive). Full restore: `scripts/restore.sh` with the pre-0.6.0 archive.

The Android app is **not** released to Play: `com.meldiq.android` needs a new Play Console app and a signed release
build pointed at the production API; the test APK talks to the local test server.

## 14. Outstanding

Hostinger email plan attachment (owner); OAuth app registrations (owner); web follow-up; invitation emails and
email-change confirmation (⏸, the server has no such flows yet); workspace switch for media/entertainment (⏸).
