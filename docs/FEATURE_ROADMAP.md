# Feature roadmap (feature-first phase)

16 October 2026. Built from `FEATURE_INVENTORY.md`. The UI/UX redesign is postponed. Every item below keeps:
- the Android package `com.griipbuddy.message`;
- the Compose project `griip-internal-messaging`;
- the current screens.

Items are ordered by the agreed priorities:
1. Critical security or data-integrity defects.
2. Core messaging and reliability.
3. Organisation and access-control correctness.
4. Core collaboration.
5. Voice/video and screen sharing.
6. Decentralisation readiness.
7. Secondary enhancements.

Each item lists **purpose · dependencies · affected modules · acceptance criteria · risks · verification**.

## Do not rebuild

Extend these, don't replace them:
- `authz.ts` and the org model;
- challenge auth and device certificates;
- the `MessageCrypto`/`griip-envelope-v1` pipeline;
- the server queue, receipts and retention job;
- encrypted attachments;
- LiveKit signalling and token issuance;
- media keys delivered inside encrypted messages;
- the design system and screens;
- the deploy scripts;
- the Node reference client;
- Griip Bot.

---

## Tier 1: critical security and data integrity

### T1.1 Demotion to Guest revokes branch, team and channel access (P1). **Done 2026-10-16 (server; not deployed)**
- **Purpose:** an org member demoted to Guest currently keeps every channel, including private team channels, and keeps receiving their messages. Guests must have explicit-only access (`ARCHITECTURE.md` §5).
- **Dependencies:** none.
- **Modules:** `server/src/authz.ts`, `server/src/routes/orgs.ts` (role change), `server/test/orgs.test.ts`.
- **Acceptance:**
  - Demotion to Guest deletes the user's branch and team memberships in that org.
  - It removes all their channel memberships in that org.
  - It notifies the affected conversations and the user.
  - The user disappears from `/v1/conversations/:id/devices` and gets 404 on those channels.
  - A manager can still add the guest to a specific channel explicitly, and that grant survives later revalidation.
  - Existing guests' explicit memberships are untouched by unrelated changes.
  - DMs and groups are unaffected.
- **Risks:** removes access people may have relied on. That's intended, but say so in release notes. Re-granting is manual.
- **Verification:** new vitest cases through the real API: demote, check memberships, keys, posting and re-grant. Run the full suite.

### T1.2 Enforce pinned identity keys (TOFU) on Android and in the reference client. **Done 2026-10-16** (owner decision: block, don't just warn; device check pending)
- **Purpose:** today a relay that substitutes a user's identity key gets future messages encrypted to its devices, and its forged messages are accepted. There's only a DM-only warning. This contradicts `SECURITY.md` §3.
- **Dependencies:** an owner decision. The recommendation is to **stop encrypting to, and accepting from,** a user whose identity changed until the local user accepts the new key, with a warning in every conversation type. Also T2.4, so it can be unit-tested.
- **Modules:**
  - `data/repo/Directory.kt`: verify certificates against `pinnedIdentityKey`; treat a mismatch as untrusted.
  - `MessageRepository`: show a held or failed state.
  - `ConversationScreen`: banner for groups and channels.
  - `test/support/griipClient.ts` and the bot: same rule.
  - `SECURITY.md`.
- **Acceptance:**
  - With a substituted key, no envelope is sealed to the new devices, and envelopes signed by them are not shown as genuine.
  - After the user accepts the new identity, messaging resumes.
  - The first contact still pins automatically.
- **Risks:** a legitimate re-registration (lost phone without the recovery key) blocks messaging until someone accepts. The UX must make that one tap with a clear warning.
- **Verification:** JVM tests for `KeyDirectory` with a fake API (needs T2.4), plus reference-client tests with a substituted key served by a test harness override.

### T1.3 Evict removed members from live calls (P3). **Done 2026-10-16 (server; not deployed)**
- **Purpose:** someone removed from a conversation, org, branch or team stays in its LiveKit room. `SECURITY.md` §7 claims the opposite.
- **Dependencies:** none (server only). Key rotation is T5.4.
- **Modules:** `routes/conversations.ts` (remove member), the `routes/orgs.ts` revalidation paths, `routes/calls.ts` (helper), `livekit.ts`, tests.
- **Acceptance:**
  - Any membership removal calls `removeParticipant` for every device of that user in active calls of the affected conversations.
  - It marks them left and broadcasts `updated`.
  - `join` and `connected` keep returning 404 for them.
