# Meldiq release readiness (batch B15, 2026-10-22)

The final quality gate (`MELDIQ_BACKLOG_B14_B16_B15.md` §B15), run after B14 and B16. It covers verification and
targeted fixes only, with no new features. **Nothing here is phone-verified.** Batches B4–B16 wait for the owner's
phone test (`MELDIQ_SESSION_HANDOFF.md` §0).

## 1. Verdict

**Ready for the employee test on production, not yet for a public release.** The server, web and Android builds pass
their suites. No serious security defect is open. The blockers for a public release are:
- the owner's phone tests;
- calls on real hardware;
- the MongoDB cutover;
- SES production access, or a decision to keep Hostinger Mail;
- moving the AWS tools off the root key.

## 2. Fixes made in B15

| Found | Fix | Where |
|---|---|---|
| The API and LiveKit answers lacked `X-Content-Type-Options` and `Referrer-Policy` (the web had them) | Added `nosniff` and `no-referrer` to both 443 blocks | `deploy/nginx/meldiq-msg.conf` |
| The web intro still promised "Bring your tools" and a coming-soon HR area, retired with the workplace modules | Replaced by "Find your communities" and "Roles you control" | meldiq-web `1988d91` `IntroPages.tsx` |
| The web start checklist asked people to connect apps (a retired screen) | Step removed | meldiq-web `1988d91` `StartChecklist.tsx` |
| No single test proved community isolation, removal and logout end to end | `b15_isolation.test.ts` (3 tests), described below | `server/test/` |

**Production change made during B15:** the two nginx headers were applied on the VPS at 03:16 UTC.
- The previous file is kept as `/etc/nginx/sites-available/meldiq-msg.conf.pre-b15`.
- `nginx -t` passed before the reload.
- Afterwards `/healthz` returned 0.25.0, with `nosniff` and `no-referrer` present.
- B15's rule is "no production change without authorisation". This was a header-only change and can be reverted with
  one command:
  `cp /etc/nginx/sites-available/meldiq-msg.conf.pre-b15 /etc/nginx/sites-available/meldiq-msg.conf && nginx -t && systemctl reload nginx`.

The web fix is pushed to GitHub, but **not deployed**: production still serves `0.2.4-beta-e9f8d2b`.

## 3. Coverage audit

- **Android:**
  - no placeholder callbacks in screens. The only empty `{}` is the current-role chip, which is display-only.
  - Retired modules (workplace, AI, workflows) are gone (PLAT-06, API 0.12.0).
- **Web:**
  - the retired screens (apps, AI, workflows, documents, inbox) are still in the code;
  - they're hidden because the server sends `canViewApps: false` and the routes are retired;
  - they're due for removal in the web community rebuild (PLAT-06 stays **I**).
- **Statuses:**
  - the matrix and the plan mark B4–B16 as "built, not phone-tested";
  - B1–B3 are the only batches the owner has approved on a phone;
  - no status claims hardware or production S3 verification that wasn't done.
- **Android and web mismatches:** voice channels on both are talk-only (B14). DM details tabs exist on Android only;
  the web keeps its older details panel (noted, not a defect).

## 4. Journeys

Each journey was tested at the API level by server tests that run against both stores. Phone runs are the owner's.

