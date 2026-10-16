# Federation: audit of the existing architecture, gaps and migration strategy

Date: 2026-10-16. Supersedes the "independent instances only" reading in ADR 0001 (now amended by ADR 0005).
Scope: what the code did before federation, verified by reading `server/src` and `android/app/src`, and the smallest safe path
to a federated network.

## 1. Audit (as found)

| # | Question | Finding (code reference) |
|---|---|---|
| 1 | How accounts are created and identified | `POST /v1/auth/register` (`routes/auth.ts`): the device generates an Ed25519 **identity key**, an Ed25519 **device signing key** and an X25519 **device encryption key**, and signs a device certificate (`griip-device-cert-v1`, identity key → device keys). The server assigns `users.id` (`u_` + 128-bit random) and `devices.id` (`d_` + random). `username` is unique per server (`[a-z0-9._]{3,24}`) |
| 2 | Global vs local identifiers | **Global by construction:** identity keys, device keys, message ids (client UUIDs), attachment ids (128-bit random). **Random but server-assigned:** `u_…`, `d_…`, `c_…`, `call_…` (collision-free in practice, but nothing stops another server from claiming them). **Server-local:** `username`, `conversations.dm_key`, org/team ids, session tokens, the queue's integer ids |
| 3 | How clients resolve recipients | `GET /v1/users/search` (local users only, exact username for strangers), `POST /v1/users/devices` and `GET /v1/conversations/:id/devices` return identity key + certified devices from the **home server's own tables**. Clients pin identity keys on first use and hold messages after a change (`KeyDirectory`, T1.2) |
| 4 | Delivery, persistence, queues, acks | `POST /v1/messages` verifies the sender device's signature, requires the recipient set to equal the members' active devices (`409 device_mismatch`), and writes one `delivery_queue` row per device (TTL ≤ 7 days, unique on device+kind+message+sender device). Devices receive over the WebSocket (`queue` frames) or `GET /v1/queue`, and `ack` deletes rows and creates `delivered` receipts for the sender's devices. Idempotency: `submitted_messages` |
| 5 | Single-server assumptions | Every user, device and conversation member must be a local row (foreign keys). Fan-out writes only to the local queue. `hub.sendToUser` reaches only local sockets. Calls: one LiveKit per server, rooms and tokens issued by the conversation's server. No server identity exists; the only "server identity" is its URL + TLS |
| 6 | LiveKit scope | Room name `room-<random>` per call (unique per server only). Participant identity `<userId>.<deviceId>` (globally unique in practice). Tokens are issued by the call's server for its own LiveKit |
| 7 | Hard-coded instance | No hard-coded domain in the server. The app stores the server URL per profile (and in the session). `/v1/server-info` describes the instance |
| 8 | Key publication and verification | Keys are published only to the home server at registration / device add; the server checks the certificate; clients verify certificates against the **pinned** identity key. No key transparency log |
| 9 | Connection authentication, pending messages | Bearer session token (`sessions`, sha256 stored), re-auth by signing a challenge. Pending messages: ciphertext + per-device key wraps in `delivery_queue`; server never has content keys |
| 10 | Upgrades preserving accounts | Migrations are additive and run at start; the app's default profile keeps its storage names. Nothing in a federation design needs to change existing ids or keys |

**Cryptographic binding that federation must respect:** the envelope signature and AEAD associated data bind
`messageId`, `conversationId`, `senderUserId` and `senderDeviceId`; the HPKE key wrap binds `messageId` and the recipient `deviceId`
(`server/src/crypto.ts`, `griipClient.ts`, Android `EnvelopeV1`). A message is only verifiable at the recipient if **those ids are
identical on both servers.**

## 2. Gaps

| Requirement | Gap |
|---|---|
| Server identity | none: a server is only a URL. Needed: a stable key-based server id, signed descriptors, key rotation |
| Global addresses | usernames are server-local. Needed: `@username:domain` bound to a verified server id |
| Discovery | local search only. Needed: exact global address lookup through the home server, privacy-preserving, rate-limited |
| Registry | none. Needed: several core nodes, signed replicated records, anti-rollback, lifecycle, decommission/revocation |
| Server-to-server transport | none. Needed: authenticated, replay-resistant, rate-limited requests; bounded outbox with retries and dedupe |
| Remote principals | users/devices must be local rows. Needed: a safe representation of remote users and devices |
| Conversations | `c_…` ids are random per server. Needed: an id both servers agree on |
| Attachments | stored on the uploader's server only |
| Calls | caller's LiveKit only, tokens only for local users |
| Clients | no UI for remote addresses or server verification |

