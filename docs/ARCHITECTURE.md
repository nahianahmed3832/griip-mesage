# Griip Message — Architecture

## 1. Overview

```
 Android app (Kotlin, Compose)                 VPS 72.60.107.88 (Docker Compose project "griip-internal-messaging")
 ┌───────────────────────────────┐   HTTPS/WSS   ┌──────────── host nginx (TLS, Let's Encrypt) ─────────────┐
 │ UI (Compose, Griip design sys)│──────────────▶│ msg.griipbuddy.cloud → 127.0.0.1:18080 → api (Fastify)   │
 │ ViewModels                    │               │ rtc.griipbuddy.cloud → 127.0.0.1:17880 → livekit (SFU)   │
 │ Repositories                  │               └──────────────────────────────────────────────────────────┘
 │  ├ SessionRepository (keys)   │   UDP 7882 / TCP 7881 (ICE, DTLS-SRTP + E2EE frames)
 │  ├ MessageRepository (outbox, │──────────────▶ livekit container (node_ip 72.60.107.88)
 │  │   decrypt pipeline)        │
 │  ├ OrgRepository / Users      │               api ──(RoomService, webhooks)── livekit
 │  └ CallManager (LiveKit SDK)  │               api ── SQLite (WAL) on volume griip-internal-messaging_api-data
 │ Room + SQLCipher (history)    │                    └ /data/attachments (encrypted blobs, ≤7 d)
 │ Android Keystore (key sealing)│
 └───────────────────────────────┘
```

The diagram shows the GriipBuddy production instance. Anyone can run the same stack (`SELF_HOSTING.md`); each instance is independent,
and federated servers form one network: users address each other as `@name:domain`, home servers exchange signed ciphertext, and a
replicated registry of independently operated core nodes verifies servers (`FEDERATION_PROTOCOL.md`, §9). The app can also hold one
account per server as separate on-device profiles (`ANDROID_SERVER_SETUP.md`).

## 2. Repository layout

| Path | What |
|---|---|
| `android/` | Native app: Kotlin 2.2.21, AGP 8.13.2, Compose BOM 2025.10.01, Room 2.8.3 + SQLCipher 4.12, LiveKit Android 2.29.0, BouncyCastle 1.79 |
| `server/` | Relay/API: Node 22, TypeScript, Fastify 5, better-sqlite3, zod, livekit-server-sdk. Tests use vitest. |
| `server/test/support/griipClient.ts` | Reference protocol client (same crypto as the app), used by the tests and the bot |
| `tools/griip-bot/` | Headless test peer: chat, and calls through `@livekit/rtc-node` |
| `deploy/` | `compose.yaml`, `.env.example`, LiveKit config template; scripts: `bundle.sh` (assemble a deployment), `gen-config.sh`, `validate-env.sh`, `preflight.sh`, `deploy.sh`, `verify.sh`, `backup.sh`, `restore.sh` |
| `docs/` | Design system, architecture, security, retention, deployment, self-hosting, server setup, calling, voice messages, troubleshooting, testing, issues, decisions (`DECISIONS.md` and `decisions/`), progress |

## 3. Android app

- **MVVM with repositories** and manual DI (`di/AppContainer.kt`, decision D-07). Coroutines and Flow throughout. Navigation Compose uses string routes.
- **One container per server profile.** `GriipApp` holds the active `AppContainer`, built for a `ProfileStorage` (Keystore alias, sealed
  identity preferences, SQLCipher database, attachment folder, server setting). Switching, adding, removing or erasing a profile closes the
  container and builds a new one; `MainActivity` rebuilds the UI tree for it. The original install is the `default` profile with its
  original storage names (ADR 0002).
