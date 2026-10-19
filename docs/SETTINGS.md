# Settings

Restructured 2026-10-17 (`SettingsHub` and the screens in `ui/screens/profile/`). Every control persists (SettingsStore
on the phone; profile and privacy on the server).

| Entry | What's there |
|---|---|
| Profile card | Edit profile: photo, names, username, bio, birthday, phone (docs/PROFILE_AND_PRIVACY.md) |
| Accounts | Accounts on this phone: switch, add, sign out of one, remove one |
| Chats | Wallpaper (5, drawn by the app), message text size (90–130 %), animated emoji, link opening and previews, automatic photo download (never / Wi-Fi / always). No theme or accent choice since UI-002: every signed-in screen uses Meldiq's Cocoa palette (the saved choices are kept, unused) |
| Privacy & Security | Recovery key and safety numbers, devices and sessions, who sees each profile field, who can find you, read receipts, typing, blocked people |
| Notifications & Sounds | Messages, groups, message text in notifications, sound, vibration, call ringtone, background connection, wake-ups through UnifiedPush (off by default; docs/BACKGROUND_DELIVERY.md), Android's own settings (docs/NOTIFICATIONS.md) |
| Calls | End-to-end encrypted call media, the phone's echo canceller |
| Data & Storage | Storage used, clear cache, encrypted backup and restore (docs/BACKUP.md) |
| Language | docs/LANGUAGES.md |
| About | Version and how data is protected; seven taps show developer options |

## Accounts
Built on the per-account containers that already existed (`ProfileStorage`): each account has its own identity keys,
device credentials, Keystore key, encrypted database, files and settings namespace. Switching closes one container and
opens the other; nothing is moved, merged or shared, and the identity keys of one account are never used by another.
"Add account" starts a new, signed-out account on the Meldiq network (sign in with a recovery key, link a device, or
register). "Sign out of this account" removes only that account's keys, messages and files from the phone (the account
itself isn't deleted); other accounts stay signed in. Only the active account is connected, so the others' messages and
calls arrive when you switch to them.

## Server setup is hidden
Ordinary users join the Meldiq network without choosing a server. The server choice (onboarding, Settings › Servers)
shows only in debug/test builds, with developer options on (seven taps on About in Settings, or on the Meldiq logo on
the welcome screen), or when the phone already uses another server. Nothing was removed: federation, self-hosting,
registration and discovery work as before.

## Navigation
Bottom bar (UI-002, 2026-10-18): **Home, DMs, Activity**.
- **Home** shows the selected workspace. Its switcher is the workspace name at the top left. Your avatar at the top right opens profile and settings. It also has search, quick cards (Unreads, Drafts, Calls), unread DMs, @mentions and the workspace's channels in collapsible groups.
- **DMs** lists direct messages and groups.
- **Activity** shows @mentions, missed calls and, for people allowed to see it, the workspace change log.
- **Settings, Contacts, Calls and Workspace** are no longer tabs. They open from Home (avatar menu, Calls card, workspace switcher) and from the DMs menu, and have a Back button.
- **New chats** start from the New button on Home and DMs; groups and calls start from the DMs menu.

Contacts lists the people you talk to (from your chats; no address book), finds people by name, `@username`,
`@name:server` or an exact phone number they allow, and opens workspaces and invitations, which are no longer a tab.
