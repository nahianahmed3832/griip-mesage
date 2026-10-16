# Technical debt (audit, 2026-10-16)

Severity: **High** = can cause a security failure, data loss or an outage. **Medium** = correctness or operability. **Low** = hygiene.

Items already scheduled as features are cross-referenced to `FEATURE_ROADMAP.md`.

## Security and correctness

| # | Debt | Where | Severity | Roadmap |
|---|---|---|---|---|
| D1 | ~~The certificate check uses the server's current identity key, not the pinned one~~ **Fixed 2026-10-16 (T1.2)** in the app and the reference client | `Directory.kt`, `griipClient.ts` | High | T1.2 |
| D2 | Demotion to Guest relies on `revalidateChannelMemberships`, whose GUEST branch keeps everything. Branch and team rows also survive | `server/src/authz.ts`, `routes/orgs.ts` | High | T1.1 |
| D3 | ~~No call eviction on membership change~~ **Fixed 2026-10-16 (T1.3), not deployed** | `server/src/routes/calls.ts`, `jobs.ts` | High | T1.3 |
| D4 | ~~The catch-all in `processQueue` acks after any exception~~ **Fixed 2026-10-16 (T1.4)** | `QueueProcessor.kt` | High | T1.4 |
| D5 | ~~Open registration and no per-user quotas~~ **Fixed in code 2026-10-16 (T1.6), not deployed** | `routes/auth.ts`, `routes/attachments.ts`, `compose.yaml` | High | T1.6 |
| D6 | ~~`Hub.Conn.flushing` is declared but unused~~ **Fixed 2026-10-16 (T2.1):** it gates live pushes until the backlog is drained, and stops the empty-backlog acknowledgement loop | `server/src/hub.ts`, `ws.ts` | Medium | T2.1 |
| D7 | ~~`RealtimeClient` drops frames with `tryEmit` into a bounded buffer~~ **Fixed 2026-10-16 (T2.2)**: `FrameInbox` | `FrameInbox.kt`, `RealtimeClient.kt` | Medium | T2.2 |
| D8 | ~~`call_invite` keys are accepted from any member (first wins)~~ **Fixed in code 2026-10-16 (T5.2)**: only the call's creator. Device check pending | `MessageRepository.apply` | Medium | T5.2 |
| D9 | ~~Invitations aren't re-checked against the creator's current rights~~ **Fixed 2026-10-16 (T3.1), not deployed** | `invites.ts`, `authz.ts` | Medium | T3.1 |
| D10 | ~~Presence and last-seen are readable by anyone~~ **Fixed 2026-10-16 (T1.7), not deployed** | `routes/users.ts`, `authz.ts` | Medium | T1.7 |
| D11 | ~~The activity feed filters by action type, not scope~~ **Fixed 2026-10-16 (T1.8), not deployed** | `routes/orgs.ts` | Medium | T1.8 |
| D12 | Sender lookup excludes revoked devices, losing earlier messages | `routes/users.ts` `deviceDirectory`, `Directory.kt` | Medium | T1.5 |

## Data, storage, retention

