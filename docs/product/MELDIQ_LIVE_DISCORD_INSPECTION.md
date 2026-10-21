# Live Discord inspection, control by control (2026-10-21, round 3)

The control-level record of the signed-in Discord desktop app, compared with Meldiq's code. Feature-level status stays
in `MELDIQ_FEATURE_GAP_MATRIX.md` (feature IDs like `MOD-07`); this file holds the individual controls (IDs like
`CHAT-MSG-007`) and points each one at a feature ID. Friends, DMs, group DMs, invitations and calls have their own
files:
- `MELDIQ_SOCIAL_AND_COMMUNICATION_AUDIT.md`;
- `MELDIQ_COMMUNITY_INVITATION_SPEC.md`;
- `MELDIQ_CALLING_SCREENSHARE_AUDIT.md`.

Earlier rounds: `MELDIQ_DISCORD_REFERENCE_AUDIT.md` (round 1, server settings, channel creation, permissions, roles) and
`DISCORD_FEATURE_AUDIT.md` (developer-docs evidence). Discord is a reference for structure and behaviour only. No
code, assets, names or brand are copied.

## 1. Access, method and limits

| Question | Answer |
|---|---|
| Tool | Claude Code's computer-use server: the background `app_*` tools on the Discord window, and full-screen control (approved by the owner twice this session) for hover states and right-click menus |
| Does it act on the owner's computer? | Yes. It drives the Discord desktop app installed on the owner's Mac (`com.hnc.Discord`, build "stable 643186", shown in Settings), with no separate browser |
| Discord open and signed in? | Yes, the owner's own account. No password, code or token was asked for, seen or stored. Masked email and phone in Settings › Account were left masked ("Reveal" was not pressed) |
| What could be reached | Everything a signed-in user sees: <ul><li>member view of a large public Community server (about 4,000 online);</li><li>owner view of the owner's own small server (round 1's server, role "CEO", not a Community server);</li><li>DM and group-DM lists and headers;</li><li>user settings;</li><li>discovery;</li><li>creation flows up to their final button</li></ul> |
| What could not | <ul><li>Discord's Android and iOS apps (no mobile session);</li><li>any flow that changes data or reaches other people: sending, reacting, joining voice, calling, sharing a screen, creating a server, channel, event or invite, sending, accepting or cancelling friend requests, blocking or removing a friend, submitting a report;</li><li>Community-only admin features (onboarding editor, Server Insights, Welcome Screen editor), because the owner's server isn't a Community server and enabling that would change it</li></ul> |
| Evidence | 94 screenshots in `research/discord/live-2026-10-21/`. Named files are cited below (for example `vlenmw` → `NNN-message-menu.jpg`); round-3 social screenshots start with `2-`. The folder is git-ignored because the captures show real people's names, avatars and messages |
| Evidence classes | **O** observed this round (file named); **O1** observed in round 1 (`research/discord/NN-*.jpg`); **D** Discord's official documentation; **U** unverified product knowledge |

### Safety log (everything this inspection changed in the owner's Discord)

Every dialog was closed with Cancel, Close or Escape, and nothing was sent, posted or submitted. These side effects
happened, and are recorded here so nothing is hidden:

1. **Show All Channels (REA server menu):** the menu was still animating, so a click landed on this toggle. It was
   turned back off at once (`show-all-channels-reverted.jpg`), and the channel list matched what it was before.
2. **REA "Get Started" checklist:** opening the #showcase forum ticked one task (1 of 5 → 2 of 5). Discord ticks a
   guide task when you visit its channel. That's a read-state change on the owner's member profile in that server, and
   it can't be undone by the user.
3. **Read state:** viewing channels and DMs marked them read, as any visit does.
4. **Group DM invite link:** opening "Add Friends to DM" in a group DM made Discord show an invite link to that group
   ("expires in 24 hours"). It wasn't copied or shared.
5. **Report dialog:** after it was closed on the Report Summary step without pressing Submit Report, Discord showed a
   "How'd your reporting experience go?" survey. It was dismissed with Escape, without an answer.

## 2. Status for every control

Each row carries two statuses:
- **Insp.** (Discord):
  - **Ins** inspected and documented;
  - **Part** partially inspected (menu entry seen, flow not walked);
  - **Doc** documentation only;
  - **Blk** blocked (with the reason).
- **Meldiq:**
  - **KEEP** already equivalent;
  - **FIX** present but wrong;
  - **IMPROVE** present, could be better;
  - **ADAPT** Discord's idea in Meldiq's own form;
  - **BUILD** missing;
  - **DEFER** later, with a reason;
  - **EXCLUDE** won't build, with a reason.

