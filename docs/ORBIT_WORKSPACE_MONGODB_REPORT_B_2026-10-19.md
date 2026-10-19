# Orbit workspace organisation + MongoDB — Report B (device tests and the production gate), 2026-10-19

Follows Report A (`ORBIT_WORKSPACE_MONGODB_REPORT_2026-10-19.md`). Device testing ran after the owner said "Connected
and ready" (about 10:15 CST). **Production was not changed: no deploy, no migration, no AWS or VPS access.** The
production phase stops at a genuine access blocker (§9). Nothing is committed.

One process note: the Phase A stop message was asked in the app's next-step box rather than with the exact wording
the brief specified.

## 1. Device and build

- HONOR ELI-AN00, serial AREBVB4530006465, Android 16, physical (no `ro.kernel.qemu`, USB). **No emulator was used.**
- Orbit Test (`com.griipbuddy.Orbit.test`, "Orbit Test", 0.2.2-beta code 9), side by side with the Play app
  `com.griipbuddy.Orbit` (0.2.2-beta code 9), which was not opened or changed. Two existing Orbit Test profiles (earlier
  rigs) were kept; two new profiles were added for this test server.
- Final build installed 10:46 CST: SHA-256 `8b8a77bb359cd2087457135344f5a5077ff65bc9b97d7fd0f54c49f21a3f242f` (scratchpad
  `apk/…-b.apk`). Three installs in total, each confirmed by the owner's tap.
- Test server: this Mac only (API 0.5.0 at `localhost:18097` through `adb reverse`, local LiveKit, local MongoDB replica
  set holding the **accounts and preferences domains**). The production address was never saved in a profile.

## 2. Results on the phone (verification level: verified on the physical device)

| Check | Result |
|---|---|
| Sign-up with email + code, encryption set-up, recovery key screen (account rows in MongoDB) | Pass |
| Join a workspace with an invite; Home groups (Dhaka branch only; Chittagong locked) | Pass |
| Realtime: DM, two @mentions, team and private-channel membership arrive live | Pass |
| Starred, custom section (create, rename, delete), order, collapse, mute: each saved to MongoDB | Pass (after fixes, §3) |
| Server → phone: another device's change (simulated in MongoDB) adopted on next sync, incl. mute switch | Pass |
| Offline: change made while the API was down, sent on reconnect | Pass |
| Workspace avatar set by the owner bot shows in the header and switcher; removed from the phone as admin (blob deleted, audited) | Pass |
| Member: settings read-only, Leave offered; Admin (role changed live): name/description edit saved and audited | Pass |
| Invitations as admin: all invites with state, use count and creator; admin may only invite Member/Guest; revoke → Revoked | Pass |
| Archive and restore a team as admin (audited, archived list) | Pass |
| Channel details on a private channel: About, Privacy and access (mode switch offered to scope managers), mute synced | Pass |
| Activity › Thread replies: bot reply in a thread the phone replied in shows unseen; opening clears | Pass |
| Second workspace: join, switch both ways, no channels/sections/starred leak across workspaces | Pass |
| Guest (role changed live): all channels gone, New menu without channel/invite | Pass |
| Messaging regression: thread reply and DM from the phone decrypted by the bots (E2EE intact) | Pass |
| Calls regression: voice call rings (`RINGING`) and ends (`ENDED`/hangup) | Pass (signalling only, no media partner) |
| Local message search | Pass |
| Rollback rehearsal with phone-written data: stop → `mongo-rollback preferences` → run on SQLite (new write lands in SQLite) → `mongo-migrate preferences --prune` → run on MongoDB | Pass |

