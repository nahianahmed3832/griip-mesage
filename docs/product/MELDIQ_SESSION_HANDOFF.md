# Session handoff (2026-10-21, gated-batch round)

For the next session: where everything is, what was done and verified, what isn't, and exactly what comes next. The
batch plan and its approval gate are in `MELDIQ_IMPLEMENTATION_PLAN.md`; the status of every feature is in
`MELDIQ_FEATURE_GAP_MATRIX.md`.

> **Gate in force:** B1 was phone-tested and approved by the owner (every checklist step passed, 2026-10-21). B2 is
> built and its APK is waiting for the owner's phone test. **Don't start B3, or make other UI or backend changes,
> until the owner approves B2 or reports defects in it.** Answer questions and fix reported B2 defects only.

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

## 5. Batch B1: approved

Sign-in and unlock polish (`MELDIQ_AUTH_FLOW_AUDIT.md`). The owner tested `meldiq-test-b1-debug.apk` on their phone
against production and reported that every checklist step passed (2026-10-21). Status: **physically verified**.

## 6. Batch B2: community navigation and home (built 2026-10-21, waiting for the phone test)

### What's in it

| Feature | What the person sees | Code |
|---|---|---|
| Community notification settings (COM-13, Discord COMM-CTRL-002) | Community sheet › **Notification settings**: <ul><li>Mute the community;</li><li>"Channels notify me about" All new messages / Mentions only / Off;</li><li>**Ignore @everyone**;</li><li>"Channels with their own setting" list with "Add a channel"; each channel can follow the community again</li></ul> | Android `CommunityNotificationsSheet.kt`, `NavPrefsRules.kt` (community level, overrides, `countsEveryone`), `NavPreferences.kt`, `MessageRepository.kt` + `Mentions.kt` (`MentionNotify.isMention`). Server `preferences/schema.ts`, `preferences/routes.ts` (`community` in community documents) |
| Rail order (NAV-11, Discord NAV-CTRL-002) | Long-press any community in the rail, or community sheet › **Arrange communities**: Move to the top, Move up, Move down; kept on every device | Android `CommunityRail.kt` (`ArrangeCommunitiesSheet`), `CommunityHomeScreen.kt`; server `railOrder` in the personal document |
| Starter templates (COM-01, Discord CREATE-CTRL-001) | Create a community › **Start with** Starter / Friends / Gaming / Study group / Club, with the channel list of the chosen one; Gaming, Study group and Club add a forum, all add voice channels | Server `workspace/communityStart.ts` (`COMMUNITY_TEMPLATES`), `GET /v1/community-templates`, `POST /v1/orgs {template}`; Android `JoinWorkspace.kt` (`CommunityTemplates`) |
| Unread and mention signals (NAV-05) | Rechecked: the rail bar and mention count, Home and Activity badges | No change needed |

**Server and API:**
- API **0.15.0**, with new features `community-preferences` and `community-templates`.
- No database migration (the new fields live in the preferences documents, SQLite or MongoDB).
- Older apps and the web keep working: they leave the new fields out and the server keeps the stored values.

**New Android strings** in English, Chinese and Bengali: `cn_*`, `rail_*`, `ctpl_*`.

**New script:** `server/scripts/phone-test-server.sh` runs a local test server for a phone over USB and prints the
newest emailed code.

### How it was checked

- **Server:**
  - `community_navigation.test.ts` (11): levels, overrides, older-client writes, refusals, privacy, rail order, the
    template list and creation;
  - full suite **638 passed**, 10 skipped (SQLite);
  - the B2 and preferences tests also pass with preferences in MongoDB.
- **Android:** `CommunityNavigationRulesTest` (8), full unit suite **580 passed**, lint clean.
- **Emulator** (local server 0.15.0, two real Meldiq clients: the main app and "Meldiq Test" as a second account), each
  confirmed with `dumpsys notification` and screenshots `docs/product/screens-2026-10-21/b2-*.png`:
  - Arrange moved Mod Lab to the top; the order was saved on the server and held after reopening.
  - Mentions only + Ignore @everyone + "introductions: All new messages" were saved to the community document.
  - A real @everyone with Ignore on: delivered and decrypted, **no notification**, no mention badge.
  - With Ignore off: **notified** ("#general: …").
  - A plain message in a "Mentions only" channel: no notification. A plain message in the channel set to "All new
    messages": notified.
  - Created "Squad Night" from Gaming: Start here, Play (general, looking-for-group, clips forum), Voice (lobby,
    squad).
  - Found and fixed during the walk: the old "It starts with #welcome…" card contradicted the chosen template. It now
    shows only when the server has no templates.
- **Physical phone:** **not yet tested.**

### B2 APK

