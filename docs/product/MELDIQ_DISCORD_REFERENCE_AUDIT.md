# Discord reference audit for Meldiq Android (2026-10-21)

Discord is a reference for how community messaging behaves: navigation, channel organisation, permissions and
message handling. Nothing is copied: no code, assets, icons, names, colours or layouts measured to the pixel. Meldiq
keeps its own brand (Graphite and the other presets, the Meldiq logo, its own icons from Material Symbols) and its own
security model (end-to-end encrypted messages, which the server cannot read).

Companion documents:
- `MELDIQ_COMMUNITY_CHAT_SPEC.md`: the Android screens this audit led to.
- `MELDIQ_ANDROID_INTERACTION_MATRIX.md`: every gesture and menu action, with its permission and test.

## 1. How Discord was studied

| Source | What it gave | Limits |
|---|---|---|
| **Discord desktop app, signed in** (macOS, the owner's own account, opened through computer use with the owner's approval) | Direct observation of the authenticated product: server rail, channel list, a server the owner runs (role "CEO"), a forum channel with posts, a text channel, a group DM and a one-to-one DM, the server menu, server settings, channel settings and permissions, roles, access, the Create Channel dialog (two steps for a private channel), the context menus of a message, a channel and a category, the forum's sort menu | Desktop only. Viewed, never changed: every dialog was cancelled, nothing was sent, created, edited or deleted. One draft name was typed into the Create Channel dialog and discarded with Close (the channel list afterwards showed no new channel) |
| **REA** (`rea-agents` 6.7.0, the toolkit the owner supplied, under Node 22.20.0 through `npx -p node@22.20.0`; `rea doctor` reports Node healthy, while the binary-analysis engines it lists, such as Hopper, Ghidra, IDA and jadx, aren't installed and aren't needed here) against an isolated headless Chrome (fresh profile, no cookies, port 9333) | `list-browser-targets`, `inspect-web-page --include-accessibility-text` of the developer documentation's Permissions page (3,169 accessibility nodes) and Channels resource (6,810 nodes), `capture-web-screenshot` of discord.com. The documented permission order and the channel object (types, forum tags, sort order, layouts) came from these captures | The help center is behind a Cloudflare bot check. Headless Chrome got "Performing security verification" and REA saw only that page. The check was not worked around |
| **Official help center**, read in the in-app browser pane (an ordinary browser session) | Mobile layout (Home tab with the server rail and Messages, Notifications tab, You tab, swipe to reply, details from the chat header), the 2026 mobile visual refresh (people are circles, servers are squircles, a decluttered composer with a "+" menu), Channel Categories 101 (sync, collapse shows only unread channels, mute), Forum Channels FAQ (tags, guidelines, sort, list/gallery, close post), Announcement Channel FAQ | Text only, no interaction |
| **Owner's earlier recording** `IMG_1141.MP4` (Discord mobile at 2:50–4:23) | Already summarised in `MELDIQ_3_REFERENCE_MATRIX.md` | The file is gone from `~/Desktop/video_file`; only round 2's frames remain |

