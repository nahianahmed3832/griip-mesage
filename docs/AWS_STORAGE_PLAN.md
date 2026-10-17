# Connecting the Hostinger backend to AWS storage (2026-10-17)

**Status (2026-10-17): production stores new files in S3.** The bucket is `griipbuddy-orbit-media-sg` in Singapore,
and the API is 0.2.0 on the Hostinger VPS. Messages, accounts and every record stay in SQLite on the VPS. The
deployment, verification, rollback and risks are in **docs/ORBIT_AWS_PRODUCTION_2026-10-17.md**. This plan keeps the
design, costs and test-stack results.

**In GriipBuddy's AWS account (`ap-southeast-1`), all approved:**

- `orbit-storage-prod`: bucket `griipbuddy-orbit-media-sg` and runtime user `orbit-api-prod-ap-southeast-1`, created
  by `orbit-ops-admin`;
- `orbit-ops-admin`: an operations user limited to Orbit, so Orbit needs no root credentials. The root user created
  it once;
- `orbit-storage-test`: bucket `griipbuddy-orbit-media-sg-test` and user `orbit-api-test-ap-southeast-1`, kept for
  automated tests;
- the budget alarm `orbit-monthly-10usd`.

Sections 1, 3, 7 and 8 below describe the state before the deployment, as planned.

## 1. Where data lives today

**Production** (read-only check on 2026-10-17): image `griip-internal-messaging-api:0.1.0`, **only migration 001
applied**, `STORAGE_BACKEND` doesn't exist there yet, data volume 70 MB.

| Data | Production now (0.1.0) | With this code |
|---|---|---|
| Accounts, devices, sessions, profiles, privacy | SQLite on Hostinger (`/data/griip.sqlite`) | same |
| Conversations, members, roles, workspaces | SQLite | same (deleted workspaces: `org_deletions`) |
| Offline delivery queue (encrypted envelopes) | SQLite `delivery_queue`, until acknowledged, max 7 days | same |
| Message history | **only on phones** | phones **plus** encrypted per-account history in SQLite (`history_items`; docs/MESSAGE_HISTORY.md) |
| Attachment records (who, which conversation, size, expiry, kept) | SQLite `attachments` | same, plus open direct uploads in `attachment_uploads` |
| Attachment bytes (photos, videos, voice messages, files: always ciphertext) | `/data/attachments` on Hostinger | `/data/attachments`, **or S3** with `STORAGE_BACKEND=s3` |
| Profile photos (not end-to-end encrypted; shown per privacy setting) | none (no photo support in 0.1.0) | `/data/profile-photos`, **or S3** |
| Federation queues, call records, audit log | SQLite | same |
| Phones | SQLCipher database, keys in the Keystore, decrypted files in app storage | same |

So **S3 only ever holds file bytes** (encrypted attachments and profile photos). Every record (who may read a file, when
it expires, quotas, messages, accounts) stays in SQLite on Hostinger. S3 isn't a database.

## 2. What was built

### Server

- **The storage layer, on the AWS SDK.**
  - `src/storage/s3.ts`: `@aws-sdk/client-s3` and `@aws-sdk/s3-request-presigner` 3.1144.0, pinned exactly, 0 known
    vulnerabilities.
  - `src/storage/blobstore.ts`: the interface and the disk store.
  - Writes never overwrite (`If-None-Match: *`), carry their SHA-256 when known, use multipart above 64 MiB, and
    retry.
  - Errors carry only status and S3 error code.
  - Credentials come from the server's env or the SDK's default chain (so IAM Roles Anywhere works without code changes).
