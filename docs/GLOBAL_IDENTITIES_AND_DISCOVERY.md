# Global identities and discovery

Status (2026-10-16): implemented and tested (`federation_registry.test.ts`, `federation_e2e.test.ts`, Android `GlobalAddressTest`,
emulator run in `TESTING.md` §8). Protocol details: `FEDERATION_PROTOCOL.md`.

## 1. The identity model

| Concept | Example | Who controls it | Stable? |
|---|---|---|---|
| Human-readable address | `@bob:b.example` | Bob picks the name; his server's operator controls the domain | As long as the server keeps the domain |
| Stable server identity | `gs1rtxv3cqin6kyewm7nedvqsfw44` | The server's root key (`sha256` of it) | Forever; survives key rotation, new IPs and domain changes |
| Server keys | root → rotated operational keys | Server operator (`DATA_DIR/federation/server-key.json`) | Rotatable; each new key signed by the previous |
| User's long-term identity | Ed25519 identity key | Bob's devices only (recovery key on paper) | Until Bob resets it; contacts pin it and see a warning if it changes |
| Device identity | per-device Ed25519 + X25519 keys, certified by the identity key | Bob's device | Per device |
| User and device ids | `u_FJJT…`, `d_P6Dw…` | Assigned by Bob's home server, identical everywhere | Forever, bound to the home server id |
| Home-server endpoint | `https://b.example` | Operator; signed in the descriptor | Changeable without changing any identity |
| Published key bundle | identity key + certified active devices | Bob's home server publishes; nobody else can | Changes when devices are added or revoked |
| Profile and discovery metadata | display name, avatar colour | Bob | Changeable; not used for trust |

**What changing what does:**
- Moving the server to a new IP or adding a reverse proxy changes nothing that peers check.
- Changing the domain is a new signed descriptor. Core nodes verify the new endpoint and rebind. Users keep their ids and keys; their
  address text changes, and peers update their usernames on the next sync.
- Rotating the server key appends to the chain (admin action); the server id is unchanged. Requests or descriptors signed with a
  rotated-away key are refused everywhere.
- A user's identity key is never derived from or tied to the server key, so no server action can change it silently.

## 2. Why an address can't be hijacked

Claim: an attacker can't make `@bob:b.example` resolve to an account they control, unless they control `b.example`'s endpoint **and**
it isn't bound to an existing server, or a quorum of core nodes is malicious. Even then, Bob's existing contacts are warned.

1. **Unsigned or foreign records aren't accepted.** A descriptor must hash to its server id and be signed by the current key in its
   chain. A core node can't map `b.example` to a different key without a valid descriptor for another server id.
2. **Endpoint control is proven by a challenge** (each core node, independently, with a fresh nonce naming that node). Pointing a
   descriptor at someone else's endpoint fails: that endpoint answers with its own identity (`challenge_failed`, tested).
3. **A domain bound to a live server can't be claimed** by another server id (`domain_taken`, tested). It's released only after the
   holder is decommissioned or revoked (signed records) or has been unreachable for 90 days (`CORE_DOMAIN_RELEASE_DAYS`).
4. **Resolution needs a quorum** of independently operated core nodes (default: majority) to agree, with answers signed by keys the
   home server is configured with. One malicious or stale node can't substitute a binding.
5. **Rollback is refused:** lower descriptor `seq` or a key chain that doesn't extend the known one (tested with a stolen old key).
6. **Bindings are pinned** by home servers. If a quorum later binds the domain to a different server id, the lookup reports
   `identityChanged` and the app warns ("This address now points to a different server than before").
7. **Contacts pin user identity keys.** Even a fully compromised path can only present a *different* key, which the recipient's app
   refuses to encrypt to until the safety number is checked (tested: `IdentityChangedError`).
8. **A server can only speak for its own users:** deliveries, key entries and call actions for a user bound to another server are
   rejected (`sender_not_from_peer`, `id_conflict`, `not_your_user`; tested), and shadows can't sign in.

Residual risks: a domain's DNS/TLS hijack before the domain is ever bound, or after its release; a malicious quorum of core
operators; a malicious home server publishing new keys for its own users (detected by pinning and safety numbers, not prevented).

## 3. Server key rotation and loss

- Rotation: `rotateKey()` on the server identity (operator tooling), then the server reissues and re-announces its descriptor. Peers that
  cached the old key re-resolve on the first signature failure. Tested: messages flowed straight after a rotation; the old key was refused.
- Root key compromise can't be recovered in place (the id is its hash): decommission the server id and start a new one.
- **Key loss = identity loss.** The key is in `DATA_DIR/federation` (volume), included in `scripts/backup.sh`, and `restore.sh` never
  overwrites an existing key.

## 4. Discovery

- **Exact address lookup** (`@name:domain`) through the user's own home server: `GET /v1/federation/resolve`. The home server resolves the
  domain (quorum), checks policy, and asks the remote server for exactly that username. The remote answers with the identity key and
  certified devices, or the same 404 whether the name doesn't exist or isn't reachable.
- The app shows the person, their address, their server, how the server was verified ("verified by 2 directory nodes" / "checked
  earlier"), the server id prefix and the safety number **before** the first message.
- **Key-change warnings:** existing pinning (`KeyDirectory`, T1.2) applies unchanged to remote people.
- **Invitations / QR codes:** sharing your address (Settings shows `@name:domain`, tap to copy) is supported. Organisation invite codes stay
  server-local; a QR code that encodes a global address and its expected server id is **not implemented** yet.
- **Abuse resistance:** lookups are rate-limited per user (30/min), per peer (60/min) and per peer server overall; core queries are
  rate-limited per IP; lookups are audited on the answering server (which server asked, not whom).
- **Privacy:** no address book is ever uploaded; only the exact address typed leaves the device, to the user's own server; the core
  network sees only domain and server-id queries from servers, never user names.

## 5. Global name search: deliberately not shipped

Exact-address lookup ships first. A global search (by name, across servers) would need either a central index of every user (a
privacy and enumeration problem, and a single point of control) or fan-out queries to every server (leaking the query to all of
them). Opt-in public profiles listed in a directory would be the safer future step: only users who opt in, signed by their home
server, rate-limited, and still verified end to end by key. Until there's a concrete need and a reviewed design, discovery across
servers is by address you get from the person.

## 6. Existing accounts

No re-registration and no key change: once a server sets `FEDERATION_DOMAIN`, each existing account is reachable as
`@username:domain` (verified on the local test server: `@emutest:a.griip.test`, created before federation). Server-scoped accounts are
never merged; someone with accounts on two servers has two addresses. Account migration between servers isn't implemented.
