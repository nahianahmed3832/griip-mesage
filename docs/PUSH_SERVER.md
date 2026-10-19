# Push server (ntfy) in production: runbook

**Status (2026-10-17): prepared and tested locally. Nothing below has been applied to production.** Each numbered step
is a production change that needs the owner's explicit approval, given separately. Stop after any check that fails.

This turns on the payload-free wake-ups of `BACKGROUND_DELIVERY.md` (option B) for `https://msg.griipbuddy.cloud`: our own
ntfy at `https://ntfy.griipbuddy.cloud`, and API 0.3.0 with `PUSH_UNIFIEDPUSH_HOSTS=ntfy.griipbuddy.cloud`.

## What changes

| Piece | Where | Value |
|---|---|---|
| DNS | Hostinger DNS, zone `griipbuddy.cloud` | `A ntfy → 72.60.107.88`, TTL 300 (like `msg` and `rtc`) |
| Container | service `ntfy` of Compose project `griip-internal-messaging` | `binwiederhier/ntfy:v2.31.1` pinned by digest, `127.0.0.1:18081` → 80, 256 MB / 0.5 CPU / 128 processes, all capabilities dropped |
| Config | `/opt/griip-internal-messaging/ntfy/server.yml` (`deploy/ntfy/server.yml`) | below |
| Data | volumes `griip-internal-messaging_ntfy-cache`, `_ntfy-auth` | wake-ups held 12 h, ntfy's access database; both safe to lose, so not backed up |
| TLS site | `/etc/nginx/sites-available/griip-ntfy.conf` (`deploy/nginx/griip-ntfy.conf`), enabled by a symlink | certificate `griip-ntfy`, webroot `/var/www/griip-message-acme` like `griip-message` |
| API | image `griip-internal-messaging-api:0.3.0` | migrations 011 (push endpoints) and 012 (abuse controls); `.env` gains `PUSH_UNIFIEDPUSH_HOSTS` |

Nothing else on the server changes: no other Compose project, nginx site, certificate or firewall rule, and LiveKit is
not recreated.

**ntfy's settings** (`deploy/ntfy/server.yml`):
- `base-url: https://ntfy.griipbuddy.cloud`, `behind-proxy: true` (a visitor is the right-most `X-Forwarded-For`
  address, the one nginx adds), `cache-duration: 12h`;
- `auth-default-access: deny-all` with the one rule `everyone:up*:rw`. That's the declarative form of
  `ntfy access everyone 'up*' read-write`: ntfy applies it again at every start, so nothing is set by hand;
- `visitor-subscriber-rate-limiting: true`: wake-ups count against the subscribing phone's limits, not the API's;
- sign-up, login, reservations and the web app off. No attachments, no e-mail, no web push, no upstream (`ntfy.sh`).

**Why read-write rather than ntfy's usual write-only for `up*`:** phones subscribe without an account. ntfy makes a
subscriber the topic's "rate visitor" only if it may also write. Without a rate visitor, a UnifiedPush publish gets
HTTP 507.

## Checked before writing this (2026-10-17, read-only)

| Check | Result |
|---|---|
| `127.0.0.1:18081` on the VPS | free (`ss -tlnp`) |
| Versions | nginx 1.24.0, certbot 2.9.0, Docker 29.1.3, Compose 5.0.1 |
| The VPS reaching its own public address on 443 | **works**: from inside the `api` container, Node's `fetch("https://msg.griipbuddy.cloud/healthz")` returned 200 in 236 ms (`ip route get 72.60.107.88`: local; `ufw` inactive) |
| `ntfy.griipbuddy.cloud` | doesn't exist yet (NXDOMAIN; negative TTL 600 s). The record above passed Hostinger's `dns_records_validate` |
| Free memory | about 4.8 GB available |

Tested locally with an ntfy v2.31.1 server built from the release tag, running this exact `server.yml` (only paths and
listen address changed):
- `/`, `/config.js` and `/metrics` answer 404;
- publishing to, or reading, any other topic answers 403;
- `ntfy access` lists only `everyone: read-write up*` and "no access to any (other) topics";
- `?up=1` to an `up` topic nobody subscribes to answers 507;
- with a subscriber, the empty POST Meldiq sends (`TTL`, `Urgency: high`, `User-Agent: meldiq-push/1`) answers 200, and the
  subscriber gets `"message":"triggered"` at once;
