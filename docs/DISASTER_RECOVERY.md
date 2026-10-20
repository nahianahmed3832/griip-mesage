# Disaster recovery (2026-10-20)

How Meldiq recovers from failures with the infrastructure it has today: one Hostinger VPS running the API (SQLite),
LiveKit, ntfy and nginx; encrypted attachments in AWS S3; account email over Hostinger SMTP. The objectives below are
what this setup can honestly reach, not targets it has been measured against under load.

## 1. Current state (checked 2026-10-20, read-only)

| What | State |
|---|---|
| Scheduled server backups | **None.** No timer or cron job for Meldiq on the VPS |
| Off-site backups | **Not set up.** `deploy/scripts/offsite-backup.sh` (age-encrypted, object-locked S3) exists and was rehearsed locally, but there is no backup bucket (`deploy/aws/orbit-backups.yaml` never deployed), no `/root/.orbit-offsite.env`, and no owner age key on the server |
| Local snapshots | Manual, before each deploy: `backups/pre-<version>-<time>/` on the VPS (same disk as the live data) |
| Rollback images | `griip-internal-messaging-api:<previous>-rollback` kept per deploy |
| Attachments | S3 `griipbuddy-orbit-media-sg` (versioning per `orbit-storage.yaml`), encrypted end to end; useless without the clients' keys |
| Restore drill | 2026-10-20 on non-production data: snapshot of the local test server › `verify-backup` (integrity ok, schema 21) › restored into a fresh folder › row counts identical › a second API served it (`/healthz` ok). Production has not been drilled |

**Recovery point today:** the last manual pre-deploy snapshot (a day or more). If the VPS disk is lost, everything
since the last snapshot is lost, and the snapshots with it. This is the most important gap.

## 2. Closing the gap (needs the owner)

1. The owner creates an age key pair on their own computer (`age-keygen -o meldiq-backup.key`), keeps the private key
   offline, and gives the public key (`age1…`).
2. Deploy `deploy/aws/orbit-backups.yaml` (one S3 bucket with object lock, a write-only IAM key limited to the VPS
   address). Cost: S3 storage for compressed, encrypted snapshots, a few cents a month at today's size.
3. Put the bucket, prefix, public key and write-only key in `/root/.orbit-offsite.env` (mode 600).
4. Install a daily systemd timer running `scripts/offsite-backup.sh`, plus a weekly `offsite-restore-check.sh`
   (lists the newest backup and checks its manifest; decryption needs the owner's key and happens off the server).
5. Run the first backup by hand, then a full restore drill on a separate machine with the owner's key.

When MongoDB holds domains (accounts, preferences, integrations), `backup.sh` covers SQLite only: add a `mongodump`
of the Meldiq database to the same archive before moving production to MongoDB.

## 3. Scenarios

| Failure | Detect | Recover | Realistic objective today |
|---|---|---|---|
| Bad deploy | `scripts/verify.sh`, `/healthz`, the platform health view | `docker compose … up -d --no-deps api` with the `-rollback` image; restore the pre-deploy snapshot only if a migration broke data (`scripts/restore.sh`) | minutes |
| API process crash | Compose restarts it (`restart: unless-stopped`); health view shows a fresh uptime | automatic; check the logs for the cause | under a minute |
| Background job failing | health view: job's last run failed, warning listed | logs by job name; jobs retry on their next tick | next tick |
| Database corruption | `PRAGMA integrity_check` (`verify-backup` on a fresh snapshot), API errors | stop the API, restore the newest good snapshot, start; data since that snapshot is lost | depends on the snapshot age (see §1) |
| VPS lost | everything down | new VPS, Docker, `deploy/scripts` bundle, restore the newest snapshot (today: none off the VPS), DNS to the new address, new TLS certificates | hours, **with data loss up to the last off-site backup; today there is none** |
| S3 unreachable | health view: storage unreachable; uploads and downloads fail | wait for AWS; messages keep flowing, files queue on the clients | AWS's recovery |
| Push provider down (ntfy, FCM) | health view: push failing | messages still arrive when the app is open or the stay-connected service runs | provider's recovery |
| Email provider down | health view: mail failing | sign-up codes and resets wait; `MAIL_TRANSPORT` can be switched back to SES (`.env` backup) | minutes after the switch |
| Connector outage | health view: app jobs dead or failing | jobs retry with backoff; dead jobs stay listed | provider's recovery |
| Media server overloaded | calls fail to connect; the call screen's connection details | one SFU on 2 shared vCPUs: no failover; capacity needs a bigger or dedicated server | none without new capacity |

## 4. Restore drill, round 1 candidate (2026-10-20, synthetic data, owner's Mac)

A second drill, on the remediation round 1 candidate (`claude/remediation-round-1`). It covers the whole off-site path
and the new migration 013. It was isolated: a throwaway server in a temporary folder in the session scratchpad, with
made-up accounts only, deleted afterwards along with its throwaway `age` key. Docker isn't installed here, so the
container steps of `backup.sh` and `restore.sh` ran as the same `node dist/admin.js` commands.

| Step | What ran | Result |
|---|---|---|
| Seed | Test-harness server: 3 users, a DM with 5 messages (recipient offline, so they were queued), a group, a workspace with a channel, a 4 KB attachment | done |
| Snapshot | `admin.js backup` on the **running** server, then `verify-backup` and `table-counts` | `ok: true`, integrity `ok`, schema 28, 3 users |
| Archive | `tar -czf <name>.tar.gz backups/<name>.sqlite attachments` (as `backup.sh`) | 35,867 bytes |
| Off-site encrypt | `scripts/offsite-backup.sh --local … --label drill` with `ORBIT_BACKUP_ARCHIVE` and a throwaway age recipient | `.tar.gz.age` and manifest written |
| Off-site restore check | `scripts/offsite-restore-check.sh <age> <manifest> <key>` | sha256 matches the manifest, `verify-backup` ok, **row counts match the manifest (83 tables)**, "restore check passed" |
| Restore and boot (same version) | Snapshot as `griip.sqlite` in a fresh data folder, attachments copied, `node dist/index.js` | `/healthz` ok (0.8.1); 28 migrations; 3 users, 7 queued items, 1 attachment row and its file |
| Restore and boot (upgrade from 0.8.1) | The same, from a copy changed to look like a 0.8.1 database (`users.deleted_at` dropped, migration 13 unrecorded) | `/healthz` ok; migration 013 applied at start; `users.deleted_at` present; data intact |

Not covered:
- Docker and `restore.sh` themselves.
- S3-hosted attachments.
- A real upload to, and download from, a backup bucket.
- A production-sized database.
- Rolling back to 0.8.1 on a database that has 013, and timing.

**Rollback after 013.** Migration 013 adds one nullable column. 0.8.1 reads `users` with `SELECT *` and inserts with
explicit columns, and `verify-backup` compares the highest version (still 28), so rolling back to the `0.8.1-rollback`
image after deploying round 1 is expected to work. That rollback wasn't tested.

**Secrets.** `.env` and `livekit/livekit.yaml` aren't in any archive (on purpose). Their only copies are on the VPS
(`env.backup` next to each pre-deploy snapshot). They hold:
- the S3 runtime key;
- `INTEGRATIONS_TOKEN_KEY`;
- SMTP;
- LiveKit;
- the FCM service account.

Rebuilding after losing the VPS needs a copy kept off it, in the owner's password manager or AWS Secrets Manager.
That's an owner decision, alongside §2.
