# Session handoff (2026-10-21, gated-batch round)

For the next session: where everything is, what was done and verified, what isn't, and exactly what comes next. The
batch plan and its approval gate are in `MELDIQ_IMPLEMENTATION_PLAN.md`; the status of every feature is in
`MELDIQ_FEATURE_GAP_MATRIX.md`.

> **Gate in force:** B1, B2 and B3 were phone-tested and approved by the owner (every checklist step passed,
> 2026-10-21). B4 is built and its APK is waiting for the owner's phone test. **Don't start B4b, or make other UI or
> backend changes, until the owner approves B4 or reports defects in it.** Answer questions and fix reported B4 defects
> only.

## 1. Repositories and branches

| Repository | Folder | Branch | State at hand-off |
|---|---|---|---|
| meldiq (Android, server, docs), `github.com/nahianahmed3832/meldiq` | **`/Volumes/code_files/meldiq_android`** (the owner signs from here) | `internal-beta` (a tag shares the name: use `refs/heads/internal-beta`) | Fast-forwarded to this session's last commit. **Not pushed.** The owner's own uncommitted edit is untouched: `android/app/build.gradle.kts` versionCode 20, versionName `0.4.0-beta` |
| same | `.claude/worktrees/meldiq-android-rebuild-8c8540` | `claude/meldiq-android-rebuild-8c8540` | Where this session worked; same commits |
| meldiq-web, `github.com/nahianahmed3832/meldiq-web` | `/Volumes/code_files/orbit_web` | `main` | Two local commits, sign-in help (`0f462ed`) and emoji picker (`bd42c61`). **Not pushed** |

Never touch `signed_file/` or `android/app/release/`.

## 2. Computer and Discord access (verified 2026-10-21)

- **Tool:** the computer-use MCP server. It drives the Discord desktop app on the owner's Mac (`com.hnc.Discord`,
  stable 643186), signed in to the owner's account. The owner granted app access and full-screen control (asked again
  per session).
- **Not available:**
  - Discord on mobile;
  - flows that send, join, create or reach people;
  - Community-only admin screens.
- **Screenshots:**
  - `research/discord/live-2026-10-21/`, 119 from this round (94 general, 25 social prefixed `2-`), also copied to the main folder;
  - round 1's are in `research/discord/`.
  - Both folders are git-ignored because they show real people and messages. They exist only on this Mac's main
    folder and the worktree.
- **Side effects of inspecting** (safety log, `MELDIQ_LIVE_DISCORD_INSPECTION.md` §1):
  - a toggle flipped and restored;
  - one REA guide task ticked by visiting a channel;
  - read state;
  - a group-DM invite link shown and not shared;
  - a report survey dismissed.
  - Nothing was sent, posted, created, submitted, joined or called.

## 3. Discord inspection coverage

| Area | Document | Coverage |
|---|---|---|
| Navigation, community (member and owner), channels and categories, messages, composer, report flow, forums, polls, events, emoji, creation, discovery, settings, Safety Setup, AutoMod, Audit Log, Bans, Access, Invites, Emoji settings | `MELDIQ_LIVE_DISCORD_INSPECTION.md` | About 90 control rows with IDs (`NAV-CTRL-*`, `COMM-CTRL-*`, `CHAN-CTRL-*`, `CAT-CTRL-*`, `CHAT-MSG-*`, `CHAT-COMP-*`, `FORUM-CTRL-*`, `POLL-CTRL-*`, `EVENT-CTRL-*`, `EXPR-CTRL-*`, `CREATE-CTRL-*`, `SET-CTRL-*`, `ADMIN-CTRL-*`) |
| Friends, DMs, group DMs | `MELDIQ_SOCIAL_AND_COMMUNICATION_AUDIT.md` | `FRIEND-CTRL-001–012`, `DM-CTRL-001–010`, `GDM-CTRL-001–008`. Lists, menus, forms, profiles and settings observed; request, accept, block and send actions were not performed (they reach real people) |
| Invitations | `MELDIQ_COMMUNITY_INVITATION_SPEC.md` | `INV-CTRL-001–010`. Entry points, Invites page, Pause, Access observed; the invite dialog and link opening are documentation-based (opening the dialog creates a real invite) |
| Calls and screen share | `MELDIQ_CALLING_SCREENSHARE_AUDIT.md` | `CALL-CTRL-001–009`: entry points and settings observed; live calls and sharing not observed (would reach real people) |
| Round 1 (server settings, roles, permissions, create channel) | `MELDIQ_DISCORD_REFERENCE_AUDIT.md` | — |