- a wake-up sent while the subscriber is away is delivered when it reconnects with `since=`;
- the wake-up is counted against the subscriber's address (taken from `X-Forwarded-For`), not the publisher's.

`compose.yaml` validates against the Compose specification schema. `bundle.sh`, `gen-config.sh` and `validate-env.sh` ran on a
throwaway directory: a missing `ntfy/server.yml`, a port clash and a push host that doesn't match `base-url` are each reported.
**Not tested locally:** the container itself (no Docker on the Mac) and the nginx file (no nginx 1.24). Step 2 checks
the container on loopback before anything is public, and `nginx -t` runs before every reload in step 3.

## 0. Code on `internal-beta` (approval: commit and push)

Production deploys `internal-beta`. That branch must contain:
- the abuse controls (`2aa13e3`);
- the push wake-up commits, merged with it (`c96bc18`);
- this preparation:
  - `deploy/ntfy/`, `deploy/nginx/`, this runbook;
  - the script and compose changes;
  - version 0.3.0 in `server/src/app.ts`, `server/package.json` and `server/package-lock.json`, and the image tag in
    `deploy/compose.yaml`.

**Deploying 0.3.0 also turns on the abuse controls** (`ABUSE_CONTROLS.md` § Activating it). Its checks are part of step 4.

Push only with the full refspec (`refs/heads/internal-beta:refs/heads/internal-beta`; a tag has the same name), then wait
for **CI result** to pass.

Commands below use these two names, and run from the repository root at that commit:

```bash
R=root@72.60.107.88
```

```bash
D=/opt/griip-internal-messaging
```

## 1. DNS (approval)

Add one record in Hostinger DNS. Use hPanel, or the API's `dns_records_update` for `griipbuddy.cloud` with `overwrite: false`
(it only appends):

```json
[{"name": "ntfy", "type": "A", "ttl": 300, "records": [{"content": "72.60.107.88"}]}]
```

**Check:** both lines print `72.60.107.88`. Public resolvers may cache the earlier NXDOMAIN for up to 600 s.

```bash
dig +short ntfy.griipbuddy.cloud @orbit.dns-parking.com
```

```bash
dig +short ntfy.griipbuddy.cloud @1.1.1.1
```

**Rollback:** delete the `ntfy` A record (`dns_records_delete` with filter name `ntfy`, type `A`). Nothing else uses it.

## 2. Files and the ntfy container, on loopback only (approval)

Nothing public changes in this step: the container listens on `127.0.0.1:18081` only, and no site points at it yet. The
running `api` and `livekit` containers keep running as they are.

**2a. Back up the deployment's files.** The `.env` file is copied, never printed.

```bash
ssh $R "cd $D && b=backups/pre-0.3.0-\$(date -u +%Y%m%dT%H%M%SZ) && mkdir -p \$b && cp -a compose.yaml .env.example scripts \$b/ && cp -p .env \$b/env.backup && echo \$b"
```

Note the printed directory. It's `$B` below.

**2b. Sync the files.** This is what `deploy.sh` syncs, without its last step, which would start or recreate every service:

```bash
rsync -az --delete --exclude node_modules --exclude dist --exclude data --exclude test server/ $R:$D/server/
```

```bash
rsync -az deploy/compose.yaml deploy/.env.example $R:$D/
```

```bash
ssh $R "mkdir -p $D/ntfy" && rsync -az deploy/ntfy/server.yml $R:$D/ntfy/
```

```bash
rsync -az deploy/scripts/ $R:$D/scripts/
```

From here until step 4, `compose.yaml` on the VPS names image 0.3.0, which isn't built yet. **Don't run a plain
`docker compose up -d` or `deploy.sh`.** Use only the per-service commands below. A reboot is safe: Docker restarts
the existing containers as they are.

**2c. Start ntfy** on the VPS (`ssh $R`, then `cd /opt/griip-internal-messaging`):

```bash
bash scripts/validate-env.sh
```

