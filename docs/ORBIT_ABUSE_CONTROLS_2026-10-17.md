# Task 003: registration, rate limiting and storage abuse protection (2026-10-17)

**Status:**
- **Implemented and tested locally:** server 239/239 (29 new), typecheck and build clean.
- **Not verified against a test environment, and not deployed:** production still runs API 0.2.0 without these
  controls.
- **Nothing is committed** yet.

The reference for operators is [`ABUSE_CONTROLS.md`](ABUSE_CONTROLS.md).

Production was read, not changed:
- nginx proxy headers;
- non-secret API limits;
- the Docker network;
- disk use;
- aggregate usage counts, with no names or ids.

No credential, IAM policy, account, session or stored file was touched.

## 1. What existed, and the abuse paths found

| Area | Existing protection | Abuse path found |
|---|---|---|
| **Client address** | Rate limits keyed per device (signed in) or per IP. `compose.yaml` sets `TRUST_PROXY=true` | **Bypass, in production.** nginx *appends* to `X-Forwarded-For`, and `TRUST_PROXY=true` believes the left-most entry, which the client writes. Any per-IP limit can be dodged by sending a made-up header. Confirmed by reading the production nginx site and with a local test. IPv6 clients could also rotate through their /64 |
| **Registration** | Invite mode exists (T1.6). Production is **open** (owner's choice). 30 requests a minute per IP on the route | 30 accounts a minute per IP (unlimited with the header bypass), each with full quotas. No server-wide cap |
| **Storage per account** | Per-user daily 2 GiB, stored 4 GiB, kept 10 GiB; server-wide 10 GiB; concurrency and in-flight limits | **Multiplied by new accounts:** strangers could fill the 10 GiB server-wide cap and deny uploads to employees. No per-workspace cap |
| **Parallel direct uploads** | Quota check, then the upload record | **Race:** a multipart start waited for S3 between the quota check and the record, so parallel starts all passed. Reproduced: 4 of 6 passed a quota for 2 |
| **Retries** | Cancelled and abandoned uploads are cleaned up; completing is idempotent | Start-and-cancel loops were unlimited: S3 requests and server work, no stored bytes |
| **Message history** | Per account 1 GiB / 500,000 items, in a transaction | **No server-wide cap.** It's stored in SQLite on the VPS disk that about 17 other projects share (56 GB free). New accounts × 1 GiB could fill it |
| **Sign-in and recovery** | Ed25519 signatures over single-use nonces (nothing to guess); 30 a minute per IP; the same error for an unknown device and a bad signature | `add-device` told an unknown username apart from a wrong certificate. Usernames are public by design anyway (availability check, search) |
| **Devices** | None | Unlimited devices per account. Each one gets its own rate-limit bucket, and every message to the account carries a key for each, up to the 1,000-recipient limit |
| **Realtime** | 64 KiB frames; typing at most every 1.5 s; the session re-checked on every frame | No frame rate limit (each frame is a database lookup). Unlimited connections per device |
| **Authorization** | Membership and posting rights checked on every upload, download and upload-session call. Ids are unguessable; unauthorised and missing both get 404 | **None found.** New tests call the API directly to confirm |
| **Request size** | 256 KiB bodies (2 MiB messages, 6 MiB history); uploads streamed with the size enforced while reading; presigned URLs bound to size and SHA-256 | **None found.** Server-side, whatever the app sends |

**The 2 GiB daily allowance, checked in code:** `ATTACHMENT_USER_DAILY_BYTES` defaults to `2048 * MiB`, and production
uses the default.

**Production usage** in its first 7 days (22 accounts, a share of them synthetic test accounts):

| Measure | Value |
|---|---|
| Uploads per person-day, heaviest | 56 MiB |
| Uploads per person-day, median | 1.7 MiB |
| Largest file | 24 MiB (production caps files at 25 MB) |
| History stored | 0.2 MiB in all |
| Active devices per account | at most 2 |
| Messages per person-day | at most 123 |

**Decision:**
- **Established accounts keep 2 GiB a day.** The business wants files up to 500 MB (supported since 2026-10-17), and
  2 GiB is four such files. It was never the abuse path: account multiplication was.
- **New open-registration accounts get 256 MiB a day.** That's about 5× the heaviest observed day, and ten maximum-size
  files at today's 25 MB cap.

Operators can still lower the general allowance with `ATTACHMENT_USER_DAILY_BYTES`.

## 2. Controls implemented, with their defaults

See [`ABUSE_CONTROLS.md`](ABUSE_CONTROLS.md) for how each behaves. 0 turns a limit off.

| Control | Setting (default) |
|---|---|
| Real client address behind nginx; hop counts refused; a warning when `true` | `TRUST_PROXY` (`compose.yaml`: `loopback,uniquelocal`) |
| Requests without a session counted per IPv4 address or IPv6 /64 | — |
| Open registrations per network | `REGISTRATION_PER_ADDRESS_PER_HOUR` (10), `…_PER_DAY` (30) |
| Open registrations server-wide | `REGISTRATION_OPEN_PER_DAY` (200) |
| New-account limits, ended by an established member's invitation or `admin.js limits-end` | `NEW_ACCOUNT_LIMIT_DAYS` (7), `NEW_ACCOUNT_DAILY_UPLOAD_BYTES` (256 MiB), `NEW_ACCOUNT_STORED_BYTES` (512 MiB), `NEW_ACCOUNTS_TOTAL_STORED_BYTES` (2 GiB), `NEW_ACCOUNT_HISTORY_BYTES` (64 MiB) |
| Per-workspace file quota | `ATTACHMENT_ORG_STORED_BYTES` (0: off until a business limit is chosen) |
| Upload starts per person per hour | `ATTACHMENT_UPLOAD_STARTS_PER_HOUR` (300) |
| Parallel-start race fixed: direct uploads recorded before S3 is called | — |
| Server-wide history cap | `HISTORY_TOTAL_BYTES` (10 GiB) |
| Devices per account | `MAX_DEVICES_PER_USER` (20) |
| Realtime frame budget; connections per device | `WS_FRAMES_PER_SECOND` (20), `WS_FRAME_BURST` (100), `WS_MAX_SOCKETS_PER_DEVICE` (4) |
| Same answer for an unknown username and a wrong certificate on `add-device` | — |
| `admin.js limits <username>`, `admin.js limits-end <username>` | — |

Refusals carry stable codes:
- `registration_limited` and `too_many_uploads`, both with `Retry-After`;
- `registration_paused`;
- `quota_exceeded`;
- `org_quota_exceeded`;
- `storage_full`;
- `history_full`;
- `too_many_devices`.

## 3. Migration and files changed

**Migration `012_abuse_controls.sql`** is additive. It adds `users.limited_until` (null for every existing account) and
the index `idx_audit_action`. Older servers ignore both. It's numbered 012 because the push wake-up work in progress
uses 011; migrations are applied by version, whichever arrives first.

| File | Change |
|---|---|
| `server/src/abuse.ts` (new) | Network keys (IPv6 /64), in-memory sliding windows, new-account helpers, the day's open registrations |
| `server/src/config.ts` | `TRUST_PROXY` parsing; `abuse` settings; `HISTORY_TOTAL_BYTES` |
| `server/src/app.ts` | Rate limits per network without a session; warning for `TRUST_PROXY=true`; hub socket cap |
| `server/src/routes/auth.ts` | Registration limits; `limited_until` set at open registration; device cap; uniform `add-device` answer |
| `server/src/routes/attachments.ts` | New-account and workspace quotas; in-flight bytes per workspace and pool; upload starts; recording before S3 |
| `server/src/routes/history.ts` | New-account and server-wide history caps; the summary shows the account's own cap |
| `server/src/invites.ts` | An established member's invitation ends new-account limits |
| `server/src/ws.ts`, `server/src/hub.ts` | Frame budget; the oldest connection is replaced beyond the per-device cap |
| `server/src/admin.ts` | `limits`, `limits-end` |
| `server/migrations/012_abuse_controls.sql` (new) | Above |
| `server/test/abuse_registration.test.ts`, `server/test/abuse_storage.test.ts` (new) | 29 tests |
| `server/test/support/harness.ts`, `griipClient.ts` | Limits off by default for other suites (every test user registers from 127.0.0.1); optional extra headers; `Retry-After` on failures |
| `deploy/compose.yaml` | `TRUST_PROXY=loopback,uniquelocal` |
| `deploy/.env.example`, `docs/SELF_HOSTING.md`, `docs/ABUSE_CONTROLS.md` (new), this report, `docs/RELEASE_PLAN.md`, `docs/SECURITY_REMEDIATION.md`, `README.md` | Documentation |

## 4. Tests run and results

All local, on the development Mac (Node 25.8.0). CI runs the same on Node 22.22.3 once this is pushed.

| Command | Result |
|---|---|
| `npx vitest run` (before any change) | 210 passed, 9 skipped |
| `npx vitest run` (after) | **239 passed**, 9 skipped (real-S3 suite, no credentials), 32 files |
| `npx vitest run test/abuse_*.test.ts`, three times | 29/29 each time |
| The parallel-upload test against the old ordering (temporarily restored, then put back) | **Failed** as expected: 4 of 6 parallel starts passed a quota for 2. With the fix: exactly 2 |
| `npm run typecheck` / `npm run build` | Pass / pass |

Coverage of the ten required scenarios:

| # | Scenario | Test |
|---|---|---|
| 1 | Registration limits and repeated attempts | per network per hour and day; IPv6 /64; an invented `X-Forwarded-For` doesn't help (and does with `TRUST_PROXY=true`); failed attempts don't count; server-wide pause; invitations not counted per network |
| 2 | Sign-in limits and retrying | 30 a minute per network; another network unaffected; the same person signs in after a minute; same answer for an unknown username and a wrong certificate |
| 3 | Uploads over the size limit | announced and sent, direct and streamed: 413 |
| 4 | Per-user and workspace quotas | new-account daily and pool limits; established accounts unaffected; workspace cap, with DMs unaffected |
| 5 | Parallel uploads past a quota | six parallel multipart starts: two admitted, four `quota_exceeded`, two records |
| 6 | Unauthorised upload and download | a non-member can't upload, start, complete, cancel or download (404); no session: 401 |
| 7 | Failed, cancelled and retried uploads | a cancel frees the share and S3's parts; an S3 failure holds nothing back and the retry works; an upload cut off half-way counts as a start |
| 8 | Abandoned multipart cleanup | expired, aborted at S3, parts gone, nothing recorded touched |
| 9 | Normal use continues | a message with a file is delivered and the file downloads and matches; realtime unaffected at normal rates |
| 10 | Recovery after a limit expires | registration after the hour and after 24 hours; sign-in after a minute; upload starts after an hour; new-account limits after 7 days |

Also tested:
- the history caps: per new account, and server-wide (deleting and shrinking still work);
- the device cap, and that removing a device makes room;
- the frame budget (closed at 4429);
- the socket cap (the oldest closed at 4409, the newest kept);
- `TRUST_PROXY` parsing.

All test accounts and files are synthetic, on in-process servers. S3 is the in-process fake: no AWS traffic.

## 5. Not implemented, and why

| Control | Why not | What it needs |
|---|---|---|
| **Download egress limits** | A presigned S3 link can be reused many times within its 5 minutes, from anywhere, and S3 can't make single-use links. Each re-download is billed transfer | An infrastructure decision: CloudFront signed URLs or cookies, or serving downloads through the API with a per-person budget. Meanwhile, a cost alarm on S3 transfer |
| **Delivery-queue growth** | A member posting at the per-device rate in a large channel fills each recipient device's queue (7-day expiry). There's no per-recipient cap: what to drop is a product decision | A queue cap per device, with a "too many messages" marker for the app |
| **Email or phone verification** | Accounts have no email, and phone numbers are unverified profile fields. Verification would need a paid SMS or email provider and app screens. Invitations are the account model's verification | An owner decision, and a provider with its cost |
| **CAPTCHA or proof-of-work** | Needs a third party or app changes | A product decision |
| **nginx `limit_req`** | A production infrastructure change, outside this task. It would turn floods away before Node.js | An approved change to the API's nginx site |
| **Durable counters** | Per-network and per-hour counters are in memory: a restart clears them, and they aren't shared between instances. Production runs a single instance | Fine at this scale. Move them to the database if more instances appear |
| **Global caps on challenges and pairing requests** | A server-wide cap would let one attacker lock everyone out of signing in. Per-network limits apply, and the rows expire in 2 and 10 minutes | — |
| **Open-mode registration with an invitation** | In open mode a code given at sign-up isn't used, so the account still counts as open. It ends its limits by accepting the invitation afterwards, as the app does | — |
| **Production verification** | Not deployed (needs approval) | Activation steps in `ABUSE_CONTROLS.md` |

## 6. Effect on people and costs

People:
- **Existing accounts:** no new limits.
- **New employees** who register openly and are then invited by an established member: no limits after accepting.
- **Anyone else in their first 7 days:**
  - 256 MiB of uploads a day, 512 MiB stored, 64 MiB of history;
  - shared storage of 2 GiB, which can fill under abuse; established accounts are then unaffected.
- **An office signing up together from one address:**
  - 10 an hour and 30 a day without invitations, then a clear message pointing to invitation codes;
  - nobody is locked out for good.
- **Normal app traffic** (a few realtime frames a second, at most 2 active devices seen so far, reconnects) stays well
  inside the limits.

Costs:
- **No new service** and nothing paid.
- **Server work** per upload start: a few indexed SQL sums. For new accounts, two more over files and open uploads.
- **Memory:** at most 50,000 keys per counter.
- **Storage is bounded:** new accounts' share of S3 at 2 GiB (about $0.05 a month), and history on the VPS disk at
  10 GiB.

## 7. Deployment and rollback

The steps are in [`ABUSE_CONTROLS.md`](ABUSE_CONTROLS.md) › Activating it and Rollback. In short:
1. Commit, and wait for CI.
2. Bump the version, so the 0.2.0 image stays for rollback.
3. Back up the database.
4. Run `deploy.sh`: API only, with migration 012 additive and no `.env` change.
5. Check in production that a made-up `X-Forwarded-For` doesn't change the address in the logs.

**Rollback:**
- any single limit: set it to 0 and restart the API;
- the whole change: start the 0.2.0 image. No data needs restoring.

## 8. Recommended next task

**Release these controls to production (API 0.3.0), with live checks.**

Why first: until then, production's per-address limits can be dodged with a header, and registration and storage
are open to account multiplication.

It's small and needs no new infrastructure, but it needs the owner's approval to deploy:
1. version bump;
2. backup;
3. `deploy.sh`;
4. the client-address check;
5. a synthetic new account;
6. an existing account's messages and files.

After that, the next isolated engineering task is **download egress protection** (§5): a per-person download budget
and short-lived links, or CloudFront signed URLs. It's the remaining cost path that S3 makes possible.
