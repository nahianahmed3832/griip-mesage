# Background delivery: evaluation and plan (T2.5)

2026-10-16. **Goal:** messages and incoming calls should reach a phone whose app is in the background, suspended or killed, without
weakening Griip's privacy model.

**Rule for every option:** a push carries **no message content, no sender or conversation identifiers, no private keys and no call keys.**
It only wakes the app, which then fetches its encrypted queue over its own authenticated connection.

## What exists (and what was added today)

- **"Stay connected" (default on):** a foreground service of type `remoteMessaging` keeps the relay WebSocket open while the app is
  in the background (`RelayConnectionService`).
  - It works while the process lives.
  - It doesn't survive a force-stop, and OEM battery managers can kill it. MagicOS on the test phone is one of these.
- **Added (T2.5):**
  - `BackgroundDelivery` interface. Mechanisms are replaceable, with `PersistentConnection` as today's implementation.
  - `BackgroundDeliveryRestartReceiver` restarts the service after **reboot** (`BOOT_COMPLETED`) and after an **app update**
    (`MY_PACKAGE_REPLACED`). Previously it stayed off until the app was opened.
  - Both broadcasts are exempt from Android's background foreground-service start limits. `remoteMessaging` isn't among the types
    Android 15 forbids from `BOOT_COMPLETED`.
  - **Not verified on a device yet.**

## Telegram reference (behaviour only)

- Telegram wakes its app with FCM (and Huawei HMS on Huawei builds). It registers the token with its servers (`PushListenerController`)
  and offers an optional keep-alive foreground service.
- Its pushes go through Google or Huawei and carry server-encrypted notification data.
- **Griip should copy only the structure:** a wake-up channel with a persistent-connection fallback. The payload stays empty.

## Options

| | **A. Persistent connection (have)** | **B. Self-hosted UnifiedPush (ntfy on our VPS)** | **C. Firebase Cloud Messaging** |
|---|---|---|---|
| Works with the app killed | No (until reopened, or a reboot or update with A's restart) | Yes. The distributor app holds one connection for all UnifiedPush apps | Yes, through Google Play services |
| Needs on the phone | Nothing | A UnifiedPush distributor app (for example ntfy) | **Google Play services.** China-market HONOR phones such as the test phone (ELI-AN00) normally ship without them, so FCM would not work there |
| Metadata exposure | None beyond the relay | Only our own server sees wake-up timing | Google sees the device token and wake-up timing |
| OEM battery managers | Can kill the service; users whitelist the app | Can kill the distributor; users whitelist one app | FCM high-priority messages are generally let through |
| Infrastructure | None | A new `ntfy` container in Compose project `griip-internal-messaging`, a TLS route through the host nginx, and per-device topic credentials. **This is a production change and needs approval** | A Firebase project, a server key, `google-services.json` and Play services dependencies |
| Server work | None | Store each device's UnifiedPush endpoint. When a queue item is stored for a device that's offline, send a payload-free wake-up, coalesced to about one per 30 s per device, to allow-listed hosts only (no SSRF) | Store the FCM token; send data-only, payload-free high-priority messages |
| Client work | Done | The UnifiedPush connector library; register and unregister in the `BackgroundDelivery` implementation | Firebase Messaging dependency; the same wake-up handling |
| Calls while killed | No | Yes (wake, then fetch `call` state, then full-screen notification) | Yes |

## Recommendation

1. **Now (done):** keep A as the default, with the boot and update restart.
   - Document per-OEM battery whitelisting for testers (MagicOS: *Settings → Battery → App launch → Griip Message → Manage manually*).
   - Don't promise killed-app delivery.
2. **Next, after owner approval:** B, self-hosted UnifiedPush through ntfy, as a second `BackgroundDelivery` implementation.
   - It's the only option that keeps metadata on our own infrastructure and also works on phones without Google services.
   - **Needs approval for:** the new container, the subdomain or path with TLS, and the nginx site.
3. **Optional, later:** C, only for phones with Play services, and only if the owner accepts Google seeing wake-up timing. It shouldn't replace B.

## Verification plan (needs real devices; none of this has run)

Run on the HONOR phone and, ideally, one Pixel or Samsung with Play services. For each mechanism:
1. **App in the background:** a message from Griip Bot is notified within about 5 s.
2. **App swiped from recents:** the message still arrives (A: if the service survives; B and C: wake-up).
3. **Doze:**
   ```bash
   adb shell dumpsys deviceidle force-idle
   ```
   Then send a message and record the delivery delay.
4. **Reboot without opening the app:** the "connected" notification returns (A restart), and a message arrives.
5. **App update** (`adb install -r`): the service returns without opening the app.
6. **Incoming call while killed or locked:** a full-screen call notification appears (Android 14+ needs the full-screen-intent permission).
7. **Force-stop:** nothing arrives until the app is opened. This is expected on Android, and the report must state it.
