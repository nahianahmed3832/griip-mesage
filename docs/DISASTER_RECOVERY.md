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
