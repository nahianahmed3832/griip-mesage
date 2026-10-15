# Decisions log

**D-01: Native Kotlin + Jetpack Compose.** This was mandated. The app is pure Compose with a custom design system on top of Material 3 primitives (`DESIGN_SYSTEM.md`).

**D-02: Toolchain pinned to versions already proven on this machine.**
- AGP 8.13.2, Gradle 8.13, Kotlin 2.2.21, KSP 2.2.21-2.0.5, Compose BOM 2025.10.01, compileSdk/targetSdk 36, minSdk 26.
- Newer AGP 9 and Kotlin 2.4 lines were available but would have meant migration work with no benefit for this beta.
- LiveKit Android 2.29.0 ships Kotlin 1.9 metadata, so it is compatible with this compiler (checked by inspecting the AAR).

**D-03: Backend is Node 22 + TypeScript + Fastify 5 + better-sqlite3.**
- SQLite in WAL mode with synchronous transactions is enough for a single-process internal beta, and it's easy to back up and inspect.
- Temporary delivery tables are kept separate from permanent organisational tables (`ARCHITECTURE.md` §6).

**D-04: Message encryption is the custom composition `griip-envelope-v1` (HPKE + AES-GCM + Ed25519), not Signal or MLS, for now.** Options evaluated:
- *libsignal* (official Android artifact). It is AGPL-3.0, which is a licensing concern for proprietary company software, and its session, pre-key and sender-key plumbing was too large to integrate reliably alongside everything else in this session.
- *MLS (OpenMLS/Marmot).* There is no maintained Kotlin/Android binding, and a TypeScript library can't run in Kotlin.
- *Chosen:* vetted primitives from BouncyCastle (RFC 9180 HPKE, RFC 8032 Ed25519) plus platform AES-GCM, composed into a signed, multi-recipient envelope.
  - It sits behind the `MessageCrypto` interface, so a Signal or MLS implementation can replace it without touching screens or endpoints.
  - **Trade-off:** no forward secrecy and no post-compromise security (`SECURITY.md` §4).

**D-05: Local storage uses SQLCipher 4.12, with the passphrase sealed by Android Keystore.** Room alone is not encrypted.

**D-06: Calls use self-hosted LiveKit 1.13.10 (SFU) with shared-key media E2EE.**
- The per-call key travels inside encrypted messages.
- The app refuses to join without the key when E2EE is on (no silent downgrade).
- Keys aren't rotated per membership change. That is a documented gap.

**D-07: Manual dependency injection (`AppContainer`) instead of Hilt.** It means less annotation processing and build risk, and the object graph is small and explicit.

**D-08: No FCM.**
- Background delivery uses an optional "stay connected" foreground service of type `remoteMessaging`, so no third-party push service sees metadata.
- **Limitation:** a force-stopped or OS-killed app gets no messages or calls until it is reopened.
- FCM, or a self-hosted UnifiedPush relay, can be added later behind an interface.

**D-09: Deployed to the existing Hostinger VPS behind its existing nginx.**
- It uses new subdomains `msg.` and `rtc.griipbuddy.cloud` with their own certbot certificate.
- The API and LiveKit signalling are bound to loopback, and only LiveKit's media ports are opened in the Hostinger firewall.
- Media uses a single UDP port (7882) plus TCP fallback (7881) instead of a port range, which keeps the firewall change minimal.
- No TURN server yet (`KNOWN_ISSUES.md`).

**D-10: The debug build may use plain `http://` servers, but shows a persistent warning.** This is for LAN testing before TLS. Release builds are TLS-only, except for the emulator host address and loopback.

**D-11: Design language.** Soft glassmorphism on a mint → peach gradient, teal actions, pill CTAs with an arrow knob, frosted list cards and tinted icon tiles, translated from the supplied reference. Dark mode uses deeper "action" teals so white text stays readable on teal surfaces.

**D-12: The test peer is a headless bot built on `@livekit/rtc-node` plus the reference TypeScript client.** It verifies Kotlin ↔ Node crypto interop and real media and E2EE behaviour on physical devices without a second phone.
