# Orbit 2.0: target architecture and API contracts (2026-10-18)

**Stage 2 of the Orbit 2.0 mobile brief.** The decisions behind this design, and the conflicts with the brief they
resolve, are in `ORBIT_2_MOBILE_AUDIT_2026-10-18.md` §6 (C1–C9). This document is the contract the Android app
implements now, and that the iOS, web and PC clients will implement later.

## 1. Shape of the system

```
Android app ─┐                      ┌─ accounts, sessions, email codes, legacy links      (new, /v2)
(iOS later) ─┼─HTTPS/WSS─▶ API ─────┼─ users, devices, orgs, conversations, queue, calls  (existing, /v1)
(web later) ─┘  one Node process    ├─ SQLite now; MongoDB Atlas later (§8)
                                     ├─▶ S3 (private): presigned single and multipart uploads, 5-minute downloads
                                     ├─▶ Amazon SES: verification and reset emails
                                     └─▶ LiveKit, UnifiedPush (unchanged)
```

- **Two layers of identity.** The *account* (email, password, name) decides who may sign in and holds sessions. The
  *encryption identity* (identity key, device keys) decides who can read messages. An account has at most one
  encryption identity; a device belongs to it. The server can authenticate the account but still can't read messages.
- **One backend for every client.** The mobile app uses only the contracts below and the existing `/v1` messaging,
  organisation, file and call routes. No mobile-only account model or database.
- **No new services.** Accounts are a module inside the existing server (`server/src/accounts/`). SES is the only new
  external service, and only for email.

## 2. Accounts and sessions

| Rule | Value |
|---|---|
| Registration fields | name (1–80), email (normalised to lower case, ≤ 254), password (10–128 characters, not the email) |
| Password storage | scrypt, N = 2^15, r = 8, p = 3 (32 MiB, OWASP-equivalent), 16-byte salt, parameters stored with the hash and upgraded at sign-in |
| Email verification | 6-digit code plus the password chosen at sign-up, 15 minutes, 5 attempts, hashed; sign-in is refused until verified |
| Password reset | 10-character code (about 50 bits), 15 minutes, 5 attempts; a reset revokes every session |
| Access token | 32 random bytes, 15 minutes, stored as SHA-256 |
| Refresh token | `<session id>.<32 random bytes>`, rotated on every use, 60 days idle, 180 days at most; the just-replaced token works once more for 30 s (lost answer); any older one revokes the session |
| Enumeration | register, resend and forgot always answer 202; login answers `invalid_credentials` for unknown email and wrong password alike, after a dummy hash; wrong codes answer alike in every case |
| Guessing | 5 failures per email and network per 15 minutes, 50 per email per hour (cleared by a password reset), plus the per-network route limits |
| Email limits | 5 codes per account per purpose per hour, 30 account emails per network per hour, 1 "already registered" notice per hour; emails contain nothing anyone typed |
| Invites | On invite-only servers a sign-up takes one invite use when the account is made; an organisation invite joins its organisation when the identity is made |
| Disabled accounts | `accounts.disabled_at` or `users.disabled_at` ends every session |

A session is bound to a device once the device has encryption keys. Only a device-bound session can use the `/v1`
messaging routes and the WebSocket; an unbound one can only call `/v2/account/*` and `/v2/workspaces`. The WebSocket
re-checks the **session** on every frame, so a rotated access token doesn't drop the socket, and a revoked session is
cut off at once.

## 3. Device setup (after sign-in)

| Situation | What the app does | Server route |
|---|---|---|
| New account, first device | Makes the identity and device keys, shows the recovery key once | `POST /v2/account/devices` with `identityKey` |
| Device that already has keys for this account | Signs the login challenge with its device key; the session is bound at sign-in | `POST /v2/auth/login` with `device` |
| New phone, recovery key at hand | Derives the identity from the recovery key, certifies new device keys | `POST /v2/account/devices` |
| New phone, old phone at hand | Existing pairing (`/v1/pairing`), then binds | `POST /v2/account/devices` |
| Neither | "Start fresh": new identity, old devices revoked, server history deleted, contacts see a new security code | `POST /v2/account/identity/reset` (password required) |

## 4. Workspaces

- **Personal** (`id: "personal"`): always present. DMs, groups and contacts outside any organisation. No admin tools.
- **Organisations**: the existing orgs, with the caller's role (OWNER, ADMIN, MEMBER, GUEST). Create with
  `POST /v1/orgs`, join with `POST /v1/invites/preview` and `/v1/invites/accept`, manage with the existing routes.
