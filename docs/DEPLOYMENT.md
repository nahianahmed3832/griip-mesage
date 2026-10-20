# Deployment

## Current deployment (internal beta)

| Item | Value |
|---|---|
| VPS | Hostinger VM 1245833, `srv1245833.hstgr.cloud`, **72.60.107.88** (Ubuntu 24.04, 2 vCPU, 8 GB) |
| Compose project | **`griip-internal-messaging`** |
| Directory | **`/opt/griip-internal-messaging`** (root, 0750; `.env` 0600) |
| Services | `api` (`griip-internal-messaging-api:0.10.0` since 2026-10-20 18:09 UTC, built on the VPS; `0.9.0-rollback` and `0.8.1-rollback` kept), `livekit` (`livekit/livekit-server:v1.13.10`), `ntfy` (`binwiederhier/ntfy:v2.31.1`) |
| Volume / network | `griip-internal-messaging_api-data`, `griip-internal-messaging_default` |
| Public URLs | `https://msg.griipbuddy.cloud` (API + WebSocket), `wss://rtc.griipbuddy.cloud` (LiveKit signalling) |
| TLS | host nginx (`/etc/nginx/sites-available/griip-message.conf`) with certbot certificate `griip-message`, which renews automatically |
| Ports | 127.0.0.1:18080 → api, 127.0.0.1:17880 → LiveKit HTTP. Public: **7881/tcp** (ICE TCP), **7882/udp** (media) |
| Firewall | Hostinger group "web-only" (id 376928): 22, 80, 443 plus 7881/tcp and 7882/udp, which were added for this project |
| DNS | Hostinger DNS for `griipbuddy.cloud`: A records `msg` and `rtc` → 72.60.107.88 |

Nothing else on the server was modified. No other Compose project, volume, nginx site or certificate was touched.

## Operating it (always project-scoped)

```bash
ssh root@72.60.107.88
```

```bash
cd /opt/griip-internal-messaging
```

Status, health and logs:

```bash
docker compose -p griip-internal-messaging -f compose.yaml ps
```

```bash
bash scripts/verify.sh
```

```bash
docker compose -p griip-internal-messaging -f compose.yaml logs --tail 100 api
```

Operator CLI (there is no admin HTTP surface):

```bash
docker compose -p griip-internal-messaging -f compose.yaml exec api node dist/admin.js stats
```

```bash
docker compose -p griip-internal-messaging -f compose.yaml exec api node dist/admin.js invite griipbuddy MEMBER 20
```

**Registration is invite-only by default** (`REGISTRATION_MODE=invite`, T1.6). A new account needs one of:
- an **org invite** code from an org manager, which also joins the person to that org;
- a **registration invite** from the operator. The code is shown once, and only its hash is stored. Existing accounts sign in as before.

```bash
docker compose -p griip-internal-messaging -f compose.yaml exec api node dist/admin.js reg-invite 5 7 "QA testers"
```

That gives 5 uses for 7 days. List invites with `reg-invites` and revoke one with `reg-invite-revoke <hint|id>`.

**Limits on the shared VPS** (T1.6):
- Compose caps the API at 1 CPU, 512 MB and 256 processes, and LiveKit at 1.5 CPU, 1 GB and 512 processes.
- Uploads stream to disk.
- Per-user (250 MiB/day, 1 GiB stored) and server-wide (5 GiB) attachment quotas and in-flight limits are set by the
  `ATTACHMENT_*` variables (`.env.example`).
- Docker local volumes have no disk quota. The storage bound comes from the server-wide attachment cap plus the 7-day expiry, and from
  capped container logs (10 MB × 5 each). `verify.sh` warns when the Docker disk has less than 5 GB free.

Restart this project only:

```bash
docker compose -p griip-internal-messaging -f compose.yaml restart
```

**Never** run `docker system prune`, `docker volume prune` or any other global cleanup on this shared server.

## API-only upgrade (how 0.6.0 went out, 2026-10-19)

The usual way to ship a new API version without touching LiveKit or ntfy:

1. `bash scripts/backup.sh backups/pre-<version>-<UTC time>` on the VPS, plus copies of `.env` and `compose.yaml` into
   the same folder, and `docker image tag griip-internal-messaging-api:<old> griip-internal-messaging-api:<old>-rollback`.
   (Run commands that use `docker compose exec` with stdin closed: piped into `bash -s`, they swallow the rest of the script.)
2. rsync `server/` (without `node_modules`, `dist`, `data`, `test`), `deploy/scripts/` and `.env.example`; then
   `docker build -t griip-internal-messaging-api:<new> server`.
3. Rehearse the migrations on a copy of the snapshot in a throwaway container (`--network none`, `DATA_DIR` pointing at
   the copy, `node dist/admin.js stats`, then `verify-backup`).
4. Install `compose.yaml` (its `image:` names the new tag), `validate-env.sh`, `docker compose … config -q`, then
   `docker compose -p griip-internal-messaging -f compose.yaml up -d --no-deps --no-build api`.
5. `verify.sh`, `/healthz`, server-info features, logs, and a bot round-trip (`tools/griip-bot`, a production test identity).

Rollback: set `image:` back to the `-rollback` tag and run the same `up -d --no-deps --no-build api`. Migrations are
additive, so the older code starts on the newer database (it ignores tables it doesn't know). A full data restore uses
`scripts/restore.sh` with the pre-upgrade archive.

### API 0.8.1 (2026-10-20, 13:04 UTC, owner's go-ahead)

0.8.0 (internal-beta `64a5254`, the server the Android 0.2.3-beta build uses; deployed ~11:45 UTC by another session)
+ `170b988`: integration sign-ins can return to Meldiq Web (`INTEGRATIONS_WEB_RETURN_URLS`, docs/INTEGRATIONS.md §3).
Branch `api-0.8.1-web-return`. No migrations (schema stays 28).

| Step | Result |
|---|---|
| Checks before | `tsc`, build, server tests 723 passed in SQLite and in MongoDB mode (MongoDB mode with 3 workers: at full parallelism its suites time out starting replica sets) |
| Pre-flight | running image 0.8.0; every VPS `server/` file's checksum equal to `64a5254`; `compose.yaml` unchanged; 54 GB free |
| Backup | `backups/pre-0.8.1-20261020T130126Z`: integrity ok, schema 28, 32 users, `.env` + `compose.yaml` copies; image tagged `0.8.0-rollback`; sources kept as `server.pre-0.8.1` |
| Config | `.env` += `INTEGRATIONS_WEB_RETURN_URLS=https://web.meldiq.app/#/workspace/apps` (unquoted: the `#` follows a `/`); a one-off container from the new image parsed it from the real env file before the switch |
| Switch | `validate-env.sh`, `config -q`, `up -d --no-deps --no-build api`; healthy; LiveKit and ntfy not restarted |
| Checks after | `verify.sh` 0 restarts; `/healthz` 0.8.1 on msg.griipbuddy.cloud and through web.meldiq.app; the variable inside the container; no warnings or errors in the log |

Not verifiable on production yet: no one can connect an app there (`integrations-status`: no `INTEGRATIONS_TOKEN_KEY`, no
provider credentials, 0 installs). Rollback: `image: griip-internal-messaging-api:0.8.0-rollback`, the same
`up -d --no-deps --no-build api` (the extra variable is ignored by 0.8.0).

### API 0.10.0 and Meldiq Web d870db2 (2026-10-20, 18:09 UTC, owner's go-ahead)

Round 3 (`claude/meldiq-3-reference-rebuild` at `d6a8d08`): statuses with an emoji and an end time, pausing
notifications, "N online" per conversation (migration 030), plus the client work in
docs/product/MELDIQ_3_REFERENCE_MATRIX.md. Deployed before phone acceptance, on the owner's instruction; the owner
plans full testing next.

| Step | Result |
|---|---|
| Checks before | on the final source: server 753 passed / 10 skipped in SQLite and MongoDB modes; web 402 passed; Android 546 passed, lint 0 errors |
| Pre-flight | running 0.9.0, healthy; VPS `server/` sources identical to `5ef1b71` (checksum); 52 GB free |
| Backup | `backups/pre-0.10.0-20261020T180755Z`: integrity ok, schema 29, 33 users, `.env` + `compose.yaml` copies; image tagged `0.9.0-rollback`; sources kept as `server.pre-0.10.0` |
| Rehearsal | 0.10.0 on a copy of the snapshot, `--network none`: migration 30 applied, the three columns present, `verify-backup` ok (schema 30, 33 users) |
| Switch | `validate-env.sh` 0 errors, `config -q`, `up -d --no-deps --no-build api`; healthy; LiveKit and ntfy not restarted |
| Checks after | `/healthz` 0.10.0; restarts 0; no warnings or errors in the log; schema 30, 33 users; server-info lists `status-expiry`, `notifications-pause`, `conversation-presence` |
| Web | release `0.2.4-beta-d870db2` (contains `4a79225`); `verify.sh https://web.meldiq.app` all passed; API 0.10.0 through it |

Rollback:
- API: `image: griip-internal-messaging-api:0.9.0-rollback`, then `up -d --no-deps --no-build api`. 0.9.0 ignores the
  three new columns.
- Web: `bash /root/meldiq-web-0.2.4-beta-d870db2/rollback.sh`.

### API 0.9.0 and Meldiq Web 4a79225 (2026-10-20, 16:53 UTC, owner's go-ahead)

Branch `claude/meldiq-2-redesign` at `5ef1b71`: production 0.8.1, remediation round 1 (account deletion, migration
013; revoked-device fix) and Meldiq 2.0 round 1 (communities and analytics, migration 029; curated directory;
experiments off). The owner chose to deploy before the phone acceptance test.

| Step | Result |
|---|---|
| Checks before | GitHub CI green on both branches in both repositories (server, Android, web); server 745 tests passed locally |
| Pre-flight | running image 0.8.1, healthy; VPS `server/` sources identical to `api-0.8.1-web-return` (checksum); 53 GB free |
| Backup | `backups/pre-0.9.0-20261020T165059Z`: integrity ok, schema 28, 33 users, `.env` + `compose.yaml` copies; image tagged `0.8.1-rollback`; sources kept as `server.pre-0.9.0` |
| Rehearsal | 0.9.0 image on a copy of the snapshot, `--network none`: migrations 13 and 29 applied, `verify-backup` ok (schema 29, 33 users), 6 workspaces all `team` |
| Switch | `validate-env.sh` 0 errors, `config -q`, `up -d --no-deps --no-build api`; healthy; LiveKit and ntfy not restarted |
| Checks after | `verify.sh` 0 restarts; `/healthz` 0.9.0; server-info lists `account-delete`, `communities`, `experiments`, `analytics-events`; no warnings or errors in the log. `EXPERIMENTS_MODE` and `ANALYTICS_ENABLED` are unset, so both are off |
| Web | release `0.2.4-beta-4a79225` (contains the previous `e95c739`), installed with the same command as before; `verify.sh https://web.meldiq.app` all passed; API 0.9.0 through it; web.griipbuddy.cloud still redirects |

Not done: the bot round-trip (the permission check refused reading the bot's identity file) and phone acceptance.

Rollback:
- API: `image: griip-internal-messaging-api:0.8.1-rollback`, then the same `up -d --no-deps --no-build api`. 0.8.1
  ignores `users.deleted_at`, `orgs.kind` and `analytics_daily`.
- Web: `bash /root/meldiq-web-0.2.4-beta-4a79225/rollback.sh`.

Existing `.env` warnings, unchanged by this deploy: `APP_ENV=development` and `REGISTRATION_MODE=open` on production.

## Redeploying from a workstation

```bash
TARGET=root@72.60.107.88 ./deploy/scripts/deploy.sh
```

The script:
1. runs `scripts/preflight.sh` on the VPS, which is read-only: resources, Compose projects, port conflicts, proxy and firewall;
2. rsyncs `server/`, `compose.yaml`, the LiveKit template and the scripts;
3. runs `gen-config.sh`, which creates `.env` with fresh LiveKit credentials only if it's missing and renders `livekit/livekit.yaml`;
4. runs `docker compose -p griip-internal-messaging up -d --build` followed by `verify.sh`.

## First-time setup on another server

1. Run `deploy/scripts/preflight.sh` on the server and resolve any conflicts it reports.
2. Point DNS for two names at the server: one for the API, one for LiveKit.
3. Open the LiveKit media ports in the provider firewall: TCP `LIVEKIT_RTC_TCP_PORT` (7881) and UDP `LIVEKIT_RTC_UDP_PORT` (7882).
   The host port must equal the container port so that ICE candidates are correct.
4. Copy `deploy/` and `server/` to `/opt/griip-internal-messaging`, then run `scripts/gen-config.sh`. Edit `.env` to set `PUBLIC_API_URL`,
   `LIVEKIT_PUBLIC_URL` and `LIVEKIT_NODE_IP` (the public IPv4), then run `scripts/gen-config.sh` again.
5. Set up a reverse proxy with TLS:
   - API name → `127.0.0.1:18080`, with WebSocket upgrade and a 3600 s read timeout.
   - LiveKit name → `127.0.0.1:17880`, with WebSocket upgrade.
   - See the live config at `/etc/nginx/sites-available/griip-message.conf`.
6. Start the project:

   ```bash
   docker compose -p griip-internal-messaging -f compose.yaml up -d --build
   ```

7. Check health with `bash scripts/verify.sh` and `curl https://<api>/healthz`.

## Seeding the GriipBuddy structure (optional, development data)

Register the owner in the app first, then run:

```bash
docker compose -p griip-internal-messaging -f compose.yaml exec api node dist/seed.js --owner <username> --force
```

It creates the GriipBuddy org with the Bangladesh and International branches, the departments, teams and channels from the spec, and prints invite codes.
It refuses to run with `NODE_ENV=production` unless you pass `--force`, and it never creates sample users or messages.

## MongoDB (2026-10-19, not in production)

The server can keep the accounts and preferences domains in MongoDB (`docs/MONGODB.md`): the settings are in
`deploy/.env.example` (`MONGODB_*`), checked by `validate-env.sh`. Moving a domain is a maintenance-window step with
backups first (`MONGODB.md` §6–§7: stop the API, `node dist/admin.js mongo-migrate <domain> --yes`, add the domain to
`MONGODB_DOMAINS`, start, smoke-test); rollback is `mongo-rollback`. Production has no MongoDB deployment yet.

## Rollback / removal (reversible)

Stop the project and keep its data:

```bash
docker compose -p griip-internal-messaging -f compose.yaml down
```

Full removal deletes this project's data volume only:

```bash
docker compose -p griip-internal-messaging -f compose.yaml down -v
```

Then:
- Run `rm /etc/nginx/sites-enabled/griip-message.conf`, then `nginx -t`, then `systemctl reload nginx`.
- Optionally run `certbot delete --cert-name griip-message`.
- Remove the `msg` and `rtc` DNS records and the 7881/tcp and 7882/udp firewall rules in Hostinger.

## Android app configuration

- Release builds default to production (`https://msg.griipbuddy.cloud` until the Meldiq hostname is approved); debug
  builds to development (`http://localhost:8080`, through `adb reverse`). Set `meldiq.apiBaseUrl.<environment>` in
  `android/local.properties` for another address (docs/ENVIRONMENTS.md).
- Users can change the server before creating an identity: Welcome screen → server icon.

## Resource observations (2026-10-16)

- **Before deployment:** the VPS ran about 20 other containers. RAM was 7.8 GiB total with about 3.6 GiB available and no swap. Disk was 54 GB free. No OOM kills in 7 days.
- **After deployment, idle:** the API uses about 32 MiB of RAM and LiveKit about 22 MiB, both at near-zero CPU. They are capped at 512 MiB and 1 GiB respectively.
- **Media:** a 2-party test call carried E2EE video and audio through the SFU. Group-call capacity hasn't been benchmarked.
- **Warning:** LiveKit says the host's UDP receive buffer (425,984 bytes) is below its recommended 5 MB. That's fine for small tests. Raising `net.core.rmem_max` is a host-wide change, so it was not made.
