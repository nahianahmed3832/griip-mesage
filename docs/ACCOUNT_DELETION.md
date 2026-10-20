# Account deletion

Anyone can delete their own Meldiq account from the app (Android: Settings › Account › Delete account; web: Settings ›
Account › Delete account). This document covers what happens. It replaces the round-4 design (commit ba13c28,
2026-10-18), which was written for the pre-2.0 username accounts and never merged; this version is the same design on
the Orbit 2.0 account model.

## The request

`DELETE /v2/account` with the account session, and the body:

```json
{ "password": "<the account's password>", "confirmEmail": "<the account's email, typed by the person>" }
```

| Answer | When |
|---|---|
| `200 { "deleted": true, "deletedAt": <ms> }` | The account was deleted |
| `401 invalid_credentials` | The password is wrong (the same throttling as sign-in) |
| `400 confirm_mismatch` | The typed email isn't the account's (case doesn't matter) |
| `409 owns_workspaces`, `details.workspaces: [{ id, name, members }]` | The person is the only owner of a workspace. Nothing changes. Make someone else an owner, or delete the workspace, first |
| `401` | No session, or the session has ended |

Rate limit: 10 a minute. The server advertises `account-delete` in `/v1/server-info`.

## What goes

- The email-and-password account: the row itself (so the email address can be used again), every session, the email
  codes, legacy tickets, recovery codes and recovery tickets.
- Every workspace membership, exactly as if the person left: branches, teams, user groups, meeting invitations,
  workspace channels, the workspace arrangement (starred, sections) and personal app connections, which are revoked at
  the provider first.
- Groups and other channels the person was in. A group left without an admin gets its longest-standing member as admin.
- Every device: revoked, unnamed, its v1 and v2 sessions ended, its queued deliveries and wake-up addresses (UnifiedPush
  and Firebase) removed.
- Undelivered messages and receipts the person sent, the encrypted server history, blocks both ways, link-a-device
  approvals, unfinished uploads, reminders, raised hands, document sharing entries.
- Customer-inbox conversations assigned to the person go back to their queue.
- The profile: names, username (freed for anyone), phone, birthday, status, photo (queued for deletion from storage)
  and privacy settings.

## What stays

- An anonymous `users` row with the identity's id and public identity key, marked `deleted_at` and `disabled_at`. This
  way a direct conversation stays one conversation for the other person, who sees "Deleted account". Every lookup that
  skips disabled accounts (search, profiles, presence, new conversations) skips it too.
- Direct conversations with someone who still has an account. A group or direct conversation with nobody left in it is
  removed with its files.
- Files already sent into conversations, which belong to them and expire under the normal rules.
- Workspace content that belongs to the workspace: documents, workflows, API keys, audit entries. They now name
  "Deleted account". A workflow whose owner is gone fails its next run (`owner_not_allowed`) until an admin takes it over.
- Messages already delivered: they are on the recipients' devices, end-to-end encrypted.

## Order and retries

1. The only-owner check, which changes nothing when it refuses.
2. Every session of the account is revoked and its sockets closed.
3. Each workspace is left as above.
4. One SQLite transaction revokes the devices, removes the personal records, leaves the remaining groups and
   anonymises the profile.
5. The arrangement and app connections that may live in MongoDB are removed.
6. Contacts' apps are told to refresh the device list (`devices.changed`), conversations and workspaces are refreshed,
   live calls are left, rooms nobody is left in are closed, and stored files are queued for deletion.
7. The account row goes last, so a failure part-way can be retried by signing in again.

The audit log records `account.delete` with counts only, never the email, username or profile.

## Not covered yet

- An operator command for deletion requests made outside the app (the round-4 `admin.js delete-account`). The CLI
  doesn't build the context that leaving workspaces and revoking app connections needs. Until it does, an operator
  handles such a request by signing in to the account with the person, or by disabling the account (`disable`).
- Data the person entered as a customer-inbox agent (notes, replies) stays with the workspace's customer conversations.