To finish the blocked flows, the owner provides a Discord test server and spare accounts (social audit §5).

## 4. Findings that changed the plan

- **DMs need consent:** `POST /v1/conversations/dm` creates a DM with anyone (DM-03, P1). The proposed connections
  model reverses matrix DM-04 and **needs the owner's approval** (`MELDIQ_FRIENDS_AND_DMS_SPEC.md`).
- **Invites:** the server side is complete and tested. Links are `meldiq://` only: B4 adds https App Links, a
  signed-out preview, pause invites, and invites from DMs (`MELDIQ_COMMUNITY_INVITATION_SPEC.md`).
- **Translations:** hard-coded English in the message menu and the conversation info screen (MSG-19, B5).
- **Camera:** a stage-by-stage trace plan for B10 (`MELDIQ_CALLING_SCREENSHARE_AUDIT.md` §3). The 2026-10-20 fixes
  are still unverified on a phone.
- **New feature IDs:** NAV-11, COM-16–18, CH-17–18, MSG-19–21, DM-07–09, MOD-13, CALL-11.

## 5. Batches B1 to B3: approved

| Batch | What | Owner's phone test |
|---|---|---|
| B1 | Sign-in and unlock: recovery choices behind help links (`MELDIQ_AUTH_FLOW_AUDIT.md`) | Every step passed (production server) |
| B2 | Community notification settings with Ignore @everyone, rail order, starter templates (API 0.15.0) | Every step passed (local test server) |
| B3 | Slowmode, timed mutes, category levels, hidden channels and the channel browser (API 0.16.0, migration 035) | Every step passed (local test server) |

All three are **physically verified**. Details are in git history (`377d83c`, `edfb459`, `457757a`) and the matrix.

## 6. Batch B4: administration, part 1 (built 2026-10-21, waiting for the phone test)

