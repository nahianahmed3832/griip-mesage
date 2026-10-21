# Session handoff (2026-10-21, gated-batch round)

For the next session: where everything is, what was done and verified, what isn't, and exactly what comes next. The
batch plan and its approval gate are in `MELDIQ_IMPLEMENTATION_PLAN.md`; the status of every feature is in
`MELDIQ_FEATURE_GAP_MATRIX.md`.

> **Gate in force:** B1 and B2 were phone-tested and approved by the owner (every checklist step passed, 2026-10-21).
> B3 is built and its APK is waiting for the owner's phone test. **Don't start B4, or make other UI or backend changes,
> until the owner approves B3 or reports defects in it.** Answer questions and fix reported B3 defects only.

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
## 5. Batches B1 and B2: approved

| Batch | What | Owner's phone test |
|---|---|---|
| B1 | Sign-in and unlock: recovery choices behind help links (`MELDIQ_AUTH_FLOW_AUDIT.md`) | Every step passed (production server) |
| B2 | Community notification settings with Ignore @everyone, rail order, starter templates (API 0.15.0) | Every step passed (local test server) |

Both are **physically verified**. Details of what they contain are in git history (`377d83c`, `edfb459`) and the
matrix rows NAV-11, COM-01 and COM-13.

## 6. Batch B3: channels and categories (built 2026-10-21, waiting for the phone test)

### What's in it

| Feature | What the person sees | Code |
|---|---|---|
| Slowmode (CH-14, Discord O1 channel settings, CHAN-CTRL-013) | <ul><li>Edit channel › **Slowmode**: Off, 5 s, 10 s, 30 s, 1 min, 5 min, 15 min, 1 h, 6 h;</li><li>members see "Slowmode is on: one message every 30 s" above the message box;</li><li>after posting, "you can post again in N s";</li><li>a message sent meanwhile waits (clock icon) and goes out when allowed</li></ul> | Server: migration `035_slowmode.sql`, `authz.ts` (`slowmodeExempt`, `nextPostAt`), `routes/messages.ts` (429 `slowmode`), `routes/conversations.ts` (PATCH `slowmodeSec`, view fields), permission `messages.bypass_slowmode` (Moderator template). Android: `ChannelEditSheets.kt`, `SlowmodeLine` in `ChannelControlSheets.kt`, `MessageRepository.kt` (waits instead of failing), `ConversationRepository.kt` (`slowmode` in memory) |
| Timed mutes (CH-17, CHAN-CTRL-002) | Muting a channel, a category, the whole community, or a DM (long-press › **Mute for a while**) asks how long: 15 minutes, 1 hour, 3 hours, 8 hours, 24 hours, or until turned back on. A DM shows "Muted until …". Mutes end by themselves | `mutedUntil` in the preferences; `NavPrefsRules.level`, `NavPreferences.muteFor` (+ expiry timer), `MuteForSheet` |
| Category level (CHAN-CTRL-003) | Long-press a category › **Notification settings**: follow the community, All, Mentions only, Off. Its channels follow it unless they have their own | `categoryNotify`; channel → category → community; `CategoryLevelSheet` |
| Hide channels and Browse channels (COM-16, COMM-CTRL-007, CHAN-CTRL-005) | <ul><li>Channel menu › **Hide from channel list** or **Show in channel list**;</li><li>a "N hidden · Browse channels" row at the bottom;</li><li>community sheet › **Browse channels**: a switch per channel, and Show all;</li><li>hidden channels still appear while they mention you or are open</li></ul> | `hidden`; `CommunityPanelRules.sections(hidden)`, `BrowseChannelsSheet` |

**Server and API:**
- API **0.16.0**, with new features `slowmode` and `channel-preferences`.
- **Migration 035** adds two columns (additive). It was applied to the existing local test database without problems.
- Older apps and the web keep working: they leave the new preference fields out and the stored values are kept.

**New Android strings** in three languages: `mf_*`, `cl_*`, `hc_*`, `sm_*`.

### How it was checked

