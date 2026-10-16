# ADR 0004: Call reachability without push

Date: 2026-10-16. Status: accepted. Details: `../CALLING_AND_E2EE.md`.

## Context

Calls often didn't reach the other person. The server rings only devices with a live WebSocket (`hub.sendToUser`), and the app
never asked for ringing calls after reconnecting, so a ring sent while a device was reconnecting was lost. The caller also couldn't
tell "invite sent" from "their phone is ringing", and a busy callee looked like an unanswered one.

There is no push service: the app deliberately has no FCM and no global push credential (D-08), and UnifiedPush needs a distributor
app and server infrastructure the deployment doesn't have. A WebSocket can't wake an app that Android has stopped.

## Decision

- **Ring recovery:** after every reconnect, once the backlog has drained, the app asks `GET /v1/calls/active` and rings any call that
  is still `RINGING`, wasn't started by this user, isn't already answered by one of their devices, isn't already handled, and is
  younger than the 45 s ring window.
- **Alerted:** a device that shows a ring calls `POST /v1/calls/:id/alerted` (idempotent; migration `003_call_alerted.sql` adds
  `calls.alerted_at`). The caller gets an `alerted` event and the call screen changes from "Calling…" to "Ringing…".
- **Busy:** a device already in a call declines a new 1:1 ring with `reason: "busy"`; the call ends as `busy` and the caller sees "Busy".
- **No push now.** Delivery to a stopped app stays impossible and is documented, not hidden. A push provider can be added behind the
  same ring path later (server-side provider interface, no credential in the app).

## Consequences

- Rings reach phones whose app is running or kept connected by the optional foreground service, including across reconnects.
- A phone whose app was force-stopped, or that Android has frozen, still misses calls (`../BACKGROUND_DELIVERY.md`).
- The new fields and endpoint are additive: older apps ignore `alertedAt`, and older servers return 404 for `/alerted`, which the app
  ignores.
