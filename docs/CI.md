# Continuous integration

Added 2026-10-17. GitHub Actions workflow: [`.github/workflows/ci.yml`](../.github/workflows/ci.yml).

## When it runs

- Every push to `internal-beta`, the default branch.
- Every pull request, whatever its branch.
- By hand, on any branch: **Actions › CI › Run workflow**.

A newer push to a pull request cancels that pull request's older run. Runs on `internal-beta` always finish.

## What it checks

It runs the commands developers already use (README › Build, [`TESTING.md`](TESTING.md)), with the versions the
repository pins. It adds no second build system.

| Job | Step | Command | What fails it |
|---|---|---|---|
| **Server** | Install | `npm ci` | `package-lock.json` disagrees with `package.json`, or an install fails |
| | Typecheck | `npm run typecheck` | any TypeScript error |
| | Tests | `npm test` | any failing vitest test, including the shared `griip-envelope-v1` vectors (`test/interop_vectors.test.ts`) |
| | Build | `npm run build` | the production build (`tsc`) fails |
| **Android** | Unit tests | `./gradlew :app:testDebugUnitTest` | any failing JVM test, including the shared vectors (`EnvelopeInteropTest`, `PayloadVectorsTest`) and the Room migration tests |
| | Lint | `./gradlew :app:lintDebug` | a lint **error** (warnings are reported, not fatal) |
| | Builds | `./gradlew :app:assembleDebug :app:assembleRelease` | either build fails, including R8 minification and release lint (`lintVitalRelease`) |
| | Schema | `git status --porcelain -- app/schemas` | the build changed Room's exported schema files and they weren't committed |
| **CI result** | | — | either job above didn't succeed |

Versions:
- **Server:** Node 22.22.3, the version in `server/Dockerfile`, which production runs.
- **Android:**
  - JDK 21 (Temurin), matching the Android Studio JBR used for local builds;
  - the Gradle wrapper in the repository (8.13), checked against Gradle's published checksums before it runs;
  - the Android SDK preinstalled on the runner. AGP fetches any missing platform.

## How failures show

- A failed step turns the job red, and its log ends at the failing command.
- Vitest reports each failing test with its file and line, and annotates it on the pull request.
- In the Android job, Lint and Builds run even after Unit tests fail, so one run lists every failure. The job still fails.
- Every Android run uploads **`android-reports`**, kept for 14 days. It holds the HTML and XML test results and the lint
  report.
- **CI result** is the single check to require before merging. It's red unless both jobs passed: a skipped, cancelled
  or failed job counts as a failure.

Nothing in the workflow uses `continue-on-error`, so a failed check can't be reported as a success.

### Make it a required check (owner, in GitHub settings)

`internal-beta` has no branch protection. CI runs, but nothing stops a red commit from landing. To make it binding:
1. Open **Settings › Branches › Add rule** (or **Rules › Rulesets**) for `internal-beta`.
2. Require the status check **CI result**.

To merge only through pull requests, also turn on **Require a pull request before merging**. Today work is pushed
straight to `internal-beta`, so that would change the workflow.

## Caching

- **npm:** downloads are cached by `actions/setup-node`, keyed on `server/package-lock.json`. `npm ci` still installs
  exactly the locked versions.
- **Gradle:** the user home (dependencies, wrapper) is cached by `gradle/actions/setup-gradle` with its open-source
  `basic` provider on `actions/cache`. Only runs on `internal-beta` write the cache, so a pull request can't change what
  later runs restore.
- Tasks always run in a fresh checkout: the cache holds downloaded dependencies, not test results.

## What CI never does

- **No secrets.**
  - The workflow reads none, and its token is read-only (`contents: read`). Checkout doesn't keep the token on disk.
  - Pull requests from forks run with the same read-only rights. The workflow uses `pull_request`, never
    `pull_request_target`.
- **No signing, publishing or deploying.** The release build is unsigned, and no APK or bundle is uploaded. The
  repository is public, so anything uploaded could be downloaded by anyone. Release builds are made and signed by the
  owner ([`RELEASE_BUILDS.md`](RELEASE_BUILDS.md)).
- **No AWS, VPS or production access.** The real-S3 suite (`test/s3_live.test.ts`) skips itself without
  `ORBIT_LIVE_S3_BUCKET` and credentials. Production smoke and acceptance scripts (`test/e2e`, `test/acceptance`) aren't run.

## What CI doesn't cover

These still need a person:
- On-device tests (`src/androidTest`) need an emulator. They'd be the next addition.
- A physical phone.
- Calls through LiveKit.
- The Docker image build.
- The Griip Bot.
- Production smoke tests.

## Same checks on your machine

```bash
cd server && npm ci && npm run typecheck && npm test && npm run build
```

```bash
cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

Gradle reuses earlier results. To re-run everything as a fresh runner would, add `--rerun-tasks`, or run
`./gradlew clean` first.

## Maintaining it

- **Pinned actions.** Each action is pinned to a full commit SHA, with its version in a comment. To update one:
  1. Look up the new release's commit:
     ```bash
     gh api repos/<owner>/<action>/commits/<tag> --jq .sha
     ```
  2. Replace both the SHA and the comment.
- **Version changes.** When the Node version in `server/Dockerfile` or the JDK changes, change it here in the same
  commit.
