# Session handoff (2026-10-21, Ultimate Master Prompt round)

For the next session: where everything is, what was done and verified, what isn't, and exactly what comes next. The
plan is `MELDIQ_IMPLEMENTATION_PLAN.md`; the status of every feature is `MELDIQ_FEATURE_GAP_MATRIX.md`.

## 1. Repositories and branches

| Repository | Folder | Branch | State at hand-off |
|---|---|---|---|
| meldiq (Android, server, docs), `github.com/nahianahmed3832/meldiq` | **`/Volumes/code_files/meldiq_android`** (the owner signs from here) | `internal-beta` (local; a tag shares the name, so use `refs/heads/internal-beta`) | Fast-forwarded to this session's last commit. **Not pushed.** The owner's own uncommitted edit stays as it was: `android/app/build.gradle.kts` versionCode 20, versionName `0.4.0-beta` (lower than the committed `0.5.0-beta`; the owner's choice to confirm) |
| same | `.claude/worktrees/meldiq-android-rebuild-8c8540` | `claude/meldiq-android-rebuild-8c8540` | Where this session worked; same commits |
| meldiq-web, `github.com/nahianahmed3832/meldiq-web` | `/Volumes/code_files/orbit_web` | `main` | Was 11 commits behind `origin/main`; fast-forwarded (clean tree). Two new local commits: sign-in help (`0f462ed`) and emoji picker (`bd42c61`). **Not pushed.** The `orbit_web-meldiq-2` and `orbit_web-remediation-r1` worktrees were not touched |

The owner's rule (`CLAUDE.md` and memory) applies: work lands in `/Volumes/code_files/meldiq_android`. Never touch
`signed_file/` or `android/app/release/`. Checksums of `signed_file/` were verified unchanged.
`android/app/release/app-release.aab` was re-signed by the owner at 16:37.

## 2. Done this session, and how it was checked

| Work | Checked |
|---|---|
| **Sign-in and unlock show only the normal path.** Recovery choices behind "Having trouble signing in?" and "Can't access your email?" (Android and web) | Android: 61 account unit tests (5 new). Emulator walk-through: sign up → verify → new phone → sign in (clean) → help sheet → recovery keeps the email → unlock screen (clean) → other ways → emailed code unlock. Web: type check, lint, 255 Vitest, Playwright `accounts` (2) and `cross-device` (3) |
| **Full emoji picker** (Unicode 15.1, 1,898 emoji, search, categories, recent, skin tone), **More reactions**, reaction validation (Android and web) | Android `EmojiCatalogTest` (5) and emulator (search, tone, insert, send, reaction from Flags). Web `emoji.test.ts` (5), Playwright `emoji.spec` (found and fixed a category-jump bug) |
| **History movable to MongoDB** (store interface, MongoDB store, cascades, retention of orphans) | Server: full suite on SQLite and with accounts, preferences and history in MongoDB, 621 each at that point; `history_mongo.test.ts` (6) |
| **Member timeouts**: server permission, route, enforcement, voice eviction; Android sheet, tag, composer notice | Server `member_timeout.test.ts` (6) and full suite 627 passed. Android `TimeoutRulesTest` (4), 572 unit tests, lint. Emulator: moderator timed out a member; the timed-out member saw "You're timed out … until 7:10 PM" |
| Docs: Discord feature audit (with developer-docs evidence), gap matrix, full-stack spec, MongoDB migration, AWS architecture (from a read-only account check), realtime media plan, AI architecture, design system index, architecture and implementation plans | — |

**Final results:**
- Server: 627 passed, 10 skipped (SQLite, after the timeout change).
- Android: 572 unit tests, 0 failures, lint clean.
- Web: 260 Vitest; Playwright accounts, cross-device and emoji in Chromium.

## 3. Things the owner should know

1. **A test sign-up reached production.** The emulator still held an earlier install's data pointing at
   `https://msg.griipbuddy.cloud`. One sign-up for `ada@meldiq.test` went there before I noticed and switched to the
   local server. Production couldn't send the email, so the account is **unverified**: no keys, no access, and
   replaced by any later sign-up with that address. It was not deleted, because that needs the owner's go-ahead.
2. **The AWS tools use the root user's access key** (seen on 2026-10-21; already flagged on 2026-10-17). Only
   read-only calls were made. The fix is in `MELDIQ_AWS_ARCHITECTURE.md` §5.
3. **The media bucket has no CORS rules**, by design: the web goes through the nginx `/media` proxy.
4. **Server version is now 0.14.0** (migration 034 adds `org_members.timeout_until`). Not deployed. Deploys follow
   `docs/DEPLOYMENT.md` with a backup and the owner's go-ahead.
5. **Android database version 11** (`conversations.timedOutUntil`), a forward migration with the schema exported.
   Installing over 0.5.0-beta migrates in place.

## 4. Not done, blocked or unverified

- **MongoDB as the only database:** 2 of 9 live domains (accounts, preferences) plus history can run on MongoDB;
  about 640 statements remain. No production MongoDB exists (needs the owner). See the migration doc for the order.
- **AWS:** nothing created. IaC templates are specified, not written.
- **Media:**
  - the camera and screen-share fixes from 2026-10-20 are still awaiting a phone;
  - no quality measurements yet;
  - game streaming is specified only.
- **Phone tests:** no device was connected this session. Emulator only.
- **Web:**
  - community rebuild not started (W1);
  - timeouts not on the web yet;
  - Firefox and WebKit Playwright runs not done this time.
- **Specified only:** reports, slowmode, 2FA, custom emoji, onboarding questions, the encryption-compatible
  equivalents of automatic moderation, AI.
- **Translations:** the new Chinese and Bengali strings need native review.

## 5. Next steps, exactly

1. Owner: review and push `refs/heads/internal-beta` (meldiq) and `main` (meldiq-web). Decide versionCode and
   versionName. Deploy API 0.14.0 with the usual rehearsal.
2. Owner: phone checks (media plan §7); AWS root key (AWS doc §5); Atlas cluster (migration doc §7).
3. Engineering, next session:
   - **Reports (MOD-07):** server `reports` table and routes, Android report sheet and moderator queue, tests. Spec
     §2.2.
   - **Timeouts on the web:** Members › Time out, plus the composer notice.
   - **MongoDB files domain:** same recipe as history.
   - Then slowmode, message requests and 2FA.
