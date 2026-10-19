# Orbit → Meldiq rebrand (2026-10-19)

> **Later the same day:** the owner chose the application id **`com.meldiq.android`** (matching the domain they bought,
> `meldiq.app`), replacing `com.griipbuddy.meldiq` below everywhere in code; the design, messaging and media round that
> followed is in [MELDIQ_ROUND5_2026-10-19.md](MELDIQ_ROUND5_2026-10-19.md). This report stays as the record of the
> first rebrand and its phone test.

Branch `worktree-orbit-integrations` (worktree `.claude/worktrees/orbit-integrations`), on top of the uncommitted
roles-and-integrations round (API 0.6.0, [report](ORBIT_INTEGRATIONS_REPORT_2026-10-19.md)). A snapshot of that state
was taken outside the repository before the rebrand. **Nothing is committed or pushed. Production, the VPS, DNS, AWS
and every database are unchanged. No emulator was used.** Infrastructure steps are in the
[infrastructure plan](MELDIQ_INFRASTRUCTURE_PLAN_2026-10-19.md); addresses in [ENVIRONMENTS.md](ENVIRONMENTS.md).

## 1. The rebrand

The product is **Meldiq** ("Meldiq by GriipBuddy") everywhere a person sees it: launcher label, splash, onboarding,
sign-in and account screens, settings and About, notifications and channel names, call screens and errors, file
sharing, gallery folders (`Pictures/Meldiq`, `Movies/Meldiq`), backups (`meldiq-backup-….meldiqbackup`), the app
directory and its texts, server emails ("… is your Meldiq verification code"), the browser pages of app sign-ins ("Return
to Meldiq"), and server error messages. All new texts exist in English, Simplified Chinese and Bengali (generated
tables). The visual design, the Cocoa palette, components, typography and navigation are unchanged.

New launcher icon and splash mark in the existing brand style (same deep-green tile and mint gradients): two
interlocking rings whose glowing overlap is the "meld", and a small spark; adaptive icon, monochrome (themed) icon and
status-bar icon, all vector, inside the adaptive-icon safe zone. The welcome screen's decoration follows it (two thin
overlapping rings instead of an orbit with a moon).

## 2. Android package identity

| | Before | After |
|---|---|---|
| Application id | `com.griipbuddy.Orbit` | **`com.griipbuddy.meldiq`** |
| Code namespace / Kotlin packages | `com.griipbuddy.orbit` | `com.griipbuddy.meldiq` (main, test and androidTest moved; Room schema folder moved) |
| Instrumentation id | `com.griipbuddy.orbit.androidtest` | `com.griipbuddy.meldiq.androidtest` |
| Side-by-side debug build | `-Porbit.sideBySide=true` → `….Orbit.test` "Orbit Test" | `-Pmeldiq.sideBySide=true` → `com.griipbuddy.meldiq.test` "Meldiq Test" |
| App links | `griip://invite`, `griip://integrations` | `meldiq://invite` (and `griip://invite` from older invitations), `meldiq://integrations` only |
| Push service, notification channels, preference files, WorkManager names, sounds | `OrbitPushService`, `orbit_*` | `MeldiqPushService`, `meldiq_*` (a new application id starts with empty storage, so nothing needs migrating) |
| Signing | debug key for test builds; upload key for releases (outside Git) | unchanged |

