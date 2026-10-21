# Session handoff (2026-10-21, gated-batch round)

For the next session: where everything is, what was done and verified, what isn't, and exactly what comes next. The
batch plan and its approval gate are in `MELDIQ_IMPLEMENTATION_PLAN.md`; the status of every feature is in
`MELDIQ_FEATURE_GAP_MATRIX.md`.

> **Gate changed 2026-10-21 23:13:** B1, B2 and B3 were phone-tested and approved by the owner. The owner then asked
> for the remaining batches to be built one after another without waiting, and to phone-test them all together at the
> end. Each batch is committed on its own with its own APK and checklist below, so a defect can be traced to its batch.
> **Nothing from B4 onwards has been tested on a real phone yet**; emulator checks are listed per batch.

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

## 6. Batch B4: administration, part 1 (built 2026-10-21, not phone-tested)

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

## 6b. Batch B4b: administration, part 2 (built 2026-10-22, not phone-tested)

### What's in it

| Feature | What the person sees | Code |
|---|---|---|
| Join applications (COM-18, Discord ADMIN-CTRL-008) | <ul><li>Community settings › Community › **Join questions** (public communities, people who can edit the community): up to 5 questions, Save, or **Turn off applications**;</li><li>Discover › the community › **Apply to join**: an answer for every question, **Send application**, then "Your application is waiting for the moderators" with **Withdraw application**;</li><li>after a decline: "Your last application wasn't accepted. You can apply again";</li><li>while the questions are on, open joining is refused (`application_required`)</li></ul> | Android `moderation/ApplicationsScreen.kt` (`ApplySheet`, `JoinQuestionsSheet`), `DiscoverScreen.kt`, `ApplicationRules.kt`, `ApplicationRepository.kt`. Server `routes/applications.ts`, migration 037, permission `members.approve` (Moderator and Community Manager templates) |
| Applications queue | Community settings › Moderation › **Applications**: Waiting (n) / Answered; each shows the person, when, and every question with its answer; **Let in** (they join, with the usual ban, pause and member-limit checks) or **Decline**, each with an optional note; "Let in by …" afterwards. Audited as `application.approved` / `application.denied` | `ApplicationsScreen.kt`, route `applications/{orgId}` |
| Activity alerts (MOD-10, ADMIN-CTRL-002) | When 10 or more people join within 10 minutes, the people who can pause joining see "N people joined within 10 minutes" with **Security** in Community settings, for 24 hours; at most one alert per 30 minutes; nothing is paused automatically. Security actions › **Activity alerts** on/off | Server `workspace/activityAlerts.ts`; Android `CommunitySettingsScreen.kt`, `SecuritySheet.kt` |
| https invite links (COM-05, INV-B-01/02) | Invitations are shared and drawn as `https://meldiq.app/invite/CODE`. Tapping one inside a Meldiq chat opens Join with the code and preview. Outside the app, Android opens Meldiq for it once `meldiq.app` serves `assetlinks.json` (owner, below); the old `meldiq://` links and QR codes still work | `InviteScan.kt`, `AndroidManifest.xml`, `MainActivity.kt`, `ConversationScreen.kt` |
| Signed-out invite page | Web repo: `/invite/CODE` shows the community's name, description and size (never channels or members), the code, **Open in the Meldiq app**, **Copy the code**, **Use Meldiq Web**; clear messages for invalid, expired, revoked and used-up links, and for paused joining | `orbit_web` commit `2ed45e4`: `public/invite.html`, `public/invite.js`, nginx `location ^~ /invite/`; server `GET /v1/invites/:code/public` (30 per minute per address) |
| Invite from a profile (DM-08, INV-B-05) | Someone's profile › **Invite** › one of your communities (not ones where you're a guest): a one-use invitation valid 7 days, sent as the https link in your DM with them; the DM opens | `profile/InviteToCommunitySheet.kt` (`ProfileInvite`) |
| Meldiq's operator commands for reports | On the server: `admin reports [closed]`, `admin report-show <id>`, `admin report-close <id> actioned\|dismissed [note]` for reports sent to Meldiq's team | `server/src/admin.ts` |

**Server and API:** API **0.18.0**, features `join-applications`, `activity-alerts`, `invite-links`. **Migration 037**
(additive): `orgs.join_questions`, `orgs.activity_alerts` (default on), `orgs.raid_alert_at`, `orgs.raid_alert_count`,
and a `join_applications` table (MongoDB domain `workspace`). Also fixed: the admin `cleanup` command lacked the reports
store, so purging closed reports there would have failed.

**New Android strings** in three languages: `ja_*`, `pi_*`.

### How it was checked

- **Server:** `npx vitest run`: **670 passed**, 10 skipped (`applications_alerts.test.ts`, 8 tests: questions and
  limits, answers required, one pending application, bans and private communities, approve joins the person, deny,
  withdraw, paused joining, open joining refused while questions are on, the alert after 10 joins and only for
  moderators, turning alerts off, the public invite preview).
- **Android:** `testDebugUnitTest` **598 tests, 0 failures** (new `ApplicationRulesTest`, `InviteScanTest` updated);
  `lintDebug` 0 errors.
- **Web:** `npm test` 260 passed, eslint and prettier clean; the page checked in a browser against the local server
  (valid and invalid codes).
- **Emulator** (local server, Ada in the main app, Erin in "Meldiq Test"):
  - `https://meldiq.app/invite/CODE` sent to the app opened Join with the code and preview; Ada joined;
  - Erin › Discover › Apply Lab › Apply to join › answered both › waiting; Ada › Applications › Waiting (1) showed both
    answers › Let in with a note › Answered "Let in by Ada · Welcome"; Erin became a member and the preview offered Open;
  - 10 test accounts joined by script: Ada saw "10 people joined within 10 minutes" › Security › the Activity alerts
    switch turned off and on (stored on the server);
  - Join questions: added a third question and saved ("On · 3 questions");
  - Carol's profile › Invite › Apply Lab: the DM opened with the https link (one use, 168 hours on the server); tapping
    the link in the chat opened Join.
- **Not checked:** App Link verification on a real phone (needs `assetlinks.json` on meldiq.app); the web page on the
  real domain; Chinese and Bengali on screen.

### B4b APK

- **File:** `/Volumes/code_files/meldiq_android/android/app/build/outputs/apk/debug/meldiq-test-b4b-debug.apk`
  (147 MB, git-ignored). **SHA-256:** `15fef42d5844dde4fc9f06a0f5e86f7f21ce260514e050d734391c1f99f09b82`.
- **Variant:** debug, side by side: `com.meldiq.android.test`, versionCode 19 / `0.5.0-beta`, server
  `http://localhost:8080` (the local test server over USB). It contains B4 as well, so B4 and B4b can be tested with
  this one APK.

### Owner's phone checklist (B4b)

**Before you start:** server from `/Volumes/code_files/meldiq_android` with `server/scripts/phone-test-server.sh` (API
0.18.0); `adb install -r android/app/build/outputs/apk/debug/meldiq-test-b4b-debug.apk`; your owner account and the
member account. Your test community must be **listed in Discover** (Settings › Overview).

1. **Questions:** as the owner, Settings › **Join questions** › two questions › Save. **Expected:** "On · 2 questions".
2. **Apply:** as a person who isn't a member (a third account, or leave the community with the member account first),
   Discover › your community › **Apply to join** › answer › Send. **Expected:** "Your application is waiting".
