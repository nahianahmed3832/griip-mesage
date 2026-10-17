# Background delivery

**Goal:** messages and incoming calls should reach a phone whose app is in the background, suspended or killed, without
weakening Orbit's privacy model.

**Rule for every option:** a push carries **no message content, no sender or conversation identifiers, no private keys and no call keys.**
It only wakes the app, which then fetches its encrypted queue over its own authenticated connection.

## Status (2026-10-17)

| Part | State |
|---|---|
| A. "Stay connected" (persistent connection) | In production since 2026-10-16. Default on |
| B. Self-hosted UnifiedPush wake-ups, server | **Built and tested locally** (`server/test/push_wakeup.test.ts`, `push_transport.test.ts`). Off unless `PUSH_UNIFIEDPUSH_HOSTS` is set. **Not deployed** |
| B. App | **Built and tested on the JVM** (`PushRegistrarTest`, `WakeFetchTest`, `PushContractTest`). Off by default. **Never run on a phone** |
| B. Push server (ntfy), DNS name, TLS route | **Prepared, not set up:** compose service, config, nginx site and runbook (`PUSH_SERVER.md`). Each production step needs the owner's approval |
| C. FCM | Not started |

The 2026-10-16 evaluation of the options follows at the end.

## How a wake-up works (option B)

```
phone: distributor app (ntfy) ──holds one push connection──▶ ntfy server (ours)
                │  ▲                                              ▲
  1. endpoint   │  │ 3. "wake" (empty)                            │ 2. empty POST, only when this
  (per profile) ▼  │                                              │    device has no open socket
phone: Orbit ──PUT /v1/devices/current/push──▶ Orbit API ─────────┘
        │
        └─ 4. opens its own authenticated WebSocket, fetches the encrypted queue, notifies as usual
```

1. **Registration.** With *Wake-ups through UnifiedPush* on, the app asks the distributor app for an endpoint for the active
   server profile (the UnifiedPush *instance* is the profile id) and sends it to its server
   (`PUT /v1/devices/current/push`). It does so again at every app start, because the server drops endpoints that stop working.
2. **Wake-up.** When the server queues an envelope for a device that has no open socket, it sends that device's endpoint an
   empty HTTPS POST with transport headers only (`TTL`, `Urgency: high`, a fixed `User-Agent`). A ring does the same for each
   of the callee's devices without a socket.
3. **Delivery.** ntfy (in the distributor role) forwards it to the app. The app ignores the content (ntfy turns an empty
   body into the text "triggered").
4. **Fetch.** The app (`WakeWorker`, an expedited job, and `WakeFetch`) keeps its relay connection open until:
   - the backlog has drained,
   - ring recovery (`GET /v1/calls/active`) has run, so a call that rang shows its full-screen notification, and
   - no call is ringing.

   Messages are verified, decrypted, stored, acknowledged and notified by the existing queue path. Nothing about this path is new.

## Server

**Settings** (`deploy/.env.example`):

| Variable | Default | Meaning |
|---|---|---|
| `PUSH_UNIFIEDPUSH_HOSTS` | empty (off) | Comma-separated push hosts wake-ups may go to: `ntfy.example.net`, or `host:port` for a port other than 443. Nothing else is ever contacted. Invalid entries stop the server at startup |
| `PUSH_WAKE_INTERVAL_SECONDS` | 30 (5–600) | At most one wake-up per device in this window. A ring waits at most 5 s after the previous wake-up |
| `PUSH_TIMEOUT_MS` | 5000 (1000–15000) | Deadline for one wake-up request, connecting included |

**API** (session required; acts on the calling device):

- `PUT /v1/devices/current/push` with `{"kind":"unifiedpush","endpoint":"https://…"}` registers or replaces the device's endpoint.
  - One endpoint per device. An endpoint registered again by another device moves to that device.
  - Returns `{"push":{"kind","host","updatedAt"}}`.
  - Refusals:
    - `404 push_unavailable`: push is off on this server;
    - `400 push_endpoint_invalid`: not https, or it has a user name, password or `#fragment`;
    - `400 push_host_not_allowed`: the host isn't allow-listed. `details.allowedHosts` names the hosts, so the app can say which server to set in ntfy.
- `DELETE /v1/devices/current/push` removes it. It works even while push is off on the server.
- `GET /v1/server-info`:
  - lists `push-unifiedpush` in `features` only when configured;
  - adds `push: {"unifiedPushHosts": [...]}`, which is `null` when off.

**When a device is woken, and when it isn't:**

- **Woken:** an envelope (message or control, such as a call key or an edit) is queued for it while it has no open socket.
  This covers local senders and federated peers.
- **Not woken:** for delivery receipts. They wait until the app next connects.
- **Socket died:** a socket can close with items that were pushed into it live and never acknowledged. This happens when an app
  is killed before the server notices. The device is then woken once. Items it received in its backlog and didn't
  acknowledge don't count, so one item the app can't process never wakes it over and over.
- **Coalesced, never dropped:** a request inside the device's window is deferred to the window's end. It's skipped then if the
  device has connected or its queue has emptied meanwhile.
