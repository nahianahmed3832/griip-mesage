# Abuse controls

Added 2026-10-17 (Task 003). Code: `server/src/abuse.ts`, with checks in `routes/auth.ts`, `routes/attachments.ts`,
`routes/history.ts`, `ws.ts`, `hub.ts` and `invites.ts`. Tests: `test/abuse_registration.test.ts` and
`test/abuse_storage.test.ts`. The task report, with the audit of what existed before, is
[`ORBIT_ABUSE_CONTROLS_2026-10-17.md`](ORBIT_ABUSE_CONTROLS_2026-10-17.md).

**Status:** tested locally (239 server tests). Not deployed: production runs API 0.2.0 without these controls until an
approved release (see [Activating it](#activating-it)).

Every limit is a setting in `.env`; 0 turns it off. Defaults are in `server/src/config.ts` and `deploy/.env.example`.

## The client's address

Several limits count per network, so the server must know the real client address.
- **Behind nginx,** the API reads it from `X-Forwarded-For`.
- **`TRUST_PROXY` names the proxies to believe.** Production uses `loopback,uniquelocal`:
  - nginx reaches the container through Docker's proxy, from the network gateway (`192.168.128.1`, a private address);
  - the client is the last address nginx appended, so entries a client writes itself are ignored.
- **`TRUST_PROXY=true`**, the setting until this change, believes every entry: a client can pick its own address and
  dodge every per-address limit. A test shows this. The server logs a warning at start if it's still set.
- **A hop count** such as `1` is refused at start: Fastify ignores hop counts altogether.
- **Requests without a session** are counted per network: an IPv4 address, or an IPv6 **/64**. A phone or home holds a
  whole /64, so counting single IPv6 addresses would be trivial to dodge.

## Registration (open mode only)

| Setting | Default | What it does |
|---|---|---|
| `REGISTRATION_PER_ADDRESS_PER_HOUR` | 10 | Accounts created from one network in an hour |
| `REGISTRATION_PER_ADDRESS_PER_DAY` | 30 | …and in 24 hours |
| `REGISTRATION_OPEN_PER_DAY` | 200 | Open registrations on the whole server in 24 hours. Past it, sign-up waits (`registration_paused`) |

How it behaves:
- **What counts.** Only accounts actually created. A refused attempt, a taken username or a bad signature costs
  nothing. Attempts themselves stay limited by the sign-in limit below.
- **When refused,** the answer is `429 registration_limited` with `Retry-After`. The windows slide, so nobody is locked
  out for good. The message points to invitation codes.
- **With an invitation** (`REGISTRATION_MODE=invite`), registration isn't counted per network: each code is already
  limited by its own uses. An office joining together should use invitations.
- **Usernames are normalised** (trimmed, lower-case) before the uniqueness check. The limits count networks, not
  names, so changing the name doesn't help.

## New accounts

An account made by **open registration** has smaller allowances for `NEW_ACCOUNT_LIMIT_DAYS` (7) days:

| Setting | Default | Established accounts |
|---|---|---|
| `NEW_ACCOUNT_DAILY_UPLOAD_BYTES` | 256 MiB a day | `ATTACHMENT_USER_DAILY_BYTES` (2 GiB) |
| `NEW_ACCOUNT_STORED_BYTES` (kept files included) | 512 MiB | `ATTACHMENT_USER_STORED_BYTES` (4 GiB) + kept quota |
| `NEW_ACCOUNTS_TOTAL_STORED_BYTES` (all new accounts together) | 2 GiB | — |
| `NEW_ACCOUNT_HISTORY_BYTES` | 64 MiB | `HISTORY_MAX_BYTES_PER_USER` (1 GiB) |

The shared pool means more accounts don't buy more storage. Once it's full, uploads from new accounts get
`507 storage_full` and established accounts are unaffected.

The limits end in any of these ways:
- by themselves, after the days have passed;
- when the account **accepts an invitation from an established member**. An invitation from an account that's still
  limited doesn't count, so nobody can vouch for their own extra accounts;
- when an administrator runs `admin.js limits-end <username>`.

Accounts that existed before this change have no limits: `users.limited_until` is null.

## Sign-in and recovery

- **No passwords.** Sign-in and recovery (`/v1/auth/login`, `/v1/auth/add-device`) need an Ed25519 signature over a
  single-use server nonce, by a device key or the identity key, so guessing is pointless.
- **Rate limits** protect the server's CPU:
  - 30 requests a minute per network on each sign-in route (`challenge`, `login`, `register`, `add-device`, `pairing`);
  - 600 a minute per device or network for everything else (`RATE_LIMIT_PER_MINUTE`).
- **After a 429,** waiting a minute is enough.
- **No account oracle.** `add-device` answers an unknown username and a certificate that doesn't match with the same
  `401 Invalid device proof`. Before, it said which part failed. `login` already did this for device ids.
- **Usernames are public on purpose:** `username-available` and search show them, so people can find each other. Both
  are rate-limited.
- **Devices.** `MAX_DEVICES_PER_USER` (20) caps devices per account; removed devices don't count. More devices would
  mean more rate-limit buckets and bigger messages for everyone writing to the account.
  - **When it's reached,** `add-device` answers `409 too_many_devices`.
  - **To fix it,** the person removes an old device in Settings › Devices, or an administrator raises the setting and
    restarts the API.

## Uploads and storage

All of these are enforced by the server, whatever the app does:
- **File size.** `ATTACHMENT_MAX_BYTES` covers:
  - a declared size;
  - bytes actually received;
  - a direct upload's announced size, which its presigned URL is bound to, along with the SHA-256.
- **Quotas.** They're checked before anything is received:
  - per person, a day and stored;
  - for new accounts;
  - per workspace;
  - server-wide.
- **Uploads in progress** count against every quota at once:
  - **Streamed uploads** reserve their bytes in memory.
  - **Direct uploads** are recorded in the database **before** S3 is asked for anything.

  Until this change, a multipart start waited for S3 between the check and the record. In the test, 4 of 6 parallel
  starts passed a quota that allowed 2.
- **Workspaces.** `ATTACHMENT_ORG_STORED_BYTES` (0, off) caps files in one workspace's conversations, including kept
  files and uploads in progress. DMs and groups belong to no workspace.
- **Upload starts.** `ATTACHMENT_UPLOAD_STARTS_PER_HOUR` (300) counts every upload started, whether it finished, failed
  or was cancelled. A cancelled upload frees its bytes, but endless start-and-cancel loops still stop. After a refusal,
  `Retry-After` says when to try again.
- **Concurrency.** These are unchanged:
  - 2 uploads at a time per person;
  - 8 on the server;
  - 1 GiB of uploads in flight;
  - 10 direct uploads open per person.
- **Cleanup.** Also unchanged: abandoned direct uploads (6 hours) are aborted and their parts and objects deleted by
  the retention job. The bucket also aborts incomplete multipart uploads after a day. Recorded files are never touched.

## Message history

- **New accounts** get `NEW_ACCOUNT_HISTORY_BYTES`.
- **`HISTORY_TOTAL_BYTES`** (10 GiB) caps every account's history together. It lives in the database, on the VPS disk
  that about 17 other projects share. Past it, writes that would grow history get `507 history_full`. Writes that
  replace or delete items always go through.

## Realtime connections

- **`WS_FRAMES_PER_SECOND`** (20) **and `WS_FRAME_BURST`** (100) set each connection's frame budget. Frames past it are
  ignored; a connection that keeps sending far past it is closed with code 4429. Apps send a few frames a second at
  most: pings, acknowledgements, typing.
- **`WS_MAX_SOCKETS_PER_DEVICE`** (4): a device opening more connections than this has its **oldest** one closed (code
  4409). Reconnects always work. The app ignores the close of a connection it has already replaced.

## Administrators: false positives

| Problem | Fix |
|---|---|
| A new employee is under new-account limits | Invite them to the workspace (accepting ends the limits), or `admin.js limits-end <username>` |
| See an account's limits, devices and usage (sizes only, no content) | `admin.js limits <username>` |
| An office can't register more people from one network | Use invitation codes, raise `REGISTRATION_PER_ADDRESS_*`, or wait (the windows slide) |
| Sign-up is paused for the day | Raise `REGISTRATION_OPEN_PER_DAY`, or hand out invitation codes |
| Someone has too many devices | Remove old ones in the app, or raise `MAX_DEVICES_PER_USER` |
| Any per-network or per-hour counter | They're in memory only and reset when the API restarts |

Run the commands as everywhere else:

```bash
docker compose -p griip-internal-messaging -f /opt/griip-internal-messaging/compose.yaml exec -T api node dist/admin.js limits <username>
```

Change a setting in `.env`, then restart only the API:

```bash
docker compose -p griip-internal-messaging -f /opt/griip-internal-messaging/compose.yaml up -d --no-deps api
```

## Privacy

- **Network addresses and user ids** are kept in memory only, for at most 24 hours, and never written to disk or logs.
- **The database** gains one column, `users.limited_until`, a time. The day's open registrations are counted from
  audit rows that already existed.
- **No email, phone or other personal data** is collected for these controls.

## Activating it

The changes ship as the next API release. Each step needs the owner's approval, and none happens automatically.
1. **Commit and check.** Commit, and wait for CI (**CI result**) to pass.
2. **Version.** Bump the API version, for example to 0.3.0, in all three places:
   - `server/src/app.ts` `VERSION`;
   - `server/package.json`;
   - the image tag in `deploy/compose.yaml`.

   The 0.2.0 image then stays available for rollback.
3. **Back up** the database on the VPS (`deploy/scripts/backup.sh`, or the snapshot in `RELEASE_PLAN.md` step 2).
4. **Deploy** with `TARGET=root@72.60.107.88 ./deploy/scripts/deploy.sh`. It syncs `server/` and `compose.yaml`, which
   now sets `TRUST_PROXY=loopback,uniquelocal`, and rebuilds and starts only the API. LiveKit isn't touched.
   - Migration `012` runs at start. It's additive: one column and one index.
   - No `.env` change is needed: the defaults apply.
   - Registration stays **open**, as the owner chose.
5. **Verify:**
   - **Health.** `/healthz` reports the new version. The start log has no `TRUST_PROXY=true` warning.
   - **Client address.** Send one request with a made-up `X-Forwarded-For: 198.51.100.1` from this Mac. The API's
     request log must show the Mac's real public address, not `198.51.100.1` and not `192.168.128.1`.
   - **New accounts.** Register a synthetic test account. `admin.js limits <username>` shows its new-account limits.
     End them with `limits-end`, or disable the account afterwards.
   - **Existing accounts.** An existing account signs in, sends a message and opens a file.

## Rollback

- **Settings.** Any single limit: set it to 0 in `.env` and restart `api`. Nothing else changes.
- **Code.** Retag the previous image and start it:
  ```bash
  docker compose -p griip-internal-messaging -f /opt/griip-internal-messaging/compose.yaml up -d --no-deps api
  ```
  Run this with the image tag in `compose.yaml` set back to `0.2.0`. Migration 012 is additive, so the old code runs on
  the new database and nothing needs restoring: it ignores `limited_until` and the index.
- **`TRUST_PROXY`.** Don't go back to `true`. If the client address is wrong after deploying (every request showing
  `192.168.128.1`), set `TRUST_PROXY` to the address nginx connects from and restart. The rate limits stay on in
  either case.