The desktop captures are kept outside Git in `research/discord/` (19 screenshots plus REA's text captures). They show
real people's names and messages from the owner's servers, so they are listed in `.gitignore` and never shipped.

### What was not observed directly

- **Discord's Android or iOS app.** No signed-in mobile session was available, and signing in was not attempted.
  Mobile behaviour in this audit comes from the official help articles ("documented"), not from observation.
- Voice channels in use, stage channels, threads created from a message, onboarding questions, AutoMod rules and the
  audit log's contents.
- Anything that needs changing the owner's servers: creating a channel, reordering, editing permissions.

Every row below says which of **observed** (desktop app), **documented** (help center or developer docs, read through
REA or the browser) or **unverified** it rests on.

## 2. Reference matrix

Columns follow the owner's brief. "Android" names the Meldiq files changed; "Server" the backend change, if any.
Decisions: **adopt** (same behaviour), **adapt** (same purpose, Meldiq's form), **omit** (not for Meldiq), **later**.

### A. Main navigation

| Area | Evidence | Structure and interaction | Permissions | Decision | Android | Server | Verification |
|---|---|---|---|---|---|---|---|
| Server rail | observed (`01`, `19`); documented (mobile layout) | A narrow column of server icons (squircles); a pill at the left edge marks unread, a taller one the selected server; a red count for mentions; "Add a Server" and "Discover" at the bottom; Direct Messages at the top | — | **adapt**: Home's left rail lists your communities, with the same unread and mention signals, a selected marker, Join or create and Discover at the end. Direct messages stay their own bottom tab | `ui/screens/home/CommunityHomeScreen.kt`, `CommunityRail.kt` | — | `CommunityPanelRulesTest`; emulator screenshot |
| Mobile tabs | documented | Home (servers and DMs), Notifications, You (profile, friends, settings) | — | **adapt**: Home (communities), DMs, Activity, You. You holds your profile, status, Later, Reminders, Drafts & sent, Calls, contacts and settings (they were Home cards and a profile sheet) | `AppRoot.kt`, `ui/screens/you/YouScreen.kt` | — | `NavMotionTest`; emulator |
| Back and details | documented | Channel details open by tapping the chat's title (no swipe) | — | **adopt** (Meldiq already did this) | existing | — | existing |
| Search | observed | Server search field in the channel header; DM search ("Find or start a conversation") | — | **adapt**: search icon in the channel panel header (universal search, scoped to the community) and the existing DM search | `CommunityHomeScreen.kt` | — | emulator |

### B. Community interface

| Area | Evidence | Structure and interaction | Permissions | Decision | Android | Server | Verification |
|---|---|---|---|---|---|---|---|
| Server header and menu | observed (`02`) | Name with a chevron opens a menu: Invite, Settings, Create Channel, Create Category, Create Event, Notification Settings, Privacy, Hide Muted Channels, Copy ID; Leave for non-owners | Settings and create entries only for those allowed | **adapt**: the panel header opens a community sheet: icon, name, member count; Invite, Notifications (mute all), Settings; Mark as read, Create channel, Create category, Events, Members, Hide muted channels, Leave | `CommunityMenuSheet.kt` | — | `CommunityPanelRulesTest` (hide muted) |
| Top shortcuts | observed | Events, Members, Server Boosts above the channels | — | **adapt**: Events and Members rows; no boosts | `CommunityHomeScreen.kt` | — | emulator |
| Member list | observed | Grouped by the highest hoisted role, then Online and Offline; role colours; app badge | Members permission | **adapt**: existing Members screen (roles shown as tags) | existing `OrgScreens.kt` | — | existing |
| Discovery and joining | observed (`11`), documented | Access: Invite Only / Apply to Join / Discoverable | Owner | **adapt**: Meldiq's visibility (public/private) and join policy (open/invite) as three choices: Invite only, Public with invites, Public and open | `WorkspaceSettings.kt` | — | existing server tests |
| Rules | documented | Rules screening in Community settings | — | **adapt**: Meldiq's rules text, shown before joining and in About | existing | — | existing |

### C. Channel management

| Area | Evidence | Structure and interaction | Permissions | Decision | Android | Server | Verification |
|---|---|---|---|---|---|---|---|
| Create channel | observed (`03`–`05`) | Dialog: type (Text, Voice, Forum; Announcement in community servers), name with a type prefix, Private switch; private → Next → "Add members or roles" (search, role checkboxes, member list) → Create | Manage Channels | **adapt**: the existing sheet (Text, Announcement, Forum, Voice, Private, category); a private channel opens its member list right after creation | `ManageSheets.kt` | — | `CommunityRulesTest` |
| Category "+" | observed | Hovering a category shows "+" to create a channel in it | Manage Channels | **adopt**: "+" on the category header for people who may create channels there | `CommunityHomeScreen.kt` | — | emulator |
| Channel menu | observed (`17`) | Mark as Read, Favorites, Invite, Pin to Top, Copy Link, Mute, Notification Settings, Move to…, Edit, Duplicate, Create Text Channel, Delete | Edit, Move, Delete need Manage Channels | **adapt**: long-press sheet: Mark as read, Favourite, Mute, Notifications, Copy name; managers: Edit channel, Permissions, Move to category, Move up/down, Sync with category, Archive, Delete | `ChannelMenuSheet.kt` | `DELETE /v1/conversations/:id` | `ChannelMenuRulesTest`; server `channel_delete.test.ts` |
| Category menu | observed (`16`) | Mark As Read, Collapse Category, Collapse All, Mute Category, Notification Settings, Move to…, Edit Category, Delete Category | Edit and Delete need Manage Channels | **adopt** (Move to… becomes Move up/down) | `CategoryMenuSheet.kt` | — | `CommunityPanelRulesTest` |
| Collapse | documented | A collapsed category still shows its channels with unread messages; collapsed and muted hides them unless you're mentioned | personal | **adopt**, stored in the synced navigation preferences | `CommunityPanelRules` | — | `CommunityPanelRulesTest` |
| Channel settings | observed (`06`) | Overview (name, topic, slowmode, content visibility, hide after inactivity), Permissions, Invites, Integrations, Delete Channel | Manage Channels | **adapt**: Edit channel sheet (name, topic, text/forum, archive) and Delete; no slowmode yet (later) | `EditChannelSheet.kt` | existing PATCH | emulator |
| Permission sync | observed (`07`); documented | "Permissions not synced with category: X — Sync Now"; a synced channel copies the category | Manage Permissions | **adapt**: Meldiq layers a channel's overwrites on top of its category's, so a channel with no overwrites of its own *is* in sync. The permissions screen says "Synced with category" or "Has its own permissions" with **Sync with category** (removes the channel's own overwrites) | `ChannelPermissionsScreen.kt`, `OverwriteRules.synced` | existing PUT | `CommunityRulesTest` |
| Advanced permissions | observed (`08`); documented (developer permissions page via REA) | Per role or member, each permission is Deny / Inherit / Allow; order: @everyone deny→allow, roles deny→allow, member deny→allow | Manage Permissions | **adopt** (Meldiq had the same order since the rebuild); the screen shows what a row inherits from the category | `ChannelPermissionsScreen.kt` | — | existing server tests |
| Reorder | documented | Drag and drop; moving into a category asks whether to sync | Manage Channels | **adapt**: Move up/down and Move to category from the menus (drag is awkward on a phone list that also scrolls); moving never changes a channel's own overwrites, and the move result says how many people's access changed | existing | existing | existing |

### D. Administration

| Area | Evidence | Structure | Decision | Android | Verification |
|---|---|---|---|---|---|
| Server settings | observed (`09`) | Grouped: server (Profile, Tag, Engagement), Expression, People (Members, Roles, Invites, Access), Apps, Moderation (Safety Setup, Audit Log, Bans, AutoMod), Community, Template, Delete Server | **adapt**: Community settings in groups: Community (Overview, Access & rules), People (Members, Roles, Invites), Channels (Channels & categories), Moderation (Bans, Audit log, Activity), Events, Usage, then Transfer ownership / Delete community / Leave. No apps (connectors were removed) | `CommunitySettingsScreen.kt` | emulator |
| Roles | observed (`10`) | Default permissions (@everyone) card, search, Create Role, list with member counts, edit and more buttons, drag to reorder | **adapt**: existing Roles screen | existing | existing |
| Bans | observed (menu) | Ban list with reasons | **adopt**: existing Bans screen | existing | existing |
| Destructive actions | observed | Delete in red, separated at the end of menus | **adopt**: every delete asks to confirm and names what is lost | menus | `ChannelMenuRulesTest` |

### E. Community chat

| Area | Evidence | Structure and interaction | Decision | Android | Verification |
|---|---|---|---|---|---|
| Message runs | observed (`01`) | Avatar, coloured name, "Yesterday at 4:45 PM", then the run's messages without repeating the name; date dividers with a line | **existing**: Meldiq's Stream layout | existing `MessageBubble.kt` | existing |
| Reply preview | observed | A curved line from the reply to a small avatar, @name and a snippet above the message | **existing** (reply quote) | existing | existing |
| Mentions of you | observed (`18`) | The whole message row is tinted, with an accent bar at its start | **adopt** | `MessageBubble.kt` | `MentionHighlightTest` |
| Unread marker | unverified on desktop (none unread); documented behaviour of "New" dividers | A "NEW" divider above the first unread message; opening a channel lands there | **adopt** | `ConversationScreen.kt`, `UnreadMarker` | `UnreadMarkerTest` |
| Hover toolbar / message menu | observed (`15`) | Quick reactions; Add Reaction, Edit, Reply, Forward, Create Thread, Copy Text, Pin, Bookmark, Reminder, Mark Unread, Copy Link, Delete (red) | **existing** in Meldiq's long-press menu (reply, react, edit, delete, copy, forward, pin, thread, save for later, remind, translate, info); **added** Mark unread | `MessageActions` | `ChannelMenuRulesTest` |
| Composer | observed; documented (refresh) | "+" for files and actions, text field, emoji, send | **existing** ("+" attachment sheet) | existing | existing |
| Swipe to reply | documented | Swipe across a message | **existing** | existing | existing |

### F. Forums and threads

| Area | Evidence | Structure and interaction | Decision | Android | Server | Verification |
|---|---|---|---|---|---|---|
| Post list | observed (`12`) | "Search or create a post…", New Post, Sort & View, tag chips, cards with title, author: first line, reaction, reply count, age | **adapt**: search field, New post, sort, tag chips, cards with title, author, preview, replies, last activity | `Forum.kt` | forum tags | `ForumRulesTest` |
| Tags | documented (FAQ; channel resource via REA) | Up to 20 tags per forum, chosen at posting, filtered by tapping chips | **adopt**: a forum's tags are channel settings (names only, readable by the server); a post's tags travel inside its encrypted message, so filtering happens on the phone | `Forum.kt`, `ForumRules` | `conversations.forum_tags`, migration 033 | `ForumRulesTest`; server `forum_tags.test.ts` |
| Sort & view | observed (`14`) | Recently Active / Date Posted; List / Gallery; tag matching Some / All | **adapt**: Recent activity / Newest; tags match any selected (all: later); list only (gallery: later) | `Forum.kt` | — | `ForumRulesTest` |
| Open a post | observed (`13`) | Opens as a side panel with the title, reactions, Following, copy link; skeleton rows while loading | **adapt**: a post opens as its thread screen with the title in the header | existing | — | existing |
| Close post | documented | Close removes from new posts and locks | **later** | — | — | — |

### G. Direct and group messages

| Area | Evidence | Structure and interaction | Decision | Android | Verification |
|---|---|---|---|---|---|
| DM list | observed (`19`) | Avatar with presence, name, "3 Members" for groups, close button; Find or start a conversation; Friends | **existing** in DMs (avatar, presence, preview, time, unread, muted, pinned, archive, chips) | existing `ChatsScreen.kt` | existing |
| One-to-one header | observed | Avatar with presence, name; voice call, video call, pins, add people (makes a group), profile panel; search | **existing** (calls, pins, security, search, details) | existing | existing |
| Group header | observed (`18`) | Group avatar and name; calls, pins, add people, member list; Members panel with "Invite to Group DM" | **existing** (calls, details with members, add people) | existing | existing |
| Favourites | documented | Long-press a DM to Favorite; it stays on top with a star | **existing** (star) | existing | existing |
| DMs and channels look different | observed | DMs show the person; channels show "#name" and channel controls | **adopt**: channels keep their type icon and topic, DMs their person, groups their group identity (no calls in text channels) | existing | existing |

### H. Mobile behaviour

| Area | Evidence | Decision |
|---|---|---|
| Long-press menus | documented | Long-press everywhere a context menu is needed (channels, categories, messages, DMs), opening a bottom sheet |
| Shapes | documented (refresh) | People circles, communities squircles: Meldiq's rail uses rounded squares for communities, circles for people |
| Back | documented | System back closes sheets first, then returns along the stack (Compose Navigation) |
| Themes | documented | Meldiq's own presets in light and dark (Graphite dark by default) |

## 3. Limits of this round, stated plainly

- Discord's mobile app was never observed. Mobile patterns come from help articles.
- The desktop app was observed in one owner-run server (10 members) and two conversations. Large servers, stage
  channels, onboarding and AutoMod were not seen.
- REA could not read the help center (Cloudflare). It did read the developer documentation and the public site.
- Nothing about Discord's internals was examined: no bundles, no network traffic of the signed-in app, no tokens.

## 4. What was built and how it was checked (2026-10-21)

Screens: `docs/product/MELDIQ_COMMUNITY_CHAT_SPEC.md`. Every action: `MELDIQ_ANDROID_INTERACTION_MATRIX.md`.
Screenshots of the new Meldiq app on the emulator (test data only): `docs/product/screens-2026-10-21/`.

**On the Android emulator** (Medium Phone, API 36.1, the side-by-side debug build `com.meldiq.android.test`,
against a throwaway local server on this Mac reached through `adb reverse`, with a second member scripted through the
server's reference client posting encrypted messages):

| Checked | Result |
|---|---|
| Sign-up with an emailed code, start a community | worked (code read from the local server's mail folder) |
| Home: rail, panel, categories in order, voice channel row | as specified |
| Unread and mention signals | rail mention count, channel bold with mention count, Home and Activity tab badges |
| Opening a channel with unread messages | "New messages" line above exactly the six messages that arrived while away |
| A message mentioning you | tinted row with an accent bar |
| Channel menu, category menu, community sheet | items as `CommunityMenuRules` decides; Favourite moved the channel to Favourites |
| Collapse a category | only the open channel stayed visible; tapping the header expanded it |
| Create a forum channel from a category's "+" | created and opened |
| Edit channel: three forum tags | saved on the server; New post offered them; the post carried its tag; tag chips filtered (match and no match) |
| Delete a channel by typing its name | server answered 200; the channel left the panel |
| Community settings, You tab, DMs tab | drawn as specified |

Found and fixed during the emulator run: the first-run introduction still advertised the removed app connectors and an
"HR, coming soon" page (replaced by community pages); "team", "teammates" and "branches" wording in seven shown
strings; "1 members"; a forum channel's header showed "#" instead of its forum icon; a forum post's avatar used "You".

**Not checked on a device:** voice channels with sound (the emulator has no microphone, and listen-only needs a
LiveKit server and a second participant), uploads over a real mobile network, and touch feel. The owner offered to
connect a phone for those (§5).

## 5. Phone checks still to do

With the phone on USB (the side-by-side test app, a local server on this Mac through `adb reverse`, the owner tapping
install and permission prompts):

1. Voice channel: join with the microphone allowed; a second participant (the bot) hears you. Then a role with
   `voice.speak` denied on that channel: join again; the microphone, camera and share buttons are disabled and the
   notice says you can only listen; the bot still hears nothing from you.
2. A channel with `voice.connect` denied: the channel says you can't join; Join isn't offered.
3. A large file (hundreds of MB, ideally 1 GiB) over Wi-Fi and over mobile data, in a channel, with the screen off
   for part of it.
4. Long-press feel on channel and category rows; scrolling the rail and the panel; system back through sheets.
5. Large text (Android font size) on Home and the channel menus.
