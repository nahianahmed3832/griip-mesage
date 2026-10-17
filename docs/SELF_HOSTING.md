# Self-hosting Griip Message

Run your own Griip Message server for an organisation or community. People keep their existing account on another server:
and, if you enable federation (§12), your users can reach people on every other federated Griip server by address (`@name:domain`)
without accounts there. A standalone server (federation off) works exactly as before.

> **What was verified, and what wasn't (2026-10-16).** The server, migrations, health and info endpoints, backup and verify
> commands, the env validator, the bundle script and the LiveKit configuration were exercised on a Mac with the native Node
> server and the Homebrew `livekit-server` 1.13.10 binary, with an emulator and a phone as clients (`TESTING.md` §7).
> **Docker was not installed on that machine, so `docker compose up`, `scripts/backup.sh` and `scripts/restore.sh` were
> syntax-checked only.** The same `compose.yaml` runs the production server.

## 1. What you need

- A Linux host with Docker Engine and the Compose v2 plugin (`docker compose version`). 2 vCPU and 2 GB RAM is enough for tens of users
  and 1:1 calls (`ARCHITECTURE.md` §7).
- Two DNS names pointing at the host: one for the API (e.g. `msg.example.org`) and one for LiveKit (e.g. `rtc.example.org`).
- A TLS reverse proxy on the host (nginx, Caddy or similar). Release builds of the app connect over HTTPS only.
- Open in the host and provider firewalls: **TCP 7881 and UDP 7882** (call media, reached directly, not through the proxy), and
  TCP 443 for the proxy.

## 2. Components

| Service | Image / build | Listens on | Data |
|---|---|---|---|
| `api` | built from `server/` (`server/Dockerfile`, Node 22, non-root) | `127.0.0.1:${API_HOST_PORT:-18080}` → 8080 | volume `griip-internal-messaging_api-data` at `/data`: `griip.sqlite` (WAL) and `attachments/` (ciphertext only, ≤ 7 days) |
| `livekit` | `livekit/livekit-server:v1.13.10` | signalling `127.0.0.1:${LIVEKIT_SIGNAL_HOST_PORT:-17880}` → 7880; media `7881/tcp`, `7882/udp` | none (stateless) |

The Compose project name is fixed (`griip-internal-messaging`): every container, network and volume is prefixed with it, so the
deployment never touches other projects on the host. Run Compose only with `-p griip-internal-messaging`.

The API applies database migrations (`server/migrations/NNN_*.sql`, tracked in `schema_migrations`) every time it starts.

## 3. Install

From a checkout of this repository, on the host:

```bash
deploy/scripts/bundle.sh /opt/griip-internal-messaging
```

```bash
cd /opt/griip-internal-messaging && sh scripts/gen-config.sh
```

`bundle.sh` copies `compose.yaml`, `.env.example`, `server/` (source and migrations, no `node_modules`), the LiveKit template and the
scripts. `gen-config.sh` creates `.env` (mode 600) with a fresh LiveKit API key and a 48-character secret, then renders
`livekit/livekit.yaml`. It never overwrites an existing `.env`.

Edit `.env`:

| Variable | Set it to |
|---|---|
| `PUBLIC_API_URL` | `https://msg.example.org` (documentation for operators; the app is given this address) |
| `LIVEKIT_PUBLIC_URL` | `wss://rtc.example.org`. The API hands this to the app for each call |
| `LIVEKIT_NODE_IP` | the host's public IPv4. Leave empty to let LiveKit discover it with STUN (`LIVEKIT_USE_EXTERNAL_IP=true`) |
| `REGISTRATION_MODE` | `invite` (default: new accounts need an invitation) or `open` (anyone who can reach the server can sign up) |
| `INSTANCE_NAME` | optional display name shown on the app's server check, quoted if it has spaces: `INSTANCE_NAME="Example Community"` |
| ports, retention, quotas | defaults are in `.env.example`; retention is capped at 7 days in code |

Then render the LiveKit config again, check everything and start:

```bash
sh scripts/gen-config.sh
```

```bash
bash scripts/validate-env.sh
```

```bash
docker compose -p griip-internal-messaging -f compose.yaml up -d --build
```

```bash
GRIIP_DIR=/opt/griip-internal-messaging bash scripts/verify.sh
```

`validate-env.sh` is read-only. It reports each setting without printing secrets and fails on: example URLs left in place, URLs without
`https://`/`wss://` (plain `http`/`ws` is a warning: only debug app builds accept it), a missing or short LiveKit secret, an unknown
registration mode, invalid or duplicate ports, and a `livekit.yaml` rendered from different keys or ports than `.env`.