- **Switching** is a client choice that the server stores (`selectedWorkspaceId`) so every device opens the same one.
  Every request is still authorised by `authz.ts`; switching grants nothing.
- **Ownership transfer** today: an owner promotes another member to OWNER, then steps down (the server keeps at least one
  owner). An atomic transfer route is a follow-up.

## 5. Legacy migration

```
recognised install (keys + v1 session on this phone)        fresh install ("Move an existing Orbit account")
        │ username shown, recovery key typed                       │ username and recovery key typed
        └──────────────▶ app derives identity key, checks it, signs challenge ◀──────────────┘
                         POST /v2/migration/legacy/verify ─▶ single-use ticket (15 min)
                         POST /v2/migration/legacy/complete {ticket, name, email, password}
                         POST /v2/auth/email/verify {email, code, password}
                         POST /v2/auth/login (+ device proof on a recognised install)
                         fresh install: POST /v2/account/devices with keys from the recovery key, then history restore
```

- The link is the existing `users` row: same user id, so every membership, conversation, call record and history item
  stays attached. Nothing is copied.
- A failed verification creates nothing. `complete` repeated with the same ticket and email returns the same result;
  a second link for the same legacy user or the same email is refused.
- The recovery key never leaves the phone (C2). It is not stored as a credential.
- Retirement: `LEGACY_MIGRATION=off` closes the routes; `LEGACY_LOGIN=off` ends v1 sign-in; `LEGACY_REGISTRATION=off`
  ends v1 sign-up. The owner sets the dates (§9).
- Required move: with `LEGACY_MIGRATION_REQUIRED=on`, server-info says `accounts.legacyMigrationRequired: true`, and an
  updated app shows the move with no "Later" before it opens. Apps from before 2.0 keep signing in while
  `LEGACY_LOGIN=on`, so nobody is locked out before the update reaches them. The server reports it only while it can
  email codes, and the app also checks `accounts.registration`. Without email the move would trap people at the code
  step. With SES in its sandbox, codes reach verified addresses only, so turn this on after production access.
- Apps offer the move at all only while `accounts.registration` is true (the server can email codes). Production 0.4.1
  lists `legacy-migration` with email still off, and a move that can't finish isn't offered.

## 6. API contracts (v2)

Errors use the v1 envelope: `{"error": {"code": "...", "message": "...", "details": ...}}`. Times are Unix milliseconds.
Zod schemas in `server/src/accounts/schemas.ts` are the source of truth; the Kotlin models in
`android/.../data/account/AccountModels.kt` mirror them.

### Shared objects

```jsonc
// Session (every successful sign-in or refresh)
{ "accessToken": "…", "accessTokenExpiresAt": 0, "refreshToken": "…", "refreshTokenExpiresAt": 0,
  "session": { "id": "s_…", "deviceId": "d_…|null", "createdAt": 0 },
  "account": Account }
// Account
{ "id": "acc_…", "email": "a@b.c", "name": "Ada", "createdAt": 0, "legacyLinkedAt": 0|null,
  "selectedWorkspaceId": "personal|o_…|null",
  "identity": { "userId": "u_…", "username": "ada", "identityKey": "base64" } | null }
// Workspace
{ "id": "personal|o_…", "kind": "personal|organization", "name": "…", "role": "OWNER|ADMIN|MEMBER|GUEST", "slug": "…|null" }
// Device proof (an existing device's key signs a fresh /v1/auth/challenge nonce)
{ "deviceId": "d_…", "nonce": "…", "signature": "base64 Ed25519 over griip-auth-v1\nlogin\n{nonce}\n{signingKey}" }
// New device
{ "name": "…", "signingKey": "base64", "encryptionKey": "base64", "certificate": "base64" }
```

### Routes

