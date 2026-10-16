# Call history in conversations

Added 2026-10-17. Every call in a personal or group conversation leaves one line in that conversation saying what
happened: completed (with how long), missed, declined, busy, no answer, cancelled, failed, or still in progress.

## Where the facts come from

Only the server's own record of the call: who started it, when it was answered (two different people connected), when
and why it ended, and who connected and for how long. Nothing is estimated: a duration is shown only when both of its
ends were recorded.

| Server (`server/src/routes/calls.ts`) | |
|---|---|
| `callView(...).attended` | Ended calls only: one entry per person who connected (`userId`, `joinedAt`, `leftAt`, `seconds`), overlapping devices counted once. People who only rang never appear. Older apps ignore the field |
| `GET /v1/conversations/:id/calls?before=&limit=` | Members only (404 otherwise), newest first, at most 100. Only calls since the caller became a member, so someone added later doesn't see earlier calls |

Reconnecting on the same device keeps one record (the LiveKit webhook resets `joined_at`), so a person's time is a
close lower bound. Calls hosted by another server (federation) have no attendance on the mirror; for 1:1 calls the
outcome uses answer and end times, which are mirrored.

## The app

- One entry per call, id `call-<callId>`, so repeated events and syncs update it in place (never a duplicate). It is
  written when the call's encrypted key arrives (as before) or from the server's record, whichever comes first.
- Updated from live call events (`ring`, `started`, `ended`), from the decline response, and from the conversation's
  call list each time the conversation opens (for calls that ended while the phone was offline).
- `CallHistory.summarize` (unit-tested in `CallHistoryTest`) turns the record into an outcome:

| | Caller sees | Callee sees |
|---|---|---|
| Answered | Outgoing call · duration | Incoming call · duration |
| Declined | Call declined | You declined a call |
| Busy (callee on another call) | Not answered · they were on another call | Missed call |
| Ring timeout | No answer | Missed call |
| Caller hung up first | Cancelled call | Missed call |
| Error / connection lost before answer | Call failed | Missed call |

  Group calls use attendance: you connected → the group call with **your** time and how many people joined; you
  didn't → missed (or declined, if this phone declined it); you started it and nobody came → no one joined. A server
  from before 2026-10-17 doesn't say who connected, so group calls there show "Group call ended", never a guessed
  "missed".
- A call whose start request failed never reached the server: a local "Call failed" line keeps the attempt visible.
- Missed calls are drawn in red; the chat list preview says the outcome ("📞 Missed voice call").

Entries are local rows in the encrypted database; nothing new is sent between phones. Call history needs no database
migration.

## Deployment

`attended` and the per-conversation list need the server change (service `api`). Against the current production server
the app still shows 1:1 outcomes and durations from live events; group outcomes show "ended", and calls that ended
while the phone was offline aren't caught up until the server is updated.
