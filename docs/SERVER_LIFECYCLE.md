# Server lifecycle on the Griip network

Status (2026-10-16): implemented and tested (`server/test/federation_registry.test.ts`, `federation_e2e.test.ts`; local
three-node network in `TESTING.md` §8). Protocol: `FEDERATION_PROTOCOL.md` §3.

## 1. States

```
                  register (descriptor verified, policy ok)
   (unknown) ───────────────────────────────▶ pending ──── endpoint challenge ok ───▶ active
                                                 ▲                                    │  ▲
                         replicated but not      │                 lease expires       │  │ heartbeat + new challenge
                         reachable from this node│                 (no heartbeat)      ▼  │
                                                 └──────────────────────────────── unreachable
   operator flag on one node:  active ⇄ suspended            (nothing is deleted; the domain stays bound
                                                             for CORE_DOMAIN_RELEASE_DAYS)
   final (replicated, permanent):  any ──signed by the server──▶ decommissioned
                                   any ──threshold of core signatures──▶ revoked
```

| State | Meaning | Routing |
|---|---|---|
| `pending` | A core node knows the descriptor (e.g. by replication) but hasn't verified the endpoint itself | Not used for new lookups on that node; peers refuse requests from a server they see as pending |
| `active` | Verified endpoint, lease valid | Normal |
| `unreachable` | No heartbeat within the lease (`CORE_LEASE_SECONDS`, 180 s) | Lookups still answer (with the state); senders keep items in their bounded outbox and retry with backoff |
| `suspended` | One node's operator paused it (local, not replicated) | That node reports `suspended`; resolvers use the majority state |
| `decommissioned` | The server signed its own retirement | Never routed again; the id is tombstoned forever |
| `revoked` | A threshold of core operators signed a revocation (e.g. a malicious server) | Never routed again |

## 2. Joining

1. The server creates its root key once (`DATA_DIR/federation/server-key.json`, mode 600) and derives its server id.
2. The operator sets `FEDERATION_DOMAIN`, `FEDERATION_PUBLIC_URL` (`https://<domain>`), `FEDERATION_CORE_NODES` and starts the server
   (`SELF_HOSTING.md` §12).
3. The server publishes `GET /.well-known/griip-server` and sends its signed descriptor to every core node.
4. Each core node verifies the descriptor, checks policy (open or allowlist) and the endpoint (https, host = domain), then **challenges**
   the endpoint with a fresh nonce; the server signs it with its current key.
5. The node binds the domain (if free under the binding rules), marks the server active with a lease and appends the descriptor to its
   replication feed. Other nodes replicate it and challenge the endpoint themselves before binding.
6. Heartbeats every `FEDERATION_HEARTBEAT_SECONDS` (60 s) renew the lease on every node.
7. Peers can resolve the domain once a quorum of nodes agrees.

A URL alone never admits a server: an unsigned or mismatching descriptor, someone else's endpoint, or a bound domain is refused
(tested: `bad_descriptor`, `challenge_failed`, `domain_taken`, `insecure_endpoint`).

## 3. Temporary disconnection and reconnection

- Lease expiry turns the server `unreachable` on each node. Its descriptor, domain binding and every account on it stay as they were.
- Peers keep queued items for it (≤ 7 days, bounded per peer) and retry with exponential backoff (2 s … 5 min). Users see "<domain> isn't
  reachable right now. Your messages wait for up to 7 days…" in the conversation.
- When it comes back, its next heartbeat triggers a **new endpoint challenge** on each node before it's `active` again; no new
  registration and no duplicate rows (tested).
- Peers deliver the backlog in order once the server answers (tested: two queued messages, delivered in order after recovery).
- Known peers keep working while core nodes are partly down (cached descriptors); new lookups need a quorum.

## 4. Permanent decommission

- `node dist/admin.js fed-decommission "<reason>" --yes` signs `griip.server.decommission` with the current key and sends it to every core
  node; nodes replicate it. Tested: the id can't re-register, its requests are refused (410), peers drop its queued items, and another
  server may then register the domain under a **new** id.
- The tombstone keeps the old id from ever being reused, so nobody can impersonate the retired server.
- **What users lose:** their accounts lived on that server. Their contacts' apps keep the history (it's on devices), but no new message
  can reach them at that address. A user who moves to another server gets a new account and address; contacts must start a new
  conversation and verify the new identity. Loss of a server is never treated as proof that its users moved anywhere.
- **If a server vanishes without decommissioning:** it stays `unreachable`; after 90 days its domain may be claimed by another server id
  (which contacts' apps flag as a different server); its own id is never reassigned.

## 5. Revocation (malicious servers)

Core operators each sign `{serverId, reason, at}` with their node key (`signRevocation`); a record with at least
`CORE_REVOCATION_THRESHOLD` valid signatures (default: majority of nodes) is accepted by any node and replicated. One operator alone can't
revoke a server (tested). Home servers additionally block servers locally with `fed-block` (immediate, local only).

## 6. Loss of a core node

- Resolution needs `FEDERATION_CORE_QUORUM` agreeing answers (default 2 of 3). With one of three nodes down, lookups still work (tested in
  vitest and on the emulator: "identity verified by 2 directory nodes"). With two down, new lookups fail closed; known servers keep working
  from cache.
- A returning node pulls everything it missed from its peers' feeds (tested).
- Core nodes keep no message data, so losing one loses no messages.

## 7. Key rotation

Stop the server, `node dist/admin.js fed-rotate-key`, start it: it reissues its descriptor with the extended key chain and re-announces.
The server id doesn't change. Peers re-resolve on the first signature failure. Anything signed with the old key is refused from then on
(tested with a stolen old key against core nodes and against a peer).
