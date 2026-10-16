# Griip federation protocol, version 1 (`griip-fed/1`)

Status (2026-10-16): **implemented and tested** for direct messages, receipts, attachments, voice messages and 1:1 voice/video
calls between servers, a replicated core registry with three nodes, and the server lifecycle. Not federated in v1: groups,
channels, organisations, typing indicators and presence (§9). Code: `server/src/federation/`, `server/src/core/`. Tests:
`server/test/federation_registry.test.ts`, `server/test/federation_e2e.test.ts`. Design rationale: `FEDERATION_GAP_ANALYSIS.md`,
`decisions/0005-federated-network.md`.

## 1. Roles

```
            ┌──────────── core network (independently operated nodes) ────────────┐
            │  core 1 ◀──── replication (signed records) ────▶ core 2 ◀──▶ core 3 │
            └────▲───────────────▲───────────────────────────────▲────────────────┘
   register,     │ heartbeat     │ resolve (quorum)              │
   heartbeat     │               │                               │
        ┌────────┴───────┐   signed HTTPS (griip-fed/1)   ┌──────┴─────────┐
        │ home server A  │◀──────────────────────────────▶│ home server B  │
        │ (LiveKit A)    │   ciphertext envelopes, keys,   │ (LiveKit B)    │
        └──▲─────────────┘   receipts, attachments, calls  └────────────▲──┘
           │ client API (unchanged)                    client API     │
        Alice's devices                                        Bob's devices
```

- **Home server:** where an account lives. It stores the account's devices, queues ciphertext for them, and represents its
  users to other servers. Users never talk to other servers directly.
- **Core node:** keeps the registry of servers (signed descriptors, domain bindings, reachability, decommission and revocation
  records) and replicates it with other core nodes. No messages, contacts or user keys ever reach a core node.
- **Peer:** any other home server.

## 2. Identities

| Identifier | Form | Scope and binding |
|---|---|---|
| Server id | `gs1` + base32(sha256(root key))[0:26] | Self-certifying: derived from the server's Ed25519 root key; never changes, survives key rotation and domain changes |
| Server key chain | root key → key 1 → key 2 … | Each operational key signed by the previous one (`griip.server.key` records) |
| Federation domain | `host[:port]` | Routing name in user addresses; bound to a server id by quorum of core nodes after an endpoint challenge |
| Global user address | `@username:domain` | Human-readable; resolves through the domain to the server id, then to the user |
| User id / device id | `u_…`, `d_…` (128-bit random) | Assigned by the home server; **identical on every server** (envelopes are signed over them) and bound to the home server id |
| User identity key | Ed25519 | The account's long-term key (pinned by contacts, safety numbers) |
| Device keys | Ed25519 signing + X25519 encryption, certified by the identity key | Published by the home server only |

Details and hijack analysis: `GLOBAL_IDENTITIES_AND_DISCOVERY.md`.

## 3. Core network

### 3.1 Server descriptor

```json
{ "v": 1, "t": "griip.server.descriptor", "serverId": "gs1…", "rootKey": "<b64>", "keyChain": [{"index":1,"key":"<b64>","createdAt":…,"sig":"<b64>"}],
  "domain": "b.example", "endpoint": "https://b.example", "protocols": ["griip-fed/1"], "capabilities": ["dm","attachments","receipts","calls"],
  "seq": 1792153347930, "issuedAt": …, "expiresAt": …, "sig": "<by current key over canonical JSON without sig>" }
```

Canonical JSON = keys sorted, no whitespace. Valid if: id = hash(rootKey), every chain link verifies against the previous key, the
signature verifies against the last key, `expiresAt` is in the future (7-day lifetime; reissued when less than a day remains, on key rotation and on endpoint change), the protocol is listed.
**Anti-rollback:** a lower `seq` than one already seen, or a key chain that isn't an extension of the known chain, is rejected
everywhere (core nodes, resolvers). Someone holding a rotated-away key can't produce an acceptable descriptor or request.

### 3.2 Core node API (all answers signed by the node: `griip.core.answer {coreId, coreKey, at, query, result, sig}`)