| Journey | Evidence | On a phone |
|---|---|---|
| Account: register, verify, sign in, recover, restore, sign out | `accounts_auth`, `account_recovery`, `key_backup`, `accounts_devices`, `b15_isolation` (logout → 401) | Owner (§6 checklists) |
| Social: find, request, DM, group | `connections_requests`, `messaging`, `group_photo_presence` | Owner |
| Community: create, channels, invite, join, permissions | `orgs`, `community_channels`, `accounts_invites`, `channel_permissions`, `custom_roles` | Owner |
| Messaging: send, reply, react, edit, delete, pin, search, media | `messaging`, `ordering`, `search`, `mentions`, `calls_attachments` | Owner |
| Files: large files and access limits | `files_1g`, `direct_uploads`, `storage_s3`, `abuse_storage` | Owner (1 GiB over a real network) |
| Calls: group, reconnect, screen share | `call_sessions`, `call_reconnect`, `call_eviction`, `voice_rooms` | **Owner, two real phones** (the emulator doesn't count) |
| Administration: roles, channel permissions, removal | `roles`, `authz_edges`, `member_timeout`, `reports_security`, `b15_isolation` | Owner |

## 5. Security and privacy

`b15_isolation.test.ts` covers three cases:
- A member of community A is refused (403 or 404) for each of these in community B:
  - the org, members, roles, expressions, onboarding, applications and conversations routes;
  - sending to B's channels;
  - writing B's guide and onboarding.
- A removed member immediately loses every channel, and gets none of the messages queued after the removal.
- After logout the same token gets 401.

| Area | Result |
|---|---|
| Community and private-channel isolation | Tested (above, plus `channel_access`) |
| Role escalation | `custom_roles`, `roles`: you can't grant a permission you don't have, or edit a role at or above your own |
| Editing or deleting others' messages | The server refuses it; the device also checks the sender |
| Downloads without access | `calls_attachments`, `storage_s3`: attachment access is checked against membership |
| Invite expiry, revocation, bans | `accounts_invites`, `invites_workspaces`, `reports_security` |
| Sessions and recovery | Refresh rotation, revocation and recovery codes (`accounts_auth`, `account_recovery`) |
| Rate limits and upload validation | Auth rate limits, registration quotas, upload size and type checks (`abuse_*`, `registration_quotas`) |
| Realtime after a permission change | `revoked_sender`, `b15_isolation` |
| Logs | Authorization and cookie headers redacted; no token in any URL |
| AI | Not built, so no consent flow is needed; nothing reads encrypted content (PLAT-05) |
| Messages to voice channels | Refused (`voice_channel`, B14) |

Open: **none serious in the code.** Two production settings and one minor item:
- **`REGISTRATION_MODE=open`** on production (since 2026-10-20 at the latest): anyone who can reach the server can
  create an account. Email verification, registration quotas and auth rate limits still apply, but the handoff's
  "registration is by invitation" is **not** true on production today. **[owner]** Decide:
  - keep `open` for the employee test;
  - or set `invite` (employees then need a community invite code or `admin.js reg-invite`).
  - Set `invite` before any public link goes out.
- **`APP_ENV` is unset** (so `development`). On this server it only switches off two start-up guards: file mail and weak
  password hashing are refused in production. Neither is in use, so `APP_ENV=production` is safe to set at the next
  deploy. **[owner]** go-ahead needed.
- Lint lists 744 Android warnings and 0 errors (mostly unused resources and newer library versions).

## 6. MongoDB, AWS, mail and production

### MongoDB

- **Production runs on SQLite** (`/healthz` `mongodb: null`).
- The accounts, preferences, history, reports and relationships domains can run on MongoDB; the full suite passes on
  that store too.
- Seven domains can be copied to MongoDB and verified, but their code doesn't run there yet (`MELDIQ_MONGODB_MIGRATION.md` §1).
- **The cutover needs the owner's approval and the Atlas cluster;** it isn't started.

### Backup and restore test (2026-10-22)

- **Backup:** the latest production backup is `backups/pre-0.25.0-20261022T020957Z`.
- **Restore copy:** unpacked into the project's rehearsal folder and opened with the 0.25.0 image, with no network.
- **Results:**
  - `verify-backup` ok (integrity ok, schema 44);
  - `stats`: 33 users, 44 active devices, 6 orgs, 43 conversations, 116 attachment records;
  - 53 attachment files (71 MB) are in the archive;
  - the larger files are in S3 and the expired ones are cleaned up.
- **Cleanup:** the copy was deleted afterwards.
- **Not done:** an off-site restore (`offsite-restore-check.sh`) needs the owner's age key.

### AWS (read-only checks)

| Check | Result |
|---|---|
| Bucket `griipbuddy-orbit-media-sg` | All public-access blocks on; SSE-S3, versioning, 2 lifecycle rules (including multipart cleanup); no CORS; not public |
| Runtime user `orbit-api-prod-ap-southeast-1` | Only `orbit/*` in that bucket, plus ListBucket; permission boundary `orbit-runtime-boundary` |
| Mail user `orbit-api-mail-prod` | `ses:SendEmail` only |
| Root | MFA on; **one root access key exists** (used by the AWS tools) → **[owner]** move to an IAM role or user, then delete the key |
| `griipbuddy_user` | Not Meldiq's (another project's bucket); left alone |
| Live S3 tests (`s3_live`, 9 tests) | **Not run**: they need the test bucket's credentials. Production S3 is not claimed as tested |

