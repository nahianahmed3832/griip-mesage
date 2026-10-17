# Orbit production storage on AWS: deployment report (2026-10-17)

**Status: production stores new files in Amazon S3 since 2026-10-17 10:22 UTC. Messages, accounts and every record
stay in SQLite on the Hostinger VPS.** It was set up in two phases:

1. The API was updated to 0.2.0 with files still on the disk.
2. The API was switched to S3, keeping the disk as a read fallback.

Each phase was checked before the next. The plan, costs and design are in docs/AWS_STORAGE_PLAN.md.

## 1. AWS resources (Singapore, `ap-southeast-1`)

| Resource | What it is | Created by |
|---|---|---|
| Stack `orbit-ops-admin` | IAM user `orbit-ops-admin` and managed policies `orbit-ops-admin` and `orbit-runtime-boundary` (deploy/aws/orbit-ops-admin.yaml) | the root user, once, to bootstrap |
| Stack `orbit-storage-prod` | bucket **`griipbuddy-orbit-media-sg`** and runtime user **`orbit-api-prod-ap-southeast-1`** (deploy/aws/orbit-storage.yaml, `Environment=prod`) | `orbit-ops-admin` |
| Stack `orbit-storage-test` | bucket `griipbuddy-orbit-media-sg-test` and user `orbit-api-test-ap-southeast-1`, kept for automated tests | the root user (earlier, approved) |
| Budget `orbit-monthly-10usd` | emails at 80% of actual spend or 100% of forecast of $10 a month; covers the whole account | the root user (earlier, approved) |

### The production bucket

All checked after creation:

- **Public access:** all four public-access blocks on, the bucket policy isn't public, and anonymous or plain-HTTP
  requests get 403.
- **Encryption at rest:** SSE-S3 (AES256). Every attachment is also ciphertext from the phone (AES-256-GCM, keys only
  in the end-to-end encrypted message), so S3 never holds a readable attachment.
- **Ownership and versioning:** bucket-owner object ownership; a deleted or replaced object stays recoverable for 30
  days.
- **Lifecycle:**
  - incomplete multipart uploads are aborted after 1 day;
  - old versions expire after 30 days, and expired delete markers are removed;
  - **no rule ever expires a current object.** Only the server deletes files, when its records say they've expired
    (7 days, or 365 days after the last request to keep them for history).

### Identities and permissions (no credentials here)

- **`orbit-api-prod-ap-southeast-1` (runtime, used by the API on the VPS):**
  - put, get and abort-multipart objects, and list uploaded parts, only under `orbit/` in this bucket;
  - delete objects and list the prefix **only from the VPS's address 72.60.107.88**.

  It carries the permissions boundary `orbit-runtime-boundary` (objects in `griipbuddy-orbit-*` buckets only). Its one
  access key went from AWS straight into `/opt/griip-internal-messaging/.env` (root-only, 600) through a pipe. It was
  never displayed and isn't in Git or chat. Rotate every 90 days (§6). It's separate from the test user and its key.
- **`orbit-ops-admin` (for operators, from this Mac, profile `orbit-admin`):**
  - CloudFormation on `orbit-storage-*` stacks;
  - S3 on `griipbuddy-orbit-*` buckets;
  - Orbit runtime users, only with the boundary attached;
  - `orbit-*` budgets, cost read-outs, and its own key rotation.

  Checked: it's refused when listing IAM users, on other buckets, when creating other users, and when creating a
  runtime user without the boundary. It replaces root for everything Orbit needs.
- **The root user** isn't used by the application. It was used once, to create `orbit-ops-admin` (§7).

## 2. Hostinger deployment

- **Service updated:** only `api` of the Compose project `griip-internal-messaging` (`/opt/griip-internal-messaging`).
  - Image: from `griip-internal-messaging-api:0.1.0` to **`griip-internal-messaging-api:0.2.0`**, built on the VPS from
    commit `510bc0f` (plus the version-string fix, §4).
  - **LiveKit was not restarted or changed** (same container before and after both switches).
  - Data volume `griip-internal-messaging_api-data`: unchanged and not deleted.