- **Layers**
  - `crypto/`: Keystore sealer, identity store, Ed25519/HPKE/AES-GCM wrappers, `EnvelopeV1` (behind the `MessageCrypto` interface), recovery code, fingerprints.
  - `data/api/`: `ApiClient` (OkHttp, kotlinx.serialization; re-authenticates on 401 by signing a challenge) and `RealtimeClient` (one WebSocket, exponential backoff with jitter, reconnects when the network returns).
  - `data/db/`: Room entities for conversations, messages, outbox, users (with pinned identity keys), call log, call keys and key-value pairs. SQLCipher open-helper.
  - `data/repo/`: session, directory (TOFU pinning), conversations, messages, orgs, settings.
  - `call/`: `CallManager` (LiveKit room lifecycle, E2EE key handling, screen share, ring recovery, alerted/busy), `CallReachability.kt`
    (`RingRecovery`, `MediaFlowMonitor`, `MediaPath`, `CallFailure`), `MediaSecurityTracker`, `FrameCryptorGuard`, `CallDiagnostics`,
    `CallService` (microphone/camera foreground service), incoming-call notification. See `CALLING_AND_E2EE.md`.
  - `voice/`: voice message recording (`AndroidAudioRecorder`, `VoiceDraftController`) and app-wide playback (`VoicePlayer`). See `VOICE_MESSAGES.md`.
  - `service/RelayConnectionService`: optional "stay connected" foreground service (type `remoteMessaging`) for background delivery without FCM.
  - `ui/`: theme tokens, reusable glass components, screens.
- **Message pipeline**
  - *Send:* write the message to Room as SENDING and add an outbox row. The outbox loop fetches recipient devices (cached 60 s), encrypts, and POSTs `/v1/messages`.
    - On `409 device_mismatch` it refreshes keys and retries once.
    - On network errors or 5xx it backs off: 2 s, 5 s, 15 s, 30 s, 60 s, 120 s, then marks the message FAILED.
    - While offline it waits without counting attempts.
  - *Receive:* WebSocket `queue` frames are processed in order under a mutex: verify, decrypt, apply (text, edit, delete, reaction, pin, read, call_invite), then ack in a batch.
    - Transient failures (keys unreachable) stay un-acked and are redelivered.
    - Undecryptable items are acked and shown as a red "couldn't be verified" placeholder.
  - Read receipts are encrypted control messages, sent for DMs and small groups (≤ 20 members). Delivery receipts are generated by the server.
- **History** lives only on the device. The UI loads the newest 60 messages and pages back in steps of 60 (incremental loading). Search uses SQL `LIKE` over local text.

## 4. Server

- `src/app.ts` builds Fastify (rate limit, websocket, content parsers, error handler) and registers the route modules in `src/routes/`.
- `src/authz.ts` holds all authorisation logic (§5). `src/hub.ts` tracks online sockets and pushes queue items. `src/jobs.ts` runs retention cleanup and call reconciliation every 60 s.
- SQLite in WAL mode with foreign keys on; migrations are in `server/migrations/` (§6), applied at start. A single process means writes are serialised. Multi-row operations use transactions.
- MongoDB (2026-10-19, `server/src/mongo/`, `MONGODB.md`): with `MONGODB_URI` and `MONGODB_DOMAINS`, the accounts and
  personal-preferences domains live in MongoDB instead (same rows as documents, validators and indexes derived from the
  SQLite schema). Every other domain stays in SQLite until it is moved the same way; the server refuses to start in a
  state that would split a domain between the two.
- Public, unauthenticated: `GET /healthz` and `GET /v1/server-info` (instance name, version, client API range, registration mode, calling,
  limits). Operator CLI `src/admin.ts`, including `backup` (online SQLite snapshot, `src/backup.ts`) and `verify-backup`.
- Realtime frames:
  - Server → client: `hello`, `queue` (+`backlog`), `queue.drained`, `typing`, `presence`, `call` (`ring`/`started`/`updated`/`ended`/`declined`/`dismissed`/`alerted`), `conv.updated`, `org.updated`, `devices.changed`.
  - Client → server: `ack`, `typing`, `ping`.