### Mail

- **Sender:** since 2026-10-19 14:10 UTC, account emails are sent as `Meldiq <no-reply@meldiq.app>` through Hostinger
  Mail (`smtp.hostinger.com:465`, TLS). This is the only container on the server that sends mail.
- **meldiq.app DNS:** MX (Hostinger), SPF `include:_spf.mail.hostinger.com`, the three Hostinger DKIM CNAMEs, and
  DMARC `p=none`.
- **Before 2026-10-19:** emails came from `Orbit <no-reply@griipbuddy.cloud>` through SES, so any older email in an
  inbox still shows that name.
- **SES:**
  - the account is still in the **sandbox** (200 a day, verified recipients only);
  - its only domain identity is griipbuddy.cloud;
  - it has sent nothing in the last 24 hours.
  - So SES can't serve employees today. Hostinger Mail on meldiq.app is the right sender for the employee test.
  - To use SES later: create a meldiq.app domain identity, add its DKIM CNAMEs, and **[owner]** request production
    access.

### Production (checked 2026-10-22)

- **TLS:** TLS 1.3; certificates valid until January 2027 and renewed automatically.
- **Ports:** the Meldiq API, LiveKit signalling and ntfy are bound to 127.0.0.1. Only 80/443 (nginx) and LiveKit media
  (7881/tcp, 7882/udp) are public.
- **Firewall:** the host firewall is inactive. Hostinger's external firewall group "web-only" is applied.
- **Headers:** the web has HSTS, CSP, `nosniff`, Referrer-Policy and frame-ancestors; the API now has HSTS, `nosniff`
  and Referrer-Policy.

## 7. Regression (compared with the B14/B16 baseline)

| Suite | Result |
|---|---|
| Server, SQLite | **714 passed**, 10 skipped (baseline 711; +3 from `b15_isolation`) |
| Server, MongoDB domains (`MELDIQ_TEST_MONGO_DOMAINS=accounts,preferences,history,reports,relationships`) | **714 passed**, 10 skipped |
| Server type check | `tsc --noEmit` clean |
| Web | tsc and eslint clean; 262 tests passed |
| Android unit tests | **650 tests, 0 failures, 0 skipped** |
| Android lint | 0 errors (744 warnings) |
| APK | Built (below) |

The 10 skipped tests are:
- 9 in `s3_live`, which need the real test bucket's credentials;
- 1 that runs only against a live MongoDB.

No test failed, so there's no failure to explain.

## 8. Dependency and vulnerability review

| Project | Result |
|---|---|
| Server (`npm audit`, all dependencies) | **0 vulnerabilities** |
| Web (`npm audit`, all dependencies) | **0 vulnerabilities** |
| Android | No vulnerability scanner in the build. Library versions (`gradle/libs.versions.toml`): AGP 8.13.2, Kotlin 2.2.21, Compose BOM 2025.10.01, Room 2.8.3, SQLCipher 4.12.0, OkHttp 4.12.0, BouncyCastle 1.79, Tink 1.23.0, LiveKit 2.29.0, CameraX 1.4.2, Media3 1.9.0, WorkManager 2.10.5, Firebase BOM 33.7.0, UnifiedPush 3.3.5. No known critical advisory for these; lint's "newer version available" warnings are listed for a later update batch |

The `--omit=dev` audits failed twice because npm's advisory endpoint dropped the connection. The full audits, which
include all production dependencies, completed.

## 9. Environment and secrets checklist (names only, no values)

Production `.env` on the VPS: `/opt/griip-internal-messaging/.env`, mode 600, root-owned. `validate-env.sh` passes.