```bash
docker compose -p griip-internal-messaging -f compose.yaml config -q
```

```bash
docker compose -p griip-internal-messaging -f compose.yaml up -d --no-deps ntfy
```

**2d. Check, on the VPS:**

```bash
docker compose -p griip-internal-messaging -f compose.yaml ps ntfy
```

It must show `healthy` within about 40 s.

```bash
docker compose -p griip-internal-messaging -f compose.yaml exec -T ntfy ntfy access
```

It must list only read-write `up*` for everyone, and no access to any other topic.

```bash
curl -sS http://127.0.0.1:18081/v1/health
```

It must print `{"healthy":true,…}`.

```bash
curl -s -o /dev/null -w '%{http_code}\n' -d x http://127.0.0.1:18081/probe
```

It must print `403`.

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST 'http://127.0.0.1:18081/upRunbook00001?up=1'
```

It must print `507`: no subscriber.

```bash
(curl -sN -m 6 -H 'X-Forwarded-For: 198.51.100.7' http://127.0.0.1:18081/upRunbook00001/json &) ; sleep 2; curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'X-Forwarded-For: 203.0.113.9' 'http://127.0.0.1:18081/upRunbook00001?up=1'; sleep 4
```

It must print the `open` event, then `200`, then a `"message":"triggered"` event. These test wake-ups expire with the
12 h cache.

```bash
docker compose -p griip-internal-messaging -f compose.yaml logs --tail 30 ntfy
```

```bash
docker stats --no-stream $(docker compose -p griip-internal-messaging -f compose.yaml ps -q ntfy)
```

**Rollback:** remove the container and its two volumes only:

```bash
docker compose -p griip-internal-messaging -f compose.yaml rm -sf ntfy
```

```bash
docker volume rm griip-internal-messaging_ntfy-cache griip-internal-messaging_ntfy-auth
```

To undo the file sync as well, copy `compose.yaml`, `.env.example` and `scripts/` back from `$B`. `server/` is only
build input: the running image doesn't change until step 4.

## 3. nginx site and certificate (approval)

**3a. Serve the ACME challenge for the new name** (HTTP only; the full site needs the certificate first), on the VPS:

```bash
cat > /etc/nginx/sites-available/griip-ntfy.conf <<'EOF'
# Bootstrap for the certificate; replaced by deploy/nginx/griip-ntfy.conf in step 3c.
server {
    listen 80;
    listen [::]:80;
    server_name ntfy.griipbuddy.cloud;
    access_log off;
    location ^~ /.well-known/acme-challenge/ {
        root /var/www/griip-message-acme;
        default_type text/plain;
    }
    location / { return 404; }
}
EOF
```

```bash
ln -s /etc/nginx/sites-available/griip-ntfy.conf /etc/nginx/sites-enabled/griip-ntfy.conf
```

```bash
nginx -t && systemctl reload nginx
```

Run the reload only if `nginx -t` reports success.

**3b. Certificate.** A dry run against Let's Encrypt staging first, then the real one. It uses the existing certbot
account, and renewals use the same webroot through `certbot.timer`.

```bash
certbot certonly --dry-run --webroot -w /var/www/griip-message-acme -d ntfy.griipbuddy.cloud --cert-name griip-ntfy --non-interactive
```

```bash
certbot certonly --webroot -w /var/www/griip-message-acme -d ntfy.griipbuddy.cloud --cert-name griip-ntfy --non-interactive
```

**3c. The full site**, from the Mac:

```bash
scp deploy/nginx/griip-ntfy.conf $R:/etc/nginx/sites-available/griip-ntfy.conf
```

Then on the VPS:

```bash
nginx -t && systemctl reload nginx
```

The site file does the following:
- proxies to `127.0.0.1:18081` with `proxy_buffering off`, `proxy_request_buffering off` and a 3600 s read timeout
  (ntfy sends keepalives every 45 s);
- passes WebSocket upgrades through its own `map`;
- sets HSTS and a 16 KB body limit;
- redirects HTTP to HTTPS, except for the ACME path;
- writes an access log (`/var/log/nginx/griip-ntfy.access.log`) with no client address and no URL, because the URL
  carries the phone's topic.

**3d. Check, from the Mac:**

```bash
curl -sS https://ntfy.griipbuddy.cloud/v1/health
```

It must print `{"healthy":true,…}` with a valid certificate.

```bash
curl -s -o /dev/null -w '%{http_code}\n' -d x https://ntfy.griipbuddy.cloud/probe
```

It must print `403`.

```bash
curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' http://ntfy.griipbuddy.cloud/v1/health
```

It must print `308 https://…`.

```bash
(curl -sN -m 8 https://ntfy.griipbuddy.cloud/upRunbook00002/json &) ; sleep 3; curl -s -o /dev/null -w '%{http_code}\n' -X POST 'https://ntfy.griipbuddy.cloud/upRunbook00002?up=1'; sleep 5
```

It must print the `open` event, then `200`, then the `triggered` event **at once**. A delay means nginx is buffering.

```bash
curl -s -o /dev/null -w '%{http_code}\n' --http1.1 -m 5 -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' https://ntfy.griipbuddy.cloud/upRunbook00002/ws
```

It must print `101`.

Then the way the API will go, from inside the `api` container on the VPS:

```bash
docker compose -p griip-internal-messaging -f compose.yaml exec -T api node -e 'fetch("https://ntfy.griipbuddy.cloud/v1/health").then((r) => console.log(r.status), (e) => console.log("failed", e.cause && e.cause.code))'
```

It must print `200`.

```bash
certbot renew --cert-name griip-ntfy --dry-run
```

**Rollback:**

```bash
rm /etc/nginx/sites-enabled/griip-ntfy.conf /etc/nginx/sites-available/griip-ntfy.conf
```

```bash
nginx -t && systemctl reload nginx
```

```bash
certbot delete --cert-name griip-ntfy
```

The other sites and certificates aren't affected; the shared webroot directory stays.

## 4. API 0.3.0, wake-ups still off (approval)

On the VPS, in `/opt/griip-internal-messaging`:

**4a. Back up the database and files** (snapshot taken while running, plus a check):

```bash
bash scripts/backup.sh $B
```

**4b. Build the new image.** The running API is untouched, and `0.2.0` stays tagged for rollback:

```bash
docker compose -p griip-internal-messaging -f compose.yaml build api 2>&1 | tee build-0.3.0.log | tail -5
```

**4c. Replace the API only.** Migrations 011 and 012 run at start, and both are additive. Clients reconnect within seconds.

```bash
docker compose -p griip-internal-messaging -f compose.yaml up -d --no-deps api
```

**4d. Check:**

```bash
bash scripts/verify.sh
```

`api` must be healthy and `ntfy` healthy.

```bash
curl -s https://msg.griipbuddy.cloud/v1/server-info
```

It must show version `0.3.0`, with no `push-unifiedpush` yet.

```bash
docker compose -p griip-internal-messaging -f compose.yaml exec -T api node dist/admin.js stats
```

It must show `pushEndpoints: 0`, which proves migration 011 ran.

Then:
- run the abuse-control checks of `ABUSE_CONTROLS.md` § Activating it, step 5: the client address with a made-up
  `X-Forwarded-For`, a synthetic new account's limits, and an existing account;
- check that `docker compose … logs --tail 100 api` shows no errors;
- check that LiveKit's container is the same one as before (`docker compose … ps`: its uptime didn't reset).

