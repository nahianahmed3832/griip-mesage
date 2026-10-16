# Orbit (formerly Griip Message)

**Orbit by GriipBuddy** · *Private by design. Connected by everyone.*

A native Android messaging and collaboration app for GriipBuddy teams, as an internal beta. The app was renamed from
Griip Message on 2026-10-16; servers, protocols (`griip-envelope-v1`, `griip-fed/1`), the `griip://invite/` link and the
Docker deployment keep their names.
- **Messaging:** end-to-end encrypted direct messages, groups and team channels.
- **Workspaces:** organisations, branches, departments and teams with role-based access.
- **Calls:** voice and video calls and Android screen sharing over a self-hosted LiveKit SFU.
- **Storage:** history is kept on the device, and the relay keeps encrypted envelopes for at most 7 days.

> Internal beta. Servers can federate (v1, not yet deployed). Read [`docs/SECURITY.md`](docs/SECURITY.md) and [`docs/SECURITY_AND_PRIVACY.md`](docs/SECURITY_AND_PRIVACY.md) before using it for sensitive information.

| | |
|---|---|
| Android application ID | `com.griipbuddy.orbit` (Orbit). Builds before 2026-10-16 were `com.griipbuddy.message` (Griip Message): a separate app; nothing moves between them, so an Orbit install signs in or registers afresh |
| APK | `artifacts/GriipMessage-Federation-v1-testing.apk` (Orbit, debug-signed); older Griip Message builds stay in `artifacts/`. Default server `https://msg.griipbuddy.cloud` |
| Relay / API | `https://msg.griipbuddy.cloud` |
| Calls (LiveKit) | `wss://rtc.griipbuddy.cloud`, media on 7881/tcp and 7882/udp |
| Compose project | `griip-internal-messaging` in `/opt/griip-internal-messaging` on 72.60.107.88 |

## Install & try

1. Install the APK. You can use `adb install -r artifacts/GriipMessage-debug.apk`, or copy it to the phone and open it.
2. Tap **Get started**, pick a display name and a username. Your keys are created on the phone.
3. Join a workspace with an invite code, or create one. Owners and admins create invites from **Workspace → Manage → Invitations**.
4. Save your recovery key from **Profile → Security & recovery**.
5. For background delivery, keep **Settings → Stay connected in background** on, and allow the app in your phone's battery manager.

## Build

Android needs JDK 17+; Android Studio's bundled JBR works.

```bash
cd android && ./gradlew :app:assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`. To point a build at a local server, put
`griip.serverUrl=http://10.0.2.2:8080` in `android/local.properties`.

The server needs Node 22+. Install, test, then build:

```bash
cd server && npm install
```

```bash
cd server && npm test
```

```bash
cd server && npm run build
```

Run it locally:

```bash
cd server && PORT=8080 LIVEKIT_API_KEY=devkey LIVEKIT_API_SECRET=<32+ chars> LIVEKIT_PUBLIC_URL=ws://<lan-ip>:7880 npm start
```

For local calls, run `livekit-server --dev` and use `devkey`/`secret` with a matching secret.

## Deploy

See [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md). Always use project-scoped commands:

```bash
docker compose -p griip-internal-messaging -f /opt/griip-internal-messaging/compose.yaml ps
```

## Test against the app with Griip Bot

`tools/griip-bot` is a headless second participant. It echoes messages, verifies attachments, and answers or places E2EE calls,
and it logs the media it receives.

```bash
cd tools/griip-bot && npm install
```

```bash
cd tools/griip-bot && npm run bot -- chat <yourUsername> 1800
```

```bash
cd tools/griip-bot && npm run bot -- call <yourUsername> video 120
```

## Documentation
- [Design system](docs/DESIGN_SYSTEM.md): how the supplied reference was translated
- [Architecture](docs/ARCHITECTURE.md) and [Decisions](docs/DECISIONS.md)
- [Security](docs/SECURITY.md) and [Retention](docs/RETENTION.md)
- [Deployment](docs/DEPLOYMENT.md) (GriipBuddy production) and [Self-hosting](docs/SELF_HOSTING.md) (your own server)
- [Connecting the app to a server](docs/ANDROID_SERVER_SETUP.md): server check, several servers on one phone
- [Calling and call encryption](docs/CALLING_AND_E2EE.md), [Voice messages](docs/VOICE_MESSAGES.md), [Troubleshooting](docs/TROUBLESHOOTING.md)
- Federation: [protocol](docs/FEDERATION_PROTOCOL.md), [global identities and discovery](docs/GLOBAL_IDENTITIES_AND_DISCOVERY.md),
  [server lifecycle](docs/SERVER_LIFECYCLE.md), [security and privacy](docs/SECURITY_AND_PRIVACY.md), [audit and plan](docs/FEDERATION_GAP_ANALYSIS.md)
- [Decision records](docs/decisions/README.md)
- [Testing](docs/TESTING.md): what passed, failed or wasn't verified, with evidence
- [Known issues](docs/KNOWN_ISSUES.md) and [Progress](docs/PROGRESS.md)
