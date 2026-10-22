# Meldiq Architecture 1.0: target architecture and plan (2026-10-22)

**Status: approved with the owner's decisions of 2026-10-22 (§10).** Phase B is in progress on the local branch
`architecture-1.0`. The audit it
rests on is [`ARCHITECTURE_1_0_AUDIT.md`](ARCHITECTURE_1_0_AUDIT.md). Section numbers below follow the brief's list of
required deliverables (§9): 1 is the audit, 2 to 9 are here.

Principle: one understandable codebase, one canonical backend contract, native applications preserved, AI integrated
cleanly, and complexity introduced only when it earns its place.

## 0. Where the brief and the repository disagree

The audit found four places where the brief describes something the repository doesn't have. The owner decided
D1–D3 on 2026-10-22 (§10); the last column is the decision.

| # | Brief says | Repository and production say | Decision |
|---|---|---|---|
| D1 | Web is an existing Next.js application | Meldiq Web is a **React + Vite single-page app** (`meldiq-web`, static files behind nginx on web.meldiq.app). There is no Next.js code anywhere. | **Migrate to Next.js** (owner, ADR 0008 below), incrementally in phase F once the contracts are stable. I recommended keeping Vite (encrypted content can't be server-rendered); the risks are handled in the F0 spike. |
| D2 | Preserve the existing native iOS stack | `/Volumes/code_files/meldiq_ios` is **empty**. No Swift code exists in any accessible repository. | **Gap report and implementation plan only** in 1.0 (§7.3). Building the app is a separate, owner-approved project. |
| D3 | MongoDB initially, including message storage | **Production runs on SQLite** (`/healthz` reports `mongodb: null`, checked 2026-10-22). 5 of 12 data domains can run on MongoDB; about 650 SQL statements in 7 domains can't yet. No MongoDB cluster exists. | Keep the existing domain-by-domain move (`MELDIQ_MONGODB_MIGRATION.md`), now as part of the module refactor: every module gets a store interface; The owner provisions **MongoDB Atlas now** (§10); each domain's cutover still needs its own go-ahead. |
| D4 | The server stores messages | Meldiq is **end-to-end encrypted**. The server stores ciphertext envelopes for at most 7 days (relay queue) and each account's history as ciphertext under opaque tags. It never holds message plaintext. | "Message storage" means the relay queue and the encrypted history store. Both move behind persistence interfaces (§5). Server-side AI cannot read messages (§6). |

## 2. Target architecture

```
 Android (Kotlin/Compose)     Meldiq Web (React/Vite)     iOS (planned, SwiftUI)
          │  HTTPS /v1 /v2 REST (OpenAPI)  ·  WSS /v1/ws typed events  │
          ▼                                                            ▼
 ┌──────────────────────── Meldiq API: one Fastify/TypeScript process (modular monolith) ─────────────────────────┐
 │ app/        server bootstrap, config, error envelope, auth + rate-limit middleware                              │
 │ modules/    auth · accounts · organizations · messaging · realtime · files · calls · notifications · ai ·      │
 │             administration · federation (off)                                                                   │
 │ infrastructure/  database (SQLite today, MongoDB per domain) · storage (S3, local) · realtime bus (in-process)  │
 │                  · ai-providers · mail                                                                          │
 │ shared/     contracts (zod: REST + WebSocket) · validation · logging · utilities                                │
 └─────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
        │ SQLite → MongoDB (per domain)      │ S3 ap-southeast-1 (ciphertext)     │ LiveKit SFU (same VPS)
        │                                    │                                    │ FCM · ntfy · SMTP/SES
```

What stays exactly as it is: Fastify 5, Node 22, zod, the Hostinger VPS with Docker Compose, S3 for file objects,
LiveKit for media, FCM and ntfy for push, the `griip-envelope-v1` encryption, and every route and event clients use
today.

What is **not** introduced: Kafka, Kubernetes, microservices, a second message service, a vector database, Redis.
Redis (or MongoDB change streams) becomes a candidate only when a second API process is needed: the WebSocket hub
is in memory, so a second process needs a shared bus. One process on the current VPS has headroom for the internal
beta; there is no measurement saying otherwise, so no bus is added now. The hub gets an interface (`RealtimeBus`) so
that adding one later touches one adapter.

## 3. Backend folder structure and dependency rules

### 3.1 Layout (adapted to what exists)

The server already has domain folders (`accounts/`, `permissions/`, `push/`, `history/`, `mongo/` …) next to a flat
`routes/` folder and about 15 root files. The refactor groups them by module; it does not rewrite them.

```
server/src/
  app/            server.ts (today app.ts), index.ts, config/ (config.ts), errors/ (ApiError, error handler),
                  middleware/ (requireAuth, rate limits, abuse.ts)
  modules/
    auth/           v1 challenge sign-in (routes/auth.ts), pairing, sessions, authenticateToken (context.ts)
    accounts/       accounts/* (v2), profile.ts, photos.ts, preferences/, relationships/, key backup, recovery,
                    deletion, reminders/
    organizations/  routes/orgs.ts, community.ts, roles.ts, applications.ts, onboarding.ts, guide.ts,
                    expressions.ts, reports/, invites.ts, roles.ts, permissions/, authz.ts, workspace/*,
                    communityMigration.ts, orgRepair.ts
    messaging/      routes/messages.ts, conversations.ts, history/, mentions.ts, search/, channelRepair.ts
    realtime/       hub.ts, ws.ts, presence
    files/          routes/attachments.ts (the S3 interaction stays behind infrastructure/storage)
    calls/          routes/calls.ts, livekit.ts, routes/meetings.ts, workspace/meetings.ts
    notifications/  push/*, routes/push.ts
    ai/             new (§6)
    administration/ ops/*, admin.ts (CLI), usage/, entitlements.ts, experiments/, backup.ts, retiredData.ts, jobs.ts
    federation/     federation/*, core/* (unchanged, off unless FEDERATION_DOMAIN is set)
  infrastructure/
    database/       db.ts (SQLite + migrations), mongo/client.ts, config, catalog, migrate, admin tools
    storage/        storage/* (S3, local, fallback)
    realtime/       InProcessBus (today's hub fan-out); a shared bus later if ever needed
    ai-providers/   provider adapters (§6)
    mail/           accounts/mailer.ts, mail templates
  shared/
    contracts/      zod schemas for every request, response and realtime event; error-code catalog (§4)
    validation/     zId, zB64, zKey, zSig, zName, parse() (today in context.ts)
    logging/        the redaction rules and log helpers
    utilities/      util.ts, crypto.ts
```

Inside a module only the layers it needs:

| Layer | Holds | Example (messaging) |
|---|---|---|
| domain | Rules and types, no I/O | envelope rules, ordering and idempotency rules, slowmode |
| application | Use cases; depends on store interfaces, not on SQLite or MongoDB | `sendEnvelope`, `fetchBacklog`, `ack` |
| infrastructure | The module's store implementations | `SqliteRelayStore`, `MongoRelayStore` |
| transport | Fastify routes and WebSocket handlers; validate with `shared/contracts`, call use cases | `POST /v1/messages`, `ack` frames |

Small modules (expressions, guide) stay transport + store; they don't get empty layers.

### 3.2 Dependency rules

1. `transport → application → domain`. `infrastructure` implements interfaces that `application` declares.
2. A module uses another module only through that module's `index.ts` (its public use cases). It never imports
   another module's store, never queries another module's tables or collections.
3. `shared/` imports nothing from `modules/`, `infrastructure/` or `app/`. `infrastructure/` imports only `shared/`.
4. Only `app/server.ts` wires everything (stores chosen by `ctx.mongo?.owns(domain)`, as today).
5. Enforced by a test (`test/architecture_boundaries.test.ts`) that reads import paths and fails on a violation. No
   new lint dependency.

Rule 2 is the expensive one. Today `authz.ts` and most routes read other domains' tables directly (861 `prepare()`
calls in 60 files). The move does not fix all of that at once: each existing cross-module query is listed in the
boundary test's allowlist with its module pair, and the list may only shrink. New code can't add to it.