## 3. Smallest safe design (chosen)

1. **Server identity:** each server generates an Ed25519 *root key* once (`DATA_DIR/federation/server-key.json`, mode 600, never
   regenerated). `serverId = "gs1" + base32(sha256(rootKey))[0:26]`: self-certifying, so a directory can't map a server id to a
   different key. Operational key rotation is a chain of records each signed by the previous key.
2. **Signed descriptor:** `{serverId, rootKey, keyChain, domain, endpoint, protocols, capabilities, seq, issuedAt, expiresAt}`,
   signed by the current key. Higher `seq` wins; lower is ignored (anti-rollback).
3. **Core nodes:** separate processes (`node dist/core/index.js`) with their own keys. They verify descriptors, prove endpoint control
   with a challenge, bind `domain → serverId`, keep leases (active/unreachable), accept server-signed decommission tombstones and
   threshold-signed revocations, and replicate signed records with each other. They sign their answers. They never see messages.
4. **Resolution:** home servers ask all configured core nodes and require a quorum (default majority) to agree on `domain → serverId`;
   descriptors are verified locally. Verified bindings are cached and pinned; known servers keep working on cache while a core is down.
5. **Federation transport:** HTTPS requests signed with the server key (method, path, body hash, from, to, time, nonce); nonces stored
   to stop replays; responses signed; per-peer rate limits; bounded per-destination outbox with retries, backoff, expiry and
   idempotent delivery ids.
6. **Remote principals as shadows:** a remote user and their devices are stored locally with **the same ids** as on their home
   server, bound to the home `serverId` (`users.home_server_id`). Shadows can't sign in. A collision with a local id or an id bound
   to another server is rejected. Keys still come only from the remote home server and are verified by clients (certificates, pinning).
7. **Federated DMs:** `conversations.id = "cf_" + base64url(sha256("griip-fed-dm-v1|" + dm_key))[0:22]`, computed identically on both
   servers from the two (identical) user ids. Envelopes, signatures and wraps are unchanged; recipients verify exactly as today.
8. **Fan-out:** queue rows for shadow devices go to the federation outbox instead of the local queue; the receiving server verifies
   the envelope signature against the sender's shadow device, enforces policy, and queues for its local devices. Receipts follow
   the same path back.
9. **Attachments and voice messages:** after upload to a federated conversation, the uploader's server pushes the ciphertext to the
   peer server through the same ordered outbox, so recipients download from their own server (no client change, no IP exposure to
   the remote server).

No change to `griip-envelope-v1`, the attachment format or call E2EE. Groups, channels and organisations stay server-local in this
release (group federation needs client-verifiable membership first: `SECURITY.md` §4).

## 4. Migration strategy

- Additive migration `004_federation.sql`: nullable columns on `users` (`home_server_id`, `remote_address`) and `devices` (`remote`),
  new tables for peers, outbox, inbox dedupe, nonces and blocks. Existing rows are untouched: every existing user is local
  (`home_server_id IS NULL`), every existing conversation keeps its id.
- Existing accounts get a global address automatically once the server has a federation domain: `@username:domain`. No re-registration,
  no key change, no account merge.
- Federation is off unless configured (`FEDERATION_*`); with it off the server behaves exactly as before.
- Rollback: disabling federation stops new federated traffic; federated DMs stay readable on devices (history is local).

## 5. Milestones (status 2026-10-16)

| # | Milestone | Acceptance |
|---|---|---|
| F1 | Server keys, descriptors, rotation | **Done**: registry tests 1, 4 |
| F2 | Core registry with replication and lifecycle | **Done**: registry tests 2–9 |
| F3 | Signed federation transport + outbox | **Done**: e2e 7–8, 15, 17–18 |
| F4 | Global lookup + federated DM text both ways | **Done**: e2e 1–6, 14, 16 |
| F5 | Attachments and voice messages | **Done**: e2e 9; emulator (TESTING.md §8.2) |
| F6 | Acceptance with a core node down | **Done**: e2e 21–22; emulator with core node 3 stopped |
| F7 | Android | **Done** for lookup, verified-identity sheet, federated chats, voice playback, own address, block (emulator); phone not run |
| F8 | Calls | **Done** server-side and with real media between bots; **not verified in the app** |