- **Direct uploads from the app** (`src/routes/attachments.ts`, migration `010_direct_uploads.sql`):
  - `POST /v1/attachments/uploads` checks the conversation and quotas, then presigns a PUT. The URL is bound to the
    exact size and SHA-256, can't overwrite, and lasts 15 minutes. Large files are multipart with per-part SHA-256.
  - `POST …/parts` and `GET …/parts` presign parts, and resume.
  - `POST …/url` renews an expired link.
  - `POST …/complete` re-checks membership, verifies the stored object (size, hash, every part's ETag, size and hash),
    then records the attachment atomically.
  - `DELETE …` cancels.
- **Downloads.** After the membership check, a 302 to a 5-minute presigned GET.
- **Cleanup.** Open uploads count against quotas. Expired or cancelled uploads (and those in a deleted workspace) are
  aborted or deleted by the retention job, with retries.
- **Admin commands:** `storage-check` (backend and reachability, no secrets) and `storage-orphans [--delete]`
  (unreferenced objects; a dry run by default).
- **`server-info`** lists `direct-upload` only when S3 is on.
- **Fixed on the way.** Disk-store downloads now end deterministically (explicit end offset). Before, a download
  response could stay open after the client had the whole file; the new tests caught it about 1 run in 4.

### App

- **`AttachmentUploader`.** It uses direct uploads when the server offers them, with checksummed parts, per-part retry,
  resume from what S3 has, link renewal and cancellation on failure. Otherwise it uses the original upload.
- **No Orbit token reaches S3**, and presigned URLs are never logged.

### Infrastructure

`deploy/aws/orbit-storage.yaml` (CloudFormation): the test stack and, since 2026-10-17, the production stack. Created
by `orbit-ops-admin` (`deploy/aws/orbit-ops-admin.yaml`) with the permissions boundary `orbit-runtime-boundary` on the
runtime user.

- **Bucket:**
  - private, with all public access blocked;
  - S3-managed encryption (SSE-S3), HTTPS required;
  - bucket-owner object ownership;
  - versioning, with deleted or replaced objects recoverable for 30 days;
  - incomplete multipart uploads aborted after a day.
- **IAM user `orbit-api-<test|prod>-ap-southeast-1`** with only:
  - `PutObject`, `GetObject`, `AbortMultipartUpload` and `ListMultipartUploadParts` under `orbit/`;
  - `DeleteObject` and a prefix-limited `ListBucket`, **only from the VPS's address** (72.60.107.88).

  Uploads and downloads can't be limited by address, because phones use the presigned URLs. The access key is never a
  stack output.

### Configuration

New settings are in `deploy/.env.example`: `STORAGE_BACKEND`, `S3_*`, `S3_UPLOAD_URL_TTL_SECONDS`, `DIRECT_UPLOAD_*`.

## 3. Different storage locations after enabling S3

- **Attachment bytes and profile photos:** S3 (Singapore).
- **Records, accounts, conversation metadata, delivery queue and encrypted message history:** SQLite on Hostinger.
- **Files already on the disk** stay there until they expire or are replaced. There's no migration of existing files;
  production has 70 MB and no photos.

## 4. AWS setup and estimated cost

List prices from the AWS Pricing API, Singapore:

- S3 Standard: $0.025 per GB-month;
- uploads and listings: $0.005 per 1,000;
- downloads and checks: $0.0004 per 1,000;
- data out to the internet: $0.12 per GB after the account's free 100 GB a month.

| Scenario (per month) | Storage | Requests | Data out | Total |
|---|---|---|---|---|
| Internal beta: ~50 people, 20 GB stored, 25k uploads, 120k downloads, 50 GB downloaded | $0.50 | ~$0.17 | $0 within the free 100 GB | **≈ $1** |
| 500 people: 300 GB stored, 360k uploads, 2.3M downloads, 800 GB downloaded | $7.50 | ~$2.70 | ~$84 | **≈ $95** |

Data out dominates. It's charged whether phones download from S3 or the server streams it, because S3 to Hostinger is
internet traffic too. Uploads into S3 are free. Versioning adds at most 30 days of deleted files.

**Budget alarm.** `orbit-monthly-10usd`, created 2026-10-17 (budgets are free for the first two). It emails the
account owner when actual spend reaches 80% of $10 a month, or the forecast reaches 100%. It covers the **whole
account**, not only Orbit, so the other buckets count towards it as well.

## 5. Permissions and authentication for Hostinger

| | Dedicated IAM user (recommended to start) | IAM Roles Anywhere |
|---|---|---|
| What the server holds | a long-lived access key in `/opt/griip-internal-messaging/.env` (root-only, 600) | an X.509 certificate and private key, exchanged for 1-hour credentials by `aws_signing_helper` (`credential_process`) |
| If the VPS is compromised | the key works until rotated or deleted | the certificate works until revoked (CRL) or expired; stolen session credentials last at most an hour |
| Setup | the stack above, plus one access key | a CA (self-managed with OpenSSL: free; AWS Private CA: costs extra), a trust anchor, a profile, a role, the helper binary in the image, and certificate renewal |
| Presigned URLs | valid for their 15 / 5 minutes | valid for at most the remaining session (≤ 1 h): fine for these lifetimes |
| Code changes | none (`S3_ACCESS_KEY_ID`) | none (SDK default chain; mount the config and certificate) |

**Recommendation.** Start with the dedicated IAM user:

- least privilege;
- delete and list only from the VPS address;
- the key rotated every 90 days;
- **never the root user** for the application.

Move to Roles Anywhere with a self-managed CA once it runs well.

**Getting the key onto the server securely.** Create it with the AWS CLI on this Mac (as an administrator, not in chat)
and pipe it over SSH straight into the VPS `.env`. It's never printed, never in chat, never in Git.

**Your AWS account itself:**

- **Root access key.** The root user has a **long-lived access key**. Delete it (IAM › Security credentials), since
  nothing should use root keys.
- **Daily sign-in.** Use an administrator through IAM Identity Center instead of root. Root MFA is already on.
- **Existing resources.** Two other buckets (`gadgetglitz`, `griipbuddy1`) and IAM users (`griipbuddy_user`, `nahid66`)
  exist and are untouched.

## 6. Tests and results (2026-10-17)

| Check | Result |
|---|---|
| Server tests (vitest) | **208/208** (29 files). New `direct_uploads` 11, `storage_s3` 16 (incl. the disk store and no-overwrite) |
| Server typecheck / build / `npm audit` (production deps) | pass / pass / 0 vulnerabilities |
| Android unit tests | **268/268** (56 classes), incl. `AttachmentUploaderTest` 7 new (exact bytes and signed headers reach the store, no Orbit token sent to it, expired-link renewal, checksummed parts with a dropped connection retried, resume from stored parts, cancel on failure, fallback on older servers) |
| Android lint / builds | 0 errors (79 warnings, unchanged) / `assembleDebug` and `assembleRelease` (R8) pass |
| CloudFormation template | valid (`validate-template`; needs `CAPABILITY_NAMED_IAM`) |
| Real S3 (`test/s3_live.test.ts`, test bucket, least-privilege test user) | **9/9** |
| Real S3 from the app (emulator, debug build, local server on the test bucket) | photo sent by direct upload; voice message received and played |

### Real S3 (2026-10-17, synthetic data only)

**Test stack `orbit-storage-test`** (ap-southeast-1), created from the template with `Environment=test`:

- bucket `griipbuddy-orbit-media-sg-test`;
- IAM user `orbit-api-test-ap-southeast-1` with one access key. The key lives only in `~/.aws/credentials`
  (profile `orbit-s3-test`, mode 600) on the test Mac;
- `ServerIp` set to the test Mac's address, so only that Mac may delete or list.

Checked from outside:

- public-access block all on, SSE-S3 (AES256), versioning on, bucket-owner ownership, both lifecycle rules;
- the bucket policy isn't public;
- the test key is refused outside `orbit/`, on other buckets, and for listing buckets.

**`test/s3_live.test.ts`** runs only when `ORBIT_LIVE_S3_BUCKET` is set:

```
ORBIT_LIVE_S3_BUCKET=griipbuddy-orbit-media-sg-test AWS_PROFILE=orbit-s3-test npx vitest run test/s3_live.test.ts
```

It runs a local server on the bucket, with throwaway accounts and random ciphertext, under a prefix unique to each run.
Everything is deleted at the end.

| Check | What AWS did |
|---|---|
| Identity | the server's key works under its prefix; `AccessDenied` elsewhere |
| Direct upload | the presigned PUT went straight to S3; stored as is with SSE-S3; size and SHA-256 verified on completion; the member got a 302 to a 5-minute link, downloaded it (whole and a range) and decrypted it; a non-member got 404 |
| S3's own refusals | other bytes `BadDigest` (400); another size 403; no `if-none-match` 403; the same URL again 412 (same bytes) or 400 (other bytes) |
| Outsiders | an anonymous GET 403; plain HTTP 403; a link edited to another object 403; an expired link 403 |
| Multipart (11 MiB in 5 MiB parts) | parts 1 and 3 sent, `GET …/parts` reported them, part 2 sent, completed with checksums, downloaded and decrypted intact |
| Abandoned uploads | the retention job removed an uploaded object and aborted a multipart upload |
| Profile photos | stored in S3, the old one deleted on replace, served only through the server; direct S3 access 403 |
| Original upload path | works on S3 |
| Persistence | after a server restart, every file, record and photo was still there and decrypted |

**The app on a real bucket.** The Android emulator, with the debug build, ran against a local server on the test
bucket (prefix `orbit/emulator-<run>/`).

- **Sending.** A test-pattern photo used the direct path: `uploads` → PUT to S3 → `complete` → `keep`. The server
  never handled the bytes. In S3 it is 52,982 bytes, with SSE AES256 and a SHA-256 checksum. The database recorded
  the file as kept, for 365 days, with no open upload left.
- **Receiving.** A test bot sent a 3-second voice message through the original path (S3 stored it as 25,828 bytes of
  ciphertext). The app got a 302 to S3, downloaded it, decrypted it and played the whole clip.
- **The server's log** has no presigned links, key ids or S3 host names.

**Cleanup.** The test objects are deleted, and the bucket has no current objects. Their old versions remain
recoverable until versioning's 30 days run out, then disappear (a few MB of synthetic data). The test stack, its
user and its key stay until you decide (§8).

What the tests cover against the fake S3 (it checks the SDK's real signatures, checksums and conditional writes):

- **Access:**
  - permission to create uploads, and non-members refused;
  - uploads owned by their creator (others get 404);
  - cross-conversation and unauthenticated access denied;
  - downloads only for members, with no link for others.
- **Integrity:**
  - wrong bytes, a wrong size, a dropped no-overwrite header, and overwrites all refused;
  - multipart with checksums, a dropped connection, resume and early completion;
  - AES-256-GCM ciphertext stored byte-for-byte and decrypting after download.
- **Limits:** size, quota and open-upload limits.
- **Failure handling:**
  - expiry and cleanup of objects and parts;
  - removal from the conversation mid-upload;
  - S3 outage → 503 and recovery;
  - URL renewal;
  - the original upload path still working on S3, and disk servers not offering direct uploads.
- **Unchanged behaviour:** profile photos on S3; the existing message, federation and workspace suites unchanged.

## 7. Docker services to update (not done)

Only **`api`** of `griip-internal-messaging`, built from this code (the image gains the AWS SDK). LiveKit is unchanged.

Migrations **002–010** will run; all add tables or columns, none removes data. Back up first (`deploy/scripts/backup.sh`).

Set in `.env` before restarting:

- `REGISTRATION_MODE` explicitly (the new default is invite-only);
- for S3: `STORAGE_BACKEND=s3`, `S3_BUCKET`, `S3_REGION=ap-southeast-1`, `S3_PREFIX=orbit/`, and the IAM user's key.

The existing nginx limit (27m) is fine for direct uploads, since big files no longer pass through nginx. It needs ≥ 520m
only for apps that still upload through the server.

After deploying, run `admin.js storage-check`, then the real-S3 checks in §8.

## 8. Remaining blockers and next steps

1. **Test stage: done (2026-10-17).** Test bucket and user created, budget alarm set, live tests 9/9, the app checked
   on the emulator, and the test data removed (§6).
2. **The test stack: keep it or delete it.**
   - Keep it for re-running `s3_live.test.ts` after changes: it costs nothing while empty.
   - Or delete it: delete the access key, delete the stack, then empty and delete the bucket. The bucket is kept on
     stack deletion (`DeletionPolicy: Retain`).
3. **Production, each step only with your approval:**
   1. Create the production bucket and user (`Environment=prod`, `ServerIp` = the VPS).
   2. Write the key to the VPS `.env` without displaying it.
   3. Back up, then deploy `api`.
   4. Enable `STORAGE_BACKEND=s3`.
   5. Run `admin.js storage-check`.
   6. Check from a phone.

   Deploying `api` also brings profile photos, history and workspace deletion to production (§7).
4. **A physical phone** for the app-side checks (still not connected; the emulator covered them so far).
5. **Optional later:** Roles Anywhere; moving the database (below).

## Moving the database to AWS (proposal only, not in scope yet)

If all durable records should live in AWS too, the path is **Amazon RDS for PostgreSQL**.

**Cost** (list price, Singapore, from the Pricing API):

- `db.t4g.micro` single-AZ: $0.025 an hour, about $18 a month, plus storage at $0.138 per GB-month;
- `db.t4g.small` Multi-AZ, for production availability: $0.102 an hour, about $74 a month, plus storage.

**What it would take:**

1. A data-access layer that supports PostgreSQL. Today the code calls better-sqlite3 directly, synchronously, inside
   transactions.
2. Schema migrations for PostgreSQL, from the same 10 SQLite migrations.
3. A one-off copy with checksums, run in a maintenance window, with the SQLite file kept as the fallback.
4. A dual-run test period.
5. Network security: RDS shouldn't be public. Use TLS plus a security group allowing only the VPS, or move the API into
   AWS as well.

The database would sit in Singapore while the API runs on Hostinger, so every query crosses the internet. Latency and
data-out charges follow from that.

**Recommendation.** Keep SQLite on Hostinger while the API runs there. It's backed up by `backup.sh` and now holds the
encrypted history. Move the database only together with the API (e.g. ECS or Lightsail in Singapore), as a separate,
tested project.

Nothing here replaces the current database, migrates production records or deletes data.
