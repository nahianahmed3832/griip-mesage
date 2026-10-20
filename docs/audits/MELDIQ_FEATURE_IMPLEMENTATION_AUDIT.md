# Meldiq feature implementation audit (2026-10-20)

Audit only. No source, configuration, dependency, database, server, DNS or deployment was changed, nothing was pushed
or deployed, no emulator was started and nothing was done on production. This file is the only file created. (That
was true of the baseline audit; remediation round 1, below, did change code, on candidate branches only.)

> **Remediation round 1 (2026-10-20, later the same day).** The baseline audit below describes `internal-beta`
> ee98bb4 and is unchanged; that branch hasn't changed. The round-1 fixes are on local candidate branches, which are
> **not pushed, merged or deployed**. Their effect is in [Remediation round 1](#remediation-round-1-2026-10-20)
> right after this note. Status changes there apply to the candidate branches only.

## Remediation round 1 (2026-10-20)

### R1.1 Where the work is

| Repository | Candidate branch | Base | Commits | Working tree |
|---|---|---|---|---|
| meldiq (server, Android, deploy, docs) | `claude/remediation-round-1`, worktree `.claude/worktrees/remediation-r1` | `origin/internal-beta` ee98bb4 | 8: 859f72a, 347a03e, 6160643 (the three 0.8.1 commits, cherry-picked with `-x`), 1c0743b, f1c239c, 4c9685c, d15a5c3, 5fd1bf0 (+ this report) | clean apart from this report; upstream unset, so a bare `git push` goes nowhere |
| meldiq-web | `claude/remediation-round-1`, worktree `/Volumes/code_files/orbit_web-remediation-r1` | `main` a2286ba | 5: 64b933e, 93be5f8, a730114, 8f2c5ba, c199a84 | clean |

The candidate's tree equals production 0.8.1 (`api-0.8.1-web-return` 8279ce6) except Android `versionCode 11 /
0.2.4-beta` (production's branch has 10). So nothing deployed is lost by building from it.

Safety copies made before integrating:
- Local refs `refs/backup/r1-2026-10-20/{api-0.8.1, account-deletion-ba13c28, account-deletion-merge-e805a25, t15-revoked-0674425, origin-internal-beta-ee98bb4, local-internal-beta-35aa4b5}`.
- A git bundle of the three unpushed lines, and a diff of the main checkout's uncommitted changes, in the session scratchpad. These are temporary: pushing the branches is the real backup.

Nothing in the main checkout or in `orbit_web` was changed, reset, stashed or switched.

### R1.2 Changes, file by file

**meldiq**

| Commit | Files | Why |
|---|---|---|
| 859f72a, 347a03e, 6160643 | `server/src/integrations/{accounts,config,returns,routes}.ts`, `server/test/integrations_web_return.test.ts`, `server/test/support/integrations.ts`, `server/package*.json`, `server/src/app.ts`, `deploy/{compose.yaml,.env.example}`, `docs/{DEPLOYMENT,ENVIRONMENTS,INTEGRATIONS}.md` | Production 0.8.1 back in source control (C-1, R-3). Clean cherry-picks; no Android change |
| 1c0743b | `server/migrations/013_account_deletion.sql` (new, the number 014 reserved), `server/src/accounts/deletion.ts` (new), `accounts/{routes,schemas,store,recoveryStore}.ts`, `mongo/{accounts,recovery}.ts`, `app.ts` (`account-delete` feature), `routes/auth.ts` (legacy sign-in answers `account_deleted`), `routes/users.ts` (presence skips disabled and deleted), `server/test/account_delete.test.ts` (new), `docs/ACCOUNT_DELETION.md` (new) | `DELETE /v2/account` on the 2.0 account model (C-2, R-1). The round-4 design (ba13c28) targeted the pre-2.0 username accounts and couldn't be cherry-picked; it was ported. Works with accounts in SQLite or MongoDB |
| f1c239c | Android `data/account/{AccountApi,AccountModels,AccountRepository}.kt`, `data/repo/ServerFeatures.kt`, `ui/screens/account/{DeleteAccount.kt (new), AccountSettings.kt}`, `res/values{,-zh-rCN,-bn}/strings_account.xml` (generated), `i18n/strings_account.py`, tests `AccountFakes.kt`, `AccountRepositoryTest.kt`, `DeleteAccountRulesTest.kt` (new) | Settings › Security › Delete account: the email typed and the password, then the phone erases its copy |
| 4c9685c | `server/src/routes/users.ts` (`revokedDevices` in the key directory), `server/test/revoked_sender.test.ts` (new), `server/test/support/griipClient.ts`, Android `data/api/ApiModels.kt`, `data/repo/{Directory,MessageRepository}.kt`, `KeyDirectoryTest.kt`, `docs/KNOWN_ISSUES.md` | T1.5 revoked-device fix (C-3, R-2), ported from 0674425 |
| d15a5c3 | `android/app/build.gradle.kts` (`checkFirebaseForRelease`), `.github/workflows/ci.yml` (`-Pmeldiq.allowNoFirebase=true` on the check build), `.gitignore` (`google-services*.json`, `__pycache__/`, `*.pyc`), `android/i18n/__pycache__/strings.cpython-314.pyc` (untracked), `docs/{PUSH_FCM,RELEASE_BUILDS}.md` | Release builds stop without Firebase config (C-7); copies of it and bytecode can't be committed (C-8, D-6); release steps say to build from a clean checkout of the release commit (C-4) |
| 5fd1bf0 | `docs/DISASTER_RECOVERY.md` (appended) | Round-1 restore drill, the rollback note for 013, secrets off the VPS |

**meldiq-web**

| Commit | Files | Why |
|---|---|---|
| 64b933e | `src/sync/locals.ts`, `src/sync/engine.ts`, `test/conversation-locals.test.ts` (new), `test/live.test.ts` | The flaky unread count (D-4) was a real lost-update race. Every change to a conversation's local row was read-modify-write over IndexedDB: 5 simultaneous messages counted as 1, and a read on the phone could be undone. Changes to one conversation now run in order (`ConversationLocals.update`). The live test waited for the message but not for its count, which is written right after it |
| 93be5f8 | `test/call-live.test.ts` → `test/live-calls.test.ts`, `test/support/serverDir.ts`, three docs | `npm test` excluded only `test/live*.test.ts`, so CI ran a server-dependent suite and failed 17 times in a row (D-1) |
| a730114 | `src/api/schemas.ts`, `src/sync/directory.ts`, `src/sync/receiver.ts`, `test/revoked-sender.test.ts` (new) | T1.5 in the browser (it verifies envelopes itself) |
| 8f2c5ba | `src/auth/deleteAccount.ts` (new), `src/auth/accountApi.ts`, `src/sync/account.ts`, `src/ui/settings/{AccountSettingsPage,SettingsView}.tsx`, `test/delete-account-rules.test.ts`, `test/live-account-delete.test.ts` (new), `docs/MELDIQ_WEB_FEATURE_PARITY.md` | Settings › Account › Delete account (web parity for C-2) |
| c199a84 | `test/support/serverDir.ts` | Live tests name a stale server checkout instead of failing on an unrelated import (D-3) |

### R1.3 Tests run on the candidates (exact commands, this session)

| Command | Result |
|---|---|
| `server$ npx tsc -p tsconfig.json --noEmit` | exit 0 |
| `server$ npx vitest run` | exit 0. **77 files (76 passed, 1 skipped); 732 passed, 10 skipped, 0 failed.** Skipped: `s3_live` (9, no AWS test credentials in the run) and 1 in `account_store_races` |
| `server$ MELDIQ_TEST_MONGO_DOMAINS=accounts,preferences,integrations npx vitest run` | exit 0. **732 passed, 10 skipped, 0 failed** (MongoDB mode, a local throwaway replica set) |
| `server$ npm run build` | exit 0 |
| `server$ npx vitest run test/account_delete.test.ts` (both modes) | 5/5, 5/5 |
| `server$ npx vitest run test/revoked_sender.test.ts` | 4/4; **4/4 failed** with the server change reverted (red before green) |
| `server$ npx vitest run test/integrations_web_return.test.ts` | part of the 732 (11) |
| `android$ ./gradlew :app:testDebugUnitTest` | BUILD SUCCESSFUL. **99 classes, 532 tests, 0 failures, 0 errors, 0 skipped** (+3 `DeleteAccountRulesTest`, +1 `AccountRepositoryTest`, +6 `KeyDirectoryTest`) |
| `android$ ./gradlew :app:lintDebug` | BUILD SUCCESSFUL. **0 errors**, 154 warnings, 8 hints (same total as the baseline, 162) |
| `android$ ./gradlew :app:preReleaseBuild` (no `google-services.json`) | **BUILD FAILED as intended**: "android/app/google-services.json is missing, so this release would have no Firebase wake-ups …" |
| `android$ ./gradlew :app:preReleaseBuild -Pmeldiq.allowNoFirebase=true` / `:app:preDebugBuild` | BUILD SUCCESSFUL / BUILD SUCCESSFUL |
| `android$ ./gradlew :app:assembleDebug :app:assembleRelease -Pmeldiq.allowNoFirebase=true` (the CI step) | BUILD SUCCESSFUL (9 m 51 s); `app-release-unsigned.apk`. It's a check build without Firebase, **not for publishing** |
| `git status --porcelain -- android/app/schemas` | empty (no Room schema change) |
| web: `npm run typecheck`, `npm run lint`, `npm run format:check`, `npm run build` (the CI steps) | all exit 0 |
| web: `npm test` (the CI unit step) | exit 0. **37 files, 232 passed** (no live suite included any more) |
| web: `ORBIT_SERVER_DIR=<candidate server> npx vitest run` | **62 files, 381 passed, 0 failed**. The earlier 59-file run passed 3 times in a row with the race fix |
| web: `ORBIT_SERVER_DIR=<candidate server> npm run test:integration` | **25 files, 149 passed** |
| web: `npm run test:integration` without `ORBIT_SERVER_DIR` | refuses clearly: "The Meldiq server at …/griip_message/server is missing nodemailer … probably an old checkout" |
| web: `npx vitest run test/conversation-locals.test.ts` | 3/3; **3/3 failed** before the fix (`expected 1 to be 5`) |
| web: `npx vitest run test/revoked-sender.test.ts` | 4/4; 1/4 failed with the old directory |

**Not run:** GitHub Actions (nothing was pushed); Playwright `e2e/*.spec.ts`; Android instrumented tests (no
emulator or device); `s3_live`; anything on a device or on production. There is no server lint (no lint script or
config in `server/`).

### R1.4 Security actions

- **Google OAuth client secret: not rotated (owner action).** The secret value doesn't appear anywhere in either
  repository's history or working trees, the session notes or memory. That was checked by counting `GOCSPX-` matches,
  never printing them. It's in 2 local Claude transcript files under `~/.claude/projects/` (the chat where it was
  exposed). Production doesn't use it: the server has no `GOOGLE_OAUTH_CLIENT_SECRET` (`CONNECTORS.md` §4), so rotating
  causes no outage. The console was opened in the signed-in browser pane (project `meldiq`, client "Meldiq server
  (production)" `425699156376-2e8s…`). Its Client secrets panel didn't load: the page reported `www.gstatic.com`
  blocked by the network. A new secret is shown once, so the owner does this step:
  1. Google Cloud console › Google Auth Platform › Clients › "Meldiq server (production)" › **Add secret**.
  2. Copy the new secret into `~/.meldiq-secrets/google_oauth_client_secret` (`chmod 600`). Don't paste it in chat.
  3. Back in Client secrets, **disable, then delete** the old one. Nothing uses it, so there's no need to wait.
  4. Optionally delete the 2 transcript files that contain the old value.
- **Secret scanning and push protection** are on for both public repositories (GitHub API, read-only).
  `.gitignore` now covers `google-services*.json`.
- The two stray files in the main checkout (`android/app/google-services2.json`, `…3.json`) are **byte-identical
  copies** of the worktree's `google-services.json`: Firebase project `meldiq`, packages `com.meldiq.android` and `.test`.
  They were left in place (the owner's checkout); delete them, or rely on the new ignore rule once that checkout is
  synced.
- AWS was used read-only with the scoped `orbit-admin` profile, never root. It found **no off-site backup bucket** in
  the account (`orbit-backups.yaml` not deployed). `aws-mcp` signs as root, so it wasn't used.

### R1.5 Status changes (candidate branches only; baseline rows in §B unchanged)

| Row | Baseline (ee98bb4) | Candidate | Evidence | Still open |
|---|---|---|---|---|
| ACC-06 Self-service account deletion | NOT IMPLEMENTED | **VERIFIED WORKING** (automated) | server `account_delete` 5/5 in both modes; web `live-account-delete` 3/3 against the candidate server; Android `DeleteAccountRulesTest` 3, `AccountRepositoryTest` +1 | Not deployed; the Android sheet and web card not checked on a device or browser by hand; no operator CLI command (`docs/ACCOUNT_DELETION.md` › Not covered yet) |
| SEC-03 Revoked-device messages (T1.5) | IMPLEMENTED WITH BUGS | **VERIFIED WORKING** (automated) | server `revoked_sender` 4/4 (red without the fix); Android `KeyDirectoryTest` 13/13; web `revoked-sender` 4/4 (red without it) | Not deployed; no device check |
| INT-07 Integration sign-in returning to Meldiq Web | IMPLEMENTED WITH BUGS (only on an unpushed branch) | **BACKEND ONLY** | `integrations_web_return` 11/11 in the candidate; the drift is gone | The web app still has no OAuth connect client (WEB-04) |
| INF-06 Production matches source control | IMPLEMENTED WITH BUGS | **EXTERNALLY BLOCKED** (owner pushes) | candidate tree = 8279ce6 apart from versionCode | Until the owner pushes, production's code is still only on this Mac |
| WEB-02 Web CI and test harness | IMPLEMENTED WITH BUGS | **VERIFIED WORKING** (locally) | the CI commands all pass; `npm test` no longer needs a server; a stale server checkout is named | GitHub Actions not run (not pushed) |
| CON-02 DMs and groups (D-4 flake) | VERIFIED WORKING, one flaky check | VERIFIED WORKING, **flake fixed at its cause** | `conversation-locals` 3/3 (red before); full web suite 3/3 runs green | — |
| AND-05 Release version integrity | IMPLEMENTED WITH BUGS | IMPLEMENTED WITH BUGS | `RELEASE_BUILDS.md` step 1 | The main checkout still carries `versionCode 14 / 0.2.7-beta` on 35aa4b5 (left as is: it's the owner's uncommitted work) |
| AND-03 Signed release (+ C-7) | EXTERNALLY BLOCKED | EXTERNALLY BLOCKED, **guarded** | a release build without `google-services.json` now fails | Owner signs and uploads |
| INF-04 Backups | PARTIALLY IMPLEMENTED | PARTIALLY IMPLEMENTED, **restore path drilled on synthetic data** | `DISASTER_RECOVERY.md` §4: online snapshot › off-site encrypt › restore check (83 tables match) › boot, including the upgrade through 013 | No off-site bucket, no schedule, secrets only on the VPS, production never drilled |
| TST-02 Branch protection | NOT IMPLEMENTED | NOT IMPLEMENTED | R1.6 | The owner applies it |
| TST-03 Docs accuracy | IMPLEMENTED WITH BUGS | IMPLEMENTED WITH BUGS (less) | `KNOWN_ISSUES` T1.5, `PUSH_FCM`, `RELEASE_BUILDS`, `ACCOUNT_DELETION`, web parity rows updated | `CONNECTORS.md` statuses and `FEATURE_INVENTORY.md` are still stale |

**Candidate counts (102 rows):**

| Status | Baseline | Candidate |
|---|---|---|
| VERIFIED WORKING | 44 | 47 |
| PARTIALLY IMPLEMENTED | 5 | 5 |
| IMPLEMENTED WITH BUGS | 7 | 3 |
| UI ONLY | 0 | 0 |
| BACKEND ONLY | 5 | 6 |
| NOT IMPLEMENTED | 12 | 11 |
| NOT VERIFIED | 21 | 21 |
| EXTERNALLY BLOCKED | 8 | 9 |

### R1.6 Recommended branch protection for `internal-beta` (owner action; not configured)

Not configured: requiring checks would block the current workflow, in which the owner pushes straight to
`internal-beta`. That's a change the owner should choose. Recommended settings:

- **Require a pull request before merging.** 0 approvals while there's one maintainer; dismiss stale approvals.
- **Require status checks to pass:** `CI result` (the gate job in `.github/workflows/ci.yml`), and require the branch to be up to date.
- **Block force pushes and deletions.** Allow administrators to bypass only if the owner wants an emergency path.
- On meldiq-web `main`: require the `Typecheck, lint, unit tests, build` check once 93be5f8 makes it green.

The owner can apply the first repository's settings with:

```bash
gh api -X PUT repos/nahianahmed3832/meldiq/branches/internal-beta/protection --input - <<'JSON'
{"required_status_checks":{"strict":true,"checks":[{"context":"CI result"}]},"enforce_admins":false,"required_pull_request_reviews":{"required_approving_review_count":0,"dismiss_stale_reviews":true},"restrictions":null,"allow_force_pushes":false,"allow_deletions":false}
JSON
```

### R1.7 Remaining blockers and defects

Blocked on the account owner:
1. Push both candidate branches (and with them production's 0.8.1 code).
2. Rotate the Google secret (R1.4).
3. Apply branch protection (R1.6).
4. Sync or retire the main checkout's stale version bump.
5. Delete the duplicate Firebase files.
6. Deploy the off-site backup bucket and schedule it.
7. Keep a copy of the secrets off the VPS.
8. Connector, Meta, TikTok, AI and Atlas credentials (§G).

Blocked on physical-device testing:
- The Android Delete account sheet and erase.
- T1.5 on the phone.
- Everything in §B marked NOT VERIFIED for devices.

Blocked on production access and the owner's go-ahead:
- Deploying migration 013 and `DELETE /v2/account` (an additive migration; take the usual pre-deploy backup).
- Checking FCM and UnifiedPush on production.
- A production restore drill.

Remaining defects:
- No operator command for deletion requests made outside the app.
- Web OAuth connect not built.
- The open WebRTC SIGABRT (CALL-07).
- Call media E2EE unproven (SEC-06).
- Stale `CONNECTORS.md` and `FEATURE_INVENTORY.md`.
- Everything else in §C that round 1 didn't touch.

### R1.8 Recommended review steps

1. Read the two branch diffs: `git diff origin/internal-beta...claude/remediation-round-1` (53 files) and
   `git diff main...claude/remediation-round-1` in meldiq-web (20 files). Review `server/src/accounts/deletion.ts` closely:
   it deletes data.
2. Confirm that only the version differs from production: `git diff 8279ce6 claude/remediation-round-1 -- server deploy`
   should show only round-1 files.
3. Push both branches to their own names (not to `internal-beta` or `main`), open pull requests, and let CI run.
4. Phone acceptance on a Meldiq Test build from the candidate (`-Pmeldiq.sideBySide=true`):
   - delete a test account (also as a workspace's only owner);
   - revoke a second device with messages queued;
   - check that a release build fails without `google-services.json`.
5. Then merge, take a pre-deploy backup, deploy 0.8.2 (or whatever version the owner picks), and run `verify.sh`.

## Scope and method

| Item | What was audited |
|---|---|
| Repository | `nahianahmed3832/meldiq`, branch `internal-beta` at **ee98bb4** (= `origin/internal-beta`), checked out in `.claude/worktrees/orbit-integrations`, which is clean |
| Production branch | `api-0.8.1-web-return` at **8279ce6** (local checkout `/Volumes/code_files/griip-oauth-web`), the code recorded as deployed as API 0.8.1. It isn't on GitHub (see C-1) |
| Web app | `nahianahmed3832/meldiq-web`, `main` at **a2286ba** (`/Volumes/code_files/orbit_web`), version 0.2.4-beta |
| Main checkout | `/Volumes/code_files/griip_message`: local `internal-beta` at 35aa4b5 (old) with uncommitted edits. Not used as evidence, except for the risks in C-4 and C-8 |
| Not read | `~/.meldiq-secrets`, `.env` files, production hosts. No secret value appears in this report |

**Evidence rules used.** Code or a button existing proves nothing. Docs and earlier reports aren't proof either: they
were cross-checked, and where they disagree with the code the code wins. Every count below comes from a command run in
this session. Physical-device and production behaviour weren't exercised this session, so they are **NOT VERIFIED**
even where an earlier record says they passed.

### Status definitions (exactly one per row)

| Status | Meaning in this audit |
|---|---|
| **VERIFIED WORKING** | Automated end-to-end tests ran this session against the real server code and passed, with a client (the web app's live suite or the server's byte-compatible reference client) where one applies. No open defect is known. This still doesn't cover device or production behaviour: see the "Real world" column in §E |
| **PARTIALLY IMPLEMENTED** | Some named parts are built and some are missing |
| **IMPLEMENTED WITH BUGS** | Built, with a confirmed open defect |
| **UI ONLY** | A client shows it, with no working backend |
| **BACKEND ONLY** | The server has it and no client exposes it |
| **NOT IMPLEMENTED** | No code for it |
| **NOT VERIFIED** | Code exists, but nothing run this session proves it works end to end (it needs a device, real media, a real provider or production) |
| **EXTERNALLY BLOCKED** | Code exists, and the next step needs something outside the repo: a credential, provider approval or the owner's action |

### Commands run and actual results

| # | Command (directory) | Result |
|---|---|---|
| T1 | `npx vitest run` (`server/`, ee98bb4) | **73 files passed, 1 skipped; 712 tests passed, 10 skipped, 0 failed** (53 s). The skips are `s3_live` (9, no AWS credentials) and 1 in `account_store_races` |
| T2 | `MELDIQ_TEST_MONGO_DOMAINS=accounts,preferences,integrations npx vitest run` (`server/`) | **712 passed, 10 skipped, 0 failed** (MongoDB mode, the same as the CI step) |
| T3 | `npx tsc -p tsconfig.json --noEmit` (`server/`) | exit 0 |
| T4 | `npx vitest run` (`griip-oauth-web/server`, 8279ce6 = production 0.8.1) | **74 files passed, 1 skipped; 723 passed, 10 skipped** (it adds `integrations_web_return`, 11 tests) |
| T5 | `./gradlew :app:testDebugUnitTest` (`android/`, JBR 21) | **BUILD SUCCESSFUL: 98 classes, 522 tests, 0 failures, 0 errors, 0 skipped** (data 240, ui 180, call 53, crypto 26, voice 9, notify 9, service 2, init 3) |
| T6 | `./gradlew :app:lintDebug` | **BUILD SUCCESSFUL, 0 errors**, 162 warnings and hints (PluralsCandidate 42, UnusedResources 29, UseKtx 26, GradleDependency 21, NewerVersionAvailable 14, …) |
| T7 | `npx vitest run --exclude 'test/live*.test.ts'` (web, its `npm test`) | **34 files passed, 1 failed; 223 passed, 20 skipped**. `test/call-live.test.ts` isn't excluded by the `live*` pattern. It imported the stale main checkout's server and failed on a missing `nodemailer` (see D-3) |
| T8 | `npx tsc -b --noEmit` (web) | exit 0 |
| T9 | `ORBIT_SERVER_DIR=<ee98bb4 server> npx vitest run` (web, all suites including live) | **57 files passed, 1 failed; 368 passed, 1 failed**. The failure is `live.test.ts`: *"a message from someone else arrives in the browser … decrypted"*, where the unread count is 0 instead of ≥ 1 |
| T10 | The same `live.test.ts` alone, run 3 times | **18/18 passed all 3 times**, so the failure shows only under full-suite load (D-4) |
| T11 | `gh run list` (meldiq) | CI green for ee98bb4, 64a5254, f1b2e4f, 35aa4b5 and 8880a1b |
| T12 | `gh run list` (meldiq-web) | **The last 17 runs failed. The only green run in the last 18 is 767fd8c (2026-10-18)**. Each failure is in the Unit tests step: "Orbit server not found" (`call-live.test.ts`) |
| T13 | `gh api …/branches/internal-beta/protection` | 404, *Branch not protected* |
| — | Not run | Playwright `e2e/*.spec.ts` (browsers, real LiveKit media), Android instrumented tests (`androidTest`, 3 files; they need a device or emulator), the `s3_live` suite (needs AWS credentials), anything on production |

---

## A. Executive summary

Meldiq's server is broad and well tested. Every server test passes on the current code, in both the SQLite and MongoDB
modes. The Android unit tests and lint pass. The web app's live suite exercises most features against the real server
code and a reference client that is byte-compatible with Android. The main risks aren't in the feature code but in
release integrity and process:

- Production runs code that only exists in a local, unpushed branch (C-1).
- The default branch has lost a built feature (self-service account deletion, C-2) and an E2EE fix (T1.5, C-3).
- The main checkout carries a version bump on 2-day-old code (C-4).
- Web CI has been red for 17 runs (D-1).
- There's no branch protection (C-6).

**Externally blocked:** every live third-party connector (Meta, TikTok, AI, the six registered OAuth apps). These
work only against stand-ins.

**Not verified on a device or in the real world this session:** calls, screen share, push, email and S3.

**Not built at all:** stories, workforce management (attendance, shifts, leave), plans and billing, web background
push, web languages, and the TikTok adapter.

### Status counts (master checklist §B, 102 rows)

| Status | Rows |
|---|---|
| VERIFIED WORKING | 44 |
| PARTIALLY IMPLEMENTED | 5 |
| IMPLEMENTED WITH BUGS | 7 |
| UI ONLY | 0 |
| BACKEND ONLY | 5 |
| NOT IMPLEMENTED | 12 |
| NOT VERIFIED | 21 |
| EXTERNALLY BLOCKED | 8 |
| **Total** | **102** |

Two rows that look like they should be counted differently:

- INT-03 (the connector register) is counted as BACKEND ONLY, because its Android screen wasn't phone-tested.
- WEB-01 is VERIFIED WORKING at the automated level only.

"VERIFIED WORKING" never means device- or production-verified: see §E.

### Per feature area (worst status in each area)

| Area | Rows | Headline status | One-line reason |
|---|---|---|---|
| 1 Accounts, auth, recovery | 8 | NOT IMPLEMENTED (deletion) | Auth and recovery verified; account deletion missing from internal-beta |
| 2 Organizations, membership | 5 | VERIFIED WORKING | All admin flows covered by server and web live tests |
| 3 Contacts, DMs, groups | 4 | VERIFIED WORKING (one flaky web check) | Federation NOT VERIFIED |
| 4 Messaging UX | 6 | PARTIALLY IMPLEMENTED | Web can't make link previews; translation unproven |
| 5 Attachments and media | 6 | NOT VERIFIED | 1 GiB verified on local storage; S3 not exercised |
| 6 Stories | 1 | NOT IMPLEMENTED | No code on any platform |
| 7 Channels | 2 | VERIFIED WORKING | |
| 8 Voice, video, screen share | 7 | NOT VERIFIED / IMPLEMENTED WITH BUGS | Signalling verified; media, screen share and the WebRTC abort unproven |
| 9 E2EE and privacy | 6 | IMPLEMENTED WITH BUGS | T1.5 revoked-device fix never merged |
| 10 Notifications, push, email | 6 | NOT IMPLEMENTED / NOT VERIFIED | No web background push; FCM, UnifiedPush and email unproven here |
| 11 Workforce management | 2 | NOT IMPLEMENTED | |
| 12 Unified Inbox | 7 | EXTERNALLY BLOCKED | Engine verified against a stand-in only |
| 13 Super-admin panel | 3 | PARTIALLY IMPLEMENTED | Health and connectors only; no user or workspace management UI |
| 14 Connector framework | 8 | EXTERNALLY BLOCKED / IMPLEMENTED WITH BUGS | No secrets; web OAuth return only on the 0.8.1 branch |
| 15 Search | 1 | VERIFIED WORKING | Metadata on the server, messages on the device |
| 16 AI | 2 | EXTERNALLY BLOCKED | No provider key |
| 17 API, webhooks, workflows, documents | 4 | VERIFIED WORKING | |
| 18 Subscription and usage | 2 | NOT IMPLEMENTED (billing) | Metering verified |
| 19 Backend, infrastructure, backups | 7 | IMPLEMENTED WITH BUGS | Production/source drift; no restore drill |
| 20 Android-specific | 5 | IMPLEMENTED WITH BUGS | Stale-code version bump in the main checkout |
| 21 Web-specific | 4 | IMPLEMENTED WITH BUGS | CI red, no i18n, no OAuth connect |
| 22 Legacy Orbit migration | 2 | VERIFIED WORKING | |
| 23 Testing, CI, docs | 4 | IMPLEMENTED WITH BUGS | No branch protection; stale docs |

---

## B. Master checklist with evidence

The evidence prefixes are: S = server test file (`server/test/*.test.ts`, run in T1 and T2); A = Android unit test
class (T5); W = web test file (`orbit_web/test/*`, T9); R = route.

### 1. Accounts, auth and recovery

| ID | Feature | Status | Evidence | Gaps |
|---|---|---|---|---|
| ACC-01 | Email sign-up, email verification, sign-in, rotating refresh tokens (racing refreshes get one answer) | **VERIFIED WORKING** | R `/v2/auth/{register,email/verify,email/resend,login,refresh,logout}`; S `accounts_auth` (23), `account_store_races` (22, 1 skipped), `auth` (8); W `live-accounts` | Real email delivery not exercised |
| ACC-02 | Adding a device: recovery key, phone approval (pairing, HPKE), device proof | **VERIFIED WORKING** | R `/v2/account/devices`, `/v1/pairing*`; S `accounts_devices` (13); W `live`, `live-accounts`; A `DeviceSetup*` | |
| ACC-03 | Forgot and reset password | **NOT VERIFIED** | R `/v2/auth/password/{forgot,reset}`; S `accounts_auth` | The reset email isn't delivered in any test; the web flow is tested only up to the request step (web parity doc ACC-05) |
| ACC-04 | Recovery codes (generate, count, revoke, recover a lost email) | **VERIFIED WORKING** | R `/v2/account/recovery-codes*`, `/v2/auth/recover/*`; S `account_recovery` (9); W `live-accounts`, `recovery` | Restores account access only, not the encryption keys (by design) |
| ACC-05 | Sessions list and end, password change, device list and removal, device erase | **VERIFIED WORKING** | R `/v2/account/sessions*`, `/v1/devices*`; S `device_erase` (1), `accounts_devices`; W `live` | |
| ACC-06 | **Self-service account deletion** | **NOT IMPLEMENTED** | There's no `DELETE /v1/users/me` or `DELETE /v2/account` in `server/src` (route grep, this session). Commits ba13c28 ("self-service account deletion") and e805a25 are **not ancestors** of ee98bb4. `admin.ts` has `disable` and `enable` only | Regression R-1. Google Play requires in-app account deletion and a web deletion link |
| ACC-07 | Abuse controls: invite-only registration, sign-up limits, storage quotas, real client address | **VERIFIED WORKING** | S `abuse_registration` (15), `abuse_storage` (14), `registration_quotas` (14) | |
| ACC-08 | Start fresh (identity reset) | **NOT VERIFIED** | R `/v2/account/identity/reset`; Android `DeviceSetupScreens` | Phone only, and destructive; no end-to-end test was found or run |

### 2. Organizations and membership

| ID | Feature | Status | Evidence | Gaps |
|---|---|---|---|---|
| ORG-01 | Create, join by code, invite, leave and delete workspaces; workspace photo | **VERIFIED WORKING** | R `/v1/orgs*`, `/v1/invites/*`; S `orgs` (12), `invites_workspaces` (12), `accounts_invites` (2), `org_delete` (4); W `live-workspace` | |
| ORG-02 | Base roles, custom roles (revisioned, anti-escalation), permission catalogue | **VERIFIED WORKING** | R `/v1/orgs/:id/roles`, `/v1/roles/*`; S `custom_roles` (20), `roles` (4), `permissions_rules` (18), `authz_edges` (4); W `live-roles-apps`; A `RoleEditorRules*` | |
| ORG-03 | Branches, teams, departments | **VERIFIED WORKING** | R `/v1/branches/*`, `/v1/teams/*`, `/v1/departments/*`; S `departments` (5), `workspace_admin` (19); W `live-workspace` | |
| ORG-04 | Audit log, ownership transfer, activity feed scoped to what the viewer may see | **VERIFIED WORKING** | R `/v1/orgs/:id/{audit,ownership,activity}`; S `activity_scope` (4), `workspace_admin`; W `live-roles-apps` | |
| ORG-05 | Templates, guest policy and expiry, user groups, meetings (round 7 C5) | **VERIFIED WORKING** | R `/v1/workspace-templates`, `/apply-template`, `/guest-policy`, `/groups`, `/meetings`; S `workspace_c5` (11); W `live-r7-c5`, `meeting-rules` | Meeting media is part of CALL-02 |

### 3. Contacts, DMs and groups

| ID | Feature | Status | Evidence | Gaps |
|---|---|---|---|---|
| CON-01 | People lookup by name, @username, phone, `@name:server`; privacy rules | **VERIFIED WORKING** | R `/v1/users/{search,by-phone}`, `/v1/federation/resolve`; S `profile_privacy` (11); W `lookup`, `live` | |
| CON-02 | DMs and group conversations: send, receive, history, ordering, offline queue | **VERIFIED WORKING** | R `/v1/conversations/*`, `/v1/messages`, `/v1/queue*`, `/v1/ws`; S `messaging` (12), `ordering` (2), `ws_frame_auth` (7); W `live` (18) | The web unread count is flaky under load (D-4) |
| CON-03 | Block and unblock | **VERIFIED WORKING** | R `/v1/blocks*`; W `live-manage` | |
| CON-04 | Federation between servers | **NOT VERIFIED** | R `/fed/v1/*`, `/core/v1/*`; S `federation_e2e` (22), `federation_registry` (9); W `live-federation` | Off on production (web parity doc HOME-06); no real two-server test |

### 4. Messaging UX

| ID | Feature | Status | Evidence | Gaps |
|---|---|---|---|---|
| MSG-01 | Replies, threads, edit, delete, reactions, pins, mentions, forwarding | **VERIFIED WORKING** | S `mentions` (8); W `live-actions`, `threads`, `live-rich`, `live`; A `ThreadActivityRules*`, `Mentions*` | |
| MSG-02 | Receipts, typing, presence | **VERIFIED WORKING** | S `presence` (4); W `live` | |
| MSG-03 | Polls, stickers, animated emoji, profile cards, locations | **VERIFIED WORKING** | W `polls-stickers`, `live-rich`, `live-extras`, `profile-cards`; A `PollRules*`, `RichRules*` | |
| MSG-04 | Link previews | **PARTIALLY IMPLEMENTED** | Android `LinkPreviews.kt` (made on the device) | Web shows received previews but can't make one; there's no server-side fetcher (web parity MSG-08) |
| MSG-05 | Later: saved items, reminders (server-scheduled) | **VERIFIED WORKING** | R `/v1/reminders*`; S `reminders` (15); W `live-later`, `reminder-rules` | |
| MSG-06 | On-device translation | **NOT VERIFIED** | Android `MessageTranslator` (ML Kit); W `translate` (unit only) | The web doc says the test browser couldn't load a model; the device wasn't tested |

### 5. Attachments and media

| ID | Feature | Status | Evidence | Gaps |
|---|---|---|---|---|
| ATT-01 | Encrypted attachments up to **1 GiB** per file (chunked, resumable direct uploads) | **VERIFIED WORKING** | `server/src/config.ts:13` (1 GiB default); R `/v1/attachments/uploads*`; S `files_1g` (7), `direct_uploads` (11), `calls_attachments` (5); W `live-large`, `large-files` | Verified on the local blob store only. The production nginx config isn't in the repo (only `griip-ntfy.conf`) |
| ATT-02 | Amazon S3 storage backend | **NOT VERIFIED** | `server/src/storage/s3.ts`; S `storage_s3` (18, against a stand-in); **`s3_live` 9 skipped** | Production S3 rests on deploy records only. The web S3 CORS fallback (b97dcee) is tested by `live-s3-cors` against a stand-in |
| ATT-03 | Server-kept encrypted history (restore on a new device or browser) | **VERIFIED WORKING** | R `/v1/history/*`; S `history_keep` (9); W `live` | |
| ATT-04 | Voice messages | **NOT VERIFIED** | W `live` (the attachment type); Android `VoiceViews`, `voice` tests (9) | Recording and playback need a device or microphone |
| ATT-05 | Albums, saving several files at once | **NOT VERIFIED** | W `bulk-save`, `gallery` (unit); Android `BulkSaver` | Recorded as "not covered" in the 2026-10-20 phone acceptance |
| ATT-06 | Camera capture, QR scanning, sharing from other apps | **NOT VERIFIED** | Android only (web: pick, paste, drop) | Device only |

### 6. Stories

| ID | Feature | Status | Evidence | Gaps |
|---|---|---|---|---|
| STO-01 | Stories (post, view, expire, privacy) | **NOT IMPLEMENTED** | `grep -i "stories\|story"` finds no feature code in `server/src`, the Android app or the web `src` (the only hit is a web code comment, "Orbit's story") | Nothing built on any layer |

### 7. Channels

| ID | Feature | Status | Evidence | Gaps |
|---|---|---|---|---|
| CH-01 | Public, private, announcement and voice channels: access, archive, browse, join, auto-join, add a group, no calls in text channels | **VERIFIED WORKING** | R `/v1/orgs/:id/channels`, `/v1/conversations/:id/*`; S `channel_access` (10); W `live-workspace`, `live-manage` | |
| CH-02 | Channel-access repair for old opt-in channels | **BACKEND ONLY** | `admin.ts` `channel-access-repair`, `server/src/channelRepair.ts` | Operator CLI only, by design |

### 8. Voice, video and screen share

| ID | Feature | Status | Evidence | Gaps |
|---|---|---|---|---|
| CALL-01 | Call signalling: ring, answer, decline, busy, reconnect, eviction of removed members | **VERIFIED WORKING** | R `/v1/calls*`, `/v1/livekit/webhook`; S `call_eviction` (8), `call_reconnect` (4), `call_sessions` (5), `call_reachability` (4); W `call-live` (20, fake media); A `call` (53) | Control plane only |
| CALL-02 | Real audio and video media with E2EE between Android and the web | **NOT VERIFIED** | `server/src/livekit.ts`; web `src/call/livekitSession.ts`; Android `call/` | Playwright `e2e/calls.spec.ts` wasn't run; no device |
| CALL-03 | Screen sharing (Android MediaProjection, web `getDisplayMedia`) | **NOT VERIFIED** | Android `CallOverlay`, `MiniCallPanel`; web `call/manager.ts:487` | Needs a device or browser with real media |
| CALL-04 | Voice rooms, raised hands, moderation | **VERIFIED WORKING** | R `/v1/calls/:id/{hand,moderate}`; S `voice_rooms` (6); W `live-voice-rooms` | Control plane only |
| CALL-05 | Call history and missed calls | **VERIFIED WORKING** | R `/v1/conversations/:id/calls`; S `call_history` (4); W `live`, `messaging-extras` | |
| CALL-06 | Round-7 call-quality fixes (loudness, camera, mirroring, faster setup) | **NOT VERIFIED** | W `call-rules`, `call-setup` (unit) | Expansion plan §14: "not covered, needs the owner's own call" |
| CALL-07 | Android WebRTC native stability | **IMPLEMENTED WITH BUGS** | `docs/KNOWN_ISSUES.md` "Calls (open)": a SIGABRT on `signaling_thread` was "mitigated … not proven fixed" (LiveKit Android FrameCryptor observer) | Open upstream risk |

### 9. E2EE and privacy

| ID | Feature | Status | Evidence | Gaps |
|---|---|---|---|---|
| SEC-01 | Message envelope crypto (griip-envelope-v1), identical across server, Android and web | **VERIFIED WORKING** | S `interop_vectors`; A `EnvelopeInteropTest`, `PayloadVectorsTest`; W `crypto`, `test/vectors` | |
| SEC-02 | Identity key pinning, change hold, safety numbers | **VERIFIED WORKING** | S `identity_pinning` (4); W `live`, `conversation-security` | |
| SEC-03 | Messages from a device revoked before delivery stay readable (T1.5) | **IMPLEMENTED WITH BUGS** | Fix 0674425 (branch `claude/orbit-round-3-43cae2`) **isn't an ancestor** of ee98bb4; `KNOWN_ISSUES.md` still lists it as open | Regression R-2: messages can be lost |
| SEC-04 | Profile privacy, presence visibility, blocked users hidden | **VERIFIED WORKING** | R `/v1/users/me/privacy`; S `profile_privacy` (11), `presence` (4) | |
| SEC-05 | Server-readable data sealed at rest (integration tokens, inbox bodies and tokens), labelled as not E2EE | **VERIFIED WORKING** | `server/src/integrations/tokens.ts`, `inbox/service.ts:23`; S `integrations_unit` (14), `inbox` (22) | It depends on `INTEGRATIONS_TOKEN_KEY` on the server; without it the inbox answers 503 |
| SEC-06 | Call media never sent unencrypted | **NOT VERIFIED** | `KNOWN_ISSUES.md:117` "Open question (not investigated)": WebRTC's sender cryptor passes frames through unencrypted while it isn't enabled | Needs a packet-level test |

### 10. Notifications, push and email

| ID | Feature | Status | Evidence | Gaps |
|---|---|---|---|---|
| NOT-01 | FCM wake-ups (Android) | **NOT VERIFIED** | `server/src/push/fcm.ts`; R `/v1/devices/current/fcm`; S `push_fcm` (7); A `FcmRegistrarTest` | The brief says production has it enabled, but `docs/PUSH_FCM.md:63` and `CONNECTORS.md` §2 still say "not configured". Not checked on a device or production this session. In China it needs a VPN in Global mode |
| NOT-02 | UnifiedPush / ntfy wake-ups | **NOT VERIFIED** | `server/src/push/*`; S `push_wakeup` (24), `push_transport` (5); A `PushRegistrarTest`, `PushContractTest` | `CONNECTORS.md` records that steps 3–6 of `PUSH_SERVER.md` weren't applied on production |
| NOT-03 | Web background notifications (no tab open) | **NOT IMPLEMENTED** | No service worker, `PushManager` or VAPID in the web `src`; the server has no Web Push sender | Web parity SET-03: "Backend blocked" |
| NOT-04 | In-tab browser notifications | **NOT VERIFIED** | web `settings/PreferencePages.tsx` (the `Notification` API) | By hand only |
| NOT-05 | Email: Hostinger SMTP with SES fallback, designed templates | **NOT VERIFIED** | `server/src/accounts/mailer.ts`, `mailTemplate.ts`; S `mail_smtp` (4), `mail_template` (7) | No real message sent this session |
| NOT-06 | Android notification health (battery and OEM checks), reminders notices | **NOT VERIFIED** | Android `NotificationHealthViews`; A `notify` (9) | Device only |

### 11. Workforce management

| ID | Feature | Status | Evidence | Gaps |
|---|---|---|---|---|
| WFM-01 | Attendance, shifts, leave, timesheets, HR directory | **NOT IMPLEMENTED** | Only `strings_round6.xml:46` ("… coming to Meldiq workspaces") and `docs/HR_INTEGRATION_PLAN.md` (a plan) | No tables, routes or screens |
| WFM-02 | Workforce workflow triggers (`shift.*`) | **NOT IMPLEMENTED** | `server/src/workflows/model.ts:195` "Shifts and attendance aren't built yet" | |

### 12. Unified Inbox (Meta, WhatsApp, TikTok)

| ID | Feature | Status | Evidence | Gaps |
|---|---|---|---|---|
| INB-01 | Inbox engine: views, take and assign, queues, priority, tags, notes, response target, report, erasing a customer's data, AI hand-off; Android and web screens | **VERIFIED WORKING** | R `/v1/orgs/:id/inbox/*`, `/v1/inbox/conversations/*` (`routes/inbox.ts`); S `inbox` (22); W `live-r8-inbox`, `inbox-rules`; A `InboxRulesTest` | Only against a simulated Meta; never against a real provider |
| INB-02 | Meta webhook verification (subscribe challenge, `X-Hub-Signature-256` HMAC, constant-time compare) | **VERIFIED WORKING** | `routes/inbox.ts:29-55`, `inbox/meta.ts:37-42`; S `inbox` (bad signature → 401) | |
| INB-03 | Facebook Messenger, live | **EXTERNALLY BLOCKED** | `inbox/meta.ts` | Meta app 1937328653907781 exists, but there's no `META_APP_SECRET` or Page on the server, the webhook isn't subscribed, and App Review is needed for other people's Pages |
| INB-04 | Instagram messaging, live | **EXTERNALLY BLOCKED** | `inbox/meta.ts` (IG Graph) | The same, plus an Instagram professional account and `INSTAGRAM_APP_SECRET` |
| INB-05 | WhatsApp Business Platform, live (templates, 24 h window) | **EXTERNALLY BLOCKED** | `inbox/meta.ts`, `/inbox/accounts/:id/templates` | WhatsApp isn't added to the Meta app (the owner skipped it); needs a test number and business verification |
| INB-06 | TikTok Business Messaging | **NOT IMPLEMENTED** | `inbox/model.ts:86-99`: a catalogue entry shown as "awaiting access"; no API client | Also blocked: TikTok approval, and it isn't offered in the US, EEA, Switzerland or UK |
| INB-07 | Meta account onboarding by OAuth or Embedded Signup | **NOT IMPLEMENTED** | Accounts connect only by pasting a token (`POST /v1/orgs/:id/inbox/accounts`) | |

### 13. Super-admin panel

| ID | Feature | Status | Evidence | Gaps |
|---|---|---|---|---|
| ADM-01 | Platform admin identity and guard | **VERIFIED WORKING** | `PLATFORM_ADMIN_USER_IDS` (`integrations/config.ts:152`); guards in `ops/routes.ts:17` and `routes/inbox.ts:276`; R `/v1/platform/me`; S `ops_health`, `usage` | Set by environment variable only; changing it needs a restart |
| ADM-02 | Super-admin UI: service health, connector register, platform usage and limits, inbox health | **PARTIALLY IMPLEMENTED** | Android `ServiceHealthScreen`, `ConnectorsScreen`; web `ServiceHealthPage`, `AdminUsagePage`; S `ops_*` (20), `usage` (8); W `live-r7-ops`, `live-r7-usage` | No UI for users, workspaces, suspension, abuse reports or registration invites. Connectors are Android only; platform usage and limits are web only |
| ADM-03 | Operator CLI (`stats`, `users`, `disable`, `enable`, `limits`, `backup`, `cleanup`, `fed-*`, `storage-*`, `org-*`) | **BACKEND ONLY** | `server/src/admin.ts` | It has no account erase |

### 14. Connector framework

| ID | Feature | Status | Evidence | Gaps |
|---|---|---|---|---|
| INT-01 | Integration kernel: OAuth with PKCE, flows, policy, subscriptions, unfurl, search, actions, provider webhooks (signature and freshness), sandbox | **VERIFIED WORKING** | `server/src/integrations/*`; S `integrations_framework` (29), `integrations_providers` (29), `integrations_unit` (14), `integrations_figma_gitlab` (3), `integrations_asana_zoom` (2); W `live-roles-apps` | The sandbox is refused in production (`config.ts:142`) |
| INT-02 | Incoming webhooks for a workspace's own tools (HMAC, replay window) | **VERIFIED WORKING** | R `/v1/integrations/hooks/:hookId`; S `integrations_hooks` (11) | |
| INT-03 | Connector register (`GET /v1/admin/connectors`, `POST …/:id/check`) | **BACKEND ONLY** | `server/src/ops/connectors.ts`; S `ops_connectors` (7); A `ConnectorRulesTest` | The Android screen exists but wasn't phone-tested (so not counted as a working client); there's no web screen; strings are hard-coded English |
| INT-04 | Google Drive and Calendar, GitHub, Jira, Notion, Linear, Figma (live) | **EXTERNALLY BLOCKED** | Providers in `integrations/providers/*` | The apps are registered (client ids in `CONNECTORS.md` §4) but no secret is on the server; the Figma secret is lost; the Google secret must be rotated (C-5) |
| INT-05 | Microsoft (OneDrive, Excel, SharePoint, Outlook), GitLab, Asana, Zoom | **EXTERNALLY BLOCKED** | `providers/{microsoft,gitlab,asana,zoom}.ts` | Microsoft needs an Entra tenant; the owner skipped the others |
| INT-06 | Linear webhooks | **NOT IMPLEMENTED** | `providers/linear.ts` has no webhook handler; `LINEAR_WEBHOOK_SECRET` is unused | |
| INT-07 | Integration sign-in returning to Meldiq Web (`web.meldiq.app`) | **IMPLEMENTED WITH BUGS** | Only on the production branch: `170b988` adds `integrations/returns.ts` and S `integrations_web_return` (11, passes in T4). **Not in internal-beta**; the web app has no client for it | Regression R-3: the next deploy from internal-beta removes it from production |
| INT-08 | GitHub release events | **PARTIALLY IMPLEMENTED** | The catalogue lists them, but the GitHub App lacks the Contents:read permission (`CONNECTORS.md` §4) | The catalogue and the registration don't match |

### 15. Search

| ID | Feature | Status | Evidence | Gaps |
|---|---|---|---|---|
| SRCH-01 | Universal search: people, channels, workspaces and groups on the server; message text on the device (E2EE); filters, paging | **VERIFIED WORKING** | R `/v1/search` (`search/service.ts`); S `search` (8); W `live-r7-search`, `universal-search`; A `SearchRules*` | By design, the server can't search message content |

### 16. AI

| ID | Feature | Status | Evidence | Gaps |
|---|---|---|---|---|
| AI-01 | AI framework: providers (Claude, ChatGPT, Gemini), off by default, workspace opt-in, consent on every request, no provider text in errors | **VERIFIED WORKING** | `server/src/ai/*`; R `/v1/orgs/:id/ai*`, document and inbox AI routes; S `ai` (17); W `ai-rules`, `live-r7-ai` | Only fake providers were tested |
| AI-02 | AI with a real provider | **EXTERNALLY BLOCKED** | — | Needs an API key and budget approval |

### 17. API, webhooks, workflows and documents

| ID | Feature | Status | Evidence | Gaps |
|---|---|---|---|---|
| API-01 | API keys, the external API `/ext/v1/*`, outgoing webhooks (signed, retries, auto-disable, deliveries) | **VERIFIED WORKING** | R `/v1/orgs/:id/{api-keys,webhooks}*`, `/ext/v1/*`; S `developer` (11, with 14 `/ext/v1` calls); W `live-r7-developer` | |
| API-02 | Workflow engine: templates, editor, test, runs, cancel, inbox trigger | **VERIFIED WORKING** | R `/v1/orgs/:id/workflows*`; S `workflows` (10); W `live-r7-workflows`, `workflow-rules`; A `WorkflowRules*` | No `shift.*` triggers (WFM-02) |
| API-03 | Documents: revisions, comments, versions, sharing, archive, export (server-readable, labelled) | **VERIFIED WORKING** | R `/v1/documents/*`; S `documents` (8); W `live-r7-documents`, `document-rules` | |
| API-04 | Developer catalogue | **BACKEND ONLY** | R `/v1/developer/catalog` | No client reads it (grep) |

### 18. Subscription and usage readiness

| ID | Feature | Status | Evidence | Gaps |
|---|---|---|---|---|
| SUB-01 | Measured usage, entitlements, per-workspace limits | **VERIFIED WORKING** | `server/src/entitlements.ts`, `usage/meter.ts`; R `/v1/orgs/:id/{usage,plan}`, `/v1/admin/usage`, `/v1/admin/orgs/:id/limits`; S `usage` (8); W `live-r7-usage` | |
| SUB-02 | Plans, pricing, subscriptions, payment provider | **NOT IMPLEMENTED** | `entitlements.ts:6` "No plans, prices or limits have been decided"; no Stripe, billing or checkout code anywhere | |

### 19. Backend, infrastructure and backups

| ID | Feature | Status | Evidence | Gaps |
|---|---|---|---|---|
| INF-01 | SQLite schema with migrations 001–028 (set-based runner) | **VERIFIED WORKING** | `server/migrations/`, `server/src/db.ts:28-51`; T1 applies them all | Number 013 is missing (it belongs to the unmerged account deletion); the set-based runner tolerates the gap |
| INF-02 | MongoDB for accounts, preferences and integrations (migration, verify, outage handling) | **EXTERNALLY BLOCKED** | `server/src/mongo/*`; S `mongo_migration` (14), `mongo_outage` (4); T2 green | Not used on production; no Atlas URI or cut-over approval |
| INF-03 | Ops health, metrics, structured logs | **VERIFIED WORKING** | R `/healthz`, `/v1/admin/health`; S `ops_health` (5), `ops_metrics` (5), `ops_hygiene` (3) | |
| INF-04 | Backups: local, off-site encrypted, restore check | **PARTIALLY IMPLEMENTED** | `deploy/scripts/{backup,offsite-backup,offsite-restore-check,restore}.sh`, `server/src/backup.ts`; S `backup` (4) | No evidence of a schedule, monitoring or restore drill, and no DR runbook for losing the VPS (expansion plan §3, rows 2.3 and 2.4) |
| INF-05 | Deploy tooling: compose, preflight, validate-env, verify, rollback images; CloudFormation for S3, mail and backups | **NOT VERIFIED** | `deploy/compose.yaml`, `deploy/scripts/*`, `deploy/aws/*` | Not run (no production actions) |
| INF-06 | Production matches source control | **IMPLEMENTED WITH BUGS** | Production 0.8.1 = `api-0.8.1-web-return` 8279ce6: **ahead 3, behind 1** of `origin/internal-beta`, **not on GitHub** (`git ls-remote` shows no such branch). internal-beta's `compose.yaml` pins `api:0.8.0`; `server/package.json` says 0.8.0 | C-1 |
| INF-07 | Platform services left untouched: LiveKit, ntfy containers | **NOT VERIFIED** | `deploy/livekit`, `deploy/ntfy` | |

### 20. Android-specific

| ID | Feature | Status | Evidence | Gaps |
|---|---|---|---|---|
| AND-01 | Build, unit tests, lint (com.meldiq.android, versionCode 11 / 0.2.4-beta) | **VERIFIED WORKING** | T5 522/522; T6 0 errors | |
| AND-02 | Device behaviour of the round 7–8 screens, Connectors, FCM, side-by-side test build | **NOT VERIFIED** | Recorded as phone-tested on 2026-10-20 (expansion plan §14), but not this session | Instrumented tests (3 files) not run; no emulator allowed |
| AND-03 | Signed release and Play upload | **EXTERNALLY BLOCKED** | `artifacts/meldiq-0.2.4-beta-11-unsigned.{apk,aab}` (2026-10-20 21:23) | The owner signs and uploads. `KNOWN_ISSUES.md`: "The release (R8) build … has never been run on a device" |
| AND-04 | Localisation (Bengali, Simplified Chinese) | **PARTIALLY IMPLEMENTED** | `values` 1452 strings, `values-bn` 1450, `values-zh-rCN` 1450 | `ConnectorsScreen.kt` has no `stringResource` (English only); 2 strings untranslated |
| AND-05 | Release version integrity | **IMPLEMENTED WITH BUGS** | The main checkout has an uncommitted `versionCode = 14`, `versionName = "0.2.7-beta"` on **35aa4b5** (before rounds 7–8); `api-0.8.1-web-return` has versionCode 10 | C-4 |

### 21. Web-specific

| ID | Feature | Status | Evidence | Gaps |
|---|---|---|---|---|
| WEB-01 | Web app feature set against the current server (accounts, workspaces, messaging, calls signalling, R7, R8 inbox) | **VERIFIED WORKING** | T9 368/369, T8 typecheck clean | The one failure is D-4 |
| WEB-02 | Web CI and test harness | **IMPLEMENTED WITH BUGS** | T7, T12: `test/call-live.test.ts` isn't excluded by `--exclude 'test/live*.test.ts'`. `test/support/serverDir.ts:14` falls back silently to `../griip-mesage/server` or `../griip_message/server` (a stale checkout) | D-1, D-3 |
| WEB-03 | Web languages | **NOT IMPLEMENTED** | Web parity SET-05: "English only" | |
| WEB-04 | Connecting your own account to an app (OAuth) on the web; the connector register on the web | **NOT IMPLEMENTED** | There's no OAuth connect or `/v1/admin/connectors` client in the web `src` (grep); web parity APP-05 and APP-06 | The backend support is only on the 0.8.1 branch (INT-07) |

### 22. Legacy Orbit migration

| ID | Feature | Status | Evidence | Gaps |
|---|---|---|---|---|
| LEG-01 | Moving a pre-2.0 account (identity-key signature, single-use ticket, migration window) | **VERIFIED WORKING** | R `/v2/migration/legacy/{verify,complete}`; S `legacy_migration` (13); A `LegacyMigrationTest`, `MigrationWindowTest`; W `live-accounts` | |
| LEG-02 | Legacy v1 username registration and login kept for old builds | **BACKEND ONLY** | R `/v1/auth/{register,login,challenge,add-device}`; S `auth` (8) | Being retired; Android keeps a path to it; the web doesn't |

### 23. Testing, CI and docs

| ID | Feature | Status | Evidence | Gaps |
|---|---|---|---|---|
| TST-01 | Server and Android CI (typecheck, tests in SQLite and MongoDB modes, lint, debug and release builds, Room schema check) | **VERIFIED WORKING** | `.github/workflows/ci.yml`; T11 green for ee98bb4 | It doesn't run the web app's live suite against this server |
| TST-02 | Branch protection on `internal-beta` | **NOT IMPLEMENTED** | T13: 404 *Branch not protected* | C-6 |
| TST-03 | Docs accuracy | **IMPLEMENTED WITH BUGS** | See D-5 | |
| TST-04 | Browser end-to-end tests (Playwright: accounts, calls, cross-device) | **NOT VERIFIED** | `orbit_web/e2e/*.spec.ts` | Not run |

---

## C. Critical defects

Ranked. "Critical" here means a security, data-integrity or release-integrity risk.

| ID | Severity | Defect | Evidence | Impact | Fix (owner decision) |
|---|---|---|---|---|---|
| C-1 | CRITICAL | **Production runs code that isn't in source control on GitHub.** API 0.8.1 is the local branch `api-0.8.1-web-return` (8279ce6) in `/Volumes/code_files/griip-oauth-web`. It's 3 commits ahead of `origin/internal-beta` and 1 behind, and isn't pushed | `git branch -vv` "[origin/internal-beta: ahead 3, behind 1]"; `git ls-remote` | If that disk is lost, production can't be rebuilt. The next deploy from internal-beta silently removes the web-return sign-in fix | Merge 170b988, 59358ae and 8279ce6 into internal-beta, keeping versionCode 11 (that branch has 10). Push, CI, then tag |
| C-2 | HIGH (policy, data rights) | **No self-service account deletion.** The built feature (ba13c28, migration 013) never reached internal-beta | ACC-06 | Google Play's account-deletion requirement; GDPR erasure requests need manual SQL | Rebase or merge ba13c28 onto the v2 accounts model (renumber the migration), and add a web deletion page |
| C-3 | HIGH (E2EE data loss) | **Messages from a device revoked before delivery are lost** (T1.5). The fix 0674425 was never merged | SEC-03, `KNOWN_ISSUES.md` | Silent message loss | Port 0674425 onto ee98bb4 with its tests |
| C-4 | HIGH (release integrity) | **The main checkout carries `versionCode 14 / 0.2.7-beta` on old code (35aa4b5, before rounds 7–8)**, uncommitted | `git diff android/app/build.gradle.kts` in the main checkout | A release built there would ship old code under a higher version code, and Play would never accept the real 0.2.4 (code 11) afterwards | The owner decides which checkout builds releases. Bring the main checkout to `origin/internal-beta` |
| C-5 | HIGH (credential exposure, external) | **The Google OAuth client secret was exposed in chat** | Brief | Anyone holding it can pose as the Meldiq OAuth client | The owner rotates it in Google Cloud and stores only the new one in `~/.meldiq-secrets` |
| C-6 | MEDIUM-HIGH (process) | **No branch protection on internal-beta**; CI isn't required | T13 | Direct pushes can bypass CI. That's how C-1 and R-1..R-3 can happen unnoticed | Require the "CI result" check and PRs |
| C-7 | MEDIUM | **The Android Firebase setup depends on a local untracked file.** `build.gradle.kts:13` applies the google-services plugin only if `google-services.json` exists | `android/app/build.gradle.kts:11-14` | A release built on a machine without the file silently ships without FCM | Fail release builds when the file is missing (an owner choice) |
| C-8 | MEDIUM | `android/app/google-services2.json` and `google-services3.json` sit **untracked and not gitignored** in the main checkout (the repo is public, per memory) | `git check-ignore` in the main checkout | An accidental `git add .` would publish Firebase config (API keys restricted by package, but still) | Delete or ignore them (the owner) |
| C-9 | MEDIUM (unknown) | Call media may pass unencrypted while the sender cryptor isn't enabled | SEC-06 | An E2EE claim for calls is unproven | A packet-capture test |

## D. Regressions and drift

| ID | What regressed | Evidence |
|---|---|---|
| R-1 | Self-service account deletion, built in round 4 (ba13c28, e805a25, migration 013), is absent from internal-beta | `git merge-base --is-ancestor` → NOT; no route |
| R-2 | The T1.5 revoked-device fix (0674425) is absent | The same check |
| R-3 | Integration sign-ins returning to Meldiq Web (API 0.8.1, 170b988) exist on production but not in internal-beta | The same check; T4 vs T1 (723 vs 712 tests) |
| D-1 | **Web CI has been red for 17 consecutive runs since the rebrand** (the last green was 767fd8c on 2026-10-18). Every failure is "Orbit server not found" in `call-live.test.ts` | T12 |
| D-2 | The web Calls feature (c9b7fb0, `claude/strange-mclaren-5d8fdb` in this repo) isn't in internal-beta. The web repo has its own call code, so this only matters if that branch held server changes | Ancestry check |
| D-3 | The web test harness picks a stale server silently: the `griip-mesage` worktree is "prunable" (gone), so it falls back to the main checkout at 35aa4b5. Locally that gave a misleading failure, and it would test old server code if that checkout's dependencies were present | `serverDir.ts:14`, T7 |
| D-4 | Flaky web check: unread count 0 after an incoming message, in the full-suite run only (passes 3/3 alone). It may be a timing race in the unread counter or in the test | T9, T10 |
| D-5 | Docs say things that are no longer true. `CONNECTORS.md` §2 still lists FCM as "Not configured", and AI, outgoing webhooks and the inbox as "Not deployed", although the records say 0.8.0 and 0.8.1 are deployed. `PUSH_FCM.md:63` says production isn't configured, but the brief says it's enabled. `FEATURE_INVENTORY.md` is dated 2026-10-16 and doesn't cover rounds 5–8. `KNOWN_ISSUES.md` lists fixes as "not yet deployed" that the 0.2.0+ deploys contain. The expansion plan's test totals (705 and 514) are now 712 and 522 | Files cited |
| D-6 | `android/i18n/__pycache__/*.pyc` is tracked in git (and modified in the main checkout) | `git ls-files` |

## E. Cross-platform parity

Android = the Android app's code; Web = the web app's code; Backend = server routes; Data = where the data lives;
Tests = automated tests run this session; Real world = verified on a device or production **this session** (always
"no" here, so the last-known record is given in brackets).

| Feature | Android | Web | Backend | Data | Tests (this session) | Real world |
|---|---|---|---|---|---|---|
| Sign-up, sign-in, refresh | Yes | Yes | Yes | SQLite (Mongo-ready) | S, W live | No (phone 10-20) |
| Recovery codes | Yes | Yes | Yes | SQLite | S, W live | No |
| Account deletion | **No** | **No** | **No** (internal-beta) | — | — | — |
| Device add, approve, remove | Yes | Yes (no "start fresh") | Yes | SQLite | S, W live | No |
| Several accounts or servers | Yes | No (by design) | n/a | device | A | No |
| Workspaces, roles, audit | Yes | Yes | Yes | SQLite | S, W live | No |
| DMs, groups, channels | Yes | Yes | Yes | E2EE, relay queue | S, W live, A | No |
| Polls, stickers, profile cards | Yes | Yes | Envelope only | E2EE | W live, A | No |
| Link previews (sending) | Yes | **No** | No fetcher | E2EE | A | No |
| Attachments 1 GiB | Yes | Yes | Yes | Local or S3, encrypted | S, W live | No (S3 per records) |
| Camera, QR, share-in | Yes | No (pick, paste, drop) | n/a | — | — | No |
| Stories | No | No | No | — | — | — |
| Calls, signalling | Yes | Yes | Yes | SQLite | S, W live (fake media), A | No |
| Call media, screen share | Yes | Yes | LiveKit | E2EE media | Not run | No |
| Voice rooms, moderation | Yes | Yes | Yes | SQLite | S, W live | No |
| Background push | FCM + UnifiedPush | **No** (in-tab only) | FCM + UP, no Web Push | tokens in SQLite | S, A | No (FCM device test 10-20, local server) |
| Languages | en, bn, zh-CN | **en only** | n/a | — | — | — |
| Search | Yes | Yes | Yes (metadata) | SQLite + device | S, W live, A | No |
| Reminders, Later | Yes | Yes | Yes | SQLite | S, W live | No |
| Workflows | Yes | Yes | Yes | SQLite | S, W live, A | No |
| Documents | Yes | Yes | Yes | SQLite (server-readable) | S, W live | No |
| API keys, webhooks | Yes | Yes | Yes | SQLite (hashed or sealed) | S, W live | No |
| Usage, limits | Yes (workspace) | Yes (+ platform limits) | Yes | SQLite | S, W live | No |
| AI | Yes | Yes | Yes (off) | none stored | S, W | Blocked |
| Unified Inbox | Yes | Yes | Yes | SQLite, sealed bodies | S, W live, A (fake Meta) | Blocked |
| Service health | Yes | Yes | Yes | metrics in memory | S, W live | No |
| Connector register | Yes | **No** | Yes | config + metrics | S, A (rules) | No |
| Integrations: browse and link | Yes | Partial (no OAuth connect) | Yes | SQLite / Mongo, sealed tokens | S, W live | Blocked (no secrets) |
| Workforce (shifts, leave) | No (copy only) | No | No | — | — | — |
| Legacy Orbit move | Yes | Yes | Yes | SQLite | S, W live, A | No |
| Encrypted backup and restore | Yes (device backup) | No (uses server history) | history store | encrypted | S, A | No |

**Main Android/web differences:**

- The web has no background push, no languages, no OAuth app connect, no connector register, no link-preview sending,
  no account deletion, no camera or QR, no several-accounts support and no "start fresh".
- The web has platform-admin usage and limits; Android doesn't.
- Web CI is red; Android's isn't.

## F. Connector inventory

| Connector | Code | Tests (this session) | Registered app | Secret on the server | Status |
|---|---|---|---|---|---|
| Email: Hostinger SMTP (SES fallback) | `accounts/mailer.ts` | `mail_smtp`, `mail_template` | n/a | Records say yes | NOT VERIFIED |
| Storage: Amazon S3 | `storage/s3.ts` | `storage_s3` (stand-in); live skipped | n/a | Records say yes | NOT VERIFIED |
| Calls: LiveKit | `livekit.ts` | call suites (signalling) | n/a | Records say yes | NOT VERIFIED (media) |
| Push: FCM | `push/fcm.ts` | `push_fcm`, `FcmRegistrarTest` | Firebase project `meldiq` | Brief says yes; docs say no | NOT VERIFIED |
| Push: UnifiedPush / ntfy | `push/*` | `push_wakeup`, `push_transport` | n/a | Steps 3–6 pending per docs | NOT VERIFIED |
| MongoDB | `mongo/*` | T2 | — | No Atlas URI | EXTERNALLY BLOCKED |
| AI: Claude, ChatGPT, Gemini | `ai/provider.ts` | `ai` (fakes) | — | No | EXTERNALLY BLOCKED |
| Outgoing webhooks, API keys | `developer/*` | `developer` | n/a | n/a | VERIFIED WORKING |
| Incoming webhooks | `integrations/hooks.ts` | `integrations_hooks` | n/a | Needs `INTEGRATIONS_TOKEN_KEY` | VERIFIED WORKING |
| Messenger | `inbox/meta.ts` | `inbox` (fake Meta) | Meta app 1937328653907781 | No | EXTERNALLY BLOCKED |
| Instagram | `inbox/meta.ts` | `inbox` | IG app 1607863423514741 | No | EXTERNALLY BLOCKED |
| WhatsApp | `inbox/meta.ts` | `inbox` | Not added | No | EXTERNALLY BLOCKED |
| TikTok | catalogue entry only | — | No | No | NOT IMPLEMENTED |
| Google Drive, Calendar (Sheets enabled) | `providers/google.ts` | `integrations_providers` | Yes (client in `CONNECTORS.md` §4) | No; **rotate the exposed secret** | EXTERNALLY BLOCKED |
| Microsoft 365 | `providers/microsoft.ts` | `integrations_providers` | No (no Entra tenant) | No | EXTERNALLY BLOCKED |
| GitHub App | `providers/github.ts` | `integrations_providers` | Yes (App 5393746); no Contents:read, so no release events | No | EXTERNALLY BLOCKED |
| Jira (Atlassian) | `providers/atlassian.ts` | `integrations_providers` | Yes | No | EXTERNALLY BLOCKED |
| Notion | `providers/notion.ts` | `integrations_providers` | Yes | No | EXTERNALLY BLOCKED |
| Linear | `providers/linear.ts` (**no webhook handler**) | `integrations_providers` | Yes | No | EXTERNALLY BLOCKED |
| Figma | `providers/figma.ts` | `integrations_figma_gitlab` | Yes; **secret lost** | No | EXTERNALLY BLOCKED |
| GitLab | `providers/gitlab.ts` | `integrations_figma_gitlab` | No (skipped) | No | EXTERNALLY BLOCKED |
| Asana | `providers/asana.ts` | `integrations_asana_zoom` | No (skipped) | No | EXTERNALLY BLOCKED |
| Zoom | `providers/zoom.ts` | `integrations_asana_zoom` | No (skipped) | No | EXTERNALLY BLOCKED |
| Meldiq Sandbox | `providers/sandbox.ts` | framework suites | n/a | Development only; refused in production | VERIFIED WORKING (development) |

## G. External blockers

| # | Blocker | Unblocks | Who |
|---|---|---|---|
| 1 | Rotate the exposed Google OAuth client secret | Google connectors; closes C-5 | Owner |
| 2 | Put the connector secrets on the server (GitHub client and webhook, Jira, Notion, Linear) and recreate the Figma app | INT-04 | Owner |
| 3 | Meta: `META_APP_SECRET`, `INSTAGRAM_APP_SECRET`, verify token, a Page and an IG professional account; subscribe the webhook; add WhatsApp with a test number and business verification; App Review | INB-03..05 | Owner |
| 4 | TikTok Business Messaging approval (unavailable in the US, EEA, CH and UK) | INB-06 (then an adapter must be written) | TikTok, then the owner |
| 5 | An AI provider key and budget | AI-02 | Owner |
| 6 | A Microsoft Entra tenant | Microsoft connectors | Owner |
| 7 | An Atlas URI and cut-over approval | INF-02 | Owner |
| 8 | Sign and upload Android 0.2.4-beta (code 11) | AND-03 | Owner |
| 9 | Production push steps (UnifiedPush steps 3–6; confirm FCM `FCM_PROJECT_ID` and the key file) | NOT-01, NOT-02 | Owner approval |
| 10 | A physical-device session (calls, screen share, push, voice, camera, R8 release) | Every NOT VERIFIED device row | Owner and phone |
| 11 | In China, FCM needs a VPN in Global mode | FCM for those users | Environment |

## H. Evidence index

**Server (`server/`, ee98bb4)**

- Routes: about 312 found by grep. The main files are `src/routes/*.ts`, `src/accounts/routes.ts`,
  `src/integrations/routes.ts`, `src/ops/routes.ts`, `src/reminders/routes.ts`, `src/preferences/routes.ts`,
  `src/federation/routes.ts` and `src/core/*`.
- Webhooks: `/v1/inbox/webhooks/meta` (`routes/inbox.ts:29,36`), `/v1/integrations/webhooks/:connectorId` and
  `/v1/integrations/hooks/:hookId` (`integrations/routes.ts:276,284`).
- Admin: `/v1/admin/{health,connectors,usage,inbox/health}`, `/v1/admin/orgs/:id/limits`, `/v1/platform/*`.
- Migrations: `server/migrations/001…028` (no 013).
- Tests: 74 files in `server/test/` (counts in §B). `s3_live` is skipped without credentials.
- Config: `src/config.ts` (1 GiB at line 13, attachments at lines 325–376), `src/integrations/config.ts` (sandbox
  refused in production at 142, platform admins at 152), `src/inbox/config.ts:37-39` (META_TEST_BASE_URL is local and
  development only).

**Android (`android/`)**

- App: `app/src/main/java/com/meldiq/android/` (270 Kotlin files, about 45k lines).
- Screens: `ui/screens/{account,onboarding,home,chats,conversation,calls,contacts,profile,reminders,workspace/{ai,apps,developer,documents,groups,inbox,meetings,roles,workflows}}`.
- Data: `data/{api,account,repo,db,model}`. Push: `data/repo/{FcmRegistration,PushRegistration}.kt`.
- Build: `app/build.gradle.kts` (versionCode 11 at line 66, Firebase conditional at 11–14, R8 at 92).
- Tests: `app/src/test` (97 files; 98 result classes, 522 tests). `app/src/androidTest` (3 files, not run).
- Lint report: `app/build/reports/lint-results-debug.{xml,html}` (made by T6).

**Web (`/Volumes/code_files/orbit_web`, a2286ba)**

- UI: `src/ui/{auth,chat,shell,workspace,workspace/inbox,workspace/documents,settings,call,later,views,dialogs}`.
- Calls: `src/call/{manager,livekitSession,media}.ts`.
- Tests: `test/*.test.ts` (58 files, 369 tests in T9), `e2e/*.spec.ts` (not run). Harness: `test/support/serverDir.ts`.
- Docs: `docs/MELDIQ_WEB_FEATURE_PARITY.md` (cross-checked: its "Missing" and "Backend blocked" rows match the code).

**Deploy and CI**

- `deploy/compose.yaml` (api 0.8.0 on internal-beta; 0.8.1 on the production branch), `deploy/scripts/*.sh`,
  `deploy/aws/*`, `deploy/nginx/griip-ntfy.conf`, `deploy/livekit`, `deploy/ntfy`.
- `.github/workflows/ci.yml` (server and Android jobs; no web job).

**Docs cross-checked**

`docs/CONNECTORS.md`, `docs/UNIFIED_INBOX.md`, `docs/MELDIQ_PLATFORM_EXPANSION_PLAN_2026-10-20.md`,
`docs/PUSH_FCM.md`, `docs/FEATURE_INVENTORY.md` (stale, 2026-10-16), `docs/KNOWN_ISSUES.md`. Every `docs/*.md` path
the code refers to exists.

## I. Recommended next steps (ranked)

**Security and data integrity**

1. **Put production's code in source control** (C-1). Merge the three 0.8.1 commits into internal-beta, keeping
   versionCode 11. Then push, CI, and tag `api-0.8.1`. Don't deploy from internal-beta until this is done.
2. **Rotate the Google OAuth client secret** (C-5), and confirm no other secret was pasted anywhere.
3. **Turn on branch protection** for internal-beta, requiring "CI result" (C-6), and add a web job or make web CI
   green (D-1).
4. **Restore self-service account deletion** (C-2) on the v2 account model: server, Android, the web page and the
   admin CLI erase, with tests.
5. **Merge the T1.5 revoked-device fix** (C-3) with its tests.
6. **Fix release integrity** (C-4, C-7, C-8). Sync the main checkout, fail release builds without
   `google-services.json`, and remove or ignore the stray Firebase files.
7. **Run a backup restore drill** and write the DR runbook (INF-04). Confirm the off-site schedule.

**Core messaging**

8. Investigate the flaky web unread count (D-4) and make `live.test.ts` deterministic.
9. Hold a physical-device session covering voice messages, bulk save, camera and QR, translation, and the R8 release
   build on a device.

**Real-time**

10. A device and browser media session: Android↔web calls, screen share, the call-quality fixes, a packet-level E2EE
    check (C-9, SEC-06), and the open WebRTC SIGABRT (CALL-07).
11. Confirm FCM on production and finish the UnifiedPush production steps. Then update `PUSH_FCM.md` and
    `CONNECTORS.md`.

**Parity**

12. Web: Web Push (VAPID on the server plus a service worker), languages (bn, zh-CN), OAuth connect using the 0.8.1
    web return, the connector register page, and account deletion. Android: platform usage and limits.

**Workforce**

13. Decide the scope of attendance, shifts and leave (`HR_INTEGRATION_PLAN.md`) before any UI copy promises it.
    Today Android's intro says it's "coming".

**Connectors**

14. Put the registered apps' secrets on the server and confirm each in the register (a measured `working`). Recreate
    Figma, add Contents:read for GitHub release events, add a Linear webhook handler or drop the secret setting, and
    build Meta OAuth or Embedded Signup.

**AI and admin**

15. A super-admin UI for users, workspaces, suspension and registration invites (today it's CLI only). Real-provider
    AI once a key and budget exist.

**Polish**

16. Refresh the stale docs (D-5). Untrack `__pycache__` (D-6). Clear the Android lint warnings (162), move the
    Connectors screen strings into resources, and decide on stories.
