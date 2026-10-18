# Orbit 2.0 mobile: implementation report (2026-10-18)

Work for the owner's "Orbit 2.0 — Mobile Application Rebuild" brief, in the worktree `claude/orbit-2-mobile` (based on
`internal-beta` 6f355a1). **Nothing is committed or pushed.** The flows were tested on the owner's HONOR phone against
local servers (§9c); during that test one test account was created on production by mistake (§9c, incident). The audit is `ORBIT_2_MOBILE_AUDIT_2026-10-18.md`; the design and API contracts are
`ORBIT_2_ARCHITECTURE_2026-10-18.md`.

## 1. Result in one table

| Area | Status |
|---|---|
| Repository audit | Done. No WhatsApp-specific code exists; nothing was removed. Nine conflicts between the brief and the current design are written up with options (audit §6) |
| Email-and-password accounts (server) | Done and tested: sign-up, email code, sign-in, refresh with rotation and reuse detection, sign-out, session list and revoke, forgot and reset password, change password, rename |
| Device setup under an account | Done and tested: first identity, add a device (recovery key or pairing), re-bind a returning device by its key, "start fresh" |
| Workspaces | Done and tested on the server and in the app's data layer: personal workspace plus organisations, selection synced to the account. Shown in the app in the new-account start screen; UI-002's Home switcher (now in this branch, §9d) still lists organisations from the app's own saved choice |
| Legacy migration | Done and tested on both sides: recognised installs and fresh installs, retry-safe, recovery key never sent |
| 1 GB files | Done: 1 GiB limit end to end (server config and server-info, app checks, free-space check, 24-hour upload sessions). The S3 multipart pipeline already existed and is unchanged |
| Android app | Account data layer, sign-up/sign-in/code/reset screens, device setup, legacy move, recovery-key screen, workspace start, account settings. With UI-002 merged in: 380 JVM tests pass, lint 0 errors, release and side-by-side builds pass. The main flows passed on the owner's HONOR phone, before (§9c) and after the merge (§9d) |
| iOS | **Not started** (audit C4) |
| MongoDB Atlas | **Not done**: SQLite stays; the account module is behind a store interface (audit C3) |
| Email in production | **Blocked**: needs Amazon SES set up by the owner. Until then v2 sign-up answers 503 |
| Home, DMs, Activity on the new model | The revised UI-002 design (Home, DMs, Activity) is now in this branch, copied from the main checkout's uncommitted work with the owner's approval (§9d). Its workspace switcher still uses the app's saved organisation, not the account's selected workspace |

## 2. Final mobile architecture

```
Android app (Kotlin/Compose)                                   Server (Node/Fastify, one process)
 ui/screens/account  ── AccountFlowViewModel ──┐                 /v2/auth/*, /v2/account/*, /v2/workspaces,
 ui/AppRoot → AccountGate (recovery key,       │                 /v2/migration/legacy/*   (src/accounts)
   workspace start, legacy move) → MainNav     │                       │ AccountStore (SQLite now)
 data/account: AccountRepository, DeviceSetup, ├─ HttpAccountApi ─┐    │ migration 014
   LegacyMigration, WorkspaceRepository        │                  │    ▼
 data/repo/SessionRepository (messaging        │   ApiClient ─────┴──▶ /v1/* unchanged (users, orgs, messages,
   session; reauth = refresh token)            │   (401 → refresh)     files, calls, ws) — a device-bound v2
 crypto/IdentityStore (keys + account sealed)  ┘                       access token authenticates them
```

- An account (email, password, name) signs people in. The encryption identity (identity key, device keys) still decides
  who can read messages; the server still can't.
- After sign-in, a phone without keys sets them up: a new account makes its identity automatically and shows the recovery
  key once; a returning phone proves its device key at sign-in; a new phone uses the recovery key, another phone
  (pairing) or "start fresh".
- Once bound to a device, the v2 access token is the messaging session (`SessionInfo.token`), so every existing screen,
  the realtime connection, history restore, calls and files work unchanged.
- Servers that don't list `accounts-v2` in server-info (production today runs 0.2.0) keep the legacy onboarding.

## 3. Files and modules