## 4. API and WebSocket contract plan

### 4.1 What is kept

- Paths `/v1/*` and `/v2/*` (accounts), `GET /v1/server-info` with `api` / `minClientApi` and a `features` list.
- The error envelope `{ error: { code, message, details? } }`, already uniform through one error handler.
- WebSocket frames `{ "t": "<name>", ... }` on `/v1/ws`.

### 4.2 What is added

| Item | How | Where |
|---|---|---|
| Request and response schemas | zod schemas for every route's body, query, params and response, moved out of the route files | `shared/contracts/http/<module>.ts` |
| Realtime event schemas | A discriminated union `RealtimeEvent` of the 21 client-facing server→client frames and 4 client→server frames (audit §2.5); `Hub.send*` takes `RealtimeEvent` instead of `unknown` | `shared/contracts/realtime.ts` |
| Error codes | A catalog of the ~110 codes with HTTP status and meaning; `ApiError` takes a catalog key | `shared/contracts/errors.ts` |
| OpenAPI | Generated from the zod schemas at build time into `docs/api/openapi.json`; served at `/v1/openapi.json` outside production | build script |
| Contract tests | Every route's real response in the test suite is validated against its response schema (in tests only, no runtime cost) | `test/contracts/*` |
| Golden fixtures | JSON examples of each payload and event, generated from the schemas and checked into `shared/contracts/fixtures/`. The server, Android and web test suites all decode the same files | `shared/contracts/fixtures/` |
| Client types | Web: TypeScript types generated from the JSON Schemas into the web repository by a script, with a checksum check in CI. Android: hand-written `@Serializable` models stay, verified against the fixtures (Kotlin generators would replace `ApiClient`, which works). iOS: `Codable` models verified against the same fixtures | per client |

### 4.3 Semantics to document (and test) per area

- **Message ids, idempotency:** client-generated `messageId` (8–64 chars), unique; a retry from the same device gets
  `duplicate: true` with the original `serverTs`, another device gets `409 conflict`.
- **Ordering:** each recipient device receives its queue in server insertion order (the queue row id). The hub holds
  live items back while a backlog flush runs, so a live item never overtakes the backlog.
- **Pagination:** queue (`GET /v1/queue`, limit ≤ 200, `more`), history (`GET /v1/history/items` by `seq` cursor), and
  the other list routes. Each list route states its cursor and order in its schema.
- **Reconnect and resync:** `hello` → backlog in batches (`queue` with `backlog: true`) → `queue.drained`; the client
  acks in batches; un-acked items are redelivered; `GET /v1/calls/active` recovers missed rings; `conv.updated` /
  `org.updated` tell clients to reload.
- **Uploads:** `POST /v1/attachments/uploads` → presigned S3 part URLs → `complete` (permission re-checked) → the
  envelope references the attachment id; downloads by short-lived presigned URL after a membership check.