**Rollback:** set the image tag in `compose.yaml` back to `0.2.0` (or copy `$B/compose.yaml` back), then
`docker compose -p griip-internal-messaging -f compose.yaml up -d --no-deps api`. Migrations 011 and 012 are additive,
and 0.2.0 ignores them, so nothing is restored. `$B` holds the database snapshot if it's ever needed
(`scripts/restore.sh`).

## 5. Turn wake-ups on (approval)

On the VPS, in `/opt/griip-internal-messaging`. The value isn't secret, but `.env` is still never printed:

```bash
cp -p .env $B/env.pre-push
```

```bash
if grep -q '^PUSH_UNIFIEDPUSH_HOSTS=' .env; then sed -i 's/^PUSH_UNIFIEDPUSH_HOSTS=.*/PUSH_UNIFIEDPUSH_HOSTS=ntfy.griipbuddy.cloud/' .env; else printf '\nPUSH_UNIFIEDPUSH_HOSTS=ntfy.griipbuddy.cloud\n' >> .env; fi
```

```bash
grep -c '^PUSH_UNIFIEDPUSH_HOSTS=ntfy.griipbuddy.cloud$' .env
```

It must print `1`.

```bash
bash scripts/validate-env.sh
```

It must report the push host and that it matches `ntfy/server.yml`.