| Route | Auth | Body | Success | Errors |
|---|---|---|---|---|
| `POST /v2/auth/register` | – | `name, email, password, inviteCode?` | 202 `{status:"verification_sent", email}` | 400, 403 `invite_required`/`invalid_invite`, 429 `registration_limited`/`registration_paused`, 503 `email_unavailable` |
| `POST /v2/auth/email/verify` | – | `email, code, password` | 200 `{verified:true}` | 400 `invalid_code` (wrong, expired or used-up code, or wrong password) |
| `POST /v2/auth/email/resend` | – | `email` | 202 | 503 `email_unavailable` |
| `POST /v2/auth/login` | – | `email, password, deviceName?, device?` | 200 Session | 401 `invalid_credentials`, 403 `email_unverified`, 401 `account_disabled`, 401 `invalid_device_proof`/`device_revoked`/`challenge_expired`, 429 `login_throttled` |
| `POST /v2/auth/refresh` | – | `refreshToken` | 200 Session | 401 `session_expired`, 401 `session_revoked` |
| `POST /v2/auth/logout` | access | – | 200 `{ok:true}` | 401 |
| `POST /v2/auth/password/forgot` | – | `email` | 202 | 503 `email_unavailable` |
| `POST /v2/auth/password/reset` | – | `email, code, newPassword` | 200 `{ok:true}` | 400 `invalid_code`, 400 `weak_password` |
| `GET /v2/account` | access | – | 200 `{account, session}` | 401 |
| `PATCH /v2/account` | access | `name` | 200 `{account}` | 400 |
| `POST /v2/account/password` | access | `currentPassword, newPassword` | 200 `{ok:true}` (other sessions revoked) | 401 `invalid_credentials` |
| `GET /v2/account/sessions` | access | – | 200 `{sessions:[{id,label,deviceId,createdAt,lastUsedAt,current}]}` | 401 |
| `DELETE /v2/account/sessions/:id` | access | – | 200 `{ok:true}` | 404 |
| `PUT /v2/account/preferences` | access | `selectedWorkspaceId` | 200 `{selectedWorkspaceId}` | 404 `workspace_not_found` |
| `POST /v2/account/devices` | access | `identityKey?, username?, device, nonce, signature` | 200 `{userId, deviceId, username, result:"identity"|"device"|"existing"}` | 409 `identity_mismatch`, 401 `invalid_certificate`/`invalid_proof`, 409 `too_many_devices`/`username_taken` |
| `POST /v2/account/identity/reset` | access | `password, identityKey, device, nonce, signature` | 200 as above | 401 `invalid_credentials` |
| `GET /v2/workspaces` | access | – | 200 `{workspaces:[Workspace], selectedWorkspaceId}` | 401 |
| `POST /v2/migration/legacy/verify` | – | `username, nonce, signature` | 200 `{ticket, expiresAt, legacyUser:{username, displayName}}` | 401 `legacy_verification_failed`, 409 `already_migrated` (`details.emailHint`), 410 `migration_closed`, 429 |
| `POST /v2/migration/legacy/complete` | – | `ticket, name, email, password` | 202 `{status:"verification_sent", email}` | 401 `invalid_ticket`, 409 `email_taken`/`already_migrated` |

The legacy signature is Ed25519 by the **identity** key over `griip-legacy-migration-v1\n{username}\n{nonce}`, with the
nonce from `POST /v1/auth/challenge` (single use, 2 minutes).

`GET /v1/server-info` adds: `api: 2`, `minClientApi: 1`, features `accounts-v2`, `legacy-migration` (while open),
`files-1g`, and `fileMaxBytes` (the plaintext limit). Apps older than API 2 keep working until v1 sign-in is retired.

## 7. Files (1 GB)