| Endpoint | Purpose |
|---|---|
| `POST /core/v1/register {descriptor}` | Join or update. The node verifies the descriptor, applies policy, checks the endpoint (https, host = domain in production), **challenges the endpoint**, then binds the domain and marks the server active with a lease |
| `POST /core/v1/heartbeat` | Server-signed `griip.server.heartbeat {at, seq}`; renews the lease. A server that was unreachable is challenged again before it's active |
| `POST /core/v1/decommission` | Server-signed `griip.server.decommission {reason, at}`: permanent |
| `POST /core/v1/revocations` | `griip.server.revocation {serverId, reason, at, sigs[]}` with at least a threshold of trusted core signatures |
| `GET /core/v1/domains/:domain`, `GET /core/v1/servers/:id` | Signed answer: server id, state, descriptor, final record, lease |
| `GET /core/v1/feed?after=n` | Replication feed of self-authenticating records (descriptors, decommissions, revocations) |
| `GET /core/v1/info` | Node id, key, peers, threshold |

**Endpoint challenge:** the node fetches `GET {endpoint}/.well-known/griip-server?challenge=<nonce>&core=<coreId>`; the answer must
contain the server's descriptor and a `griip.server.challenge {coreId, nonce}` signed with the server's current key.

**Domain binding rules:** a domain bound to an active or recently unreachable server can't be claimed by another server id
(`409 domain_taken`). It's released only when the holder is decommissioned or revoked, or has been unreachable for
`CORE_DOMAIN_RELEASE_DAYS` (default 90).

**Replication:** each node pulls every peer's feed; records verify on their own; a replicated descriptor is **challenged again by the
receiving node** before it binds the domain (if the server can't be reached from there, the descriptor is kept as `pending`,
unbound). Applying a record is idempotent; cursors are per peer.

### 3.3 Resolution (home servers)

- `domain → server id`: ask every configured core node in parallel; verify each answer's signature against that node's configured key;
  accept a binding only if **`FEDERATION_CORE_QUORUM`** nodes (default: majority) agree; verify the descriptor locally; keep the
  newest valid one that doesn't roll back. The binding is cached and pinned; a different server later winning the quorum for a pinned
  domain is reported (`identityChanged`).
- `server id → descriptor` (for requests and routing): one valid descriptor suffices (self-certifying), newest wins, cached 5 minutes.
- Final records (decommission, revocation) verify on their own, so a single honest node is enough to learn them.
- **When core nodes are unreachable:** known servers keep working from cache until their descriptor expires; new domains need a live
  quorum (`503 directory_unavailable`). Disagreement without quorum → `409 directory_conflict` (no routing).

### 3.4 States

`pending` (descriptor known, endpoint not verified by this node) → `active` (verified, lease valid) ⇄ `unreachable` (lease expired;
nothing deleted) → `active` only after a new challenge. `suspended` (operator flag on one node, not replicated). `decommissioned`
and `revoked` are final and replicated. See `SERVER_LIFECYCLE.md`.

### 3.5 Running a core node

`node dist/core/index.js` (same image or build as the server). Environment:

| Variable | Default | Meaning |
|---|---|---|
| `HOST`, `PORT` | `0.0.0.0`, `8090` | Listen address |
| `CORE_DATA_DIR` | `core-data` | Node key (`core-key.json`, created once, mode 600) and SQLite registry |
| `CORE_PEERS` | — | Other core nodes: `url#publicKey,…` |
| `CORE_REVOCATION_THRESHOLD` | majority of all nodes | Core signatures a revocation needs |
| `CORE_ALLOW_HTTP` | `false` | Development only: http endpoints, endpoint host ≠ domain |
| `CORE_LEASE_SECONDS` | `180` | Lease renewed by heartbeats |
| `CORE_DOMAIN_RELEASE_DAYS` | `90` | Unreachable time after which a domain may be claimed by another server |
| `CORE_REGISTRATION` / `CORE_ALLOWLIST` | `open` / — | `allowlist`: only listed server ids or domains may join |
| `CORE_REPLICATE_SECONDS`, `CORE_SWEEP_SECONDS` | `15`, `15` | Replication and lease-expiry intervals |
| `CORE_FETCH_TIMEOUT_MS` | `5000` | Challenge and replication timeout |

