# Meldiq Android: communities and chat, screen by screen (2026-10-21)

What the Android app does after the Discord reference round (`MELDIQ_DISCORD_REFERENCE_AUDIT.md`). Each screen lists
its parts, its states, and the rules that decide what shows. Rules live in plain Kotlin objects with unit tests;
screens only draw them.

## 1. Navigation

Four bottom tabs, always the same order:

| Tab | Holds | Badge |
|---|---|---|
| **Home** | Your communities (rail) and the selected community's channels (panel) | unread channels in communities |
| **DMs** | Direct messages and groups (the existing list: chips, pinned, archive, search, new message) | unread DMs and groups |
| **Activity** | Mentions, threads, missed calls, the community log | mentions |
| **You** | Your profile and status, notification pause, Later, Reminders, Drafts & sent, Calls, Contacts, settings, accounts | overdue reminders |

Opening a channel, a DM, settings or anything else pushes a screen over the tabs; Back returns to the same tab with
its scroll position. The tab bar hides on pushed screens.

## 2. Home: rail and channel panel

```
┌────┬──────────────────────────────┐
│ ▣  │ Community name ⌄    🔍  👤+   │  header: opens the community sheet; search; invite
│ ▣• │ 📅 Events   👥 Members        │  shortcuts
│ ▣3 │ ★ FAVOURITES                  │  your starred channels here
│ ── │ ⌄ START HERE              +   │  category: tap collapses, long-press opens its menu,
│ ＋ │   # welcome                   │  + creates a channel in it (if you may)
│ 🧭 │   📣 announcements        ③   │  unread: bold; mentions: count
│    │ › TALK (collapsed)            │  collapsed: only channels with unread stay visible
│    │   # general               •   │
│    │   🔊 lounge                    │  voice: people inside listed under it
│    │       ◯ Rizve                 │
└────┴──────────────────────────────┘
```

**Rail** (72 dp): your communities as rounded squares (the community photo, or its initials on its colour), in the
server's order. Selected: a bar at the start edge and a stronger outline. Unread anywhere in a community: a short bar
at the start edge; mentions: a count badge at the bottom-end corner. Below a divider: **Join or create** (+) and
**Discover** (compass, only on servers with `community-discovery`). The rail scrolls on its own.

**Panel**, for the selected community:
- Header: the community's name and a chevron (opens the community sheet, §3), search (universal search), invite
  (only with `canInvite`).
- Shortcuts: Events (scheduled events in voice channels) and Members (only with `canViewDirectory`).
- Favourites: channels you starred in this community, when there are any.
- Your sections: the personal sections you made (kept from the old Home).
- Categories in the community's order, then channels without a category ("Channels"). A category header shows its
  name, a chevron, and "+" for people who may create channels there.