- **AI:** §6.4.
- **Compatibility policy:** fields are only added within a version; clients ignore unknown fields and unknown event
  names; a breaking change needs a new path version and an adapter for the old one for at least two app releases;
  `minClientApi` rises only with the owner's approval. A `contracts` version is added to `hello` (additive).

## 5. Database and S3 ownership rules

### 5.1 One owner per collection or table

| Module | Owns (tables → collections, same names) |
|---|---|
| auth | auth_challenges, sessions, pairing_requests |
| accounts | accounts domain (accounts, account_sessions, account_codes, legacy_tickets, recovery, key backups), users, devices, user_blocks, nav_preferences, relationships domain, reminders, registration_invites |
| organizations | workspace domain (orgs, members, roles, categories, overwrites, bans, invitations, audit_log, join_applications, …), reports |
| messaging | conversations domain, relay domain's delivery_queue and submitted_messages, history domain |
| files | files domain (attachments, attachment_uploads, abandoned_uploads, pending_blob_deletes) and the S3 objects under the attachments and profile-photo prefixes |
| calls | calls, call_participants |
| notifications | push_endpoints, fcm tokens |
| administration | usage, experiments, ops data |
| federation | federation domain |

Other modules read or change these only through the owner's use cases (rule 2). The table is the starting point; the
boundary test makes it binding.

### 5.2 Message persistence

Two stores, both behind interfaces in the messaging module:

- `RelayStore`: enqueue a fan-out (one row per recipient device) with the idempotency record in one atomic step;
  fetch a device's backlog after a cursor; ack a batch; the retention sweep. `SqliteRelayStore` first (the existing
  SQL moved, unchanged), `MongoRelayStore` second. MongoDB needs a per-device monotonic sequence for ordering (a
  counter document per device, incremented in the same transaction as the fan-out) and a unique index on
  `messageId`.
- `HistoryStore`: exists (`history/store.ts`, `mongo/history.ts`) and moves into the module.

Rules kept: ciphertext only; 7-day queue TTL; ack deletes; per-device order; idempotency on `messageId`; fan-out
limit 1,000 recipient devices per envelope (a real ceiling for large channels, recorded in the audit, not changed
in 1.0).

### 5.3 S3

- File objects stay in S3 (`griipbuddy-orbit-media-sg`, ap-southeast-1); metadata stays in the files module's store.
- Only the files and accounts (profile photos) modules call `infrastructure/storage`. No other module builds S3 keys.
- Every presigned URL is issued after a permission check and is short-lived; upload completion re-checks permission.

### 5.4 MongoDB cutover rules (unchanged from `MONGODB.md`)

No data moves without: the owner's go-ahead, an encrypted off-host backup, a rehearsal on a production snapshot, the
copy-and-verify tool, a write freeze, and the `mongo-rollback` path tested. SQLite's copy stays untouched until the
owner approves cleanup. Order: files → federation → relay → conversations + workspace (with asynchronous `authz`) →
identity.

## 6. AI and encryption boundary

### 6.1 Where plaintext exists

| Data | Plaintext on | Plaintext on the server? |
|---|---|---|
| Messages, files, voice messages, call media | the sender's and recipients' devices, after decryption | **Never** |
| Encrypted history | the account's own devices | Never |
| Channel names, topics, category names, forum tags, event titles, community rules and guide, member display names | server (stored in clear for routing, permissions and discovery) | Yes |
| Draft the person is typing | that device | Only if the person sends it to AI |

### 6.2 Policy

1. Server-side AI receives only (a) text the person explicitly submits from their own device after a consent step
   naming the provider, model, region and exactly what is sent, and (b) server-held plaintext metadata for community
   features that a community admin has turned on.
2. Nothing is sent automatically or in the background; nobody's messages are sent by someone who couldn't read them.
3. The server keeps no prompt or answer; it keeps a usage record (who, feature, model, tokens, cost, time).
4. Off by default: `AI_ENABLED=false` server-wide, per-community "AI features" off, per-feature flags.
5. Model output and any retrieved context are untrusted input: validated, never executed, never used to widen access.
6. If a feature needs content that only clients hold, the feature runs from the client with consent, or it isn't
   built. The trust boundary is not changed in 1.0.

### 6.3 Module

```
modules/ai/
  domain/          feature catalog (draft-help, catch-up summary, community FAQ), limits, prompt ids @ versions
  application/     runTask(feature, input, user) → checks AI_ENABLED, community setting, permission, quota, size;
                   picks the model for the feature; streams; records usage; tools call other modules' use cases
                   with the requesting person's own authorisation (no service-level privileges)
  infrastructure/  usage store (ai_usage), quota counters
  transport/       POST /v1/ai/tasks (JSON, or text/event-stream when streaming), DELETE /v1/ai/tasks/:id (cancel)
infrastructure/ai-providers/
  ModelProvider    generate(request, signal) → AsyncIterable<chunk> + usage; one adapter per provider
  FakeProvider     deterministic, for tests; the default when AI_ENABLED=true and no provider is configured in tests
```

Cancellation through `AbortSignal` (client disconnect or `DELETE`); timeouts per feature; one retry on a provider's
retryable errors only; per-person and per-community daily and monthly token quotas; provider keys only in the server's
environment. No worker process and no queue until a chosen feature needs one (transcription, document processing); then
a second entrypoint in the same codebase (`npm run worker`), with the queue chosen from the deployment at that time.
No vector database: messages are encrypted, so there is nothing for a server-side index to hold except published
community knowledge, which is small enough for keyword search until measured otherwise.

### 6.4 Contract

