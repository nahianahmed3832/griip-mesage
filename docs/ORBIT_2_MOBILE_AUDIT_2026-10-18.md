# Orbit 2.0 mobile: repository audit (2026-10-18)

**Stage 1 of the Orbit 2.0 mobile brief.** Read-only audit of `internal-beta` at `6f355a1`, done in the worktree
`claude/orbit-2-mobile`. It builds on `ORBIT_PRODUCT_TECHNICAL_AUDIT_2026-10-17.md` (the full feature inventory and
risks S1–S14) and checks the parts the Orbit 2.0 brief touches against the code itself. The design that follows from it
is in `ORBIT_2_ARCHITECTURE_2026-10-18.md`.

What was read: `server/src` (all routes, `context.ts`, `authz.ts`, `config.ts`, `db.ts`, `ws.ts`, storage), migrations
001–012, `android/app/src/main` (identity, session, API, realtime, attachments, onboarding, navigation, DI), the Gradle
catalogue, `.github/workflows/ci.yml`, `deploy/`. Nothing in production was touched.

Other work that affects this audit:

- **The revised UI-002** (workspace Home; exactly three tabs, Home, DMs and Activity; a workspace switcher) is verified on
  the owner's phone but **uncommitted** in the main checkout. It already is the brief's navigation baseline (§6).
- **A web-client session** has an uncommitted `orbit-web` worktree that builds a browser client on today's
  device-keyed accounts (pairing, no passwords). The brief puts the web out of scope; see conflict C7.
- **The account-deletion branch** (`claude/orbit-round-4-8f76de`, migration `013`) is committed but not on
  `internal-beta`. New migrations here start at `014` so both can land; the runner applies any missing version.

## 1. Current architecture

| Area | What the code does today |
|---|---|
| Platforms | **Android only.** Kotlin 2.2.21, AGP 8.13.2, Jetpack Compose (BOM 2025.10.01), minSdk 26, targetSdk 36, one Gradle module `:app`. **No iOS code of any kind.** |
| App structure | MVVM, manual DI (`di/AppContainer`), one container per server profile (`ProfileStorage`). Navigation Compose with string routes (`ui/AppRoot.kt`). Design system in `ui/theme` and `ui/components` (Ivory for auth, Cocoa signed in). |
| Local data | Room v7 on SQLCipher (passphrase sealed by Android Keystore); keys and session sealed in SharedPreferences by `KeystoreSealer`; attachments in app storage. |
| Identity | **No passwords.** An account is an Ed25519 identity key made on the phone (`crypto/IdentityStore.kt`). Each device has an Ed25519 signing key and an X25519 encryption key, certified by the identity key. The **recovery key** is the 32-byte identity seed plus a 2-byte checksum in grouped base32 (`RecoveryCode`). Linking a phone seals the seed to it (pairing code). |
| Sessions | `POST /v1/auth/challenge` + `/v1/auth/login` signed by the device key; 256-bit bearer token stored as SHA-256, 7 days. On 401 the app silently signs a new challenge (`SessionRepository.reauth`). |
| Server | Node 22, TypeScript, Fastify 5, **SQLite** (better-sqlite3, WAL), zod. One process, in-memory WebSocket hub. 116 route registrations, including `/v1/ws` and 17 federation and core-node routes. Authorisation in `authz.ts`. |
| Organisations | Orgs → branches → departments → teams; roles OWNER, ADMIN, MEMBER, GUEST (plus branch manager and team lead), all enforced on the server. Invitations with hashed codes, scope, expiry and role caps. Ownership changes by promoting another owner, then stepping down (one owner minimum). |
| Messaging | DMs, groups (≤ 50), org/branch/team channels. Every message is `griip-envelope-v1` (HPKE + AES-GCM + Ed25519); the server checks signatures and recipient sets and **never sees content**. 7-day queue, receipts, typing, presence, replies, reactions, edits, pins, mentions, threads (client side), encrypted server history keyed from the identity seed. |
| Realtime | `/v1/ws`: `hello`, `queue`/`backlog`/`queue.drained`, `typing`, `presence`, `call`, `conv.updated`, `org.updated`, `devices.changed`. The socket re-checks its bearer token on every client frame. |
| Files | Encrypted on the phone (per-file AES-256-GCM; chunked format above 25 MB). With S3: presigned single PUT or multipart (16 MiB parts, each bound to its size and SHA-256), resume by listing parts, cancel, server-side verification, abandoned-upload cleanup, 5-minute download links after a membership check. **App cap 500 MB, server default 500 MiB, production 25 MB.** |
| Calls | Self-hosted LiveKit, room-scoped tokens, media E2EE with the key sent in an envelope. |
| Notifications | UnifiedPush wake-ups (no content), local notifications, "stay connected" service. |
| Tests and CI | Server: 31 vitest files. Android: 62 JVM test classes, 2 instrumented. GitHub Actions: server typecheck/test/build, Android unit tests, lint, debug and unsigned release builds, Room schema check. |
| Configuration | `deploy/.env.example` (55 variables), `BuildConfig.DEFAULT_SERVER_URL`, a side-by-side debug build (`-Porbit.sideBySide=true`). No staging environment exists. |