- Calls:
  - `POST /v1/calls` creates one live call per conversation and issues a 10-minute room-scoped LiveKit token.
  - `join`/`decline` (optional `reason`: `declined` or `busy`)/`leave`/`end`/`connected` endpoints change call state; `alerted` records that a
    callee device is ringing; `GET /v1/calls/active` lists live calls, which the app uses to recover rings it missed while reconnecting.
  - LiveKit webhooks plus a 60 s reconciliation against `RoomService.listParticipants` keep the state correct. Unanswered 1:1 calls end as `missed` after 45 s.

## 5. Authorisation (enforced in `authz.ts` and `permissions/`, tested in `test/orgs.test.ts`, `test/channel_permissions.test.ts`)

Communities replaced the branch/department/team model on 2026-10-21 (`COMMUNITY_REBUILD_2026-10-21.md`).

- **Community permissions** come from the base role (Owner, Admin, Member, Guest) plus custom roles' grants, minus
  their restrictions (`permissions/rules.ts`). Owners hold everything; admins everything but owner-only powers.
- **Channel permissions** start from those and apply the channel's category overwrites, then the channel's own: at each
  level @everyone deny, @everyone allow, the person's roles' denies, their allows, then the person's own overwrite.
  Owners and admins skip overwrites; a role's community-wide restriction still wins (`permissions/channel.ts`).
- **Seeing a channel** (`channels.view`): public and announcement channels are joined automatically by everyone whose
  channel permissions include it; private channels are explicit membership only, and no overwrite can add anyone to
  one. Guests are never eligible: managers add them to specific channels.
- **Posting** needs `messages.post` (and `messages.announce` or channel management in announcement channels); **file
  uploads** need `messages.attach` as well, checked when an upload starts and again when it completes.
- **Every change that can affect access** (a role assigned, removed, edited or archived; an overwrite; a channel moved
  to another category; a kind change) re-checks memberships and evicts people from calls in channels they lost.
- **Bans** (`members.ban`) remove the person and refuse invitations and open joining until lifted. Owners can't be banned.
- **Discovery** lists only public communities; only owners change visibility or the join policy.

## 6. Data model (server)

Communities (2026-10-21, migration 032): `orgs` (with `visibility`, `join_policy`, `rules`), `channel_categories`,
`conversations.category_id / position / format`, `channel_overwrites`, `org_bans`, `invitations.grant_role_id`,
`community_migration_runs`. The `branches`, `departments`, `teams` tables and the channels' `scope` columns stay as the
migration found them, so an older server can be rolled back to.


Permanent organisational metadata:
- `users`, `devices`, `orgs`, `org_members`, `org_roles`, `org_role_members`, `channel_categories`, `channel_overwrites`, `org_bans`, `invitations` (hashed codes)
- `conversations`, `conversation_members`
- `audit_log` (no content)

Temporary data:
- `delivery_queue` (≤ 7 d), `submitted_messages` (idempotency, ≤ 7 d), `attachments` (≤ 7 d)
- `auth_challenges` (2 min), `sessions` (7 d), `pairing_requests` (10 min)
- `calls` / `call_participants` (operational; `calls.alerted_at` since migration 003)

## 7. Scaling limits (honest)

- One API process with in-memory WebSocket routing, so it can't be scaled horizontally as-is. That would need a shared pub/sub.
- SQLite suits an internal beta with tens to low hundreds of users. Channel fan-out stores one envelope per recipient device.
- LiveKit runs as a single node on a 2-vCPU VPS that is shared with other services. Only 2-person media calls are verified (`TESTING.md`). Group calls are capped at 12 participants and haven't been load-tested.

## 8. Path to independent relays

Evaluated item by item in `DECENTRALISATION_READINESS.md` (2026-10-16).

