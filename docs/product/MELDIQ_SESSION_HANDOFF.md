# Session handoff (2026-10-21, gated-batch round)

For the next session: where everything is, what was done and verified, what isn't, and exactly what comes next. The
batch plan and its approval gate are in `MELDIQ_IMPLEMENTATION_PLAN.md`; the status of every feature is in
`MELDIQ_FEATURE_GAP_MATRIX.md`.

> **Gate in force:** batch B1 is built and its APK is waiting for the owner's phone test. **Don't start B2, or make
> other UI or backend changes, until the owner approves B1 or reports defects in it.** Answer questions and fix
> reported B1 defects only.

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

## 5. Batch B1: what it contains and how it was checked

**Scope:** sign-in shows only email, password and Sign in, plus one "Having trouble signing in?" link. That opens a
sheet with forgot password, recovery code and moving an old account. The unlock screen shows "Get a code by email"
first, with the other ways behind "Can't access your email?". Built on 2026-10-21 (commit `377d83c`, Android; web
`0f462ed`). See `MELDIQ_AUTH_FLOW_AUDIT.md`.

**Automated:**
- **Android:** 572 unit tests passed this round (including 61 account tests). Lint was clean at commit `377d83c`.
- **Web:** type check, lint, Vitest, Playwright `accounts` and `cross-device`, on 2026-10-21.

**Emulator:** the walk-through on 2026-10-21 covered:
- sign up → verify → new phone → sign in;
- help sheet → recovery keeps the email;
- unlock screen → other ways → emailed code unlock.

Screenshots `docs/product/screens-2026-10-21/auth-1..4`. Session restore after an app restart was **not re-checked in
this round**: it's on the phone checklist.

**Physical phone:** **not yet tested.**

## 6. B1 APK and the owner's phone checklist

Built on 2026-10-21 at 18:41 with:

```bash
./gradlew assembleDebug -Pmeldiq.sideBySide=true -Pmeldiq.environment=production
```

The build succeeded. `testDebugUnitTest` before it: **572 tests, 0 failures**.

- **APK:** `/Volumes/code_files/meldiq_android/android/app/build/outputs/apk/debug/meldiq-test-b1-debug.apk`
  (147 MB, git-ignored).
  - SHA-256: `f90d185460c66897c1c5060cd269df12c459cd81cc3bb5cf44e5d89553ca993c`.
  - Built from commit `46e5873` plus this round's docs; no app code changed since.
- **Variant:** debug, side by side: package `com.meldiq.android.test`, label "Meldiq Test". It installs beside the
  owner's Meldiq and never replaces it.
- **Server:** production relay `https://msg.griipbuddy.cloud` (the environment flag `meldiq.environment=production`),
  because sign-in and unlock need real emails.
- **Version:** versionCode 19, `0.5.0-beta` (committed values; the owner's uncommitted 20 / `0.4.0-beta` isn't in
  this build).

**Phone checklist (B1):**
1. Install: `adb install -r meldiq-test-b1-debug.apk`, or open the file on the phone. It appears as "Meldiq Test".
2. Sign in with your account.
   - **Expected:** only email, password, Sign in, and the link "Having trouble signing in?". No recovery options are
     listed on the form.
3. Tap "Having trouble signing in?".
   - **Expected:** a sheet with three choices: "I forgot my password", "I can't get into my email", "Move an existing
     account".
   - Close it with "Back to sign in" and with the back gesture: the form keeps what you typed.
4. Sign in.
   - **Expected:** because this is a new device, the unlock screen shows one main button "Get a code by email" and the
     link "Can't access your email?".
   - If your account has no key backup yet (for example after a password reset), the screen says so and lists the
     other ways directly. That's correct too: note which one you saw.
5. Tap "Can't access your email?".
   - **Expected:** "Other ways to unlock" lists another phone, recovery key and start fresh, with "Back to the email
     code".
   - **Don't choose "Start fresh" with your real account:** it replaces your encryption keys.
   - Go back.
6. Tap "Get a code by email", enter the emailed code.
   - **Expected:** your messages unlock and history appears.
7. Swipe the app away and reopen it.
   - **Expected:** you're still signed in and unlocked, with no sign-in screen.
8. Turn on airplane mode and open the app.
   - **Expected:** it opens to your chats with an offline banner, no crash.
   - Turn airplane mode off: it reconnects.
9. Optional: in Settings › Language, switch to 中文 and বাংলা and repeat step 3. The help sheet is translated.
10. Report back: pass/fail per step, a screenshot of anything wrong, and the phone model.

Afterwards you can remove "Meldiq Test" from the phone, and sign out of it first so the server forgets that device.

## 7. Things the owner should know (carried over)

1. **A test sign-up reached production on 2026-10-21:** `ada@meldiq.test`, unverified, no keys. Not deleted (owner's
   call).
2. **The AWS tools use the root user's access key:** move them off it (`MELDIQ_AWS_ARCHITECTURE.md` §5).
3. **Server 0.14.0 isn't deployed:** migration 034 (timeouts).
4. **Android database version 11:** a forward migration.

## 8. Next, exactly

1. **Owner:** the B1 phone test (§6), then approve or report.
2. **Owner:** decisions listed in `MELDIQ_IMPLEMENTATION_PLAN.md` "Owner actions" (connections model, fingerprints,
   test accounts).
3. **Engineering, only after B1 approval:** B2 (community navigation and home) as scoped in the plan. Its first step
   is a short Discord re-check of the notification dialog and rail order, then Meldiq's `CommunityMenus.kt`,
   `CommunitySettingsScreen.kt` and the server's preferences domain.
4. **Don't change before B1 feedback:** the sign-in and unlock screens (`AccountScreens.kt`,
   `DeviceSetupScreens.kt`, `AccountHelpSheets.kt`, `AccountFlowViewModel.kt`) except to fix reported defects.
