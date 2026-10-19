# Meldiq infrastructure migration plan (2026-10-19)

**Status: plan only.** Nothing in production has changed: no DNS, proxy, container, AWS, database or provider-console
change was made. Every step below waits for (a) the purchased Meldiq domain, (b) the physical-device test of the
Meldiq build, and (c) the owner's explicit approval of that step. The repository side of the rebrand is done
([rebrand notes](MELDIQ_REBRAND_2026-10-19.md)); addresses are configured in the places listed in
[ENVIRONMENTS.md](ENVIRONMENTS.md).

Below, `<domain>` is the domain the owner buys through Hostinger (nothing assumes its name). Suggested hostnames, to
confirm then: `api.<domain>` (REST, WebSocket, sign-in callbacks, webhooks), `rtc.<domain>` (LiveKit signalling),
`ntfy.<domain>` (push wake-ups), and `<domain>` itself for mail (SES identity) and any web pages.

## 1. What needs the domain or the owner

| Step | Needs the domain | Needs approval |
|---|---|---|
| DNS records for the new names (§2) | yes | yes |
| TLS certificates and nginx sites for them (§3) | yes | yes (host change) |
| Server `.env` switch: `PUBLIC_API_URL`, `LIVEKIT_PUBLIC_URL`, push hosts, mail sender (§4) | yes | yes (production restart) |
| Meldiq release build with the new production address; new Play listing `com.meldiq.android` (§5) | yes | yes |
| Deploy API 0.6.0 (roles, integrations, Meldiq names) | no | yes, after the phone test |
| Docker project rename (§6) | no | yes (downtime) |
| AWS: new SES identity for `<domain>`; tag updates; optional new bucket (§7) | SES only | yes, each |
| MongoDB production database (§8) | no | yes (Atlas URI, backups) |
| OAuth app registrations at each provider (§9) | yes (callback URLs) | yes, and the owner's provider accounts |

## 2. DNS (Hostinger DNS for `<domain>`)

1. Lower the TTL of nothing yet (new names have no old value); use 300 s for the first records.
2. `A api.<domain> → 72.60.107.88`, `A rtc.<domain> → 72.60.107.88`, `A ntfy.<domain> → 72.60.107.88`. (No AAAA: the
   VPS listens on IPv4 for these services today.)
3. Mail (with the SES stack, §7): three DKIM CNAMEs from SES, `MAIL FROM` subdomain (`bounce.<domain>`: MX to SES's
   feedback host and SPF TXT `v=spf1 include:amazonses.com ~all`), and `_dmarc` TXT (`v=DMARC1; p=none` first).
4. Optional: CAA `0 issue "letsencrypt.org"`.
5. The `griipbuddy.cloud` names stay as they are during and after the switch (old apps, ntfy subscriptions).

## 3. TLS and reverse proxy (host nginx on the VPS)

- Certificates: certbot webroot, one per name (`meldiq-api`, `meldiq-rtc`, `meldiq-ntfy`), like the existing
  `griip-*` ones.
- New site files beside the old ones, proxying to the **same loopback ports**, so both names serve the same
  containers during the transition: `api.<domain>` → `127.0.0.1:18080` (with WebSocket upgrade for `/v1/ws`,
  `client_max_body_size` as today, request buffering off for uploads); `rtc.<domain>` → `127.0.0.1:17880` (WebSocket);
  `ntfy.<domain>` → `127.0.0.1:18081` (copy of `deploy/nginx/griip-ntfy.conf` with the new name).
- `nginx -t`, then reload. Rollback: remove the new site symlinks and reload (the old names were never touched).
- LiveKit media ports (7881/tcp, 7882/udp) and the Hostinger firewall don't change.

## 4. Server configuration at the switch

Edit `/opt/griip-internal-messaging/.env` (backup first), then `docker compose -p griip-internal-messaging … up -d
--no-deps api` (never touch LiveKit without need):
- `PUBLIC_API_URL=https://api.<domain>`; `INTEGRATIONS_PUBLIC_URL` only if sign-ins should use another name.
- `LIVEKIT_PUBLIC_URL=wss://rtc.<domain>` (new call tokens carry it; calls in progress keep their address).
- `PUSH_UNIFIEDPUSH_HOSTS=ntfy.griipbuddy.cloud,ntfy.<domain>` (both while phones move over), and ntfy's `base-url` in
  `deploy/ntfy/server.yml` only when the old name is retired (phones subscribed through the old name keep working).
