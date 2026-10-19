# Reminders (round 6, 2026-10-19)

Reminders are kept and fired by the server, so they arrive on every device the person is signed in on, even after a
phone restart or with the app closed. What a reminder says is sealed on the phone; the server only knows when it is due.

## What people can do

- **Reminders screen** (Home › Reminders card): Upcoming, Overdue (went off and not dealt with) and Done.
- **New reminder**: a title, an optional note, date and time in the phone's time zone, and a repeat (once, every day,
  weekdays, every week, every month).
- **On a reminder**: mark as done, snooze (10 minutes, 1 hour, this evening, tomorrow morning), edit, stop reminding
  (never marks anything as done) or delete.
- **From a message**: long-press › Save for later / Remind me (Home › Later). When the server offers `reminders`, a
  saved message's reminder becomes a server reminder (id `later_<sha256(message id)>`, the same on every device); older
  servers keep the previous on-phone timer (WorkManager).

## How it works

| | |
|---|---|
| Storage | `reminders` and `reminder_occurrences` (migration 018), in the identity domain (SQLite) |
| Content | `sealed`: AES-256-GCM with a key derived by HKDF-SHA256 from the identity seed (salt `meldiq-reminders-v1`, info `enc|<userId>`), associated data `meldiq-reminder-v1|<userId>|<reminderId>`. Every device of the account can read it; the server can't. Code: `crypto/ReminderCrypto.kt` |
| What the server sees | due time (UTC ms), IANA time zone, repeat rule (local `HH:MM` and anchor date), state, fire and delivery times, revision |
| Firing | a durable loop (`REMINDER_TICK_MS`, default 15 s) selects due rows; each occurrence is recorded in `reminder_occurrences` in the same transaction that moves the reminder on, so a restart or a second tick never fires twice. Missed occurrences of a repeating reminder aren't replayed: it fires once and continues from now |
| Delivery | a `reminder.due` WebSocket event (id and times only) to connected devices, and a payload-free wake-up (UnifiedPush/ntfy, or FCM when configured) to the others. The app then fetches its reminders, shows the one that fired from its own decrypted copy (once per occurrence), and acknowledges it (`delivered_at`) |
| Retries | a failed attempt is retried with bounded exponential backoff (30 s doubling, at most 1 hour); after 8 failures the occurrence is marked failed (shown in the app) and the reminder moves on |
| Time zones | instants are UTC; repeating reminders keep their local wall-clock time in their zone across daylight-saving changes (a time skipped by a change lands just after the gap) |
| Concurrency | every change names the revision it started from; a stale one gets 409 with the current copy, and the app retries once on it |
| Limits | 500 waiting reminders per person; sealed content ≤ 6000 characters; done/dismissed reminders are removed after 30 days |

## API (device-authenticated, own reminders only)

`GET /v1/reminders[?state=]`, `GET /v1/reminders/:id`, `POST /v1/reminders` (`{id, sealed, dueAt, timeZone, repeat?}`;
the same id again returns the existing reminder), `PATCH /v1/reminders/:id` (`{rev, sealed?, dueAt?, timeZone?,
repeat?, state?}`; `fired` can't be set by hand), `DELETE /v1/reminders/:id`, `POST /v1/reminders/:id/ack`
(`{firedAt}`). Feature flag: `reminders`. Someone else's reminder answers 404.

## Tests

`server/test/reminders.test.ts` (time maths across DST, weekdays, monthly on the 31st, retries, idempotent firing,
revisions, ownership), `ReminderRulesTest` (lists, snoozes, ids, sealing). Phone checks: `MELDIQ_ROUND6_2026-10-19.md`.

## Not built

Task and project deadlines (Meldiq has no task system; HR tasks come through the People & HR plan,
`HR_INTEGRATION_PLAN.md`), meeting reminders from calendars, quiet hours, and notification actions (Done/Snooze on the
notification itself).