`POST /v1/ai/tasks { feature, input: { text }, consent: { provider, model, region }, communityId? }` →
`200 { output, usage }` or a stream of `{ t: "delta", text }` … `{ t: "done", usage }`; errors `ai_disabled`,
`ai_quota_exceeded`, `ai_input_too_large`, `ai_provider_unavailable`, `ai_consent_mismatch` (the consent names a
different model than the server would use).

## 7. Client migration plan

Clients move only after the contract for their area is published (fixtures and OpenAPI exist) and the server passes
its contract tests. Mixed versions must keep working throughout: no client is forced to update by this work.

### 7.1 Web (repository `meldiq-web`: React + Vite today, Next.js by ADR 0008)

The contract adoption below comes first, on the current build; the Next.js migration (ADR 0008 steps F0–F3) follows
on top of it, so each step is testable on its own.

- Generated contract types replace hand-written payload types area by area; the WebSocket client switches on the
  `RealtimeEvent` union; unknown events are ignored.
- A queue item the web can't parse gets a placeholder (as Android does) instead of being acked and dropped.
- Lenient `.catch()` defaults on enums are replaced by an explicit "unknown" value the UI can show.
- Code for retired server modules goes: the AI client (`sync/ai.ts`, `ui/ai/*`, `ui/workspace/AiPage.tsx`), and
  the `inbox.*`, `integration.event` and `/v1/documents` handling. The new AI sheet comes after the server module.
- Web releases stay with the "Meldiq web parity with Android" session's process (check the live release before any
  install).
- Detail from the web audit: [`ARCHITECTURE_1_0_AUDIT.md` §5](ARCHITECTURE_1_0_AUDIT.md).

### 7.2 Android (Kotlin + Compose)

- Keep MVVM + repositories + manual DI; no new Gradle modules.
- Unit tests decode every golden fixture with the app's own models; mismatches are fixed in the models.
- Realtime frames parsed in one place into one sealed type; repositories subscribe to it. Outbound frames built
  with the serializer instead of string templates.
- The duplicated decoders (`attachmentsJson`, `callJson`, `richJson`) and the four preview functions collapse into
  one each, with tests; `MessageRepository` splits into send, receive and controls parts behind the same API.
- AI: one consent sheet, starting with draft help, after the server module exists.
- Detail: [`ARCHITECTURE_1_0_AUDIT.md` §4](ARCHITECTURE_1_0_AUDIT.md).

### 7.3 iOS (not present)

Gap report: no code, no Xcode project, no bundle id, no push certificate, no TestFlight. Implementation plan for an
owner-approved project:

1. SwiftUI app, iOS 17+ (CryptoKit has HPKE from iOS 17, matching `griip-envelope-v1`'s X25519/HKDF-SHA256/AES-256-GCM;
   Ed25519 via `Curve25519.Signing`). Verify against the shared vectors (`server/test/interop_vectors.test.ts`) first.
