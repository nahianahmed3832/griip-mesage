# Release builds and signing

Reviewed 2026-10-17 (Engineering Task 001). This page covers what separates a release build from a debug build, how
the app picks its server, and how to make and sign a release that employees can be given.

**Rule:** employees get **signed release builds through Google Play internal testing**, never debug APKs. No release
goes to employees until the owner has reviewed its signing and configuration with the checklist below. CI builds
releases unsigned and never publishes them ([`CI.md`](CI.md)).

## Debug and release, side by side

Source: `android/app/build.gradle.kts`, `src/main` and `src/debug`.

| | Debug (`assembleDebug`) | Release (`assembleRelease`, `bundleRelease`) |
|---|---|---|
| Debuggable | **yes**: `run-as` and debuggers can reach the app's files and memory | no |
| Code | not minified | R8 minification and resource shrinking (`proguard-rules.pro`) |
| Network security config | `src/debug/res/xml/network_security_config.xml`: **plain HTTP to any host** | `src/main/res/xml/network_security_config.xml`: TLS only, with HTTP allowed to `10.0.2.2`, `localhost` and `127.0.0.1` |
| Server addresses typed in the app (`ServerAddress.parse`, `allowCleartext = BuildConfig.DEBUG`) | `http://` accepted anywhere, with a warning banner | `http://` refused except for the same three hosts: "This app version connects to servers over HTTPS only" |
| Server choice at sign-up (`showServerSetup`) | always shown | only in developer mode, or when the address isn't the default |
| Diagnostic logging (`CallDiag`, decode and history-sync failures) | on | off (`BuildConfig.DEBUG` checks) |
| Application id | `com.griipbuddy.Orbit`; `com.griipbuddy.Orbit.test` ("Orbit Test") with `-Porbit.sideBySide=true` | `com.griipbuddy.Orbit` |
| Signing | the machine's Android debug key | **none in Gradle**: the build is unsigned until the owner signs it |
| Extra libraries | Compose UI tooling and test manifest | not included |

Backups are off in both variants:
- `allowBackup="false"` and `fullBackupContent="false"`;
- `data_extraction_rules.xml` excludes every domain from cloud backup and device transfer.

## How the app picks its server

1. **Build time.** `DEFAULT_SERVER_URL` is `griip.serverUrl` from `android/local.properties` when present. Otherwise
   it's `https://msg.griipbuddy.cloud`. Both variants use the same rule.
2. **Run time.** Each account profile uses the first of these that exists (`GriipApp.kt`):
   - its signed-in session's server;
   - its saved server setting;
   - the server the profile was created for;
   - the build's default.
3. **Calls.** The LiveKit address isn't built in. The server returns it with each call token (`MediaGrant.url`, from
   the server's `LIVEKIT_PUBLIC_URL`; production: `wss://rtc.griipbuddy.cloud`).

### Does a release build enforce HTTPS?

**Mostly yes.**
- **API and real-time socket.** OkHttp honours the network security config, so a release build refuses plain HTTP and
  `ws://` to any host except the three development hosts.
- **Call signalling.** By reading only, not tested: LiveKit's signalling also runs on OkHttp, so the same rule applies.
- **Call media.** It's always DTLS-SRTP, plus the app's own frame encryption.

Gaps:

| # | Gap | Risk | Recommended fix (not done in this task) |
|---|---|---|---|
| R1 | The release config allows HTTP to `10.0.2.2`, `localhost` and `127.0.0.1`. On a phone, `10.0.2.2` is an ordinary private address on many office networks | Low: someone must type that address | Drop the exception from `src/main`. Debug builds already allow everything. Update `ServerProfilesTest` with it |
| R2 | Nothing stops a release build with an `http://` default. A `griip.serverUrl=http://…` line in `local.properties` would ship in the release | Medium: easy to miss on a developer machine | A Gradle check that fails release tasks unless `DEFAULT_SERVER_URL` starts with `https://`. Until then, use checklist step 3 |
| R3 | Test APKs handed out so far are **debug** builds: debuggable, HTTP to any host, signed with a developer machine's debug key | High for a staff rollout (audit S3) | Stop sending debug APKs to employees. Use Play internal testing |
| R4 | The R8 release build has run only on the emulator, never on a phone | Medium: a missing keep rule would crash at runtime | Install the Play internal-testing build on a physical phone and run the smoke test (step 8) before inviting anyone |

## Signing

### Current setup