## 4. Server-to-server requests

Every `/fed/v1/*` request carries `x-griip-from`, `x-griip-to`, `x-griip-time`, `x-griip-nonce`, `x-griip-body-sha256` and
`x-griip-signature` = Ed25519 (current server key) over

```
griip-fed-req/1 \n METHOD \n path?query \n bodySha256 \n from \n to \n time \n nonce
```

The receiver checks, in order: sender id format → per-sender rate limit → sender resolved and verified through the core network
(unknown → 403, decommissioned/revoked → 410, blocked or not allowed by policy → 403, not active → 403) → destination is itself
(421) → time within ±5 minutes → body hash → signature against the sender's current key (re-resolving once, for key rotation) →
nonce unused (replay → 401). Every response carries `x-griip-response-signature` over
`griip-fed-res/1 \n requestNonce \n bodySha256 \n responder \n requester`, which the requester verifies: answers can't be forged or
replayed by an intermediary. Production requires https endpoints; payloads are end-to-end encrypted regardless of TLS.

## 5. Endpoints

| Endpoint | Direction | What |
|---|---|---|
| `GET /.well-known/griip-server` | anyone | Current descriptor; with `?challenge&core` also the signed proof for a core node |
| `GET /fed/v1/users/lookup?username=` | peer → home | Exact-name lookup: identity key and certified devices. Same 404 for "no such user" and "not reachable"; 60 lookups/min per peer |
| `GET /fed/v1/users/:userId` | peer → home | Key refresh, only for a server hosting someone the user already shares a conversation with |
| `POST /fed/v1/deliver {items[], senders[]}` | sender's home → recipient's home | Envelopes and receipts for the receiver's devices, plus the senders' directory entries |
| `POST /fed/v1/attachments` | uploader's home → peer | Ciphertext of an attachment (metadata in `x-griip-attachment`), for a shared conversation |
| `POST /fed/v1/calls/event` | call host → peer | Call ring/updated/ended/alerted/declined/dismissed, to mirror |
| `POST /fed/v1/calls/:id/:action` | peer → call host | `join`, `connected`, `alerted`, `decline`, `end`, `leave` on behalf of the peer's own user |

Client API additions: `GET /v1/federation/resolve?address=@name:domain`, `GET /v1/federation/info`, `/v1/blocks` (any user),
`federation` in `/v1/server-info` and in conversation views, `homeServer` on users.

## 6. Delivery

### 6.1 Remote people ("shadows")

A remote user is stored on the other server with **the same user id and device ids**, `users.home_server_id` = their server id,
username `name:domain`. Entries come only from their home server (lookup, refresh, or `senders` in a delivery) and are validated:
id formats, every device certificate verifies against the identity key, an id that is local here or bound to another server is
refused (`id_conflict`), and a device key can't belong to another device. Devices the home server stops listing are revoked locally.
Shadows can't sign in (login and add-device exclude them), aren't searchable, and can't be added to groups.

### 6.2 Conversation ids

A DM with a remote person gets `cf_` + base64url(sha256("griip-fed-dm-v1|" + sorted ids))[0:22] on **both** servers, so the
existing envelope format (which binds the conversation id) verifies unchanged at the recipient.

### 6.3 Sequence: cross-server message

```
Alice's device        Server A                         Server B                    Bob's device
 encrypt to Bob's ──▶ POST /v1/messages
 devices (keys via     verify sender signature,
 A's shadow of Bob)    recipient set; Bob's devices
                       → federation outbox (same
                       ciphertext + key wrap)
                       ── POST /fed/v1/deliver ──────▶ verify request; validate senders;
                          (signed, batched, ordered)   recipient device local? sender is
                                                       A's user? conv id = cf_(Alice,Bob)?
                                                       envelope signature valid for Alice's
                                                       device? not blocked? dedupe key new?
                       ◀── {results: accepted|…} ───── queue for Bob's device ───────────▶ verify certificate
                       delete accepted items                                              against pinned key,
                                                                                          signature, HPKE, AEAD
                                                       ack ─▶ receipt for Alice's devices
                       ◀────── POST /fed/v1/deliver (receipt) ─────
 "Delivered" ◀──────── queue receipt
```

