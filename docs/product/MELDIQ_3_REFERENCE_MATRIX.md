# Meldiq round 3: reference-to-implementation matrix (2026-10-21)

Branch `claude/meldiq-3-reference-rebuild` in this repository and in meldiq-web, cut from the round 2 tips (meldiq
`cb63510`, web `a54ff5c`). **Not pushed, not merged, not deployed.**

## Source and limits

- **Recording.** The owner's `IMG_1141.MP4`: 4 min 23 s, an iPhone recording. Slack mobile runs from 0:00 to about
  2:50, Discord mobile from about 2:50 to the end.
- **The file has gone.** It is no longer in `~/Desktop/video_file` (moved or deleted after round 2). This matrix is
  built from the 88 frames saved in round 2, one every 3 s. So:
  - timestamps are those frames' times, ±3 s;
  - nothing between frames (motion, durations, transitions) was seen.
- **Not in the frames.** A voice channel or huddle in use, desktop layouts, search results with content, a Discord
  thread, member lists with many people.
- **Statuses** below: verified (tested on the final source), implemented (device check pending), partial, existing
  (already in Meldiq, no change needed), deferred, not applicable, blocked.

## Matrix

| # | Seen at | Reference shows | Meldiq before round 3 | Round 3 | Tests | Status |
|---|---|---|---|---|---|---|
| 1 | 0:00–0:45 Slack | Bottom tabs Home / DMs / Activity / More / Search | Home / DMs / Activity; search in the header; settings from the avatar | Kept: More and Search tabs would add a step for what the header already does | n/a | existing |
| 2 | 0:00 Slack | Home quick cards (Slackbot, Huddles, To-dos, Saved) above collapsible Channels / DMs / Apps | Threads, Later, Reminders, Drafts & sent, Calls cards; collapsible sections | Kept | existing | existing |
| 3 | 0:03–0:06 Slack | Workspace switcher sheet: list, Add a workspace, Preferences, Help | Sheet on Android; menu on web | Android Home also gets a one-tap rail (round 2) | round 2 | existing |
| 4 | 0:09–0:24 Slack | Channel header: name, member count, tabs, huddle button; empty channel "Welcome!" with **Add description / Add people** | Web: "Welcome to #channel", no actions; Android: a generic "Say hello" | **Both:** empty channels show the welcome; managers get Add description / Add people (open the channel's details) | browser-checked (web); Android compiled | verified (web); implemented (Android) |
| 5 | 0:21 Slack | Members list with search, skeleton while loading | Member lists with search in channel info | Kept | existing | existing |
| 6 | 0:27–0:33 Slack | Channel notifications: All new posts / Just mentions / Mute | Web: All / Mentions only / Off. Android: a mute toggle only, stored as "mentions" (a cross-platform mismatch) | **Android:** the same three levels and rule as the web (docs/MENTIONS.md); mute now stored as "none" | `StatusRulesTest` levels cases | verified |
| 7 | 0:30 Slack | Channel details: description, member strip, Settings (notifications, mute, member suggestions, convert to private) | Channel info with members, notification level (web), leave | Convert to private: deferred (channel access changes need their own review) | n/a | partial |
| 8 | 0:24 Slack | Agents & Apps list with Add | App directory | Curated in round 2 (Recommended / More apps) | round 2 | existing |
| 9 | 0:42–0:45 Slack | Composer "+" sheet: photos strip, upload a file, apps, recent files | Attachment picker, files, stickers, polls | Kept | existing | existing |
| 10 | 0:39–0:48 Slack | Slackbot AI prompts | AI, off by default per workspace (docs/AI.md) | Kept off | existing | existing |
| 11 | 0:57–1:00 Slack | DMs: recent people strip, chips All / Unreads / External, voice-message previews | Android: All / Unread / Direct / Groups chips; web: list | Kept; "External" is federation, already labelled | existing | existing |
| 12 | 1:00–1:09 Slack | DM: voice message with speed, **"Rizve has paused their notifications"**, thread "Reply in Thread" | Voice messages, threads | **New:** the notice in a DM, when the other person paused notifications and their last-seen setting lets you see it | server `status_presence.test.ts`; web live test; browser | verified (server, web); implemented (Android) |
| 13 | 1:12 Slack | Activity: chips **All / DMs / Mentions / Threads**, Unreads toggle | Android: sections without chips; web: sections | **Both:** All / Mentions / Threads / Calls chips (DMs are their own tab in Meldiq; Calls added because Activity holds missed calls) | `StatusRulesTest`, `test/status.test.ts`; browser | verified |
| 14 | 1:15 Slack | More: Files, Assigned to you, External connections | Files per conversation (info › files); no global file browser | A global file browser needs a local index of end-to-end encrypted files on each device | n/a | deferred |
| 15 | 1:21 Slack | Search: recently visited places | Universal search with recent searches | Kept | existing | existing |
| 16 | 1:24–1:36 Slack | Profile popover: **What's your status? / Set yourself as away / Notifications paused until …** | Status text only, no presets, no end time; no pause | **New on all three:** statuses with emoji, suggestions and "clear after"; pausing notifications for 30 min / 1 h / 2 h / until tomorrow 9:00, on every device. "Set yourself as away" not added: presence is automatic | server tests; Android `StatusRulesTest`; web tests and browser | verified (server, web); implemented (Android) |
| 17 | 1:39–1:42 Slack | Set a Status: In a meeting 1 h, Commuting 30 min, Out sick today, Vacationing don't clear, Working remotely today; Clear after | none | The same five suggestions and lengths on both clients | tested on both | verified |
| 18 | 1:45 Slack | Profile: photo, name, Active, local time, Set a Status, Edit Profile, contact | Profile screens with privacy per field | Kept; local time not added (needs a time zone on profiles) | n/a | existing / deferred |
| 19 | 1:48–2:30 Slack | Preferences: notifications (keywords, what shows in Activity), appearance (color mode incl. True Dark, skin tone, message display), accessibility (animation, screen reader, swipe actions), language and region (24-hour clock), privacy, debug and reset cache | Notifications, appearance presets and themes, language, privacy, storage, calls | Keywords, skin tone, swipe configuration, 24-hour clock: deferred, low value against risk this round | n/a | deferred |
| 20 | 2:12 Slack | Swipe actions configurable per list | Fixed swipe actions | Deferred | n/a | deferred |
| 21 | 2:42–2:48 Slack | Emoji picker with search and categories; composer toolbar (Aa, emoji, @, /) | Emoji and sticker pickers, mentions, formatting | Kept | existing | existing |
| 22 | 2:51–3:00 Discord | Server rail with unread markers; Messages with Add Friends and a friends strip | Web rail; Android rail on Home (round 2) | Kept; no "friends" model (contacts exist) | round 2 | existing |
| 23 | 2:54 Discord | User card at the bottom with status; profile with bio, member since, connections, note | Profile with bio (status), photo, privacy | Status now carries emoji and end time (16) | as 16 | verified |
| 24 | 3:00–3:06 Discord | Server channel list with categories, Search, Members, Events; channel header **"2 Online"** | Sections and voice rooms; header "N members" | **New:** "N online" in channel headers, counting only people whose online state you may see | server test; web browser ("1 online"); Android compiled | verified (server, web); implemented (Android) |
| 25 | 3:09–3:24 Discord | Chat with role badges, attachments, link previews; Emoji / GIFs / Stickers tabs | Messages, roles, attachments, previews, stickers; no GIF provider | GIFs need a third-party provider: deferred | n/a | existing / deferred |
| 26 | 3:30–3:36 Discord | Server menu: Invite, Notifications, Settings, **Mark As Read**, Create Channel / Category / Event, Hide Muted Channels, Allow DMs, Report Raid | Invite, create channel, settings, sections | **New on both:** Mark all as read (this workspace's unread channels). Hide muted, report raid, events: deferred | `WorkspaceHomeRulesTest`, `test/status.test.ts`; browser (absent with nothing unread) | verified |
| 27 | 3:39–3:57 Discord | Server Settings: Overview, Channels, Integrations, Template, Emoji, Stickers, Members, Roles, Invites, Moderation, AutoMod, Audit Log, Bans, Security, Enable Community | Overview, channels, people, invitations, roles, apps, audit log, templates; communities (round 2) | Grouped menu (round 2). AutoMod and bans: deferred, they need moderation policy | round 2 | existing / deferred |
| 28 | 4:06–4:12 Discord | Channel long-press: Invite, Pin to top, Copy link, Mark as read, Mute, Notification settings, Edit, Duplicate, Copy ID | Star (pin), sections, mute, read, archive, info | **Android:** the three notification levels in the sheet. Copy link: deferred (Android can't open conversation links yet, so a link would only work on the web) | `StatusRulesTest` | verified (levels) / deferred (link) |

## What changed, by layer

- **Server** (`e60edb1`):
  - migration 030: `users.status_emoji`, `status_expires_at`, `notifications_paused_until`, all additive;
  - `PATCH /v1/users/me` takes `statusEmoji`, `statusExpiresAt` (a future time, at most a year away) and
    `notificationsPausedUntil` (at most 30 days);
  - every view applies the end time, so an expired status never shows;
  - members see `notificationsPaused` only if the person's last-seen setting allows it;
  - `GET /v1/conversations/:id/presence` is for members only, counts only visible online state, and allows 60
    requests a minute;
  - account deletion clears the new columns;
  - features `status-expiry`, `notifications-pause`, `conversation-presence`.
- **Android** (`d338cca`, `1e1d71a`):
  - status and pause sheets from the Home "You" sheet;
  - the notifier stays quiet while paused (calls still ring);
  - DM status, and the paused notice;
  - "N online" in channels;
  - Activity chips; Mark all as read in the Home menu; the channel welcome;
  - the three notification levels with the web's rule.
- **Web** (`5975d62`, `d870db2`):
  - status and pause dialogs from the profile menu;
  - no notifications while paused;
  - DM status and the paused notice;
  - "N online";
  - Activity chips with an empty Calls state;
  - Mark all as read in the workspace menu;
  - welcome actions.

## Security and privacy

- The pause and "N online" follow the existing last-seen privacy rule, so neither reveals what a person hides.
- Status visibility follows the "bio" setting, as before.
- No end-to-end encrypted content is involved.
- Nothing new is sent to analytics.

## Decisions for the owner

1. **Chats muted on Android before round 3.** They are stored on the server as "mentions", the old Android mute. Under
   the shared rule (docs/MENTIONS.md) "mentions" lets @everyone through, so these chats will now notify on @everyone.
   Nothing records whether a stored "mentions" came from the old Android mute or from the web's "Mentions only"
   menu, so I made no automatic migration. Options:
   - leave it: people can choose Off in the sheet;
   - run a one-time server migration of all "mentions" to "none". That also changes web users' deliberate
     "Mentions only" choices.
2. **Deferred features that need decisions:**
   - a GIF provider (cost and privacy);
   - AutoMod and bans (moderation policy);
   - public community discovery;
   - convert-to-private (access review).