The **Batch** column names the implementation batch in §9 that owns the work.

## 3. Navigation and home

| ID | Where | Control | Activation → result | Persists | Ev | Insp. | Meldiq today | Meldiq | Batch / feature |
|---|---|---|---|---|---|---|---|---|---|
| NAV-CTRL-001 | Server rail | Server icon (click) | Opens that server's last channel; white pill marks selection, dot marks unread, red badge mentions | read state | O `friends-home` | Ins | Community rail, `ui/screens/community/CommunityPanel.kt` | KEEP | NAV-01 |
| NAV-CTRL-002 | Server rail, right-click icon | Server menu: Mark As Read, Invite to Server, Mute Server ›, Notification Settings › (shows level), Hide Muted Channels ☐, Show All Channels ☐, Privacy Settings, Edit Per-server Profile, Move to… › (First in List, Last in List), Leave Server, Copy Server ID | Each opens the same item as the header menu; Move to reorders the rail | yes (each item) | O `server-icon-menu`, `server-move-to` | Ins | Long-press community icon: `CommunityMenus.kt` (mark read, mute, hide muted, leave). No rail reorder, no per-community nickname | IMPROVE | B2: rail reorder (NAV-11 new) |
| NAV-CTRL-003 | Rail end | "+" Add a Server | Opens Create Your Server (§8) | — | O `create-server` | Ins | Create community, `CreateCommunity*` | KEEP | COM-01 |
| NAV-CTRL-004 | Rail end | Discover (compass) | Discover page: Apps / Servers, category tabs (Home, Gaming, Music, Entertainment, Science & Tech, Education, Student Hubs), search, featured cards with banner, icon, verified mark, description, online and member counts | — | O `discover` | Ins | Discover communities (Android), server `GET /v1/communities` | KEEP | COM-04 |
| NAV-CTRL-005 | Title bar | Inbox | Popover with tabs Unreads, Mentions, Bookmarks, Reminders; header buttons (mark all read); Unreads groups unread messages per channel with mute, mark read, collapse per group | read state | O `inbox` | Ins | Activity tab (mentions, replies) and Later (saved, reminders): `LaterScreen.kt`, Activity screens. No "unreads by channel" list | IMPROVE | B5 (NAV-04: unreads view) |
| NAV-CTRL-006 | Title bar | Back / Forward | Navigation history | — | O | Ins | System back (Android) | KEEP | NAV-10 |
| NAV-CTRL-007 | DM sidebar top | "Find or start a conversation" | Quick switcher search over people, servers, channels | — | O | Part | Search screen (`SearchScreen.kt`) | KEEP | NAV-06 |
| NAV-CTRL-008 | DM sidebar | Friends, Nitro, Shop, Quests | Friends page; the rest are paid features or promotions | — | O | Ins | No paid tiers by design | EXCLUDE (Nitro, Shop, Quests: Meldiq has no paid tiers) | — |
| NAV-CTRL-009 | User panel (bottom left) | Avatar / name | Profile popover: banner, avatar, display name, username, badges, bio, View Full Bio, Edit Profile, status ›, Badges, custom status, Switch Accounts ›, Copy User ID | status | O `own-profile-popover` | Ins | You tab: profile, status (`YouScreen.kt`) | KEEP | NAV-07/08 |
| NAV-CTRL-010 | Profile popover › status | Online / Idle › / Do Not Disturb › ("You will not receive desktop notifications") / Invisible › ("You will appear offline"); › opens durations | yes | O `status-menu` | Ins | Status text and emoji with expiry, "pause notifications until" (`users.notifications_paused_until`). No idle or invisible choice | IMPROVE | B6 (NAV-08: presence choice) |
| NAV-CTRL-011 | User panel | Mic, Headphones (deafen), ⌄ Input Options, ⌄ Output Options, Settings gear | Toggle mute/deafen anywhere; device menus | yes | O | Part (device menus not opened) | In-call controls only (`CallControls.kt`) | ADAPT (no always-on voice bar on phones) | B10 |

## 4. Community, member view