**Server (new):** `migrations/014_accounts.sql`; `src/accounts/` — `config.ts` (settings), `passwords.ts` (scrypt),
`mailer.ts` (none/file/SES), `store.ts` (`AccountStore` + SQLite), `sessions.ts` (tokens), `service.ts` (sign-up,
sign-in, codes, passwords), `identity.ts` (device setup, start fresh), `legacy.ts` (migration), `workspaces.ts`,
`schemas.ts` (zod contracts), `routes.ts`, `index.ts`.

**Server (changed):** `context.ts` (v2 tokens authenticate v1 routes; sockets re-check the session), `ws.ts`, `app.ts`
(API 2, version 0.4.0, server-info fields, routes), `config.ts` (file limit, account settings, 24 h upload sessions),
`abuse.ts` (daily sign-up cap counts v2 sign-ups), `routes/auth.ts` (legacy switches, shared device revocation),
`crypto.ts`, `jobs.ts`, `admin.ts`, `package.json` (`@aws-sdk/client-sesv2` 3.1144.0, same version as the S3 SDK).

**Android (new):** `data/account/` — `AccountModels.kt`, `AccountRules.kt`, `AccountApi.kt`, `AccountRepository.kt`,
`DeviceSetup.kt`, `LegacyMigration.kt`, `WorkspaceRepository.kt`; `ui/screens/account/` — `AccountFlowViewModel.kt`,
`AccountOnboarding.kt`, `AccountScreens.kt`, `DeviceSetupScreens.kt`, `LegacyMoveScreens.kt`, `AccountGate.kt`,
`StartScreens.kt`, `AccountSettings.kt`; `res/values*/strings_account.xml` from `android/i18n/strings_account.py`
(English, Chinese, Bengali).

**Android (changed, small):** `IdentityStore` (seals the account record), `SessionRepository` (`MessagingSession`,
reauth through the refresh token, pairing that returns keys), `AppContainer` (wiring), `AppRoot` (one line: the gate),
`Onboarding.kt` (routes to the v2 flow), `ProfileScreens.kt` (account card in Security), `AttachmentPolicy` and
`MessageRepository` (1 GiB, free space), `ApiModels` (server-info fields), `EnvelopeCrypto` (legacy signing string),
`i18n/gen.py` (second strings file).

**Deployment and docs:** `deploy/.env.example`, `deploy/scripts/validate-env.sh`, `docs/SECURITY.md` §2b,
`docs/ARCHITECTURE.md` §10, `README.md`, and the three Orbit 2.0 documents.

## 4. Features: new, changed, removed