2. Networking: `URLSession` + `URLSessionWebSocketTask`, `Codable` models checked against the golden fixtures.
3. Storage: SQLite (GRDB) with the file protection class, keys in the Keychain (Secure Enclave where possible).
4. Calls: LiveKit Swift SDK with frame encryption; CallKit and PushKit for incoming calls; APNs for wake-ups (the
   server's push module gains an APNs adapter).
5. Order: sign-in and device setup → DMs → communities read-only → posting → files → calls.

### 7.4 Feature-parity checklist

Kept as a table in `docs/architecture/FEATURE_PARITY.md` (created in phase F) with one row per feature in
`docs/FEATURE_INVENTORY.md` and a column per client: works / partial / missing / not applicable, each with evidence.

## 8. Testing, compatibility and rollout

### 8.1 Baseline (2026-10-22, before any change)

Recorded in the audit §8: server typecheck clean, 717 passed / 10 skipped on SQLite and the same with the five
MongoDB-capable domains in MongoDB; web typecheck clean, 363 passed / 1 skipped; Android 651 JVM tests passed. Every phase must end at or above this baseline with no new skips.

### 8.2 Verification matrix

| Area | Server tests | Contract tests | Android | Web | Manual (owner's phone) |
|---|---|---|---|---|---|
| Sign-in, account migration (v1 → v2) | existing suites | auth, accounts schemas | unit | unit | phone |
| Membership and permissions | orgs, channel_permissions, b15_isolation | org schemas | — | — | phone |
| Messaging: order, duplicates, history, paging | messaging, ordering, history_* | message, queue, history | unit | unit | two phones |
| Cross-client sync and reconnect | ws_frame_auth, presence, call_reconnect | realtime fixtures | unit | unit | phone + web |
| Attachments and S3 | direct_uploads, files_1g, storage_s3, s3_live (manual) | attachments | unit | unit | phone |
| Realtime events and notifications | push_* | realtime fixtures | unit | unit | phone |
| Calls and screen share | call_* | calls | — | — | two devices |
| E2EE and privacy | interop_vectors, revoked_sender, identity_pinning | — | crypto tests | crypto tests | — |
| AI: streaming, provider failure, authorisation, quotas | new ai_* suites with FakeProvider | ai schemas | unit | unit | phone |
| Client compatibility | old-client fixtures (frames and responses from the last two releases) | yes | — | — | old APK |
| Migrations and rollback | mongo_migration, backup | — | — | — | rehearsal on snapshot |
| Boundaries | architecture_boundaries | — | — | — | — |

### 8.3 Rollout

- Work on a dedicated local branch `architecture-1.0` (plain name), one commit per step, owner-only attribution.
  `internal-beta` keeps the release candidate; the branch merges into it only after the owner's approval.
- Phases B and C change no route, event or data: they ship as a normal API release after the full suite, a rehearsal
  on a snapshot copy, and the usual rollback image.
- Data moves (MongoDB cutovers) are separate, owner-approved releases (§5.4).
- Health checks: `/healthz` (exists). Added: structured logs with a request id, per-route metrics (exists in memory),
  a `/v1/admin/health` field per module store. No message plaintext, keys or tokens in logs (the logger already
  redacts headers and never logs bodies).

## 9. Implementation sequence and affected files

| Step | Phase | What | Main files | Changes behaviour? |
|---|---|---|---|---|
| A | A | Audit and this plan | `docs/architecture/*` | No |
| B0 | B | CI's MongoDB pass uses the current domain list (`accounts,preferences,history,reports,relationships`) | `.github/workflows/ci.yml` | No |
| B1 | B | Create `app/`, `shared/`, `infrastructure/`; move bootstrap, config, errors, validation, storage, database | `app.ts`, `index.ts`, `config.ts`, `util.ts`, `context.ts`, `db.ts`, `mongo/*`, `storage/*` | No |
| B2 | B | Move modules one per commit, boundary test with allowlist | all of `src/routes/*` and domain folders | No |
| C1 | C | Contracts: realtime union, error catalog, typed `Hub` | `hub.ts`, `ws.ts`, every `sendTo*` caller, `util.ts` | No |
| C2 | C | HTTP schemas, OpenAPI generation, contract tests, fixtures | every route file | No |
| D1 | D | `RelayStore` (SQLite) behind the messaging use cases | `routes/messages.ts`, `hub.ts`, `jobs.ts` | No |
| D2 | D | Files store, then `MongoRelayStore`, tests on both stores | `routes/attachments.ts`, `mongo/*` | No (until a cutover) |
| D3 | D | Atlas connected (no data moved), then owner-approved cutovers domain by domain | VPS `.env`, `admin mongo-migrate` | Data home changes, behaviour doesn't |
| E1 | E | AI module with FakeProvider, disabled by default, quotas, usage, streaming, cancellation | `modules/ai/*`, `infrastructure/ai-providers/*` | Additive, off |
| E2 | E | First real provider adapter (owner's choice) | `infrastructure/ai-providers/*` | Off until enabled |
| F1 | F | Web: generated types, typed realtime handling, remove calls to the retired AI routes | `meldiq-web/src/*` | No visible change |
| F1n | F | Web: Next.js migration (ADR 0008: spike, shell, routes, build and deploy scripts) | `meldiq-web/*` | No visible change intended |
| F2 | F | Android: fixture tests, one realtime event type, AI consent sheet | `android/app/src/main/java/.../data/api/*` | AI sheet only |
| F3 | F | iOS gap report (done here) and, if approved, the app project | new repository | — |
| G | G | Verification matrix run, parity checklist, release | `docs/architecture/*` | — |

Existing features affected: none functionally in B, C and D1 (pure moves and typing). D2's MongoDB store is used only
after a cutover. E adds routes behind flags. F changes client internals, with the web's removal of dead AI calls as the
only visible difference (a Settings entry that already fails today, per the web audit).

## 10. Owner decisions (answered 2026-10-22)

| # | Question | Owner's answer | Consequence |
|---|---|---|---|
| D1 | Web framework | **Migrate Meldiq Web to Next.js** | ADR 0008 below; phase F gains a web migration track (§7.1) |
| D2 | iOS | **Gap report and plan only** | §7.3 stays a plan; no iOS code in 1.0 |
| D3 | MongoDB | **The owner provisions MongoDB Atlas now** | Cutovers are scheduled during phase D, each still with a backup, a rehearsal, a maintenance window and the owner's go-ahead (§5.4) |
| 5 | Sequencing | **Own branch, alongside the batches** | Local branch `architecture-1.0`; merged into internal-beta only with approval |
| 4 | AI provider and first feature | Open | Needed before step E2 (E1 runs on the fake provider) |

### What the owner does for Atlas (nothing is sent to Claude)

1. Create an Atlas project and a dedicated cluster on AWS in **ap-southeast-1** (M10 or larger: replica set, backups,
   transactions; M0/M2 only for a staging rehearsal).
2. Create a database user with `readWrite` on the database `meldiq` only.
3. Network access: allow only the VPS address 72.60.107.88.
4. Put `MONGODB_URI` (a `mongodb+srv://` address) and `MONGODB_DB=meldiq` into `/opt/griip-internal-messaging/.env` on
   the VPS yourself, and leave `MONGODB_DOMAINS` empty. Don't paste the URI or password into a chat.
5. Say when it's done. The first server release after that only connects and prepares collections and indexes
   (`/healthz` then shows `mongodb: true`); no data moves until a cutover you approve.

First cutover candidates (already run on both stores in CI): preferences, relationships, reports, history,
accounts. Then files, federation, relay, conversations + workspace, identity, each after its store move in phase D.

## ADR 0008 (accepted 2026-10-22): migrate Meldiq Web to Next.js

- **Context:** the brief names Next.js; the existing web app is React 19 + Vite 7, served as static files by nginx,
  with a hash router, a custom store, a service worker, IndexedDB key storage and WebCrypto. The owner chose Next.js.
- **Decision:** move Meldiq Web to Next.js in phase F, after the contracts are stable, without changing behaviour.
- **How (incremental, no big-bang rewrite):**
  1. **F0 spike:** a Next.js shell that mounts the existing React tree as one client component, built with static
     export so hosting stays static files behind nginx. It must keep the current CSP (`script-src 'self'`): Next.js
     App Router pages emit inline scripts, so the spike decides between the Pages Router (whose page data is a
     non-executed JSON script), CSP hashes, or a small Node runtime with per-request nonces. The spike also proves
     the service worker build, IndexedDB/WebCrypto, LiveKit and the E2EE worker, and Safari 16.4+.
  2. Replace the hash router with Next.js routes screen by screen; keep the engine (`sync/engine.ts`), crypto and
     store unchanged. All app screens stay client components: content is encrypted, so the server renders only the
     shell.
  3. Swap the build and `deploy/scripts/*` to the Next.js output; keep the release, install and rollback scripts'
     shape so the live release process doesn't change.
  4. Done when the unit suite, the live suite and the 47 Playwright tests pass on the Next.js build in Chromium,
     Firefox and WebKit.
- **Consequences:** a large diff in `meldiq-web`; risk to the strict CSP and to the service worker (handled in F0);
  the work belongs to the web parity session's release process ([`web-deploys`] rule: check the live release before
  installing). Public pages (marketing, community previews) can later use server rendering where it helps.

## 11. Progress log

### Phase B (2026-10-22, branch `architecture-1.0`, not merged, not deployed)

| Commit | Step | What |
|---|---|---|
| `25da76e` | B0 | CI's MongoDB pass runs `accounts,preferences,history,reports,relationships` (it ran the retired `integrations` and skipped three stores) |
| `b3aa6d3` | B1–B2 | 119 files moved with `git mv` into `app/`, `modules/<10 modules>/`, `infrastructure/{database,storage,mail}/`, `shared/utilities/`; 523 import paths rewritten; no other code change except the migrations path in `infrastructure/database/db.ts` and one error message. Map: [`SERVER_MODULE_MAP.md`](SERVER_MODULE_MAP.md) |
| `a5dcb71` | B2 | `test/architecture_boundaries.test.ts`: import rules (78 existing exceptions) and table ownership (146 existing file→table accesses outside the owner), both shrink-only; every migration table has an owner |

Tests: typecheck clean; server 717 passed / 10 skipped on SQLite and in MongoDB mode before the boundary test, 721
with it; `npm run build`; a smoke start of `dist/index.js` (44 migrations applied, `/healthz`, `/v1/server-info`) and
`dist/admin.js`.

Known limits, deliberately left for later steps:
- `app/context.ts` still mixes the context type, authentication helpers and validation primitives, and
  `shared/utilities/util.ts` holds `ApiError`: splitting them is C1 (with the error catalog), so B stays a pure move.
- Modules have no `index.ts` public API yet except `accounts`; cross-module calls are the 78 allowlisted imports.
- Layer subfolders (domain/application/infrastructure/transport) are introduced per module when it gets a store
  interface (D1 messaging first), not as empty folders.
- Older documents name the old paths (`SERVER_MODULE_MAP.md` translates).
- Merging into internal-beta will conflict with any batch that edits the same server files; git's rename detection
  handles most of it, and B is easiest to merge before the next server batch lands.

Next safe step: C1 (realtime event union, error-code catalog, typed `Hub`, split of `context.ts`/`util.ts`), still
without any route, event or data change.

### Phase B merged (2026-10-22)

The owner asked to merge B before the next server batch: `internal-beta` fast-forwarded to `5470943` (local, not
pushed). Checked in the main folder: typecheck clean, 721 passed / 10 skipped.

### Step C1 (2026-10-22, branch `architecture-1.0`)

| Commit | What |
|---|---|
| `d7fe59a` | `app/context.ts` keeps only `AppContext`; authentication → `modules/auth/index.ts` (the auth module's public API); `parse` and the zod primitives → `shared/validation/`; `ApiError` and helpers → `shared/errors.ts`. 46 importers rewritten by symbol |
| `a13722d` | Error-code catalog `ERROR_CODES` in `shared/errors.ts`: 168 codes (35 federation) with statuses and meaning; `ApiError`, `FederationError` and the permission refusals only take catalog codes; `test/error_catalog.test.ts` checks statuses and dead codes. A federated call host's error code is passed on only if catalogued |
| `86ce795` | Realtime contract `shared/contracts/realtime.ts`: `ServerEvent` (21 frame names) and `ClientEvent` (4); `Hub.send*` and every frame written take `ServerEvent`; `callView` returns `CallWire`; queue items `QueueItemWire` |

Security fix found by typing the frames: a call event relayed by a peer server was built as
`{ t: "call", event, call, ...extra }` with the peer's `extra` spread last, so a malicious peer could replace `t` and
`call` and push any frame (for example `org.deleted`) to this server's clients. Federation is off in production, so it
was never exposed. Extras from peers are now limited to the contract's typed fields (`test/realtime_contract.test.ts`).

Still to do in C (C2): zod schemas for HTTP bodies and responses, OpenAPI generation, contract tests and golden
fixtures (the realtime types become zod schemas there too, so fixtures and JSON Schema come from one source).

### C1 merged (2026-10-22)

`internal-beta` fast-forwarded to `3e2361b` (local, not pushed). Main folder: 727 passed / 10 skipped.

### Step C2, first part (2026-10-22, branch `architecture-1.0`)

| Commit | What |
|---|---|
| `bdc5d19` | Contracts as zod 4 schemas (`zod/v4` from the installed zod 3.25, no new dependency): `shared/contracts/realtime.ts` and `shared/contracts/http/` for 30 routes (system, messaging and queue, history, files, calls), whose routes now validate input with them. Optional `ContractObserver` in `buildApp` (tests only) checks every documented answer, every error answer on every route, and every frame; `npm run contracts`; `test/contracts.test.ts` (drift, stale contracts, fixtures) |
| `7c88bde` | Generated `docs/api/openapi.json`, `docs/api/realtime.schema.json`; 162 golden fixtures in `shared/contracts/fixtures/` (28 HTTP, 115 errors, 19 frame types) |

What the checks found on the first full run: every error answer on all routes already had the envelope and a
catalogued code, and every frame matched; only the catalog's statuses for the five invite codes were wrong (fixed).

Open in C2:
- The other ~200 routes, area by area (accounts and auth, communities, conversations, users, the rest), each with its
  input schemas moved into the contract, as done here.
- Fixtures not captured yet because no test exercises them: `POST /v1/queue/ack` (tests ack over the WebSocket) and
  the frames `org.reports`, `org.applications`, `org.application`, `org.alert`, `prefs.updated`,
  `connections.updated`, `meeting.*`.
- OpenAPI inlines repeated schemas (`CallWire` …); named components come with the next areas.
- A path that matches no route answers Fastify's own 404 body; Android detects older servers by it. Moving it to the
  envelope needs a compatible form first.
- Client side (phase F): Android and web unit tests decode the fixtures; web types generated from the JSON Schemas.

### C2 part 1 merged (2026-10-22)

`internal-beta` fast-forwarded to `e83d222` (local, not pushed). Main folder: 731 passed / 10 skipped.

### Step C2, remaining routes (2026-10-22, branch `architecture-1.0`)

Done area by area in five parallel worktrees (one commit each, cherry-picked here), then integrated:

| Commit | Area | Routes |
|---|---|---|
| `042edce` | one contract file per area, so the areas share no file | — |
| `3a14861` | `docs/api/COVERAGE.md` (generated) and tests for frames no suite reached | — |
| `340be1f` | accounts (`/v2/auth`, `/v2/account`, `/v2/migration`, `/v2/workspaces`); `modules/accounts/schemas.ts` removed | 30 |
| `3f82e41` | community features (categories, overwrites, discovery, bans, guest policy, security, timeouts, applications, expressions, onboarding, guide, reports) | 37 |
| `6340c36` | identity (v1 auth, pairing, devices, users, blocks, presence, push, connections, preferences, reminders) | 47 |
| `e948958` | identity modules take enums and rules from the contracts instead of copies | — |
| `79d0f32` | communities and roles (orgs, members, invites, channels, roles, ownership, usage, plan, audit) | 34 |
| `1fc9c55` | conversations, search, meetings, admin, experiments, federation client routes (`requires: "federation"`) | 34 |
| `a51f9ad` | one definition each for the user summary, conversation view and community view; tests for the last unexercised routes; recovery codes redacted in fixtures | — |

Result: **211 of 211 client-facing routes documented and verified at runtime** (every one answered in the suite and
matched its strict schema); frames 25 of 28. Excluded on purpose: the federation peer protocol, the core node, the
LiveKit webhook, the WebSocket upgrade. Server 741 passed / 10 skipped on SQLite and in MongoDB mode; build passes.

Behaviour notes (no route, status or code changed):
- Built-in validation messages of documented routes now come from zod 4 (wording of `details[].message`).
- zod 4's `int()` accepts only safe integers, so an integer above 2^53 in a request is now a 400 (none is meaningful).
- Inconsistencies documented as they are, to settle in a later compatible change: `POST /v1/orgs/:orgId/categories`
  can answer `{}` and `PATCH /v1/categories/:id` `{ category: null }` when the creator can't see an empty category;
  `GET /v1/auth/username-available` has two shapes; the success bodies vary (`{ok:true}`, `{blocked}`,
  `{status:"sent_if_pending"}` …); `GET /v1/devices` reports the person's online state on every device; hidden profile
  fields are sometimes null and sometimes absent; fixed legacy fields (`branches`, `teams` …) remain for older apps.
- Constants that shared/contracts can't import from modules are copied once with a compile-time equality check
  (permission keys, role icons, template ids, channel capabilities, report reasons, event repeats); identity's copies
  were replaced by imports in the other direction.

Next: phase D (persistence interfaces) and, in parallel on the client side, phase F's first step: the Android and web
unit tests decode `shared/contracts/fixtures`.

### C2 merged (2026-10-22)

`internal-beta` fast-forwarded to `a1ed0d7` (local, not pushed). Main folder: 741 passed / 10 skipped.

### Step D1: the relay behind a persistence interface (2026-10-22, branch `architecture-1.0`)

| Commit | What |
|---|---|
| `aedf4aa` | `RelayStore` (async) and `SqliteRelayStore` with the existing SQL; sending, acknowledging, the REST queue, the WebSocket backlog, retention, push wake-ups, health, reports, federation delivery and five deletion cascades use it. The relay tables now belong to messaging alone (13 table exceptions gone). The backlog flush holds live items instead of relying on a synchronous query |
| `57908cd` | `MongoRelayStore` and the `relay` MongoDB domain (calls split into their own `calls` domain); per-device work queue in the hub; outbox room checked before a message is accepted; tests on both stores; CI's MongoDB pass includes `relay`; `deploy/scripts/validate-env.sh` accepts the current domains |

How the MongoDB relay keeps today's guarantees:

| Guarantee | SQLite (before) | MongoDB |
|---|---|---|
| A message id is accepted once | `submitted_messages` primary key in one transaction | the same key; record and items in one transaction; a concurrent duplicate answers as a retry |
| Items per device in id order, ids numeric | AUTOINCREMENT | a counter in `orbit_meta`, started above both MongoDB's and SQLite's highest id (never reused after a migration) |
| Live items don't overtake the backlog, nor arrive twice (P6) | query and send in one synchronous step | items arriving during a flush are held and sent after the last batch, minus those a batch carried (`test/hub_backlog.test.ts`) |
| Acknowledgements before a reconnect's backlog | synchronous acks | a per-device work queue: a device's frames, REST acks and a new connection's first flush run in order |
| Accepting a message also updates conversations, @everyone records, the federation outbox | same transaction | a SQLite transaction right after the commit (logged if it fails; the message is delivered either way); a full outbox is refused before accepting |
| Deletion cascades | inside the caller's transaction | right after it; anything left behind expires within the queue lifetime |

Production: unchanged (SQLite). The relay's cutover waits for Atlas and a load test of send/ack throughput.

### D1 merged (2026-10-22)

`internal-beta` fast-forwarded to `d884e16` (local, not pushed). Main folder: 749 passed / 10 skipped.

### Step D2: the files store (2026-10-22, branch `architecture-1.0`)

| Commit | What |
|---|---|
| `aa1f26c` | `FileStore` (async) with `SqliteFileStore` (the existing SQL) and `MongoFileStore` (`files` domain); attachments, direct uploads, retention, kept files, quotas, usage figures, plan limits, federation's pushed attachments and three deletion cascades use it; the files tables now belong to the files module alone (14 table exceptions gone; the operator-only org repair report still counts them in SQLite) |

What changed to keep today's guarantees on an asynchronous store:

| Guarantee | Before (SQLite) | Now |
|---|---|---|
| Parallel uploads see each other's quota reservations | check and reserve with no `await` between | check and reserve inside a per-process lock (`shared/utilities/lock.ts`) |
| Exactly one of "complete an upload" and "expire it" wins | one SQLite transaction | the same in SQLite; one MongoDB transaction |
| Quotas per community, for new accounts together, per peer server | SQL joins with conversations and users | the store sums over ids the owning module supplies (`authz.conversationIdsOf`, `limitedUserIds`, the peer's users) |
| A deleted conversation's files and uploads go with it | foreign-key cascade | `files.forgetConversation` in the same transaction (SQLite) or right after it (MongoDB); stored bytes are deleted once those cascades have run |
| Usage gauges written in one transaction | sums inside it | stored bytes read first, then every gauge written in one transaction |

Production: unchanged (SQLite; files in S3). The `files` cutover can follow the five ready domains once Atlas exists.

Tests for D2: 755 passed / 10 skipped on SQLite and with accounts, preferences, history, reports, relationships, relay
and files in MongoDB (CI's MongoDB pass now includes files); build passes. Two findings on the way:
- The shared test MongoDB aborted mid-run with EMFILE: every test server makes its own database, and WiredTiger kept
  file handles past macOS's 10,240 per process. `test/support/mongoGlobal.ts` now closes idle handles after seconds and
  caps the cache (test-only; production has one database).
- `push_wakeup.test.ts` › "coalesces wake-ups to one per window" failed in 2 of 4 full MongoDB-mode runs on this laptop
  under load and passed every time alone: its 150–900 ms windows are tight when MongoDB round trips slow down. Left as
  is and noted, not hidden; widen its windows if CI shows it.

### D2 merged (2026-10-22)

`internal-beta` fast-forwarded to `9201ff1` (local, not pushed). Main folder: 755 passed / 10 skipped, build passes.

### Step F1: clients decode the shared fixtures (2026-10-22)

| Where | Commit | What |
|---|---|---|
| Android | `f65735a` | `ContractFixturesTest`: the 154 answers the app decodes (table generated from its typed `api.get<T>(…)` calls plus the account client), every frame and every error, with `GriipJson` (must pass) and a strict decoder without coercion (shrink-only allowlist, empty today). Proven to fail on a corrupted fixture. Android 654 passed |
| Web | `meldiq-web` `96ab083` (branch `contract-fixtures`, by the web parity session) | `test/contract-fixtures.test.ts`: a copy of the fixtures (`scripts/sync-contract-fixtures.ts`, `--check` fails on drift) decoded with the web's own schemas. The route table is found by scanning `src/**` for `json`/`call` with their schemas: 309 calls reach 184 of 211 routes, 158 decodes against 134 fixtures, none fail. Every frame goes through the new `src/sync/frames.ts` `parseFrame` (the engine uses it; `pong` and `org.alert` are ignored on purpose); every error is read through `ApiClient`. A strict pass records where a `.catch()` replaced a value: 0 today. Proven to fail on a wrong-typed fixture, an unknown enum value and a drifted copy. Web 366 passed / 1 skipped, tsc clean (rerun here) |

The web's allowlist has 99 "unrouted" entries: web calls to routes the server no longer has, all retired features
(AI, documents, inbox, integrations, workflows, webhooks, api-keys, groups, branches, teams and departments,
developer catalog, workspace templates, add-group). Removing them is the web side of F1 in the step table above, with F2.

Found while mapping: the app still calls three routes the server no longer has, from retired workspace screens:
`GET /v1/orgs/:orgId/groups` (MeetingsScreen), `GET /v1/workspace-templates` and `POST /v1/orgs/:orgId/apply-template`
(WorkspaceC5Controls). They fail with Fastify's 404 (the app's "unsupported"); removing them belongs with F2.

Local note: Gradle doesn't know the fixtures (outside the module), so after they change run the test with `--rerun`;
CI builds clean and always runs it. Declaring them as an input needs `build.gradle.kts`, left alone here.