- **Never woken:** revoked devices, disabled accounts, devices with an open socket, or while the server shuts down.

**Push server answers:**

| Answer | Effect |
|---|---|
| 2xx | Done; `last_wake_at` recorded |
| 404 or 410 | Endpoint removed (gone for good, RFC 8030) |
| Anything else: 3xx (redirects are never followed), 429, 5xx (ntfy answers 507 while no subscriber is connected), timeout, TLS or network error | Logged as a failure with the device id and a reason code. An endpoint that keeps failing for 7 days is removed |
| The host was taken off `PUSH_UNIFIEDPUSH_HOSTS` | Endpoint removed without contacting it |

Endpoints are also removed on device revocation, on sign-out (`POST /v1/auth/logout`) and by the retention job (revoked devices).
`admin.js stats` shows `pushEndpoints` and `pushEndpointsFailing`. SSRF protection and what the push server learns: `SECURITY.md` §7c.

## App

- **Settings › Notifications & Sounds › Background delivery** has two switches:
  - *Stay connected in background* (A, default on);
  - *Wake-ups through UnifiedPush* (B, default off).

  They're independent. Keep A on until B shows "On, through ntfy…".
- **Turning B on:** the connector's `tryUseCurrentOrDefaultDistributor` chooses the distributor. Android shows its chooser if
  there are several and none is the default. The app then registers.
- **The status line shows one of:**
  - off;
  - the server doesn't offer wake-ups;
  - no distributor app;
  - setting up;
  - the distributor failed (with its reason);
  - the server accepts only `<host>`, so set ntfy's default server to it;
  - waiting to reach the server;
  - on, through `<host>`.

  The text below the switches names the server's push host (`server-info`).
- **Profiles:** only the active profile registers.
  - Switching away ends that profile's registration with the distributor and, best effort, on its server.
  - Switching back registers again.
  - An event for a profile that isn't active ends its registration.
  - *Sign out & erase* and removing a profile end it locally (the server's copy goes with the device's revocation).
- **Boot and app update:** if B is on, the app registers again and fetches once. Wake-ups sent while the phone was off may have
  expired at the push server.
- **Notification health:** while B is active, it stops warning that the background connection is off.
- **Code:**

  | File | What it holds |
  |---|---|
  | `service/UnifiedPushDelivery.kt` | The `BackgroundDelivery` implementation |
  | `service/OrbitPushService.kt` | The connector's service |
  | `service/WakeWorker.kt` | The expedited job that keeps the app alive during a fetch |
  | `data/repo/PushRegistration.kt` | State, registration and server sync (pure Kotlin) |
  | `data/repo/WakeFetch.kt` | The wake-to-fetch logic (pure Kotlin) |

- **Dependency:** UnifiedPush connector 3.3.5 (Apache-2.0). The connector asks for Tink's JVM build, whose full `protobuf-java`
  clashes with LiveKit's `protobuf-javalite`. The connector uses only Tink's `subtle` primitives (its WebPush keys), so the build
  swaps in `tink-android` 1.23.0, which has no protobuf dependency.

## Deploying (needs the owner's approval; nothing below has been done)

The exact production steps, their checks and rollback are in `PUSH_SERVER.md`. In short:

1. **ntfy service** `ntfy` in `deploy/compose.yaml` (Compose project `griip-internal-messaging`): a pinned image,
   published on `127.0.0.1:${NTFY_HOST_PORT:-18081}` only, with resource limits like the other services. Its config is
   `deploy/ntfy/server.yml`:
   - `base-url` `https://ntfy.griipbuddy.cloud`, `behind-proxy`, a 12 h cache;
   - `auth-default-access: deny-all` plus `auth-access: everyone:up*:rw`, the declarative form of
     `ntfy access everyone 'up*' read-write`, applied at every start;
   - `visitor-subscriber-rate-limiting: true`: UnifiedPush topics are rate-limited per subscribing phone, not per publisher;
   - sign-up, login, reservations and the web app off.

   Topics are random (`up` + 12 characters). Wake-ups carry nothing, so someone who learned a topic could only see wake-up times.
2. **DNS:** an `A` record `ntfy.griipbuddy.cloud` → the VPS address.
3. **Host nginx:** a TLS site for that name proxying to the container (`deploy/nginx/griip-ntfy.conf`). Subscriptions are
   long-lived streams: `proxy_buffering off`, `proxy_read_timeout` of 1 h, WebSocket upgrade headers.
4. **API:** deploy an image that contains this code (migration 011 runs on start), set
   `PUSH_UNIFIEDPUSH_HOSTS=ntfy.griipbuddy.cloud` in `.env`, then recreate `api`.
   - The API reaches ntfy through the public HTTPS address (it never connects to private addresses), so the firewall must allow
     the host to reach its own public address on 443. On the production VPS it does (checked 2026-10-17 from inside the
     `api` container).
   - `scripts/validate-env.sh` then reports the push host, and `/v1/server-info` lists `push-unifiedpush`.
