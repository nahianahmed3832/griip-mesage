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

**GitHub** (owner's go-ahead after the phone test): four commits under the owner's name, no AI attribution, pushed as a
fast-forward to **`internal-beta`** (`3bd40e6..c34a950`, full refspec, no force): `ef7ffa1` server, `7631ea3` Android,
`1372d1d` deploy settings and test bot, `c34a950` docs; then the image tag and this section.

**Production**: API **0.6.0 → 0.7.0** on 2026-10-19 at 13:38 UTC (api service only):

| Step | Result |
|---|---|
| Backup | `backups/pre-0.7.0-20261019T133555Z` on the VPS: verified snapshot (integrity ok, schema 16, 32 users) + attachments archive (71 MB), `.env` and `compose.yaml` copies; image tagged `0.6.0-rollback`; previous sources kept as `server.pre-0.7.0` |
| Build | `griip-internal-messaging-api:0.7.0` built on the VPS from the pushed sources |
| Rehearsal | migrations 017–020 applied to a copy of the snapshot in a network-less throwaway container: schema 20, integrity ok, same counts (32 users, 17 conversations, 91 attachments); copy deleted |
| Switch | `up -d --no-deps --no-build api` only; healthy 7 s later; LiveKit and ntfy not restarted; `validate-env.sh` 0 errors (2 pre-existing warnings: open registration, APP_ENV development) |
| Checks | `/healthz` 0.7.0 on msg.griipbuddy.cloud and through web.meldiq.app; features `recovery-codes`, `reminders`, `voice-rooms` (no `push-fcm`: not configured); live schema 20 with the new tables, same counts; no warnings or errors in the log; browser WebSocket sign-in still waits ~10.8 s before 4401 on both hosts (the web patches are in this release); two production test bots exchanged an end-to-end encrypted message, echo and reaction through the relay |
| Not changed | mail stays SES (sandbox: recovery and verification codes reach only verified addresses until SES production access), no FCM, no integration credentials, no MongoDB, no DNS, no AWS, LiveKit/ntfy untouched |

Rollback: `image: griip-internal-messaging-api:0.6.0-rollback` in `compose.yaml`, then
`docker compose -p griip-internal-messaging -f compose.yaml up -d --no-deps --no-build api` (no data restore needed:
migrations 017–020 only add tables and columns; 0.6.0 ignores them). Full restore: `scripts/restore.sh` with the
pre-0.7.0 archive.

The receiving test bot also got 18 old queued items from earlier test devices it can't verify ("unknown or uncertified
sender device"); they predate this release (the oldest queued items were 83 hours old) and are not a regression.

The Android app is **not** released to Play: `com.meldiq.android` needs a Play Console app and a signed release build
pointed at the production API; the test APK talks to the local test server.

### 5.1 Same day: Hostinger email and API 0.7.1

**Email (14:10 UTC, owner's go-ahead):** production account email moved from SES to **Hostinger SMTP** as
`Meldiq <no-reply@meldiq.app>` (was `Orbit <no-reply@griipbuddy.cloud>`). The owner attached the mail plan and created
the mailbox; DNS (MX, SPF, DKIM, DMARC `p=none`) was already in place. `.env` backup
`backups/pre-smtp-20261019T141003Z/env.backup`, API restarted alone. Details and rollback: `EMAIL.md`.

**API 0.7.1 (14:33 UTC, owner's go-ahead):** internal-beta `1bba8a8..7a6de0a`: `2d063a7` racing refresh fix (migration
021, from its own session), `b2ba9af` designed account emails with the embedded logo, `7a6de0a` version.

| Step | Result |
|---|---|
| Checks before push | `tsc`, build, server tests **589 passed** in SQLite and in MongoDB mode, `npm audit` 0 vulnerabilities |
| Backup | `backups/pre-0.7.1-20261019T143130Z`: integrity ok, schema 20, 32 users, `.env` + `compose.yaml` copies; image tagged `0.7.0-rollback`; sources kept as `server.pre-0.7.1` |
| Rehearsal | migration 021 on a copy of the snapshot, network-less container: schema 21, integrity ok, same counts; copy deleted |
| Switch | `up -d --no-deps --no-build api` only; healthy after 6 s; LiveKit and ntfy untouched; `validate-env.sh` 0 errors (the same 2 warnings) |
| Checks | `/healthz` 0.7.1 on msg.griipbuddy.cloud, web.meldiq.app 200, `verify.sh` 0 restarts, no warnings or errors in the log; `previous_refresh_answer` column present; two production test bots exchanged an encrypted greeting and echo; a designed verification email sent from the production container arrived as multipart (text + HTML with the inline logo), DKIM-signed by `meldiq.app` |

Rollback: `image: griip-internal-messaging-api:0.7.0-rollback`, same `up -d --no-deps --no-build api` (migration 021 only
adds a column; 0.7.0 ignores it).

## 6. Owner actions and open items

- ~~Hostinger mailbox for `no-reply@meldiq.app`~~ done, live since 14:10 UTC (§5.1). Rotate the mailbox password in
  hPanel (it was shared in chat) and update `SMTP_PASS` in the server `.env`.
- OAuth registrations for Asana and Zoom (and the earlier connectors).
- A Firebase project, if FCM is wanted (`PUSH_FCM.md` › Owner steps).
- HR plan questions (`HR_INTEGRATION_PLAN.md` §6).
- Start the web parity session (Meldiq Web: dark theme and message formats).