`verify.sh` and `preflight.sh` default to `/opt/griip-internal-messaging`; set `GRIIP_DIR` to use another directory.

## 4. Reverse proxy

The API needs WebSocket upgrade and a long read timeout (the realtime socket stays open). LiveKit signalling is also a WebSocket.
Media (7881/tcp, 7882/udp) does **not** go through the proxy. Example for nginx, inside your TLS `server` blocks:

```nginx
# msg.example.org
location / {
    proxy_pass http://127.0.0.1:18080;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 3600s;
    client_max_body_size 26m;   # attachments up to ATTACHMENT_MAX_BYTES (25 MiB) plus encryption overhead
}
# rtc.example.org
location / {
    proxy_pass http://127.0.0.1:17880;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_read_timeout 3600s;
}
```

`compose.yaml` sets `TRUST_PROXY=loopback,uniquelocal` for the API. The client address used for rate limits and sign-up
limits is then the last entry in `X-Forwarded-For` that your proxy added; entries a client writes itself are ignored.
If your proxy reaches the API from a public address, list that address instead. Don't set `TRUST_PROXY=true`: it
believes every entry, so anyone can pick their own address ([`ABUSE_CONTROLS.md`](ABUSE_CONTROLS.md)). Only expose the
API through a proxy you control.

## 5. Health, version and compatibility

- `GET /healthz` (no auth): `{"ok":true,"version":"0.1.0","time":…,"livekit":true}`. It checks the database. The Compose healthcheck
  uses it.
- `GET /v1/server-info` (no auth), additive document version `infoVersion: 1`:
  `name`, `version`, `api` (the client API the server speaks, 1), `minClientApi` (oldest app API it accepts, 1), `instanceName`,
  `registration` (`invite`/`open`), `calling` (whether LiveKit is configured), `queueTtlSeconds`, `attachmentMaxBytes`,
  `attachmentTtlSeconds` and `encryption` (`griip-envelope-v1 …`). It contains no secrets, paths or usage numbers.
- The app checks this before it saves or adds a server (`ServerCompat`): it refuses a server whose `minClientApi` is newer than
  the app, whose `api` is older, or whose encryption isn't `griip-envelope-v1`. Servers from before these fields existed count as API 1.

## 6. Accounts and invitations

With `REGISTRATION_MODE=invite`, create invitations with the operator CLI (there is no HTTP admin surface):

```bash
docker compose -p griip-internal-messaging -f compose.yaml exec api node dist/admin.js reg-invite 5 7 "first members"
```

Other commands: `stats`, `users`, `invite <orgSlug> [ADMIN|MEMBER|GUEST] [uses]`, `reg-invites`, `reg-invite-revoke <hint|id>`,
`disable <username>`, `enable <username>`, `cleanup`, `backup <file>`, `verify-backup <file>` (`server/src/admin.ts`).

## 7. Calls: self-hosted LiveKit

- The API issues 10-minute, room-scoped LiveKit tokens to conversation members only. The LiveKit API secret stays on the server;
  the app never sees it.
- LiveKit calls the API back at `http://api:8080/v1/livekit/webhook` (inside the Compose network, signed with the API key) and the API
  reconciles rooms every 60 s, so call state survives missed webhooks.
- `livekit.yaml` (rendered): `rtc.tcp_port` 7881, `rtc.udp_port` 7882, `use_external_ip` or `node_ip`, rooms capped at 12 participants.
  The host port must equal the container port so ICE candidates are correct.
- **No TURN server is configured.** Phones on networks that block UDP fall back to TCP 7881 (the app then says "Connected over TCP");
  networks that block both can't place calls. If your users are behind strict firewalls, add LiveKit's built-in TURN (TLS on 443 with
  its own certificate and DNS name) following LiveKit's documentation; it isn't part of this kit.
- Media is end-to-end encrypted with a per-call key that travels inside the encrypted message channel (`CALLING_AND_E2EE.md`);
  your LiveKit server forwards encrypted frames it can't read.
- Without `LIVEKIT_API_KEY`/`LIVEKIT_API_SECRET`/`LIVEKIT_PUBLIC_URL`, the server runs with calling off (`calling: false`) and the app
  says "Calling isn't set up on this server."

## 8. Backup and restore

```bash
bash scripts/backup.sh
```

