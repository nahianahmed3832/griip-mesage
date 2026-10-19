# Profiles and privacy

Added 2026-10-17. Settings › Edit profile, and Settings › Privacy & Security.

## Fields

| Field | Stored | Default audience | Notes |
|---|---|---|---|
| Username | server | always visible | 3–24 characters, unique; changes at most once an hour. The old one becomes free; links, mentions and `@name:server` addresses that used it stop working |
| Display name | server | always visible | needed in every chat and call |
| First / last name | server | everyone | optional; suggests a display name |
| Bio | server (`status_text`) | everyone | up to 140 characters |
| Profile photo | server, as a JPEG | everyone | re-encoded on the phone (square, 512 px, no camera metadata). **Not end-to-end encrypted**: the server stores and serves it to its audience |
| Birthday | server | **nobody** | `YYYY-MM-DD`, or day and month only |
| Phone number | server | **nobody** | E.164, not verified by Meldiq |
| Online / last seen | (live) | contacts | as before, now a setting |
| Find me by phone | — | **nobody** | exact number only, never a list; same answer whether no one has the number or its owner doesn't allow it |
| Find me by username | — | everyone | "contacts" also hides you from other servers' lookups |

Audiences: **everyone** (anyone signed in who can look you up), **my contacts** (people who share a chat with you, or a
workspace where neither of you is a guest), **nobody** (only you). Someone you blocked sees only your username, display
name and colour, never your photo, bio, last seen or other fields.

The server enforces all of this (`server/src/profile.ts`); fields a viewer may not see are left out of what it sends,
not just hidden by the app. Lists (search, members) show only what's public to everyone, except that people who share a
chat with you see what you show contacts. Your online state is now hidden everywhere your "last seen" setting says,
including conversation and workspace member lists and live presence updates (before, member lists showed it to all
members regardless).

## What isn't hidden

Messages and calls are end-to-end encrypted. To deliver them, servers still see your username, display name, who you
talk to and when, and message sizes; servers on the Meldiq network see the username, display name and address of people
who talk across servers. Contacts are never uploaded: Meldiq doesn't read the phone's address book (sharing a contact
uses Android's picker for one entry).

## Server

Migration **008** adds `first_name`, `last_name`, `birthday`, `phone`, `photo_rev`, `privacy`, `username_changed_at`
(all nullable; existing accounts unchanged). Endpoints: `PATCH /v1/users/me` (more fields), `PUT /v1/users/me/privacy`,
`PUT|DELETE /v1/users/me/photo`, `GET /v1/users/:id/photo`, `GET /v1/users/by-phone`. Photos live in
`DATA_DIR/profile-photos` (`PROFILE_PHOTO_DIR`). Tests: `server/test/profile_privacy.test.ts` (11).

App: `ProfileRules` (`ProfileRulesTest`), `ProfileRepository`, `EditProfileScreen`, `PrivacyScreen`, `BlockedUsersScreen`,
`UserAvatar` (photos with an in-memory and per-account disk cache). Room v6 adds `users.photoRev`.

## Servers without profile support (2026-10-17)

Production ran a server from before profiles, so changing a profile photo returned the server framework's own "Route …
not found" 404, which the app showed as a bare "Not found."

- **What the app does now.** It recognises a missing route (`ApiErrors.routeMissing`, tested in `ApiErrorsTest`) and
  says the server needs an update.
- **Edit profile.** The screen reads the server's `features`. Without `profiles` / `profile-photos` it explains up front
  what can't be saved and disables the photo button.
- **Photo storage.** Photos are stored per revision (`<user>-<revision>`, on disk or in S3). A replaced photo's old file
  is deleted, and a new revision is never served with the old bytes.
- **Tests.** `server/test/storage_s3.test.ts` covers upload, replacement and removal on S3; `profile_privacy.test.ts`
  covers the disk.
- **On the emulator** against a local server: upload, display, replacement (the old file is gone).
- **Still needed:** deploying the server, for production.