### 6.4 Outbox semantics

- One ordered queue per destination server (`federation_outbox`), bounded by `FEDERATION_OUTBOX_MAX_PER_PEER` (default 10,000):
  when full, new sends to that server get `503 federation_backlog` (backpressure, no unbounded growth). Receipts are dropped rather
  than failing an acknowledgement.
- Items expire with the queue TTL (≤ 7 days) and are deleted, never delivered late.
- Batches of up to 100 items; attachments go individually and before the messages queued after them.
- Each item has a stable key; the receiver stores accepted keys for 8 days, so retries are idempotent (no duplicates after a lost
  response). Rejected items (e.g. `sender_not_from_peer`, `bad_signature`, `unknown_recipient`) are dropped and audited without content.
- A failing peer backs off exponentially (2 s … 5 min); other peers aren't held up. A decommissioned or revoked peer's queue is dropped.
- **No full mesh:** servers connect to each other only when they have something to deliver; there are no standing connections.
- **Relay policy (v1):** there is no third-party relay. Delivery is direct between the two home servers; if that fails, items wait
  in the sender's outbox (bounded, ≤ 7 days). A relay, if added later, would only forward the same signed ciphertext batches.

### 6.5 Attachments and voice messages

After an upload to a federated conversation, the uploader's server queues the ciphertext for each remote member's server, which stores
it under the same id (size and SHA-256 checked, per-peer storage quota). The recipient downloads from their own server with the
existing client API; the content key is only in the end-to-end encrypted message. Voice messages are attachments with `voice: true`.

### 6.6 Calls (`CALLING_AND_E2EE.md` §6)

The caller's server hosts the call on its LiveKit and issues every token. The peer mirrors the call (same id,
`calls.host_server_id`) from signed events and forwards its users' actions to the host, which checks that the user and device belong
to the requesting server and are members of the conversation. The first ring carries the caller's directory entry so the peer can
create the DM if no message has yet. The media key travels only in the E2EE `call_invite` message.

## 7. Policy, abuse and limits

- `FEDERATION_POLICY=open|allowlist` (+ `FEDERATION_ALLOWLIST`), operator blocks in `federation_blocks` (by server id), per-user blocks
  (`/v1/blocks`, silently dropped, no oracle). Core nodes can run `CORE_REGISTRATION=allowlist`.
- Rate limits: 1,200 requests/min per peer, 60 lookups/min per peer, 30 lookups/min per local user.
- Quotas: outbox per peer, item payload ≤ 300 KB, batch ≤ 200 items and 4 MB, pushed attachments per peer ≤ the per-user stored quota.
- Audit log (`audit_log`): lookups (who asked, not whom), rejected senders and deliveries, outbox drops; never content or keys.

## 8. Compatibility and upgrades

- Versioned by protocol string (`griip-fed/1`) in descriptors; new fields are additive and ignored by older peers.
- Descriptors are reissued before they expire and on key rotation; peers re-resolve when a signature fails.
- Migrations `004_federation.sql` and `005_federated_calls.sql` are additive; existing accounts become `@username:domain` without change.
- Disabling federation stops new federated traffic; sends to federated conversations then return `503 federation_disabled`.

## 9. Not in v1

- Groups, channels and organisations across servers (need client-verifiable membership first: `SECURITY.md` §4).
- Typing indicators and presence across servers (privacy; deliberately not sent).
- Global name search (exact address lookup only: `GLOBAL_IDENTITIES_AND_DISCOVERY.md` §5).
- Account migration between servers (would need an explicit, separately secured process).
- Sealed sender / metadata-hiding delivery (`SECURITY_AND_PRIVACY.md` §5).
- Push wake-up (unchanged: `BACKGROUND_DELIVERY.md`).