3. **Queue:** as the owner, Settings › Moderation › **Applications**. **Expected:** both answers. **Let in** with a
   note. **Expected:** Answered "Let in by …", and the person sees the community.
4. **Decline and apply again:** repeat 2 and 3 with **Decline**. **Expected:** the person sees "wasn't accepted. You can
   apply again".
5. **Invite from a profile:** open someone's profile › **Invite** › a community. **Expected:** your DM with them opens
   with a `https://meldiq.app/invite/…` link. On their phone, tapping it in the chat opens Join.
6. **Activity alerts:** Settings › Security actions › **Activity alerts** is on; turn it off and on. (A join spike needs
   10 joins within 10 minutes; Claude checked that on the emulator.)
7. **Languages:** 中文 or বাংলা: the Applications screen and the apply sheet are translated.
8. **Report back:** pass or fail per step, a screenshot of anything wrong, and the phone model.

## 6c. Batch B5: chat interface (built 2026-10-22, not phone-tested)

### What's in it

| Feature | What the person sees | Code |
|---|---|---|
| Thread list per channel (CH-18, Discord CHAN-CTRL-011) | A channel's header › **Threads** (speech-bubble icon, text channels only): every thread this phone has, latest reply first, each with its first line, who started it, the number of replies and when; **Search threads**; "No threads here yet. To start one, long-press a message › Reply in thread." Tapping one opens it | `conversation/ThreadsSheet.kt`, `data/model/ThreadListRules.kt`, DAO `observeThreadRoots` (a query, no schema change) |
| Message links (MSG-20, CHAT-MSG-004) | Message menu › **Copy message link** → `https://meldiq.app/m/CONVERSATION/MESSAGE` (ids only, no content). Tapping it in a chat, or opening it from another app, shows the conversation at that message (inside its thread for a reply). Not in that conversation: "You can't open this message: you're not in that conversation". Message not on this phone: the conversation, with a notice | `data/model/MessageLinks.kt`, `ui/MessageLinkOpener.kt`, `ConversationScreen.kt` (`focusMessage`), `MainActivity.kt`, `AppRoot.kt` (route `conversation/{id}?thread=&message=`), manifest (`/m/` path, `meldiq://m/`) |
| Unreads (NAV-04, NAV-CTRL-005) | Activity › **Unreads** chip: conversations with unread messages, "Direct messages and groups" first, then each community by name; muted, archived, left and hidden channels left out; each row has its count and **Mark as read**; **Mark all read** at the top; "All caught up" when empty. The All chip doesn't repeat them | `home/ActivityFeedScreen.kt` (`ActivityRules.unreads`) |
| Translations (MSG-19) | The message menu, message info (status values too), forward dialog, the file warning, the composer's buttons and placeholders, the header, banners (server unreachable, key changed, can't post) and conversation info in Chinese and Bengali | `ConversationScreen.kt`, `ConversationInfoScreen.kt`; strings `b5_*` (about 120) |

No server change in B5.

### How it was checked

- **Android:** unit tests (new `ChatInterfaceRulesTest`: links, thread names and search, unreads grouping and
  exclusions, the Unreads chip, routes) and the full suite; lint.
- **Emulator** (Ada): #general › Threads showed the empty state; long-press › Reply in thread › sent a reply ›
  Threads listed it ("You · 1 reply"); long-press › **Copy message link** put
  `https://meldiq.app/m/c_…/683e3b6b-…` on the clipboard; opening that link from outside the app showed #general;
  Activity › Unreads listed #introductions under its community; Mark as read emptied it; with the app in Chinese the
  whole message menu and message info were Chinese.
- **Found and fixed during the check:** the Unreads group used the channel's category name instead of the community's;
  the message status in Message info stayed English.
- **Not changed (B12):** dates such as "Yesterday" come from the shared time formatter, which is still English.

### B5 APK

- **File:** `/Volumes/code_files/meldiq_android/android/app/build/outputs/apk/debug/meldiq-test-b5-debug.apk`
  (147 MB, git-ignored). **SHA-256:** `89da079076fc5ee2a8e27da4288d5eedf82bb9a750ad652b8ec4f98ecf4f5426`.
- **Variant:** debug, side by side (`com.meldiq.android.test`, versionCode 19 / `0.5.0-beta`), server
  `http://localhost:8080`. It contains B4 and B4b too.
- **Checks:** `testDebugUnitTest` 605 tests, 0 failures; `lintDebug` 0 errors.

### Owner's phone checklist (B5)

1. **Threads:** in a channel, long-press a message › Reply in thread › send. Back in the channel, tap the speech-bubble
   button in the header. **Expected:** the thread with "1 reply"; search finds it by its first words.
2. **Message link:** long-press a message › **Copy message link**. Paste it into another chat and send it, then tap
   it. **Expected:** the first chat opens at that message.
3. **Not a member:** send that link to your other account in a DM, where that account isn't in the channel; tap it
   there. **Expected:** "You can't open this message".
4. **Unreads:** from the other account, send a message to a channel. On the first account, Activity › **Unreads**.
   **Expected:** the channel under its community's name; **Mark as read** removes it.
5. **Languages:** set the app to 中文 or বাংলা and long-press a message: every menu item is translated; open Message
   info.
6. **Report back:** pass or fail per step, with screenshots.

## 6d. Batch B6: connections, message requests and DM permissions (built 2026-10-22, not phone-tested)

The connections model reverses matrix row DM-04's "no friend graph". It was built under the owner's overnight
go-ahead ("you can keep working all the steps one by one"); **say so if you'd rather not have it**, and it can be
switched off by not showing the screens (the server keeps working without them).

### What's in it

| Feature | What the person sees | Code |
|---|---|---|
| Who can message you (DM-03) | Settings › Privacy & Security › Messages: **Who can message you directly** (Everyone / People in your communities / Only your connections) and **Who can send you requests** (Everyone / People in your communities / Nobody) | Android `profile/MessagingPrivacy.kt`. Server `PUT /v1/users/me/messaging`, `relationships/rules.ts` |
| Message requests | A DM from someone your first setting doesn't allow (but the second does) arrives under DMs › **Message requests (n)**, with no notification and no badge; it opens read-only: "X wants to message you", **Accept** / **Decline** / **Block**. Accept moves it to your DMs and connects you; Decline removes it from this phone; Block also blocks. The sender sees "Message request sent… You can send N more" (3 in all) and never learns of a decline. No calls through a request, either way. Someone your settings refuse gets "This person only accepts messages from people they're connected with"; blocked and unknown people get the same "You can't message this person" | Android `contacts/ConnectionsScreens.kt` (`RequestsScreen`), `ConversationScreen.kt` (`MessageRequestBar`, outgoing banner), `ChatsScreen.kt`, `RequestRules`. Server `routes/conversations.ts` (DM creation, `?requests=1`), `routes/messages.ts` (limit, no push, nothing delivered after a decline), `routes/calls.ts` |
| Connections (DM-04) | DMs › ⋯ › **Connections**: Requests for you (Accept / Decline), Sent (Cancel), Connected, and **Add by username**. A profile's button: **Connect** / **Request sent** (tap to cancel) / **Accept** / **Connected** (tap to remove, with a confirmation). Crossed requests connect at once. Nobody is told of a decline or removal | `ConnectionsScreens.kt` (`PeopleScreen`, `ConnectionButton`), `ConnectionRules`, `ConnectionRepository`. Server `routes/connections.ts` |

