# Engineering Task 001: CI and release readiness (2026-10-17)

**Status: a CI workflow is written and every check it runs passes locally. It hasn't run on GitHub yet: that needs
the commit pushed, which waits for the owner's approval.**

Nothing else changed:
- no application code, build configuration or dependency;
- no production system, AWS resource or credential.

Follows the audit [`ORBIT_PRODUCT_TECHNICAL_AUDIT_2026-10-17.md`](ORBIT_PRODUCT_TECHNICAL_AUDIT_2026-10-17.md),
plan step 1.

## 1. What existed

| Area | Found |
|---|---|
| Hosting | GitHub `nahianahmed3832/griip-mesage`, public. Default branch `internal-beta`. Work is pushed straight to it, with no pull requests. Actions are enabled |
| CI | **None**: no `.github/`. `internal-beta` has **no branch protection** |
| Server checks | `npm run typecheck`, `npm test` (vitest, 30 files), `npm run build` (`server/package.json`). Node pinned by `server/Dockerfile` (22.22.3) and `engines` (≥ 22) |
| Android checks | `:app:testDebugUnitTest` (JUnit 4, 56 classes), `:app:lintDebug` (Android Lint, the only static analysis; no detekt, ktlint or ESLint), `:app:assembleDebug`, `:app:assembleRelease` (R8). Gradle wrapper 8.13, AGP 8.13.2, Kotlin 2.2.21, JDK 17 bytecode |
| Shared protocol tests | `griip-envelope-v1` and payload vectors, committed under `android/app/src/test/resources/`. Server: `test/interop_vectors.test.ts`. Android: `EnvelopeInteropTest`, `PayloadVectorsTest` |
| Tests needing outside systems | Real S3 (`s3_live.test.ts`, skips itself without a bucket); production smoke and acceptance scripts; on-device tests (`src/androidTest`) |
| Build variants | `debug` (optional `-Porbit.sideBySide=true`) and `release`. No signing config in Gradle |

## 2. What was added

| File | Purpose |
|---|---|
| `.github/workflows/ci.yml` | The workflow ([`CI.md`](CI.md)). Jobs:<ul><li>**Server:** `npm ci`, typecheck, tests, build.</li><li>**Android:** unit tests, lint, debug and release builds, Room schema check, reports uploaded.</li><li>**CI result:** the one check to require.</li></ul>Settings:<ul><li>Read-only token, no secrets.</li><li>Actions pinned to commit SHAs.</li><li>npm and Gradle caching.</li><li>Nothing signed, published or deployed.</li></ul> |
| `docs/CI.md` | How CI works, what it covers and doesn't, how to make it a required check, running it locally, updating pins |
| `docs/RELEASE_BUILDS.md` | Debug vs release, how the server address is chosen, HTTPS enforcement and its gaps (R1–R4), signing and secret rules, the owner's release checklist |
| `docs/SECURITY_REMEDIATION.md` | Operational steps, not carried out: root access key, VPS key, registration and storage abuse, encrypted off-site backups and restore drills, debug builds and transport |
| `docs/ORBIT_CI_RELEASE_READINESS_2026-10-17.md` | This report |
| `README.md` | Two lines in the documentation list |

No minimal fixes were needed: every existing check already passed.

## 3. Commands run and results

All on the development Mac:
- macOS, Node 25.8.0;
- Android Studio JBR 21.0.10;
- Android SDK build-tools 36.0.0.

| Command | Result |
|---|---|
| `gh repo view`, `gh api …/actions/permissions`, `gh api …/branches/internal-beta/protection` | Public repository, Actions enabled, branch not protected |
| `gh api repos/<action>/releases`, `…/commits/<tag>` | Resolved the SHA pins. Each pin is a stable release at least about 3 weeks old (setup-node v7.1.0 and setup-java v6.1.0 were newer, so the previous releases were used) |
| `ruby -ryaml` parse of `ci.yml` | **PASS**: valid YAML, jobs `server`, `android`, `result` |
| `cd server && npm ci` | **PASS**: installs from the lockfile, 0 vulnerabilities reported |
| `npm run typecheck` | **PASS** |
| `npm test` (with `CI=true`) | **PASS**: 29 files passed and 1 skipped (real S3); 210 tests passed, 9 skipped; 12 s |
| `npx vitest run test/interop_vectors.test.ts --reporter=verbose` | **PASS**: the committed envelope vectors verify with the Node client |
| `npm run build` | **PASS** |
| `cd android && ./gradlew clean` | **PASS** |
| `./gradlew :app:testDebugUnitTest --no-build-cache` | **PASS**: 268 tests in 56 classes, 0 failures, 0 skipped; 78 s |
| `./gradlew :app:lintDebug --no-build-cache` | **PASS**: 0 errors, 79 warnings, 5 hints; 133 s |
| `./gradlew :app:assembleDebug :app:assembleRelease --no-build-cache` | **PASS**: 320 s, mostly R8 |
| `git status --porcelain -- app/schemas` | **PASS**: no change |
| `aapt2 dump xmltree` (manifest, network config) on both APKs | **Release:** not debuggable; TLS only, with HTTP allowed to `10.0.2.2`, `localhost` and `127.0.0.1`.<br>**Debug:** `debuggable=true`; HTTP to any host |
| `BuildConfig.java` (release, debug) | `DEFAULT_SERVER_URL = "https://msg.griipbuddy.cloud"` in both (no `local.properties` override) |
| `apksigner verify` on the release APK | `DOES NOT VERIFY`, as expected: CI and command-line release builds are unsigned |

