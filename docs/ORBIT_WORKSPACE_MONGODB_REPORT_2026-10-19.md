# Orbit workspace organisation + MongoDB — Report A (before device testing), 2026-10-19

Owner's overnight brief "Workspace organization, MongoDB, physical-device validation, AWS deployment & database
migration". This report covers Phase A (Parts 1–10A). **Nothing was committed, pushed or deployed. No emulator was used.
No production system, AWS resource or the HONOR phone was touched.** Report B follows device testing and the rollout.

Verification levels used below: **implemented** · **unit tested** · **integration tested** (real HTTP server, real local
MongoDB) · **device verified** (none yet) · **migrated to production** (none) · **verified after migration** (none).

## 1. Repository and infrastructure findings

- Main checkout `/Volumes/code_files/griip_message`, branch `internal-beta` at b8bfecd (= production API 0.4.2). Work is
  uncommitted on top, together with the earlier overnight session's uncommitted work (kept and built on, nothing
  discarded) and the owner's own changes (`android/app/build.gradle.kts` version bump to 0.2.2-beta/9, `CLAUDE.md`), both
  untouched. A tracked `android/i18n/__pycache__/strings.cpython-314.pyc` was rewritten by the earlier session (generated
  file; left as is for the owner to decide). This session ran `gen.py` with `python3 -I -B` only.
- Server: Fastify 5, TypeScript, better-sqlite3 (SQLite, WAL), 15 migrations (013 lives on another branch). ~500
  synchronous SQL statements across the routes; authorisation (`authz.ts`) synchronous on the message hot path.
- **No MongoDB existed anywhere** (no code, config, cluster or credentials). Decision C3 (2026-10-18) had kept SQLite
  with the account module behind a store interface.
- Production: Hostinger VPS 72.60.107.88, compose project `griip-internal-messaging`, host nginx, S3 in
  ap-southeast-1 for files, backups on the VPS only and unencrypted (`SECURITY_REMEDIATION.md`).
- Android: Room + SQLCipher holds history, outbox, call log and keys-related state; starred conversations and mutes
  were phone-only before this round. Data inventory: `docs/MONGODB.md` §9.
- CI: GitHub Actions (server typecheck/tests/build, Android unit tests/lint/builds), no secrets.

## 2. Priority matrix

| Priority | Item | Status |
|---|---|---|
| Essential | E1 org management: switch, name/description, **avatar**, create, members/roles, **leave**, isolation | Complete (avatar + leave new) |
| Essential | E2 members and invitations: list, search, invite states (active/revoked/expired/used up), creator, removal, role changes | Complete |
| Essential | E3 roles and permissions, server-enforced, escalation tests | Complete (`roles.ts`, 2 test files) |
| Essential | E4 teams and channels: create/rename/describe, access modes, **archive/restore of branches and teams**, collapsible groups | Complete; admin-defined channel order not built (personal order is) |
| Essential | E5 channel details: about, access, notifications, members, pins, files | Complete |
| Essential | E6 messaging: mentions, threads, pins, read state, posting permissions, search | Existing; thread replies added to Activity |
| Essential | E7 navigation: exactly Home / DMs / Activity; Activity now has thread replies | Complete |
| Essential | E8 notifications and search: mute synced across devices; local message search | Complete (no server plaintext index, by design) |
| High | 1. Personal sections, favourites (starred), pinned order, synced | Complete |
| High | 2. Thread navigation and unread replies | Partial: Activity "Thread replies" with unseen counts (per phone) |
| High | 3. Announcement channels | Existing (access mode switch added) |
| High | 4. Channel tabs (messages, files, pins, members, details) | Existing details sections |
| High | 5. Org user groups and group mentions | Deferred (needs a protocol change, `WORKSPACE_ADMINISTRATION.md` §8) |
| High | 6. Admin audit history | Existing log; readable labels for the new events |
| High | 7. Scoped guests | Existing (GUEST role) |
| High | 8. Search and activity filters | Not built this round |
| Medium | Docs/FAQ, tasks, templates, automations, webhooks, usage reports | Deferred, dependencies documented |

## 3. MongoDB status

| Item | Status |
|---|---|
| Collections, validators, indexes for all 38 tables (derived from the SQLite schema) | Implemented, integration tested |
| Accounts domain authoritative in MongoDB (`MongoAccountStore`), sagas for cross-store steps | Implemented, integration tested (all 61 account tests + race tests on both stores) |
| Preferences domain authoritative in MongoDB (`MongoPreferenceStore`) | Implemented, integration tested |
| Migration tool: batched, checkpointed, resumable, idempotent, verified (counts, ids, per-row hash, set checksum) | Implemented, integration tested; rehearsed with the real CLI on the device-test server |
| Rollback tool (MongoDB → SQLite, FK-checked, backup first) | Implemented, integration tested |
| Authority guards (no split domains, no silent stale fallback) | Implemented, integration tested, rehearsed |
| Outage behaviour (503, SQLite domains keep working) | Integration tested |
| Remaining domains (identity, workspace, conversations, relay, files, history, federation) authoritative in MongoDB | **Not done**: copy/verify only. Each needs its SQL moved behind an async store (plan: `MONGODB.md` §6) |
| Production MongoDB | **Blocked**: no deployment or credentials exist (owner) |

