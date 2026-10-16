# Android UI/UX redesign (2026-10-16)

Scope: the existing Kotlin/Compose app (`com.griipbuddy.message`). Backend, API, protocols, crypto, `CallManager` and the
LiveKit lifecycle are unchanged. The brief's reference link (`claude.ai/artifact/UtUudBfx1EkBnaZAo73JTb`) opened, but it's
the Telegram *architecture* assessment, with no screen designs, so the design below comes from the brief and the code.

## Screen inventory (before)

| Screen | What works | Problems |
|---|---|---|
| Navigation | Four tabs (Home, Chats, Workspace, Profile); deep links and notification taps open conversations; call overlay above everything | **Home** is a decorative dashboard (greeting, hero card, "inbox health" ring) between the user and their chats. Floating glass tab bar. No Calls destination, though a call log exists. Settings split between a Profile tab and a separate Settings screen |
| Chat list | Live data; filters (All, Unread, Direct, Groups, Channels); title search; typing, mentions, unread counts, mute, live-call tag; connection banner | Every row is a floating glass card with a shadow (low density, hard to scan). Drafts are saved but not shown. No row actions (mute, mark read). The "new" actions are two unlabeled header icons |
| Conversation | Grouped bubbles, day dividers, replies, threads, reactions, attachments with progress, edit/delete/pin, retry, held messages, identity-change banner, live-call banner, pinned bar, typing, drafts, pagination | Every bubble has its own shadow, glass border or gradient. Floating, shadowed composer. No "jump to latest" control. Attaching opens the file picker directly, with no choice of photo or file |
| Conversation info, members, invites, teams, activity | Real data, permission-aware actions | Glass cards everywhere; large decorative tiles |
| Search | People (server), conversations (local titles), message text (local `LIKE`) | Glass cards; no hint that message search covers only this device; no loading state |
| Calls | Incoming, outgoing, connecting, voice, video, grid, screen share, minimise bar, E2EE pill (D-17 semantics) | Glass control bar; otherwise sound |
| Profile, Settings, Security, Devices | Every control persists (`SettingsStore`): theme, notifications, background connection, read receipts, typing, media E2EE | Settings split across two screens; glass cards |
| Onboarding | Create, restore, link a device, server choice, invitation code | Gradient canvas and hero art; buttons with "knobs" |
| States | Empty, error banners, shimmer loading, connection banner | Inconsistent margins (20 dp gutter, 5 dp card gaps) |

Design system before: teal, lavender and peach accents; a mint-to-peach gradient canvas; glass surfaces with shadows on almost
every element; 20–28 dp radii; gradient buttons with arrow knobs.

## Plan (what changes)

1. **Tokens:**
   - Restrained emerald primary, neutral light and dark surfaces with strong text contrast.
   - Lavender and peach become muted semantic tints, used only for groups and announcements.
   - Flat backgrounds, borders instead of shadows, 8–16 dp radii, 16 dp gutter. Plus Jakarta Sans stays.
2. **Components** (shared, so every screen follows): flat cards, compact top bars, a docked navigation bar, plain icon buttons,
   solid buttons without knobs, flat list rows with dividers, filled search field, Material-style chips and switches,
   flat sheets and banners, solid-colour avatars.
3. **Navigation:**
   - Tabs: **Chats** (start), **Calls** (real call log, call back, new call), **Workspace** (org, teams, channels, manage),
     **Settings** (profile, account, privacy and security, appearance, notifications, messaging, calls, server, about).
   - The Home dashboard is removed. Its live-call join, search and "new" actions move to the chat list, and its workspace
     summary stays on the Workspace tab.
4. **Chat list:** a compact top bar with search and a new-chat button; filter chips; live-call banner; dense flat rows showing
   drafts, mute, mentions, unread and typing; long-press actions limited to what works (mute or unmute, mark read, info).
5. **Conversation:**
   - Compact header; flat bubbles (tinted outgoing, neutral incoming, no per-bubble shadow); date chips.
   - A jump-to-latest button; a flat composer; an attachment sheet with only the supported options (photo or video, file).