- **What production ran before:** a source snapshot from just before the first Git commit (2026-10-16), with only
  migration 001. The new image adds migrations 002–010, which only add tables and columns. They were rehearsed first
  on a copy of the production database: integrity ok, and every user, conversation, queued message and attachment
  kept.
- **`.env` changes**, values not shown:
  - `REGISTRATION_MODE=open`, explicitly, as before;
  - `STORAGE_BACKEND=s3`;
  - `STORAGE_READ_FALLBACK=local`;
  - `S3_BUCKET`, `S3_REGION`, `S3_PREFIX`;
  - the runtime key.

  `ATTACHMENT_MAX_BYTES` stays 25 MB.
- **Compose file:** the repository's compose file now puts the process limits under `deploy.resources.limits.pids`.
  Compose 5 refused the earlier `pids_limit` next to deploy limits. LiveKit's new limits (1.5 CPUs, 512 processes)
  apply the next time LiveKit is recreated, not now.

## 3. Where data lives now

| Data | Where |
|---|---|
| Accounts, devices, sessions, profiles, privacy settings | SQLite on the Hostinger VPS |
| Conversations, members, workspaces, roles | SQLite on the VPS |
| Messages waiting for delivery (ciphertext, at most 7 days) | SQLite on the VPS |
| Encrypted message history (new in 0.2.0) | SQLite on the VPS |
| Attachment records (who, which conversation, size, expiry) | SQLite on the VPS |
| **New attachment bytes** (photos, videos, voice messages, files; ciphertext) | **S3 `griipbuddy-orbit-media-sg`, `orbit/attachments/<random id>`** |
| **New profile photos** | **S3, `orbit/profile-photos/<user>-<revision>`** |
| Attachments stored before the switch | the VPS disk, still served until they expire within 7 days |
| Call media | not stored (relayed by LiveKit) |
| Backups | the VPS, `/opt/griip-internal-messaging/backups/` (root-only) |

**Messages and account records are not in AWS.** Moving the database would be a separate, validated migration (Amazon
RDS proposal in docs/AWS_STORAGE_PLAN.md).

## 4. Verification

Synthetic data and dedicated test accounts only. No employee's messages or files were opened.

**Phase 1** (0.2.0 on the disk, before S3):

- Health and `server-info` answer, publicly and on the VPS. LiveKit signalling answers.
- An account created before the deploy (test bot `griipboteeb7`) received a new test account's queued text and voice
  message. The hash was ok and it decrypted.
- An end-to-end encrypted voice call between the two connected, with audio both ways.

**Phase 2: the production bucket from the VPS.** `storage-check` with the runtime key: listing works, which is only
allowed from the VPS address. A file stored on the disk before the switch is still served through the fallback,
streamed from the disk.

**Phase 2: `test/acceptance/storage_acceptance.ts` against `https://msg.griipbuddy.cloud`.** It used three throwaway
accounts, disabled afterwards.

| Check | Result |
|---|---|
| Server offers direct uploads (limit 25 MB) | pass |
| Photo (400 KB), voice (30 KB), file (1 MB) and video (24 MB) go straight to S3, encrypted at rest, and are recorded by the server | pass |
| A message carrying them reaches the recipient, who downloads (302 to a 5-minute link) and decrypts every file | pass |
| A range of a file (resumed download) | pass |
| Someone outside the conversation gets 404, no link, and can't start an upload there | pass |
| Anonymous, plain-HTTP, edited (other object) and tampered (signature) requests to S3 | 403 each |
| S3 refuses other bytes (`BadDigest`), another size, a missing no-overwrite condition, and an overwrite (412) | pass |
| An upload cut off mid-way: the server refuses to complete it early (409); the retry completes intact | pass |
| A cancelled upload can't be completed | pass |
| Files over the limit are refused before any upload (413) | pass |
| Older apps' upload through the server lands in S3 | pass |
| Profile photo stored and served through the server | pass |
| An expired download link (after 5 minutes) | 403 |
| **Phase total** | **16/16** |
| *API restarted (recreated on the rebuilt 0.2.0 image)* | |
| After the restart: all 7 files download intact, the photo, outsiders still 404 | **10/10** |