## 4. Organisational features implemented this round (server + app)

Server (API 0.5.0, migration 015, feature flags `workspace-admin`, `nav-preferences`, `org-photos`):
- Organisation avatar: `PUT/DELETE/GET /v1/orgs/:id/photo` (owners/admins set, members see), removed with the org,
  covered by the orphan scan; `photoRev` in org summaries and `/v2/workspaces`.
- Personal navigation preferences: `GET/PUT /v1/me/preferences/:workspaceId` with revision-based concurrency (409 +
  current copy), validation and size limits, own conversations only, realtime `prefs.updated` to the person's other
  devices, clean-up on leave and org deletion.
- From the earlier session (audited, tested, kept): role rules (`roles.ts`), channel access modes, team/branch
  archive and restore with the archived list, invitation states and creators, `canLeave`/`canRestore`, audit events.
- Hardening: async authentication (one lookup per request, shared with the rate limiter), WebSocket frames handled in
  order after an async session check, conditional refresh-token rotation, single-use codes enforced atomically,
  disabled-account socket sweep moved before any await in the retention job.

App (Orbit 0.2.2-beta, code 9):
- Workspace settings: avatar add/change/remove; avatars everywhere a workspace is shown (`OrgMark`).
- Starred, custom sections (create, rename, delete, move to, order up/down), collapsed groups and mute, synced through
  the server (offline changes queued and replayed onto the latest copy); "Move to section" from Home and DMs.
- Activity: "Thread replies" in threads you started or replied in, unseen first.
- Readable workspace change-log entries for the new events.
- Strings for every new screen in English, Simplified Chinese and Bengali (`i18n/strings_workspace.py`).

## 5. MongoDB schema and data-layer changes

`docs/MONGODB.md` (architecture, collections, concurrency, configuration, migration and rollback runbooks, backups,
inventory, blockers). Code: `server/src/mongo/` (`config`, `catalog`, `client`, `index`, `migrate`, `admin`,
`accounts`, `preferences`); `server/src/accounts/store.ts` (async `AccountStore` with `createAccount`, `linkIdentity`,
`releaseIdentity`, `bindDeviceWith`, conditional `rotateSession`/`deleteCode`); `server/src/preferences/`.
Settings: `MONGODB_URI`, `MONGODB_DB`, `MONGODB_DOMAINS`, `MONGODB_MAX_POOL`, `MONGODB_TIMEOUT_MS` (in
`deploy/.env.example`, checked by `validate-env.sh`; TLS required for a remote production database).

## 6. Automated test and build results (this Mac, 2026-10-19 ~04:00 CST)

| Check | Result |
|---|---|
| Server typecheck (`tsc --noEmit`) | Pass |
| Server tests, SQLite | **399 passed**, 10 skipped (9 live-S3 without credentials, 1 MongoDB-only case), 46 files |
| Server tests, accounts + preferences in MongoDB (`ORBIT_TEST_MONGO_DOMAINS`) | **399 passed**, 10 skipped |
| Server build | Pass (`dist/`, API 0.5.0) |
| Android unit tests | **427 passed**, 0 failed |
| Android lint | 0 errors, 103 warnings (mostly pre-existing: KTX, dependency versions, a few unused strings) |
| Orbit Test APK (`assembleDebug -Porbit.sideBySide=true`) | Built |
| Migration rehearsal with the real CLI | Pass: seeded on SQLite, write freeze, `mongo-migrate accounts` and `preferences` verified, guard refused a stale start, server restarted with both domains in MongoDB, migrated account signed in and refreshed |

New server tests: `nav_preferences`, `mongo_migration`, `mongo_outage`, `account_store_races` (and the earlier
session's `roles`, `workspace_admin`). New app tests: `NavPrefsRulesTest`, `ThreadActivityRulesTest`, `NewActionsTest`
(and the earlier `WorkspaceRolesTest`). CI runs the server suite a second time with the MongoDB domains.

## 7. Test environment and APK

- Server: this checkout's `server/dist`, `127.0.0.1:18097`, scratch data, `REGISTRATION_MODE=open`,
  `MAIL_TRANSPORT=file` (codes written to disk for the test accounts), local LiveKit `17897` (TCP 7897),
  `MONGODB_DOMAINS=accounts,preferences` on a local single-node replica set `127.0.0.1:27091` (database
  `orbit_devicetest`). Scripts and data: this session's scratchpad `testenv/` (`mongo.sh`, `server.sh`, `admin.sh`,
  `seed.mts`, state mode 600). Nothing is reachable from outside this Mac.
