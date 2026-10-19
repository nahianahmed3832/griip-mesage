# Account recovery codes (round 6, 2026-10-19)

A way back into a Meldiq account for someone who lost access to the email address it uses, without a backdoor.

## Two different things

| | Restores | Where it lives |
|---|---|---|
| **Recovery codes** (this document) | access to the **account**: its email address and password | hashed on the server |
| **Encryption recovery key** (existing) | the **encryption identity**, so encrypted history can be read on a new phone | only with the person |

Recovery codes never decrypt anything. After recovering an account, a new phone still needs the encryption recovery
key or another signed-in phone; without either, "Start fresh" makes new keys and old encrypted history stays unreadable.

## Making codes (Settings › Security › Recovery codes)

- Ten codes, each 12 characters of a 31-letter alphabet (about 59 bits), shown once as `ABCD-EFGH-JKLM`. The screen is
  marked secure (no screenshots or recent-apps preview) and "Copy" marks the clipboard entry sensitive.
- Stored as SHA-256 of `<account id>:recovery:<code>` (migration 017); a new set replaces the old one; both making and
  revoking a set need the password again; the account's email is told when a set is made.

## Recovering (sign-in › Lost access to your email?)

1. The account's current address, one recovery code and the new address. The code is checked in constant time against
   the account's set and **used up** here. A 6-digit code goes to the new address; a 30-minute single-use ticket
   (stored hashed) carries the recovery on.
2. That 6-digit code and a new password (same rules as sign-up). The address and password change together, every
   session of the account is signed out, login throttles are cleared, the old address is told (masked new address) and
   the new one gets a confirmation with the number of codes left.

Refusals: an unknown address, a wrong, used or revoked code all get the same answer (`invalid_recovery`); wrong codes
are throttled per address and network (5 in 15 minutes) and per address (20 a day); a wrong email code counts toward 5
tries; an address another account uses is refused (`email_in_use`) without using the code up.

Not accepted as proof, by design: birth dates, employee ids, job titles, profile details or security questions.

## API

`GET /v2/account/recovery-codes` (counts only), `POST /v2/account/recovery-codes` (`{password}` → codes, once),
`POST /v2/account/recovery-codes/revoke` (`{password}`), `POST /v2/auth/recover/start` (`{email, code, newEmail}`),
`POST /v2/auth/recover/complete` (`{ticket, code, newPassword}`). Feature flag: `recovery-codes`. Audit events:
`account.recovery_codes_created`, `account.recovery_codes_revoked`, `account.recovery_started`, `account.recovered`
(never the codes).

## Needs

Account email (MAIL_TRANSPORT ses or smtp): the new address is proven with an emailed code. Production sends through
SES today; SMTP waits for the Hostinger mailbox (`EMAIL.md`).

## Tests

`server/test/account_recovery.test.ts` (generation, hashing, single use, revocation, throttling, uniform errors, ticket
replay, wrong email codes, sessions signed out, audit), MongoDB store in the MongoDB suite, `ReminderRulesTest` (code
input rules).