- **Google Play App Signing.** Google holds the app signing key. The Play-installed app on the owner's phone is
  Play-signed. The owner holds the **upload key**, which only proves to Play that an upload is theirs.
- **The upload key** is kept by the owner in `signed_file/`, inside the repository folder but ignored by Git:
  - `.gitignore` ignores `signed_file/`, `*.jks`, `*.keystore`, `keystore.properties`, `*.aab` and `android/app/release/`;
  - `deploy.sh` syncs only `server/` to the VPS, so it never reaches the server;
  - the 2026-10-17 audit found no key material in tracked files or history.
- **Bundles are signed by hand** in Android Studio (**Build › Generate Signed App Bundle**). There's no `signingConfig`
  in Gradle, so command-line and CI release builds are unsigned. Keep it that way until CI signing is designed and
  approved (below).

### Rules for signing secrets

- **Never commit** a keystore, its passwords, `keystore.properties` or a signed `.aab`/`.apk`. The repository is
  **public**: anything pushed is public at once, and stays in history.
- **Passwords** belong in a password manager:
  - not in `gradle.properties`, `local.properties` or environment files;
  - not in shell history: let `jarsigner` and Android Studio prompt for them.
- **Move the upload key out of the repository folder.** For example, keep it in an encrypted disk image or the
  password manager's file storage, plus one offline backup. Inside the folder, a `git add -f`, a tool that ignores
  `.gitignore`, or a copy of the folder can leak it.
- **If the upload key leaks or is lost:** ask for an upload-key reset in **Play Console › App integrity**. The app
  signing key stays with Google, so installed apps keep updating.
- **Debug keys don't sign anything you hand out.** A debug key lives on a developer machine and isn't protected.

### CI signing later (design only, needs the owner's approval)

If releases are ever built in CI:
- **A protected environment.** Use a GitHub Environment `release` that the owner must approve for each run. Its secrets
  are the keystore (base64) and the passwords.
- **Manual runs only.** Trigger it with `workflow_dispatch`, on `internal-beta` only, never from pull requests.
- **Keystore on the runner.** Decode it into `$RUNNER_TEMP` and delete it at the end of the job.
- **Signing config.** Read it from environment variables only when they're set, so other builds stay unsigned.
- **Uploads.** Send bundles straight to Play's **internal testing** track, with a service account limited to that
  track. **Never** upload them as workflow artifacts: on a public repository, anyone signed in to GitHub can download
  those.

## Making a release for employees (owner)

1. **Start clean.** Check out the commit to release, with a clean tree and CI green. Make sure the server it needs is
   deployed.
2. **Bump the version.** Raise `versionCode` (Play refuses a code it has seen) and set `versionName` in
   `android/app/build.gradle.kts`. Commit it.
3. **Check the default server.** `android/local.properties` must have no `griip.serverUrl` line, or an `https://` one.
   After building, confirm the value:
   ```bash
   grep DEFAULT_SERVER_URL android/app/build/generated/source/buildConfig/release/com/griipbuddy/orbit/BuildConfig.java
   ```
4. **Build and sign.** Use Android Studio › **Build › Generate Signed App Bundle**: choose `release` and the upload key.
5. **Check the bundle's signer.** Run this on the bundle (Android Studio writes it to `android/app/release/`):
   ```bash
   keytool -printcert -jarfile android/app/release/app-release.aab
   ```
   The SHA-256 must match the **upload key certificate** in Play Console › App integrity.
6. **Check it isn't debuggable.** Build the same commit as an APK with `./gradlew :app:assembleRelease`, then dump its
   manifest:
   ```bash
   "$ANDROID_HOME/build-tools/36.0.0/aapt2" dump xmltree --file AndroidManifest.xml android/app/build/outputs/apk/release/app-release-unsigned.apk
   ```
   Look in the output: `android:debuggable` must not appear, and `android:networkSecurityConfig` must be there.
7. **Keep the R8 mapping.** Keep `android/app/build/outputs/mapping/release/mapping.txt` with the release, and upload
   it to Play so crash reports are readable. It isn't a secret, but it belongs with the release, not in Git.
8. **Upload to internal testing.** Upload to **Play Console › Testing › Internal testing**. Add testers by email list.
   Install it on a physical phone from Play, then check:
   - sign-in;
   - a message each way;
   - a photo, video, voice message and file;
   - a voice and a video call;
   - the app after a restart.
9. **Hand it to employees.** Only after that, and with the owner's approval, add employees to the testing list. Never
   promote to production without a separate decision.
