# Orbit UI-002: home and chat-list redesign (2026-10-18)

> **Superseded the same day** by the revised UI-002 brief (workspace home, Home / DMs / Activity tabs): see
> `docs/ORBIT_UI002_WORKSPACE_HOME_2026-10-18.md`. This report describes the first version, committed in `6f355a1`.

Scope: the signed-in home screen (the Chats tab) and the bottom navigation, in the Cocoa direction from UI-001. The
conversation screen and contact profile (UI-003) aren't touched. Committed and pushed to `internal-beta` together with UI-001. A device pass is
planned for tomorrow; this round was checked on the Mac only (see "Build and test results").

**Reference.** `docs/design-references/home-reference.png` isn't in the repo. The owner's image was used: three dark
cocoa phones; the middle one shows the chat list with a story row, filter chips, rows and a floating bar with "+".

**Before UI-002 started:** UI-001's final Gradle run finished, `BUILD SUCCESSFUL in 7m 43s`, exit 0, 2026-10-18
02:17–02:24:
- 331 unit tests, 0 failures;
- lint 0 errors;
- debug and release built;
- androidTest compiled.

The UI-001 changes were kept as they are (nothing reset or discarded).

## 1. What was implemented

**Cocoa on the home screen only.**
- `ChatsScreen` draws itself in `ProvideGriipPalette(CocoaColors)` in either app theme.
- Its long-press sheet, banners, empty state, undo bar and loading shimmer follow it.
- Calls, Contacts, Settings, the conversation screen and the auth screens keep their palettes.

**System bars.**
- New `SystemBarRequests` / `DarkSurfaceSystemBars()` (`ui/theme/SystemBars.kt`): while the chat list is shown, it asks
  `MainActivity` for light status- and navigation-bar icons. Leaving it hands the bars back to the theme.
- Signed out, the bars stay as UI-001 set them (dark icons on ivory).
- When the list scrolls under the transparent status bar, a cocoa scrim fades in behind the clock.

**A. Top bar** (`HomeTopBar`):
- a large "Chats" title (a heading for TalkBack);
- a round **search** button that opens the existing Search screen (people, chats, message text);
- a round **overflow** menu with what the app already does: New group, Start a call (the existing new-call picker),
  and Join with an invite.
- No camera button: there's no camera action that fits this screen.

**Name filter.** The existing "Filter chats by name" field stays, under the title. Like before, it also finds archived
chats.

**B. Quick contacts** (`QuickContactsStrip`, `HomeRules.quickContacts`):
- A horizontal strip: first a **New** tile (the existing New message flow), then the people you have a **direct chat**
  with, in the chat list's own order (most recent first), at most 12.
- Real profile photos where you're allowed to see them, initials otherwise.
- An accent ring marks a chat with unread messages; a green dot marks someone online. Both appear only when this phone
  knows it.
- Tapping a person opens that chat.
- It's hidden when you have no direct chats, while filtering by name, and in the archived view.
- Each tile is one button for TalkBack ("Chat with Ann Rahman, 2 unread, online").

**C. Filter chips** (`AccentChip`):
- **All, Unread (count), Direct, Groups, Channels**: the filters that already worked, now scrollable chips.
- The selected chip is filled with the peach → pink gradient; the others are a quiet raised fill. They're tabs for
  TalkBack, with selected state.

**D. Conversation rows** (`ConversationRow`):
- Same content, rules and actions as before: avatar, name, last-message preview or draft or "typing…", time, live-call
  tag, muted icon, @mention badge and unread count.
- Bigger avatar (56 dp), more padding, a minimum height of 80 dp, and space instead of dividers.
- Groups (circle with a group icon), channels (rounded square) and people stay distinguishable.
- Swipe actions (read/unread, archive, mute), long-press actions and tap-to-open are unchanged.
- The list order is the database's (newest first), unchanged.

**E. Floating bottom navigation** (`FloatingNavBar`, `ui/components`):
- A rounded bar raised above the gesture area with the four existing tabs: **Chats, Calls, Contacts, Settings**.
- The selected tab is an accent-gradient pill; the others are muted. The unread total stays as a badge on Chats.
- A round accent **"+"** beside the bar runs the existing flows: **New chat** (Start a call on the Calls tab).
- The bar is drawn in Cocoa on every tab. It replaces the docked `GriipNavigationBar`, which was removed.
- It floats over the screens. The existing `LocalTabBarPadding` measures the whole bar, including the gesture inset, so
  the last row isn't covered.

**States:** loading (shimmer), empty ("No conversations yet" with New message), no filter matches, offline or
connecting (connection banner with Retry), blocked notifications, live call to join, and the archived view are all still
there.

## 2. Files changed (UI-002)

New:
- `ui/theme/SystemBars.kt`: light system-bar icons on request.
- `ui/components/FloatingNavBar.kt`: the floating bar and its "+" action.
- `ui/screens/chats/HomeComponents.kt`: `HomeRules`, `QuickContact`, `HomeTopBar`, `QuickContactsStrip`.
- `test/.../ui/screens/chats/HomeRulesTest.kt`: 6 tests.
- This report.

Changed:
- `MainActivity.kt`: provides `SystemBarRequests`; the bar style follows the requests.
- `ui/AppRoot.kt`: `FloatingNavBar` in the Cocoa palette, with the contextual "+".
- `ui/components/Chrome.kt`: the unused `GriipNavigationBar` was removed.
- `ui/components/Inputs.kt`: `AccentChip`.
- `ui/screens/chats/ChatsScreen.kt`: `ChatsScreen` is now Cocoa, with the new header, strip and chips.
  `NewChatScreen` and `RemoteContactSheet` are unchanged.
