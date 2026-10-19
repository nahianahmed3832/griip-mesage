# Account email (verification, password reset, notices)

The server sends three kinds of account email (`server/src/accounts/mailer.ts`, `mailTexts`): the sign-up verification
code, the password-reset code, and the "someone tried to register with your email" notice. Plain text, no links, nothing
anyone typed. Codes are short-lived (15 minutes), single-use and attempt-limited; resend and recovery answers are
generic; sending is rate-limited per network (`ACCOUNT_EMAILS_PER_NETWORK_PER_HOUR`). No transport ever logs a body.

## Transports (`MAIL_TRANSPORT`)

| Value | Use | Settings |
|---|---|---|
| `none` | default: sign-up and reset answer 503 | — |
| `file` | development only (refused in production): one `.eml` file per message, mode 0600 | `MAIL_FILE_DIR` |
| `ses` | Amazon SES (production since 2026-10-18, sandbox) | `MAIL_FROM`, `SES_REGION`, optional `SES_ACCESS_KEY_ID`/`SES_SECRET_ACCESS_KEY` |
| `smtp` | any SMTP provider; **Hostinger Mail** for development and early testing (2026-10-19) | `MAIL_FROM` (or `MAIL_FROM_NAME` + `MAIL_FROM_ADDRESS`), `SMTP_HOST`, `SMTP_PORT` (465), `SMTP_SECURE`, `SMTP_USER`, `SMTP_PASS` |

SMTP is always encrypted: TLS from the first byte on port 465, a required STARTTLS upgrade on any other port, TLS 1.2 or
newer with the certificate checked, 15-second timeouts, nodemailer's logging off (`smtpTransport`). Swapping providers
(Hostinger → SES, Resend, Postmark…) is a change of environment variables only; authentication code never calls a
provider directly. Tests: `server/test/mail_smtp.test.ts` (configuration guards, TLS settings, sending, error pass-through).

## Hostinger Mail for `no-reply@meldiq.app` — status 2026-10-19

The owner chose the sender **`Meldiq <no-reply@meldiq.app>`** (the brief's `meldeq.app` isn't a domain the account owns;
`meldiq.app` is, bought 2026-10-19).

| Step | Status |
|---|---|
| Domain `meldiq.app` in the Hostinger account, Hostinger DNS | done (bought 2026-10-19; `web.meldiq.app` already points at the VPS) |
| Email plan | a **Starter Business Email** order (1 mailbox seat, IMAP/POP3, 1,000 outgoing messages per day per mailbox) came with the domain and is **"pending setup", attached to no domain** |
| Attach the plan to `meldiq.app` | **owner action in hPanel** (Emails › set up the pending plan › choose `meldiq.app`): the Hostinger API offers no operation for it. hPanel also adds the MX, SPF and DKIM records when the domain uses Hostinger DNS |
| Create mailbox `no-reply@meldiq.app` | after the step above: `mail_mailboxes_create-mailbox` (API) with a generated password, stored only in the server's `.env` |
| SMTP settings | read from hPanel for the mailbox once it exists (Hostinger's documentation gives `smtp.hostinger.com`, port 465 with SSL/TLS; confirm there, don't assume) |
| SPF / DKIM / DMARC | check the records hPanel created (`dns_records_list`); add `_dmarc` TXT `v=DMARC1; p=none; rua=mailto:…` first, tighten to `quarantine` after a week of clean reports — **needs the owner's go-ahead (DNS change)** |
| Delivery test | send a verification code to a team address from a staging/local server with `MAIL_TRANSPORT=smtp`; check headers for DKIM=pass, SPF=pass |
| Production | production keeps SES until the owner switches; switching is `MAIL_TRANSPORT=smtp` + the SMTP variables in the server `.env`, then restart the API service only |

Hostinger's limits (1,000 messages/day, 100 recipients per message) are fine for early testing; a transactional
provider (SES out of the sandbox, Resend) is the path for public launch.