- Synthetic data: workspace "Orbit Test Co" (branches Dhaka, Chittagong; team Support; channels #general,
  #dhaka-desk, #support, private #leadership, #announcements), workspace "Side Project", bots Nadia (owner), Tanvir
  (admin), Mitu (Dhaka manager), Rafi (guest), Orbit 2.0 account "Lina", invite codes for the phone as member, admin
  and guest.
- APK: `Orbit-Test-0.2.2-beta-workspace-mongodb-2026-10-19.apk` in this session's scratchpad `apk/`
  (`com.griipbuddy.Orbit.test`, label "Orbit Test", 0.2.2-beta code 9, installs beside the Play app; SHA-256
  `28e198c30d1130f9778167f8b7645b9079aa5d290240afe883b42a28d81aa270`).

## 8. Remaining security and data-integrity issues

0. The local LiveKit's media TCP port (7897) listens on all interfaces of this Mac while the test environment runs
   (like the earlier rigs); the signalling port and the API are on 127.0.0.1 only.

1. Production has no MongoDB, and backups are on the VPS only, unencrypted: both block the production migration.
2. Accounts in MongoDB cross two stores; a crash between saga steps can leave a link that the next attempt repairs
   (tested), or an invitation use consumed without an account (rare, harmless). SQLite keeps single transactions.
3. A remote MongoDB adds a round trip to every v2-authenticated request and WebSocket frame; latency from the VPS to the
   chosen region must be measured before cutover.
4. Personal preferences (starred, section names) are server-visible metadata, not content (`WORKSPACE_ADMINISTRATION.md`).
5. Unused strings left by the earlier session (6) and the rewritten tracked `.pyc`: cosmetic.

## 9. Files changed and git status

59 tracked files modified (≈ +2,070/−390 lines) and 45 new files (server `src/mongo/*`, `src/preferences/*`,
`src/photos.ts`, `src/roles.ts`, migration 015, six test files and helpers; app `NavPreferences`, `NavPrefsRules`,
`ThreadActivityRules`, `ThreadSeen`, `OrgAvatar`, `HomeCustomSections` plus the earlier session's screens and tests;
docs `MONGODB.md`, `WORKSPACE_ADMINISTRATION.md`, this report). `server/package.json`/lock: `mongodb` 7.7.0 and
`mongodb-memory-server` 11.3.0 (dev), version 0.5.0. Nothing committed.

## 10. Production

No production rollout, no production database change, no AWS change, no VPS access. The rollout waits for the device
checks, the owner's MongoDB deployment and credentials, encrypted off-VPS backups, and a deploy decision (the tree is
uncommitted).

## 11. Emulator

No emulator was launched or used at any point.

## 12. Physical-device checklist (HONOR, Orbit Test, against the local MongoDB-backed test server)

Setup (after the owner says the phone is connected and ready):
- [ ] `adb devices -l` shows the HONOR (serial AREBVB4530006465, a physical model, not `emulator-*`); SDK adb only.
- [ ] Owner isn't on a private screen; install the APK (owner taps "Continue"); the regular Orbit app is untouched.
- [ ] `adb reverse` tcp:18097, tcp:17897, tcp:7897; in Orbit Test add the server `http://localhost:18097`.

Accounts and workspaces (MongoDB-backed):
- [ ] Sign up with a test address; enter the emailed code (read from the test server's mail folder); set up encryption.
- [ ] Sign out and in again; the session survives an app restart (token refresh).
- [ ] Join "Orbit Test Co" with the member code; Home shows Dhaka, #general, #dhaka-desk; no Chittagong channels.
- [ ] Join "Side Project"; switch workspaces; each shows only its own channels (isolation).

Roles and administration:
- [ ] As member: no admin actions offered; Workspace settings read-only; Leave offered.
- [ ] With the admin code (second test account or role change by the owner bot): edit name/description, set and remove
      the workspace photo (visible in the switcher and Home header), archive and restore a team.
- [ ] Member directory and search; invitation list shows states and creators.
- [ ] Private #leadership appears only after the bot adds you; access mode card explains the mode; switch shown only
      to scope managers.

Personal sections and notifications:
- [ ] Star a channel and a DM; create a section, move a channel into it, rename it, reorder, delete it.
- [ ] Collapse a group; mute a channel; kill and reopen the app: everything kept (server copy).
- [ ] Airplane mode: star/mute offline, back online: the change reaches the server (`admin.sh mongo-status` counts).

Messaging, Activity, regressions:
- [ ] `seed.mts after-join <username>`: two @mentions, a DM, team and private channel arrive; Activity shows mentions.
- [ ] Reply in a thread, have a bot reply: Activity › Thread replies shows it unseen; opening it clears.
- [ ] Send text, a photo and a file; open a file; pins; search finds a message.
- [ ] A voice call with a bot over the local LiveKit (if time).
- [ ] Layout: scrolling, insets, touch targets, a large system font on Home, Activity and Workspace settings.

Not testable alone: a second physical device (multi-device preference sync is covered by tests and the realtime frame);
production behaviour (the gate).