- `ui/screens/chats/ConversationRow.kt`: sizes and spacing; dividers off by default.
- `android/i18n/strings.py` and the generated `res/values{,-bn,-zh-rCN}/strings.xml`: 6 new strings (`home_more`,
  `home_start_call`, `home_new`, `home_chat_with`, `home_unread_count`, `home_online`).

Not touched: server, API, database schema and migrations, crypto and keys, auth and onboarding behaviour, delivery,
calls, and the conversation and profile screens.

## 3. Existing interactions preserved

- Tap a row to open the conversation; long-press for mute/unmute, mark read/unread, archive/unarchive, and info.
- Swipes: right for read/unread, left for archive/restore, and mute.
- Undo after archiving; the archived list with Back.
- The name filter (including archived chats); the five filters and the Unread count.
- Search screen; new chat; new group; start a call; join with an invite.
- Joining a live call from the banner; reconnect from the connection banner; the notification-problem banner.
- The unread badge on Chats; tab switching that keeps state and scroll; a deep link or notification opening a
  conversation.
- The scroll position kept when coming back from a chat (`ChatsViewModel`, now with a remembered `LazyListState`).

## 4. Intentionally omitted (no backend or data model)

| Reference element | Why it's not there |
|---|---|
| Stories / status ring with counts | Orbit has no stories or status backend. The strip shows real direct-chat contacts instead; a ring means unread messages, not stories. |
| "Favorites" filter | No favourite or pinned field on conversations; adding one needs a schema change |
| "Work" filter | Workspace chats are the channels; a separate "Work" chip would just repeat "Channels" |
| "Communities" filter | That's Orbit's "Channels" filter, kept under its own name |
| Pinned-chat icon | No pin state in the data model |
| Delivery ticks in rows | The conversation row doesn't store the last message's delivery state (only its preview text) |
| Camera button | No camera action that fits the chat list |
| "Updates" tab | No updates or stories destination; the real tabs are Chats, Calls, Contacts, Settings |
| "Profile" tab | Your profile lives under the Settings tab (unchanged) |

## 5. Build and test results

All on the Mac (JDK from Android Studio). Nothing in UI-002 has run on a device yet.

**First, the fast checks:**
- `./gradlew :app:compileDebugKotlin` succeeded, with no warnings in the UI-002 files.
- Focused unit tests all passed:

  | Test class | Tests |
  |---|---|
  | `HomeRulesTest` (new) | 6 |
  | `ChatListRulesTest` | 4 |
  | `ChatSwipeRulesTest` | 5 |
  | `ChatPreviewTest` | 4 |
  | `UiRulesTest` | 6 |
  | `NavMotionTest` | 2 |
  | `OrbitPalettesTest` | 4 |

**Then the full check:** `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin`.
It was stopped partway, at 02:30–02:39, and the two unfinished tasks were run again on their own. Results:

- **Unit tests:** **337 tests, 0 failures, 0 errors, 0 skipped**, in 64 classes. That is UI-001's 331 plus the 6 new ones.
- **`assembleDebug`:** succeeded.
- **`compileDebugAndroidTestKotlin`:** succeeded. `OnboardingFlowTest` and the other instrumented tests still compile.
- **`lintDebug`:** succeeded, `BUILD SUCCESSFUL in 11m 18s`, at 02:39–02:51. It reported **0 errors, 81 warnings and 5 hints**. UI-001 had 80 warnings.
  - The one new warning is `PluralsCandidate` on `home_unread_count` ("%1$d unread"). It's harmless in English, since "unread" doesn't change with the number. Making it a plural is a follow-up.
  - There are no other lint findings in UI-002 files.

**Not run:** `assembleRelease`, which CI builds, and any instrumented test on the phone.

## 6. Visual issues and known bugs to check tomorrow (device pass)

- **Not seen on a device.** Nothing in UI-002 has been looked at on a phone yet. To check:
  - status- and navigation-bar icon colour switching between Chats (Cocoa) and other tabs in light mode;
  - the floating bar over the gesture area and with three-button navigation;
  - the last row clearing the bar;
  - the overflow menu's colours;
  - the strip's ring and online dot;
  - the scrim when scrolled;
  - large fonts and small screens.
- **Mixed themes.** In light mode, Calls, Contacts and Settings stay light while the bar above them is Cocoa. This is
  transitional until those screens move to Cocoa.
- **Light-mode handover.** Chats is dark in light mode, so moving from Chats to a light screen flips the bar icons when
  the Chats screen leaves composition (end of the transition). Check that it doesn't flicker.
- **Untranslated text.** Existing hard-coded English on this screen (live-call banner, empty-state lines, the long-press
  sheet, "Archived · n chats", "Muted", "Mentioned") isn't translated yet.
- **No Compose UI test for the home screen.** It needs seeded conversations (the `ConversationOpenTest` pattern) and a
  device.
- **Phone state from the UI-001 review:**
  - Orbit Test on the owner's phone still has two test profiles (`@ui.tester` on the local test server, and one empty
    profile) and is active on the empty one.
  - The local test server (port 18091) and its `adb reverse` may still be running from this session.
  - Switching back and removing the test profiles is pending the owner's go-ahead.

## 7. Recommended next step

UI-003: the conversation screen and contact profile in Cocoa:
- message bubbles;
- composer;
- header with call actions;
- @mentions in peach;
- media stack;
- the profile with stat tiles and settings rows.

These should reuse `ProvideGriipPalette(CocoaColors)`, `DarkSurfaceSystemBars()`, `AccentChip` and the tokens. Do the
device pass for UI-001 and UI-002 first.
