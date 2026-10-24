# Devices and sessions in the console (Platform Admin 2.0, API 0.34.0)

## What's shown

- **Devices** (`devices`): the name people chose, when they were added and last seen, and whether they were removed.
  Also the client last seen on the device's latest session (platform, client, OS), whether it's online now, and its
  wake-up registration state (FCM / UnifiedPush: none, registered or failing, and the last wake-up). Never the token
  or endpoint.
- **Sessions** (`account_sessions`): a masked id, label, device, creation, last use, absolute and idle expiry,
  revocation time and reason, the client seen on it, and whether it holds an open connection now. Sessions that ended
  in the last 30 days are listed too. Tokens, refresh tokens and their hashes are never returned.
- **Open connections** (the hub): device, client, when opened, last frame.
- **Sign-in history** (`audit_log`, `account.*`): sign-ins (password or passkey), wrong passwords for the account,
  refresh-token reuse, and password changes and resets. Never addresses.
- **Findings**, derived only from these records: a device with several active sessions; devices unseen for 90 days;
  sessions without an encryption device; 5 or more wrong passwords in 24 hours; a reused refresh token.

## Actions

All of these need a reason and a fresh passkey check, refuse acting on one's own account (and, unless super admin, on
another platform admin), and are audited.

| Action | Permission | What happens on the server |
|---|---|---|
| Revoke a session | `users.sessions.revoke` | The session is marked revoked. Every request re-reads it, so its access token is refused at once and its refresh token can't renew it. Its device's sockets close; open sockets also re-check their session on every frame |
| End all sessions | `users.sessions.revoke` | Every app session (v2 and v1) ends, plus console sessions if the person is an admin; their sockets close |
| Remove a device | `devices.revoke` | The same path as removing it in the app (`revokeUserDevice`): sessions, queued deliveries and wake-up registration go; contacts are told the device list changed; the person leaves any call on it |
| Disconnect | `users.sessions.revoke` | Closes sockets only: the session stays valid and the app reconnects (for a stuck connection) |
| Temporary suspension | `users.suspend` | Suspended with an end time; the console's minute job lifts it then, audited as `system:restrictions` |

## Collected from this release on

The client kind per session (`session_clients`), derived from the User-Agent or the optional `X-Meldiq-Client`
header. The raw header isn't stored.