**Installing the test APK safely.** Because the id is new, Android installs Meldiq as a **separate app** beside Orbit
(`com.griipbuddy.Orbit`, the Play install) and "Orbit Test": nothing is replaced, uninstalled or overwritten, and the
Orbit app's data, keys and accounts stay where they are. Meldiq starts empty: sign in with the account (email and
password, then unlock with the recovery key or approve from a phone already signed in, Meldiq or Orbit), or create a
new test account on the test server. App data never moves between the two apps; server-side history and workspaces
come back after sign-in. `adb install` (not `-r`, which isn't needed) puts it on the phone; Android asks to confirm
("Continue"). A later Play release of Meldiq is signed differently from this debug build, so this test install has to
be removed before installing that release (its data is test data on the local test server).

## 3. Files and subsystems changed

- **Android**: `app/build.gradle.kts` (application id, environments, build types), `AndroidManifest.xml` (links, push
  service), every Kotlin file (package `com.griipbuddy.meldiq`), `MeldiqBrand.kt`, `MeldiqPalettes.kt`,
  `MeldiqPushService.kt`, `WelcomeScreen.kt` (hero), `InviteScan.kt`, `MainActivity.kt`, `OrgScreens.kt`,
  `Channels.kt`, media, backup and storage screens, icon drawables, `colors.xml`, `themes.xml`, `drawables.xml`, raw
  sounds, i18n tables (`android/i18n/*.py`) and their generated resources, unit tests, `settings.gradle.kts`.
- **Server**: product texts (mailer, accounts, legacy move, auth, integrations pages and catalog, errors), `server-info`
  name "Meldiq relay", `MeldiqMongo`, sandbox connector `meldiq-sandbox` (`sandbox.meldiq.invalid`), webhook headers
  `X-Meldiq-Timestamp` / `X-Meldiq-Signature` / `X-Meldiq-Delivery`, sign-in cookie `meldiq_oauth`, app return link
  `meldiq://integrations`, user agents (`Meldiq-Integrations/1`, `meldiq-push/1`), MongoDB default database `meldiq`,
  generated usernames `meldiq…`, `PUBLIC_API_URL` as the default sign-in address, package name `meldiq-server`, tests.
- **Deploy and CI**: `deploy/.env.example` (environment section, MongoDB, S3 notes), `compose.yaml` and
  `scripts/deploy.sh` headers, ntfy and nginx comments, `.github/workflows/ci.yml` (`MELDIQ_TEST_MONGO_DOMAINS`).
- **Docs**: README, new `ENVIRONMENTS.md`, this file, the infrastructure plan, and every living document (product
  name, build flags, server-address settings); dated reports keep their original wording as history.

## 4. Old names kept on purpose

| Kept | Where | Why |
|---|---|---|
| `orbit-att-chunk`, `orbit-history-v1`, `orbit-call-verify-v1` | Android crypto | Labels inside the encryption: files, server history and call verification codes must work across Orbit and Meldiq apps |
| `ORBITBK1` (backup magic) | Android backups | Backups made by Orbit restore in Meldiq and the other way round |
| `orbit-integration-v1`, `orbit-integration-token-key-id` | server token sealing | Encryption labels |
| `griip-envelope-v1`, `griip-fed/1`, `/.well-known/griip-server` | protocols | Protocol names (unchanged since Griip Message) |
| `griip://invite/…` (read, not drawn) | Android | Invitations shared from Orbit and Griip Message still open the app |
| accent id `"orbit"` (shown as "Meldiq green") | Android settings | Internal id of the default accent, stored on the phone and never shown; its label is Meldiq's |
| `S3_PREFIX` default `orbit/`, bucket `griipbuddy-orbit-media-sg` | server, `.env.example` | Production files live under that prefix in that bucket; renaming would hide them |
| `orbit_meta` | MongoDB | Collections keep their names |
| AWS stacks `orbit-*`, IAM users and policies, tags `app=orbit`, profile `orbit-admin` | `deploy/aws/*.yaml`, docs | Live resources; names can't change in place (plan §7) |
| Compose project `griip-internal-messaging`, its directory, volumes and scripts | `deploy/` | Live deployment; a rename needs pinned volumes and a window (plan §6) |
| `msg.`, `rtc.`, `ntfy.griipbuddy.cloud` | production default, ntfy config | Live hostnames until the Meldiq domain exists |
| `ORBIT_LIVE_S3_BUCKET`, profile `orbit-s3-test` | live S3 test | Point at the real test bucket and profile |
| "Orbit 2.0" and `docs/ORBIT_*` | comments, docs | The name of the 2026-10-18 architecture phase and its documents |
| `com.griipbuddy.Orbit`, "Orbit" in legacy-move and device-link texts | comments, two strings | History, and telling people their older app or phone works |
| `GriipBuddy`, `com.griipbuddy.*`, internal `Griip*` code names | everywhere | The company name stays |
| Test placeholders (`orbit-labs`, fake bucket names) | server tests | Arbitrary data, no meaning |

## 5–9. Domain, Docker, AWS, MongoDB, providers

- **Domain/API**: not bought; nothing points at a made-up hostname. Addresses are centralised (Android
  `meldiq.environment` / `meldiq.apiBaseUrl.<environment>`; server `APP_ENV`, `PUBLIC_API_URL`, `LIVEKIT_PUBLIC_URL`,
  `INTEGRATIONS_PUBLIC_URL` falling back to `PUBLIC_API_URL`, `INTEGRATIONS_APP_RETURN_URL`, `PUSH_UNIFIEDPUSH_HOSTS`).
  Debug builds no longer default to production. DNS, TLS, proxy and callbacks: plan §2–4, §9.
- **Docker**: template comments updated; the live project, volumes, image name and scripts keep their names; the
  rename procedure with pinned volumes and rollback is plan §6. Not applied.
- **AWS/S3**: no AWS change (one read-only stack listing with the scoped `orbit-admin` profile). What can be renamed,
  what needs replacement, and an optional bucket copy: plan §7.
- **MongoDB**: default database name `meldiq` (production doesn't use MongoDB yet); nothing created, renamed or
  migrated. Plan §8.
- **Connectors/OAuth**: product texts and the sandbox renamed; return link `meldiq://integrations`; no provider is
  registered yet, so no customer connection exists or can break. Callback and webhook URLs to register: plan §9.

## 10. Build and test results (this Mac, 2026-10-19, after the rebrand)

| Check | Result |
|---|---|
| Server typecheck and build | pass |
| Server tests, SQLite | **519 passed, 0 failed**, 10 skipped (live S3 tests: no bucket here) |
| Server tests, MongoDB (`MELDIQ_TEST_MONGO_DOMAINS=accounts,preferences,integrations`) | first full run 518 passed, **1 failed**: `account_store_races` "two refreshes racing with one token" (session revoked), the timing-sensitive test already seen flaking under full-suite load in the previous round; alone it passed 5 times out of 5, and the second full run passed: **519 passed, 0 failed**, 10 skipped |
| Android Kotlin compile, `testDebugUnitTest` | **447 passed, 0 failed** (84 classes, all under `com.griipbuddy.meldiq`; includes the new Meldiq invite-link test) |
| Android lint (`lintDebug`) | **0 errors**, 106 warnings, 5 hints |
| Debug APK (`assembleDebug`) | built; package `com.griipbuddy.meldiq`, label "Meldiq" (every locale), launcher `com.griipbuddy.meldiq.MainActivity`, links `meldiq://invite`, `griip://invite`, `meldiq://integrations`, default server `http://localhost:18098`, environment `development` |
| Release build (`assembleRelease`, R8, unsigned) | built; environment `production`, default server `https://msg.griipbuddy.cloud` |
| Gradle endpoint guards | `-Pmeldiq.apiBaseUrl.production=http://…` refused ("must use https://"); `-Pmeldiq.environment=staging` without an address refused |
| Local backend (Meldiq build, MongoDB integrations domain) | `/healthz` ok, `server-info` name "Meldiq relay" 0.6.0; smoke test **9/9** (honest app states, browser sign-in with the `meldiq://integrations` return link, "approved" until the app finishes, someone else's app refused, connected, search) |
| Deploy scripts | `bash -n` syntax check passed for the edited scripts. `docker compose config` not run: Docker isn't installed on this Mac (the Compose change is comments only); run it on the VPS before any deploy |
| Remaining old names | only the intentional ones in §4 (plus dated reports in `docs/`) |

Not tested: anything on a phone, any real OAuth provider, any production endpoint, Docker on the VPS. Instrumented
UI tests (`connectedAndroidTest`) weren't run: they need a device or emulator, and no emulator may be used.

## 11. Test APK

`artifacts/meldiq-test-2026-10-19.apk` in the worktree (git-ignored): `com.griipbuddy.meldiq`, "Meldiq", 0.2.1-beta (8),
debug-signed, 145 MB, SHA-256 `3a75b44181ffaf0e54b6832af075e9353f4d41f35107db5830860462dc34e3be`. Its default server is
the local test backend on this Mac (`http://localhost:18098`, MongoDB integrations domain, sandbox on), reached from the
phone through `adb reverse tcp:18098 tcp:18098` (plus the LiveKit ports for calls); any other server can be chosen in
the app.

## 12. Blockers and what's needed from the owner

- The Meldiq **domain** (Hostinger) and approval of each infrastructure step (plan §1).
- A **new Play Console app** for `com.griipbuddy.meldiq` when releasing (the Orbit listing can't be renamed).
- Provider OAuth registrations (Google, Microsoft, GitHub, Atlassian, Notion, Linear) under the Meldiq name, with the
  owner's provider accounts (plan §9). No credentials exist today.
- Decisions: keep or migrate the S3 bucket name and the Compose project name (recommended: keep both for now).
- Orbit Web (separate repository, separate session) still says Orbit.
- Before committing: review the combined change (integrations round + rebrand); staging with `git add -A` shows the
  Kotlin moves as renames.

## 13. Production

Unchanged: no deploy, no DNS or proxy change, no container, volume, bucket, IAM, SES or database change, no provider
console change, no credential rotated or exposed. One read-only AWS call (CloudFormation stack list, scoped
`orbit-admin` profile).

## 14. Emulator

None used: no Android Studio emulator, AVD or Gradle-managed device. Testing was on the owner's HONOR phone only (§16).

## 16. Physical-device results (HONOR ELI-AN00, 2026-10-19, 15:28–16:15 local time)

Meldiq test APK against the local test backend (`adb reverse` for 18098, 17898, 7898), a fresh test account
(`@meldiqtester1495`, local server only), test bots, the Meldiq Sandbox. Production wasn't touched.

| Area | Result |
|---|---|
| Install | `adb install` hung for 5 minutes with no prompt; `adb push` (2.5 s) + `pm install` succeeded at once. Installed as a **separate app**: the Play Orbit (`com.griipbuddy.Orbit`) is still installed and untouched |
| Branding | Installer, launcher label and icon, splash and welcome screen show Meldiq and the new mark (two-ring hero); sign-in, sign-up and account texts say Meldiq; the legacy-move card names Orbit or Griip Message; Cocoa palette and navigation unchanged |
| Sign-up | Email code arrived as "… is your Meldiq verification code" (server mail file); recovery key shown once; workspace "Meldiq Lab" created; Home / DMs / Activity as before |
| Secure screens | Password and recovery-key screens refuse screenshots (`FLAG_SECURE`), as intended |
| Invites | Invite created; the share text is "Join Meldiq Lab on Meldiq with invite code … (`meldiq://invite/…`)"; two bots joined with the code and the member count updated live |
| Messaging | E2EE DM with the echo bot: sent, delivered and read receipts, echo reply with quote, reaction; message notification on the `meldiq_msg_*` channel |
| Files | Photo upload (Standard quality): the bot downloaded the ciphertext, hash ok, decrypted the PNG; image shown in the chat; "Save to Gallery" wrote `Pictures/Meldiq/…` with the decrypted size |
| Calls | Voice call to the bot: E2EE on, the bot decoded the phone's microphone (4,670 frames over 46 s), "End-to-end encrypted · 2 in call", ongoing-call notification, clean hang-up, call shown in history. **Not tested:** video (to keep the phone's camera off) and screen sharing (needs the owner's tap on the system consent) |
| Roles | Read-Only Member created from its template and assigned to Sam Bot (accepted by the server). Posting refusal isn't re-checked on the phone (covered by server tests) |
| Apps | Directory states honest (Google, GitHub: Needs configuration; Meldiq Sandbox, Incoming webhooks: Available). Sandbox installed, connected through Chrome Custom Tab consent; the browser handed back to Meldiq and the app finished the sign-in itself (`POST …/flows/…/finish` in the server log; no query strings logged). Share from an app: link card with Meldiq Sandbox details. Workspace linked, `#builds` subscribed (resources listed through the admin's own account), signed test event posted |
| Incoming webhook | Created for `#builds`, secret shown once; an HMAC-signed post (`X-Meldiq-*`) was accepted (202) and shown in the channel's app updates, labelled "Not end-to-end encrypted" |
| Background | With Meldiq in the background, bot messages arrived and a message notification was posted; "Meldiq is connected" background-service notification present |
| Reconnect | Server stopped: "Connecting… Retry" banner, message queued with a clock; server restarted: banner cleared and the queued message was sent |
| Login and recovery | Signed out (keys removed from this phone), signed in with email and password, a wrong recovery key refused ("That recovery key isn't valid"), the right one restored the account; encrypted DM history came back from the server |
| Not tested | Device approval from a second phone (no second device), Orbit-to-Meldiq linking, video, screen sharing, language switch, the Android 12+ splash animation in detail |

**Findings:**
1. *Integrations UI (from the previous round):* Incoming webhooks showed a "Link workspace" button and a "Link this
   workspace" setup step that don't apply to webhooks. **Fixed** the same day: the server offers `link_workspace` only
   for connectors that link a workspace (`allowedActions(…, linkable)`), and the app shows the link and notification
   steps only when `supports.workspaceLink`. Server 520 passed (2 new checks), integration suites on MongoDB 54 passed,
   Android 447 passed; APK rebuilt (SHA-256 `38f9bc85f5b00b0978f444fc633f9f7dc07f15faeeeef8c1cbedc32dedf55b3b`), updated
   in place on the phone: Incoming webhooks now shows Settings and Uninstall only, its webhook list is intact, and the
   Sandbox still offers linking.
2. *Install over USB:* plain `adb install` stalled with no prompt on this phone; `push` + `pm install` works. Device
   instructions updated in memory, not a code issue.
3. *Permission prompts:* the notification and Bluetooth prompts were answered "Allow" without a tap from the test
   driver (it meant to decline Bluetooth); presumably answered on the phone. Microphone was "Allow this time only".
4. *Orbit Test* (`com.griipbuddy.Orbit.test`) shows as not installed for the phone's users at the end of the test
   (it was listed at 15:28). The test didn't install, uninstall or change it (only the Meldiq install and its
   temporary file ran); its data folder still exists. Worth a look by the owner.
5. Test files placed on the phone (the test photo in Downloads and its gallery copy) were removed afterwards. Meldiq and
   its test account remain installed for further testing; `adb reverse` rules stay until the phone is unplugged.

## 15. Next steps and the physical-device checklist

Owner: connect the HONOR phone (USB debugging on, unlocked) and confirm. Then, with the SDK's adb:

1. **Install and launch:** `adb reverse` for 18098 and the LiveKit ports; `adb install artifacts/meldiq-test-2026-10-19.apk`
   (tap Continue). Check: the launcher shows "Meldiq" with the new icon *beside* Orbit (Orbit untouched, still signed
   in); the splash and welcome screen show the new mark; the theme is unchanged.
2. **Sign-up and login:** create a test account on the local server (email code from the server's mail folder); log out
   and back in.
3. **Existing account / device linking:** sign in to the same test account and unlock with the recovery key; approve a
   second sign-in from another signed-in device if one is available (Meldiq-to-Meldiq; Orbit-to-Meldiq only on a server
   both apps use).
4. **Recovery key:** view it in Settings, confirm the unlock flow refuses a wrong key.
5. **Messaging and real time:** chat with test bots (`seed.mts join`) in a DM, a group and a channel, send and reply;
   messages arrive live without refreshing.
6. **Files:** photo, video and file upload and download; "Save to gallery" lands in `Pictures/Meldiq` / `Movies/Meldiq`.
7. **Calls:** voice and video with a second device if available; screen sharing; the call notification says Meldiq.
8. **Workspaces:** create, invite (QR shows a `meldiq://invite/…` link; the shared text says Meldiq), bots join, switch.
9. **Roles:** custom roles, assignment, a Read-only member can't post.
10. **Apps:** directory states, install the Meldiq Sandbox, connect (the browser's "Return to Meldiq" opens Meldiq, not
    Orbit), share from an app, channel notification, incoming webhook (`X-Meldiq-*` headers via `seed.mts hook`).
11. **Notifications and background:** message and call notifications titled Meldiq with the new status-bar icon;
    "Stay connected" notification; background delivery after locking the phone.
12. **Errors and reconnect:** airplane mode on and off (reconnects, queued messages send); stop the local server briefly
    (clear error, recovers).