- **Risks:** LiveKit unreachable. Retry from the 60 s reconciliation job by comparing room participants with membership.
- **Verification:** vitest with a recording LiveKit stub (as in probe P3). Also run once on production with Griip Bot.

### T1.4 Never discard a message because of a local failure (Android)
- **Purpose:** `processQueue` acks and deletes queue items after *any* exception: cancellation, database errors, bugs in `apply`. Each case loses a message permanently.
- **Dependencies:** T2.4 for tests.
- **Modules:** `MessageRepository.processQueue/pollQueueOnce`.
- **Acceptance:**
  - Only cryptographic or format failures (`EnvelopeException`, JSON decode of the decrypted payload) are recorded as undecryptable and acked.
  - `CancellationException` is rethrown.
  - Other exceptions leave the item un-acked for redelivery, with a retry limit and a visible error after repeated failure.
- **Risks:** a poisoned item could redeliver forever. Cap attempts per queue id.
- **Verification:** JVM tests that inject failures into a fake DAO and API.

### T1.5 Keep messages from devices revoked after sending (P7)
- **Purpose:** revoking a phone currently destroys the messages it sent that are still queued.
- **Dependencies:** a protocol addition shared by server and clients.
- **Modules:**
  - Server: the `deviceDirectory` sender lookup returns revoked devices with `revokedAt`.
  - Android: `Directory.senderSigningKey`.
  - The reference client.
- **Acceptance:** an envelope whose `serverTs` is earlier than the sender device's `revokedAt` verifies and decrypts. Later ones are rejected. Revoked devices are still never *encryption* targets.
- **Risks:** a stolen device could keep sending until revoked (unchanged). Rejecting items queued after revocation still holds because the server already deletes them.
- **Verification:** extend the P7 probe into a permanent vitest; add an Android JVM test (T2.4).

### T1.6 Protect the shared VPS: registration gating, upload quotas, CPU and disk limits (P5). **Done 2026-10-16 (server and app; not deployed)**
- **Purpose:** anyone on the internet can register, DM anyone and upload 25 MB blobs with no quota. Uploads are buffered in a 512 MB container. Other production apps run on the same 2-vCPU VPS.
- **Dependencies:** an owner decision on gating. Options: invite code required to register (recommended), an email or domain allowlist, or an operator approval step.
- **Modules:** `routes/auth.ts`, `routes/attachments.ts` (stream to disk, per-user daily byte quota, global disk cap), `config.ts`, `deploy/compose.yaml` (`cpus`, `pids_limit`), the onboarding screen (invite field), docs.
- **Acceptance:**
  - Registration without a valid invite returns 403.
  - Uploads beyond the quota return 429 or 413.
  - Uploads stream to disk.
  - Compose sets CPU limits.
  - Existing accounts keep working.
- **Risks:** locking out legitimate testers. Ship an operator CLI that issues registration invites.
- **Verification:** vitest for the gate and quota. `docker compose config` and `verify.sh` for the limits. Deploying is a separate, approved step.

### T1.7 Presence privacy (P4). **Done 2026-10-16 (server; not deployed)**
- **Purpose:** any account can watch any user's online and last-seen state.
- **Modules:** `GET /v1/presence`, `GET /v1/users/:id`, `ws.ts`, the conversation member views.
- **Acceptance:** online and last-seen state are returned only for users who share a conversation or org with the caller (non-guest). Otherwise the response is `null`.
- **Risks:** none significant.
- **Verification:** vitest (the probe P4 scenario becomes a regression test).

### T1.8 Activity feed must respect branch isolation (P2). **Done 2026-10-16 (server; not deployed)**
- **Purpose:** members see other branches' team and channel names.
- **Modules:** `GET /v1/orgs/:id/activity`. Store the scope (branch and team id) in `audit_log.detail`, or filter by the target's scope.
- **Acceptance:** a branch-B member sees only org-level events and events for branches and teams they can access. Admins see everything.
- **Risks:** existing audit rows lack a scope. Filter them by looking up the target id.
- **Verification:** vitest (probe P2 scenario).