Also checked:

- In the bucket, as `orbit-ops-admin`: each test object has the right size and SSE AES256.
- In the database: all 7 attachment records persisted, and no upload session was left open.

**After the switch:**

- The two test bots exchanged a voice message: it was uploaded into S3, downloaded through the redirect, the hash was
  ok, and it decrypted.
- An end-to-end encrypted **video** call connected, with audio and video both ways.

**The app itself against production:** the Android emulator, with the APK below and a new test account
`s3emucheck1017`.

- A voice message from the test bot downloaded from S3 and played in full (3.2 s).
- A JPEG photo and an MP4 video were sent by the app's direct path: `uploads` → PUT to S3 → `complete` → `keep`.
  - In S3: 52,982 and 485,522 bytes, SSE AES256, with SHA-256 checksums.
  - The bot downloaded both, the hashes matched, and they decrypted.

**Log hygiene:** the production `api` log has no `X-Amz-Signature`, `X-Amz-Credential`, `AKIA`, `amazonaws.com` or
secret.

**Automated suites** at commit `570ad81`:

| Suite | Result |
|---|---|
| Server tests (vitest) | 210 passed; the 9 live tests run only on request (9/9 earlier against the test bucket) |
| Typecheck, build, `npm audit` (production) | pass, pass, 0 vulnerabilities |
| Android unit tests | 268/268 (56 classes) |
| Android lint | 0 errors (79 warnings, unchanged) |
| `assembleDebug` (side-by-side) / `assembleRelease` (R8) | pass / pass |

**APK for testing:** `artifacts/Orbit-0.1.4-beta-S3-production-test-2026-10-17.apk`

- package `com.griipbuddy.Orbit.test` ("Orbit Test"), version 0.1.4-beta (5), a debug build;
- installs beside the Play app without touching it, and uses `https://msg.griipbuddy.cloud`;
- SHA-256 `1f3e651f33df3f51802162dd8335b23fb5b16b038e5b1e45a8635ff27c30307e`, 141,823,981 bytes;
- built from commit `570ad81` (the Android sources are identical at `510bc0f`).

**Physical phone: not tested by me.** The phone wasn't connected over USB during this work (`adb devices -l` was
empty throughout), so nothing above is a physical-device result. The owner is testing the APK on their phone, away
from this Mac, from a 24-hour download link.

## 4a. Costs

List prices, Singapore (docs/AWS_STORAGE_PLAN.md §4):