```bash
docker compose -p griip-internal-messaging -f compose.yaml up -d --no-deps api
```

**Check:**

```bash
curl -s https://msg.griipbuddy.cloud/v1/server-info
```

`features` must include `push-unifiedpush`, and `push.unifiedPushHosts` must be `["ntfy.griipbuddy.cloud"]`.

```bash
bash scripts/verify.sh
```

Its "push wake-ups" part must show `push-unifiedpush`, and the `api` container reaching
`https://ntfy.griipbuddy.cloud/v1/health` with HTTP 200.

**Rollback:**

```bash
sed -i 's/^PUSH_UNIFIEDPUSH_HOSTS=.*/PUSH_UNIFIEDPUSH_HOSTS=/' .env
```

```bash
docker compose -p griip-internal-messaging -f compose.yaml up -d --no-deps api
```

The apps then show "Your server doesn't offer wake-ups" and keep "Stay connected". Stored endpoints stay unused.

## 6. A phone (the owner's, or a test phone)

1. Install ntfy on the phone (Play, F-Droid, or the APK from ntfy's GitHub releases on phones without Play).
2. In ntfy, set *Settings → Default server* to `https://ntfy.griipbuddy.cloud`. Do this **before** turning wake-ups on
   in Meldiq. Let ntfy run unrestricted in the background (MagicOS: *Settings → Battery → App launch → ntfy → Manage
   manually*, all on).
3. In a Meldiq build with this code: *Settings → Wake-ups through UnifiedPush* on. The status must read "On, through
   ntfy.griipbuddy.cloud".
4. On the VPS, `admin.js stats` must show `pushEndpoints` 1 or more, and `pushEndpointsFailing` 0.
5. Turn *Stay connected* off, put Meldiq in the background, and send the phone a message from the test bot. It's
   notified within about 5 s. Then run the rest of `BACKGROUND_DELIVERY.md` § Verification.
6. Failed wake-ups show in the API log as a device id and a reason code (for example `http_507`):

   ```bash
   docker compose -p griip-internal-messaging -f compose.yaml logs --since 1h api | grep -i push
   ```

## Full rollback

Undo in reverse order:
1. step 5: empty `PUSH_UNIFIEDPUSH_HOSTS`, recreate `api`;
2. step 4: image 0.2.0, if the new server itself is the problem;
3. step 3: remove the site and certificate;
4. step 2: remove the `ntfy` container and its volumes, and restore the files from `$B`;
5. step 1: delete the DNS record.

Phones fall back to "Stay connected". Each phone's ntfy app can be pointed back to `ntfy.sh` or uninstalled.

## Limits worth knowing

- **ntfy holds a wake-up for 12 h** for a phone that's offline. Meldiq's queue keeps the message itself for 7 days, and
  the app fetches it whenever it connects.
- **Rate-limit defaults apply per visitor address:**
  - 30 subscriptions;
  - a burst of 60 requests, then one every 5 s.

  Each phone holds one subscription to this server, so more than about 30 phones behind one office NAT would need
  `visitor-subscription-limit` raised in `ntfy/server.yml`, then `up -d --no-deps ntfy`.
- **After an ntfy restart,** wake-ups answer 507 until each phone's ntfy app resubscribes, which takes seconds to
  minutes. Meldiq's next message or ring sends another.
- **Updating ntfy:** change the tag and digest in `compose.yaml` (`docker buildx imagetools inspect
  binwiederhier/ntfy:<tag>` shows the digest) and read the release notes for database schema changes. v2.31.1 upgraded
  the cache schema to 17, so an older ntfy would need the step in its notes.
