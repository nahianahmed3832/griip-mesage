# Meldiq round 6 (2026-10-19): Graphite design, recovery codes, server reminders, voice rooms, connectors, FCM prep

Worktree `.claude/worktrees/orbit-integrations`, branch `worktree-orbit-integrations`, on top of internal-beta `3bd40e6`
(API 0.6.0 in production). API version **0.7.0**. Owner's scope for this round (asked at the start): dark theme and DMs,
server reminders, recovery codes, voice rooms; HR design first; web app in its own session; Firebase code prep only;
more connectors.

## 1. Audit (what was found)

| Project | Where | Notes |
|---|---|---|
| Meldiq Android | `android/` (Kotlin, Compose, Room v7 → v8) | package `com.meldiq.android` (the brief's "meldeq" was confirmed a typo this morning) |
| Meldiq API | `server/` (Fastify, SQLite, optional MongoDB, S3, LiveKit, ntfy) | production 0.6.0 + the web WebSocket patches |
| Meldiq Web | `/Volumes/code_files/orbit_web` (Vite/React), live at web.meldiq.app | separate repo; parity is its own session (not started: the app only lets the owner start it) |
| Employee management | `/Volumes/code_files/griipbuddy_employee_management` (Next.js 16, Prisma, PostgreSQL), live at griipbuddy.cloud | own database, sign-in and deployment on the same VPS; plan in `HR_INTEGRATION_PLAN.md`, nothing changed there |
| Firebase | none | push is UnifiedPush/ntfy |
| Connectors | Google Drive/Calendar, OneDrive/SharePoint/Outlook, GitHub, Jira, Notion, Linear, Figma, GitLab already built | Asana and Zoom were "planned" |
| Recovery | encryption recovery key existed; nothing for a lost email | |
| Reminders | Later reminders were WorkManager timers on each phone | |
| Voice | 1:1 and group calls on LiveKit with E2EE and screen sharing; no persistent rooms | |
| Monetization | `entitlements.ts` (limits, no plans) already in place | unchanged this round |

## 2. What was built

| Area | Result | Status |
|---|---|---|
| Graphite design (dark default, light, system) | new preset, one-time move for existing installs, flat header, single accent, palette picker 2×2 | Implemented; contrast unit-tested; **phone check pending** |
| Security padlock + panel | replaces the "End-to-end encrypted" chip; facts per conversation | Implemented, unit-tested; phone pending |
| Composer | animated growth to 6 lines, border, tighter corners when wrapping | Implemented; phone pending |
| Intro pages | 5 pages, Skip, dots, replay in Settings | Implemented; phone pending |
| Recovery codes | API (`ACCOUNT_RECOVERY.md`), SQLite + MongoDB stores, Settings row, sign-in recovery flow | Implemented, 9 API tests in both stores; phone pending |
| Server reminders | API + durable firing + retries (`REMINDERS.md`), sealed content, Reminders screen, Home card, Later bridged | Implemented, 15 API tests, 7 app tests; phone pending |
| Voice rooms | API (`VOICE_ROOMS.md`), app DB v8, Home section, room banner, raise hand, moderation | Implemented, 5 API tests, key-rule tests; phone pending |
| Asana, Zoom connectors | link previews (`INTEGRATIONS.md`) | Implemented, mock-tested; **needs OAuth app registrations** |
| FCM | server sender, token routes, migration 020 (`PUSH_FCM.md`) | Implemented, 7 tests; **off until a Firebase project exists**; Android client not added |
| HR | `HR_INTEGRATION_PLAN.md` | Design only, by the owner's choice |

Migrations: 017 (recovery), 018 (reminders), 019 (voice rooms), 020 (FCM tokens), all additive.

## 3. Automated checks

| Check | Result |
|---|---|
| `npx tsc --noEmit` (server) | clean |
| `npx vitest run` (server, SQLite) | 576 passed, 10 skipped (live S3/Mongo suites), 60 files |
| same with `MELDIQ_TEST_MONGO_DOMAINS=accounts,preferences,integrations` | 1 failure (a test reading SQLite directly) fixed; recovery suite 9/9 in both modes |
| Android unit tests | 469, 0 failures (incl. `MigrationsTest` v7 → v8, and tests added for the phone-test fixes) |
| Android lint + debug APK | lint clean (one error found and fixed: `LocalActivity` for the secure window); `app-debug.apk`, `com.meldiq.android` 0.2.1-beta (8) |

## 4. Phone test (HONOR ELI-AN00, Android 16, USB, physical; 2026-10-19 20:36–21:30 CST)

Installed over the existing install (`pm install -r`, data kept) against the local test server (API 0.7.0, its own data;
nothing in production). No crashes in the crash log for the whole session.

| Check | Result |
|---|---|
| Graphite | existing install moved once to Dark + Graphite; Home, chat, settings readable; flat header, one accent; Light mode the same design in greys; presets in a 2×2 grid |
| Padlock and panel | chip gone; padlock opens Security with the right facts for a channel (incl. app updates not end-to-end encrypted) |
| Composer | grows to six lines then scrolls, tighter corners, keyboard fine; draft cleared without sending |
| Intro pages | replayed from Settings: 5 pages, logo, dots, Skip, "Coming soon" on People & HR, Get started returns |
| Reminders | created in the editor (time picker, Asia/Shanghai), stored sealed (no plain text on the server), fired at 20:45:03 and shown and acknowledged the same second (notification category reminder, private on the lock screen); snooze 10 min; stop reminding; **app force-stopped: the server fired it at 20:55:49 and the phone showed it 2 s after reopening** |
| Later bridge | a saved message's reminder became a server reminder and fired |
| Recovery codes | 10 codes made after the password, shown once, screenshots refused on that screen, "10 of 10 left", then "8 of 10" |
| Recovery flow | sign-out → Lost access to your email → code + new address → emailed code + new password → sign-in with the new address and new password, the phone's keys and messages kept (done three times); old address told, new address confirmed |
| Voice rooms | created from New channel › Voice room; banner and Home section show who's in it live; no ring; joined a bot's room with "End-to-end encrypted" on both sides; raised hands in order; moderator mute (LiveKit muted the bot's mic, shown "muted"), lower hand, remove; **key handover: with the opener gone, the phone passed the key to a newcomer and both decrypted**; room closed when empty |

Defects found on the phone and fixed (each retested on the phone):

1. **Recovery code field scrambled typed codes**: re-inserting dashes moved the cursor. Now the field keeps the raw characters
   and draws the dashes (`VisualTransformation`); unit test for the cursor mapping.
2. **Sign-in refused after recovery** ("This phone holds the messages of <old address>"): the phone's profile now follows
   its own account to the new address (`AccountRepository.addressRecovered`); unit test.
3. **Overdue saved message notified again** when first bridged to a server reminder: items already past their time aren't
   bridged.
4. **Voice room chat got call entries** ("Incoming voice call", "Group voice call in progress") from the key message and
   the call-list sync: voice-room calls are never recorded, and older entries are removed.
5. **Voice room name truncated** in the header: the call buttons are hidden in voice rooms (the banner's Join opens it).

Not tested on the phone: a second real phone in a voice room (a bot stood in), FCM (no project), Asana/Zoom (no OAuth apps),
Light mode on every screen (Home and Settings checked).

One unexplained event: the phone raised its own hand once at 12:59:43 UTC, between my scripted steps (no tap was sent
then); nothing in the code raises a hand without a press on its button, and it didn't recur during the rest of the test.
Possibly a touch on the phone.

## 5. GitHub and production

Pending: after the phone test and the owner's go-ahead.

## 6. Owner actions and open items

- Hostinger mailbox for `no-reply@meldiq.app` (SMTP) — unchanged from round 5; production mail stays SES.
- OAuth registrations for Asana and Zoom (and the earlier connectors).
- A Firebase project, if FCM is wanted (`PUSH_FCM.md` › Owner steps).
- HR plan questions (`HR_INTEGRATION_PLAN.md` §6).
- Start the web parity session (Meldiq Web: dark theme and message formats).
