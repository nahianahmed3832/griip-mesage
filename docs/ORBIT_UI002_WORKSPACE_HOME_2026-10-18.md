# Orbit UI-002 (revised): workspace home, Home / DMs / Activity navigation (2026-10-18)

**Status: implemented and verified on the owner's physical phone (Phase C, section 8).**
- Phase A (implementation and code-level checks) is in section 6.
- Phase C (device tests, the defects they found, and the owner's request during them to bring every remaining old-design screen into the new design) is in section 8.
- No emulator was used at any point.
- Nothing is committed or pushed. These changes sit uncommitted on `internal-beta`, on top of `6f355a1`.
- The uncommitted version bump in `android/app/build.gradle.kts` (0.1.5-beta, code 6) isn't part of this work and was left as it is.

This replaces the first UI-002 design in `6f355a1`: a Cocoa chat list, four tabs (Chats, Calls, Contacts, Settings) and a floating "+". Its report, `docs/ORBIT_UI002_HOME_CHAT_LIST_2026-10-18.md`, is kept as history.

**Reference.** The owner's image shows three phones in purple, mustard and green. It was used for the layout only:
- header: workspace with logo and name, avatar, search and filter;
- three quick cards;
- Unread DMs with "See all";
- Mentions;
- channel groups;
- a compose button;
- Home / DMs / Activity tabs.

Its colours, logo and names weren't used. Everything is in Orbit's Cocoa palette: neutral raised surfaces, warm text, and the peach → pink accent only on selected states, the New button and counts that need you.

## 1. What existed, and what changed

| Area | In `6f355a1` | Revised UI-002 |
|---|---|---|
| Palette, tokens, `SystemBarRequests`, `AccentChip`, `ConversationRow` | Cocoa on the chat list | Kept. Since Phase C (owner's request) Cocoa is the palette of **every signed-in screen**, set once at the root. |
| Bottom bar (`FloatingNavBar`) | Chats, Calls, Contacts, Settings and a round "+" | **Home, DMs, Activity** only; the "+" is gone. Each tab now has its own click action for TalkBack (it was missing before). |
| Chat list (`ChatsScreen`) | Every conversation; filter chips; quick-contacts strip | Becomes the **DMs** tab: direct messages and groups. Kept: swipes, long-press, archive, filters (All / Unread / Direct / Groups) and undo. The strip is removed; Home's Unread DMs replaces it. |
| Workspace data (`OrgRepository`, `WorkspaceSwitcherList`) | Only on the Workspace screen | Also used by Home's switcher: same list, same saved selection |
| Calls, Contacts, Settings, Workspace | Tabs (Workspace opened from Contacts) | Ordinary screens with a Back button, opened from Home and the DMs menu |
| Home | An alias for the chat list | **New:** the workspace homepage (section 2) |
| Activity | Only the per-workspace admin log | **New:** the Activity tab (section 2); the admin log is linked from it |

## 2. What was implemented

### One palette for the signed-in app (Phase C, owner's decision)
- **Every signed-in screen is drawn in Cocoa**, whatever the app theme: the three tabs and the bar, Calls, Contacts, Settings and all its screens, Workspace and its screens, Search, New message/group/call, join, profiles, chat info, the chat screen's colours, sheets and dialogs, and the call screens.
  - It's set once in `AppRoot` (`SignedInPalette`). The tab screens no longer wrap themselves.
  - Light status- and navigation-bar icons come from the same place.
- **Signed out**, the UI-001 Ivory screens are unchanged. The revoked-device screen keeps the app theme.
- **Settings › Chats** no longer offers System/Light/Dark or an accent colour, so no setting pretends to work.
  - Wallpaper, text size, emoji, links and media stay.
  - The saved choices are kept, unused.
  - The Settings row now reads "Wallpaper, text size, links, media".
- **Call screen:** its background was a fixed green-black. It now uses the palette's `callStage`, Cocoa's warm near-black.
- **The chat screen's layout is unchanged.** Only its colours follow Cocoa. Its redesign (bubbles, composer, header) is UI-003.

### Navigation
- Exactly three tabs: **Home, DMs, Activity**.
  - Home is the start screen.
  - The selected tab is an accent pill; the others are muted.
  - Tabs keep their state and scroll position, as before.
- Badges, from real counts:
  - **Home:** channels with unread messages (not muted or archived), in any workspace.
  - **DMs:** unread messages in direct messages and groups (not muted or archived).
  - **Activity:** unread @mentions.
- The bar floats over the tab screens. Each tab screen leaves room for it and for the gesture area, so the last item isn't covered.
- The bar shows on the three tabs only. Conversations, settings and the other screens use their own Back.
- **Calls** opens from Home's Calls card, the "Call history" action on Activity, the DMs menu and the profile menu. Starting a call: the DMs menu, Calls' own "New call" button, and the conversation header (unchanged).
- **Contacts** opens from the DMs menu and the profile menu.
- **Settings** opens from your avatar.
- **Workspace** management opens from the switcher ("Manage workspace") and from "Browse teams and channels" on Home.

### Home (`WorkspaceHomeScreen`, `HomeSections`, `WorkspaceHomeRules`, `HomeViewModel`)

**Header**
- **Workspace mark and name (top left).** Tapping opens the **switcher**:
  - Your workspaces come from the server's membership list (`GET /v1/orgs`, cached for offline starts), through the existing `WorkspaceSwitcherList`.
  - Switching uses the existing saved selection (`OrgRepository.select`), the same one the Workspace screen uses.
  - The sheet also has "Manage workspace" and "Join or create a workspace".
  - With no workspace, the header says so and the sheet offers joining.
- **Your avatar (top right)** opens a sheet with your name and handle, and the existing screens:
  - edit profile;
  - all settings;
  - notifications;
  - appearance;
  - privacy and security (recovery key, devices);
  - language;
  - contacts;
  - call history;
  - accounts and sign out (the existing confirmation).
- **Search** opens the existing search screen (people, chats and message text).
- **Filter and options:** "Unread only" (the button shows a dot while it's on), "Expand all groups", "Collapse all groups".

**Quick cards**
- **Unreads**: unread conversations in this workspace's view. The card is an on/off switch for "Unread only", and is highlighted while on.
- **Drafts**: unsent drafts saved on this phone. It opens a list; opening one restores the draft in the composer, as before.
- **Calls**: calls missed in the last 24 hours. It opens Calls.

**Unread DMs**
- Unread direct messages and groups, newest first, at most 5.
- Each row: real avatar with online dot, name, preview (draft, sender and message), and an accent count (a quiet count if muted).
- "See all *n*" opens the DMs tab filtered to Unread.
- With nothing unread it says "No unread messages."

**Mentions** (collapsible)
- Conversations in this workspace's view where someone @mentioned you since you last read them, with an "@*n*" count.
- The count is the existing per-conversation `mentionCount`, kept on this phone from messages it decrypted. Opening the chat clears it.
- With none it says "No new mentions."

**Channels**
- The selected workspace's joined channels, grouped as Orbit already scopes them: organisation-wide, then each branch, then each team (by name).
- Each group header can be collapsed. Collapsed, it shows how many of its channels are unread.
- Channel rows:
  - their kind as an icon: `#`, a lock for private (from the channel's `kind`), a megaphone for announcements;
  - bold when unread, with a count, or "@*n*" when you're mentioned;
  - dimmed when muted;
  - a call icon while a call is live.
- "Browse teams and channels" opens the Workspace screen (join public channels, teams, invites).
- With no channels it says so. With no workspace, a "Join or create a workspace" card replaces this section.

**Everywhere on Home**
- Long-press on a row opens the existing actions sheet (mute, read or unread, archive with Undo, info). It's now shared with DMs as `ChatActionsSheet`.
- **New (compose) button** above the tab bar: the existing New message flow, which also offers New group.
- **States:**
  - loading shimmer;
  - offline/connecting banner with Retry;
  - blocked-notifications banner (moved here from the chat list);
  - "couldn't refresh workspaces" with Retry when nothing is cached;
  - live-call banner with Join;
  - empty sections in words.
- On open, Home refreshes the workspace list and conversations. The chat list used to do this; Home is now the first screen.

### DMs tab
- The chat list limited to direct messages and groups, titled "DMs".
- Search; the name filter; All / Unread (with count) / Direct / Groups.
- Swipes; long-press; Archived, with Undo.
- The menu: New group, Start a call, Call history, Contacts, Join with an invite.
- The New button.
- The Archived view still lists every archived chat, **channels included**, so a channel archived earlier can be restored. Home leaves archived channels out.

### Activity tab (`ActivityFeedScreen`, `ActivityViewModel`)
- **Mentions** in every workspace, with counts.
- **Missed calls** from the last 7 days (up to 20): the chat's name, or the caller's name the call log kept, plus the kind of call and the time. "Call history" opens Calls.
- **Workspace changes:** the existing branch, team and channel log of the selected workspace. It's shown only when the server says you may see it (`permissions.canViewDirectory`).
- With nothing to show: "Nothing new".

### Accessibility
- **Touch targets:** header controls, section headers and group headers are at least 48 dp. Rows are at least 52 dp tall.
- **Headings:** section titles and group headers are headings.
- **Collapsible headers:** they announce "Expanded" or "Collapsed", and are buttons with a click action.
- **Unreads card:** it's a switch, announced as on or off.
- **Tabs:** each reads as one node ("DMs, 3, tab, selected") with a click action.
- **Counts:** they have spoken labels ("Unread: 3", "Mentions: 1").
- **Text:** at large font sizes, card text wraps to two lines and names truncate.

### Performance
- The lists are lazy with stable keys.
- Section contents are computed with `remember` from the conversation list.
- No blur or other expensive effects; gradients only on the header backdrop, the selected tab, the New button and counts.

### Text
47 new strings in English, Simplified Chinese and Bengali:
- `nav_*`, `home_*`, `activity_*` and `common_retry`.
- Generated with `python3 -I -B android/i18n/gen.py`.
- 13 strings nothing uses any more were deleted:
  - 3 used only by the removed strip;
  - the 4 old tab labels;
  - the "Chats" title;
  - the "Channels" chip;
  - the theme and accent labels (Phase C).
- `settings_chats_sub` was reworded.

Older hard-coded English that's still there: the long-press sheet, the live-call banner, the switcher's member counts, and the "Private channel" / "Announcement channel" labels.

## 3. Omitted, and what each would need

| Reference element | Why it's not there | What it would need |
|---|---|---|
| Threads card ("1 new") | Replies are threads inside a conversation; nothing tracks which threads you follow or what's new in them | A per-user thread follow and read state, on the phone or server |
| Later / Saved ("1 overdue") | No saved-for-later model. Pinned messages exist but are per-conversation and shared with everyone | A personal saved-items store with optional reminders |
| "Drafts & Sent", "3 scheduled" | No scheduled sending, and no list of sent messages | Scheduled delivery (server-side) and an outbox view. **Drafts** (real, local) are shown. |
| Workspace logo image | Workspaces have no logo field (`OrgDto`) | A logo upload and a URL in the org API. The existing mark (initials on a colour) is shown. |
| Server-side mention inbox | Mention counts are kept per conversation on this phone; nothing on the server lists your mentions | A mention index on the server (it can't read encrypted messages, so the phone would have to report them) |
| Reactions and thread replies in Activity | Not tracked as activity anywhere | A per-user activity model |
| Huddles, topic streams, channel types beyond Public / Private / Announcement | Not supported | Not planned in UI-002 |

## 4. Files changed (revised UI-002, on top of `6f355a1`)

New:
- `ui/screens/home/WorkspaceHomeScreen.kt`: the Home tab, its sheets (workspaces, profile, drafts) and the filter menu.
- `ui/screens/home/HomeSections.kt`: header, quick cards, section and group headers, list rows, counts, the New button.
- `ui/screens/home/WorkspaceHomeRules.kt`: what each section and badge shows (pure).
- `ui/screens/home/HomeViewModels.kt`: `HomeViewModel`, `ActivityViewModel`.
- `ui/screens/home/ActivityFeedScreen.kt`: the Activity tab.
- `ui/screens/chats/ChatActionsSheet.kt`: the shared long-press sheet and `ChatListActions`.
- Tests:
  - `test/.../ui/screens/home/WorkspaceHomeRulesTest.kt` (7);
  - `test/.../ui/theme/SystemBarRequestsTest.kt` (1; moved);
  - `androidTest/.../HomeNavigationTest.kt` (4, for the phone).
- This report.

Changed:
- `ui/AppRoot.kt`:
  - the routes `HOME`, `CHATS` (the DMs tab) and `ACTIVITY`;
  - the three tabs and their badges;
  - Home as the start screen;
  - the DMs filter request.
- `ui/components/FloatingNavBar.kt`: the action button is removed; each tab has a TalkBack click action.
- `ui/screens/chats/ChatsScreen.kt`: the DMs tab (people only, filters, menu, New button, shared sheet); `DmListRequest`.
- `ui/screens/chats/ChatListRules.kt`: `isPeople`, `peopleOnly` for `visible` and `unreadCount`.
- `ui/screens/chats/HomeComponents.kt`: the quick-contacts strip and its rules are removed; `HomeTopBar` is kept for DMs.
- Back buttons and inset padding instead of tab padding:
  - `ui/screens/calls/CallsScreen.kt`;
  - `ui/screens/contacts/ContactsScreen.kt`;
  - `ui/screens/profile/SettingsScreens.kt` (`SettingsHub`);
  - `ui/screens/workspace/WorkspaceScreen.kt`.
- `ui/screens/conversation/ConversationInfoScreen.kt`: after leaving a group, it goes back to DMs or Home (there was no fallback for the new start screen).
- Phase C:
  - `ui/AppRoot.kt` (`SignedInPalette`: Cocoa at the root);
  - `ui/screens/home/HomeSections.kt` (the New button hides while scrolling down and while Undo shows);
  - `ui/screens/home/WorkspaceHomeScreen.kt` (the profile sheet's handle falls back to the session's);
  - `ui/screens/profile/SettingsScreens.kt` (theme and accent pickers removed);
  - `ui/screens/calls/CallOverlay.kt` (call stage from the palette).
- `android/i18n/strings.py` and the generated `res/values{,-bn,-zh-rCN}/strings.xml`.
- Tests:
  - `NavMotionTest` (the three tabs);
  - `ChatListRulesTest` (+1: people-only DMs, archived channels still restorable).
- Docs: `docs/SETTINGS.md` (navigation; the Chats settings without theme and accent) and `docs/DESIGN_SYSTEM.md` (Cocoa on every signed-in screen).

Deleted: `test/.../ui/screens/chats/HomeRulesTest.kt`. It tested the removed strip; its system-bar test moved.

Not touched: server, API contracts, database schema and migrations, crypto and keys, auth and onboarding, sessions, delivery, retention, call protocols, production configuration.

## 5. Physical-device checklist (Phase C, on the owner's HONOR phone, Orbit Test only)

Setup:
- Confirm with `adb devices -l` that the device is the HONOR phone, not an emulator.
- Install the side-by-side build `com.griipbuddy.Orbit.test` (`-Porbit.sideBySide=true`) with the SDK adb.
- Never touch `com.griipbuddy.Orbit`.
- Switch Orbit Test back to a real profile only with the owner's say-so.

Checks:
1. **Navigation:**
   - exactly Home, DMs, Activity;
   - selected and unselected looks;
   - badges match real unread and mention counts;
   - switching keeps each tab's scroll position;
   - Back from a conversation, Settings, Calls, Contacts and Workspace.
2. **Header:**
   - the workspace name and mark;
   - the switcher lists only your workspaces;
   - switching changes the channels;
   - "Manage workspace" and "Join or create";
   - the avatar opens the profile sheet, and every row opens its screen;
   - search opens Search;
   - filter: Unread only, Expand all, Collapse all.
3. **Cards:**
   - Unreads toggles the unread-only view;
   - Drafts lists real drafts, and opening one restores it;
   - Calls opens the call history, and the missed count matches.
4. **Unread DMs:**
   - real avatars, names, previews and counts;
   - "See all" opens DMs on Unread;
   - opening a chat clears it from the list.
5. **Mentions:**
   - an @mention from a second account appears with "@1";
   - opening it clears it;
   - collapse and expand.
6. **Channels:**
   - groups match the workspace's organisation, branches and teams;
   - collapse and expand;
   - unread and muted styling;
   - private and announcement icons;
   - a channel opens its conversation;
   - "Browse teams and channels".
7. **New button:** it opens New message; it sits above the bar and never covers the last row.
8. **DMs tab:**
   - only DMs and groups;
   - filters;
   - swipes and long-press;
   - Archived (including an archived channel), with Undo.
9. **Activity:**
   - mentions;
   - missed calls (from a second account);
   - Workspace changes, only for an admin;
   - "Nothing new" when empty.
10. **Look:**
    - Cocoa surfaces, peach/pink accents only where intended;
    - status- and navigation-bar icons light on the three tabs;
    - gesture and three-button navigation insets;
    - large font (Settings › Display) and a narrow width;
    - the light app theme (the tabs stay Cocoa; pushed screens follow the app theme, see section 7).
11. **TalkBack spot check:**
    - tabs read "Home, tab, selected";
    - group headers read Expanded or Collapsed;
    - the Unreads card reads as a switch.
12. **Regression:**
    - open a conversation, send, receive;
    - a notification tap opens the chat, and Back lands on Home;
    - Settings › Accounts still signs out only after confirming (don't sign out the owner's account).
13. **Automated on the phone:** `HomeNavigationTest` (4) via `am instrument` (not `connectedAndroidTest`, which uninstalls), plus `OnboardingFlowTest` for regressions.
14. **Screenshots** where HONOR allows them; secure-keyboard limits are recorded rather than worked around.

## 6. Code-level results (Phase A)

Everything ran on the Mac with Android Studio's JDK, one Gradle build at a time. No emulator was started or used.

| When | Command | Result |
|---|---|---|
| 11:55 | `./gradlew :app:compileDebugKotlin` | Succeeded on the first build |
| 11:58 | `:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin` and focused unit tests | Succeeded, after fixing one wrong import in the new instrumented test |
| 11:59 | `./gradlew :app:compileDebugKotlin --rerun` | `BUILD SUCCESSFUL in 30s`. Two Kotlin warnings in files this work touched: `SettingsScreens.kt`, a deprecated `Icons.Outlined.Logout`, reported twice. It's older code that this change only shifted down three lines. |
| 12:02–12:04 | `./gradlew :app:testDebugUnitTest :app:lintDebug` (final run) | `BUILD SUCCESSFUL in 2m 6s` |
| 12:04–12:05 | `./gradlew -Porbit.sideBySide=true :app:assembleDebug :app:assembleDebugAndroidTest` | `BUILD SUCCESSFUL in 34s` |

The focused unit tests in the 11:58 run all passed:

| Test class | Tests |
|---|---|
| `WorkspaceHomeRulesTest` (new) | 7 |
| `ChatListRulesTest` | 5 |
| `NavMotionTest` | 2 |
| `SystemBarRequestsTest` (moved) | 1 |
| `UiRulesTest` | 6 |
| `ChatSwipeRulesTest` | 5 |
| `ChatPreviewTest` | 4 |
| `OrbitPalettesTest` | 4 |

**Unit tests (full suite):**
- **340 tests in 65 classes, 0 failures, 0 errors, 0 skipped.**
- That's 337 before, minus the 6 removed strip tests, plus 9 new.
- `UiRulesTest` checks every language file and the escaping of the 47 new strings.

**Lint:**
- **0 errors, 83 warnings, 5 hints**, up from 81 warnings at `6f355a1`.
- The first full run had 89 warnings: 6 strings the redesign had left unused (`nav_chats`, `nav_calls`, `nav_contacts`, `nav_settings`, `chats_title`, `filter_channels`). They were removed and lint was run again.
- The 2 remaining new warnings are `PluralsCandidate` on "%1$d unsent" and "%1$d missed today". Like "%1$d unread", the English words don't change with the number.
- The other warnings in touched files are older: unused `brand_*`, `composer_message` and `translate_*` strings, and `SettingsScreens.kt` `ObsoleteSdkInt` and `UseKtx`.

**Test APKs:**
- `app/build/outputs/apk/debug/app-debug.apk`:
  - package `com.griipbuddy.Orbit.test`, label "Orbit Test";
  - version 0.1.5-beta (6), from the version bump already in the tree;
  - 144.5 MB, built 12:05, SHA-256 starting `5181e30782f3c534`.
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`: package `com.griipbuddy.orbit.androidtest`, with `HomeNavigationTest` and `OnboardingFlowTest`.

**Not run:**
- the release build (CI builds it);
- any test on a phone: `HomeNavigationTest` has been compiled only.

**Diff review:**
- Only the files in section 4 changed, plus the earlier version bump in `build.gradle.kts`, which was left untouched.
- Nothing is staged, committed or pushed.
- `CLAUDE.md`, untracked and from a tool, is untouched.

## 7. Known issues and follow-ups (after the device pass)

- **The Home badge** counts unread channels in every workspace, while Home lists only the selected one. A badge can point at another workspace; seen on the phone (badge 5 while "Night Shift Test" showed 1 unread channel). Options: per-workspace dots in the switcher, or a badge for the selected workspace only.
- **The missed-calls count** has no "seen" state: the Calls card counts the last 24 hours, and Activity lists the last 7 days.
- **Archived channels** are hidden from Home and the DMs main list. They're found under DMs › Archived (checked on the phone) and in Search.
- **The workspace list** needs one server round trip on first install. Until it answers, the header shows no name.
- **The chat screen** has Cocoa colours but its old layout; its redesign is UI-003.
- **Small palette details:**
  - the "Mint" wallpaper is now a warm tint, though its spoken label still says Mint;
  - the text-size slider's empty track is Cocoa's lavender.
- **Strings:** the older hard-coded English listed in section 2.
- **Found outside UI-002** (not changed here):
  - With open registration, the sign-up form's invitation code is ignored by the server, so the account isn't joined to that workspace. It only joins in invite-only mode. The phone account had to join from Home afterwards.
  - The Workspace screen's top bar scrolls away with its list. That's the app's existing pattern; system Back always works.
  - Search doesn't focus its field when opened.

## 8. Phase C: physical-device verification (2026-10-18, 12:09–13:05)

**Device and build**
- **Device:** the owner's HONOR ELI-AN00, Android 16, USB.
- **Not an emulator:** `ro.kernel.qemu`/`ro.boot.qemu` are empty, `ro.hardware` is `qcom`, `adb devices -l` shows `usb`.
- **Build:** the side-by-side debug build `com.griipbuddy.Orbit.test` ("Orbit Test") 0.1.5-beta (6), with the test package `com.griipbuddy.orbit.androidtest`.
  - Installed with the SDK adb (`adb install -r`, never uninstall); the owner tapped HONOR's install prompts.
  - Updated three times as defects were fixed.
- **Untouched:** the owner's own Orbit (`com.griipbuddy.Orbit`, Play, 0.1.5-beta) and its data were never touched. No `pm grant`, and no system settings were changed.

**Setup, approved by the owner on the phone**
- Orbit Test had been uninstalled since the UI-001 review, so a fresh account was needed.
- **Local server:** an isolated server on the Mac (`127.0.0.1:18091`, scratch data, open registration) with a local LiveKit (`127.0.0.1:17891`) so calls could ring. No production data or configuration was involved.
- **Port forward:** `adb reverse tcp:18091`.
- **Test account:** `@orbit.tester` in Orbit Test.
- **Seed data:** three local test bots created two workspaces ("Sunbeam Test Co": branch Dhaka, teams Mobile and Backend; "Night Shift Test"). They also made:
  - organisation, announcement, branch, team and private channels;
  - channel messages, two with an @mention;
  - three DMs and a group;
  - missed calls.
- **The owner's own data:** while exploring, the owner also made a workspace ("GriipBuddy") with a channel and a message.

**Automated tests on the phone** (`am instrument`, not `connectedAndroidTest`): `HomeNavigationTest` (4) and `OnboardingFlowTest` (7), **OK (11 tests)** both times.
- 12:12, after the first install.
- 13:01, on the final build.

**Manual checklist**

| Check | Result |
|---|---|
| Exactly Home, DMs, Activity; selected pill, muted others; badges match the data (Home 5, DMs 6, Activity 2, then down as chats were read) | Pass |
| Back from chats, Settings, Calls, Contacts, Search, Workspace, chat info, profiles; the bar only on the three tabs | Pass |
| Header: workspace mark and name, switcher (one, then three workspaces; role, check on the current one), switching changes channels and mentions, "Manage workspace", "Join or create" (joined both test workspaces by code) | Pass |
| With no workspace: "No workspace" header and the "Join or create a workspace" card | Pass |
| Avatar opens the profile sheet (name, @handle, every row opens its screen); search opens Search; filter menu: Unread only (filter dot), Expand all, Collapse all | Pass |
| Cards: Unreads count and on/off switch (highlighted); Drafts lists a real draft, which is restored in the composer and sent; Calls card shows "1 missed today" and opens Calls | Pass |
| Unread DMs: real avatars, names, previews, counts; "See all" opens DMs on Unread; reading a chat removes it | Pass |
| Mentions: both @mentions with "@1" and their team; opening one clears it and the Activity badge drops; collapsible | Pass |
| Channels: organisation, branch and team groups in order; announcement, private and public icons; unread counts and bold; collapse shows the group's unread count; unread-only hides read groups; "Browse teams and channels" → join #watercooler → it appears on Home | Pass |
| Long-press on Home rows: mute, read, archive (with Undo) and info; archived channel listed under DMs › Archived and restored there | Pass |
| DMs tab: people chats only, chips, menu (New group, Start a call, Call history, Contacts, Join with an invite), New button | Pass |
| Activity: mentions, missed calls (the caller and time), "Workspace changes" for a member allowed to see them, "Call history" | Pass |
| New button opens New message; the bar never covers the last row (bottom padding) | Pass, after fix 1 |
| A chat opened from outside (the intent a notification tap sends) lands in the chat; Back goes to Home | Pass |
| Messaging regression: a message sent from the phone shows "Sent"; bots' messages arrive; incoming calls ring and are logged as missed | Pass |
| Light app theme: Home stays Cocoa with light status icons (before the Cocoa-everywhere change, pushed screens were light, with dark icons) | Pass, then superseded by fix 3 |
| Every previously old screen in Cocoa: Settings (+ Chats, Privacy & Security, Notifications, Calls, Data & Storage, Language, Accounts), Contacts, Calls, Workspace (Teams, Manage), Search, chat info, profile, a group chat, the incoming-call screen | Pass, after fixes 3 and 4 |
| Touch targets from the UI dump: header controls 156 px (48 dp at 3.25×), rows ≥ 169 px (52 dp), cards 299 px | Pass |
| Gesture-navigation insets: bar above the gesture area, status-bar scrim when scrolled | Pass |

**Not checked on the device:**
- **Large font size:** the owner didn't change the system setting.
- **Live TalkBack:** turning it on is a system setting. The TalkBack semantics are covered by `HomeNavigationTest` on the phone and by the UI dumps.
- **Three-button navigation:** a system setting.
- **A tap on a real notification in the shade:** the same intent was sent directly instead.
- **Other screen sizes:** only one phone was available.

**Defects found on the phone and fixed (each rebuilt, reinstalled and retested)**
1. **The New button covered the counts** on rows scrolling under it, and the Undo bar overlapped it.
   - It now hides while you scroll down and comes back when you scroll up or reach the end. It also hides while Undo shows. This applies on Home and DMs.
   - Retested: hidden mid-list with every count visible; shown at the end; hidden during Undo.
2. **The profile sheet could open without your @handle** until your own profile record had loaded. It now falls back to the session's username. Retested.
3. **Owner's request during the test:** some screens still had the old design and colour themes. Cocoa now covers every signed-in screen, with the theme and accent pickers hidden; section 2 has the details. Retested on every screen listed in the checklist.
4. **The call screen's fixed green-black background** now comes from the palette. Retested with an incoming call.

**Screenshots** (actual device captures):
- About 30 PNGs, kept in this session's scratch folder (`scratchpad/ui002-rig/shots`), not in the repository.
- They cover Home (no workspace, Sunbeam, Night Shift), channel groups and collapse, the filter menu, Unreads on, the switcher, Drafts, DMs, Activity, Settings and its screens, Contacts, Calls, Workspace, Search, chat info, profile, a group chat and the incoming-call screen.
- One accidental capture of the launcher was deleted.

**Final results (13:01–13:04)**
- `./gradlew :app:compileDebugKotlin --rerun :app:testDebugUnitTest :app:lintDebug`: `BUILD SUCCESSFUL in 2m 32s`.
  - **340 unit tests, 0 failures**, in 65 classes.
  - Lint: **0 errors, 83 warnings** (the same 83 as Phase A's final run).
  - No new Kotlin warnings: those in touched files are older code at shifted line numbers.
- The installed Orbit Test was built at 13:00 from the same source, with `-Porbit.sideBySide=true :app:assembleDebug`.

**Git:** nothing staged, committed or pushed. `build.gradle.kts` (the version bump) and `CLAUDE.md` are untouched.

**Left on the phone and Mac until the owner decides:**
- Orbit Test, signed in to the local test account.
- `adb reverse tcp:18091`.
- The local API server and LiveKit, both running on 127.0.0.1.

