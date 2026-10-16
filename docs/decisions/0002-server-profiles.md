# ADR 0002: One account per server, held as separate on-device profiles

Date: 2026-10-16. Status: accepted.

## Context

The app held exactly one account. "Changing servers needs a new account" meant erasing the existing one, which is unacceptable for
people joining a self-hosted community. Requirements: no account deletion, no silent migration or merge when the endpoint changes,
identity keys never transferred between servers, existing installs keep working untouched.

Options considered:

1. **Change the server address in place.** Would silently point an existing identity, database and pinned contact keys at another
   server: a migration in disguise, and the old account would become unreachable. Rejected.
2. **Multi-account inside one database.** Every table, query and key lookup would need an account column; a mistake leaks data across
   accounts. Rejected as too invasive and risky.
3. **Separate profiles, one container each (chosen).** Each server gets its own storage namespace: Keystore alias, sealed identity
   preferences, SQLCipher database with its own passphrase, attachment folder and server setting. The app runs one `AppContainer`
   at a time and swaps it on switch.

## Decision

Option 3. The original install becomes the `default` profile with **exactly** its existing storage names, so nothing moves. Other
profiles get names derived from a random 16-hex-character id (validated, since it ends up in file names). The registry
(`griip_profiles` preferences) stores only ids, server addresses, creation times and the active id.

## Consequences

- Existing users keep their account with no migration step.
- Only the active profile is connected; messages for others wait on their servers (≤ 7 days) and arrive on switch. No background
  delivery or notifications for inactive profiles.
- Appearance and behaviour preferences are shared across profiles; account data is not.
- Removing a profile deletes its local data only; the server account remains (recoverable with its recovery key). The default profile
  can't be removed, only erased by the existing "erase this device" flow.
- Tests: `ServerProfilesTest` pins the default storage names and covers the list rules, address parsing and compatibility.
- Since federation (ADR 0005), a second profile is **not** needed to talk to people on another server: one account reaches anyone at
  `@name:domain`. Profiles remain for people who want separate accounts (e.g. work and community) on one phone.
