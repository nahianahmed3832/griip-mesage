# Settings

Restructured 2026-10-17 (`SettingsHub` and the screens in `ui/screens/profile/`). Every control persists (SettingsStore
on the phone; profile and privacy on the server).

| Entry | What's there |
|---|---|
| Profile card | Edit profile: photo, names, username, bio, birthday, phone (docs/PROFILE_AND_PRIVACY.md) |
| Accounts | Accounts on this phone: switch, add, sign out of one, remove one |
| Chats | Theme (system/light/dark), accent colour (5), wallpaper (5, drawn by the app), message text size (90–130 %), animated emoji, link opening and previews, automatic photo download (never / Wi-Fi / always) |
| Privacy & Security | Recovery key and safety numbers, devices and sessions, who sees each profile field, who can find you, read receipts, typing, blocked people |
| Notifications & Sounds | Messages, groups, message text in notifications, sound, vibration, call ringtone, background connection, Android's own settings (docs/NOTIFICATIONS.md) |
| Calls | End-to-end encrypted call media, the phone's echo canceller |
| Data & Storage | Storage used, clear cache, encrypted backup and restore (docs/BACKUP.md) |
| Language | docs/LANGUAGES.md |
| About | Version and how data is protected; seven taps show developer options |

## Accounts
Built on the per-account containers that already existed (`ProfileStorage`): each account has its own identity keys,
device credentials, Keystore key, encrypted database, files and settings namespace. Switching closes one container and
opens the other; nothing is moved, merged or shared, and the identity keys of one account are never used by another.
"Add account" starts a new, signed-out account on the Orbit network (sign in with a recovery key, link a device, or
register). "Sign out of this account" removes only that account's keys, messages and files from the phone (the account
itself isn't deleted); other accounts stay signed in. Only the active account is connected, so the others' messages and
calls arrive when you switch to them.

## Server setup is hidden
Ordinary users join the Orbit network without choosing a server. The server choice (onboarding, Settings › Servers)
shows only in debug/test builds, with developer options on (seven taps on About in Settings, or on the Orbit logo on
the welcome screen), or when the phone already uses another server. Nothing was removed: federation, self-hosting,
registration and discovery work as before.

## Navigation
Bottom bar: Chats, Calls, Contacts, Settings. New chats and groups start from the Chats top bar (no floating button).
Contacts lists the people you talk to (from your chats; no address book), finds people by name, `@username`,
`@name:server` or an exact phone number they allow, and opens workspaces and invitations, which are no longer a tab.