B4 as first planned was too large for one testable batch. It was split: this batch, and **B4b** (join applications,
raid alerts, https invite links with a signed-out preview, invite from a DM, Meldiq's operator review tool).

### What's in it

| Feature | What the person sees | Code |
|---|---|---|
| Report a message (MOD-07, Discord CHAT-MSG-003 §6.1) | <ul><li>Long-press someone else's message › **Report**;</li><li>six reasons, with "Just don't want to see them? Block …" below them;</li><li>a **Review your report** step saying who reads it ("the moderators of X. They won't see that it was you", or "Meldiq's team") and exactly what's shared (this message and up to 5 before it);</li><li>an optional note;</li><li>**Send report**, then a confirmation</li></ul> | Android `moderation/ReportSheet.kt`, `ReportRules.kt`, `ConversationScreen.kt`. Server `routes/reports.ts`, `reports/store.ts`, `mongo/reports.ts`, `reports/seal.ts`, migration 036, permission `reports.view` (Moderator template) |
| Report a person (DM-09) | Profile › **Report** (send to Meldiq's team or one of your communities); community member sheet › **Report** (to that community) | `ProfileScreens.kt`, `OrgScreens.kt` |
| Moderators' queue | Community settings › Moderation › **Reports**: Open (n) / Closed; each report shows the reason, who was reported, where, what was shared and the note. Mark as handled or Dismiss (with a note for other moderators), Reopen. A report about you says another moderator handles it | `moderation/ReportsScreen.kt` |
| Security actions (MOD-13, ADMIN-CTRL-007) | Community settings › **Security actions**: pause joining, and new DMs between members, for 1 hour, 6 hours, 24 hours or 7 days; "Paused until …" with Resume. A joiner sees "This community has paused new members" | Android `moderation/SecuritySheet.kt`, `JoinWorkspace.kt`. Server `PUT /v1/orgs/:id/security`, `invites.ts`, `community.ts`, `conversations.ts` (new DMs) |
| Audit log filters (MOD-09, ADMIN-CTRL-004) | Audit log: All / Members / Channels / Categories / Roles / Invites / Community / Reports, and "Anyone" › a person | `AdminScreens.kt` (`AuditFilters`); server `actorId` filter |
| Invite choices (INV-B-03) | Uses 1, 10, 50, 100, 500; expires 1 hour, 1 day, 3 days, 7 days, 30 days | `OrgScreens.kt` (`InviteChoices`) |

**Server and API:**
- API **0.17.0**, with new features `reports` and `security-actions`.
- **Migration 036:** a `reports` table and two pause columns on `orgs` (additive). It was applied to the existing local
  test database without problems.
- **New MongoDB domain `reports`.**
- **Report content key, for any shared deployment:**
  - The content is sealed with **`REPORTS_KEY`** (32 bytes, base64). Without it, a single server creates
    `reports.key` in its data folder, owner-only.
  - **Back that key up with the database:** without it, existing report content can't be read.
  - Where several servers share one database (the AWS plan), set `REPORTS_KEY`.
- **Retention:** closed reports are deleted 90 days after they were handled. Account and community deletion remove
  their reports.

**New Android strings** in three languages: `rp_*`, `rq_*`, `sa_*`, `au_*`, `inv_exp_*`, `inv_paused`.

### How it was checked

- **Server:**
  - `reports_security.test.ts` (14):
    - where reports go;
    - content sealed at rest;
    - a repeat report returns the first;
    - the sender check;
    - who sees and closes reports;
    - a moderator can't close their own report;
    - the hourly limit;
    - the 90-day retention;
    - who may pause and for how long;
    - joining and new DMs refused while paused, with existing DMs and moderators unaffected;
    - the audit person filter.
  - Full suite **662 passed**, 10 skipped. The B2 to B4 tests also pass with reports, preferences, history and
    accounts in MongoDB.
- **Android:** `ReportRulesTest` (6), full unit suite **593 passed**, lint clean.
- **Emulator** (local server 0.17.0; Ada an admin in the main app, Erin a member in "Meldiq Test"):
  - **Message report:**
    - Erin reported Ada's message: reasons, then the review ("Goes to the moderators of Mod Lab…", the message text,
      the 5-message line), a note, Send.
    - Stored with community scope and sealed (`r1.`).
  - **Person report:** Erin reported Dave from his profile, choosing Mod Lab: it went to the moderators.
  - **Queue:**
    - Ada's Reports showed "Open (2)" with no reporter shown.
    - The report about Ada said "another moderator handles it" (found and fixed during the walk; see below).
    - Ada marked Dave's report handled with a note: Closed showed "Handled by Ada · warned".
  - **Audit log:** the Reports filter showed "report actioned".
  - **Security actions:** pausing joining for 1 hour showed "Paused until 11:59 PM". A new account with a valid invite
    was refused (`invites_paused`), and its preview said so. Resume worked.
  - **Invite form:** shows the new use and expiry choices.
  - Screenshots: `docs/product/screens-2026-10-21/b4-*.png`.
- **Found and fixed during the walk:**
  1. A moderator could close a report about themselves. The server now refuses (`own_report`) unless they're the
     owner, and the app shows a note instead of the buttons.
  2. Ordinary members open profiles, not the member sheet, so they couldn't report a person to a community. The profile
     report now offers "Send to: Meldiq's team / your communities".
- **Emulator storage:** to fit the install, the stale `com.griipbuddy.Orbit.test` and
  `com.griipbuddy.orbit.androidtest` builds (this project's old test variants) were uninstalled. Nothing else was
  touched.
- **Physical phone:** **not yet tested.**

### B4 APK

- **File:** `/Volumes/code_files/meldiq_android/android/app/build/outputs/apk/debug/meldiq-test-b4-debug.apk`
  (147 MB, git-ignored).
- **SHA-256:** `2627390bef636ed63da08d9c2c0cbc78d98144ec166bf7f75c1235ef9449c915`.
- **Variant:** debug, side by side: `com.meldiq.android.test`, "Meldiq Test", versionCode 19 / `0.5.0-beta`.
- **Server:** `http://localhost:8080`, the local test server over USB, as for B2 and B3.

### Owner's phone checklist (B4)

**Before you start:**
1. **Server:** start the test server again from `/Volumes/code_files/meldiq_android` with
   `server/scripts/phone-test-server.sh`. It's now API 0.17.0 and updates the test data by itself.
2. **Install over B3:** `adb install -r android/app/build/outputs/apk/debug/meldiq-test-b4-debug.apk`.
3. **Accounts:** use your owner account and the second (member) account from B3 (You › Settings › Add account, then
   switch between them).

**Checks:**
1. **Report a message:**
   - As the member, long-press a message from your owner account › **Report** › Harassment or bullying.
   - **Expected:** the review step says "Goes to the moderators of …" and shows the message.
   - Add a note › **Send report**. **Expected:** "Report sent to the moderators".
2. **Report again:** report the same message. **Expected:** "You already reported this message".
3. **Report a person:**
   - As the member, open the owner's profile › **Report** › Spam.
   - Under "Send to", pick your community. **Expected:** the line changes to "Goes to the moderators of …".
   - Send.
4. **Queue:**
   - Switch to the owner › community name › Settings › Moderation › **Reports**.
   - **Expected:** "Open (2)", the reason, who was reported, the message text and the note, and **no** sign of who
     reported.
   - Mark one as handled with a note. **Expected:** it moves to Closed with "Handled by …".
   - Reopen it.
5. **Audit log:** Settings › Audit log › swipe the filters › **Reports**. **Expected:** only report entries. Tap
   "Anyone" and pick a person: only their changes.
6. **Security actions:**
   - Settings › **Security actions** › Pause joining › **1 hour**. **Expected:** "Paused until …".
   - As the member, try a new invite code in Join or create. **Expected:** "This community has paused new members".
   - Resume.
7. **Pause new DMs:**
   - Pause new DMs. As the member, try to start a new DM with someone in the community you haven't messaged before.
   - **Expected:** a message that new DMs are paused. An existing DM still works.
   - Resume.
8. **Invite choices:** Invite › **New invite**. **Expected:** uses up to 500, expiry from 1 hour to 30 days.
9. **Languages:** switch to 中文 or বাংলা and open the report sheet and the Reports screen: the text is translated.
10. **Report back:** pass or fail per step, a screenshot of anything wrong, and the phone model.

## 7. Things the owner should know (carried over)

1. **A test sign-up reached production on 2026-10-21:** `ada@meldiq.test`, unverified, no keys. Not deleted (owner's
   call).
2. **The AWS tools use the root user's access key:** move them off it (`MELDIQ_AWS_ARCHITECTURE.md` §5).
3. **Server 0.17.0 isn't deployed** (production runs 0.13.0): 0.14.0 migration 034 (timeouts); 0.15.0 B2's preference fields and templates; 0.16.0 migration 035 (slowmode) and B3's fields; 0.17.0 migration 036 (reports, pauses). For deployment, set `REPORTS_KEY` (or back up `reports.key` with the database).
4. **Android database version 11:** a forward migration.

## 8. Next, exactly

1. **Owner:** the B4 phone test (§6), then approve or report.
2. **Owner:** decisions listed in `MELDIQ_IMPLEMENTATION_PLAN.md` "Owner actions" (connections model, fingerprints,
   test accounts).
3. **Engineering, only after B4 approval:** B4b:
   - join applications (COM-18);
   - raid alerts to moderators (MOD-10);
   - https invite links with a signed-out preview and invite from a DM (`MELDIQ_COMMUNITY_INVITATION_SPEC.md`
     INV-B-01/02/05);
   - Meldiq's operator review tool for reports sent to Meldiq.
4. **Don't change before B4 feedback:**
   - Android: `moderation/*`, the report entry in `ConversationScreen.kt`, `ProfileScreens.kt`, `OrgScreens.kt`'s
     member sheet and invite form, `AdminScreens.kt`'s audit filters, `CommunitySettingsScreen.kt`'s moderation
     group;
   - server: `routes/reports.ts`, `reports/`, `mongo/reports.ts`, the security route and its checks.

   Only reported defects get fixed there.
