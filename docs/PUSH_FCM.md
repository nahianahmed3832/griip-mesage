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

1. **Done 2026-10-20:** Firebase on the Google Cloud project `meldiq` (Spark, Analytics off).
   - Android apps: `com.meldiq.android` and `com.meldiq.android.test`.
   - The FCM API (V1) is enabled.
2. Create a JSON key for a service account with the role **Firebase Cloud Messaging API Admin** only.
   - The owner does this: Firebase console › Project settings › Service accounts.
   - Keep the key out of the repository. Locally it goes in `~/.meldiq-secrets/fcm-service-account.json`; on the VPS,
     `chmod 600` and mounted read-only into the API container.
3. Set `FCM_PROJECT_ID=meldiq` and `FCM_SERVICE_ACCOUNT_FILE` (see `deploy/.env.example`), run `validate-env.sh`,
   then restart the API service only. The local test server (scratchpad `testenv/start.sh`) sets both itself when the
   key file exists.
4. **Android: built 2026-10-20.**
   - `MeldiqFcmService` wakes the app through `WakeWorker` and never reads the payload.
   - `FcmRegistrar` sends the token with `PUT /v1/devices/current/fcm` when signed in, again when Firebase rotates it,
     and retries after a network failure (`FcmRegistrarTest`).
   - Firebase is switched on only when `android/app/google-services.json` exists. That file is client config, not a
     secret, but it stays out of this public repo through `.gitignore`. Builds without it work as before and never
     register.

## Phone test, 2026-10-20 (HONOR ELI-AN00, Meldiq Test build, local server)

**Result: works end to end.**

- **Server credentials:** with the owner's service-account key, Google issued an access token for project `meldiq`
  (the free `push-fcm` connector check).
- **Token:** the app started Firebase, got a token and registered it (`PUT /v1/devices/current/fcm` 200;
  `MeldiqFcm: wake-ups: Active`).
- **Real wake-up:** the app was in the background with "Stay connected" off. One wake-up went through Google at
  16:51:47.9; Google returned 200 ("sent"). Google Play services received it at 16:51:48.7. The app ran `WakeWorker`
  ("wake-up fetch: Fetched") and reconnected at 16:51:49.5, about 1.6 s after sending. The message carried no content.
- **China:** in mainland China this needs a VPN in **Global** mode, so that Google Play services reach `mtalk.google.com`
  (ports 5228–5230) and `firebaseinstallations.googleapis.com`. With the VPN's smart/fake-IP DNS, Google answered
  `SERVICE_NOT_AVAILABLE` and the app correctly stayed `Unavailable`. People in China without a VPN need UnifiedPush
  through ntfy on Meldiq's own server, or the "Stay connected" fallback.
- **Bug found and fixed:**
  - What happened: `DELETE /v1/devices/current/push` (withdraw the UnifiedPush endpoint) also deleted the device's FCM
    token. The app withdraws UnifiedPush whenever no distributor app is installed, which is exactly the case on phones
    that rely on Firebase, so FCM unregistered itself as soon as Settings opened.
  - Fix: `removeUnifiedPushEndpoint` now touches only UnifiedPush. Sign-out and device revocation still remove both.
  - Test: `push_fcm.test.ts` checks both.
- **Not yet:** production needs `FCM_PROJECT_ID=meldiq` and the key file on the server (an approved change), plus a
  deploy of this code.

## Tests

`server/test/push_fcm.test.ts`: configuration checks, the signed assertion (verified with the public key), the exact
message (no content), pacing, connected devices skipped, token removal and keeping. Delivery to a real phone is not
verified (no project).