6. **Calls:** palette and controls polished. `CallManager` untouched. The E2EE pill keeps D-17 semantics ("End-to-end
   encrypted" only on decryption evidence).
7. **Search:** scope stated ("message text searches this device's history"), plus loading state.

## What was done (2026-10-16)

**Design system** (`ui/theme`, `ui/components`):
- Colours:
  - emerald `#087F56` light / `#3CC48F` dark;
  - neutral backgrounds `#F6F7F6` / `#0D1110`;
  - white / `#151A18` surfaces with 1 dp borders;
  - semantic tokens for bubbles, read receipts, the nav indicator and text on buttons. White text on emerald meets 4.5:1.
- Avatars are solid colours.
- Radii are 8–16 dp, the side gutter 16 dp, and message text 16 sp.
- Components:
  - flat cards; compact top bars; a docked Material 3 navigation bar with an unread badge;
  - 48 dp icon buttons; solid, outlined or tinted pill buttons;
  - flat list rows; a filled search field; Material switches with toggle semantics; flat sheets and banners;
  - a pulse loading skeleton.
- The gradient canvas, glass shadows, arrow knobs and the "inbox health" ring gauge are gone.

**Navigation:** Chats (start), Calls, Workspace and Settings, in a docked bar.
- The old Home route opens Chats. Deep links and notification taps still open conversations.
- The call overlay stays above everything.

**Screens:**
- **Chat list:**
  - compact header with search and new group; name filter; filter chips; connection and live-call banners;
  - dense rows with dividers, showing drafts ("Draft:" in red), mute, mentions, unread counts and typing;
  - long-press sheet: mute or unmute, mark as read (local, no receipt), info; a "New chat" button.
  - People are circles, groups are circles with a group icon, and org channels are rounded squares.
- **Calls (new):**
  - real local call log: missed calls in red, not-answered and incoming/outgoing, durations, call-back buttons;
  - an "Active now" section to join running calls; a "New call" button.
- **Conversation:**
  - surface header; flat bubbles (soft emerald outgoing, plain incoming) with quoted replies marked by an accent bar;
  - date chips; compact call entries; a jump-to-latest button; a flat composer;
  - an attachment sheet with only the supported options: photo or video (system photo picker) and file. Both go through
    the existing encrypted upload.
- **Settings:** profile plus sections for Account, Appearance (System / Light / Dark), Notifications, Privacy, Calls, and
  Server and about. Every control persists.
- **Search:** scope stated, a loading bar, and a people-search failure banner.
- **Calls UI:**
  - solid stage, with scrims over video;
  - a green Accept button;
  - the unverified state shows a neutral shield and "Verifying encryption…" (D-17).
- Workspace, team, members, invites, activity, info, devices, security and onboarding screens follow automatically through
  the components.

**Not changed:** backend, API, protocols, crypto, `CallManager`, the LiveKit lifecycle, permissions and data. The diff touches
only `ui/` and its tests.

**Verification:**
- JVM tests: `ChatPreviewTest` (4) and `CallLogTextTest` (4). Android **118/118**.
- `lintDebug` reports no errors. `assembleDebug` and `assembleRelease` succeed.
- Phone (HONOR, Android 16), screenshots taken on 2026-10-16:
  - dark and light chat list, Calls, Workspace and Settings;
  - a conversation in both themes;
  - the incoming call and a connected call.
  - The connected call showed "End-to-end encrypted" only after audio had decrypted. The bot decoded 2,230 frames.
  - The theme preference was returned to System afterwards.

**Known gaps:**
- Text bubbles weren't seen on the device: this phone's local history held only call entries, and sending a test message
  needs the owner's OK.
- A conversation that contains only calls still shows "No messages yet" in the list, because only text messages set the
  preview. This is existing data behaviour.
- Call entries in a conversation say "Incoming" or "Outgoing" but not "Missed", because the message doesn't record whether
  the call was answered.
- No Compose UI tests exist. TalkBack, font scaling and rotation weren't exercised on the device. Labels and roles were
  added in code.