- The protocol is already server-agnostic: clients hold their keys and encrypt end to end, and a relay only routes ciphertext and checks signatures.
  The exception is group membership, which the server asserts and clients don't verify.
- Done (2026-10-16): the app holds one account per server as separate profiles, and servers describe themselves in `/v1/server-info`.
- Done (2026-10-16): federation v1 (§9).
- The next steps would be:
  - Federate the user directory with signed identity documents.
  - Run separate media servers per organisation. The call API already returns the LiveKit URL per call.

## 9. Federation (2026-10-16)

```
 Alice ─▶ Server A ──signed griip-fed/1──▶ Server B ─▶ Bob          core 1 ◀─▶ core 2 ◀─▶ core 3
            │  outbox (bounded, ordered)       │  shadows of A's users     (registry: descriptors, domain bindings,
            └──── register / heartbeat / resolve (quorum) ─────────────────▶ leases, decommissions, revocations)
```

- **Code:** `server/src/federation/` (`identity.ts` server keys and descriptors, `records.ts` decommission/revocation/answers,
  `resolver.ts` quorum resolution and cache, `transport.ts` signed requests, `service.ts` lookup, shadows, outbox, inbound delivery,
  calls, `routes.ts` HTTP), `server/src/core/` (core node: `node.ts`, `app.ts`, `index.ts`). Migrations 004 and 005.
- **Server side:** remote users are shadow rows with their real ids (ADR 0006); federated DMs have a derived id; fan-out sends remote
  devices' rows to the outbox; receipts, attachments (pushed ciphertext) and call events follow; calls are hosted by the caller's server
  (ADR 0007). Federation is off unless `FEDERATION_DOMAIN` is set.
- **App side:** `data/repo/Federation.kt` (`GlobalAddress`, `FederationRepository`), New chat resolves `@name:server` with a verified-identity
  sheet, conversation header and banners for remote servers, own address in Settings, Block on profiles. Everything else (encryption,
  pinning, attachments, voice, calls) is the existing code.
- Specification: `FEDERATION_PROTOCOL.md`; identities: `GLOBAL_IDENTITIES_AND_DISCOVERY.md`; lifecycle: `SERVER_LIFECYCLE.md`; threats and
  metadata: `SECURITY_AND_PRIVACY.md`; audit and plan: `FEDERATION_GAP_ANALYSIS.md`.

## 10. Orbit 2.0 accounts (2026-10-18)

- **Email-and-password accounts** sit on top of the encryption identity: `server/src/accounts/` (store, sessions,
  service, device setup, workspaces, legacy migration, mailer, routes under `/v2`), migration `014_accounts.sql`. A v2
  access token whose session is bound to a device authenticates every `/v1` route and the WebSocket exactly like a v1
  session token (`context.ts` › `authenticateToken`), so messaging, organisations, files and calls are unchanged.
- **App:** `android/.../data/account/` (wire models, `AccountRepository`, `DeviceSetup`, `LegacyMigration`,
  `WorkspaceRepository`) and `ui/screens/account/` (sign-up, sign-in, email code, password reset, device setup, legacy
  move, recovery key, workspace start, account settings). `ApiClient` renews tokens on a 401 through
  `SessionRepository.reauth` → `AccountRepository.refreshTokens`. Servers without `accounts-v2` keep the legacy onboarding.
- **Workspaces:** a virtual personal workspace (DMs, groups and contacts outside any organisation) plus the account's
  organisations, from `GET /v2/workspaces`; the selected one is stored on the account.
- **Files:** 1 GiB per file of the person's file (`FILE_MAX_BYTES`), advertised as `fileMaxBytes`; the server's
  ciphertext cap is derived from it.
- Design, contracts, conflicts and the database plan: `ORBIT_2_MOBILE_AUDIT_2026-10-18.md`,
  `ORBIT_2_ARCHITECTURE_2026-10-18.md`; results: `ORBIT_2_IMPLEMENTATION_REPORT_2026-10-18.md`.
