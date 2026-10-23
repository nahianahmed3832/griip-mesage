# Passkeys (2026-10-23)

A passkey signs in to a Meldiq account instead of its password: the person's password manager (Google Password Manager
on Android) keeps a WebAuthn credential for this server and unlocks it with the screen lock. The owner asked for it after
the Discord walkthrough (Account › "Forget about your password").

## What it does and doesn't do

- **Sign-in only.** A passkey gives the same session a password sign-in gives, after the same checks (account disabled,
  email verified, and the device proof when the phone already holds the account's keys). It never unlocks end-to-end
  encrypted history: a new phone still needs another device, the key backup (its unlock asks for the password or the
  emailed code) or the recovery key.
- **User verification is always required** (`userVerification: "required"`), attestation is not asked for (`"none"`),
  passkeys are discoverable (no email typed first), ES256 and RS256 only, at most 10 per account.
- Every challenge is single use, expires after 5 minutes and belongs to one purpose (adding or signing in) and, when
  adding, to one account.

## Server

| Route | |
|---|---|
| `POST /v2/auth/passkey/options` | sign-in options (discoverable) and a `challengeId`; body `{ rpId? }` |
| `POST /v2/auth/passkey/login` | the platform's answer → session (`SessionGrant`) |
| `GET /v2/account/passkeys` | the account's passkeys and `available` |
| `POST /v2/account/passkeys/options` | create options (excludes passkeys already added); body `{ rpId? }` |
| `POST /v2/account/passkeys` | the platform's answer → the new passkey |
| `DELETE /v2/account/passkeys/:id` | remove one |
| `GET /.well-known/assetlinks.json` | Digital Asset Links for the allowed Android apps |

Verification uses `@simplewebauthn/server` 14.0.2 (`src/modules/accounts/passkeys.ts`). Tables: `account_passkeys`,
`account_passkey_challenges` (migration 045; kept in SQLite in either database mode, deleted with the account).
Tests: `server/test/passkeys.test.ts` with a software authenticator (`test/support/fakePasskey.ts`).

Settings (`deploy/.env.example`):

- `PASSKEY_RP_ID`: the host people reach the server at, e.g. `msg.meldiq.app`. Unset: passkeys are off.
- `PASSKEY_WEB_ORIGINS` (API 0.30.0): web apps on other hosts, e.g. `https://web.meldiq.app`. See "Relying parties".
- `PASSKEY_ANDROID_APPS`: `package=FINGERPRINT` entries. Each becomes an allowed origin
  (`android:apk-key-hash:…`) and an entry of `assetlinks.json`. Production lists `com.meldiq.android` and
  `com.meldiq.android.test` with the debug certificate the test builds are signed with; **add the release signing
  certificate's fingerprint before shipping a release build**, or passkeys won't be offered to it.

The nginx site already passes `/.well-known/assetlinks.json` to the API over HTTPS (only `acme-challenge` is served
on port 80).

## Relying parties (API 0.30.0)

A browser only uses a relying party (RP) that is the page's host or one of its parents, so Meldiq Web at
`web.meldiq.app` can't use passkeys made for `msg.meldiq.app`. The owner chose (2026-10-23) a second RP for the web over
moving everything to `meldiq.app`, which would have invalidated the Android passkeys made that day:

| RP | Origins it accepts | Clients |
|---|---|---|
| `PASSKEY_RP_ID` (`msg.meldiq.app`) | `https://msg.meldiq.app` and each `android:apk-key-hash:…` of `PASSKEY_ANDROID_APPS` | Android (no `rpId` sent) |
| each host of `PASSKEY_WEB_ORIGINS` (`web.meldiq.app`) | that origin only (`https://web.meldiq.app`) | Meldiq Web (`rpId` = its own host) |

- The options requests take `{ "rpId": "web.meldiq.app" }`; without it they use `PASSKEY_RP_ID`, so Android and older
  clients are unchanged. An RP the server doesn't serve is `503 passkey_unavailable`.
- Verification accepts any configured RP with any configured origin, then checks the pair: a passkey's RP (from its
  signed `rpIdHash`) must be the one the reporting origin belongs to, else `passkey_failed`.
- `GET /v1/server-info` has `passkeys: { webOrigins }` (null when passkeys are off). Meldiq Web shows passkeys only when
  its own origin is listed.
- Passkeys of both RPs belong to the same account and are listed together; each works only where it was made (a
  synced one follows the person's password manager, e.g. a Chrome passkey on their other computers). Someone who
  uses both adds one on each. Cross-device sign-in (the QR code to a phone) works within one RP.
- Nothing changes in `assetlinks.json` or the tables: the web RP needs no Digital Asset Links.

## Android

- Sign-in screen: "Sign in with a passkey" (Credential Manager, `PasskeyPrompt.get`).
- Settings › Security › Account: "Passkeys" (list, add, remove) and the "Forget about your password" card while there
  are none (`Passkeys.kt`). Hidden when the server doesn't offer passkeys.
- `AccountRepository.loginWithPasskey`: proves this phone's device key when it has one; if the passkey is another
  account's, asks once more without the proof, and the "this phone holds another account's messages" rule still applies.
- Dependencies: `androidx.credentials` 1.5.0 with its Play services provider.
- From API 0.30.0, as on Meldiq Web: "Sign in with a passkey" shows only when `server-info.passkeys` is set (an
  older server, or one with passkeys off, hides it); removing a passkey asks Keep/Remove first; the sheet says app and
  web passkeys are separate. The prompt runs on the activity behind a sheet's context, and "already has a passkey" and
  "no password manager" get their own messages (the platform's detail goes to the log, tag `Passkeys`).

## Web

Meldiq Web (github.com/nahianahmed3832/meldiq-web) from API 0.30.0: "Sign in with a passkey" on the sign-in screen and
Settings › Account › Passkeys (list, add, remove), with `navigator.credentials` and `rpId` = its own host; hidden unless
`server-info.passkeys.webOrigins` lists the page's origin. A passkey sign-in on the web sets up the browser like a
password sign-in, except the key backup asks for the password (or emailed code) when it's needed.