| Item | Value |
|---|---|
| Limit | 1 GiB = **1,073,741,824 bytes of the person's file** (`FILE_MAX_BYTES`), checked byte-exactly by the app before encrypting |
| Server cap | `ATTACHMENT_MAX_BYTES` defaults to `FILE_MAX_BYTES + 1 MiB`: the chunked format adds 16 bytes per 1 MiB chunk (16 KiB for 1 GiB) |
| Upload | Unchanged pipeline: `POST /v1/attachments/uploads` → 16 MiB parts, each presigned and bound to its size and SHA-256 → `GET …/parts` to resume → `POST …/complete` (server checks every part) → `DELETE …` to cancel |
| Session | `DIRECT_UPLOAD_SESSION_SECONDS` default raised from 6 h to 24 h (1 GiB at 1 Mbit/s takes about 2.4 h) |
| Download | 302 to a 5-minute presigned GET after a membership check; resumable with `Range` |
| Device space | the app needs room for the encrypted copy (about the file's size) and refuses early when there isn't |
| Abuse limits | per-person daily and stored caps, per-workspace cap, server total, concurrent uploads, upload starts per hour: all unchanged and configurable. No plans or paywalls |
| Visibility | `admin.js stats` already reports stored bytes; S3 cost is on the AWS budget alarm |

**Production values are the owner's call.** Production today runs `ATTACHMENT_MAX_BYTES` = 25 MB and a 10 GiB server
total. With 1 GiB files, a total of at least 200 GiB is a sensible start. S3 Standard in ap-southeast-1 costs roughly
US$0.025 per GB-month (about US$5 a month for 200 GB), plus requests and about US$0.12 per GB downloaded to the
internet. These are list prices to check against the AWS calculator before changing anything.

## 8. Database (SQLite now, MongoDB Atlas later)

Migration `014_accounts.sql` adds four tables; nothing existing changes.

| Table | Purpose |
|---|---|
| `accounts` | email (unique), name, password hash, verified time, `user_id` (unique link to the encryption identity, cascade on delete), legacy link time, selected workspace, how it was admitted (invite or legacy), disabled time |
| `account_sessions` | session id, account, bound device, label, access and refresh token hashes (plus the just-replaced refresh hash and its time), expiry times, revocation |
| `account_codes` | one email or reset code per account and purpose, hashed, with attempts and expiry |
| `legacy_tickets` | single-use migration tickets (hashed), the legacy user, expiry, use |

The account module reaches its tables through `AccountStore` (`server/src/accounts/store.ts`), so it can move to
MongoDB without touching the routes. The planned collections mirror the tables: `accounts` (unique index on
`emailLower`, unique sparse index on `userId`), `accountSessions` (unique `accessHash`, unique `refreshHash`, TTL index
on `expiresAt`), `accountCodes` (TTL on `expiresAt`), `legacyTickets` (TTL on `expiresAt`).

Moving the whole server to MongoDB Atlas is its own project, blocked on: an Atlas cluster in AWS ap-southeast-1
(M10 or above for production backups, roughly US$60 a month at list price), network access from the VPS, credentials in
the server's environment only, and a dual-write and verification plan for the existing tables. Nothing in the app talks
to the database.

## 9. Environments and settings

| Setting | Default | Notes |
|---|---|---|
| `APP_ENV` | `development` | `development`, `staging` or `production`. Production refuses the file mail transport and weak password hashing |
| `MAIL_TRANSPORT` | `none` | `none` (v2 sign-up and reset answer 503), `file` (development: messages written to `MAIL_FILE_DIR`), `ses` |
| `MAIL_FROM` | – | Required for `ses`, e.g. `Orbit <no-reply@griipbuddy.cloud>` |
| `MAIL_FILE_DIR` | `$DATA_DIR/mail` | Development only |
| `SES_REGION` | `ap-southeast-1` | Credentials from `SES_ACCESS_KEY_ID`/`SES_SECRET_ACCESS_KEY`, or the default AWS chain |
| `PASSWORD_SCRYPT_LOG_N` | `15` | Production refuses less than 15 |
| `ACCESS_TOKEN_TTL_SECONDS` | `900` | |
| `REFRESH_TOKEN_IDLE_DAYS` | `60` | |
| `SESSION_MAX_DAYS` | `180` | |
| `AUTH_RATE_LIMIT_PER_MINUTE` | `20` | unauthenticated account requests per network per minute (refresh: 10×) |
| `ACCOUNT_EMAILS_PER_NETWORK_PER_HOUR` | `30` | account emails one network may cause |
| `LEGACY_LOGIN` | `on` | v1 device-key sign-in for apps before 2.0 |
| `LEGACY_REGISTRATION` | `on` | v1 sign-up; set `off` in production when the 2.0 app ships |
| `LEGACY_MIGRATION` | `on` | the migration routes |
| `LEGACY_MIGRATION_REQUIRED` | `off` | `on`: updated apps must move before opening (no "Later"); needs `LEGACY_MIGRATION=on` and email |
| `FILE_MAX_BYTES` | `1073741824` | plaintext per-file limit |

Staging is a second Compose project on its own host name and database, with SES in sandbox mode. It doesn't exist yet
and needs the owner's approval (DNS, nginx, a second data volume).

## 10. Android structure

| Package | Contents |
|---|---|
| `data/account` | `AccountModels` (wire models, no Android imports), `AccountRules` (form checks), `AccountApi`, `AccountRepository` (sign-up, sign-in, refresh, sessions, device setup), `LegacyMigration`, `WorkspaceRepository` |
| `crypto/IdentityStore` | also seals the account tokens with the Keystore key |
| `data/api/ApiClient` | 401 → one refresh → retry; `session_revoked`/`session_expired` sign the account out |
| `ui/screens/account` | sign-up, sign-in, email code, forgot/reset password, device setup, legacy migration, workspace start |
| `ui/AppRoot` | signed-out → account onboarding; signed-in legacy install → migration prompt; signed-in → the existing navigation |

The new account code is plain Kotlin apart from the screens, so it can move into a Kotlin Multiplatform module for iOS
without changes (C4).