**Server and API:** API **0.19.0**, features `connections` and `message-requests`. **Migration 038** (additive):
`connections`, `dm_requests`, `messaging_settings`, all in the new MongoDB domain **`relationships`** (SQLite and
MongoDB stores, tests on both). 20 new requests an hour per person. Account deletion removes a person's rows.

**Default for people who never chose:** `DEFAULT_DM_POLICY`, shipped as **`everyone`** (today's behaviour). Apps
released before B6 can't show message requests: a person on an old app couldn't reply to one and wouldn't know why.
Once most people run an app with B6, set `DEFAULT_DM_POLICY=communities` (strangers' DMs then become requests).

**Android database version 12** (adds `conversations.request`, a forward migration).

**Moved to B6b** (to keep this batch testable): group photo (DM-07) and presence choices (NAV-08).

### How it was checked

- **Server:** full suite **685 passed**, 10 skipped; `connections_requests.test.ts` (15 tests: defaults, direct DMs
  between community members, a guest isn't "in the community", requests and `?requests=1`, refusals, blocked and
  unknown answering the same, the 3-message limit with no push, the recipient can't reply or call, decline hides it and
  drops queued envelopes, reopening the DM yourself after declining, accept connects, block, the hourly limit,
  connections by id and username, nobody else can accept, crossed requests, remove, connections making DMs direct,
  request settings, account deletion, the server default). The same file passes with
  `MELDIQ_TEST_MONGO_DOMAINS=relationships` (MongoDB store).
- **Android:** unit tests (new `ConnectionRulesTest`; `MigrationsTest` covers v12) and lint, in the B6 build below.
- **Emulator** (Ada in the main app, Erin in "Meldiq Test", two scripted strangers):
  - Ada › Privacy › Who can message you directly › Only your connections (stored on the server);
  - two strangers each sent a DM: **no notification**; DMs showed "Message requests · 2 waiting"; the list showed
    both with their first message;
  - Quinn's request opened read-only with the request bar; **Accept** brought back the composer; Ada's reply was
    accepted by the server; Quinn appeared under Connected;
  - Rory's request › **Decline**: gone from Requests and DMs (server: declined);
  - Connections › Add by username `erinsender4910` › "Request sent", listed under Sent; on Erin's app, Connections
    showed "Requests for you: Ada", and Ada's profile showed **Accept**; accepting connected them (server: accepted).
- **Found and fixed during the check:** the DMs tab badge and Home counted requests (now they don't); the profile
  button could fall back to "Connect" after accepting (a cancelled reload blanked it); call buttons showed on a request
  and the server would have let the sender ring (both closed).
- **Not checked on the emulator:** the sender's "You can send N more" banner (unit-tested state, server-tested limit).
- **Emulator note:** the emulator's storage is too small for two 147 MB debug builds; the copies installed there had
  the two unused CPU architectures removed and were re-signed with the same debug key. The phone APK below is the full
  build.

### B6 APK

- **File:** `/Volumes/code_files/meldiq_android/android/app/build/outputs/apk/debug/meldiq-test-b6-debug.apk`
  (147 MB, git-ignored). **SHA-256:** `5756ceda8aad992f15169aa16e80105fde38a3942bd7689631bcb870256f00b3`.
- **Variant:** debug, side by side (`com.meldiq.android.test`, versionCode 19 / `0.5.0-beta`), server
  `http://localhost:8080`. It contains B4 to B5 as well.
- **Checks:** `testDebugUnitTest` 609 tests, 0 failures; `lintDebug` 0 errors.
- **Server for this APK:** `server/scripts/phone-test-server.sh` from the main folder (API 0.19.0; it applies
  migration 038 to the test data by itself).

### Owner's phone checklist (B6)

You need two accounts on two phones, or your two accounts on one phone (Meldiq Test lets you switch).

1. **Settings:** on account A, You › Settings › Privacy & Security › **Who can message you directly** › Only your
   connections.
2. **A request arrives:** from account B (not connected with A), open A's profile (search by username) › Message ›
   send "hello". **Expected on B:** "Message request sent… You can send 2 more". **Expected on A:** no notification;
   DMs shows **Message requests · 1 waiting**.
3. **Answer it:** on A, open the request. **Expected:** "B wants to message you" with Accept, Decline, Block, and no call
   buttons. Tap **Accept**. **Expected:** you can reply, and B is under DMs › ⋯ › Connections › Connected.
4. **Decline (needs a third account C):** from C, send A a DM. On A, open the request › **Decline**. **Expected:** it
   disappears from A; C still sees "Message request sent".
5. **Connections:** on A › DMs › ⋯ › Connections › Add by username › B's username › Send. **Expected:** "Request sent".
   On B, the profile of A shows **Accept**; tap it. **Expected:** both show Connected.
6. **Refusal:** on A set **Who can send you requests** › Nobody. From a third account, try to message A. **Expected:**
   "This person only accepts messages from people they're connected with".
7. **Languages:** 中文 or বাংলা on the Connections screen and the request bar.
8. **Report back:** pass or fail per step, with screenshots.

## 6e. Batch B6b: group photo and presence choices (built 2026-10-22, not phone-tested)

### What's in it

| Feature | What the person sees | Code |
|---|---|---|
| Group photo (DM-07, Discord GDM-CTRL-002) | Group info › **Add a group photo** / **Change group photo** / **Remove group photo** for the group's admins ("Everyone in the group sees it. Like the group's name, it isn't end-to-end encrypted."). The photo replaces the group icon in the chat list and the header | Android `ConversationInfoScreen.kt`, `ConversationRow.kt` (`GroupPhoto`). Server `PUT/DELETE/GET /v1/conversations/:id/photo` (groups only, members only) |
| Presence (NAV-08, NAV-CTRL-010) | You › **Presence**: Online (automatic), Idle ("Shows you're away"), Do not disturb ("Pauses notifications on all your devices for as long"), Invisible ("You appear offline, and your last seen doesn't change"); then 1 hour, 8 hours, 24 hours, 7 days or Until I change it. Others see an amber dot and "Idle", or a red dot and "Do not disturb", on profiles and in the DM header; an invisible person shows as offline with their old last-seen time | Android `profile/PresenceSheet.kt`, `PresenceRules`, `PresenceDot` in `Display.kt`, `UserAvatar.kt`. Server `PUT /v1/users/me/presence`, `profile.ts` (`shownPresence`), `ws.ts` (`announcePresence`), `hub.ts` (last seen kept while invisible) |

**Server and API:** API **0.20.0**, features `group-photos` and `presence-modes`. **Migration 039** (additive):
`conversations.photo_rev`, `users.presence_mode`, `users.presence_until`. Presence follows each person's existing
"last seen" privacy setting: people who may not see it see nothing.

**Android database version 13** (adds `conversations.photoRev`, `users.presence`).

### How it was checked

- **Server:** full suite **691 passed**, 10 skipped; `group_photo_presence.test.ts` (6 tests: only admins set the photo,
  members see it, non-members get 404, not a JPEG refused, remove, groups only; online while connected, idle and do
  not disturb shown to others and announced live, invisible shown as offline in profiles, member lists and presence
  queries, connecting and leaving while invisible announce nothing and keep last seen, back to automatic, bad choices
  and end times refused, a timed choice ending by itself). `profile_privacy.test.ts` still passes: presence is absent,
  like other hidden fields, for people who may not see it.
- **Android:** `PresenceRulesTest`; `MigrationsTest` covers v13; full unit suite and lint in the APK build below.
- **Emulator** (Ada and Erin):
  - Ada › You › Presence › Idle › 1 hour (server: idle); Erin opened Ada's profile: **"Idle"** with an amber dot;
  - Ada › Invisible › Until I change it; Erin saw **"Last seen 6 min ago"** while Ada's app was open; Ada › Online;
  - Ada › New group "Lab trip" (with Carol and Erin) › Group info › Add a group photo › picked an image › the row
    became Change / Remove group photo and the header shows the photo (server: photo_rev set).
- **Found and fixed during the check:** after choosing Idle the You screen still said "Online" (it read a profile copy
  that only some calls refreshed).
- **Not checked:** do not disturb pausing notifications on a second device (it uses the existing pause, which B1–B3
  covered); the web shows idle and do not disturb as plain online.

### B6b APK

- **File:** `/Volumes/code_files/meldiq_android/android/app/build/outputs/apk/debug/meldiq-test-b6b-debug.apk`
  (147 MB, git-ignored). **SHA-256:** `a61b92734b77785b758771aa61191ebd8f1c4452dc5d27809e181054074add78`.
- **Variant:** debug, side by side (`com.meldiq.android.test`, versionCode 19 / `0.5.0-beta`), server
  `http://localhost:8080` (API 0.20.0 from `server/scripts/phone-test-server.sh`). It contains B4 to B6 as well: **this
  is the one APK to install for testing B4 to B6b together.**
- **Checks:** `testDebugUnitTest` 612 tests, 0 failures; `lintDebug` 0 errors.

### Owner's phone checklist (B6b)

1. **Idle:** You › Presence › Idle › 1 hour. **Expected:** the row says Idle until …. On your other account, open this
   account's profile: **Idle** with an amber dot.
2. **Do not disturb:** You › Presence › Do not disturb › 1 hour. **Expected:** "Pause notifications" shows paused until
   the same time; a message from the other account makes no sound or notification; the other account sees **Do not
   disturb** with a red dot.
3. **Invisible:** You › Presence › Invisible › Until I change it. **Expected:** the other account sees "Last seen …",
   not Online, even while this app is open. Set Online again.
4. **Group photo:** in a group you created › group info › **Add a group photo** › pick a picture. **Expected:** the
   photo in the chat list and header, on both accounts. Remove it.
5. **Languages:** 中文 or বাংলা in the Presence sheet.
6. **Report back:** pass or fail per step, with screenshots.

## 6f. Batch B7: media and large files (checked 2026-10-22; no code change)

**Finding:** what B7 planned is already built: the attach panel and picker, a preview with checks before sending,
progress on the bubble, retry and cancel (`docs/ATTACHMENTS.md`), chunked encryption for files over 25 MB, resumable
downloads (HTTP ranges), and, with S3 (production since 2026-10-17), direct multipart uploads that resume after a dropped
connection (`docs/AWS_STORAGE_PLAN.md`). Nothing was added, so there's no B7 APK: use the B6b APK.

**Checked on the emulator** (local server, disk storage): Ada sent a 100 MB file to Erin. The panel said "1 GB per
file on this server"; the preview warned that `.bin` files can run software; the upload finished in about 19 seconds
(over USB) with progress on the bubble; Erin downloaded it with progress, the open warning appeared, and the file on
Erin's phone had **the same SHA-256 as the original** (`a03635718f1a21e9…`).

**Still open, the owner's test (matrix MSG-16):** a 1 GiB file to production over real Wi-Fi and over mobile data,
including turning networks off and on mid-way. Production must allow it: `ATTACHMENT_MAX_BYTES` and the proxy's body
limit (`docs/ATTACHMENTS.md` › Large files).

### Owner's phone checklist (B7, against production with a release build)

1. Send a file of about 1 GB to your other account over Wi-Fi. **Expected:** progress, then sent; the other phone
   downloads it with progress and it opens.
2. Repeat over mobile data, and switch flight mode on and off once during the upload. **Expected:** it resumes, not
   restarts (S3 multipart), and completes.
3. Cancel an upload half-way (long-press › Cancel upload). **Expected:** it's removed and nothing arrives.
4. **Report back:** times for both networks, and any failure text.

## 6g. Batch B8: forums and polls (built 2026-10-22, not phone-tested)

B8 was split: forums and polls here; event recurrence, the Server Guide and onboarding questions are **B8b**.

### What's in it

| Feature | What the person sees | Code |
|---|---|---|
| Post guidelines (CH-11, FORUM-CTRL-006) | Edit channel › (forum) **Post guidelines** ("Shown at the top of the forum and to everyone writing a post"); a **Post guidelines** card at the top of the forum and in New post, the first lines with Show all | Server `PATCH /v1/conversations/:id {guidelines}` (forums only, managers, 2,000 characters; audited), migration 040. Android `Forum.kt` (`ForumGuidelinesCard`), `ChannelEditSheets.kt` |
| Tag matching (FORUM-CTRL-002) | With two or more tags chosen, a chip switches between **Any of these tags** and **All of these tags** | `ForumRules.filter(matchAll)` |
| Reactions on post cards (FORUM-CTRL-004) | A post card shows its most-used reaction and how many people used it, e.g. "👍 1" | `ForumRules.topReaction` |
| Follow (FORUM-CTRL-005) | A post's or thread's header: **Follow** / **Following**; replies in followed threads show in Activity › Threads even if you never wrote there. Kept on this phone | `FollowedThreads`, `observeThreadReplies(followed)` |
| Poll duration and answer emoji (MSG-21, POLL-CTRL-001) | Create a poll › an emoji button per answer; **Voting lasts** 1 hour / 4 hours / 8 hours / 24 hours (default) / 3 days / 7 days / Until I close it. The poll says "Ends …", then closes by itself | `Polls.kt`, `PollRules` (`compose`, `closed`, `vote(at)`, `cleanEmoji`); web `orbit_web` commit `6ecfc81` (same rules) |

**Server and API:** API **0.21.0**, feature `forum-guidelines`, **migration 040** (`conversations.guidelines`). Poll end
times and emoji are inside the end-to-end encrypted message: the server never sees them. A vote counts only if the
server accepted it before the end (every device applies the same rule with the server's time).

### How it was checked

- **Server:** full suite **693 passed**, 10 skipped (`forum_guidelines.test.ts`: managers only, members see them,
  removing, forums only, length).
- **Android:** `ForumPollRulesTest` (8 tests) and the full suite: **620 tests, 0 failures**; lint 0 errors.
- **Web:** `npm test` 262 passed (new `polls.test.ts`), typecheck, eslint and prettier clean.
- **Emulator** (Ada): Apply Lab › #introductions › Edit channel › Forum on, guidelines typed, Save (server: forum with
  guidelines); the forum showed the guidelines card, and so did New post; posted "AdaHere"; opened it: **Follow** →
  **Following**; reacted 👍 and the card showed "👍 1"; in #general created a poll with 😀 on the first answer and
  1 hour: the poll shows the emoji and "Ends 3:31 AM · Today".
- **Not checked on the emulator:** Any/All tag switching (unit-tested), a followed thread's reply arriving in
  Activity from another account, a poll closing by itself at its end time (unit-tested).

### B8 APK

- **File:** `/Volumes/code_files/meldiq_android/android/app/build/outputs/apk/debug/meldiq-test-b8-debug.apk`
  (147 MB, git-ignored). **SHA-256:** `e12db2c2bc5996fec31eaee6b947e81f8f5bf7c9344f01c0c94ad0160ea86ddc`.
- **Variant:** debug, side by side, server `http://localhost:8080` (API 0.21.0). It contains everything since B4: **the
  one APK to install for testing B4 to B8 together.**

### Owner's phone checklist (B8)

1. **Guidelines:** in your community, long-press a channel › Edit channel › Forum on › type Post guidelines › Save.
   **Expected:** the forum shows them at the top; New post shows them too.
2. **Tags:** give the forum two tags; post once with one tag and once with both. Choose both tags in the filter and
   tap **Any of these tags** so it says **All of these tags**. **Expected:** only the post with both.
3. **Reaction on the card:** react to a post with 👍 from both accounts. **Expected:** the card shows "👍 2".
4. **Follow:** from the second account, open the first account's post › **Follow**. From the first account, reply in
   it. **Expected:** the reply shows in the second account's Activity › Threads.
5. **Poll:** create a poll with an emoji on each answer and **1 hour**. **Expected:** "Ends …" with the time; vote from
   both accounts. After the hour it says Closed and no one can vote.
6. **Report back:** pass or fail per step, with screenshots.

## 6h. Batch B8b: repeating events, community guide, onboarding questions (built 2026-10-22, not phone-tested)

### What's in it

| Feature | What the person sees | Code |
|---|---|---|
| Repeating events (MSG-13, EVENT-CTRL-002) | New event › **Repeats**: Does not repeat / Weekly on Thursday / Every other Thursday / Monthly on the fourth Thursday / Every year (named from the chosen date). The events list says how it repeats. When one occurrence ends, the event moves to the next (same people and answers; a new "starting soon" notice) | Server `workspace/eventRepeat.ts`, `MeetingService.rollRepeating`, migration 041. Android `MeetingsScreen.kt`, `EventRepeatRules` |
| Community guide (COM-17, COMM-CTRL-003/004/005) | On the community's home: **Community guide** card with the welcome line, **Get started · n of m** tasks (each opens a channel and is ticked once opened, on this phone), and **Resources**; × hides it for now. Community settings › **Community guide** edits it | Server `routes/guide.ts`, `orgs.guide`. Android `GuideOnboarding.kt` (`GuideCard`, `GuideEditorSheet`), `CommunityGuideRepository` |
| Onboarding questions (COM-07) | Members who haven't answered see "This community asks a few questions to set things up for you" › **Answer**: one card per question (choose one / several; * required) › Done. Community settings › **Onboarding questions** (editors): turn on, add questions, answers that give roles and open public channels; members: **My onboarding answers** | Server `routes/onboarding.ts`, migration 042. Android `OnboardingSheet`, `OnboardingEditorSheet`, `OnboardingRules` |

**Safety:** onboarding can never give a role with an administrative permission (checked when the questions are saved
and again when someone answers, so a role that gains such a permission later stops being given) and can only open public
channels. Answering again takes back only roles that onboarding gave (`assigned_by = 'onboarding'`).

**Server and API:** API **0.22.0**, features `event-repeat`, `onboarding`, `community-guide`; **migrations 041**
(`meetings.repeat`) **and 042** (`community_onboarding`, `onboarding_answers`, `orgs.guide`; workspace domain).

### How it was checked

- **Server:** full suite **702 passed**, 10 skipped; `events_onboarding_guide.test.ts` (9 tests: weekly across a
  daylight-saving change in New York, every other week, monthly third and missing-fifth weekday, December to January,
  yearly 29 February, the current occurrence; a weekly event moving on and "none" turning it off; onboarding: editors
  only, admin roles and private channels refused, required and one-answer rules, roles and channels given, answering
  again takes them back, a role that gained an admin permission isn't given, guests refused; the guide: editors only,
  members see it, an archived channel's task disappears, removing it).
- **Android:** `GuideOnboardingRulesTest` (5 tests) and the full suite: **625 tests, 0 failures**; lint 0 errors.
- **Emulator** (Ada, with a guide and a question set on Apply Lab by its owner through the API): the home showed the
  onboarding banner and the guide card ("Get started · 0 of 2"); tapping "Read the welcome" opened #welcome and, back
  home, it said **1 of 2**; Answer › 🎨 Painting › Done: the banner went away and the server shows the "Painter" role
  given by onboarding; New event showed **Repeats** with "Weekly on Thursday" and "Every other Thursday".
- **Not checked on the emulator:** the two editors' screens (server-tested), an event actually rolling over (tested
  on the server with a moved clock).

### B8b APK

- **File:** `/Volumes/code_files/meldiq_android/android/app/build/outputs/apk/debug/meldiq-test-b8b-debug.apk`
  (147 MB, git-ignored). **SHA-256:** `0820620d8dae9de2b1aecde9bb6eee5d20039f0bc3f93b0601bee0096ed2657e`.
- **Variant:** debug, side by side, server `http://localhost:8080` (API 0.22.0). It contains everything since B4: **the
  one APK to install for testing B4 to B8b together.**

### Owner's phone checklist (B8b)

1. **Guide:** Community settings › **Community guide** › a welcome line, two tasks (each with a channel), one resource
   › Save. **Expected:** the card on the community's home; open a task's channel and come back: it's ticked.
2. **Onboarding:** Community settings › **Onboarding questions** › turn on › Add a question (one answer, required) with
   two answers, one giving a role you made (without admin permissions) and a channel › Save › Save. **Expected:** on
   your second account, the community shows "This community asks a few questions…" › Answer › pick › Done: the role is
   on that member (Members › their roles) and the channel is in their list.
3. **Refusal:** try giving an answer a role that has an admin permission (e.g. one with "Ban members"). **Expected:**
   "Onboarding can only give this community's roles without administrative permissions".
4. **Repeating event:** Events › New event › Repeats › Weekly … › Save. **Expected:** the list says "Weekly on …".
5. **Report back:** pass or fail per step, with screenshots.

## 6i. Batch B9: community emoji and stickers (built 2026-10-22, not phone-tested)

### What's in it

| Feature | What the person sees | Code |
|---|---|---|
| Managing them (EXP-04/05, EXPR-CTRL-002) | Community settings › **Emoji & stickers** (people with the new permission **Manage emoji and stickers**, in the Community Manager template; owners and admins): Emoji · n/50 and Stickers · n/20, each with **Upload** (pick a picture, name it `:like_this:`), tap to rename, bin to remove. PNG, GIF and WebP up to 256 KB are kept as they are; other pictures are shrunk (128 px emoji, 320 px stickers) | Server `routes/expressions.ts`, migration 043, permission `expressions.manage`. Android `community/ExpressionsScreen.kt`, `ExpressionRepository` |
| Using them | In a community's channels, the emoji sheet has a **Community** tab: tapping an emoji puts `:name:` in the message; tapping a sticker sends it. Messages draw the emoji inside the text and stickers at 140 dp | `RichViews.kt` (`CommunityExpressionsPicker`), `MessageBubble.kt` (inline content), `ui/components/CustomEmoji.kt` |
| How it travels | The text keeps `:name:`; inside the encrypted message go the emoji's id, community and **SHA-256**. A phone downloads the image (members only), keeps it only if the hash matches, and caches it. People outside the community, older apps, the web app and notifications show `:name:` | `ExpressionRules`, `CustomEmojiRef`, `RichRules.fromPayload` |

**Not built:** custom emoji as reactions; animation (GIF/APNG/animated WebP are shown still); the web app draws them
as `:name:` (W1).

**Server and API:** API **0.23.0**, feature `community-expressions`, **migration 043** (`community_expressions`,
workspace domain; images are profile-photos blobs, removed with the emoji or the community).

### How it was checked

- **Server:** full suite **707 passed**, 10 skipped; `community_expressions.test.ts` (5 tests: PNG/APNG/GIF/WebP
  detection, only managers upload, name and image checks, 256 KB limit, duplicate names, members only see and fetch
  them and the image is the uploaded one, rename and remove with audit entries, the 50-emoji limit).
- **Android:** `ExpressionRulesTest` (5 tests) and the full suite: **630 tests, 0 failures**; lint 0 errors.
- **Emulator** (Ada): Apply Lab › Settings › Emoji & stickers › Upload › picked a 64 px PNG › named `party` › listed as
  `:party:` "Added by Ada"; in #general typed "Hello", emoji sheet › Community › `:party:` › sent: the message shows
  "Hello" with the image inline; Erin's phone notified "Ada: Hello:party:" (the text fallback).
- **Found and fixed during the check:** the Upload buttons were squeezed off the screen by the section heading.

### B9 APK

- **File:** `/Volumes/code_files/meldiq_android/android/app/build/outputs/apk/debug/meldiq-test-b9-debug.apk`
  (147 MB, git-ignored). **SHA-256:** `f30b760948cb0af2f2a429ce80077465df1ff670faafc3bc12c1c0a8af22e414`.
- **Variant:** debug, side by side, server `http://localhost:8080` (API 0.23.0). It contains everything since B4: **the
  one APK to install for testing B4 to B9 together.**

### Owner's phone checklist (B9)

1. **Upload:** Community settings › **Emoji & stickers** › Emoji › Upload › pick a picture › name it › Upload.
   **Expected:** listed as `:name:`.
2. **Use it:** in a channel, emoji button › **Community** › tap it › send. **Expected:** the picture inside your text,
   on both accounts.
3. **Sticker:** upload a sticker the same way, then Community › tap it. **Expected:** it's sent and shown large.
4. **Outside the community:** forward the message to a DM with someone who isn't in it. **Expected:** they see
   `:name:`.
5. **Permissions:** with a member account, Community settings has no Emoji & stickers row.
6. **Report back:** pass or fail per step, with screenshots.

## 6j. Batch B10: voice, video and camera (built 2026-10-22, not phone-tested)

### What's in it

| Feature | What the person sees | Code |
|---|---|---|
| Deafen (CALL-11, CALL-CTRL-004) | A **Deafen** pill above the call buttons. Deafened: you hear nobody (people who join later too) and your microphone is muted; **Deafened** again puts both back, and the microphone only comes back on if it was on before | `CallManager.toggleDeafen`, `applyDeafen`; `CallOverlay.kt` |
| Noise reduction (CALL-CTRL-006) | Settings › Calls › **Noise reduction** (on by default): background noise reduction and automatic level on the microphone. Applies from the next call | `SettingsStore.noiseReduction`; `CallManager` audio options |
| Pre-call check (CALL-CTRL-005) | Settings › Calls › **Check microphone and camera**: a level bar that moves when you speak, your camera picture, and **Use the back/front camera**. Nothing is recorded or sent; the camera is released when the sheet closes | `ui/screens/calls/PreCallCheck.kt` (`PreCallCheckSheet`, `MicMeter`) |
| Camera trace (CALL-04) | Nothing visible: each camera switch writes one diagnostics line with the side, camera id, track, size and the time it took, or "not confirmed" if the new camera never sent a frame | `CallManager` (`CallDiag` "camera switch: …") |
| Reconnect measurement (CALL-09) | Nothing visible: a short drop logs "reconnected in N ms"; a full rejoin logs "rejoined after N ms (k attempts)" | `CallManager` |
| **Fix:** "connection dropped" stuck after a reconnect | After a full reconnect the other person comes back without a "joined" event, so "…'s connection dropped. Waiting for them to reconnect…" stayed on screen although they were back. It now clears when the call reconnects with them present or their media arrives | `CallManager.peerBack` |
| **Fix:** calls blocked by the Bluetooth question | Declining Android's "nearby devices" question (used for Bluetooth headsets) no longer stops the call. Before, if the microphone had been allowed earlier, the call never started and the question came back on every tap | `CallPermissionRules.canCall` in `CallOverlay.kt` |

**Already there (checked, not rebuilt):** group-call layout (`CallTiles`, fixed 2026-10-17, `CallTilesTest`).

**Not built:** per-person volume; push-to-talk; a noise-reduction model of our own (this uses the phone's).

**Server:** no change (API 0.23.0).

### How it was checked

- **Android:** `PreCallCheckTest` (level meter maths) and `CallPermissionRulesTest`, and the full suite (see the
  B10 APK section for the count); lint 0 errors.
- **Emulator** (Ada → Erin, both apps on one emulator, local LiveKit): video call started after **Don't allow** on the
  nearby-devices question (failed before the fix: found during this check); Erin accepted; **Deafen** → "Deafened",
  diagnostics "deafen on", the microphone button showed **Unmute**; tapping again → "deafen off" and **Mute**; camera
  flip on Erin's side → "camera switch: facing back id 10 … 1280x720 done in 1748 ms"; the chat shows "Outgoing video
  call · 1 min 1 s". Pre-call check: front and back camera pictures shown, and the camera released on close.
- **Reconnect** (LiveKit paused for 30 s, then resumed): both apps showed Reconnecting and came back, "reconnected in
  4374 ms" and "4171 ms"; the call went on, "End-to-end encrypted". On the first try Erin's screen kept "Ada's
  connection dropped…" after the reconnect: fixed (table above) and checked again: gone.
- **Not checked on the emulator:**
  - the far side's picture: two apps on one emulator share one camera, so frames arrived in one run and not in the
    next;
  - whether deafen silences real sound;
  - the microphone level bar moving (the emulator microphone is silent);
  - a phone losing its own network: the test above froze the media server instead.

### B10 APK

- **File:** `/Volumes/code_files/meldiq_android/android/app/build/outputs/apk/debug/meldiq-test-b10-debug.apk`
  (148 MB, git-ignored). **SHA-256:** `b769e08977ae01bed51e47339c8fb4818003579009ba2366d0ab4cfc0b335766`.
- **Variant:** debug, side by side, server `http://localhost:8080` (API 0.23.0, unchanged). It contains everything
  since B4: **the one APK to install for testing B4 to B10 together.**
- **Checks:** 632 Android unit tests, 0 failures; lint 0 errors.

### Owner's phone checklist (B10) — two phones, two accounts

1. **Bluetooth question:** on a phone that never answered it, start a call and tap **Don't allow** on "nearby
   devices". **Expected:** the call still starts.
2. **Deafen:** in a call, tap **Deafen**. **Expected:** you hear nothing, the other phone hears nothing from you, and
   the pill reads **Deafened**. Tap again: sound comes back, and your microphone only comes back if it was on before.
3. **Camera switch:** in a video call, flip the camera several times (front, back, front). **Expected:** the other
   phone sees each switch within about 2 seconds, never a frozen or mirrored back camera. If it fails, run
   `adb logcat -s CallDiag` and send the "camera switch:" lines.
4. **Noise reduction:** Settings › Calls › Noise reduction off, then a call near music or a fan, then on. **Expected:**
   with it on, background noise is quieter on the other phone.
5. **Pre-call check:** Settings › Calls › **Check microphone and camera**. **Expected:** the bar moves when you speak;
   your picture shows; **Use the back camera** switches; closing it turns the camera light off.
6. **Network drop:** in a call, turn Wi-Fi off for 10 seconds and back on, on one phone. **Expected:** "Reconnecting…",
   then the call goes on, with no "connection dropped" message left on the other phone.
7. **Report back:** pass or fail per step, with screenshots.

## 6k. Batch B11: screen sharing and game streaming (built 2026-10-22, not phone-tested)

### What's in it

| Feature | What the person sees | Code |
|---|---|---|
| Share choice (CALL-05/06) | **Share** opens a sheet first: the warning, then **Text and apps** (sharp text, up to 1080p at 15 fps) or **Games and video** (smooth motion, 720p at 30 fps), and **Include app sound**. The last choice is the default next time. Then Android's own consent (Android 14+: one app or the entire screen) | `ui/screens/calls/ShareSheet.kt`; `SettingsStore.shareMode`, `shareAppSound` |
| Game mode | 1280×720 at most, 30 fps, up to 3.5 Mbit/s, and under congestion it keeps the frame rate and lowers sharpness. Text mode is unchanged: 1920×1080, 15 fps, 2.5 Mbit/s, keeps sharpness. Rotation following uses the chosen mode's size | `ShareMode` in `CallMediaPolicy.kt`; `CallManager.applyShareMode`; `ScreenShareRotation` |
| App sound (Android 10+) | Sound from the shared apps goes with your microphone, so muting or deafening yourself silences it too; apps that forbid capture stay silent. The banner reads "Sharing · games and video · with sound" | `CallManager.startAppSound` / `stopAppSound` (LiveKit `ScreenAudioCapturer`) |
| Share ended | When the other person stops sharing, you're told "<name> stopped sharing their screen" | `CallManager` (`TrackUnpublished`) |
| Clean stop | Stopping the share, ending the call, a reconnect or Android ending the capture also stop the app sound and release it | `CallManager` |

**Not built, with the reason:**
- **H.264:** phones' hardware H.264 encoders differ, and decoding plus frame encryption need checking on real
  devices first. VP8 stays.
- **60 fps:** not without measurements on phones.
- **A viewer quality picker:** the receiver's adaptive stream already picks what it can take.
- **Game sound as a separate track:** it's mixed into the microphone, so muting yourself silences it too.

**Server:** no change (API 0.23.0).

### How it was checked

- **Android:** `ShareModeTest` (4 tests: 720p cap and shape in both orientations, frame rate against sharpness, saved
  choice fallback, Android 10 for sound) and `CallMediaPolicyTest`. Full suite: see the APK section. Lint 0 errors (it
  caught two Android 10 calls without an explicit version check: fixed).
- **Emulator** (Ada shares to Erin in a voice call, local LiveKit):
  - **Games and video with sound:**
    - diagnostics showed "screen share: game 1280x720 30 fps 3500 kbps, app sound on" and "capture resized to
      576×1280 for a 1080×2400 display";
    - Erin's app received 576×1280 at 15–18 fps, end-to-end encrypted (the emulator's encoder and decoder limit the
      rate);
    - the banner read "Sharing · games and video · with sound".
  - **Text and apps:** "text 1920x1080 15 fps 2500 kbps".
  - **Stopping:** **Stop** ended the share and Android's capture session was gone (`dumpsys media_projection`
    empty). Ending the call while sharing did the same, with no crash. A new call started with no screen share (no
    old track republished). When Ada stopped, Erin's app showed the "stopped sharing" message (seen in the system
    log).
  - **Android 16** offered "Share one app" or "Share entire screen", as the sheet says.
- **Found and fixed during the check:** the app-sound explanation was cut off after one line; it now has its own
  lines under the switch.
- **Not checked on the emulator:**
  - whether app sound is heard on the other phone (the emulator plays no game sound);
  - 30 fps on real hardware;
  - a landscape game;
  - a single-app share when that app goes to the background.

### B11 APK

- **File:** `/Volumes/code_files/meldiq_android/android/app/build/outputs/apk/debug/meldiq-test-b11-debug.apk`
  (149 MB, git-ignored). **SHA-256:** `995c241a7b5c9e0de7e7a69b5d56a3b72357dbf283ff8f084531de9835153f1d`.
- **Variant:** debug, side by side, server `http://localhost:8080` (API 0.23.0). It contains everything since B4: **the
  one APK to install for testing B4 to B11 together.**
- **Checks:** 636 Android unit tests, 0 failures; lint 0 errors.

### Owner's phone checklist (B11): two phones in a call

1. **Text mode:** **Share** › **Text and apps** › Continue › Android's dialog › share the entire screen. Open a long
   chat. **Expected:** the other phone reads the text clearly. Tap **Stop**: sharing ends, and the other phone says you
   stopped sharing.
2. **Game mode with sound:** **Share** › **Games and video**, **Include app sound** on › Continue › entire screen.
   Play a game or video in landscape. **Expected:** the other phone sees smooth, landscape motion and hears the game.
   The banner reads "Sharing · games and video · with sound". Mute yourself: the game sound stops too.
3. **One app (Android 14+):** choose **Share one app** in Android's dialog and pick a game. **Expected:** only that
   app is seen. Note what the other phone sees when you leave the app.
4. **Ending:** end the call while sharing. **Expected:** the status-bar capture icon disappears. Call again: nothing
   is shared until you choose to.
5. **Measurements (optional):** `adb logcat -s CallDiag` on both phones; send the "stats send … screen=" and
   "recv … screen=" lines (frame size, fps, kbps).
6. **Report back:** pass or fail per step, with screenshots.

## 6l. Batch B12: remaining gaps (built 2026-10-22, not phone-tested)

B12 took the gaps the matrix still listed for Android after B11 that fit one bounded batch, plus defects found on the
way.

### What's in it

| Item | What the person sees | Code |
|---|---|---|
| Dates and times in the app's language | "Today", "Yesterday", "just now", "5 min ago", greetings, and day headers in English, Chinese or Bengali. Times follow the phone's 12/24-hour setting (it was always AM/PM) | `TimeFmt` in `ui/Util.kt`, set up by `MainActivity` |
| "1 people" | "1 person" on roles, role holders and events, and "1 person lost access" after a permission change | `peopleCountLabel` in `CommunityPanel.kt` |
| Community emoji as reactions (EXP-04) | Add a reaction › **Community** row › tap. The chip shows the image. Only inside that community's channels | `ExpressionRules.reactionAllowed`, `MessageRepository.react` and receive, `LocalCommunityEmoji`, `MessageBubble` chips |
| Pronouns (NAV-07) | Edit profile › **Pronouns (optional)**; shown on the profile after the username; hidden by the bio's privacy setting | Server `PATCH /v1/users/me` `pronouns`, `profileView`; Android `EditProfileScreen`, `ProfileScreens` |
| Roles listed separately (COM-09) | A role's **Show members separately** switch; the member list groups those people under the role's name, in role order, then everyone else | Server `org_roles.hoist`, roles and members routes; Android `MemberGroups`, `MembersScreen`, `RoleEditorSheet` |
| **Fix:** community emoji and photos after Android clears the cache | When storage runs low, Android may delete the app's cache folders. After that, community emoji (and profile photos not yet cached) never loaded again until the app restarted; they now download again | `ExpressionRepository.image`, `ProfileRepository.cachedPhoto` |
| **Fix:** server shutdown error | Sockets closing after the database during shutdown threw an uncaught error (from B6b's presence check) | `ws.ts` `recordsLastSeen` |

**Left for the owner, with the reason:**
- **Two-step sign-in (AUTH-05, P2):** it changes how every account signs in, and adds recovery and moderator rules.
  It needs your decision on the design: authenticator app only, or email too; required for moderators?
- **Web parity:** pronouns, role groups, custom reactions and the B10/B11 call controls aren't in the web app (W1,
  part of the web community rebuild).
- **Not planned in this batch:** AutoMod and explicit-media filtering (P3; the server can't read encrypted content),
  stage channels, @role mentions, thread archiving, a profile banner.

**Server and API:** API **0.24.0**, features `pronouns` and `role-hoist`, **migration 044** (`users.pronouns`,
`org_roles.hoist`; additive). `users` is in the identity domain, copied to MongoDB by column, so no new mapping.

### How it was checked

- **Server:** full suite **709 passed**, 10 skipped, exit 0 (the shutdown error above was found because the suite
  exited 1 with 19 "database connection is not open" errors); `pronouns_role_hoist.test.ts` (2 tests). TypeScript
  check clean.
- **Android:**
  - new tests: `TimeFmtTest`, `CustomReactionRulesTest`, `MemberGroupsTest`;
  - full suite **641 tests, 0 failures**; lint 0 errors.
- **Emulator** (Ada, server 0.24.0):
  - **Pronouns:** Edit profile › Pronouns › "she/her" › Save. Another account's API read returns "she/her".
  - **Roles:** Apply Lab › Settings › Roles & permissions › Painter › **Show members separately** › Save role. The
    member list shows "Painter — 1" (Ada), then "Members — 12".
  - **Community emoji reaction:** #general › long-press "Hello" › More reactions › Community › the emoji. The chip
    shows the image, count 1.
  - **Cache fix:** with the folder deleted while the app ran, opening the picker downloaded the image again.
- **Not checked on the emulator:**
  - the Chinese and Bengali dates on screen (unit-tested words; the app language wasn't switched);
  - a reaction seen from a second phone;
  - pronouns on another person's profile screen (only through the API).

### B12 APK

- **File:** `/Volumes/code_files/meldiq_android/android/app/build/outputs/apk/debug/meldiq-test-b12-debug.apk`
  (148 MB, git-ignored). **SHA-256:** `047d31db65b6418c7027e089ee3744bd9729981a1f2e8748c0158f0c16ff4854`.
- **Variant:** debug, side by side, server `http://localhost:8080`, **API 0.24.0 needed for pronouns and role groups**.
  It contains everything since B4: **the one APK to install for testing B4 to B12 together.**

### Owner's phone checklist (B12)

1. **Language:** You › Settings › Language › Bengali (or Chinese). Open a chat. **Expected:** "Today" and "Yesterday"
   headers and list times in that language. Set the phone to 24-hour time: times follow it.
2. **Plurals:** a role with one person reads "1 person".
3. **Custom reaction:** in a community with its own emoji, long-press a message › More reactions › Community › tap
   one. **Expected:** the picture appears as a reaction on both phones.
4. **Pronouns:** Edit profile › Pronouns › Save. **Expected:** the other phone's view of your profile shows "@you ·
   pronouns". With Privacy › Bio set to Nobody, they disappear for others.
5. **Role groups:** Community settings › Roles & permissions › a role › **Show members separately** › Save role.
   **Expected:** Members lists that role's people under its name first.
6. **Report back:** pass or fail per step, with screenshots.

## 7. Things the owner should know (carried over)

1. **A test sign-up reached production on 2026-10-21:** `ada@meldiq.test`, unverified, no keys. Not deleted (owner's
   call).
2. **The AWS tools use the root user's access key:** move them off it (`MELDIQ_AWS_ARCHITECTURE.md` §5).
3. **Server 0.24.0 isn't deployed** (production runs 0.13.0): 0.24.0 adds migration 044 (pronouns, role groups); 0.23.0 adds migration 043 (community emoji);
   0.22.0 adds migrations 041 and 042 (event repeats, onboarding, the guide); 0.21.0 adds migration 040 (forum guidelines); 0.20.0
   adds migration 039 (group photo, presence);
   0.19.0 adds migration 038 (connections, requests) and `DEFAULT_DM_POLICY`; 0.18.0 adds migration 037 (applications, alerts); 0.14.0 migration 034 (timeouts); 0.15.0 B2's preference fields and templates; 0.16.0 migration 035 (slowmode) and B3's fields; 0.17.0 migration 036 (reports, pauses). For deployment, set `REPORTS_KEY` (or back up `reports.key` with the database).
4. **Android database version 13:** forward migrations (v12 in B6, v13 in B6b).
5. **For https invite links (B4b):** serve `https://meldiq.app/.well-known/assetlinks.json` from
   `orbit_web/deploy/app-links/assetlinks.template.json` with the release signing-certificate SHA-256 (from your signing
   key; Claude never reads it), and route `meldiq.app/invite/*` to the web server's invite page (or serve the web app
   on meldiq.app). Until then, links open the web page instead of the app outside Meldiq.

## 8. Next, exactly

1. **Engineering (in progress overnight):** B13 (integration and stability), the last overnight batch, each committed with
   its own APK and checklist in this file.
2. **Backlog added by the owner 2026-10-22** (`MELDIQ_BACKLOG_B14_B16_B15.md`): **B14** minimal DM screen and
   conversation details, then **B16** transitions and interaction polish, then **B15** the final quality gate (always
   last). The overnight rule ends at B13: B14 starts only after the owner approves B13, and each of the three stops
   for the owner's phone test. The owner's reference screenshots show private chats, so they're described in that
   file, not stored in the repository.
3. **Owner, when back:** phone-test the batches together using the per-batch checklists (B4, B4b, then later ones),
   and report pass or fail per batch.
4. **Owner decisions:** `MELDIQ_IMPLEMENTATION_PLAN.md` "Owner actions" (fingerprint and meldiq.app hosting for invite
   links, test accounts and a second device for calls, deploy).
5. Reported defects are fixed in the batch they belong to.
