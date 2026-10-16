# Server release plan (pending production releases)

Production runs Compose project `griip-internal-messaging` in `/opt/griip-internal-messaging` on the shared VPS (`DEPLOYMENT.md`).
**Every production release needs explicit owner approval.** This file records what is waiting, the exact diff, and how to undo it.

## Deployed baseline

- Server and deploy code last changed in commit `c99c860`, which was deployed on 2026-10-16 (`DEPLOYMENT.md`).
- `0a5d9ab` and `4073c51` changed only the Android app, the bot and docs.
- **Not re-confirmed:** reading production state from this workstation was blocked by tool policy during this task. Before deploying,
  compare `sha256sum server/src/*.ts` on the VPS with the baseline.

## Pending changes (in order)

| Change | Commit | Server files | Schema change | Status |
|---|---|---|---|---|
| T1.1 Demotion to Guest revokes branch, team and channel access | see `git log -- server/src/authz.ts` | `authz.ts` (`demoteToGuest`), `routes/orgs.ts` (role change notifies removed conversations) | none | **Pending approval.** Tests: 36/36 + tsc clean (2026-10-16) |
| T1.3 Evict removed members from live calls; cleanup in-flight guard | see `git log -- server/src/routes/calls.ts` | `routes/calls.ts` (`evictFromCall`, webhook check), `livekit.ts` (identities, removal outcome, 5 s timeouts), `jobs.ts` (reconciliation pass, in-flight guard), `routes/orgs.ts`, `routes/conversations.ts`, `routes/auth.ts` (call sites) | none | **Pending approval.** Tests: 44/44 + tsc clean. Smoke test after deploy: start a group call with Griip Bot and the phone, remove the bot from the group, confirm the bot's room is closed within seconds |

| T1.6 Invite-only registration, streamed uploads with quotas, container CPU/pids limits | see `git log -- server/src/invites.ts` | `invites.ts` (new), `routes/auth.ts`, `routes/orgs.ts`, `routes/attachments.ts`, `app.ts`, `config.ts`, `admin.ts`, `migrations/002_registration_gate.sql`; `deploy/compose.yaml`, `deploy/.env.example`, `deploy/scripts/verify.sh` | **002** adds `registration_invites` and an index (additive; the old code ignores both) | **Pending approval. Must ship together with an Android build whose onboarding sends an invite code**, otherwise new users can't register from the app. Before go-live, mint codes with `admin.js reg-invite`. Tests: 56/56 + tsc clean |
| T1.7 Presence visible only to people who share a conversation or org | see `git log -- server/test/presence.test.ts` | `authz.ts` (`canSeePresence`), `routes/users.ts` | none | **Pending approval.** Compatible with released apps (`/v1/presence` omits hidden users instead of returning nulls). Tests: 60/60 + tsc clean |
| T1.8 Activity feed filtered by each event's real scope | see `git log -- server/test/activity_scope.test.ts` | `routes/orgs.ts` (`activityVisibleTo`) | none | **Pending approval.** Tests: 64/64 + tsc clean |

Later tasks append rows here as they land.

## Procedure for an approved release

Run each step from the repository root on a clean, committed tree. `deploy.sh` rsyncs the *working tree*, so uncommitted edits would ship.

1. `git status --short server deploy` shows nothing. `cd server && npm test && npm run typecheck` pass.
2. Back up the database and tag the running image on the VPS. These steps are project-scoped and don't affect other containers:
   ```bash
   ssh root@72.60.107.88 'cd /opt/griip-internal-messaging && \
     docker compose -p griip-internal-messaging -f compose.yaml exec -T api \
       node -e "require(\"better-sqlite3\")(\"/data/griip.sqlite\").backup(\"/data/backup-$(date +%Y%m%d-%H%M).sqlite\").then(()=>console.log(\"ok\"))" && \
     docker tag griip-internal-messaging-api:0.1.0 griip-internal-messaging-api:rollback'
   ```
3. Deploy: `TARGET=root@72.60.107.88 ./deploy/scripts/deploy.sh`. It runs preflight, syncs, builds and starts the project and runs `verify.sh`.
4. Smoke test: `cd server && npx tsx test/e2e/prod-smoke.ts https://msg.griipbuddy.cloud` (note debt D25: it creates real `e2e.*` accounts).
   For T1.1, demote a disposable test member to Guest in a test org and confirm it loses its channels.
5. Record the deployed commit and results in this file and in `PROGRESS.md`.

## Rollback

- **Code:**
  ```bash
  ssh root@72.60.107.88 'cd /opt/griip-internal-messaging && \
    docker tag griip-internal-messaging-api:rollback griip-internal-messaging-api:0.1.0 && \
    docker compose -p griip-internal-messaging -f compose.yaml up -d --no-build api'
  ```
  Alternatively, check out the previous commit locally and re-run `deploy.sh`.
- **Data:** T1.1 has no schema change, so the old code runs on the new database. Memberships the new code removed during demotions are
  *not* restored by a code rollback. Re-grant them in the app, or restore the backup from step 2 if the whole release must be undone.
  Restoring discards every message queued since the backup, so prefer re-granting.
- Restore a backup only with the API stopped:
  ```bash
  docker compose -p griip-internal-messaging -f compose.yaml stop api
  ```
  Then copy the backup over `/data/griip.sqlite` inside the volume with a one-off container on the project's volume, and start `api` again.
  Never use global Docker cleanup.

## Risks to other services

- `deploy.sh` touches only `/opt/griip-internal-messaging` and the project's containers.
- The image rebuild uses CPU on the shared 2-vCPU host for about a minute, and the API restarts in a few seconds.
- Clients reconnect automatically, and queued messages wait in the database.