5. **Phones:**
   - install ntfy (Play, F-Droid, or the APK from ntfy's GitHub releases on phones without Play);
   - in ntfy, *Settings → Default server → `https://ntfy.griipbuddy.cloud`* **before** turning wake-ups on in Orbit;
   - let ntfy run unrestricted in the background (MagicOS: *Settings → Battery → App launch → ntfy → Manage manually*, all on).

**Rollback:** empty `PUSH_UNIFIEDPUSH_HOSTS` and recreate `api`. Apps then show "Your server doesn't offer wake-ups" and keep
"Stay connected". Stored endpoints stay unused until the variable is set again.

## Verification

**Done on 2026-10-17 (local):**

- Server: the vitest suites above cover registration and its authentication, coalescing and deferral, allow-list and SSRF
  refusals, the payload-free request on the wire, 404/410 cleanup, no push to online devices, rings, revoked devices and shutdown.
- App: the JVM tests above cover registration state, endpoint changes, server refusals, the HTTP contract and the wake-to-fetch path.

**Needs a physical phone and the production push server.** Run on the HONOR phone (MagicOS, no Google Play services) and,
ideally, one Pixel or Samsung. Not done yet:

1. **Registration:** ntfy set to our server, Orbit's switch on. The status shows "On, through ntfy.griipbuddy.cloud", and
   `admin.js stats` shows `pushEndpoints` 1.
2. **App in the background, "Stay connected" off:** a message from Griip Bot is notified within about 5 s.
3. **App swiped from recents:** the message still arrives.
4. **Doze:**
   ```bash
   adb shell dumpsys deviceidle force-idle
   ```
   Then send a message and record the delivery delay. The expedited job must get network access.
5. **MagicOS battery management:** leave the phone idle for an hour with Orbit not whitelisted (ntfy whitelisted), then send.
6. **Incoming call while killed or locked:** a full-screen call notification appears. Android 14+ needs the full-screen-intent permission.
7. **Reboot without opening the app:** a message sent while the phone was off is notified after boot.
8. **Force-stop:** Android delivers no broadcasts to a force-stopped app, so nothing arrives until Orbit is opened. Expected; state it in the report.
9. **Distributor uninstalled:** Orbit's status changes to "No distributor app", and the server stops waking the device.
10. **Battery:** with only B on, compare a day's battery use against A.
11. **Profiles:** with wake-ups on, switch to another server profile and back. Each registers again without asking for the
    distributor (the connector forgets it with its last registration; `UnifiedPushDelivery` keeps the choice), and only the
    active profile is woken.

Before the push server exists, steps 1–8 can run against a local Orbit server started with `PUSH_UNIFIEDPUSH_HOSTS=ntfy.sh`
(the public ntfy) and test accounts only, with ntfy on the phone left on its default server. ntfy.sh then sees the test
wake-ups' timing; nothing else changes. The server refuses push hosts on private addresses by design, so a ntfy on the LAN can't stand in.

## Telegram reference (behaviour only)

- Telegram wakes its app with FCM (and Huawei HMS on Huawei builds). It registers the token with its servers (`PushListenerController`)
  and offers an optional keep-alive foreground service.
- Its pushes go through Google or Huawei and carry server-encrypted notification data.
- **Orbit copies only the structure:** a wake-up channel with a persistent-connection fallback. The payload stays empty.

## Options (evaluation of 2026-10-16)

| | **A. Persistent connection** | **B. Self-hosted UnifiedPush (ntfy on our VPS)** | **C. Firebase Cloud Messaging** |
|---|---|---|---|
| Works with the app killed | No (until reopened, or a reboot or update with A's restart) | Yes. The distributor app holds one connection for all UnifiedPush apps | Yes, through Google Play services |
| Needs on the phone | Nothing | A UnifiedPush distributor app (for example ntfy) | **Google Play services.** China-market HONOR phones such as the test phone (ELI-AN00) normally ship without them, so FCM would not work there |
| Metadata exposure | None beyond the relay | Only our own server sees wake-up timing | Google sees the device token and wake-up timing |
| OEM battery managers | Can kill the service; users whitelist the app | Can kill the distributor; users whitelist one app | FCM high-priority messages are generally let through |
| Infrastructure | None | An `ntfy` container, a TLS route through the host nginx, a DNS name. **A production change that needs approval** | A Firebase project, a server key, `google-services.json` and Play services dependencies |
| Server work | None | **Done (2026-10-17)** | Store the FCM token; send data-only, payload-free high-priority messages |
| Client work | Done | **Done (2026-10-17)** | Firebase Messaging dependency; the same wake-up handling (`WakeFetch`) |
| Calls while killed | No | Yes (wake, fetch, ring recovery, full-screen notification) | Yes |

**Recommendation (unchanged):**

1. Keep A as the default, with the boot and update restart.
   - Document per-OEM battery whitelisting for testers (MagicOS: *Settings → Battery → App launch → Orbit → Manage manually*).
   - Don't promise killed-app delivery until B passes the phone checks above.
2. B, after the owner approves the deployment.
   - It's the only option that keeps metadata on our own infrastructure and also works on phones without Google services.
3. C, optional and later: only for phones with Play services, and only if the owner accepts Google seeing wake-up timing.
   It shouldn't replace B.