- **Server:**
  - `channel_controls.test.ts` (10):
    - who may set slowmode, and only the offered intervals;
    - a member's second message is refused with `retryAfter`;
    - edits and reactions are free;
    - a retry of an accepted message is a duplicate, not a refusal;
    - managers and `messages.bypass_slowmode` are exempt;
    - turning it off;
    - timed mutes (ended ones and other people's dropped);
    - category levels (other communities' dropped, "all" kept under a quieter category);
    - hidden channels (only this community's visible channels);
    - older-client writes;
    - the personal document.
  - Full suite **648 passed**, 10 skipped. The preference tests also pass with MongoDB.
- **Android:** `ChannelControlsRulesTest` (7), full unit suite **587 passed**, lint clean.
- **Emulator** (local server 0.16.0, Ada in the main app as an admin, Erin in "Meldiq Test" as an ordinary member):
  - Ada set 30 s slowmode on #general (stored on the server).
  - Erin saw the slowmode line. Her second message waited with a countdown and reached the server **30.3 s** after the
    first. Ada (admin) saw no slowmode line.
  - Ada muted #introductions for 15 minutes: stored on the server with 15.0 minutes left, and the channel menu said
    Unmute.
  - The Talk category was set to All: #general in Talk became unmuted, while #introductions stayed muted during its
    timed mute. The server dropped the now-redundant "All" override.
  - #lounge was hidden: it left the panel and the "1 hidden · Browse channels" row appeared. Browse channels listed
    it switched off; switching it on brought it back.
  - A DM with Carol muted for 1 hour: the sheet said "Muted until 10:29 PM".
  - **Defect found and fixed:** at the end of the 15 minutes, #introductions stayed muted. The end-of-mute timer
    reapplied the mutes, and the rescheduling inside that cancelled the timer's own job, so later communities were
    never reapplied. The job now lets go of itself first (`NavPreferences.scheduleMuteExpiry`). After the fix, an
    ended mute is ignored at start-up (#introductions unmuted).
    Then #lounge was muted for 15 minutes at 21:46 and, at 22:02 without restarting the app, its menu said **Mute channel** again: the timer works.
  - Screenshots: `docs/product/screens-2026-10-21/b3-*.png`.
- **Physical phone:** **not yet tested.**

### B3 APK

- **File:** `/Volumes/code_files/meldiq_android/android/app/build/outputs/apk/debug/meldiq-test-b3-debug.apk`
  (147 MB, git-ignored).
- **SHA-256:** `3418be283e117d1c629657cbf536d44a9e91e1d3a48053628b0083f1e11e4cc5` (rebuilt after the timer fix below).
- **Variant:** debug, side by side: `com.meldiq.android.test`, "Meldiq Test", versionCode 19 / `0.5.0-beta`.
- **Server:** `http://localhost:8080`, the local test server over USB, as for B2.

### Owner's phone checklist (B3)

**Before you start:**
1. **Server:** from `/Volumes/code_files/meldiq_android`, start the test server again with
   `server/scripts/phone-test-server.sh`. It's now API 0.16.0 and updates the test data from B2 by itself.
2. **Install over B2:** `adb install -r android/app/build/outputs/apk/debug/meldiq-test-b3-debug.apk`. You stay signed
   in.
3. **Second account, for slowmode only:**
   - As the community's owner you're never slowed down, so add a second account: You › Settings › **Add account**.
     Get its code with `server/scripts/phone-test-server.sh code`.
   - Join your test community with an invite as a member.

**Checks:**
1. **Slowmode:**
   - As the owner, long-press a text channel › **Edit channel** › Slowmode **30 s** › Save.
   - Switch to the second account and open that channel.
   - **Expected:** "Slowmode is on: one message every 30 s".
   - Send two messages quickly.
   - **Expected:** the second shows a clock and "you can post again in N s", then goes out after about 30 s.
   - Switch back to the owner. **Expected:** no slowmode line; you can post freely.
2. **Mute for a while:**
   - Long-press a channel › **Mute channel**.
   - **Expected:** the choices 15 minutes, 1 hour, 3 hours, 8 hours, 24 hours, Until I turn it back on.
   - Pick **15 minutes**. **Expected:** its menu now says **Unmute channel**.
   - **After 15 minutes:** it says **Mute channel** again, without you doing anything.
3. **Whole community and category:**
   - Community name › **Mute** asks how long too. Pick "Until I turn it back on", then Unmute.
   - Long-press a category › **Mute category** asks the same.
4. **Category level:**
   - Long-press a category › **Notification settings** › **All new messages**.
   - **Expected:** the sheet shows your choice when reopened.
5. **Hide and browse:**
   - Long-press a channel › **Hide from channel list**.
   - **Expected:** it disappears and "1 hidden · Browse channels" appears at the bottom.
   - Tap that row. **Expected:** the channel is listed with its switch off.
   - Switch it on. **Expected:** it's back in the list.
   - Also open Browse channels from the community menu.
6. **DM:**
   - In DMs, long-press a conversation › **Mute for a while** › 1 hour.
   - Long-press again. **Expected:** "Muted until …".
7. **Languages:** switch to 中文 or বাংলা and open the Mute for and Browse channels sheets: the text is translated.
8. **Report back:** pass or fail per step, a screenshot of anything wrong, and the phone model.

## 7. Things the owner should know (carried over)

1. **A test sign-up reached production on 2026-10-21:** `ada@meldiq.test`, unverified, no keys. Not deleted (owner's
   call).
2. **The AWS tools use the root user's access key:** move them off it (`MELDIQ_AWS_ARCHITECTURE.md` §5).
3. **Server 0.16.0 isn't deployed** (production runs 0.13.0): 0.14.0 added migration 034 (timeouts); 0.15.0 B2's preference fields and templates; 0.16.0 migration 035 (slowmode) and B3's preference fields.
4. **Android database version 11:** a forward migration.

## 8. Next, exactly

1. **Owner:** the B3 phone test (§6), then approve or report.
2. **Owner:** decisions listed in `MELDIQ_IMPLEMENTATION_PLAN.md` "Owner actions" (connections model, fingerprints,
   test accounts).
3. **Engineering, only after B3 approval:** B4 (administration and invitations): reports, pause invites and member
   DMs, join applications, raid alerts, audit log filters, https invite links (`MELDIQ_COMMUNITY_INVITATION_SPEC.md`).
4. **Don't change before B3 feedback:** `ChannelControlSheets.kt`, `ChannelEditSheets.kt`, `CommunityMenuRules.kt`,
   `CommunityMenus.kt`, `CommunityHomeScreen.kt`, `CommunityPanelRules.kt`, `ChatActionsSheet.kt`, `NavPrefsRules.kt`,
   `NavPreferences.kt`, `MessageRepository.kt`'s outbox; the server's `preferences/`, `routes/messages.ts` slowmode,
   `authz.ts` slowmode. Only reported defects get fixed there.