| # | Debt | Where | Severity |
|---|---|---|---|
| D13 | ~~Room `version = 1` with no migrations~~ **Fixed 2026-10-16 (T2.3):** explicit migration steps, no destructive upgrade fallback, JVM `MigrationsTest` (D-13) | `Database.kt`, `Migrations.kt` | High (before any schema change) |
| D14 | Channel fan-out stores the **full ciphertext once per recipient device**: up to 1,000 recipients × 128 KiB per message. Storage grows with members × devices | `routes/messages.ts`, `delivery_queue.payload` | Medium |
| D15 | ~~Attachments are buffered fully in memory~~ **Fixed 2026-10-16:** server uploads stream (T1.6). The client streams pick, encrypt, upload and download (T2.6). Remaining: GCM decryption buffers one ≤ 25 MB file inside the cipher. Constant memory needs a chunked attachment format version | `AttachmentCrypto`, `ApiClient` | Low |
| D16 | ~~`calls`, `call_participants` and `audit_log` are never pruned~~ **Fixed 2026-10-16:** 90-day and 365-day retention in the cleanup job | `jobs.ts` | Low |
| D17 | ~~No `PRAGMA secure_delete`~~ **Fixed 2026-10-16** (WAL note in `RETENTION.md`) | `db.ts` | Low |
| D18 | ~~Decrypted attachment files survive message deletion~~ **Fixed 2026-10-16 (T1.10)** | `AttachmentFiles.kt`, `MessageRepository` | Medium (T1.10) |
| D19 | ~~`setInterval` cleanup can overlap itself~~ **Fixed 2026-10-16** with T1.3: in-flight guard, and LiveKit calls time out after 5 s | `jobs.ts`, `livekit.ts` | Low |
| D20 | ~~`lastTyping` map in `ws.ts` grows without bound~~ **Fixed 2026-10-16:** pruned past 5,000 entries | `ws.ts` | Low |

## Testing and verification

| # | Debt | Severity |
|---|---|---|
| D21 | ~~No Android tests at all~~ **Partly fixed 2026-10-16 (T2.4):** JVM unit-test harness with crypto, interop and video-sink tests (25 tests). Still missing: repository tests (added with T1.2 and T1.4), Room migration tests and UI tests | High (T2.4) |
| D22 | ~~No shared crypto test vectors between Kotlin and Node~~ **Fixed 2026-10-16:** `android/app/src/test/resources/griip-envelope-v1-vectors.json`, verified by both `server/test/interop_vectors.test.ts` and `EnvelopeInteropTest` | Medium |
| D23 | Release (R8) build succeeds (`assembleRelease`, 64 MB unsigned) but has never been run on a device. Keep rules for LiveKit, BouncyCastle, SQLCipher and serialization exist but are unproven | Medium |
| D24 | `TESTING.md` mixes device evidence from different builds. Screen share was verified before the teardown fix | Low |
| D25 | The production smoke test (`test/e2e/prod-smoke.ts`) creates real accounts on production each run (four `e2e.*` accounts exist) | Low |

## Operations and deployment

| # | Debt | Severity |
|---|---|---|
| D26 | ~~Compose limits memory only~~ **`cpus` and `pids_limit` added 2026-10-16 (T1.6), not deployed.** No Docker volume quota is possible here, so attachment caps plus `verify.sh` disk warning stand in | Medium (T1.6) |
| D27 | No TURN, so restricted networks can't place calls | Medium (T5.1) |
| D28 | ~~Public `/healthz` reveals the online device count~~ **Fixed 2026-10-16** | Low |
| D29 | ~~Admin CLI `disable` can't close live sockets~~ **Fixed 2026-10-16 (T3.3), not deployed:** the API's cleanup job closes sockets of disabled accounts (within 60 s) | Low (T3.3) |
| D30 | No backups of permanent org metadata | Medium (owner decision) |
| D31 | Single process with an in-memory hub, so it can't scale horizontally | Low for the beta |

## Code structure

| # | Debt | Severity |
|---|---|---|
| D32 | `MessageRepository` (550 lines) mixes outbox, receive pipeline, attachments, receipts and call-key handling. Split it once tests exist | Low |
| D33 | `routes/orgs.ts` (760 lines) mixes structure, membership, invites, channels and activity | Low |
| D34 | String-built JSON frames on Android (`"""{"t":"ack",…}"""`) instead of serializers | Low |
| D35 | `ApiClient` is a concrete class used directly by repositories, which makes them hard to test. Introduce an interface or fake only where tests need one | Low (T2.4) |
| D36 | No tests run Room DAO queries: Room on the JVM needs Robolectric or instrumented tests. Queries are checked only at compile time (for example the thread scoping fixed in Phase 14) | Low (T2.4) |