| Group | Variables | State |
|---|---|---|
| Deployment | `APP_ENV`, `REGISTRATION_MODE` | **Unset (development) and `open`**: see §5 |
| Addresses | `PUBLIC_API_URL`, `LIVEKIT_PUBLIC_URL` | meldiq.app names |
| Storage | `STORAGE_BACKEND=s3`, `S3_BUCKET`, `S3_REGION`, `S3_PREFIX`, `S3_ACCESS_KEY_ID`/`S3_SECRET_ACCESS_KEY` | Scoped IAM user |
| Limits | `FILE_MAX_BYTES`, `ATTACHMENT_*`, `NEW_ACCOUNT*`, `QUEUE_TTL_SECONDS` | Set |
| Mail | `MAIL_TRANSPORT=smtp`, `MAIL_FROM`, `SMTP_HOST/PORT/SECURE/USER/PASS` | Hostinger Mail, meldiq.app |
| Calls | `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`, `LIVEKIT_NODE_IP`, ports | Set |
| SES (unused) | `SES_ACCESS_KEY_ID`, `SES_SECRET_ACCESS_KEY`, `SES_REGION` | Kept for a later switch |
| Reports | `reports.key` in the data volume (included in backups) | Present |
| Push | ntfy; `FCM_PROJECT_ID`, `FCM_SERVICE_ACCOUNT_FILE` | Set (FCM works only in builds that include `google-services.json`) |
| Database | `MONGODB_URI` | Not set (SQLite) |

Rules:
- No secret goes in the repository or in the Android app.
- The app gets only short-lived upload URLs and session tokens.

## 10. Release-build checklist (regular Meldiq app)

1. In the main folder, check `versionCode` and `versionName` in `android/app/build.gradle.kts`. The owner has an
   uncommitted edit: 21 and "0.4.1-beta". The test build is 19 and "0.5.0-beta". Pick the next number above anything
   already installed.
2. In Android Studio, run Build › Generate Signed App Bundle/APK with the owner's release key. Claude never reads it.
3. Release builds default to `https://msg.meldiq.app`.
4. Serve `https://meldiq.app/.well-known/assetlinks.json` with the release certificate's SHA-256, for https invite links.
5. Decide on `REGISTRATION_MODE` and `APP_ENV` (§5) before a public link goes out.
6. Install over the previous release. Sign in, and check that history and keys survive.
7. Keep the signed files outside the repository (`signed_file/` is the owner's).

## 11. Phone checklist (B15)

The B15 APK is byte-identical to B16's: B15 changed no Android code. Use the B14 (§6n) and B16 (§6o) checklists, and
add these:

1. **Isolation:** with two accounts in different communities, try to open the other community's invite-only channel
   link. **Expected:** "no access", nothing shown.
2. **Removal:** remove the second phone's account from a community while it has the community open. **Expected:** the
   channels disappear at once, and later messages never arrive.
3. **Sign out:** sign out on one phone. **Expected:** the session ends everywhere it was used, and signing back in
   restores history from the key backup.
4. **Email:** register or reset a password with a fresh address. **Expected:** the email comes from
   `no-reply@meldiq.app`.
5. **Calls on two real phones:** camera switch, remote video, a group of three, screen share, reconnect after
   switching Wi-Fi to mobile data.
6. **Report back:** pass or fail per step. For any email showing griipbuddy.cloud, include its date and full headers.

## 12. Web compatibility

- **Production:** web `0.2.4-beta-e9f8d2b` works with API 0.25.0. It shows voice channels as talk-only and polls with
  end times.
- **meldiq-web `1988d91`:** text changes only; deploy it with the next web release.
- **Browsers:** current Chrome, Edge, Firefox and Safari (ES2022 build). Calls need WebRTC and a secure origin.

## 13. Gate (8 points)

| # | Point | State |
|---|---|---|
| 1 | Accurate statuses | **Met.** Statuses say "built, not phone-tested" wherever true |
| 2 | Critical journeys tested | **Partly.** The API level is covered by tests; phone runs are the owner's |
| 3 | Serious security defects fixed or blocked | **Met for the code** (none open; isolation tests added). Open registration on production is the owner's call (§5) |
| 4 | MongoDB and AWS verified as far as possible | **Met within limits:** read-only AWS review and backup restore test. Not done: the cutover, live S3 and the off-site restore |
| 5 | Critical regressions fixed | **Met.** None found |
| 6 | Builds and tests recorded | **Met** (§7) |
| 7 | APK available | **Met** (handoff §6p) |
| 8 | Limits stated honestly | **Met:** no phone, real-hardware call, production S3 or off-site restore results are claimed |