- Channel rows (44 dp): the type icon (#, lock for private, megaphone, forum, speaker), name; unread in the primary
  text colour and bold; muted dim; a mention count, or a dot for unread without mentions; the last channel you opened
  in this community is highlighted. A voice channel lists the people in it.
- Channels you can see but haven't joined (public channels created before you joined, opt-in ones) appear dimmed;
  tapping one joins and opens it.
- Hide muted channels (community sheet) hides muted channels unless they mention you.

States: loading (skeleton rows), offline (the cached community, a connection banner), no communities (an empty state
with Join or create and Discover), the community gone (removed or banned: it disappears and the next one is chosen),
error (banner with Retry).

Rules (`CommunityPanelRules`): section order; collapsed categories keep unread channels; hidden muted channels; rail
badges (unread, mentions) per community; the remembered channel per community.

## 3. Sheets on Home

**Community sheet** (tap the header): the community's mark, name and member count; buttons Invite, Notifications
(mute or unmute every channel), Settings; then Mark as read, Create channel, Create category (with the right
permissions), Events, Members, Hide muted channels (switch), Copy community name, Leave community (red; not for the
owner).

**Category menu** (long-press a category header): Mark as read, Collapse / Expand, Collapse all, Mute category / Unmute
category; for managers: Create channel, Edit category (rename), Permissions, Move up, Move down, Delete category (red,
confirm; refused while a channel in it has its own permissions, as the server says).

**Channel menu** (long-press a channel): the channel's icon, name and topic; Mark as read, Favourite / Remove from
favourites, Mute / Unmute, Copy channel name; for managers: Edit channel, Permissions, Move to category, Move up, Move
down, Archive, Delete channel (red, confirm, says that messages and files go for everyone).

**Edit channel**: name, topic, format (text or forum, text channels only), private (people with
`channels.visibility`), Save. Errors from the server are shown in the sheet.

## 4. Community settings (Routes.WORKSPACE)

Groups, each a card of rows that open the existing screens:

| Group | Rows |
|---|---|
| Community | Overview (name, description, photo), Access & rules (visibility, joining, rules) |
| People | Members, Roles, Invitations |
| Channels | Channels & categories (arrange, create, permissions) |
| Moderation | Bans, Audit log, Activity log |
| More | Events, Usage |
| Danger zone | Transfer ownership, Delete community (owner); Leave community (everyone else) |

Rows appear only for people with the matching permission.

## 5. Conversation screen

Shared by channels, DMs, groups and threads; the header and banners differ by type.

| Type | Header | Under the header |
|---|---|---|
| Text channel | # icon, name, category · members · online | topic, pinned, voice banner if live |
| Announcement | megaphone, name | "Only people allowed to announce can post" when you can't |
| Forum | forum icon, name | post list instead of messages (§6) |
| Voice channel | speaker, name | who is in it, Join; listen-only notice when you may not speak |
| DM | the person, presence and status | paused-notifications notice |
| Group | group avatar, members | — |
| Thread / forum post | title (or "Thread"), "in #channel" | the parent message on top |

Timeline additions in this round:
- **New messages divider**: when a conversation opens with unread messages, a "New messages" line sits above the
  first unread one and the list starts there. The position is captured before the conversation is marked read and
  stays until you leave.
- **Mentions of you**: a message that mentions you (or @everyone, where it reached you) has a tinted background and an
  accent bar at its start, in both layouts.
- **Mark unread** in the message menu: marks the conversation unread again from that message, so it comes back as
  unread in Home and the DMs list.

Unchanged and kept: message runs, date dividers, replies, reactions, edits and deletes, pins, threads, swipe to reply,
voice messages, polls, stickers, link previews, translations, search in the conversation, bulk save, the "+"
attachment sheet (photos, camera, files, location, profile card, poll), upload progress and retry.

## 6. Forum channels

- Header row: a search field ("Search posts") and **New post**; a sort button (Recent activity, Newest); tag chips
  when the forum has tags (tap to filter; several chips show posts with any of them; "All" clears).
- Cards: title (bold), the first lines, the author, the post's tags, reply count, time of the last activity.
- New post sheet: title, text, tags (when the forum has any).
- Forum settings (managers, in Edit channel): the forum's tags (up to 20, 1–20 characters each).
- Security: a post's title, text and tags are inside its end-to-end encrypted message; the server only knows the
  forum's list of tag names. Search, sorting and tag filters run on the phone over the posts it has.

## 7. Voice channels

- Join from the channel row (Home) or the channel screen. The server checks `voice.connect`; without it the join is
  refused with "You can't join this voice channel".
- Speaking: `voice.speak`. Without it, the media token can't publish: Meldiq joins with the microphone off, shows
  "You can listen here, but not speak", and the microphone button is disabled.
- Both are channel permissions (role grants, category and channel overwrites), checked by the server for every join.

## 8. Direct messages and groups

Unchanged lists and screens, now reached from the DMs tab only: avatars with presence, previews, times, unread counts,
muted and pinned states, chips (All, Unread, Direct, Groups), archive, search, new message and new group. Group
details: name and photo, members, add (for managers), leave. A person added to a group sees messages from when they
were added: earlier messages were encrypted for the members at the time (docs/E2EE.md).