Takes a consistent SQLite snapshot while the server runs (`admin.js backup`, SQLite's online backup API), verifies it
(`admin.js verify-backup`: integrity check, schema version, user count), and writes `backups/griip-<UTC time>.tar.gz` with the
snapshot and the encrypted attachment files, plus a `.verify.json` report. Archives are owner-only (`umask 077`).
**`.env` and `livekit/livekit.yaml` are not included** (they hold secrets); keep copies separately.

```bash
bash scripts/restore.sh backups/griip-20261016T120000Z.tar.gz --yes
```

Restore checks the snapshot first while the server keeps running; if the check fails, nothing changes. Then it stops the API,
moves the current database to `/data/pre-restore-<time>/` (it is never deleted), puts the snapshot in place, adds the archived
attachment files without overwriting existing ones, and starts the API (migrations run). A snapshot from a newer server schema is
refused.

What a backup can't restore: messages are end-to-end encrypted and history lives on devices. The server holds only undelivered
ciphertext (≤ 7 days) and account, device and organisation metadata. Restoring an older snapshot loses queued messages and
account changes made after it was taken.

## 9. Upgrades

```bash
deploy/scripts/bundle.sh /opt/griip-internal-messaging
```

```bash
cd /opt/griip-internal-messaging && bash scripts/backup.sh && docker compose -p griip-internal-messaging -f compose.yaml up -d --build
```

Re-running `bundle.sh` updates the code and scripts and keeps `.env`, `livekit/livekit.yaml`, `backups/` and the volume. Migrations
are additive and run at start.

## 10. Removal

`docker compose -p griip-internal-messaging -f compose.yaml down` stops the project and keeps its data. Adding `-v` also deletes its
data volume; take a backup first. Neither touches other Compose projects. Remove the proxy site, DNS records and firewall rules yourself.

## 11. Limits

- One API process (in-memory WebSocket routing) and SQLite: fine for tens to low hundreds of users, not horizontally scalable.
- No Google push service. Android phones receive messages while the app or its optional "stay connected" service runs. Optional
  payload-free wake-ups through your own ntfy server (UnifiedPush) need an ntfy container, a DNS name, a proxy site and
  `PUSH_UNIFIEDPUSH_HOSTS` (`BACKGROUND_DELIVERY.md` § Deploying). A phone whose app was force-stopped gets nothing until it's opened.
- Federation v1 covers direct messages, attachments, voice messages and 1:1 calls; groups and channels stay server-local (§12).

## 12. Joining the federated network

> Verified with the native server and three local core nodes (`TESTING.md` §8); the Docker variants below weren't run on this machine.

1. Choose the federation domain. Production requires `FEDERATION_PUBLIC_URL=https://<domain>` (the core nodes challenge that endpoint;
   its host must be the domain). Proxy `/.well-known/griip-server` and `/fed/` to the API like the rest (§4).
2. Get the core network's node list (`url#publicKey` per node) from its operators. Set in `.env`:
   `FEDERATION_DOMAIN`, `FEDERATION_PUBLIC_URL`, `FEDERATION_CORE_NODES`, optionally `FEDERATION_CORE_QUORUM` (default majority),
   `FEDERATION_POLICY=open|allowlist` and `FEDERATION_ALLOWLIST`.
3. `bash scripts/validate-env.sh` (checks the federation settings too), then restart the API. On start it creates its identity key in
   `/data/federation/server-key.json` (never overwritten), announces itself to every core node and starts heartbeats and delivery.
4. Check: `docker compose -p griip-internal-messaging -f compose.yaml exec api node dist/admin.js fed-status` shows the server id, domain,
   peers and queued deliveries; `/v1/server-info` shows `federation.domain` and `federation.serverId`.
5. **Back up** with `scripts/backup.sh`: archives now include `federation/` (the identity key). Losing it means a new server identity.

Operator commands (`server/src/admin.ts`): `fed-status`; `fed-block <serverId> [reason]` / `fed-unblock`; `fed-rotate-key` (stop the API
first); `fed-decommission "<reason>" --yes` (permanent). Lifecycle: `SERVER_LIFECYCLE.md`.

### Running a core node

Core nodes are for the network's operators (independent organisations), not every server. A node is the same build with another entry
point: `node dist/core/index.js` with `CORE_DATA_DIR`, `CORE_PEERS` (`url#publicKey` of the other nodes) and the variables in
`FEDERATION_PROTOCOL.md` §3.5. With Docker, using the API image (its `/data` is writable by the app user):

```bash
docker run -d --name griip-core-1 --restart unless-stopped -p 127.0.0.1:18100:8090 -v griip-core-1:/data -e CORE_DATA_DIR=/data/core -e CORE_PEERS="https://core2.example#KEY2,https://core3.example#KEY3" griip-internal-messaging-api:0.1.0 node dist/core/index.js
```

Put it behind TLS at its own name, read its public key from `GET /core/v1/info`, and give the node list to server operators. Run at least
three nodes under separate administration so the network survives the loss of one.