- `MAIL_FROM="Meldiq <no-reply@<domain>>"` after the SES identity for `<domain>` is verified (until then the
  verified `griipbuddy.cloud` sender keeps working).
- `INTEGRATIONS_APP_RETURN_URL` stays `meldiq://integrations` (Meldiq builds); Orbit builds don't use integrations.
- Verify: `curl https://api.<domain>/healthz`, `deploy/scripts/verify.sh`, a sign-in and a call from the Meldiq build.
- Rollback: restore the `.env` backup and restart the API container.

## 5. Android

- Release builds: `meldiq.apiBaseUrl.production=https://api.<domain>` (Gradle refuses non-https), then build, sign
  with the upload key and upload to a **new** Play Console app with package `com.meldiq.android` (the Orbit listing,
  `com.griipbuddy.Orbit`, can't change its package). Play internal testing first.
- Existing Orbit installs keep working against the same server (same API, same encryption), so people can move at
  their pace: install Meldiq, sign in (email and password, then the recovery key or device approval from the Orbit
  phone). Nothing on the phone moves between the two apps; server-side history and workspaces come back after sign-in.
- Optional follow-up: Android App Links (`https://api.<domain>/…` with `/.well-known/assetlinks.json`) for the sign-in
  return instead of the custom `meldiq://` scheme.

## 6. Docker (Compose project on the shared VPS)

Today: project `griip-internal-messaging` in `/opt/griip-internal-messaging`; services `api`, `livekit`, `ntfy`;
volumes `griip-internal-messaging_api-data` (SQLite database and local files), `…_ntfy-cache`, `…_ntfy-auth`; image
`griip-internal-messaging-api:<version>`. The deploy, backup and restore scripts use that project name and directory.

Renaming isn't free: Compose prefixes volumes, networks and containers with the project name, so a renamed project
started from today's file would create **new, empty volumes**. If the owner wants Meldiq names:
1. Backup (`deploy/scripts/backup.sh`), and confirm the backup restores (`restore.sh` on a copy).
2. In `compose.yaml`: `name: meldiq`; image `meldiq-api:<version>`; pin the existing volumes with
   `volumes: { api-data: { name: griip-internal-messaging_api-data }, ntfy-cache: { name: griip-internal-messaging_ntfy-cache }, ntfy-auth: { name: griip-internal-messaging_ntfy-auth } }`
   so the data stays where it is (renaming the volumes themselves means a copy and isn't needed).
3. Maintenance window: `docker compose -p griip-internal-messaging down` (no `-v`), then
   `docker compose -p meldiq up -d`. Ports and host nginx are unchanged (loopback ports), so the proxy needs no edit.
4. Update `deploy/scripts/*` (`PROJECT`, `DIR` if the directory moves) and the docs in the same change.
5. Rollback: `docker compose -p meldiq down` (no `-v`), `docker compose -p griip-internal-messaging up -d`.
Never `docker system prune`, `volume rm` or anything global: the VPS hosts about 17 other projects.

## 7. AWS (account 914016532802, ap-southeast-1)

Live stacks (read-only check, 2026-10-19): `orbit-ops-admin`, `orbit-storage-prod`, `orbit-storage-test`,
`orbit-mail-prod`. (`orbit-backups.yaml` is a template, not deployed.)

| Resource | Current name | Can it be renamed in place? | Recommendation |
|---|---|---|---|
| CloudFormation stacks | `orbit-*` | no (stack names are permanent) | Keep; new resources go into `meldiq-*` stacks |
| Media bucket | `griipbuddy-orbit-media-sg` (+ `-test`), key prefix `orbit/` | no (bucket names are global and permanent) | **Keep.** Objects are end-to-end encrypted and private; the name isn't visible to people. A copy only if the owner wants it (below) |
| Runtime IAM users | `orbit-api-prod-ap-southeast-1`, `orbit-api-test-ap-southeast-1`, `orbit-api-mail-prod-ap-southeast-1` | no (`UserName` change = replacement, new keys) | Keep, or create `meldiq-*` users beside them and switch keys later in a planned key rotation |
| Managed policies | `orbit-runtime-boundary`, `orbit-ops-admin`, inline `orbit-media-access`, `orbit-mail-send` | no (named policies are replaced, which fails while attached) | Keep. Never edit a managed policy's `Description` (forces replacement) |
| Ops user / CLI profile | `orbit-ops-admin` / profile `orbit-admin` | no | Keep |
| SES | identity `griipbuddy.cloud`, configuration set `orbit-mail-prod` | identity is per domain | Add a `meldiq-mail-prod` stack for `<domain>` (DKIM, MAIL FROM, sender user limited to `no-reply@<domain>`) |
| Tags | `app=orbit` | yes (in-place tag update) | Add `app=meldiq` (keep `app=orbit` until cost reports use the new tag) |
| Budget | `orbit-monthly-10usd` | no (name) | Keep, or create `meldiq-monthly` and delete the old one after |

If a new bucket is wanted after all (for example `griipbuddy-meldiq-media-sg`): create it with the same template
settings (block public access, SSE-S3, versioning as today, lifecycle for the 7-day relay window and kept files, TLS-only
policy); give the runtime user access to both; copy objects with `aws s3 sync` (or S3 Batch Operations) keeping
metadata; verify counts, sizes and checksums; switch `S3_BUCKET` with `STORAGE_READ_FALLBACK` covering the old bucket;
keep the old bucket untouched until the owner approves its retirement. Rollback at any point: switch `S3_BUCKET` back.

Credentials: nothing was rotated, deleted or exposed by the rebrand. Existing items to handle on their own schedule:
the root access key (profile `griipbuddy`) still exists and should be removed (docs/SECURITY_REMEDIATION.md); runtime
keys live only in the VPS `.env`.

## 8. MongoDB

- Production doesn't use MongoDB yet (no `MONGODB_URI` on the VPS); its data is in SQLite in the `api-data` volume and
  is untouched.
- The server's default database name is now `meldiq` (was `orbit`). Create the production database as `meldiq` when
  the Atlas cluster is set up (docs/MONGODB.md). Collection names don't change, `orbit_meta` included.
- A development or staging database created earlier as `orbit` keeps working with `MONGODB_DB=orbit`; renaming one is
  a copy (`mongodump --db orbit` / `mongorestore --nsFrom 'orbit.*' --nsTo 'meldiq.*'`) on a non-production copy first,
  verified by per-collection document counts and `admin.js mongo-verify <domain>` with `MONGODB_DB=meldiq` (which
  compares the copy against the SQLite side), the old one kept until approved.

## 9. OAuth providers and webhooks

No provider is registered today (no credentials exist), so no live customer connection can break: every provider app
shows "Needs configuration". When registering, use the Meldiq name and these addresses:

| Provider | Existing callback | Callback to register | Webhook | Console work | Verification |
|---|---|---|---|---|---|
| Google (Drive, Calendar) | none | `https://api.<domain>/v1/integrations/oauth/callback` | — | OAuth client (web), consent screen "Meldiq", scopes per catalog | restricted `drive.metadata.readonly` needs Google's verification and a security assessment for public use |
| Microsoft (OneDrive, SharePoint, Outlook Calendar) | none | same | — | Entra app registration (web), delegated scopes | publisher verification for multi-tenant |
| GitHub | none | same | `https://api.<domain>/v1/integrations/webhooks/github` | GitHub App "Meldiq" (callback, webhook, secret; read-only permissions) | none for private use |
| Jira (Atlassian) | none | same | — | OAuth 2.0 (3LO) app | distribution settings to share outside the owner's site |
| Notion | none | same | — | public integration, read content | Notion review for public listing |
| Linear | none | same | — | OAuth app | none for private use |
| Incoming webhooks | — | — | `https://api.<domain>/v1/integrations/hooks/<id>` (shown per webhook) | none | none |

Client ids can be kept if a provider app is ever registered under the old host: add the new callback beside the old,
switch, then remove the old one. A live test needs the owner's provider accounts and these registrations; none was
possible in this phase.

## 10. Order of work after approval

1. Physical-device test of the Meldiq test build (local test backend).
2. Owner buys the domain → DNS (§2), TLS and nginx (§3); both names serve the same API.
3. Deploy API 0.6.0 with the old `.env` (backup first, `docker compose … up -d --no-deps api`), verify.
4. Switch `.env` addresses (§4), verify, keep old names alive.
5. Meldiq release build for production (§5), Play internal testing on a physical phone.
6. Optional, each separately approved: SES for `<domain>`, Docker project rename (§6), MongoDB production (§8), provider
   registrations (§9), AWS tags or a new bucket (§7).