A first run with Gradle's cache reported everything up to date. That's why the runs above are from `clean` with the
build cache off: they show what a fresh CI runner does.

**Could not run here:**
- **The workflow itself on GitHub.** That needs a push. Until it has run once on `ubuntu-24.04`, problems specific to
  the runner are unverified: Android SDK components, Linux-only test behaviour, timing.
- **Node 22.22.3.** It isn't installed on this Mac, which has only 25.8.0. Production builds and runs this code on
  22.22.3, but the tests themselves haven't run on 22 yet.
- **`actionlint`.** It isn't installed. The workflow was checked by parsing and review only.

## 4. Release readiness, in short

Details are in [`RELEASE_BUILDS.md`](RELEASE_BUILDS.md).
- **Release builds are sound.** They're minified, not debuggable, and HTTPS-only, apart from the three development
  hosts (R1).
- **The default server** comes from `local.properties` or `https://msg.griipbuddy.cloud`. Nothing stops an `http://`
  default from reaching a release (R2).
- **Signing is manual.** It's done in Android Studio with the owner's upload key, which is ignored by Git and never
  synced to the VPS, under Play App Signing. Keep it that way until CI signing is designed and approved.
- **What testers have.** They've received debug builds (R3), and the R8 build has never run on a phone (R4).
- **The rule:** employees get Play internal-testing release builds only, after the owner works through the checklist.

## 5. Security boundaries

**Nothing was changed.** No infrastructure was touched, no credential was rotated or disabled, and no credential value
was printed. The remediation for each risk is written up in [`SECURITY_REMEDIATION.md`](SECURITY_REMEDIATION.md), with
steps, checks and undo:
- the root key;
- the VPS key;
- registration;
- backups;
- debug builds and transport.

## 6. Unresolved and follow-up

1. **Push and watch the first run** (needs approval). Then require **CI result** in branch protection: a settings
   change only the owner can make ([`CI.md`](CI.md)).
2. **Close R1 and R2.** Small Android and Gradle changes, with tests.
3. **R4.** A Play internal-testing release on a physical phone.
4. **Add on-device tests to CI.** The emulator suite in `src/androidTest`, through an emulator action. This is slower,
   so give it its own job.
5. **Optional CI additions:**
   - a `docker build` of `server/`, so the production image is checked;
   - Dependabot (or Renovate) for action pins and dependencies;
   - `actionlint`.
6. **79 lint warnings.** Review them, then consider `warningsAsErrors` for chosen checks.
7. **Operational items** in `SECURITY_REMEDIATION.md`. Invite-only registration is ready to switch on today.
8. **Open work branches.** The push wake-up work (branch `claude/sleepy-leavitt-a7aeec`) and other branches will be
   checked by CI once they're based on a commit that contains `ci.yml`.

## 7. Recommended next task

**Engineering Task 002: the first release build for employees, through Play internal testing.**

**Why next.** CI now guards the code. The biggest remaining gap before employees is what's installed on their phones:
debuggable debug builds (audit S3).

**Scope:**
- fix R1 and R2, covered by CI;
- bump the version;
- the owner signs and uploads to the internal-testing track;
- a physical-phone smoke test of the R8 build (R4).

**Depends on:**
- this workflow being pushed and green;
- the owner (signing, the Play Console upload, choosing testers);
- the phone being connected.

**In parallel:** switching to invite-only registration (`SECURITY_REMEDIATION.md` §3) is a configuration change the
owner can approve independently.
