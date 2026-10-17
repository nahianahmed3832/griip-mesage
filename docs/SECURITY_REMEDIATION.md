# Operational security remediation

Written 2026-10-17 (Engineering Task 001), from the audit's risks S3 to S6
([`ORBIT_PRODUCT_TECHNICAL_AUDIT_2026-10-17.md`](ORBIT_PRODUCT_TECHNICAL_AUDIT_2026-10-17.md) §4).

**Nothing here has been done.** No infrastructure was changed and no credential was rotated or disabled while writing
it.
- Each step changes live systems, so each one is the owner's to approve and carry out.
- No credential values appear here, and none should be added.
- AWS steps use the scoped `orbit-admin` profile, except where a step says root.

Order of priority:
1. Registration and storage abuse (§3).
2. The root access key (§1).
3. Off-site backups (§4).
4. Release builds (§5).
5. The VPS key (§2).

§3 and §5 block a wider employee rollout. §1 and §4 should land before it.

## 1. The active AWS root access key

**Now.** Profile `griipbuddy` on the owner's Mac is the root user's long-lived access key. Root MFA is on for console
sign-in. Orbit doesn't need root: the `orbit-ops-admin` stack and profile `orbit-admin` cover Orbit's stacks.

**Risk.** A root key can't be limited by any policy. Whoever has it controls the whole account: billing, every bucket,
other projects' resources.

**Steps** (from [`ORBIT_AWS_PRODUCTION_2026-10-17.md`](ORBIT_AWS_PRODUCTION_2026-10-17.md) §7, in order):
1. **Create a console administrator** in the console, as root. Use IAM Identity Center (needs AWS Organizations, free)
   or an IAM user with MFA and `AdministratorAccess`. The password and MFA device stay with the owner.