### T1.9 "Erase this device" revokes the device on the server. **Done 2026-10-16** (device check pending)
- **Purpose:** after erasing, contacts keep encrypting to a device whose keys are gone.
- **Modules:** `SecurityScreen` erase action, `GriipApp.eraseEverything`.
- **Acceptance:** erase calls `DELETE /v1/devices/<own id>` before wiping. If offline, it warns and offers to erase anyway.
- **Verification:** reference-client check of the device list, plus a device run.

### T1.10 Deleting a message deletes its local decrypted file
- **Modules:** `MessageRepository.delete` and `apply("delete")`.
- **Acceptance:** the file in `filesDir/attachments` is removed on local or remote delete. The conversation-delete path does the same.
- **Verification:** JVM test with a temp dir (T2.4).

## Tier 2: core messaging and reliability

### T2.1 Ordered, de-duplicated delivery (P6)
- **Purpose:** live pushes overtake the backlog and are delivered twice. Edits, reactions and deletes whose target is in a later batch are lost.
- **Modules:**
  - Server: `hub.ts` (don't push live items to a connection until its backlog has drained; use the unused `flushing` flag).
  - Android: keep control messages whose target is unknown in a small pending table, applied when the target arrives.
- **Acceptance:** with a 105-item backlog and a live message, delivery order equals queue order and each item is delivered once. A reaction that arrives before its target is applied later.
- **Verification:** the P6 scenario becomes a vitest; Android JVM test.

### T2.2 Realtime frames are never dropped
- **Modules:** `RealtimeClient` (replace `tryEmit` into a 256-item buffer with a suspending, ordered channel).
- **Acceptance:** a burst of 1,000 frames is processed completely.
- **Verification:** JVM test with a fake socket.

### T2.3 Room schema migration strategy. **Done 2026-10-16** (D-13; `MigrationsTest` on the JVM)
- **Purpose:** the next schema change would crash or wipe local history, which is the only copy.
- **Acceptance:** `exportSchema` JSON is committed, and the policy is explicit migrations only. A migration test template exists.
- **Verification:** a Room `MigrationTestHelper` test (instrumented) or a schema-diff check.

### T2.4 Android test harness (enabler). **Foundation done 2026-10-16** (crypto, interop and recovery-code tests; repository tests land with T1.2 and T1.4)
- **Purpose:** there are no Android tests. Tier-1 Android fixes need them.
- **Modules:** `app/src/test` (JUnit with coroutines-test), and small interfaces in front of `ApiClient` and the DAOs where needed. No new production dependency: JVM-test-only libraries.
- **Acceptance:** CI-runnable `./gradlew :app:testDebugUnitTest` covers:
  - envelope test vectors shared with the Node client (interop);
  - `RecoveryCode`;
  - `Fingerprint`;
  - `KeyDirectory`;
  - the outbox and `processQueue`.
- **Risks:** refactoring production classes for testability. Keep it minimal.

### T2.5 Background delivery decision (push)
- **Purpose:** a killed app receives nothing (D-08).
- **Dependencies:** owner decision: FCM (metadata to Google) or self-hosted UnifiedPush/ntfy (another service on the VPS). Also add `BOOT_COMPLETED` restart of the stay-connected service.
- **Acceptance:** after a reboot or OS kill, a message or call arrives within the agreed time with the chosen mechanism.
- **Verification:** a device run (can't be done locally until a second phone is available).

### T2.6 Attachment robustness
- Stream uploads and downloads (no whole-file `ByteArray`). Mark any failure as FAILED. Resume or retry. Delete failed placeholders.
- **Verification:** server vitest for streaming; Android JVM tests.

## Tier 3: organisation and access-control correctness

| Item | Purpose and acceptance | Modules | Verification |
|---|---|---|---|
| T3.1 Invites re-checked against the creator's current rights | An invite from someone who lost manage rights stops working (or is revoked automatically) | `findInvite`, role and member removal paths | vitest |
| T3.2 Archived branch or team semantics | Decide whether archiving freezes channels (read-only) and stops new members; enforce on the server | `authz.ts`, `orgs.ts` | vitest |
| T3.3 Admin `disable` disconnects live sockets | The CLI marks the user, and the API process closes sockets (poll `disabled_at` in the cleanup job) | `admin.ts`, `jobs.ts` | vitest |
| T3.4 Control-message scope checks | Reactions and pins only apply to targets in the same conversation | `MessageRepository.apply` | JVM test |
| T3.5 Departments (only if the product needs them) | Department membership and channels, or document that departments group teams only | schema migration 002 | vitest |

## Tier 4: core collaboration

| Item | Purpose and acceptance | Verification |
|---|---|---|
| T4.1 Verify the interaction features | Threads, replies, reactions, mentions, edit, delete and pins each covered by a reference-client interop test, plus a device pass with Griip Bot | vitest + bot |
| T4.2 In-app QR scanner for invites | Scan → preview → join, using the existing zxing core and CameraX (or the system code scanner, without Google Play services) | device |
| T4.3 Search | Indexed full-text search (SQLite FTS4/5 inside SQLCipher) over messages and attachment names | JVM/instrumented test |
| T4.4 Notifications | Message history per conversation in `MessagingStyle`; reply action | device |

## Tier 5: voice, video and screen sharing

| Item | Purpose and acceptance | Dependencies | Verification |
|---|---|---|---|
| T5.1 TURN on 443/TLS | Calls connect from networks that allow only 443 | Infra decision: LiveKit embedded TURN with a cert on its own subdomain, or coturn. A firewall change | Production test from a restricted network |
| T5.2 Call key authenticity | Accept a `call_invite` key only from the call's creator (`call.createdBy` from the server or ring event). Reject others | — | JVM + bot (a second bot races a fake key) |
| T5.3 Late joiners and new devices get the key | A participant re-sends the current key, encrypted, to a joining device on request (`call_key_request` control) | T5.2 | bot |
| T5.4 Key rotation on membership change | New key distributed when someone leaves or is removed. LiveKit key index ratchet | T1.3, T5.2 | bot wrong-key test after removal |
| T5.5 Screen share on the fixed build, plus a wrong-key test | Close the UNVERIFIED rows in inventory §9 | phone + bot | device |
| T5.6 Busy and declined UX | A busy callee auto-declines with a "busy" reason | — | vitest + device |
| T5.7 Capacity benchmark | Measure CPU and bandwidth for 3–12 participants on the shared VPS before allowing larger calls | owner approval for load on production | bot load test |

## Tier 6: decentralisation readiness

Signed group state (MLS or a signed membership log) so clients don't trust server-asserted membership; signed identity documents; per-organisation server URL; pub/sub for more than one API process. **Dependency:** settle the long-term protocol first (Signal or MLS vs `griip-envelope-v1`, D-04).

## Tier 7: secondary

- Prune `calls`, `call_participants` and `audit_log` with documented retention.
- `PRAGMA secure_delete = ON` (measure the cost).
- Remove `onlineDevices` from public `/healthz`.
- The notification Answer action accepts directly.
- Release (R8) build smoke test on a device.
- Backups policy for org metadata (not the queue).
- Department edit UI.
- `GET /v1/calls/active` on app start.

---

## Five highest-priority tasks

1. **T1.1 Demotion to Guest revokes access.** Server only, clear scope, tests possible today.
2. **T1.2 Enforce pinned identity keys.** It's the core of the E2EE promise, but needs an owner decision on blocking versus warning, and the Android test harness.
3. **T1.3 Evict removed members from live calls.** Server only.
4. **T1.4 Never discard messages on local failure.** Android; needs T2.4.
5. **T1.6 Registration gating and quotas on the shared VPS.** Needs an owner decision on how registration is gated.

**Best next task: T1.1** (implemented the same day; see `PROGRESS.md`). After it, **T1.3** is the next task that needs no decision. It's an access-control failure that any admin can trigger by accident, with no special attacker needed. The fix is contained in two server files with no dependency or decision outstanding, and it can be proved with tests through the real API.
