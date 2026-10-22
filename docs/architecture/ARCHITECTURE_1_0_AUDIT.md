# Meldiq Architecture 1.0: repository audit (2026-10-22)

Phase A of the Architecture 1.0 brief. Read-only: no code was changed to write it. The target architecture and plan
that follow from it are in [`ARCHITECTURE_1_0_PLAN.md`](ARCHITECTURE_1_0_PLAN.md).

Sources: this repository at `f1086aa` (internal-beta), the web repository `meldiq-web` at `70d7c15` (main, the live
release's code), the folder `/Volumes/code_files/meldiq_ios`, and the public endpoints of production
(`https://msg.meldiq.app/healthz`, `/v1/server-info`, `https://web.meldiq.app`), read on 2026-10-22. No traffic figures
exist in the repository, so none are given.

## 1. Summary

| Area | Finding |
|---|---|
| Shape | One Fastify 5 / TypeScript / Node 22 process (API 0.26.0) on a Hostinger VPS with Docker Compose, next to LiveKit 1.13.10 and ntfy. Already a modular monolith in spirit; folder structure is half by domain, half flat. |
| Database | **SQLite in production.** MongoDB is implemented for 5 of 12 domains and tested in CI, but no cluster exists and production reports `mongodb: null`. |
| Messages | End-to-end encrypted (`griip-envelope-v1`). The server holds ciphertext in a 7-day relay queue and per-account encrypted history. It never holds plaintext. |
| Files | Ciphertext objects in S3 (`griipbuddy-orbit-media-sg`, ap-southeast-1) with presigned multipart uploads up to 1 GiB; metadata in SQLite. |
| Real-time | One WebSocket per device (`/v1/ws`), frames `{ t: … }`, in-memory hub. Frames are untyped on the server (`msg: unknown`). |
| Contracts | Uniform error envelope, zod request validation. **No OpenAPI, no response schemas, no shared or generated client types.** Each client hand-writes its models (Android ~45 `@Serializable` files, web ~380 zod objects). |
| Android | Native Kotlin/Compose, 302 files / 49k lines, one Gradle module, MVVM-lite with manual DI, Room + SQLCipher. |
| Web | **React 19 + Vite 7 SPA, not Next.js.** 300 files / 47k lines, static files behind nginx. |
| iOS | **Does not exist.** `/Volumes/code_files/meldiq_ios` is an empty folder. |
| AI | **None on the server** (the earlier module was removed in API 0.12.0). The web still contains a client for the removed AI routes; Android has 42 unused AI strings. |
| Tests | Server 717 passed / 10 skipped (both SQLite and MongoDB modes); web 363 passed / 1 skipped; Android 651 passed (§8). |

## 2. Backend

### 2.1 Structure

`server/src`: 22,892 lines of TypeScript. Bootstrap in `app.ts` (405 lines) builds Fastify, registers 25 route
plugins and wires an `AppContext` (`context.ts`) holding the database, `Authz`, `Hub`, LiveKit, blob store, push,
mail, MongoDB and the per-domain stores.

| Kind | Where | Notes |
|---|---|---|
| Routes | `src/routes/*.ts` (19 files), plus `accounts/routes.ts`, `preferences/routes.ts`, `reminders/routes.ts`, `ops/routes.ts`, `experiments/routes.ts`, `federation/routes.ts`, `ws.ts` | 228 HTTP routes: `/v1/orgs` 45, `/v1/conversations` 19, `/v2/account` 17, `/v1/users` 13, `/v1/calls` 11, `/v2/auth` 10, `/v1/attachments` 9, … |
| Domain folders | `accounts/`, `permissions/`, `push/`, `history/`, `relationships/`, `reports/`, `preferences/`, `reminders/`, `search/`, `storage/`, `mongo/`, `federation/`, `core/`, `ops/`, `usage/`, `experiments/`, `workspace/` | Some have a store interface (accounts, history, relationships, reports, preferences, key backups) |
| Root files | `authz.ts` 470, `config.ts` 497, `jobs.ts` 235, `ws.ts` 224, `hub.ts` 198, `profile.ts`, `invites.ts`, `roles.ts`, `mentions.ts`, `entitlements.ts`, `abuse.ts`, `crypto.ts`, `util.ts`, `photos.ts`, `livekit.ts`, `admin.ts` (CLI), `backup.ts`, `seed.ts` | Cross-cutting and domain logic mixed |
| Largest | `routes/orgs.ts` 824, `federation/service.ts` 814, `routes/calls.ts` 669, `routes/conversations.ts` 550, `routes/roles.ts` 546, `routes/community.ts` 546, `routes/attachments.ts` 537 | All under the 800-line ceiling except two |

**Dependency direction today:** routes call SQLite directly. There are **861 `prepare()` calls across 60 files**;
`routes/orgs.ts` alone has 67, `authz.ts` 21. Routes regularly read tables that belong to other domains (for example
`messages.ts` reads `devices` and `submitted_messages`; `authz.ts` reads conversations, members, roles and
overwrites). That coupling, not the folder names, is the real refactoring cost.

### 2.2 Middleware and cross-cutting

- Auth: `requireAuth` preHandler; bearer token authenticated as either a v1 session (Ed25519 challenge sign-in) or a v2
  account access token bound to a device (`context.ts › authenticateToken`). The WebSocket re-checks the session on
  every frame (`ws_frame_auth.test.ts`); browsers send the token as the first frame (`t: "auth"`).
- Validation: zod at every route through `parse()`; shared primitives `zId`, `zB64`, `zKey`, `zSig`, `zName`.
- Errors: one handler; `{ error: { code, message, details? } }`; about 110 distinct `ApiError` codes plus the helpers
  `badRequest` (82 uses), `notFound` (68), `forbidden` (51), `conflict` (33), `unauthorized` (10). MongoDB outages map
  to `503 unavailable`. No catalog of codes exists.
- Rate limits: `@fastify/rate-limit` plus `abuse.ts` (registration, sign-in, uploads, storage, sockets per device).
- Logging: Fastify's pino logger; authorization and cookie headers removed; request bodies never logged.
- Health: public `/healthz` (database, MongoDB, LiveKit), `/v1/server-info` (version, API range, limits, 60 feature
  flags), platform-admin `/v1/admin/health` with in-memory per-route latency and job metrics (`ops/metrics.ts`).
- Jobs: `jobs.ts` timers in the same process (retention, call reconciliation every 60 s, reminders, usage).

### 2.3 Data (SQLite, 44 migrations, MongoDB per domain)

`server/migrations/001…044.sql`, applied at start. Domain map and MongoDB readiness (from
`docs/product/MELDIQ_MONGODB_MIGRATION.md`, counts re-checked):

| Domain | Runs on MongoDB | Production |
|---|---|---|
| accounts, preferences, history, reports, relationships | Yes (store interfaces, tests on both stores) | SQLite |
| files, federation, relay, conversations, identity, workspace | Copy-and-verify only (`admin mongo-dryrun`) | SQLite |
| integrations | Retired | SQLite tables kept for `admin retired-data` |

Tools exist and are tested: `admin mongo-migrate`, `mongo-rollback`, authority records preventing a split domain,
validators and indexes derived from the SQLite schema. **CI runs the MongoDB pass with
`MELDIQ_TEST_MONGO_DOMAINS=accounts,preferences,integrations`**: `integrations` is retired and the newer
history, reports and relationships stores aren't covered in CI (they pass locally, §8).

### 2.4 Messaging, ordering, pagination

- `POST /v1/messages`: an envelope with a client `messageId`, signed by the sending device, with one wrapped key per
  recipient device (≤ 1,000). The server checks membership, posting permission, timeouts, voice-channel rules,
  slowmode, message-request limits, @everyone policy, clock skew (±1 day) and the Ed25519 signature, then writes one
  `delivery_queue` row per recipient device and a `submitted_messages` idempotency row.
- **Idempotency:** a retry from the same device returns `duplicate: true` with the original `serverTs`; the same id
  from another device is `409`.
- **Ordering:** per recipient device, by queue row id. The hub withholds live pushes while a backlog flush is running
  (`Conn.flushing`), so live items can't overtake the backlog.
- **Delivery:** WebSocket `queue` frames (backlog in batches, then `queue.drained`); clients ack in batches; REST
  fallback `GET /v1/queue?limit≤200` + `POST /v1/queue/ack`. Unacked items are redelivered until they expire (7 days).
- **History:** `/v1/history/items` (opaque tags, ciphertext, `seq` cursor, per-account quotas, tombstones).
- **Read receipts:** encrypted control messages (DMs and groups ≤ 20). Delivery receipts generated by the server.
- **Presence and typing:** WebSocket frames; presence modes (online, idle, invisible) since B6b.
- **Isolation:** `b15_isolation.test.ts`, `channel_permissions.test.ts`, `authz_edges.test.ts` cover cross-community
  access.
- **Limit worth knowing:** fan-out stores one row per recipient device, capped at 1,000 devices per envelope. Very large
  channels will need sender keys or a different fan-out; not a 1.0 change.

### 2.5 Real-time events

Server → client (21 client-facing names found in `src`; some are built from variables, so only a typed union will
make the list authoritative): `hello`, `pong`, `queue` (+`backlog`), `queue.drained`, `typing`, `presence`, `call`
(events `ring`, `started`, `updated`, `ended`, `declined`, `dismissed`, `alerted`, `moderated`), `conv.updated`,
`org.updated`, `org.deleted`, `org.reports`, `org.applications`, `org.application`, `org.alert`, `devices.changed`,
`connections.updated`, `prefs.updated`, `reminders.changed`, `reminder.due`, `meeting.updated`, `meeting.soon`.
Federation-internal `griip.*` messages travel between servers, not to clients. Client → server: `auth` (browsers),
`ack`, `typing`, `ping`. Android handles 16 of the 21, the web 20 (all but `org.alert`) plus six names the server no
longer sends (five `inbox.*`, `integration.event`).

`Hub.sendToDevice/User/Users(msg: unknown)`: nothing types a frame. `hub.ts` says it assumes a single instance.

### 2.6 Files and S3

`routes/attachments.ts`: single-shot upload (`POST /v1/attachments`), direct multipart upload sessions
(`/uploads`, `/:id/url`, `/:id/parts`, `/:id/complete`, `DELETE`), download (`GET /:id`), keep (`/:id/keep`).
Permission (`messages.attach`) checked at start and at completion. `storage/` has `S3BlobStore`, `LocalBlobStore`
and a read fallback; `STORAGE_BACKEND` chooses. Content is encrypted by clients before upload. 1 GiB per file.

### 2.7 Encryption

`griip-envelope-v1`: per-message AES-256-GCM key wrapped per device with HPKE (X25519 / HKDF-SHA256 / AES-256-GCM),
Ed25519 device signatures, device certificates signed by the identity key, trust on first use with identity-change
detection. Recovery key = identity seed; password-protected key backup (PBKDF2); history and reminder keys derived by
HKDF from the identity seed. Shared test vectors are byte-identical across server, Android and web. Documented gaps
(`docs/SECURITY.md`): no forward secrecy or post-compromise security; call keys not rotated on membership change.
Retention: queue 7 days; attachments 7 days unless kept; sessions 7 days; history until deleted.

### 2.8 Calls

`routes/calls.ts` + `livekit.ts`: one live call per conversation, 10-minute room-scoped LiveKit tokens, join / decline
/ leave / end / connected / alerted / hand / moderate, `GET /v1/calls/active` for missed rings, LiveKit webhooks and a
60 s reconciliation, 45 s missed-call timeout, eviction when permissions change. Media keys travel only inside E2EE
control messages; LiveKit frames are end-to-end encrypted. Voice channels issue listen-only tokens until the person
speaks. LiveKit is a single node on the same 2-vCPU VPS.

### 2.9 Notifications

`push/`: FCM (data-only wake-ups, no content), ntfy / UnifiedPush and Web Push addresses; `onQueuedOffline` triggers
a wake for a device with no socket. No APNs adapter (no iOS app).

### 2.10 AI

No AI code on the server (`grep` for providers and `/ai` routes finds nothing). Migration `027_ai.sql` remains from
the retired module. A design for a replacement exists (`docs/product/MELDIQ_AI_ARCHITECTURE.md`, "specified, not
built"), and its constraints are kept in the plan §6.

### 2.11 Build, CI, deployment, configuration

- CI (`.github/workflows/ci.yml`): server typecheck, tests on SQLite and on MongoDB (stale domain list, §2.3), build;
  Android unit tests, lint, debug and release assemble.
- Deployment (`docs/DEPLOYMENT.md`): VPS 72.60.107.88, Compose project `griip-internal-messaging`, API built on the
  VPS, rollback images per release, backups before each release, a rehearsal on a snapshot copy. Hostnames
  `msg.meldiq.app`, `rtc.meldiq.app`, `web.meldiq.app` (old griipbuddy.cloud names still served).
- Configuration: `config.ts` (497 lines) reads about 100 environment variables, documented in
  `deploy/.env.example` and checked by `deploy/scripts/validate-env.sh`.
- Production open items already recorded (`MELDIQ_RELEASE_READINESS.md`): `REGISTRATION_MODE=open`, `APP_ENV` unset,
  SES sandbox (mail via Hostinger SMTP).

## 3. Accounts, organizations, permissions

- Two sign-in generations: v1 (device key challenge) and v2 (email + password accounts on top of the encryption
  identity, `/v2/auth`, `/v2/account`, refresh rotation, recovery codes, key backup, legacy migration). Production
  advertises `legacyLogin`, `legacyRegistration` and `legacyMigrationRequired`.
- Communities (formerly organizations/workspaces; the `orgs` table keeps its name): base roles Owner, Admin, Member,
  Guest; custom roles with grants and restrictions; category and channel overwrites; bans, timeouts, slowmode, reports,
  join applications, onboarding, guide, expressions. Rules in `permissions/` and `authz.ts`, which is synchronous and on
  the message hot path.
- Workspace terminology survives in routes (`/v2/workspaces`, `workspace/*`) after the community rebuild.

## 4. Android (`android/`, from the client audit, spot-checked)

- One module `:app`; `com.meldiq.android`, versionCode 19 / 0.5.0-beta; compile/target SDK 36, min 26; Kotlin 2.2.21,
  Compose BOM 2025.10.01; OkHttp 4.12 + kotlinx.serialization; manual DI (`di/AppContainer.kt`, one container per
  server profile); Room 2.8.3 on SQLCipher (schema 13, 10 tables, explicit migrations with a JVM test); LiveKit Android
  2.29.0; Firebase Messaging; UnifiedPush; ML Kit on-device translation.
- 302 files, 48,952 lines: `ui` 31.5k, `data` 11.5k (`repo` 5.7k, `model` 2.6k, `api` 1.4k), `call` 2.6k, `crypto` 1k.
- Layering: UI + data, no domain layer; rule objects in `data/model/*Rules.kt` (well tested). Only 7 files define
  ViewModels; 66 UI files read the container directly and 9 call `container.api` directly.
- API: one `ApiClient` (generic `get/post/put/patch/delete`), hand-written `@Serializable` models (`ApiModels.kt` 622
  lines and five other model files), lenient JSON (`ignoreUnknownKeys`, `coerceInputValues`), 401 → one re-auth
  (v2 refresh or v1 challenge) → retry, errors as `ApiException(status, code, …)`. 188 `/v1` and 30 `/v2` path
  literals.
- Real-time: `RealtimeClient` (OkHttp WebSocket, 25 s ping, 1–30 s backoff with jitter, reconnect on network return,
  reauth on 4401/4001). One all-nullable `RealtimeFrame` class for every event; 16 frame names handled in
  `MessageRepository.handleFrame`; outbound frames are hand-built strings (`{"t":"typing","c":"$cid",…}` interpolates
  the id without escaping; ids are server-issued and charset-restricted, so this is a robustness issue, not an exploit).
- Largest classes: `ConversationScreen.kt` 1,426, `CallManager.kt` 1,363, `MessageRepository.kt` 1,289 (send, receive,
  decrypt, controls, notifications, call history in one class).
- Duplicated parsing: message previews computed in four places that disagree; `attachmentsJson`, `callJson`,
  `richJson` decoded at several read sites; `MessageEntity` built from three sources (live queue, history sync, backup
  restore).
- Notifications: FCM wake → `WakeWorker`; ntfy/UnifiedPush; optional "stay connected" foreground service; full-screen
  incoming calls.
- AI: none; 42 `ai_*` strings unused.
- Tests: 121 JVM test files (10,201 lines), including envelope interop, payload vectors, migrations, queue processing
  and three contract tests; 3 instrumented Compose tests.

## 5. Web (`meldiq-web`, from the client audit, spot-checked)

- **Framework: React 19.3 + Vite 7.3**, custom hash router, custom immutable store (`state/store.ts`) with
  `useSyncExternalStore`; no Next.js dependency, config or directory convention. Built to static files, installed by
  `deploy/scripts/install-release.sh` into `/opt/meldiq-web/releases/…`, served by nginx with a strict CSP, `/v1` and
  `/v2` proxied to the API. Live: `0.5.0-beta-b327e82`.
- 300 files, 46,908 lines: `ui` 24.4k, `sync` 8.9k (`engine.ts` 1,023), `styles` 6.1k, `call` 2.3k, `crypto` 1.1k.
- API: `fetch`-based `ApiClient`; ~380 hand-written zod objects; unknown enum values silently coerced by 42 `.catch()`
  defaults (an unknown community kind becomes "team"); a schema mismatch fails the whole response with `decode`.
- Real-time: first-frame `auth`, 25 s ping, 70 s stale detection, full-jitter backoff; frames parsed by hand per case
  in `engine.ts`; only `call` and queue items are schema-checked. **A queue item that fails the schema is acked and
  dropped** (`sync/receiver.ts:27-31`): a future item kind would be lost on the web without a placeholder.
- Encryption: WebCrypto + `@hpke/core`; `@noble/curves` fallback for Safari < 17; keys in IndexedDB (non-extractable,
  or wrapped where WebKit can't store X25519); unlock on a new browser by recovery key, phone approval or emailed code +
  password.
- Notifications: service worker; background wake via FCM when `config.json` has a `firebase` block.
- Calls: `livekit-client` with an E2EE worker and frame audit; screen share modes; voice rooms.
- **Retired features still in the client:** `sync/ai.ts` calls `/v1/orgs/:id/ai/*` and `/v1/documents/:id/ai/summary`,
  and sends decrypted message text with `consent: true` (catch-up up to 150 messages). The server no longer has these
  routes, so they fail; the code and its UI (`ui/ai/AiDialogs.tsx`, `ui/workspace/AiPage.tsx`) should go. The client
  also still handles `inbox.*`, `integration.event` and `/v1/documents` from retired modules.
- Tests: 57 unit files (363 passed), 32 live-server integration files, 11 Playwright specs (47 tests). `server-patches/`
  holds two patches already merged into the server (stale).

## 6. iOS

`/Volumes/code_files/meldiq_ios` exists and is empty. No Swift code, Xcode project, bundle identifier, APNs setup or
TestFlight record was found in any accessible repository. The README and docs describe Android and web only. Nothing
in this audit or the plan claims an iOS implementation. The plan (§7.3) gives the gap report and an implementation
outline.

## 7. Feature inventory

The maintained inventory is `docs/FEATURE_INVENTORY.md` with per-batch evidence in
`docs/product/MELDIQ_SESSION_HANDOFF.md` and `docs/product/MELDIQ_RELEASE_READINESS.md`. Verified-working status
there is either automated (server, Android, web suites) or phone-tested by the owner; batches B4b–B16 and B14b still
wait for the owner's phone test, and this audit does not upgrade any of them to verified. Feature families:

| Family | Server | Android | Web | iOS |
|---|---|---|---|---|
| Accounts v2, device setup, legacy migration, recovery, key backup | yes | yes | yes | — |
| DMs, groups, message requests, connections | yes | yes | yes | — |
| Communities: channels, categories, roles, overwrites, forums, threads, polls, events, onboarding, moderation | yes | yes | partial (web parity session) | — |
| Encrypted history sync | yes | yes | yes | — |
| Files up to 1 GiB, voice messages | yes | yes | yes | — |
| Calls, group calls, screen share, voice channels | yes | yes | yes | — |
| Push wake-ups (FCM, ntfy, web push) | yes | yes | yes | — |
| Federation | yes (off) | yes | no | — |
| AI | no | no (strings only) | stale client of removed routes | — |

## 8. Test baseline (2026-10-22, before any change)

| Suite | Command | Result |
|---|---|---|
| Server typecheck | `npx tsc --noEmit` | clean |
| Server, SQLite | `npx vitest run` | **84 files passed, 1 skipped; 717 tests passed, 10 skipped** (43.7 s) |
| Server, MongoDB domains | `MELDIQ_TEST_MONGO_DOMAINS=accounts,preferences,history,reports,relationships npx vitest run --maxWorkers=3` | **717 passed, 10 skipped** (135.7 s) |
| Web typecheck | `tsc -b` (clean clone of `70d7c15`) | clean |
| Web unit | `vitest run --exclude 'test/live*.test.ts'` | **57 files; 363 passed, 1 skipped** |
| Web live integration and Playwright | needs a running server and browsers | not run in this audit |
| Android JVM unit | `./gradlew :app:testDebugUnitTest -Pmeldiq.allowNoFirebase=true` | **651 passed, 0 failed, 0 skipped** (2 min 12 s) |
| Android instrumented | needs an emulator | not run in this audit |

Note: the web repository's own working folder (`/Volumes/code_files/orbit_web`) has a stale `node_modules`
(`@noble/curves/ed25519` missing), so its tests fail to load there; a clean `npm ci` passes. That folder belongs to
the web parity session and was not changed.

## 9. Risks found

1. **No canonical contract.** Three hand-written interpretations of the same payloads (server zod inputs, Android
   models, web zod schemas); lenient decoding on both clients hides drift.
2. **Silent loss on the web** for queue items that don't match its schema.
3. **Coupled data access** (861 direct statements, cross-domain reads) blocks both the module boundaries and the
   MongoDB move; synchronous `authz` is the hardest part.
4. **Single-process assumptions** (in-memory hub, metrics, timers): fine now, but nothing marks them as the swap points.
5. **CI's MongoDB pass covers a stale domain list.**
6. **Retired AI code in the web** that sends decrypted text to routes that no longer exist.
7. **Large classes** on both clients (`MessageRepository`, `CallManager`, `ConversationScreen`, `engine.ts`).
8. **Encryption limits** already documented (no forward secrecy, 1,000-device fan-out cap, call keys not rotated on
   membership change).