| ID | Where | Control | Activation → result | Persists | Ev | Insp. | Meldiq today | Meldiq | Batch / feature |
|---|---|---|---|---|---|---|---|---|---|
| COMM-CTRL-001 | Server header | Name ⌄ (member) | Menu: Server Boost, Invite to Server, App Directory, Show All Channels ☐, Notification Settings, Privacy Settings, Edit Per-server Profile, Hide Muted Channels ☐, Leave Server, Copy Server ID | — | O `member-server-menu` | Ins | Community sheet (`CommunityMenuSheet` in `CommunityMenus.kt`) | KEEP | COM-13 |
| COMM-CTRL-002 | Header menu | Notification Settings dialog | <ul><li>Mute server switch;</li><li>All Messages / Only @mentions / Nothing (with a note that big servers don't push all messages);</li><li>Suppress @everyone and @here;</li><li>Suppress All Role @mentions;</li><li>Suppress Highlights;</li><li>Mute New Events;</li><li>Mobile Push Notifications;</li><li>per-channel override table (channel or category × All / Mentions / Nothing / Mute), added from a picker;</li><li>Done</li></ul> | yes | O `server-notifications`, `-2` | Ins | Mute all and per-channel levels. No "suppress @everyone", no override table | IMPROVE | B2 (COM-13) |
| COMM-CTRL-003 | Sidebar top | "Get Started n of 5" progress bar | Opens the Server Guide | — | O `server-guide` | Ins | — | BUILD | B8 (COM-17 new) |
| COMM-CTRL-004 | Server Guide | Banner, icon, name, Invite; welcome message from the owner mentioning you; "Get Started" tasks, each tied to a channel, ticked when visited; "Read the rules"; Resources cards (channel posts) | Tap a task → its channel; the visit ticks it | read state | O `server-guide`, `-2` | Ins | `#welcome` channel with welcome actions (COM-06) | BUILD (guide with tasks) | B8 (COM-17) |
| COMM-CTRL-005 | Channel above composer | "Next: \<task\>" banner and task prompt above the composer ("Tell us what you're working on…", "Send a message") | Leads to the next guide task | — | O `forum-list`, `message-hover-toolbar` | Ins | — | BUILD | B8 (COM-17) |
| COMM-CTRL-006 | Channels & Roles › Customize | Customization Questions: a required multi-select dropdown ("What languages do you speak?*") and a single question with option cards (emoji + label, 2 columns, check on the chosen one) | Answers grant roles and channels | yes | O `channels-roles-customize` | Ins (member side) | — | BUILD | B8 (COM-07) |
| COMM-CTRL-007 | Channels & Roles › Browse Channels (count) | Search; tip card; channels grouped by category with a show checkbox each, activity ("Active 2 minutes ago", "72 Active Posts"), and Follow Category per category | Hides channels from your list without leaving | yes | O `browse-channels` | Ins | Leave or mute a channel. No hide-without-leaving, no channel browser | BUILD | B3 (CH-17 new) |
| COMM-CTRL-008 | Channel list | "Show All" button when channels are hidden | Brings hidden channels back | yes | O (Coding for Marketers server) | Ins | — | BUILD with CH-17 | B3 |
| COMM-CTRL-009 | Member list (right) | Groups by hoisted role ("Core Author — 1", "Moderator — 1", "Online — 3,960"); rows with status, activity, role badges | Click → profile popover | — | O `forum-list` | Ins | Member list, no role grouping (COM-09 P3) | IMPROVE | B4 |
| COMM-CTRL-010 | Member popover | Banner, avatar, name, username, server tag, "1 Mutual Server", bio, role chips, "Message @user" quick field, add friend, ⋯ | Typing sends a DM | DM | O `member-profile-popover` | Ins | Profile sheet with Message button | KEEP (no quick-message field: a sheet suits a phone) | NAV-07 |
| COMM-CTRL-011 | Header menu | Privacy Settings (per server) | DMs from members of this server, message requests | yes | O (entry), O `dm-permissions` (settings form) | Part | — | BUILD | B6 (DM-03) |
| COMM-CTRL-012 | Header menu | Edit Per-server Profile | Nickname, avatar per server | yes | O (entry only) | Part | Member titles (`members.titles`) | DEFER (P3; title covers the common need) | — |
| COMM-CTRL-013 | Header menu | Leave Server | Confirmation | yes | O (entry) | Part | Leave community (COM-10, phone-tested) | KEEP | — |

## 5. Channels and categories

| ID | Where | Control | Activation → result | Persists | Ev | Insp. | Meldiq today | Meldiq | Batch / feature |
|---|---|---|---|---|---|---|---|---|---|
| CHAN-CTRL-001 | Text channel, right-click (member) | Mark As Read, Add to Favorites, Invite to Channel, Pin Channel to Top, Remove from Channel List, Copy Link, Mute Channel ›, Notification Settings › (shows current), Copy Channel ID | — | yes | O `channel-menu` | Ins | Channel long-press: Mark read, Favourite, Mute, Copy name, Edit, Permissions, Move, Archive, Delete (`CommunityMenuRules.kt`) | IMPROVE | B3 |
| CHAN-CTRL-002 | Channel menu › Mute Channel | For 15 Minutes, 1 Hour, 3 Hours, 8 Hours, 24 Hours, Until I turn it back on | Mute ends by itself | yes | O `channel-mute-durations` | Ins | Mute is open-ended only | BUILD (timed mute, channel, category, community, DM) | B3 (COM-13) |
| CHAN-CTRL-003 | Channel menu › Notification Settings | Use Category Default (shows it) / All Messages / Only @mentions / Nothing | — | yes | O `channel-notification-level` | Ins | Per-channel level (`ChatActionsSheet.kt` `r3_notify_*`). No "category default" | IMPROVE | B3 |
| CHAN-CTRL-004 | Channel menu | Pin Channel to Top | Channel shows above categories | yes | O | Part | Favourites section | KEEP (Favourite) | — |
| CHAN-CTRL-005 | Channel menu | Remove from Channel List | Hides it (see COMM-CTRL-007) | yes | O | Part | — | BUILD | B3 (CH-17) |
| CHAN-CTRL-006 | Channel menu | Copy Link | A link to the channel | — | O | Part | Copy name | ADAPT (`https://meldiq.app/c/<community>/<channel>` once web links exist, `MELDIQ_COMMUNITY_INVITATION_SPEC.md`) | B4 |
| CHAN-CTRL-007 | Channel row hover | "Invite to Channel" icon; on voice channels also "Open Chat" | — | — | O `voice-row-hover` | Ins | Invite lives in the community sheet | KEEP | — |
| CHAN-CTRL-008 | Voice channel, right-click | Mark As Read, Add to Favorites, Invite to Voice, Pin Channel to Top, Unfollow Channel Category, Copy Link, Open Chat, Hide Names ☐, Mute Channel ›, Copy Channel ID | — | yes | O `voice-channel-menu` | Ins | Voice channel menu (CH-12). Text chat in voice: check | IMPROVE (Open Chat in voice) | B10 |
| CHAN-CTRL-009 | Voice channel click | Joins voice at once (no preview) | Live presence to others | yes | U | Blk (would join a real voice channel) | Tap shows the room, then Join (CALL-03) | KEEP | — |
| CAT-CTRL-001 | Category right-click (member) | Mark As Read, Add to Channel List, Collapse Category ☐, Collapse All Categories, Mute Category ›, Notification Settings ›, Copy Category ID | — | yes | O `category-menu` | Ins | Category long-press: Mark read, Collapse/Expand, Collapse all, Mute, New channel, Rename, Permissions, Move, Delete | KEEP (plus timed mute, B3) | CH-02 |
| CHAN-CTRL-010 | Owner header menu | Create Channel, Create Category, Create Event, Active Threads | Opens each form | — | O `owner-server-menu` (+ O1 `03`, `04`) | Ins | Same entries in the community sheet | KEEP | CH-01/03 |
| CHAN-CTRL-011 | Channel header | Threads (list popover: search by name, Create; empty state "There are no threads." + Create Thread) | — | — | O `threads-empty` | Ins | Threads open from a message (MSG-05). No per-channel thread list | BUILD (threads list sheet) | B5 (MSG-05) |
| CHAN-CTRL-012 | Channel header | Notification bell, Pinned Messages popover (full messages with embeds; "Message could not be loaded" for missing replies), Member List toggle, Search | — | — | O `pins` | Ins | Pins sheet (round 7), members, search | KEEP | MSG-07 |
| CHAN-CTRL-013 | Forum post / channel footer | "Slowmode is enabled." indicator | Posting waits | — | O `forum-post-panel`, `forum-new-post` | Ins | — | BUILD | B3 (CH-14) |

## 6. Messages and composer

| ID | Where | Control | Activation → result | Persists | Ev | Insp. | Meldiq today | Meldiq | Batch / feature |
|---|---|---|---|---|---|---|---|---|---|
| CHAT-MSG-001 | Message hover | Toolbar: 3 quick reactions (recent), Add Reaction, Reply, Forward, More (⋯) | — | — | O `message-hover-toolbar` | Ins | Long-press sheet with quick reactions and More reactions (`ConversationScreen.kt`) | KEEP (touch: long-press) | MSG-06 |
| CHAT-MSG-002 | Message right-click (someone else's, member) | Quick reactions row (4) + Add Reaction ›, Reply, Forward, Create Thread, Copy Text, Bookmark Message, Create Reminder ›, Apps ›, Mark Unread, Copy Message Link, Speak Message, Report Message (red), Copy Message ID | — | — | O `message-menu` | Ins | Reply, Reply in thread, Translate, Copy, Edit (own), Pin, Mark unread, Select, Save / Remove saved, Remind me, Save to Gallery / Files, Share, Forward, Retry, Info (own), Delete (own) | see rows below | — |
| CHAT-MSG-003 | Message menu | Report Message | Report flow (§6.1) | yes | O `report-*` | Ins (up to Submit) | — | BUILD | B4 (MOD-07) |
| CHAT-MSG-004 | Message menu | Copy Message Link | Link to the message | — | O | Part | — | ADAPT (in-app link `meldiq://m/<conv>/<msg>`, opens only for members; B5) | B5 |
| CHAT-MSG-005 | Message menu | Speak Message | Text-to-speech | — | O | Part | — | DEFER (P3: Android TalkBack already reads messages) | — |
| CHAT-MSG-006 | Message menu | Apps › | Bot and app actions | — | O | Part | Connectors retired | EXCLUDE (connectors were removed by the owner's decision) | — |
| CHAT-MSG-007 | Message menu | Bookmark Message, Create Reminder › | Saved to Inbox › Bookmarks / Reminders | yes | O | Part | Save for later, Remind me (MSG-15) | KEEP | — |
| CHAT-MSG-008 | Message menu | Pin, Edit, Delete | Own messages and moderators. Not in this member's menu for someone else's message | yes | O (absence), O1 `15` | Ins | Pin, Edit, Delete (MSG-02/03/07). **Pin and media menu labels are hard-coded English** in `ConversationScreen.kt` ("Unpin", "Pin for everyone", "Save to Gallery", "Save to Files", "Share…") | FIX (translations) | B5 |
| CHAT-MSG-009 | Message menu | Copy Message ID, Copy Server/Channel/User ID | Developer IDs | — | O | Ins | — | EXCLUDE (developer mode, not useful to Meldiq users) | — |
| CHAT-MSG-010 | Above timeline | "N new messages since 5:14 PM" bar with Mark As Read | Jumps or clears | read | O | Ins | New line and jump (MSG-11) | KEEP | — |
| CHAT-COMP-001 | Composer "+" | Upload a File, Create Thread, Create Poll, Use Apps | — | — | O `composer-plus-menu` | Ins | Attach panel: Gallery, Camera, Record video, Files, Contact, Location, Poll (`AttachPanel.kt`) | KEEP | — |
| CHAT-COMP-002 | Composer right | Send a gift, GIF, Stickers, Emoji, Apps, Send | — | — | O | Ins | Emoji, stickers, voice, send | KEEP; GIF EXCLUDE (third-party search would see what people type; EXP-06) | — |
| CHAT-COMP-003 | Composer (no permission) | Placeholder "You do not have permission to send messages in this channel." | — | — | O | Ins | Read-only line with reason (design system) | KEEP | — |

### 6.1 Report a message (CHAT-MSG-003, walked to the last step, not submitted)

| Step | What Discord shows | Ev |
|---|---|---|
| 1 | "Report Message": "Please select the option that best describes the problem."; the selected message (avatar, name, time, text); six choices with a chevron: I don't like it, Spam, Abuse or harassment, Harmful misinformation or glorifying violence, Exposing private identifying information, Something else | O `report-reasons`, `-2` |
| 2 (after Spam) | "Report Summary": "Review your report before submitting."; a good-faith statement linking the Community Guidelines; the selected message; Report Category: Spam; Back and **Submit Report** (red) | O `report-summary` |
| After closing | A satisfaction survey (three faces, "Don't show again") | O `report-feedback` |

Meldiq's version is specified in `MELDIQ_FULLSTACK_SPEC.md` §2.2. Two changes come from this observation:
1. **A review step before sending.** Show what will be shared and with whom (the community's moderators or Meldiq),
   with a plain consent line, because Meldiq's reporter discloses decrypted content.
2. **The reason list.** Spam, Harassment, Violence or harmful misinformation, Private information, Illegal content
   (platform), Something else.

Meldiq won't have "I don't like it" as a reason. Instead, a "Don't want to see this?" link offers Block or Mute. There
will be no survey.

## 7. Forums, polls, events, expressions

| ID | Where | Control | Activation → result | Persists | Ev | Insp. | Meldiq today | Meldiq | Batch / feature |
|---|---|---|---|---|---|---|---|---|---|
| FORUM-CTRL-001 | Forum header | "Search or create a post…" field, media-view toggle, New Post | — | — | O `forum-list` | Ins | Search posts, New post (`Forum.kt`) | KEEP | CH-11 |
| FORUM-CTRL-002 | Forum | Sort & View: Sort by Recently Active / Date Posted; View as List / Gallery; Tag Matching Match Some / Match All; Reset to default | — | per user | O `forum-sort-view` | Ins | Sort latest / newest; tag filter matches any (`ForumRules.filter`) | IMPROVE (Match All, P3 gallery) | B8 |
| FORUM-CTRL-003 | Forum | Tag chips filter row, "All ⌄" tags menu | Filters | — | O | Ins | Tag chips, All tags | KEEP | — |
| FORUM-CTRL-004 | Post card | Tag, title, author + first line, thumbnail, top reaction + count, reply count, last activity | Click → post panel | — | O `forum-list` | Ins | Title, preview, reply count (`fm_replies`). No top reaction or thumbnail | IMPROVE | B8 |
| FORUM-CTRL-005 | Post panel | Title, ⋯, close; reaction bar; Follow; copy link; scroll to top; composer "Send a message in …"; slowmode note | — | Follow: yes | O `forum-post-panel` | Ins | Post opens as a thread screen | IMPROVE (Follow post) | B8 |
| FORUM-CTRL-006 | New Post (inline) | Title, message, image tile, tag chips + "See more tags", emoji, slowmode note, Post (disabled until valid); Post Guidelines card (collapsible, View All) | — | on Post | O `forum-new-post` | Ins (not posted) | Title, body, tags (`Forum.kt`). No guidelines | BUILD (guidelines text set by managers, shown on New post) | B8 |
| POLL-CTRL-001 | Composer + › Create Poll | Question (0/300), answers with emoji button and delete, Add another answer, Duration (24 hours ⌄), Allow Multiple Answers ☐, Post | — | on Post | O `create-poll` | Ins (not posted) | Polls with multi choice (`Models.kt` `Poll`), closed by hand (`poll_close`). No duration, no emoji per answer | IMPROVE (duration that closes itself; answer emoji) | B8 (MSG-12) |
| EVENT-CTRL-001 | Owner menu › Create Event | Three steps (Location, Event Info, Review). Location: Voice Channel ("voice, video, screenshare, and Go Live") or Somewhere Else (location text, required) | — | on Create | O `event-location`, `-2` | Ins | Meetings with RSVP in voice rooms (MSG-13) | KEEP | — |
| EVENT-CTRL-002 | Event Info | Topic*, Start date/time*, End date/time* (required for Somewhere Else), Event Frequency* (Does not repeat, Weekly on Wednesday, Every other Wednesday, Monthly on the third Wednesday, Annually on Oct 21), Description (Markdown), Cover Image | — | on Create | O `event-info`, `event-frequency` | Ins (cancelled before Review) | Events without recurrence | BUILD (recurrence rules as listed) | B8 (MSG-13) |
| EXPR-CTRL-001 | Emoji picker | Tabs GIFs / Stickers / Emoji; search "Find the perfect emoji"; skin tone; Add Emoji (for those allowed); left rail: Frequently Used, each server's custom emoji, then categories; locked premium emoji; footer shows the hovered emoji and its `:name:` | Click inserts | recent | O `emoji-picker` | Ins | Full Unicode picker with search, categories, recent, tone (`EmojiPanel.kt`, 2026-10-21) | IMPROVE (name of the pressed emoji; custom sections with EXP-04) | B9 |
| EXPR-CTRL-002 | Server Settings › Emoji | "Upload Emoji"; drag to upload; 50 slots; table Image, Name (`:name:`), Uploaded By | — | yes | O `emoji-settings` | Ins (view) | — | BUILD | B9 (EXP-04) |

## 8. Creation, settings and administration

| ID | Where | Control | Activation → result | Persists | Ev | Insp. | Meldiq today | Meldiq | Batch / feature |
|---|---|---|---|---|---|---|---|---|---|
| CREATE-CTRL-001 | Rail + › Create Your Server | Create My Own; "Start from a template": Gaming, Friends, Study Group, School Club…; "Have an invite already?" Join a Server | — | — | O `create-server` | Ins | Create community with starter channels; join with code | IMPROVE (templates: starter channel sets) | B2 (COM-01) |
| CREATE-CTRL-002 | Step 2 | "Tell Us More": For me and my friends / For a club or community / skip | — | — | O `create-server-audience` | Ins | — | ADAPT (maps to Meldiq's visibility choice) | B2 |
| CREATE-CTRL-003 | Step 3 | Customize Your Server: icon Upload, Server Name* (prefilled "\<name\>'s server"), guidelines line, Back, Create | Creates | yes | O `create-server-customize` | Ins (not created) | Name, icon, description (COM-02) | KEEP | — |
| SET-CTRL-001 | User Settings nav | Search; Account (Account Info, Password & Security, Account Status, Family Center); Data & Privacy; Messaging Permissions (Content Filters, Spam Filters, Direct Messages, Friend Requests, Connected Games, Ignore & Block); Notifications; Billing group; Experience (Voice & Video, Appearance, Accessibility, System, Language & Time); Games & Apps; Developer; Log Out | — | — | O `settings-nav`, `-2`, `ignore-block` | Ins | Settings (`SettingsScreens.kt`), grouped | KEEP | NAV-09 |
| SET-CTRL-002 | Account | Username, Email, Phone with masked values, Reveal and Edit; Password Edit; Multi-Factor Authentication "Set up"; Logged-in Devices (count) | — | yes | O `settings-account` | Ins (no Reveal, no edit) | Account, sessions (AUTH-06). No 2FA | BUILD 2FA | B1-follow-up (AUTH-05) |
| SET-CTRL-003 | Accessibility | Live preview; Text Readability (chat text size slider 12–24 px, Always underline links, Display Name Styles); Visual Density; Color & Contrast; Reduced Motion; Audio & Screen Reader | — | yes | O `accessibility` | Ins (first section) | Message text size (`chat_text_size`), reduced motion respected | KEEP (underline links: P3) | — |
| SET-CTRL-004 | Messaging Permissions | Content Filters (sensitive media per DMs from friends / others / server channels), age-restricted toggles | — | yes | O `content-filters` | Ins | — | EXCLUDE on the server (encrypted media can't be scanned); on-device option P3 (MOD-11) | — |
| SET-CTRL-005 | Messaging Permissions | Spam Filters: Filter all / non-friends (Recommended) / Don't filter → "Spam inbox" | — | yes | O `spam-filter`, `-2` | Ins | — | ADAPT (message requests do this without reading content) | B6 (DM-03) |
| SET-CTRL-006 | Messaging Permissions | DM Permissions per server ("All servers" ⌄): Direct Messages "Allow DMs from other server members"; Message requests "Filter messages from server members you may not know" | — | yes | O `dm-permissions` | Ins | Anyone can open a DM with anyone (`POST /v1/conversations/dm` has no consent or block check before creating) | BUILD | B6 (DM-03) |
| SET-CTRL-007 | Voice & Video | Voice: mic and speaker selects, volumes, Mic Test, Input Profile (Voice Isolation / Studio / Custom), automatic input sensitivity; Camera; Streaming (Show Stream Previews, advanced stream settings); Sounds (deafen, mute… with preview); Soundboard; Advanced | — | yes | O `voice-video`, `voice-input-profile`, `streaming-sounds`, `camera-settings` | Ins | Audio route picker in calls; processing fixed in code (CALL-08) | see `MELDIQ_CALLING_SCREENSHARE_AUDIT.md` | B10/B11 |
| ADMIN-CTRL-001 | Owner header menu | Server Boost, Invite, Server Settings, Create Channel, Create Category, Create Event, Active Threads, App Directory, Notification Settings, Privacy Settings, Per-server Profile, Hide Muted Channels, Copy ID (no Leave for the owner) | — | — | O `owner-server-menu` | Ins | Community sheet (owner) | KEEP | — |
| ADMIN-CTRL-002 | Server Settings › Safety Setup | Show Members in Channel List; Verification Level (None… Change); Require 2FA for moderator actions (needs 2FA on the owner's own account first); Restrict member prune to admins; Sensitive content filters; Activity Alerts (unusual DM or join activity) with a Safety Notifications Channel | — | yes | O `safety-setup`, `-2`, `-3` | Ins (view) | Rate limits (MOD-10), no alerts to moderators | BUILD (join-raid alert to moderators, MOD-10; 2FA for moderators after AUTH-05) | B4 |
| ADMIN-CTRL-003 | Server Settings › AutoMod | Rules: Block Mention Spam, Block Suspected Spam Content, Block Commonly Flagged Words, Block Custom Words (Create). Rule form: name, enable switch, limit (unique mentions per message, 20, − +), responses (Block message with custom text / Send alert to a channel / Timeout member), exempt roles or channels; bottom bar "Currently Editing … Rule" Cancel / Save Changes | — | on Save | O `automod`, `automod-rule`, `-2`, `automod-cancelled` | Ins (cancelled) | — | ADAPT: mention-spam limit enforced by the server from metadata, word lists on devices (spec §6) | B4 (MOD-08) |
| ADMIN-CTRL-004 | Server Settings › Audit Log | Filter by User, Filter by Action; entries "user did X to #channel" with date and expandable details | — | — | O `audit-log` | Ins | Audit log (MOD-09). Filters: check | IMPROVE (filters) | B4 |
| ADMIN-CTRL-005 | Server Settings › Bans | "Bans are by account and IP…"; search by user ID or username; list | Click → unban | yes | O `bans` | Ins (view) | Bans list and unban (MOD-05) | KEEP (search: P3) | — |
| ADMIN-CTRL-006 | Server Settings › Invites | "No active invite links"; Pause Invites; Create Invite Link | — | yes | O `server-invites` | Ins (view) | `MELDIQ_COMMUNITY_INVITATION_SPEC.md` | — | B4 |
| ADMIN-CTRL-007 | Pause Invites › Security Actions | Duration ⌄ (2 hours…), Pause Invites (stop joins by invite), Pause DMs (stop new DMs between members; friends and moderators still can), Cancel / Save | — | on Save | O `security-actions` | Ins (cancelled) | Pausing invites P3 (COM-05) | BUILD | B4 |
| ADMIN-CTRL-008 | Server Settings › Access | Invite Only / Apply to Join / Discoverable (needs Community); Age-Restricted; Join Application: questions (1/5, "Short Answer"), Add a question, example questions, Preview; Apply from Profile | — | yes | O `access`, `join-application` | Ins (view) | Invite only / public with invites / public and open (COM-03). No join applications | BUILD (apply to join with questions, moderator approval) | B4 (COM-18 new) |
| ADMIN-CTRL-009 | Server Settings › Enable Community | Landing: "Are you building a Community?", Enable Community, benefits | — | on Enable | O `enable-community` | Ins (not enabled) | Every Meldiq community has community features | EXCLUDE (no split between server kinds) | — |

## 9. Batches that follow from this inspection

The owner's batch order is kept (`MELDIQ_IMPLEMENTATION_PLAN.md` "Batches"). Every batch ends with the APK and a
phone test, and the next one starts only after the owner approves. In short:

- **B1, sign-in:** done, awaiting the phone test.
- **B2, community navigation:**
  - notification dialog (suppress @everyone, override list);
  - rail order;
  - creation templates.
- **B3, channels:**
  - timed mute;
  - category-default level;
  - hide channels and channel browser;
  - slowmode.
- **B4, administration:**
  - reports;
  - pause invites;
  - join applications;
  - raid alerts;
  - audit log filters;
  - invitation links.
- **B5, chat:**
  - threads list;
  - message link;
  - menu translations.
- **B6, DMs and contacts:**
  - message requests;
  - DM permissions;
  - connections;
  - presence choice.
- **B7, media and files:** unchanged from the earlier plan.
- **B8, forums, polls, events, onboarding:**
  - forum guidelines, Match All, follow;
  - poll duration;
  - event recurrence;
  - Server Guide;
  - onboarding questions.
- **B9, expressions:** custom emoji, emoji name preview.
- **B10 to B11, calls:** see the calling audit.

## 10. Not inspected, and why

- **Discord mobile apps:** no mobile session. Mobile behaviour comes from help articles (round 1).
- **Anything that sends, joins or creates:** sending a message or reaction, joining voice, calling, screen sharing,
  creating an invite, server, channel, event or thread, submitting a report, friend-request actions, blocking or
  removing a friend. These change the owner's real account or reach real people. They need a test server and a second
  test account the owner sets up, plus the owner's go-ahead.
- **Community-only administration:** onboarding editor, Welcome Screen editor, Server Insights, Rules Screening.
  Enabling Community on the owner's server would change it. REA's member-side onboarding was observed instead
  (COMM-CTRL-006).
- **Device menus:** the user panel's input and output device menus (opened a tooltip only).
- **Roles, channel permissions and the Create Channel dialog:** observed in round 1 (`O1 03–11`), not repeated.
