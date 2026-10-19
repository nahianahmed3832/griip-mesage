# Firebase Cloud Messaging wake-ups (round 6, 2026-10-19): server ready, off

Owner's decision: "Firebase: code prep only". The server side is built and tested against a fake Google endpoint; it is
**off** until a Firebase project exists. Push wake-ups today go through UnifiedPush/ntfy (`PUSH_SERVER.md`).

## What it does

Exactly what UnifiedPush wake-ups do, through FCM: a data-only message `{"w":"1"}` (high priority) to a device with no
open connection when something is queued for it, a call rings or a reminder fires. The app then connects and fetches
everything itself. Google learns that a device was woken and when, never what for. Clients can only register or remove
their own device's token; only the server decides whom to wake.

| | |
|---|---|
| Code | `server/src/push/fcm.ts`, routes `PUT/DELETE /v1/devices/current/fcm`, migration 020 (`fcm_tokens`) |
| Auth to Google | OAuth 2.0 JWT bearer (RFC 7523) signed RS256 with the service account's key, scope `firebase.messaging`, cached for its hour |
| Send | `POST https://fcm.googleapis.com/v1/projects/<id>/messages:send` |
| Clean-up | `UNREGISTERED`/404, `SENDER_ID_MISMATCH` or an invalid token removes it; failing for 7 days removes it; signing out of push removes it |
| Pacing | ordinary wake-ups at most once per `FCM_WAKE_INTERVAL_SECONDS` (30) per device; rings and reminders don't wait |
| Feature flag | `push-fcm`, only when configured |

## Owner steps to turn it on

1. Create a Firebase project (no paid plan needed for FCM) and add the Android app `com.meldiq.android`.
2. Create a service account with the role **Firebase Cloud Messaging API Admin** only, and a JSON key. Keep the key file
   out of the repository; put it on the VPS (`chmod 600`) and mount it read-only into the API container.
3. Set `FCM_PROJECT_ID` and `FCM_SERVICE_ACCOUNT_FILE` (see `deploy/.env.example`), run `validate-env.sh`, restart the
   API service only.
4. Android: add the `firebase-messaging` library and the project's `google-services.json` (client config, not a
   secret, but specific to the project), register the token with `PUT /v1/devices/current/fcm` and treat a message as
   a wake-up. **Not done in this round**: without a project there is nothing to build against, and the library would
   add size for nothing.

## Tests

`server/test/push_fcm.test.ts`: configuration checks, the signed assertion (verified with the public key), the exact
message (no content), pacing, connected devices skipped, token removal and keeping. Delivery to a real phone is not
verified (no project).