Not tested on the device (and why): picking a workspace photo from the gallery and sending a file (would use the
owner's personal photos and files), a call with live media, larger system font sizes (a phone setting), a second
physical device (two-device sync is covered by the server-copy test and automated tests).

## 3. Defects found on the phone and fixed

1. **A new account's preferences didn't reach the server until the app restarted.** A new profile's container starts on
   the default (production) address; `ServerFeatures` cached that server's answer, without `nav-preferences`, for 5
   minutes, so syncs were skipped. Fix: the feature cache follows the server address (`ApiClient.serverAddress`,
   `ServerFeaturesTest`). Re-verified on the phone with a second new account: the first star reached MongoDB at once.
   This flaw predates this round and also affected history and direct-upload checks after adding an account.
2. **A custom section had no "Edit" on Home** (a collapsible header could not carry an action). Fixed in
   `HomeSectionHeader`; rename and delete verified on the phone.
3. Copy: "1 new replies" and "1 members" → singular forms.
Diagnostic log lines were added to the preferences sync (workspace id and outcome only, no content).

Observations, not fixed: the app doesn't save the selected workspace on the account (`selected_workspace_id` stays
empty; known follow-up from the Orbit 2.0 round); adding an account asks the default server's public `/v1/server-info`
before the address is changed (existing behaviour).

## 4. Automated results after the fixes

Server 399 passed (SQLite) and 399 passed (accounts + preferences in MongoDB), typecheck and build pass; Android 428
unit tests pass, lint 0 errors (103 warnings, mostly pre-existing).

## 5. MongoDB architecture and configuration (no secrets)

As in `docs/MONGODB.md`: domains `accounts` and `preferences` authoritative in MongoDB when listed in `MONGODB_DOMAINS`;
everything else in SQLite; settings `MONGODB_URI` (TLS required in production), `MONGODB_DB`, `MONGODB_DOMAINS`,
`MONGODB_MAX_POOL`, `MONGODB_TIMEOUT_MS`; authority recorded in `orbit_meta`.

## 6. Migration details and integrity (test environment only)

| Run | Tables | Rows | Result |
|---|---|---|---|
| Rehearsal 1 (synthetic, after seeding on SQLite) | accounts ×4, nav_preferences | 1 account, 1 session, 2 preference docs | Verified (counts, ids, row hashes, set checksums) |
| Rollback (after device tests) | nav_preferences | 4 docs (2 written by the phone after cutover) | Verified, FK-checked, SQLite backed up first |
| Forward again (`--prune`) | nav_preferences | 4 docs (incl. a write made while on SQLite) | Verified |

Backups made automatically by the CLI before each step (`testenv/data/backups/pre-mongo-*.sqlite`,
`pre-rollback-*.sqlite`). Restore was exercised by the rollback itself.

## 7. AWS / server changes

None. No AWS resource, VPS file, container, DNS or nginx change was made. Production still runs API 0.4.2 on SQLite.

## 8. Rollback readiness and legacy data

Production has nothing to roll back. For the future cutover the rollback path (`mongo-rollback`, guarded authority,
SQLite copy kept untouched during observation) is rehearsed on test data.

## 9. Gate status and blockers (owner)

| Gate item | Status |
|---|---|
| Automated and MongoDB integration tests | Pass |
| Device checks | Pass (with the untested items in §2) |
| Critical integrity/security defects | Fixed (§3) |
| Migration dry run and rollback | Rehearsed on synthetic and device data |
| API and schema stable | Yes (API 0.5.0, migration 015, MongoDB schema v1) |
| **Production MongoDB** | **Blocked: no deployment and no credentials exist** |
| **Encrypted backups off the VPS** | **Not set up** (backups are VPS-only and unencrypted) |
| **Deploying the server** | **Needs a decision: the tree is uncommitted; deploys build from it** |

Production steps once unblocked (`docs/MONGODB.md` §6–§8): deploy API 0.5.0 with `MONGODB_URI` and no domains
(expand); encrypted off-VPS backup; maintenance window; `mongo-migrate accounts` and `preferences`; add the domains;
smoke tests and the phone against production; observe with SQLite kept for rollback.

## 10. Git state

`internal-beta` at b8bfecd; about 101 changed and new files uncommitted (Report A §9 plus this round's fixes:
`ServerFeatures.kt`, `ApiClient.kt`, `HomeSections.kt`, `ActivityFeedScreen.kt`, `WorkspaceScreen.kt`, strings,
`ServerFeaturesTest`). The owner's own changes (`build.gradle.kts` version bump, `CLAUDE.md`) untouched.

## 11. Open items

Production blockers (§9); the follow-ups in §3; moving the remaining domains (plan in `MONGODB.md` §6); test
environment still running on this Mac (API, MongoDB, LiveKit) with `adb reverse` set on the phone.
