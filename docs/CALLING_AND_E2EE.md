# Calling and call encryption

How a Griip Message call goes from "tap call" to "talking", what can fail at each step, how each failure is shown and logged,
and what the E2EE indicator does and doesn't prove. Security background: `SECURITY.md` §7. Decision: `decisions/0004-call-reachability.md`.

## 1. Lifecycle: nine separate states

| # | State | Who knows | Evidence | What the user sees |
|---|---|---|---|---|
| 1 | **Invite sent** | caller | `POST /v1/calls` returned a call (`RINGING`, 10-minute room token); the per-call media key went out in a `call_invite` envelope | "Calling…" |
| 2 | **Delivered / unknown** | nobody for certain | the server sent `ring` to the callee's *live* WebSockets; without push, it can't know whether a stopped app got it | still "Calling…" |
| 3 | **Alerted** | caller | a callee device showed the ring and called `POST /v1/calls/:id/alerted`; the caller got an `alerted` event | "Ringing…" |
| 4 | **Accepted** | both | callee `POST /join` succeeded (state `ACTIVE` once two users are in) | "Connecting media…" |
| 5 | **Signalling** | each device | LiveKit room connected over WebSocket (`LIVEKIT_PUBLIC_URL`); logged as `media room joined in N ms` | "Connecting media…" |
| 6 | **Media transport** | each device | ICE chose a candidate pair; `MediaPath` reads it from WebRTC stats: `udp · direct`, `tcp · direct` or `relay (…)`, never addresses | a note when not UDP: "Connected over TCP: this network may block UDP…" |
| 7 | **Tracks** | each device | remote audio/video tracks subscribed | |
| 8 | **E2EE** | each device | frame cryptors report OK/KEY_RATCHETED for every live track, including at least one remote one (D-17) | "End-to-end encrypted" pill; otherwise "Verifying encryption…" or "Encryption problem" |
| 9 | **Usable** | each device | inbound RTP byte counters of remote tracks keep growing (`MediaFlowMonitor`, sampled every 2 s) | the call timer starts; "Reconnecting media…" if bytes stop |

Joining the room (5) or subscribing a track (7) is **not** proof that media flows; growing inbound bytes are (9). Encryption (8) is
judged separately from flow (9): media can flow while the indicator still says "Verifying encryption…".

**Terminal states** (`calls.end_reason`): `hangup`, `declined`, `busy` (new), `missed` (1:1, unanswered after `RING_TIMEOUT_SECONDS`,
default 45 s), `empty` (everyone left), `removed` (lost membership). All are idempotent: a late decline after the call ended keeps the
original reason (`call_reachability.test.ts`).

## 2. Reachability fixes (2026-10-16)

Root cause of "calls don't reach the other person": the server rings only live WebSockets, and the app never asked for ringing
calls after reconnecting. A ring sent while the callee's socket was down or reconnecting was simply lost.

- **Ring recovery** (`RingRecovery`, `CallManager.recoverRinging`): after each reconnect, once the message backlog has drained (so
  the media key has arrived), the app reads `GET /v1/calls/active` and rings calls that are `RINGING`, not started by this user, not
  answered on another of their devices, not already handled here, and less than 45 s old.
- **Alerted state:** `POST /v1/calls/:id/alerted` (members only; idempotent; 409 for the caller; 410 after the call ended), new column
  `calls.alerted_at` (migration `003_call_alerted.sql`), `alertedAt` in call responses, `alerted` event to the caller.
- **Busy:** a device already in a call declines a new 1:1 ring with `{"reason":"busy"}`; the caller sees "Busy". Group rings aren't
  declined as busy (others may answer).
- **Duplicate rings** (live `ring` event plus recovery, or several events) are ignored with a bounded "handled" set.
- **Still not possible:** waking an app Android has stopped. There is no push service and no push credential in the app. The optional
  "stay connected" foreground service keeps the socket open (`BACKGROUND_DELIVERY.md`).

## 3. Media connectivity

The deployment has no TURN server. LiveKit offers UDP 7882 and TCP 7881 candidates on the server's public IP. A phone on a network
that blocks or rate-limits UDP falls back to TCP, which works but can take several seconds to establish.

What the app now records for each call (`CallDiagnostics`, log tag `CallDiag`, no tokens, keys or addresses; the first two lines are logged in debug builds only, and the call screen shows the state in every build):

- `media room joined in N ms`: LiveKit signalling + ICE + DTLS time;
- `media Flowing via udp · direct` (or `tcp · direct`, `relay (tls)`), then changes such as `media Stalled`;
- one user-facing message per failure class (`CallFailure`): the call ended (410), no longer exists (404), not allowed (401/403),
  calling not set up on this server (503 `media_unavailable`), the key didn't arrive (`no_key`), can't reach the server, the media server
  didn't answer in time ("The network may block it; try another network"), the call pass was refused, microphone or camera unavailable.

LiveKit SDK logging stays off in the app: its signalling URL carries the access token.

## 4. Encryption path

- The caller creates a random 256-bit key per call and sends it **inside a `griip-envelope-v1` encrypted control message** to the
  conversation's devices. It never goes through the call API, a URL or a LiveKit token.
- A key is accepted only from the call's creator, in the call's own conversation (T5.2). Late joiners ask the creator again
  (`call_key_request`, T5.3).
- With E2EE on and no key after about 10 s, the app **refuses to join** with a clear message. There is no plaintext fallback.
- Every participant sets the key as LiveKit's shared key (AES-GCM frame encryption). The SFU forwards frames it can't decrypt.
- `FrameCryptorGuard` (b97c09c) replaces only LiveKit's frame-cryptor *status observer* to avoid a native crash; the cryptors, key
  provider and key index are untouched. It's kept.
