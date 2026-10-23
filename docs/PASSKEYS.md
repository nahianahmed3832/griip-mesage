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
| `POST /v2/auth/passkey/options` | sign-in options (discoverable) and a `challengeId` |
| `POST /v2/auth/passkey/login` | the platform's answer → session (`SessionGrant`) |
| `GET /v2/account/passkeys` | the account's passkeys and `available` |
| `POST /v2/account/passkeys/options` | create options (excludes passkeys already added) |
| `POST /v2/account/passkeys` | the platform's answer → the new passkey |
| `DELETE /v2/account/passkeys/:id` | remove one |
| `GET /.well-known/assetlinks.json` | Digital Asset Links for the allowed Android apps |

Verification uses `@simplewebauthn/server` 14.0.2 (`src/modules/accounts/passkeys.ts`). Tables: `account_passkeys`,
`account_passkey_challenges` (migration 045; kept in SQLite in either database mode, deleted with the account).
Tests: `server/test/passkeys.test.ts` with a software authenticator (`test/support/fakePasskey.ts`).

Settings (`deploy/.env.example`):

- `PASSKEY_RP_ID`: the host people reach the server at, e.g. `msg.meldiq.app`. Unset: passkeys are off.
- `PASSKEY_ANDROID_APPS`: `package=FINGERPRINT` entries. Each becomes an allowed origin
  (`android:apk-key-hash:…`) and an entry of `assetlinks.json`. Production lists `com.meldiq.android` and
  `com.meldiq.android.test` with the debug certificate the test builds are signed with; **add the release signing
  certificate's fingerprint before shipping a release build**, or passkeys won't be offered to it.

The nginx site already passes `/.well-known/assetlinks.json` to the API over HTTPS (only `acme-challenge` is served
on port 80).

## Android

- Sign-in screen: "Sign in with a passkey" (Credential Manager, `PasskeyPrompt.get`).
- Settings › Security › Account: "Passkeys" (list, add, remove) and the "Forget about your password" card while there
  are none (`Passkeys.kt`). Hidden when the server doesn't offer passkeys.
- `AccountRepository.loginWithPasskey`: proves this phone's device key when it has one; if the passkey is another
  account's, asks once more without the proof, and the "this phone holds another account's messages" rule still applies.
- Dependencies: `androidx.credentials` 1.5.0 with its Play services provider.

Web: not yet. A web client on another host would need the relying party to be a common parent domain (`meldiq.app`)
with assetlinks served there.
