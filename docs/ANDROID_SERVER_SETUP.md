# Connecting the Android app to a server

The app talks to one Griip Message server per account. Since 2026-10-16 it can hold **several accounts on different servers**
("server profiles") and switch between them, so joining a community's self-hosted server never requires deleting the account
you already have.

## New install: choose the server before signing up

1. On the welcome screen, tap the server icon (top right, "Server settings").
2. Type the address your administrator gave you, for example `https://msg.example.org`. A bare name (`msg.example.org`) becomes
   `https://msg.example.org`.
3. Tap **Test**. The app reads the server's public `/v1/server-info` and shows, for example,
   "Example Community · server 0.1.0 · calls available · sign-up needs an invitation", or what's wrong:
   - "Can't reach the server…": wrong address, server down, or no network;
   - "That address answered, but not like a Griip Message server.";
   - "This server needs a newer version of Griip Message." / "…too old for this version…": API mismatch;
   - "This app version connects to servers over HTTPS only": an `http://` address in a release build.
4. Tap **Save**, then create your account (with an invitation code if the server asks for one), restore with a recovery key, or link
   this phone to an account you already use on another device of the **same server**.

## Already signed in: add another server

**Settings → Server and about → (your server) → Servers → Add a server.**

1. Enter the address and tap **Check** (same checks as above, plus a warning for unencrypted `http://` servers).
2. If the server is already on the phone, the app says so and offers to switch to it instead of adding a duplicate.
3. **Add and switch** creates a new, empty profile for that server and opens it. You then sign up, restore or link there.

Your first account stays on the phone, signed in, with its keys, messages and settings. It's listed as "first account on this phone".

## Switching

Tap a server in **Servers → Switch to this server** and confirm. From the welcome screen of a signed-out profile, the server sheet
also lists "Other servers on this phone · switch back".

- Switching closes the current account's connection and database and opens the other one. Notifications from the account you leave
  are cleared.
- **Only the active server is connected.** Messages and calls for other servers wait on those servers (up to 7 days for messages)
  and arrive when you switch back. You won't be notified about them meanwhile.
- You can't switch during a call.

## What is and isn't shared between profiles

| Separate per server | Shared by the whole app |
|---|---|
| Identity and device keys (sealed under the profile's own Android Keystore key) | Theme |
| Session and server address | Notification, background-connection, read-receipt, typing and call-encryption preferences |
| Encrypted message database (SQLCipher, own passphrase) | |
| Downloaded and sent attachments, voice messages | |

Nothing is copied, migrated or merged between profiles, and identity keys never move from one server to another.

**You don't need a second profile to talk to people on other servers.** If your server federates, your one account reaches anyone at
`@name:server` (next section). Profiles are for having separate accounts, e.g. work and community, on one phone.

## Talking to people on other servers (federation)

- **Your address:** Settings → Account shows `@you:your-server` when your server federates. Tap it to copy and share it.
- **Starting a chat:** New chat → type the full address (`@bob:example.org`). The app asks your server to look it up and shows Bob's
  name, address, server, how that server was verified ("verified by 2 directory nodes") and your safety number with him. Tap
  **Message**. Only the exact address you type leaves the phone, and only to your own server.
- **In the chat:** the header shows `@bob:example.org` (presence isn't shared between servers). Messages, attachments and voice messages work
  as usual and stay end-to-end encrypted. Call buttons appear when your server can host calls (it has its own media server).
- **When their server is down:** a banner says so; your messages wait on your server for up to 7 days and are delivered when it's back.
  If their server has shut down for good, the banner says messages can't be delivered.
- **Key changes and blocking:** key-change warnings work exactly as for people on your server. Block from their profile; their messages
  are then dropped before they reach you.
- **Not across servers yet:** groups, channels, organisations, typing indicators and online status (`FEDERATION_PROTOCOL.md` §9).

## Removing and erasing

- **Remove from this phone** (Servers → a server that isn't active and isn't your first account): deletes that profile's keys,
  messages and files from this phone. The account on that server isn't deleted; you can sign in again with its recovery key or from
  another device. The first account can't be removed this way.
- **Sign out & erase this device** (Settings → Security & recovery): revokes this device on the active server, then deletes the
  active profile's keys, messages and files. Other profiles are untouched. You stay on the same server, signed out.

## Under the hood (for developers)

- `data/repo/ServerProfiles.kt`: `ProfileStorage` (where each profile lives), `ProfileList`/`ProfileRegistry` (the list and the
  active profile, in the `griip_profiles` preferences: ids and server addresses only), `ServerAddress` (normalising and checking
  typed addresses), `ServerCompat` (API compatibility).
- The first profile (`default`) keeps the original storage names: database `griip.db`, preferences `griip_identity`, Keystore alias
  `griip_master_v1`, folder `attachments`, setting `serverUrl`. `ServerProfilesTest` pins these names so an update never makes an
  existing account look signed out. Other profiles use `griip-<id>.db`, `griip_identity_<id>`, `griip_master_v1_<id>`,
  `attachments-<id>` and `serverUrl@<id>`.
- `GriipApp.switchProfile/addProfile/removeProfile/eraseEverything` close and recreate the `AppContainer`; `MainActivity` rebuilds the
  whole UI tree for the new container (`key(generation, container)`), so no screen or view model carries over.
- Debug builds allow `http://` servers (with a warning) for local testing; release builds allow it only for the emulator host and
  loopback (`res/xml/network_security_config.xml`).

## Local testing against a server on your computer

With a server running on the computer at port 18090 and LiveKit at 17890/7891 (`TESTING.md` §7), map the ports into the device:

```bash
adb reverse tcp:18090 tcp:18090
```

```bash
adb reverse tcp:17890 tcp:17890
```

```bash
adb reverse tcp:7891 tcp:7891
```

Then use `http://localhost:18090` as the server address in a debug build. Media goes over TCP through `adb reverse` (UDP can't),
so the call screen reports "Connected over TCP".