2. **Check it works.** Sign in as that administrator and confirm it can see IAM, S3 and CloudFormation.
3. **Move tools off root.** Point local tools off profile `griipbuddy` (the AWS Agent Toolkit's `aws-mcp` uses it) onto
   `orbit-admin` or the new administrator.
4. **Find anything still using the key.** Look at "last used" in **IAM › Security credentials › Access keys**, or the
   credential report:
   ```bash
   aws iam generate-credential-report --profile orbit-admin
   ```
   That needs IAM read rights, which `orbit-admin` may not have; root can always see it in the console.
5. **Deactivate the key** (don't delete it yet) and watch for a week. Anything that breaks is still using it.
6. **Delete it.** Remove the `griipbuddy` section from `~/.aws/credentials` too.

**Check.** IAM › Security credentials shows no root access key. `aws sts get-caller-identity` with any remaining
profile names an IAM user or role, never `:root`.

**Undo.** A deactivated key can be reactivated. A deleted one can't, but root can create a new key while signed in.

## 2. The long-lived AWS credential on the VPS

**Now.** The API reads an access key for `orbit-api-prod-ap-southeast-1` from `/opt/griip-internal-messaging/.env`
(root-only, mode 600). Its limits:
- objects under Orbit's prefixes only;
- delete and list only from the VPS's address;
- capped by the permissions boundary `orbit-runtime-boundary`.

It doesn't expire.

**Risk.** Anyone who reads `.env`, through a VPS compromise or a stray backup of it, can read and upload Orbit's
encrypted files until the key is rotated. File contents stay end-to-end encrypted; names, sizes and access patterns
don't.

**Steps:**
1. **Rotate every 90 days** (production report §6). Don't print a key at any step.
   1. As `orbit-admin`, create a second key and pipe it over SSH straight into `.env`.
   2. Restart only the API:
      ```bash
      docker compose -p griip-internal-messaging -f compose.yaml up -d --no-deps api
      ```
   3. Run `admin.js storage-check`.
   4. Deactivate the old key; delete it a week later.
2. **Watch it.**
   - Review the key's "last used" date and region monthly.
   - Consider CloudTrail data events for the bucket before the wider rollout (extra cost).
3. **Replace it with short-lived credentials:** IAM Roles Anywhere with a self-managed CA ([`AWS_STORAGE_PLAN.md`](AWS_STORAGE_PLAN.md)
   §5). The VPS then holds a certificate that's exchanged for one-hour credentials. The server code doesn't change: it
   uses the SDK's default chain.
4. **Keep `.env` out of backups.** It already is: `deploy/scripts/backup.sh` leaves out `.env` and `livekit.yaml`. Keep
   copies only in the password manager.

**If the key leaks:**
1. Deactivate it at once. Uploads and downloads fail until a new key is in place.
2. Create a new key and restart the API.
3. Review the bucket's object list for unexpected uploads.

## 3. Public registration and storage abuse

**Update (Task 003, not deployed yet):** the server now limits open registration per network and per day, gives new
accounts smaller allowances, caps history server-wide, and reads the real client address behind nginx
([`ABUSE_CONTROLS.md`](ABUSE_CONTROLS.md)). The steps below still apply. Invite-only registration remains the strongest
control.

**Now.**
- **Registration.** `REGISTRATION_MODE=open` (the owner's choice): anyone who reaches `https://msg.griipbuddy.cloud` can
  create an account and upload.
- **Quotas** are the defaults:
  - 2 GiB uploaded per person per day;
  - 4 GiB stored per person;
  - 10 GiB server-wide;
  - 500 MB per file;
  - production caps files at 25 MB with `ATTACHMENT_MAX_BYTES`.
- **Billing.** S3 storage and transfer are billed. The only alarm is the account-wide budget `orbit-monthly-10usd`.

**Risk.** Strangers can fill the server-wide quota, which denies uploads to employees, and run up S3 costs. Test
accounts from earlier checks are still in production.

**Steps:**
1. **Switch to invite-only registration.** The server already supports it (T1.6).
   1. Confirm every tester's app has the invitation-code field (builds from 2026-10-16 or later).
   2. Mint codes on the VPS. The code is printed once: hand it over privately.
      ```bash
      docker compose -p griip-internal-messaging -f compose.yaml exec -T api node dist/admin.js reg-invite <uses> <days> <note>
      ```
   3. Set `REGISTRATION_MODE=invite` in `.env`, then restart only `api`.
   4. Check with `admin.js reg-invites` (it shows hints, never codes), and confirm a registration without a code is
      refused.
2. **Lower the quotas for the beta**, for example 200 MB a day and 1 GiB stored per person, with
   `ATTACHMENT_USER_DAILY_BYTES`, `ATTACHMENT_USER_STORED_BYTES` and `ATTACHMENT_TOTAL_BYTES`. Restart `api`.
3. **Add an Orbit-only cost alarm.** Tag-scope a second budget to `app=orbit`. That needs the tag activated as a
   cost-allocation tag, so the alarm doesn't fire for other projects.
4. **Review accounts.** Review new accounts weekly while registration is open. Disable leftover test accounts the
   owner no longer needs (list: production report §8).

**Undo.** Set `REGISTRATION_MODE=open` and restart `api`. Existing accounts are unaffected either way.

## 4. Encrypted off-site backups and restore tests

**Now.** `deploy/scripts/backup.sh` writes an owner-only archive (database snapshot, disk attachments, federation
identity) **on the VPS itself**, unencrypted, and only when someone runs it. The pre-0.2.0 backup is at
`/opt/griip-internal-messaging/backups/`.

Files in S3 have versioning, so a deleted or replaced object stays recoverable for 30 days. That's in the same account
and region, so it isn't an off-site backup.

**Risk.** Losing the VPS, or one bad command on it, loses every account, membership and queued message: the database
exists only there. An unencrypted archive copied anywhere exposes metadata: who talks to whom, organisations, usernames.

**Steps:**
1. **Encrypt each archive** before it leaves the VPS, to a public key: `age -r <recipient>` or GPG. The private key is
   kept offline by the owner, never on the VPS. A stolen backup is then useless.
2. **Store it off-site.** For example, a separate S3 bucket in another region with:
   - versioning;
   - a lifecycle rule (keep 30 daily and 12 monthly);
   - optionally Object Lock;
   - a dedicated IAM user that can only `PutObject` into it, no read or delete, with the runtime boundary.

   Define it in CloudFormation next to `orbit-storage.yaml`. A different provider works too.
3. **Schedule it.** A daily systemd timer or cron job runs `backup.sh`, encrypts, uploads, deletes the local plaintext,
   and keeps a few encrypted local copies. It should alert on failure, for example by email from the job.
4. **Test restores monthly.** Never on production. On a scratch machine or a throwaway VPS:
   1. download the latest archive and decrypt it;
   2. run `deploy/scripts/restore.sh <archive> --yes` against a fresh deployment of the same image;
   3. run `admin.js verify-backup`;
   4. sign in with a test account and check its organisations and conversations;
   5. record the date and result in `PROGRESS.md`.
5. **Keep secrets separately.** `.env` and `livekit/livekit.yaml` aren't in backups, on purpose. Keep current copies in
   the password manager so a restore can be completed.

**Check.** A restore drill completes from an archive pulled from off-site storage, and no plaintext archive is left on
the VPS.

## 5. Debug builds and insecure transport

**Now.** Testers received debug APKs. Those builds:
- are debuggable;
- allow plain HTTP to any host;
- are signed with a developer machine's debug key.

Production itself is HTTPS-only for clients: API at `https://msg.griipbuddy.cloud`, calls at `wss://rtc.griipbuddy.cloud`.

**Risk.**
- On a phone with USB debugging on, a debuggable app's private files and memory can be read through `run-as` or a
  debugger: the SQLCipher database, sealed preferences, decrypted attachments.
- An HTTP server address in a debug build sends sign-in tokens and metadata in the clear.

**Steps:**
1. **Ship release builds only.** Employees get signed release builds from Play internal testing
   ([`RELEASE_BUILDS.md`](RELEASE_BUILDS.md) › Making a release). Debug APKs stay with developers, and the
   side-by-side `Orbit Test` build stays with the test devices.
2. **Delete the download objects** from the **test** bucket (as `orbit-admin`, once the owner has finished installing):
   - `downloads/Orbit-Test-0.1.4-beta-2026-10-17.apk`
   - `downloads/Orbit-Test-0.1.4-beta-arm64.apk`
   - `downloads/Orbit-Test-0.1.4-beta-arm64.zip`

   Their presigned links have expired or will expire, but the objects remain.
3. **Close the two release-build gaps** in `RELEASE_BUILDS.md`:
   - R1: no HTTP exception for `10.0.2.2`, `localhost` and `127.0.0.1` in release builds;
   - R2: release builds fail unless the default server is `https://`.
4. **Check the TLS setup on host nginx.** Read it on the VPS, without changing anything:
   - HSTS is set for `msg.` and `rtc.griipbuddy.cloud`;
   - only TLS 1.2 and 1.3 are accepted;
   - port 80 only redirects.

   Fix any gap in a separate, approved change.