## 2. Features to keep

Kept as they are; Orbit 2.0 builds on them.

- End-to-end encrypted messaging, the envelope format and the shared Kotlin/Node test vectors.
- Org model, roles and `authz.ts`, invitations, channels, archive and delete.
- The S3 direct and multipart upload pipeline (it already does most of what brief §7 asks).
- Calls, push wake-ups, history sync, backups, profiles and privacy, blocking.
- The design system and the revised UI-002 navigation (Home, DMs, Activity, workspace switcher), once committed.
- CI, the deployment scripts and the CloudFormation templates.

## 3. Features to refactor

| Feature | Change |
|---|---|
| Session handling (app and server) | Short-lived access token plus rotating refresh token. Refresh replaces device-signed re-authentication. The WebSocket re-checks the *session* instead of the exact token, so token rotation doesn't drop the socket or flap presence. |
| `ApiClient` | 401 → refresh once → retry; typed errors for expired or revoked sessions; v2 account contracts in their own package. |
| Device setup | Becomes a step after sign-in: create the encryption identity (new account), restore it with the recovery key, approve from another device (existing pairing), or start fresh. |
| Workspace selection | One list from the server: a personal workspace plus organisations, with the selected workspace synced to the account. |
| File limits | 1 GiB per file (byte-exact on the plaintext), with the server cap derived from the encryption overhead. Longer upload sessions for large files. |
| Server-info | Advertises API 2, account features and the plaintext file limit. |

## 4. Features to replace

| Today | Orbit 2.0 |
|---|---|
| Username + device key as the only sign-in | Name, email and password accounts with email verification, password reset, session list and revocation (§3 of the brief). |
| v1 registration (`/v1/auth/register`) | v2 registration. v1 registration stays behind a setting for old app versions and is turned off in production once the 2.0 app ships. |
| v1 challenge login | Kept only for the migration window (`LEGACY_LOGIN`), then off (C9). |
| Recovery key as an account login | Recovery key as the encryption key backup only; account access is email and password. |

## 5. Features to remove

