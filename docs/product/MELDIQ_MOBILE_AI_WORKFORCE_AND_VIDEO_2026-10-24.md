# Mobile: AI Workforce and the video module (2026-10-24)

The owner's "AI Workforce, video module & full feature integration" brief, worked on Android against the shared backend
(`internal-beta` f8c86e9, API 0.33.0 in production) and Meldiq Web (`meldiq-web` main d7e0b33).

## 1. What the audit found

### Android (the only mobile client; iOS is a plan only)

Kotlin and Jetpack Compose, one `AppContainer` for dependencies, Room (SQLCipher) for local data, the shared REST API
plus a WebSocket (`RealtimeClient`), FCM wake-ups, end-to-end encrypted messages (`EnvelopeV1`), and LiveKit Android
2.29 for calls with frame encryption. Calls: `call/CallManager.kt` (room, tracks, screen share through LiveKit's
MediaProjection service with Meldiq's notification and Stop action, reconnection), `ui/screens/calls/*` (stage, tiles,
share sheet, picture-in-picture). Voice messages: one `VoicePlayer` for the whole app (`voice/VoiceAndroid.kt`).

### The video module update of 2026-10-23/24

- Server `b48436f` (API 0.33.0): one device per person in a call, newest wins. The older device gets
  `call ended` with `endReason: "joined_elsewhere"` and is removed from the room; it stays signed in.
- Web `b131926`: screen-share Standard 720p30 / High 1080p60, several shares at once with a tray and a per-viewer
  choice, one voice message at a time, "You joined this call on another device".

What Android already had and kept: leaving the call on the server's `ended` message without ending it for others (so
newest-wins worked, with a generic text); one voice message at a time app-wide, paused on audio-focus loss (another app,
a ringing call) and stopped when the conversation closes or the person signs out; Text (1080p15) and Game (720p30) share
modes; adaptive stream, so screens that aren't drawn aren't sent to this phone.

### AI Workforce backend: not there

There is no agent catalog, workspace installation, agent identity, channel assignment, job, approval or deliverable API
on the server, and no agent screens on the web. `server/src/modules/ai` is the Platform Admin AI gateway only:
provider connections, models, prices, quotas, a kill switch (off by default), and one allowed feature, the console's
connection test. Its rule (`docs/AI_OPERATIONS.md`): no product feature uses AI until one is approved, and the gateway
never receives conversation content, because messages are end-to-end encrypted.

## 2. Checklist and status

| Item | Status |
|---|---|
| Newest device wins: say "You joined this call on another device" | **Done** (`CallOverlay`, `vid_joined_elsewhere`) |
| Screen share High 1080p at 60 fps | **Done**, offered only where a hardware VP8 encoder reports 1080p60 (`ShareEncoders`, `CallMediaPolicy.canShareHigh`); 8 Mbit/s, keeps the frame rate under congestion |
| Say when High sends clearly less (under 45 fps or under 1000 lines, measured) | **Done**, on the sharing banner |
| Several shares at once: tray with Watch/Watching, per-phone choice, a new share never takes over | **Done** (`CallTiles.watchedShare`, `ShareTray`, `CallManager.watchShare`) |
| At most 3 screens shared at once | **Done**, checked before Android's consent dialog and again when the share starts |
| Changing the share mode during a share | Not done: needs the capture and encoder changed on a live track; stop and share again for now |
| One voice message at a time, stopped on leaving | Already in place (no change) |
| Agent catalog, installed agents, Agent Manager, channel agents, jobs, approvals, deliverables | **Blocked**: no backend (section 1). Not faked on the phone |

## 3. Why the AI part needs a decision first

Building it means a server project first (catalog, per-workspace installs and identities, channel assignments, a job
runner with states and cancellation, approvals bound to job/action/artifact version, deliverables through the file
system, quotas through the existing gateway), and two decisions only the owner can make:

1. **Encryption.** Channel messages are end-to-end encrypted, so a server-side agent can't read them. The options are an
   agent that only sees what a person explicitly sends it (a task form with chosen files, consent shown each time), or an
   agent as a channel member with its own device key held by the server, which means the server can read that
   channel. The second changes Meldiq's privacy promise.
2. **Turning AI on for products.** The gateway allows no product feature today, by design.

## 4. Tests

Unit (JVM, no device): 708 Android tests, 0 failures, including `ShareHighAndSelectionTest` (High mode, the encoder
rule, "sending less", share selection, the three-share cap). Lint: 0 errors. Not tried on a phone or the emulator yet:
High mode needs a real phone's encoder, and several shares need three devices in one call.