- **Indicator (D-17):** "End-to-end encrypted" only when every live track has encrypted or decrypted a frame with our key, including a
  remote one. Missing key, encryption, decryption or internal errors show "Encryption problem"; anything else "Verifying encryption…".
- **Known limits:** with LiveKit's `failureTolerance` at -1, frames that fail to decrypt are dropped silently, so a wrong-key peer
  shows "Verifying encryption…" (never "encrypted") rather than an error. Keys aren't rotated when people join or leave (T5.4).

## 5. Tests

| Where | What |
|---|---|
| `server/test/call_reachability.test.ts` (4) | `/calls/active` finds a ringing call for an offline callee (404 for non-members); alerted once, caller 409, non-member 404, 410 after end; busy decline ends with `busy` and tells the caller; invalid reason 400; a late decline keeps `hangup` |
| other server call tests | `calls_attachments`, `call_eviction`, `authz_edges` (unchanged, passing) |
| `CallReachabilityTest` (7, JVM) | which recovered calls ring; media flow growth, stall and recovery; a counter drop is a new baseline; the path comes from the selected pair and never leaks an address; failure classes |
| `MediaSecurityTrackerTest`, `FrameCryptorGuardTest` | indicator rules and the guard (unchanged, passing) |
| Devices | `TESTING.md` §5f (phone + Griip Bot on production) and §7 (local instance) |

## 6. Calls with people on another server (federation, 2026-10-16)

The call is hosted by the **caller's** server (ADR 0007). Sequence for Alice (server A) calling Bob (server B):

```
Alice ─ POST /v1/calls ─▶ A: create call (RINGING), token for Alice (A's LiveKit)
Alice ─ call_invite (E2EE envelope with the media key) ─▶ A ─ /fed/v1/deliver ─▶ B ─▶ Bob
A ─ /fed/v1/calls/event "ring" (+ Alice's directory entry) ─▶ B: mirror the call (same id, host = A), create the DM if needed ─▶ Bob rings
Bob ─ POST /v1/calls/:id/alerted ─▶ B ─ /fed/v1/calls/:id/alerted ─▶ A: alerted_at, "Ringing…" for Alice
Bob ─ POST /v1/calls/:id/join ─▶ B ─ /fed/v1/calls/:id/join {Bob, his device} ─▶ A: checks Bob is B's user, member, device valid;
      token for "u_bob.d_bob" in A's room ─▶ B ─▶ Bob connects to A's LiveKit (wss URL from A)
connected / decline / leave / end: forwarded the same way; A broadcasts updated/ended to its users and relays them to B
```

- **Encryption is unchanged:** the media key is made by the caller and travels only inside the E2EE `call_invite`; key acceptance (creator
  only, T5.2), late-joiner requests (T5.3), frame E2EE, `FrameCryptorGuard` and the D-17 indicator apply exactly as for local calls.
- **Who can do what:** only the host issues tokens; a peer can act only for its own users and devices in a conversation both servers share;
  events about a call can only come from the server of the person who created it.
- **What the host sees:** the remote callee's device connects directly to the host's media server (its IP address and call timing are
  visible to the host); B sees its own user's participation.
- **Tested:** vitest (ring, alerted, a token issued by A for B's user, connected → ACTIVE on both, end on both; a decline relayed as
  `busy`; first-contact ring creating the DM; forged actions and events refused). **Real media:** a Griip Bot on local server A called a
  Griip Bot on local server B by `@…:b.griip.test`; both decoded the other's E2EE audio (about 2,420 frames each) and video (about 220
  frames at 640×360) through A's LiveKit (`TESTING.md` §8). The Android app's call path across servers wasn't run (the emulator has no
  microphone permission and the phone wasn't available for this step).
- **Limits:** a server without LiveKit can't start calls with remote people (the app hides the buttons) but can join theirs; group calls
  aren't federated; no TURN.

## Verifying a call (2026-10-17)

Tap the encryption badge on the call screen. Every phone in an end-to-end encrypted call holds the same media key, which
the caller generated and sent through the chat's end-to-end encrypted, signed messages. `CallVerification` derives four
emoji and six digits from that key and the call id (HKDF-SHA256; 44 bits shown, the key itself never leaves the phone).
Phones holding the same key for this call show the same code; a key someone substituted (for example a compromised
server) gives different ones. Matching codes show that everyone's media uses the same key from the same sender; they don't
by themselves prove who the sender is (that's what safety numbers are for). A call without media encryption has no code
and says so. Tests: `CallVerificationTest` (includes the RFC 5869 HKDF vector).

## Reconnecting (2026-10-17)

- This phone's connection drops: LiveKit's own reconnect runs first ("Reconnecting…"). If it gives up for a connection
  reason (not a hang-up, removal or duplicate join), Orbit gets new media credentials and rejoins the same call with the
  same key, with backoff, for up to 45 s (`CallRecovery`). The call ends ("the connection was lost") only after that.
- The other person's connection drops (1:1): the call shows "…'s connection dropped. Waiting for them to reconnect…" for
  30 s instead of ending. The server waits too (`CALL_PEER_GRACE_MS`, default 30 s; `server/test/call_reconnect.test.ts`)
  before ending the call as `lost`, which call history shows with its real duration. Hanging up still ends it at once.

## Minimised calls (2026-10-17)

Leaving Orbit during a connected call shrinks it into picture-in-picture (Android 8+; automatic with gesture navigation
on Android 12+): the other person's video or shared screen, or their avatar for voice calls, with the window's own Mute
and Hang up buttons. Tapping it returns to the full call screen. Inside the app, the existing minimise button keeps a call
bar over other screens. During a call Android requires the ongoing-call notification.