**There is no WhatsApp-specific code.** `git grep -i whatsapp` finds only a design note in `docs/PROGRESS.md` ("inspired
by WhatsApp and Telegram usability"). No WhatsApp Business API client, webhooks, provider settings, environment
variables, routes, screens or dependencies exist. The only provider-like import, `com.twilio.audioswitch`, is the audio
routing library LiveKit's Android SDK uses for calls; it is ordinary call code and stays. Nothing is removed under brief
§8. Superseded code is removed only when its replacement and the migration path are proven (C9).

## 6. Conflicts between the brief and the current design

Each item states what this implementation does. All are reversible owner decisions; none weakens a security property
silently.

| # | Conflict | Options | What this implementation does |
|---|---|---|---|
| C1 | **Email/password accounts vs end-to-end, device-keyed identity.** The server can't read messages; a password can't decrypt them. | **A.** Email/password is the account login; device and identity keys stay for encryption. A new phone signs in, then restores encryption (recovery key or another device) or starts fresh. **B.** Drop E2EE and make the server read messages (classic SaaS). Loses the core security property, and existing encrypted history can't be converted. **C.** Password-wrapped key backup on the server. Convenient, but E2EE becomes only as strong as the password against a compromised server. | **A.** Same model as Signal, WhatsApp and Matrix. C can be added later as an opt-in key backup. |
| C2 | **"Submit the legacy secret key to the backend."** The legacy secret key is the recovery key, i.e. the identity seed: the root of the encrypted history. Sending it would let the server decrypt that history. | **A.** The app derives the identity key from the recovery key and signs a server challenge; the backend verifies the signature against the stored identity key over HTTPS. The secret never leaves the phone. **B.** Send the key and discard it. | **A.** It meets the brief's intent (backend verification, nothing stored, never logged) without exposing history. |
| C3 | **MongoDB Atlas vs SQLite.** Every server module uses SQLite with transactions and 12 migrations. No Atlas cluster or connection details exist. | **A.** Keep SQLite now; put the new account code behind a store interface; design the MongoDB collections; move the database as its own server project once Atlas exists. **B.** Run MongoDB beside SQLite (two databases, which the brief forbids without justification). **C.** Port the whole server to MongoDB now, unverifiable without a cluster. | **A.** Blocked items and cost are in the architecture doc. |
| C4 | **iOS doesn't exist.** | **A.** Kotlin Multiplatform shared module (contracts, API client, session logic) plus a SwiftUI app. **B.** A separate native Swift app. **C.** Rewrite both apps in Flutter or React Native, throwing away a tested Android app. | **Not started.** The new account contracts are plain Kotlin with no Android imports, ready to move into a KMP module. iOS needs an Apple developer account, an iPhone for testing, APNs, CallKit and the LiveKit Swift SDK. |
| C5 | **"Record attachment metadata (name, type) in MongoDB."** File names and types are inside the encrypted message today; the server knows size, time, conversation and uploader. | **A.** Keep names and types end-to-end encrypted. **B.** Send them to the server in the clear (searchable, but readable by the operator). | **A.** The server records what it needs to authorise and clean up. |
| C6 | **Personal workspace.** DMs and groups already have no org; orgs are the only workspace rows. | **A.** A virtual personal workspace: DMs, groups and contacts outside any org. **B.** A hidden one-member org per person, which would need special cases in invites, channels and roles. | **A.** Organisation membership stays optional. |
| C7 | **The web client is being built now on today's accounts.** The brief says web comes later and must share the new account system. | The browser stays "another device" in Orbit 2.0. It would sign in with email and password, then be linked by pairing as today. | Flagged to the coordinating session; nothing in the web worktree is touched. |
| C8 | **1 GB files vs today's limits.** Production allows 25 MB, 2 GiB a day, 4 GiB stored per person and 10 GiB in total. | Raising the per-file cap alone allows ten 1 GB files on the whole server. | Code default 1 GiB; production values and their S3 cost are the owner's call (architecture doc §7). |
| C9 | **"No two competing account systems" vs a migration window.** Old app versions can only use v1. | Keep v1 login only while migration runs, behind `LEGACY_LOGIN`; turn v1 registration off once the 2.0 app ships; turn v1 login off at an owner-approved cutoff. | Settings exist; the retirement date is the owner's decision. |

## 7. Backend/API changes required

1. Accounts: register, verify email (code), resend, login (optionally binding an existing device by its key), refresh
   (rotating, with reuse detection), logout, session list and revoke, forgot and reset password, change password,
   profile name.
2. Device setup under an account session: first identity, add a device with the existing identity, start fresh.
3. Legacy migration: challenge, verify (identity-key signature), complete (link the existing user to new credentials).
   Idempotent and safe to retry.
4. Workspaces: personal plus organisations in one list; selected workspace stored on the account.
5. Email delivery: an interface with a development file transport and an Amazon SES transport. Production registration
   is refused while no transport is configured.
6. Files: 1 GiB plaintext limit in server-info, server cap derived from it, longer upload sessions.
7. Environments: `APP_ENV` (development, staging, production) with production-only safety checks.

## 8. Migration risks and security concerns

| Risk | Mitigation |
|---|---|
| Account enumeration through register, login, reset or migration | Same answers and similar timing for known and unknown emails and usernames; dummy password hash for unknown emails. |
| Password guessing | scrypt (N=2^15, r=8, p=3), per-account failure throttling, per-network rate limits, minimum length 10. |
| Stolen refresh token | Rotation on every use; presenting an old token again revokes the session. Tokens stored as SHA-256 only. |
| Legacy-key exposure | Never sent (C2); never logged; the app keeps it in memory only while signing. |
| A failed migration linking anyway | The link is written only after a verified signature and a valid single-use ticket, inside one transaction; `accounts.user_id` is unique. |
| Duplicate identities on retry | Unique email, unique link per legacy user, idempotent completion for the same ticket and email. |
| Lost recovery key and no other device | "Start fresh" replaces the identity key, revokes old devices and deletes server history no one can decrypt any more; contacts see a security-code change. |
| Old apps after cutover | v1 login stays until the owner's cutoff; server-info tells apps the supported API range. |
| Production data | Migrations are additive (new tables only); no existing row is changed or deleted by the deploy. |
| No email provider yet | Production can't register v2 accounts until SES is set up; nothing silently creates unverifiable accounts. |
| Physical-device evidence | Nothing here has run on a phone yet; that needs the owner's HONOR phone and the side-by-side build. |

## 9. Dependency-aware implementation plan

| Stage | Work | Depends on | Status in this round |
|---|---|---|---|
| 2 | Architecture, API contracts, data model, migration rules | 1 | This round |
| 3a | Server: accounts, sessions, email, device setup, workspaces (migration 014) | 2 | This round |
| 3b | Android: account API client, token store, refresh, auth screens, device setup, workspace choice | 3a | This round |
| 4 | Home, DMs, Activity, profile on the new model | 3b and the committed UI-002 | Waits for UI-002 to be committed; this round only wires the workspace list and account settings |
| 5 | 1 GiB files | 3a | This round (limits, server-info, app checks) |
| 6 | Legacy migration (server and app) | 3a, 3b | This round |
| 7 | Cleanup | 6 in production | Nothing to remove (§5); v1 retirement waits for the owner's cutoff |
| 8 | Validation | all | Server tests, Android tests, lint, builds; phone test needs the owner |
| 9 | Documentation | all | This round |
| Later | MongoDB move, iOS app, SES in production, staging environment | Atlas, Apple account, AWS approval | Blocked on the owner |