- **File:** `/Volumes/code_files/meldiq_android/android/app/build/outputs/apk/debug/meldiq-test-b2-debug.apk`
  (148 MB, git-ignored).
- **SHA-256:** `e32183b554aaf75ae888286c2fe627e1b9d3d012c2329e2ed153daef2f301de9`.
- **Variant:** debug, side by side: `com.meldiq.android.test`, "Meldiq Test", versionCode 19 / `0.5.0-beta`.
- **Server:** `http://localhost:8080`, the local test server on the Mac, reached over USB. The live server runs API
  0.13.0 without B2's server parts, and the brief says phone tests use a safe test server, not production data.

### Owner's phone checklist (B2)

**Before you start:**
1. **Remove the B1 build:** in "Meldiq Test", sign out (You › Settings › "Sign out of this account"), then uninstall it. Otherwise it keeps pointing
   at production.
2. **Start the test server:** on the Mac, from `/Volumes/code_files/meldiq_android`, run
   `server/scripts/phone-test-server.sh` and leave it running. Plug the phone in by USB with USB debugging on. The
   script runs `adb reverse tcp:8080 tcp:8080`; run that again if you plug in later.
3. **Install:** `adb install -r android/app/build/outputs/apk/debug/meldiq-test-b2-debug.apk`.
4. **Create an account:** open "Meldiq Test" and sign up with any address. Get the code with
   `server/scripts/phone-test-server.sh code`.

**Checks:**
1. **Templates:**
   - After sign-up, at "How do you want to start?" choose **Just me**. Then tap **+** in the left rail › **Create
     new**. (The first-run "Start a community" screen still creates the starter set; templates are on this screen.)
   - Under "Start with", tap each template.
   - **Expected:** the "Channels:" line changes for each.
   - Pick **Gaming**, name it, create.
   - **Expected:** categories Start here, Play (general, looking-for-group, a **clips** forum), Voice (**lobby**,
     **squad**).
2. **Second community:** create another, e.g. **Study group**.
   - **Expected:** a resources forum and a study-room voice channel.
3. **Rail order:**
   - Long-press a community in the left rail.
   - **Expected:** "Arrange communities" with Move to the top, up and down. Arrows are dimmed at the ends.
   - Move the second community to the top and close.
   - **Expected:** the rail shows the new order.
   - Swipe the app away and reopen: the order is kept.
4. **Notification settings:**
   - Tap the community name at the top, then **Notification settings**.
   - Choose "Mentions only", turn on **Ignore @everyone**, then "Add a channel" › #general › "All new messages".
   - **Expected:** #general is listed under "Channels with their own setting" with "All new messages".
   - Close and reopen the sheet: everything is as you left it.
5. **Follow the community again:**
   - Tap #general in that list › "Follow the community (Mentions only)".
   - **Expected:** it leaves the list.
6. **Mute:**
   - Turn on "Mute \<community\>".
   - **Expected:** the channels show as muted in the panel.
   - Turn it off again.
7. **Optional, needs a second account on another phone or the emulator:**
   - Post @everyone with Ignore on: no notification.
   - With it off: a notification.
   - (Already verified on the emulator.)
8. **Languages:** in Settings › Language, switch to 中文 or বাংলা and open Notification settings and Arrange: the text
   is translated.
9. **Report back:** pass or fail per step, a screenshot of anything wrong, and the phone model.

## 7. Things the owner should know (carried over)

1. **A test sign-up reached production on 2026-10-21:** `ada@meldiq.test`, unverified, no keys. Not deleted (owner's
   call).
2. **The AWS tools use the root user's access key:** move them off it (`MELDIQ_AWS_ARCHITECTURE.md` §5).
3. **Server 0.15.0 isn't deployed** (production runs 0.13.0): 0.14.0 added migration 034 (timeouts); 0.15.0 adds B2's preferences fields and templates (no migration).
4. **Android database version 11:** a forward migration.

## 8. Next, exactly

1. **Owner:** the B2 phone test (§6), then approve or report.
2. **Owner:** decisions listed in `MELDIQ_IMPLEMENTATION_PLAN.md` "Owner actions" (connections model, fingerprints,
   test accounts).
3. **Engineering, only after B2 approval:** B3 (channels and categories: timed mute, category default level, hide
   channels and channel browser, slowmode), as scoped in the plan.
4. **Don't change before B2 feedback:**
   - `CommunityNotificationsSheet.kt`, `CommunityRail.kt`, `CommunityMenus.kt`, `CommunityHomeScreen.kt`;
   - `JoinWorkspace.kt`'s create tab;
   - `NavPrefsRules.kt`, `NavPreferences.kt`;
   - the server's `preferences/` and `communityStart.ts`.

   Only reported defects get fixed there.
