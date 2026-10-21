# Meldiq architecture: today and the target (2026-10-21)

Master prompt §8. Detailed documents:
- `docs/ARCHITECTURE.md`: the system as built;
- `MELDIQ_MONGODB_MIGRATION.md`: data;
- `MELDIQ_AWS_ARCHITECTURE.md`: hosting;
- `MELDIQ_REALTIME_MEDIA_PLAN.md`: calls;
- `MELDIQ_AI_ARCHITECTURE.md`: AI.

## 1. Today

```
Android (Kotlin/Compose, Room+SQLCipher, keys in Keystore)      Meldiq Web (React/Vite, IndexedDB, WebCrypto)
        │  HTTPS + WebSocket (ciphertext, metadata)                       │
        ▼                                                                 ▼
Meldiq API: Fastify 5 / TypeScript / Node 22, one process (API 0.13.0)
  accounts · identity · communities (orgs) · roles & permissions · conversations · relay (delivery queue)
  attachments (S3 presign) · calls (LiveKit tokens) · history · push (FCM, ntfy) · federation (off) · jobs
        │ SQLite (WAL) on the VPS · MongoDB optional per domain · S3 ap-southeast-1 · SES
LiveKit 1.13.10 (same VPS) for media, end-to-end encrypted frames
```

- **One process** holds the WebSocket hub in memory. Jobs (retention, usage, reminders) run on timers in the same
  process.
- **Domains** are modules under `server/src` (`accounts/`, `permissions/`, `routes/*`, `history/`, `push/`, …),
  with a store interface where a domain is MongoDB-ready.
- **Security boundary:** the API authenticates every request and authorises in `authz.ts` and `permissions/`. Content
  is end-to-end encrypted. The server holds public keys, ciphertext, hashes and the metadata it needs for routing,
  membership and permissions.

## 2. Target, and why not microservices now

Meldiq stays **one deployable API** (a modular monolith) until load or ownership makes a split pay off. Splitting
now would add network hops on the message hot path, and distributed transactions across domains that still share
SQLite. The steps that do pay off:

1. **Data:** every domain behind a store interface, then MongoDB as the only database (migration doc §3 order).
   `authz` becomes asynchronous with a per-request snapshot.
2. **More than one API task:** move the hub's fan-out to a shared channel (MongoDB change streams or Redis pub/sub)
   and move timers to EventBridge Scheduler + SQS. Then any task can serve any socket, behind an ALB with
   WebSocket support.
3. **Workers:** push fan-out, retention, AI tasks and media cleanup move to SQS consumers in the same codebase (a
   second ECS service from the same image), with idempotent handlers and DLQs.
4. **Media:** LiveKit on its own instance or instances (UDP), scaled independently of the API.
5. **Candidates for a separate service later:** media coordination (if call volume dominates) and AI (if model
   latency or cost isolation needs it). Both already sit behind narrow interfaces (`livekit.ts`, the planned
   `src/ai/`).

## 3. Cross-cutting rules (already followed, keep them)

- Input validation with zod at every route. Specific error codes. Rate limits on authentication, invites, joins and
  uploads (`@fastify/rate-limit`, `abuse.ts`).
- Idempotency on message ids, upload completion and joins.
- Audit events for every administrative change (`ctx.audit`). No content and no secrets in logs.
- Permission changes take effect on the next request and on live calls (eviction). Apps are told to reload through
  WebSocket events.
- Tests run on both stores for MongoDB-ready domains (`MELDIQ_TEST_MONGO_DOMAINS`).
