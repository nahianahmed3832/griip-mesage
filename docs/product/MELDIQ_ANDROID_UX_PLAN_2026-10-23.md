# Meldiq Android: UX plan from the iPhone Discord walkthrough (2026-10-23)

The owner asked for Android first (2026-10-23), with no further desktop capture for now. This plan compares the 220 iPhone
screens captured today (`research/discord/live-2026-10-23/INDEX.md`, git-ignored because it shows real people) with
the Android app on `internal-beta` (7c71348).

Most Discord *features* already exist on Android (`MELDIQ_FEATURE_GAP_MATRIX.md`: rail, channels, forum, roles,
overwrites, timeout, ban, invites, events, threads, voice). The gaps are mostly in **how screens are organised and
feel**, plus a few small features. Discord is a reference for structure and behaviour only: no copied assets, names or
exact layouts (`MELDIQ_DESIGN_SYSTEM.md`).

Nothing here changes the database, auth, encryption or realtime contracts unless the row says so. Rows that touch a
contract are marked **contract** and need the owner's OK first.

Screen numbers (#nnn) refer to `INDEX.md`.

## P1: do first (small, high impact)

| # | Discord pattern (evidence) | Meldiq Android today | Change | Risk | Verify |
|---|---|---|---|---|---|
| A1 | **Blurred preview at final size** before a photo loads, then swapped in place (#124 → #125, #128) | Space is kept (B16 `w`/`h`) but the placeholder is a flat tile with a download icon and size | Add an optional tiny preview (a ~30-byte ThumbHash) to `AttachmentRef`, inside the encrypted message, and draw it blurred until the picture arrives. Older apps and web ignore it | **contract** (encrypted payload, optional field; the server never sees it) | Unit test the encoding; emulator: open a chat on slow network, no layout jump |
| A2 | **Data Saving Mode** and upload quality (#199, #200) | Auto-download by size only (563810d); no user choice | Settings › Chat: "Data saver" (don't auto-download on mobile data; tap to load) and upload quality Best / Standard / Data saver for photos and videos | Low (local setting) | Unit test the auto-download rule; emulator with metered network |
| A3 | **Channel permissions Basic view**: a Private toggle and a "Who can access" list, with Advanced kept for overrides (#073, #072, #090) | Advanced only (`ChannelPermissionsScreen.kt`: tri-state per subject) | A Basic / Advanced switch at the top. Basic shows Private (on = deny @everyone view) and the roles and members who can see it, add and remove. Same server routes | Low (same `PUT …/overwrites`) | Existing `channel_permissions.test.ts`; new rules test for Basic ↔ overwrites |
| A4 | **"Not synced with category" + Sync now** banner on channel permissions (#072) | Sync exists in the channel menu only (CH-08) | Show the banner at the top of the permissions screen when the channel differs from its category | Low | Unit test the "differs" rule |
| A5 | **Settings index as grouped cards** with the current value on the right (Theme · Ash, Language · English), explainer under each group, red rows last (#166–#169) | You screen uses `ListCard`s; values not shown on rows (to check row by row) | One settings index: Account, Privacy, Content, Devices, Notifications, Appearance, Accessibility, Language, Chat, Voice & video, Storage, Help, Log out; each row shows its value | Low (UI only) | Screenshot check light/dark, 360 dp, large font |
| A6 | **Empty states** with an illustration, one friendly line and the main action (#086, #127, #130, #153, #187) | `ShimmerList` for loading; empty states are mostly text | One `EmptyState` component (icon tile, title, one line, optional action) used on pins, threads, invites, events, activity, search | Low | Screenshot check |
| A7 | **Mute from the channel header bell** with durations and "Notification settings ›" showing the inherited level (#129, #083) | Timed mute exists (CH-17) in menus | Header bell opens the same sheet; the level list says what "Use category default" currently means | Low | Emulator |

## P2: next

| # | Discord pattern (evidence) | Meldiq Android today | Change | Risk | Verify |
|---|---|---|---|---|---|
| B1 | **Member management page**: nickname, roles with Edit, then Timeout / Kick / Ban / Transfer ownership as separate red rows, each opening its own page with reason and scope (#107–#111) | Timeout and ban sheets exist; reached from different places | One "Manage member" page from the member list and profile, using the existing sheets | Low | `workspace_admin.test.ts` unchanged; emulator |
| B2 | **Ban deletes recent messages**: none / 1 h / 6 h / 12 h / 24 h / 3 d / 7 d (#110) | Ban without message clean-up | Add the choice; the server deletes that person's messages in the community for the period (it can, by sender and time, without reading them) | **contract** (ban route gains a field) | Server test |
| B3 | **Prune inactive members** (#114) | Not built | P3 unless the owner wants it | — | — |
| B4 | **Invite link settings**: expiry, max uses, roles given on join, skip join application (#136–#139) | Expiry, uses, pause (COM-05) | Add "Roles given on join" and "Skip join questions" | **contract** (invite create) | Server test for role safety |
| B5 | **Events: 3-step create with a preview card** before publishing (#131–#134) | Meetings with RSVP, one form | Split into Where → What/when → Preview; same route | Low | Emulator |
| B6 | **Voice call: controls auto-hide**, tap to show (#146, #147); stream settings sheet (#149) | Call screen keeps controls visible; share sheet exists (B11) | Auto-hide after a few seconds of no touch while video or a share is on screen; TalkBack keeps them visible | Low | Emulator; TalkBack check |
| B7 | **Chat gestures setting**: swipe right-to-left = reply or details; double-tap to react with a chosen emoji (#202, #203) | Swipe to reply fixed | Settings › Chat: choose the swipe action; double-tap reaction on/off with an emoji | Low | Unit test gesture rule |
| B8 | **Appearance**: theme picker with a live preview, DM list preview All / Unread only / None, time format Auto / 12 h / 24 h (#191–#193) | Palettes exist (`AppearanceSettings.kt`); no preview choice or time format | Add the three | Low | Screenshot check |
| B9 | **Accessibility**: role colour in names / next to names / off, always underline links, contrast slider (#194–#197) | Reduced motion only | Add role-colour display and underline links first; contrast later | Low | Screenshot check |
| B10 | **Server search tabs and filters**: Recent / Members / Channels / Media / Pins / Links, filter chips from / in / has / mentions / before / after (#217, #218) | Search exists (NAV-06): metadata on the server, text on the device | Same tabs and filter chips over what Meldiq can search | Low | Unit test the filter parsing |
| B11 | **Inbox filter sheet**: include role mentions, include @here/@everyone (#212) | Activity with Unreads chip (NAV-04) | Add the two switches | Low | Unit test |
| B12 | **Category settings page** with Category permissions (Basic: Private category + warning when @everyone can't see) (#088–#090) | Category menu items (rename, permissions, delete) | One page using A3's Basic/Advanced screen | Low | Emulator |

## P3: later or only on request

- Account standing page (#172): needs a violations model on the server.
- AutoMod mention-spam rule (#115): the server sees mention counts, so a limit is possible; text rules aren't (encrypted).
- Webhooks (#104): a bot-posting path into encrypted channels needs its own design.
- Per-server profile (#159), profile effects, nameplates, wishlist (#154): cosmetic.
- Developer mode / copy IDs (#209).

## Won't build (by design)

- Nitro, Server Boost, Shop, Quests, Orbs, gifting, soundboard upsells (#160–#165, #185–#187, #219): Meldiq keeps no
  paid tiers or boosts (gap matrix header).
- Community wizard requiring "scan media" (#121): media is end-to-end encrypted.
- GIF search through a third party (EXP-06): would send typing outside Meldiq.

## How the work would run

1. P1 rows one at a time on `architecture-1.0-android`, each with its tests, merged into `internal-beta`. A1 waits for
   the owner's OK because it adds a field to the encrypted message (web keeps working; it ignores the field).
2. Each screen checked on the emulator in light and dark, at 360 dp and with large text. Phone checks are the owner's.
3. P2 after the owner reviews P1 on the phone. B2 and B4 need the owner's OK (server contract changes) and an API
   version bump on deploy.
4. Web follows later under its own plan, through the web session.

## Not covered by this capture

Long-press menus, the in-call controls drawer, mic/camera/screen-share capture and the full role-permission list could
not be driven through iPhone Mirroring. The Android equivalents already exist (`MELDIQ_ANDROID_INTERACTION_MATRIX.md`).

## Status (2026-10-23, owner: "start A2–A7, hold A1", then "include the features from the 220 screens")

| # | Result |
|---|---|
| A1 | Held by the owner |
| A2 | Done: Settings › Chats › Upload quality (photos Standard / HD / Original, videos Original / 1080p / 720p); the send preview starts there. Auto-download (never / Wi-Fi / always) already existed |
| A3 | Done: Basic / Advanced on channel and category permissions. Basic = Private switch, "Who can access" with remove, "Add members or roles", a warning when only admins can see it. Only `channels.view` changes (`OverwriteRules.privacy`, `access`, `whoCanView`, tested) |
| A4 | Already built: "Not synced with … · Sync now" banner |
| A5 | Done: rows show values (Notifications On / Off / Paused / Blocked by Android; Appearance theme · palette; Language) — `SettingsSummary`, `ValueChevron` |
| A6 | Mostly already built (`EmptyState` on 26 screens); the forum's no-match list now uses it with "Clear search and tags" |
| A7 | Done: text channels' header bell — mute 15 min to 24 h or until turned back on, unmute with "Muted until …", and the channel's level with what "default" follows now |
| B1 | Member sheet already had roles, title, timeout, remove, ban, report; actions now go from least to most severe |
| B2 | **Blocked, owner decision**: phones accept a delete only from the message's author, so a moderator can't delete someone else's messages. Allowing it means receivers trusting a moderator's delete (Android, web, and the realtime/encrypted control rules) |
| B3 | Not built (P3) |
| B4 | Already built: invites can give a role, and invite joins don't go through join questions |
| B5 | Done: events in three steps (where → what and when → preview card), Next only when the step is valid; same route |
| B6 | Not built on purpose: UI-003 removed auto-hiding call controls after the owner's report; tap to hide over video stays |
| B7 | Done: Settings › Chats › Gestures — swipe to reply on/off; double-tap to react on/off with 👍 ❤️ ✅ 😂 🔥 🙏 (a second double-tap takes it back) |
| B8 | Done: chat-list message previews All / Unread only / None; time format Automatic / 12-hour / 24-hour (`TimeFmt.hourFormat`) |
| B9 | Partly: links were already underlined. Role colours in names need each person's roles cached on the phone (a local database migration): not done |
| B10 | Done: `from:`, `has:image|file|video|link`, `before:` and `after:` (yyyy-mm-dd) narrow messages on the phone; chips add them (`SearchQuery`, tested) |
| B11 | Not needed: Activity counts mentions per conversation and each community already has "Ignore @everyone"; role mentions aren't built (MSG-10) |
| B12 | Done through A3 (category permissions Basic view and warning) |