- an internal beta of about 50 people is about **$1 a month** (storage $0.025/GB-month, requests, data out within the
  account's free 100 GB);
- about 500 people is about $95, mostly data out.

Today's test data is about 30 MB. Budget `orbit-monthly-10usd` is active and covers the whole account.

## 5. Rollback

**Backup** taken before any change, in `/opt/griip-internal-messaging/backups/pre-0.2.0-20261017T101554Z/` (root-only,
with `SHA256SUMS`):

- `griip.sqlite`: an online snapshot, integrity ok;
- `api-data.tar.gz`: the whole data volume;
- `env.backup`: `.env` before any change;
- `env.phase1`: `.env` after phase 1, before S3;
- the compose file and LiveKit config;
- `deployment-0.1.0.tar.gz`: the old source;
- `image-api-0.1.0.tar.gz`: the old image, also still tagged `griip-internal-messaging-api:0.1.0` and `…:0.1.0-rollback`.

**A. Stop using S3, keep 0.2.0.** For a storage problem: one `.env` change and a restart of `api` only. Files stored in
S3 meanwhile stay readable.

```
cd /opt/griip-internal-messaging
sed -i 's/^STORAGE_BACKEND=s3$/STORAGE_BACKEND=local/; s/^STORAGE_READ_FALLBACK=local$/STORAGE_READ_FALLBACK=s3/' .env
docker compose -p griip-internal-messaging -f compose.yaml up -d --no-deps api
docker compose -p griip-internal-messaging -f compose.yaml exec -T api node dist/admin.js storage-check
```

**B. Back to 0.1.0.** For a problem with the new server itself. This loses everything written since the snapshot, so
it's a last resort, and it needs a decision.

1. Stop `api`.
2. Keep the current database aside with `scripts/restore.sh`, which never deletes it, or by moving it by hand.
3. Restore `griip.sqlite` from the backup.
4. Restore the old `.env` (`env.backup`) and `deployment-0.1.0.tar.gz`, set the compose image back to `0.1.0`, and
   `up -d --no-deps api`.

The snapshot returns the database to its 0.1.0 state (migration 001 only). 0.1.0 can't read files stored in S3, and
messages, accounts and files from after the snapshot are lost to it.

Nothing in S3 or on the disk is deleted by either rollback.

## 6. Operations

- **Key rotation (every 90 days):**
  1. As `orbit-admin`, create a second key for `orbit-api-prod-ap-southeast-1` and pipe it into the VPS `.env`.
  2. Restart `api`, then run `storage-check`.
  3. Deactivate the old key, and delete it a week later.
- **Health:** `admin.js storage-check` and `admin.js storage-orphans` (a dry run). The budget alarm emails the owner.
- **Logs:** request logs carry paths, not presigned URLs. No signed URL, key id or S3 host name appeared in the
  production log after the acceptance run.

## 7. Root account

- **Current state:** profile `griipbuddy` on this Mac is the account's **root access key** (MFA is on for root
  sign-in). Orbit no longer needs it: `orbit-ops-admin` covers Orbit's stacks.
- **Migration, each step yours to approve:**
  1. Create a general administrator for the console. Either an IAM user with a password, MFA and
     `AdministratorAccess`, or IAM Identity Center, which needs AWS Organizations (free). The password and MFA device
     are yours, so do this in the console.
  2. Sign in as that administrator and check it works.
  3. Point the AWS Agent Toolkit (`aws-mcp`, which uses profile `griipbuddy`) at `orbit-admin` or the new
     administrator.
  4. **Deactivate** the root access key and watch for a week. Then delete it and remove profile `griipbuddy` from
     `~/.aws/credentials`.

  Nothing has been disabled or deleted yet.

## 8. Remaining risks

- **Physical-device testing is still to do** (above). Before inviting more employees:
  - install the APK on a phone;
  - send a photo, video, voice message and file to a second account;
  - check that they arrive and open.
- **Everyone on this server uses S3 now.** The server has one storage setting, so there was no per-person canary. The
  current internal-beta users are the test group. Rollback A (§5) takes one restart.
- **Open registration** (kept as before, by choice): anyone who reaches the server can create an account and upload
  within the quotas. Production uses the defaults: per person 2 GiB uploaded a day and 4 GiB stored, and 10 GiB
  server-wide. They're worth lowering with `ATTACHMENT_USER_*_BYTES` while registration is open.
- **A long-lived runtime key** on the VPS. It's limited by prefix, by address for delete and list, and by the
  boundary. Rotate it every 90 days, or move to IAM Roles Anywhere.
- **The root access key still exists** (§7).
- **Test data left in production**, all synthetic:
  - the files from the checks expire in 7 days;
  - the app's photo and video were kept for history (365 days);
  - test accounts: three `s3chk…` accounts (disabled), `s3emucheck1017` (active, emulator only), and `griipbot6f13`
    (active, a test bot like `griipboteeb7`).
- **The test APK** is also at `downloads/Orbit-Test-0.1.4-beta-2026-10-17.apk` in the *test* bucket, behind a
  24-hour presigned link, for installing away from this Mac. Delete it after testing (as `orbit-admin`).
- **The budget alarm is account-wide**, so other services can trigger it.
- **No S3 access logs or CloudTrail data events** (each costs extra). They're worth adding before a wider rollout, for
  an audit trail of object access.

## 9. Next step before more employees

1. Connect the Android phone and test with two accounts (the emulator steps above, on hardware).
2. Then invite a small named group of employees to the beta, with this rollback ready.
