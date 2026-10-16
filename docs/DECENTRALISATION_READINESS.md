# Decentralisation readiness (Phase 17)

2026-10-16. **Scope:** an evaluation, not an implementation. It covers each item the master prompt lists, judged from the current code.
Nothing here changes `griip-envelope-v1`, the org model or production.

**Bottom line:**
- Griip is already **independently operable**: there is no central account service, and any organisation can run its own instance
  with its own media server.
- It is **not federated**: users on different instances can't talk to each other.
- It is **not verifiable against a malicious server** for group membership: the server decides who is in a group, and clients
  encrypt to whoever it lists.
- Federation is not recommended now. Verifiable membership is the item worth doing first, and it needs its own protocol proposal.
- No blockchain is proposed. Nothing on this list needs global consensus; signed logs and an established group protocol cover it.

## Evaluation

| # | Item | Today (evidence) | Gap | Recommendation | Readiness |
|---|---|---|---|---|---|
| 1 | Per-organisation server configuration | One server per app install. The URL is chosen at onboarding (*Server address*, `Onboarding.kt`), stored in the session, and defaults to the build's `DEFAULT_SERVER_URL`. Several orgs can live on one server | Someone in two orgs on two different instances needs two installs. The app has one session, one database and one key set | **Account profiles:** one session, one SQLCipher database and one key set per server, with a profile switcher. The data layer is already keyed by a single `AppContainer`, so this means one container per profile. Medium effort, no protocol change | Partial |
| 2 | Signed identity documents | Each account has an Ed25519 identity key. Every device carries a `griip-device-cert-v1` certificate signed by it (`crypto.ts`, verified at registration and pairing). Clients pin the identity key on first use and hold messages if it changes (T1.2) | The binding of *username and display name* to the identity key is asserted only by the server. Nothing the user signed says "@sam on msg.example.com is this key", so another instance can't verify it | A `griip-identity-v1` document signed by the identity key: username, server origin, creation time and a revision number, with the device list signed separately. It's additive: old clients ignore it. Needed before any federation | Partial (keys yes, documents no) |
| 3 | Verifiable group membership | The server decides membership (`conversation_members`) and makes senders encrypt to exactly that device set (`device_mismatch` in `routes/messages.ts`) | **A malicious or compromised server can add a member to a group or channel**, and clients will encrypt to them. Pinning stops fake devices for *known* users, but not an invented user added to a group. DMs are fixed at two people | **Cheap step now:** show a system line in the conversation when its member list changes, computed by the client from successive member lists. That makes silent additions visible. **Proper fix:** membership changes signed by an authorised admin's identity key and checked by clients (item 4) | Not ready |
| 4 | Signed membership logs or an established group protocol | None. Each message is encrypted to each device with HPKE. There's no group key and no membership state that clients agree on | No client-verifiable membership history | **Evaluate MLS (RFC 9420)** in a separate proposal, with OpenMLS (MIT) or mls-rs (Apache-2.0 or MIT) as candidates. It gives verifiable membership, forward secrecy and post-compromise security for groups. Both are Rust, so Android needs JNI or UniFFI bindings; D-04 found no maintained Kotlin binding. The alternative is a smaller signed membership log (an admin signs each add or remove; clients check the chain). Either one is **a new envelope version with a migration plan, an interoperability plan and a security review**, not a change to v1 | Not started (by design) |
| 5 | Independently operated relay endpoints | Any organisation can run the Compose project (`deploy/`) on its own host. The relay only routes ciphertext and checks signatures. Every vitest file starts its own fresh instance (`test/support/harness.ts`), which shows instances run with no shared state | No discovery and no server-to-server protocol. Ids (`u…`, `d…`, conversation ids) only make sense within one instance | Keep instances independent. If federation is ever wanted, it needs instance-qualified ids (`user@host`), server-to-server authentication, and the signed identity documents from item 2 | Ready (independent), not federated |
| 6 | Independent media servers | The call API returns the LiveKit URL with each grant (`MediaGrant.url`), configured per instance (`LIVEKIT_PUBLIC_URL`). Media is end-to-end encrypted with a per-call key the SFU never sees, accepted only from the call's creator (T5.2) | One SFU per instance. There's no per-org or per-region choice inside an instance | Per-org LiveKit settings are a small server change if an org needs its own media server. TURN (T5.1) matters more first | Ready per instance |
| 7 | Multi-relay client routing and failure recovery | One relay URL. Reconnect has backoff (`RealtimeClient`), the queue is store-and-forward for 7 days, and the outbox retries | No fallback relay. If the instance is down, nothing flows until it's back | With profiles (item 1), each profile reconnects on its own. A second relay for the same instance needs item 9 first. **Telegram lesson (behaviour only):** clients keep a separate session per data centre and follow "go to DC n" redirects, so routing lives in the client, not DNS | Not ready |
| 8 | Run another instance without Griip's central account service | **There is no central account service.** Accounts, keys, orgs and invites are per instance. Registration is per instance (`REGISTRATION_MODE`, operator invite codes from `admin.js reg-invite`). The app can point at any URL | APK builds and updates are distributed by Griip; that's the only shared dependency. A self-hoster can rebuild with their own `griip.serverUrl` | Document a short self-hosting guide based on `DEPLOYMENT.md` once the pending release ships | Ready |
| 9 | Pub/sub and routing for several API processes | One Node process. The WebSocket hub is in memory (`hub.ts`, D31), SQLite has a single writer, and the cleanup jobs assume one process | A second process wouldn't see the first's sockets, and jobs would run twice | Only when load needs it: Postgres for state; Redis or NATS pub/sub for hub fan-out and `disconnectUser`; one elected leader for jobs; the LiveKit webhook handled by any process. The `Conn.flushing` ordering gate (T2.1) must hold across processes: deliver per device from one owner process | Not ready (not needed for the beta) |

## Proposed order

1. **Now, cheap, no protocol change:** member-list change lines in conversations (item 3, transparency only) and a self-hosting
   note (item 8).
2. **Before any multi-server use:** account profiles in the app (item 1).
3. **Separate proposal and security review:** signed identity documents (item 2), then MLS or a signed membership log (item 4),
   as a new envelope version that exists alongside v1 during migration.
4. **Only if load requires it:** multi-process server with shared pub/sub (item 9), then relay failover (item 7).
5. **Not planned:** federation between instances, and any blockchain or global ledger.

## Telegram reference (behaviour only, no code reuse)

- Telegram's data centres are one operator's infrastructure, not a decentralised system: every account has a home DC, and clients
  follow migration errors to reach it. Griip's per-instance model is closer to self-hosted Matrix or XMPP *without* federation.
- What carries over is the **client-side routing discipline**: a session per server, redirect handling, and reconnect state per
  connection. MTProto, TLRPC and Telegram's cloud-chat model don't carry over. Griip's end-to-end encryption by default is the
  opposite design.

## Status

- This is an evaluation, and nothing has been implemented.
- Item 5's claim about independent instances rests on the test harness, which starts separate instances locally.
- No second production instance has been run.