- **New:** email-and-password accounts; email verification; password reset and change; sessions with expiry,
  revocation and a list of signed-in apps; device setup after sign-in ("Approve from another phone", "Use my recovery
  key", "Start fresh"); a one-time recovery-key screen; the start choice (just me, create an organisation, join one); the
  personal workspace and synced workspace selection; legacy account migration (recognised and fresh installs); 1 GiB
  files; account card in Settings › Security.
- **Changed:** token renewal (refresh token instead of a device-signed challenge on v2 profiles); onboarding on v2 servers.
- **Removed:** nothing. There is no WhatsApp code (audit §5), and v1 sign-in stays until the owner retires it.

## 5. Authentication and workspace flows

| Flow | What happens |
|---|---|
| New user | Name, email, password → 6-digit code (sent with the password) → signed in → identity made on the phone → recovery key shown once → start choice |
| Returning user, same phone | Email, password; the phone signs the challenge with its device key → straight back in, keys and messages kept |
| Returning user, new phone | Email, password → "Unlock your messages": approve from another phone, recovery key, or start fresh → history restore as before |
| Device removed elsewhere | Sign-in retries without the device proof and re-adds the phone with new device keys under the same identity |
| Session ended (revoked, expired, password reset elsewhere) | Back to sign-in with the email filled in and the server's reason; keys and messages stay |
| Forgot password | Email → 10-character code → new password → every session ends |
| Another account on a phone holding one | Refused until the phone is erased (no mixing of two accounts' messages) |
| Workspaces | `GET /v2/workspaces` = personal + organisations with roles; `PUT /v2/account/preferences` stores the choice; every request still authorised by `authz.ts` |

## 6. Files, S3 and the 1 GB limit

- Limit: **1,073,741,824 bytes of the person's file**, checked by the app before encrypting; the server's ciphertext cap
  defaults to that plus 1 MiB (the chunked format adds 16 bytes per MiB). Server-info's `fileMaxBytes` tells the app; a
  server with a smaller `ATTACHMENT_MAX_BYTES` (production's 25 MB) advertises the matching smaller limit.
- Upload: unchanged and already complete — direct multipart upload to private S3 with 16 MiB parts, each presigned and
  bound to its size and SHA-256, resume by listing parts, per-part retry, cancel, server verification before the
  attachment exists, abandoned-upload cleanup. Sessions now last 24 hours.
- The app refuses early when the phone lacks room for the copy and its encrypted copy.
- Production values (per-file cap, 10 GiB server total, quotas) and their S3 cost are the owner's decision
  (architecture §7). No quotas, plans or paywalls were added; the existing abuse limits stay.
- **Not verified with a real 1 GiB file on a phone or against real S3.** Server tests start a 1 GiB multipart upload
  against the in-process fake S3 (signatures checked) and refuse one byte over.

## 7. Backend and API changes

New routes are listed in architecture §6. Server-info adds `api: 2`, `fileMaxBytes`, an `accounts` block and the
features `accounts-v2`, `workspaces-v2`, `files-1g`, `legacy-migration`. `minClientApi` stays 1, so current apps keep
working. Migration 014 only adds tables. The legacy switches `LEGACY_LOGIN`, `LEGACY_REGISTRATION` and
`LEGACY_MIGRATION` default to on.

## 8. Legacy migration

- **Recognised install** (signed in the old way): a prompt "Orbit has a new sign-in" with the username shown; the
  recovery key is checked against this phone's identity first, then signed into a challenge; name, email, password; the
  emailed code; then a sign-in with this phone's own device key. Same user id, same device, same database. "Later" is
  offered while the server still accepts the old sign-in.
- **Fresh install:** "Move an existing Orbit account" on the sign-in screen (never the default): username and recovery
  key, the same steps, then the phone sets itself up from the recovery key and restores history.
- Safe to retry; a failed proof links nothing; an unverified link can be redone with another address; a verified
  address can't be taken over. A mistyped address can be replaced from the code screen ("Use another one") or by
  starting the move again. If the app stops between the code and the sign-in, the recognised install shows a "finish"
  sign-in.
- **Unresolved, owner's decision:** the retirement dates for v1 sign-up, sign-in and migration; whether a phone that holds
  the identity key but whose owner lost the recovery key may migrate without typing it (audit C2/brief §4 asks for the
  key, so it's required now); merging a legacy account into a separate v2 account someone already made with the same
  email (refused today with `email_taken`).

## 9. Tests and results (run on this Mac, 2026-10-18)

| Check | Result |
|---|---|
| Server typecheck (`tsc --noEmit`) | Pass |
| Server tests (vitest, full suite) | **319 passed, 9 skipped** (the live S3 tests, which run only on request). New: `accounts_auth` 21, `accounts_devices` 13, `legacy_migration` 9, `files_1g` 6, `accounts_invites` 2 |
| Server build | Pass |
| Mutation checks | 11 of 11 deliberately broken checks caught (legacy signature, refresh reuse, workspace membership, device certificate, code comparison, verified-email gate, per-frame socket re-check, invite use, password with the code, reuse revocation, joining the admitting organisation) |
| Android unit tests (`testDebugUnitTest --rerun`) | **376 passed, 0 failed** (before the phone-test fixes; the account tests, now 40, pass after them). New: `AccountRepositoryTest` 14, `AccountFlowViewModelTest` 9, `AccountApiContractTest` 6, `LegacyMigrationTest` 5, `AccountRulesTest` 3, `WorkspaceRepositoryTest` 2; `AttachmentPolicyTest` updated |
| Android lint | 0 errors, 82 warnings, none in the new files |
| Android `assembleDebug` and `assembleRelease` (R8, unsigned) | Pass |
| Room schema files | Unchanged |
| Physical phone | **Run on 2026-10-18** on the HONOR phone (side-by-side Orbit Test, local servers): see §9c, and §9d after merging UI-002 |
| iOS build | **Not applicable**: no iOS project exists |

Covered by automated tests: registration and email verification; sign-in and sign-out; password reset and change;
expired, revoked and reused sessions; failed, successful and repeated legacy migration; reinstall recovery (recovery
key, re-binding a returning device, start fresh); personal workspace, organisation creation and joining, switching,
roles enforced on the server, cross-organisation access refused; realtime delivery between a v2 and a v1 account and the
socket surviving token renewal; file-size limits, multipart start, cancel and unauthorised access to uploads.

Not covered: the Compose screens themselves (no UI tests), SES sending (only the transport selection is tested), real
S3, real 1 GiB transfers, and anything on a phone.

## 9c. Phone test (2026-10-18, HONOR ELI-AN00, Orbit Test side-by-side build)

Setup: the side-by-side debug build of this branch (`com.griipbuddy.Orbit.test`, installed over UI-002's build with its
data kept; that session's profile was not used). A new app profile pointed at a local server on this Mac through
`adb reverse`. To test a real upgrade, the profile first ran against the **internal-beta 0.3.0 server**, then the same
data directory was served by **this branch's 0.4.0** (migration 014 applied to existing data), `MAIL_TRANSPORT=file`.
A reference-client script added a bot that sent a DM and a group message, and a second legacy account.

| Flow | Result |
|---|---|
| Legacy sign-up on the 0.3.0 server (app shows the old onboarding there) | Pass |
| After the upgrade: "Orbit has a new sign-in" on the recognised install, username filled in, "Later" | Pass ("Later" opens the app; the prompt returns at the next start) |
| Recovery key of another account | Refused on the phone, no request sent (server log) |
| Right key → email and password → emailed code → signed in with the same device | Pass: same user and device, chats and group kept, v2 session bound to the original device |
| Messaging after the move (bot → phone live, phone → bot) | Pass |
| Account card in Security: email, change password, signed-in apps | Pass (password change 200) |
| Sign out → sign in: old password refused, new one signs in with the device key | Pass (no new device) |
| Sign out & erase → sign in as a new phone → "Unlock your messages" → recovery key | Pass: device restored, encrypted history restored from the server |
| Fresh-install move ("Move an existing Orbit account") with @phonelegacy2 | Pass: phone set itself up from the key without asking again |
| New sign-up → code → recovery key shown once → start choice → create organisation | Pass: owner of the new organisation, selected as the workspace |

Defects found on the phone and fixed in this branch:

- System Back on the migration prompt, the recovery-key screen and the start choice closed the app. Now: the prompt acts
  like "Later" (when allowed), the recovery key waits for "I've saved it", the start choice falls back to the personal
  workspace. **Rechecked on the phone** for the recovery-key screen and the start choice; the migration prompt's Back
  was **not** rechecked (the attempt is what caused the incident below).
- The migration banner named a settings path that doesn't exist; it now matches the app (all three languages).
- The recovery key wrapped in the middle of a group; it now shows four groups a line (rechecked).
- Choosing the personal workspace before the workspace list loaded wasn't sent to the server (unit-tested fix).

Seen but not changed (existing behaviour): "Sign out & erase" resets that profile's server address to the default
(production), so a test profile must be pointed back at the test server each time.

**Incident: one test account created on production.** While setting up a third profile for the migration-prompt Back
recheck, the typed server address didn't land in the field (the sheet had moved), the profile kept the default server,
and its legacy sign-up created **@phonelegacy3 on `msg.griipbuddy.cloud`** (production 0.2.0) at about 15:29. Production
confirms the username is taken. The app was switched back to the local profile at once, so that profile is inactive.
Nothing else in production was written by this work. Removing or disabling the account is the owner's decision
(for example `admin.js disable phonelegacy3` on the VPS, which needs approval).

## 9d. UI-002 merged in, and the phone retest (2026-10-18, afternoon)

The phone build in §9c was made from this branch alone, so it showed the first UI-002 (four tabs) instead of the
revised workspace Home the owner had approved. With the owner's approval, the revised UI-002 was copied into this
worktree from the main checkout's **uncommitted** work. The main checkout was only read, and nothing in it changed.
Snapshot: `git diff` of 22 tracked files plus 10 new files, at about 15:40; the main checkout's `CLAUDE.md` was left
out. The owner's version bump came with it: the phone build was 0.1.5-beta (code 6), and the commit carries the
owner's later bump to 0.2.0-beta (code 7). UI-002 is committed on its own, before the account work.

- Only `ui/AppRoot.kt` was changed on both sides. The merge keeps UI-002's `SignedInPalette` and three tabs, and puts
  `AccountGate` around `MainNav` as before.
- Strings regenerate unchanged: `strings.xml` is identical to the main checkout's, and `strings_account.xml` keeps 107
  keys per language.
- Changed to fit UI-002's "Cocoa on every signed-in screen" rule: the account steps shown after sign-in (recovery key,
  start choice, legacy move) are now drawn in Cocoa. Before, they forced Ivory, which inside the Cocoa layer would have
  given light status-bar icons on a light screen. Signed-out screens (sign-in, sign-up, code, device setup) stay Ivory.
- Checks after the merge: 380 JVM tests passed and 0 failed; lint 0 errors and 84 warnings (82 before), none in
  the account files; `assembleRelease` (R8) and the side-by-side `assembleDebug` pass.

Phone retest (side-by-side Orbit Test 0.1.5-beta installed over the old one with its data kept; the Play-installed
Orbit was not touched). Before anything was submitted, every new profile's server address was checked on screen; one
attempt typed before the server sheet had opened, and the check caught it.

| Flow | Result |
|---|---|
| Home (workspace switcher, Unreads/Drafts/Calls cards, Unread DMs, Mentions), DMs, Activity, profile menu | Pass, Cocoa |
| Account card in Privacy & Security › Recovery key & safety numbers | Pass |
| Sign out → sign in (Ivory) → Home | Pass |
| New sign-up → emailed code → recovery key (Cocoa) → start choice (Cocoa) → create organisation | Pass: Home opens on the new organisation |
| Same account on a fresh profile → "Unlock your messages" → recovery key | Pass (the first try failed with "Can't reach the server" because USB dropped the `adb reverse`; after restoring it, the restore went through) |
| Legacy account made on the 0.3.0 server, data upgraded to 0.4.0 → prompt (Cocoa) | Pass |
| System Back on the migration prompt (left open in §9c) | Pass: acts as "Later"; the prompt returns on the next start |
| Move: key → email and password → code | Pass: same user, one device, one session, email on the account card |

Defect found and fixed: the arrow on the recovery-key screen acted like "I've saved it" and skipped the screen. It is
now disabled, like system Back. **Rechecked on the phone** with another sign-up.

Test accounts made in this retest are all on local servers: `phonenewthree…` and `phonenewfour…` on 18090, and
`@phonelegacy4` on a scratch data directory served on 18092 (now stopped).

## 9e. "Updated from Play, but nothing asked me to move" (2026-10-18, evening)

The owner built a signed AAB, put it on Play internal testing and updated the phone. The app showed the new
Home/DMs/Activity design but never asked for an email and password. There were three causes:

1. **The AAB didn't contain the account code.** It was built in Android Studio from the main checkout at 16:33. That
   checkout is still on `6f355a1` plus the uncommitted UI-002 files, while the account work is only on `internal-beta`
   (`5e3b72f`). Checked in the bundle: no `/v2/auth/login`, no `/v2/migration/legacy/verify`, no
   `griip-legacy-migration-v1`, no `acct_*` strings, but `nav_activity` (UI-002) is there.
2. **Production doesn't offer the move.** `msg.griipbuddy.cloud` still runs API 0.2.0: server-info has no `accounts`
   block and no `legacy-migration` feature, so even a correct app keeps the old sign-in, by design.
3. **The move was never mandatory.** The app offered "Later" whenever the old sign-in still worked.

Fixed in this branch:

- New server switch **`LEGACY_MIGRATION_REQUIRED`** (default `off`), reported as `accounts.legacyMigrationRequired`.
  With it on, an updated app shows the move before anything else, with no "Later", and Back and the arrow do nothing.
  Old app versions keep working until they update. The server refuses to start with it on while
  `LEGACY_MIGRATION=off`. It reports it only while account email works, and the app double-checks, so nobody can be
  stuck at the code step. `validate-env.sh` checks it.
- Apps offer the move only while the server can email codes (`accounts.registration`). Production 0.4.1 (UI-003
  deploy) lists `legacy-migration` with email still off, and a prompt there would end in an unsendable code.
- The rule now lives in `data/account/MigrationWindow.kt` and is tested in `MigrationWindowTest`. Server tests are in
  `legacy_migration.test.ts`.
- App version 0.2.1-beta (code 8), since Play already has code 7.
- Checks: server 323 passed and 9 skipped (live S3); Android 386 JVM tests passed and 0 failed (with the Home fix
  below); lint 0 errors, 84
  warnings (unchanged); `assembleRelease` and the side-by-side `assembleDebug` pass.

Phone check (HONOR, side-by-side Orbit Test 0.2.1-beta on fresh app data; the Play-installed Orbit 0.2.0-beta was
not touched). A legacy account `@phone.legacy.five` was made on the 0.3.0 server. The same data was then served by
this branch with `LEGACY_MIGRATION_REQUIRED=on` and `MAIL_TRANSPORT=file`:

| Check | Result |
|---|---|
| App start after the upgrade | Pass: "Orbit has a new sign-in" before anything else, with **no "Later"** and the arrow disabled |
| System Back and the arrow on the prompt | Pass: both stay on the prompt; the app doesn't close |
| Move: recovery key → email and password → emailed code | Pass: same user, one device, email verified, the app opens on Home |
| Restart after the move | Pass: no prompt |
| A bot sends 2 DMs and a group message | Pass: Home shows both chats as unread (2 and 1), DMs badge 3 |
| Replies from the phone in the DM and the group | Pass: the bot receives and decrypts both |
| Read state | Pass: Home "All caught up", DMs list shows the replies, Activity "Nothing new" |
| Create a workspace from Home, switch between two | Pass after the fix below |
| Sign out → sign in with the new email and password | Pass: back on the selected workspace, no prompt |

Bug found and fixed: **a workspace created or joined from Home didn't appear on Home until the app restarted.** The
create and join screens select the new workspace, but Home's list was loaded earlier. Home now reloads its list once
whenever the selection names a workspace it doesn't know (`WorkspaceHomeRules.selectionUnknown`, tested in
`WorkspaceHomeRulesTest`). Rechecked on the phone: a second workspace appears on Home at once, and switching works.

Seen, not changed:
- Home's "Unreads" card counts chats (2), while the DMs tab badge counts messages (3). That is UI-002's design.
- On a required move, system Back does nothing; it doesn't put the app in the background.
- The account's server-side selected workspace isn't updated by Home's switcher, which still uses the app's saved
  choice (known follow-up).

What has to happen for the phone to see it (each step needs the owner):

1. Build the signed AAB from code that has the account work: `internal-beta` once this fix is pushed. The main
   checkout must first be brought up to `internal-beta`. Its uncommitted files are already all in `internal-beta`.
2. Deploy API 0.4.0 or later to production, with a database backup first; migration 014 is additive.
3. Email: SES production access (console), then the sending key and `.env` (`MAIL_TRANSPORT=ses`, `MAIL_FROM`), per
   docs/ORBIT_SES_SETUP_2026-10-18.md steps 6–8.
4. Then `LEGACY_MIGRATION_REQUIRED=on`. Before production access, only addresses verified in SES can get codes. That is
   enough for the owner's own test if their address is verified as a sandbox recipient.

## 9b. Independent review and what it changed

A separate review of the new code (run by a review agent, with probes against an in-process server) found 12 issues.
All were fixed except where noted, each fix with a test:

| # | Finding | Fix |
|---|---|---|
| 1 High | A later sign-up could replace an unverified account's password, and the owner's code then verified the attacker's password | Verification needs the code **and** the sign-up password; an unverified account's password changes only together with a new code |
| 2 High | v2 sign-up checked the invite but never used it up | The invite use is taken in the account's own transaction; an organisation invite joins its organisation when the identity is made |
| 3 High | On the phone, another account could sign up or move onto a profile holding one account's keys and messages | One account per profile enforced in sign-up, move and sign-in; keys made for an account are reused only by it; a recognised install can't sign in to another account |
| 4 Medium | Used-up code attempts answered 429 only for addresses with a code | Every code failure answers the same 400 |
| 5 Medium | Refresh-token reuse was caught only one generation back | Refresh tokens name their session; any older token revokes it |
| 6 Medium | A lost refresh answer signed the person out | The just-replaced token works once more for 30 s |
| 7 Medium | Sign-up attempts could use up someone's reset emails; failed sign-ins could lock them out | Code allowances per purpose, a separate notice allowance, 30 emails per network per hour, a reset clears the throttle. **Still open:** the account-wide throttle (50 failures an hour) applies before the password check, so a distributed guesser can delay sign-in by an hour at a time (SECURITY.md §2b) |
| 8 Medium | A legacy move with a mistyped email could trap the app | "Use another email" / "Start the move again"; "Later" holds at every step while the old sign-in works |
| 9 Low | Sign-up limit counters only counted new addresses | Every accepted sign-up counts for its network |
| 10 Low | Refresh shared the strict per-network limit | Refresh gets 10× the limit |
| 11 Low | An expired sign-in challenge looked like an unusable device | Its own `challenge_expired` code; the app asks again once |
| 12 Low | The name typed at sign-up went into emails to any address | Emails contain nothing anyone typed |

## 10. Blockers and dependencies

| Blocker | Needs | Cost |
|---|---|---|
| Email for v2 sign-up in production | Deployed 2026-10-18: the SES identity, configuration set and sender user (stack `orbit-mail-prod`, `deploy/aws/orbit-mail.yaml`), the boundary change (`orbit-ops-admin`), and the DNS records on griipbuddy.cloud. Still needed: the owner requests SES production access; the access key, `MAIL_TRANSPORT=ses` and `MAIL_FROM` come with the 0.4.0 deploy (`docs/ORBIT_SES_SETUP_2026-10-18.md` §4, §8) | SES about US$0.10 per 1,000 emails (list price; check before use) |
| Deploying API 0.4.0 | Owner's approval, backup first, migration 014 is additive | — |
| MongoDB Atlas | Owner: cluster in AWS ap-southeast-1, network access from the VPS, credentials in the server environment; then a staged move | M10 roughly US$60/month (list price) |
| iOS | Decision on Kotlin Multiplatform vs native, an Apple developer account (US$99/year), an iPhone for testing, APNs | — |
| Home/DMs/Activity on the new workspace model | UI-002 is in this branch (§9d); its switcher still has to read and write `WorkspaceRepository`. UI-002 should be committed on its own first so this branch's history stays split | — |
| Staging | A second Compose project, DNS name and nginx site | VPS resources |
| Account deletion branch (`claude/orbit-round-4-8f76de`) | Merge it with this work; deleting a `users` row already cascades to its account | — |

## 11. Environment variables

New server settings (all in `deploy/.env.example`, checked by `validate-env.sh`): `APP_ENV`, `MAIL_TRANSPORT`,
`MAIL_FROM`, `MAIL_FILE_DIR`, `SES_REGION`, `SES_ACCESS_KEY_ID`, `SES_SECRET_ACCESS_KEY`, `PASSWORD_SCRYPT_LOG_N`,
`ACCESS_TOKEN_TTL_SECONDS`, `REFRESH_TOKEN_IDLE_DAYS`, `SESSION_MAX_DAYS`, `AUTH_RATE_LIMIT_PER_MINUTE`,
`ACCOUNT_EMAILS_PER_NETWORK_PER_HOUR`,
`LEGACY_LOGIN`, `LEGACY_REGISTRATION`, `LEGACY_MIGRATION`, `LEGACY_MIGRATION_REQUIRED`, `FILE_MAX_BYTES`. Changed defaults: `ATTACHMENT_MAX_BYTES`
(now `FILE_MAX_BYTES` + 1 MiB), `DIRECT_UPLOAD_SESSION_SECONDS` (24 h).

Local development with account emails written to disk:

```bash
cd server && npm ci && npm run build
DATA_DIR=/tmp/orbit-dev REGISTRATION_MODE=open MAIL_TRANSPORT=file PORT=18090 node dist/index.js
```

Verification codes then appear in `/tmp/orbit-dev/mail/*.eml`. The Android build is unchanged (`README.md` › Build).

## 12. Steps that need deliberate operator action

1. Back up the production database (`admin.js backup`), then deploy API 0.4.0 when approved. Migration 014 adds four
   tables and changes no existing row.
2. Set `APP_ENV=production`. Leave `MAIL_TRANSPORT=none` until SES is ready (v2 sign-up answers 503; old apps keep
   working).
3. Decide production file limits: `ATTACHMENT_MAX_BYTES` is pinned to 25 MB in production's `.env`; remove it (or set
   `FILE_MAX_BYTES`) and raise `ATTACHMENT_TOTAL_BYTES` together, after checking the S3 cost.
4. When the Orbit 2.0 app ships: `LEGACY_REGISTRATION=off`. At the cutoff the owner sets: `LEGACY_LOGIN=off`, then
   `LEGACY_MIGRATION=off`.
5. To force the move on updated apps (§9e): `LEGACY_MIGRATION_REQUIRED=on`, only once SES has production access and
   `MAIL_TRANSPORT=ses` works. Otherwise people outside the SES sandbox's verified addresses can't get their code.
