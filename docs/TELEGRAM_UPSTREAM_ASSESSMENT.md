# Telegram Android as a foundation for Griip Message: upstream assessment

| | |
|---|---|
| Date | 2026-10-16 |
| Type | Evaluation only. No Telegram code was imported into this repository, and the app, backend and deployment are unchanged |
| Upstream inspected | `https://github.com/DrKLO/Telegram`, commit `f32ffeec` "update to 13.0.2 (7175)", 2026-10-13, with all 19 submodules at their recorded commits |
| Research checkout | `/Volumes/code_files/griip_research/telegram`, outside this repository (2.2 GB with submodules) |
| Griip baseline | `internal-beta` at `4073c51`, plus the uncommitted 2026-10-16 audit work. The source of truth for Griip's features is `FEATURE_INVENTORY.md` plus the code |
| Not legal advice | §4 lists findings and open questions for a qualified lawyer. It is not a licensing opinion |

---

## 1. Executive recommendation

**Preserve the current Griip client and backend (Strategy A), and use Telegram as a behavioural reference (Strategy C).
Do not fork Telegram (Strategy B).**

The evidence, all from the inspected source:

1. **Telegram's protocol isn't a replaceable layer. It is the app's data model.**
   - 717 of the 1,962 `org.telegram` Java files reference the generated `TLRPC` schema classes, 31,453 times.
   - 599 of the 1,527 UI classes use `TLRPC` objects directly, and 194 UI classes send network requests themselves.
   - Across the app there are 1,256 `ConnectionsManager.sendRequest(...)` call sites in 237 files.
   - Swapping the transport means re-implementing or shimming the meaning behind each of those calls, not one network module.
2. **The trust model is the opposite of Griip's.**
   - Telegram is server-authoritative: the cloud keeps history, the server sequences updates (pts/qts/seq), and the client stores
     what the server says.
   - Griip's server is a blind relay with a 7-day queue, and history lives only on the device.
   - In Telegram, only 1:1 "secret chats" are end-to-end encrypted (single device, no groups). Its local database is plain SQLite:
     the compiled amalgamation has no codec, and secret-chat auth keys sit in table `enc_chats`.
   - A fork would have to remove the parts that make Telegram what it is, or regress Griip's security.
3. **Licensing changes the product's legal character.**
   - The tree is GPL-2.0 (most file headers say "or later"). It links LGPL-3.0 `tgcalls` and Apache-2.0 components.
   - Distributing a fork obliges publishing the complete corresponding source of the client, including Griip's own client code
     merged into it.
   - That conflicts with the proprietary-software concern already recorded in `DECISIONS.md` D-04.
4. **The cost of the base is very large.**
   - 2,200 Java files, about 1.56 million lines, at Java 8 language level. `ChatActivity.java` alone is 47,485 lines.
   - A 2.2 GB checkout, 19 submodules, prebuilt native libraries, NDK 27.2 and a WebRTC build from source.
   - No JVM unit tests: only instrumentation tests for TL serialization, the TLS handshake and DB migration.
   - Griip today is 55 Kotlin files (9,654 lines), plus a 3,268-line server with 36 tests.
5. **Griip would lose features:**
   - the Compose design system;
   - the org/branch/department/team hierarchy and its role matrix;
   - device certificates and TOFU;
   - the relay-expiry model;
   - LiveKit calls with media E2EE.

   None of these exist in Telegram, so each would be rebuilt inside a much larger and more coupled codebase.

**What Telegram does better, and what Griip should copy as behaviour rather than code** (details in §6):
- resumable, chunked and parallel file transfers, plus streaming playback;
- update sequencing with gap detection, which addresses Griip defect P6;
- incremental database migrations (Griip has none);
- media cache policies and auto-download presets;
- push with a keep-alive fallback;
- full-screen incoming calls through `ConnectionService`.

Each can be built against Griip's existing architecture in days to weeks, without GPL obligations.

---

## 2. Current Telegram architecture (as inspected)

### 2.1 Modules and build

| Item | Finding (file) |
|---|---|
| Modules | `TMessagesProj` (library: all Java, JNI, resources); `TMessagesProj_App` (Play app); `TMessagesProj_AppStandalone` (`.web` suffix, direct download); `TMessagesProj_AppHuawei`; `TMessagesProj_AppHockeyApp` (beta); `TMessagesProj_AppTests` (instrumentation tests); `jlatexmath`; the `TMessagesProj_Modules/media` fork of AndroidX Media3 (`settings.gradle`) |
| Toolchain | AGP 8.13.2, Gradle 8.13, Kotlin plugin 2.1.0 (used only by tests and `buildSrc`); compileSdk/targetSdk 36, minSdk 23; Java source level 1.8 with desugaring; NDK **27.2.12479018**; CMake **3.22.1** (`build.gradle`, `TMessagesProj/build.gradle`, `README.md`, `Dockerfile`) |
| App ID and signing | `APP_PACKAGE=org.telegram.messenger`. The repo ships a *dummy* `release.keystore` with passwords `android`/`androidkey` in `gradle.properties`, a dummy `google-services.json`, and filled-in `BuildVars` (README: "for reproducible builds … replace all these files") |
| Credentials in `BuildVars.java` | `APP_ID = 4`, `APP_HASH`, `SAFETYNET_KEY`, `HUAWEI_APP_ID` and Play URLs. These are Telegram's identifiers and must not be reused |
| Gradle memory | `org.gradle.jvmargs=-Xmx8g` |
| Flavours | `afat` and `bundleAfat` build all four ABIs (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`). Build types: debug (`.beta`), standalone, release, `HA_*` |
| Source size | 2,200 Java files, 1,556,480 lines: 1,962 in `org.telegram`, 173 in `org.webrtc`, plus vendored `androidx.recyclerview` and `me.vkryl`. No Kotlin in the app |
| Largest classes | `ChatActivity` 47,485 lines; `TLRPC` 70,246 (generated, 2,022 `TL_` classes, plus 19 files in `tgnet/tl`); `MessagesController` 25,608; `MessagesStorage` 19,064; `ProfileActivity` 16,993; `DialogsActivity` 14,436; `SendMessagesHelper` 12,506; `GroupCallActivity` 10,785; `LoginActivity` 10,366; `Theme` 10,159 |
| Third-party SDKs (`TMessagesProj/build.gradle`) | Firebase Messaging, Play services (maps, location, auth, vision, wearable, wallet, cast, SafetyNet, Integrity, ML Kit), Play Billing, Stripe, reCAPTCHA, Huawei AGConnect, Android Auto (`car.app`), Markwon, Guava, Gson, ZXing |

### 2.2 Layers

```
UI (org.telegram.ui: custom ActionBar/BaseFragment navigation, Views; no Compose; 1,527 files)
  │  NotificationCenter (global event bus) · Theme (10k lines) · AndroidUtilities (7k lines)
Per-account controllers (UserConfig.MAX_ACCOUNT_COUNT = 4; AccountInstance)
  MessagesController · SendMessagesHelper · MessagesStorage · FileLoader · NotificationsController
  ContactsController · MediaDataController · SecretChatHelper · voip/VoIPService
  │  all exchange TLRPC objects
ConnectionsManager.java ── JNI ──► tgnet (C++ MTProto 2.0 client)
  Handshake (2 embedded RSA server keys) · Datacenter (hard-coded DC IPs, e.g. 149.154.175.50)
  TLS-hello obfuscation · proxy checks · DNS-over-HTTPS fallback (dns.google.com, mozilla.cloudflare-dns.com)
                                     ► Telegram data centres (closed-source server)
```

### 2.3 Subsystems

| Subsystem | Implementation (evidence) |
|---|---|
| **Networking** | Native `tgnet` (`jni/tgnet/*.cpp`): MTProto sessions, salts, acks, generic, download, upload and push connections, and reconnection. `ConnectionsManager.cpp` hard-codes Telegram DC addresses. `Handshake.cpp` embeds two RSA public keys. The Java side adds DNS-over-HTTPS backup-address loaders (`GoogleDnsLoadTask`, a Cloudflare TXT loader) |
| **Identity and accounts** | Phone number plus SMS or Firebase verification (`LoginActivity`, `auth.sendCode`). The server holds the account. The client keeps an MTProto auth key per DC, and there are no user-held identity keys. Up to 4 accounts per install |
| **Message send and receive** | `SendMessagesHelper` builds `messages.sendMessage`/`sendMedia` TL requests. Incoming messages arrive as `Updates` and are applied by `MessagesController.processUpdates`, with **pts/qts/seq gap detection and `updates.getDifference` recovery**. The server keeps the authoritative history |
| **Storage** | A JNI SQLite amalgamation (`jni/sqlite/sqlite3.c`, `SQLITE_HAS_CODEC` count = 0) with a `cache4.db` file per account. WAL, `secure_delete=ON`, a 10 MB journal limit. **96 tables, schema version 179 with stepwise migrations.** Messages, users and chats are stored as TL-serialized `BLOB`s keyed by server IDs. Secret-chat `authkey` lives in `enc_chats`. A passcode gates the UI and doesn't encrypt the database |
| **Groups and channels** | `TLRPC.Chat` (basic groups, megagroups, broadcast channels, forums/topics) with server-enforced `TL_chatAdminRights` and `TL_chatBannedRights` (`ChatObject.canSendMessages`, etc.). Folders (dialog filters). New in 13.x: a `communities` API (`tgnet/tl/TL_communities.java`: `communities.create`, `togglePeerLink`, `getJoinedCommunities`, …), which from its method names appears to group chats on the server. There is no org/branch/department hierarchy |
| **E2EE** | Secret chats only (`SecretChatHelper`, 2,068 lines; 1:1, bound to one device, MTProto 2.0 E2E). Conference calls use TDLib's `tde2e` (prebuilt `libtde2e.a`) |
| **Media and files** | `FileLoadOperation`: 32 KB or 128 KB chunks, 4 parallel requests, `.temp` and `.pt` part files for resume, CDN redirects. `FileUploadOperation`: 64 KB+ parts, parallel, big-file mode. `FileStreamLoadOperation` streams into ExoPlayer (Media3 fork). `ImageLoader` memory and disk caches. `DownloadController` low/medium/high auto-download presets. `CacheByChatsController` keep-media policies (1 day / forever / delete). Native FFmpeg, dav1d, libvpx, Opus and tlottie |
| **Push** | `PushListenerController`: FCM (`PUSH_TYPE_FIREBASE`) or Huawei (`PUSH_TYPE_HUAWEI`). The token is registered with Telegram's server through `account.registerDevice`. An optional `keepAliveService` setting provides a foreground connection |
| **Calls** | `voip/VoIPService` (5,852 lines; 37 `sendRequest` calls on `phone.*`). Native `tgcalls` (LGPL-3.0) plus WebRTC compiled from source (`jni/voip/webrtc`, 1,787 `.cc` files, 39 MB). P2P or Telegram relays, group and conference calls (`GroupCallActivity`), screen share through a `camera|microphone|mediaPlayback|mediaProjection` foreground service. `TelegramConnectionService` integrates with Android Telecom |
| **Native** | `jni/CMakeLists.txt` builds `tgnet`, SQLite, VoIP/WebRTC, breakpad and wallet glue, and links **prebuilt** static libraries from `jni/prebuild/lib/<abi>/`: FFmpeg, BoringSSL, libvpx, dav1d, Opus, OpenH264, `tde2e`, `tdutils`, prisma, tlottie, the TON wallet engine, iwasm. Rebuild scripts are in `jni/prebuild/*.sh` |
| **Tests** | `TMessagesProj_AppTests`: instrumentation tests only. They cover TL scheme round-trips against 232 historical scheme snapshots (`tlscheme/*.json`, generated `Test_All.kt`), the TLS-hello handshake and DB migration. There are no JVM unit tests and no UI tests |

### 2.4 Coupling measurements

| Measure | Count |
|---|---|
| `org.telegram` Java files that reference `TLRPC` | 717 / 1,962 |
| `TLRPC.` references | 31,453 |
| Files that call `sendRequest(` / number of call sites | 237 / 1,256 |
| UI files that reference `TLRPC` / call `sendRequest` directly | 599 / 194 (of 1,527) |
| UI *component* classes (`ui/Components`) that reference `TLRPC` / controllers / `Theme` | 233 / 162 / 435 (of 792) |
| Files that use `MessagesController` / `MessagesStorage` | 554 / 145 |
| Files tied to per-account state (`currentAccount` / `selectedAccount`) | 736 |

**Conclusion:** networking *could* in principle be swapped at the `ConnectionsManager` boundary. The message *model* (TL types, server
IDs, pts sequencing, server-side rights) runs through controllers, storage and UI, so it can't be replaced cleanly.

---

## 3. Compatibility matrix

Key to the "Compatible with Griip backend?" column:
- **No:** Telegram's implementation can't talk to Griip's server, or contradicts its model.
- **Adapter:** works only behind a translation layer that Griip would have to write.
- **N/A:** client-local, so no backend dependency.

Effort is a rough order of magnitude for one experienced engineer: S ≤ 2 wk, M ≤ 6 wk, L ≤ 3 mo, XL > 3 mo.

| Feature | Telegram implementation | Griip implementation | Compatible with Griip backend? | Integration work in a fork | Dependencies to replace | Expected benefit | Regression risk | Verification |
|---|---|---|---|---|---|---|---|---|
| App ID, name, branding | `org.telegram.messenger`, Telegram name and logo | `com.griipbuddy.message`, Griip design system (D-11) | N/A | M: rename, strip all Telegram assets, strings and URLs, remove translation-platform links | `APP_PACKAGE`, BuildVars IDs, icons, `google-services.json`, keystore | None | Brand or trademark confusion if anything is missed | `aapt2 dump badging`; string and asset grep for "Telegram" |
| Identity and keys | Server-held account; MTProto auth key per DC; phone + SMS login | Ed25519 identity + device keys, X25519 HPKE key, device certificate, Keystore-sealed, recovery key | **No** | XL: new login, onboarding and account model, replacing `LoginActivity`, `UserConfig` and `AccountInstance` assumptions | `tgnet` auth, SMS/Firebase verification, reCAPTCHA, SafetyNet/Integrity | None | **High:** key handling would be re-implemented in an unfamiliar codebase | Port `auth.test` scenarios; device tests of register, recover and link |
| Message encryption | Cloud chats are client↔server encrypted only; secret chats 1:1 E2E | `griip-envelope-v1` for every DM, group and channel, multi-device | **No** | XL: decrypt and verify on receipt, then build local `TLRPC.Message` objects; encrypt on send instead of `messages.sendMessage` | `SendMessagesHelper`, update handling, `SecretChatHelper` | None (Griip is already E2EE everywhere) | **Critical:** any path that renders server text unencrypted is a silent downgrade | Kotlin↔Node interop vectors; relay DB scan for plaintext (`messaging.test`) |
| Transport and reconnection | MTProto over TCP with obfuscation, multiple connections, native | HTTPS REST + one WebSocket (OkHttp), backoff and jitter | **Adapter** | XL: replace `ConnectionsManager` with a Griip transport and map 1,256 call sites, or answer TL requests locally | `tgnet`, DNS fallback, hard-coded DCs, proxy | Telegram's connection robustness only applies to MTProto | High | Per-method mapping tests; network-flap device tests |
| Offline delivery and 7-day expiry | Server keeps history indefinitely; `getDifference` catches up | Relay queue, ack deletes, 7-day TTL, no history fetch | **No** (semantics differ) | L: pts/getDifference logic would be dead code or re-purposed for the queue | `MessagesController` update and difference logic | Pattern only (see §6) | High: Telegram UI assumes history can be re-fetched from the server | Queue and expiry tests; offline more than 7 days |
| Local history and DB protection | Plain SQLite, TL BLOBs, 96 tables, 179 migrations | Room + SQLCipher with a Keystore-sealed passphrase, no migrations yet | N/A | L: swap the SQLite amalgamation for SQLCipher (native), re-key, and re-model the tables for Griip IDs | `jni/sqlite`, `MessagesStorage` schema | Telegram's migration discipline (pattern) | **High:** an unencrypted DB is a regression | Inspect the `.db` on a device (header not `SQLite format 3`) |
| Update ordering and dedup | pts/qts/seq with gap detection and difference fetch | Defect **P6**: live pushes overtake backlog and duplicate | **Adapter** (needs per-device sequence numbers on the server) | Pattern only | — | **High** (fixes P6) | Low if done natively in Griip | Server test: 105 queued + live → strictly ordered, exactly once |
| Multi-device | Server sessions; all devices read cloud history; secret chats single-device | Per-device keys, fan-out to sender's other devices, device linking, revocation | **No** | XL | Session and auth model | None | High | `auth.test`, `messaging.test` multi-device |
| Groups and channels | Basic groups, megagroups, channels, forums, folders, communities; server-enforced admin rights | DMs, groups ≤ 50, org channels (public, private, announcement) | **Adapter** | L: map Griip conversations to `TLRPC.Chat`/`Dialog`; rights from Griip roles | `ChatObject`, rights TL types | Polished group UI and features | Medium–High: many Telegram rights and features would show but not work | Per-feature UI walkthrough |
| Org, branches, departments, teams | None (closest: communities and folders) | Full hierarchy, cross-branch teams, archiving | **No** | L: new screens inside Telegram's View framework | — | None | Feature loss until rebuilt | `orgs.test`; UI tests |
| Roles and branch-level access control | Per-chat admin and banned rights | Six org roles, branch/team scopes, server-enforced (`authz.ts`) | N/A (server-side in Griip) | M: UI to show and edit Griip roles; hide Telegram rights UI | Admin-rights screens | None | Medium: confusing dual permission UIs | `orgs.test` matrix + UI checks |
| Invitations and member management | Invite links, join requests | Hashed scoped codes, QR, `griip://` links, role ≤ inviter | **Adapter** | M | Invite-link TL flows | Telegram's join-request UX | Medium | `orgs.test` invitations |
| Encrypted attachments | Server-side files, file references, CDN, chunked/resumable/streaming | AES-GCM per file, key in the envelope, SHA-256, ≤ 25 MB, 7-day expiry, whole-file in memory | **Adapter** | L: `FileLoader` would fetch Griip blobs and decrypt in chunks; needs a chunked encryption format | `FileLoadOperation`, `FileUploadOperation`, CDN logic | **High** (resumable, streaming, low memory), but achievable natively (§6) | Medium | Interrupted-transfer tests; hash checks |
| Media playback, transcoding, stickers | ExoPlayer fork, FFmpeg, dav1d, vpx, tlottie, photo/video editor | Basic image and file handling | N/A | M | — | High UX value | Low–Medium | Media matrix on devices |
| Push | FCM/HMS through Telegram's server; optional keep-alive | None (D-08); optional "stay connected" foreground service | **No** | M: own push server or UnifiedPush, or remove FCM | Firebase, Play services | Medium (killed-app delivery) | Privacy: FCM sends metadata to Google, contrary to D-08 | Killed-app delivery tests |
| Android background behaviour | Foreground services by type, `ConnectionService`, full-screen call UI, notification styles | `remoteMessaging` foreground service, full-screen intent (unverified) | N/A | S–M (pattern) | — | Medium | Low | Device tests: locked, killed, Doze |
| 1:1 calls | `tgcalls` P2P or Telegram relays; DH key + emoji verification; `phone.*` signalling | LiveKit SFU, room tokens from the Griip API, shared-key frame E2EE | **No** | XL: replace `VoIPService` signalling and `tgcalls` with the LiveKit SDK, or build tgcalls signalling and relays | `tgcalls`, WebRTC build, `phone.*` | None for Griip (LiveKit already works) | **High** (calls just stabilised; see `TESTING.md` §5a) | Phone↔bot acceptance, wrong-key test |
| Group calls and screen share | Telegram group-call servers, `tde2e` conference E2E, presentation endpoint | LiveKit group rooms ≤ 12, `MediaProjection` screen share, E2EE verified | **No** | XL | Group-call TL API, `tde2e` | None | High | `TESTING.md` §5a re-run |
| Media E2EE key distribution | DH in call signalling; `tde2e` for conferences | Per-call key inside an encrypted `call_invite`; no-key → refuse | **No** | L | — | None | High | Wrong-key bot test |
| Safety numbers and verification | Secret-chat key emoji/visualisation | Safety numbers from identity keys, TOFU (pinning not enforced, T1.2) | N/A | M | — | None | Medium | Unit vectors |
| Notifications | Mature grouping, replies, avatars, per-chat channels | Basic per-conversation notification | N/A | Pattern | — | Medium | Low | Device checks |
| Backend, Docker, admin | Closed-source server; nothing to deploy | Node/Fastify + SQLite + LiveKit in Compose `griip-internal-messaging`, admin CLI | N/A (fork keeps Griip's backend) | — | — | None | — | Existing server tests |
| Self-hosting and independent relays | Hard-coded DCs and RSA keys in native code | Server URL stored per session; protocol server-agnostic (`ARCHITECTURE.md` §8) | N/A | M to make configurable | `tgnet` constants | None | Medium | Point at a second relay |
| UI framework | Custom Views, `BaseFragment`, global `Theme` | Jetpack Compose + Griip design system (D-01, D-11) | N/A | XL to restyle 1,527 UI files, or accept Telegram's look | — | Telegram's polish and animation | **High:** violates "don't redesign"; brand confusion | Visual review |
| Tests | TL serialization, handshake, migration (instrumented) | 36 server tests, 8 Android unit tests, device scripts and bot | N/A | — | — | Scheme-snapshot idea (pattern) | — | — |

### 3.1 Required coverage: feature loss and security regressions under a fork

| Griip area | Current state (`FEATURE_INVENTORY.md`, `TESTING.md`) | Under a Telegram fork |
|---|---|---|
| Identity and key management | Ed25519/X25519 identity and device keys, certificates, Keystore sealing, recovery key, device linking | **Lost** until re-implemented. Telegram has no user-held identity keys. Re-implementing them inside its account model is high-risk |
| Message encryption | `griip-envelope-v1` for all conversations | **Regression risk:** Telegram's UI renders server-provided text, so every code path must be re-plumbed through decryption |
| Offline delivery and expiration | Relay queue, ack-delete, 7-day TTL | **Semantic conflict:** Telegram assumes permanent server history and refetch |
| Local history and database protection | SQLCipher + Keystore-sealed passphrase | **Regression:** plain SQLite with TL blobs, unless the native SQLite is replaced with SQLCipher |
| Multi-device | Per-device keys, fan-out, revocation | **Lost:** Telegram's multi-device relies on server-readable cloud history |
| Organisation and branch isolation | Server-enforced scoping (P2 activity-feed leak open) | **Lost in the UI:** no hierarchy in Telegram. The server stays Griip's, so enforcement survives but nothing in the client shows it |
| Role enforcement | Six roles, scoped, tested in `orgs.test` | **Partly lost:** Telegram's per-chat admin rights UI would conflict and need hiding |
| Encrypted attachments | AES-GCM per file, 25 MB, 7-day expiry | Needs an adapter. Telegram's transfer engine is better, but the format must change either way |
| LiveKit calls and screen sharing | Verified on 2026-10-16 (`TESTING.md` §5a), including wrong-key E2EE | **Lost:** Telegram's VoIP stack is `tgcalls` + Telegram servers, and LiveKit would be re-integrated from scratch |
| Existing Android app behaviour | Compose UI, Griip design system, "stay connected" service, no FCM | **Lost:** View-based Telegram UI; FCM by default; Play-services dependencies |

**What must change for Telegram's client to talk to Griip's server** (it can't today):
1. MTProto framing, auth-key handshake, DC selection and RSA server keys (`tgnet`) are meaningless to Griip's REST/WebSocket API.
2. Every `TL_*` request the UI issues needs a Griip equivalent, or a local answer. 1,256 call sites, most with no Griip counterpart:
   stickers, stories, premium, payments, bots, wallets, ads and so on.
3. Incoming envelopes must be verified and decrypted, then synthesised into `TLRPC.Message`/`Update` objects with locally assigned IDs and pts.
4. Server-side history assumptions (`getHistory`, `getDifference`, file references) must be neutralised, because Griip's relay
   deletes messages on ack.

---

## 4. License and branding findings

### 4.1 Telegram's own code

- `LICENSE` is the **GNU GPL version 2** text (339 lines).
- 427 of the 2,200 Java files carry the header "It is licensed under GNU GPL v. 2 or later" (for example `MessagesController.java`,
  `jni/tgnet/ConnectionsManager.cpp`). Most other files have **no header**.
  - **Open question:** are header-less files "GPL-2.0-only" (the `LICENSE` file) or "GPL-2.0-or-later" (the headers)? It matters for the next point.
- The README asks developers to "publish **your** code too in order to comply with the licences."

### 4.2 Bundled components (license files read from each submodule)

| Component | License | Note |
|---|---|---|
| `tgcalls` | **LGPL-3.0** | LGPL-3 is generally considered incompatible with GPL-2.0-only. Combining it relies on the "or later" clause, so the combination would be distributed under GPLv3 terms. **Legal review** |
| BoringSSL, Abseil, WAMR, Media3 fork (`TMessagesProj_Modules/media`) | Apache-2.0 | Apache-2.0 is generally considered incompatible with GPL-2.0-only and compatible with GPLv3. NOTICE files must be kept |
| FFmpeg | LGPL-2.1+ as configured | `jni/prebuild/build_ffmpeg.sh` uses `--disable-everything` without `--enable-gpl` or `--enable-nonfree`. It enables H.264, HEVC, AAC and MP3 decoders, which raises **patent-licensing** questions (HEVC especially) |
| OpenH264 | BSD-2 | Cisco's patent coverage applies to **Cisco-distributed binaries**. These libraries are built from source, so the H.264 patent position needs **legal review** |
| `jlatexmath` | GPL-2.0-or-later | |
| WebRTC (vendored in `jni/voip/webrtc`) | BSD-3 + patent grant | The vendored copy **has no LICENSE or PATENTS file**, although file headers refer to them. A notice gap to fix before any redistribution |
| TDLib (`jni/td`; `tde2e`/`tdutils` are prebuilt) | Boost-1.0 | |
| Wallet engine | MIT / Apache-2.0 | TON wallet. Irrelevant to Griip and would be removed |
| libvpx, dav1d, Ogg, Opus, opusfile, libyuv | BSD | Attribution required |
| tlottie, libprisma | MIT | |
| boost-regex | Boost-1.0 | No top-level LICENSE file in the submodule; headers state Boost |
| SQLite | Public domain | |
| Firebase, Play services, ML Kit, Billing, Stripe, Huawei AGConnect | Proprietary SDK terms | They would need separate acceptance, or removal |

### 4.3 Obligations if a fork were distributed

These are summarised from the license texts. They are obligations to confirm with counsel, not conclusions.
- **Source availability.** Provide the *complete corresponding source* for every distributed APK: Java, native code, build scripts and
  the modified submodules. Provide it with the binary, or by a written offer (GPLv2 §3; GPLv3 §6 if the "or later" route is taken).
- **Notices.** Keep copyright headers, the GPL text, Apache NOTICE files and BSD/MIT attributions, and mark modified files as changed.
  Ship an in-app licenses screen.
- **No further restrictions.** The fork can't be distributed with terms that forbid redistribution or reverse engineering by recipients.
- **LGPL `tgcalls`.** Recipients must be able to replace or relink the LGPL library (installation information under LGPLv3).

**Uncertain boundaries for legal review:**
1. Does handing the APK only to employees of the same company count as "distribution"? Contractors, partner companies or
   franchise branches would very likely count.
2. Griip's Kotlin client code merged into a GPL fork would be part of the same program and released under the GPL.
   Could the `MessageCrypto` module or LiveKit integration stay proprietary as separate libraries linked into the same APK?
   (They are probably part of one combined work.)
3. The Node backend talks to the client only over the network. GPL v2 and v3 (unlike AGPL) don't normally reach it.
   Counsel should confirm that, given that the bot and test client share protocol code with the server.
4. Is it the GPLv2-only `LICENSE` or the "or later" headers that apply to the combined work with LGPL-3.0 and Apache-2.0 parts?
5. Patent licensing for H.264, HEVC and AAC decoding when distributing self-built FFmpeg and OpenH264.
6. Export-control classification of the cryptography (it applies to the current app too).

**Do not assume the Griip parts can be relicensed independently once they are combined into the fork.**

### 4.4 Branding and service terms

- README rules for developers: obtain your own `api_id`, do **not** use the name "Telegram" (or make it clear the app is unofficial),
  do **not** use the paper-plane logo, and publish your code.
- Telegram API Terms of Service (`core.telegram.org/api/terms`, read on 2026-10-16) apply to clients that use the Telegram API:
  - transparency about being part of the Telegram ecosystem;
  - no "Telegram" in the title unless preceded by "Unofficial", and no Telegram logo;
  - a ban on interfering with core functions;
  - support for sponsored messages when showing channel content;
  - API access can be revoked after 10 days of an unresolved breach.

  A Griip fork that **never connects to Telegram's servers** wouldn't be an API client. It would still have to:
  - remove the hard-coded DCs, RSA keys and DNS-over-HTTPS fallbacks, so it can't contact Telegram, Google DNS or Cloudflare by accident;
  - remove all Telegram branding, `BuildVars` credentials, Play/Huawei IDs and links to `translations.telegram.org`;
  - keep its own name, visual identity and `com.griipbuddy.message` ID;
  - never present itself as a Telegram client.

---

## 5. Build feasibility results

Attempted on 2026-10-16 on this workstation: Apple-silicon Mac, **8 GB RAM**, macOS, Android Studio's bundled JDK 21.0.10.
The source was **untouched**:
- no file edited;
- no `local.properties`;
- no credentials added (`git status` in the research checkout was empty before and after).

Command-line settings only:

```bash
cd /Volumes/code_files/griip_research/telegram
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME=~/Library/Android/sdk \
./gradlew :TMessagesProj_App:assembleAfatDebug -Pandroid.injected.build.abi=arm64-v8a \
  "-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g" --no-parallel --no-daemon
```

- `-Pandroid.injected.build.abi` is the property Android Studio uses to build only the connected device's ABI. It limits native compilation
  to arm64. The `afat` flavour otherwise builds four ABIs.
- The JVM override was needed because the repository's `gradle.properties` asks for `-Xmx8g`, which is this machine's entire RAM.

| Item | Result |
|---|---|
| Required toolchain | Android SDK 36 / build-tools 36.0.0 (present), **NDK 27.2.12479018** (absent: AGP auto-installed it side by side, **2.4 GB**, into `~/Library/Android/sdk/ndk/`), CMake 3.22.1 (present), JDK 17+ (Dockerfile uses 17; 21 worked), Gradle 8.13 |
| Submodules | 19, fetched with `git submodule update --init --recursive --depth=1` in **5 min 54 s**; checkout grows from 569 MB to **2.2 GB** |
| External dependencies | Maven Central, Google and Huawei repositories (Firebase, Play services, ML Kit, Billing, Stripe, AGConnect) |
| Prebuilt binaries | FFmpeg, BoringSSL, libvpx, dav1d, Opus, OpenH264, `tde2e`/`tdutils`, prisma, tlottie, wallet engine and iwasm are **committed as `.a` files** per ABI in `jni/prebuild/lib/` and linked as-is. Their rebuild scripts (`jni/prebuild/build_*.sh`) were not run |
| **Outcome** | **`BUILD SUCCESSFUL in 16m 6s`, 301 tasks, no compilation failures.** 128 warnings (116 C/C++ deprecations, mostly in WebRTC; Kotlin deprecations in `buildSrc`) |
| Phase timings (file timestamps) | Start 11:36:36 → NDK installed 11:43:39 (about 7 min, mostly download) → `libtmessages.49.so` linked 11:48:10 (about 4.5 min CMake + native compile, including WebRTC) → APK 11:52:43 (about 4.5 min Java compile, dex, package) |
| Output | `TMessagesProj_App/build/intermediates/apk/afat/debug/app.apk`, 66.7 MB. `package: org.telegram.messenger.beta` 13.0.2 (71759), label "Telegram Beta", target SDK 36. `libtmessages.49.so` 41.1 MB (arm64 only; ML Kit's prebuilt library adds the other ABIs, so this APK would fail on non-arm64 devices). Signed with the repository's **dummy** keystore (`CN=Android Developer, OU=Telegram, O=Telegram`) |
| Build artefacts on disk | 1.4 GB (`TMessagesProj/build`) + 962 MB (`TMessagesProj_App/build`) |
| API credentials and configuration | None were needed **to compile**. The tree already contains Telegram's own `APP_ID`/`APP_HASH`, a dummy `google-services.json` and a dummy keystore, which the README says must all be replaced before publishing |
| Runs without Telegram credentials? | **Not launched, deliberately.** As built, the APK would connect to Telegram's production data centres (hard-coded in `tgnet`) using **Telegram's own `api_id`**, which third parties are told not to use. With its own `api_id` it could log in to Telegram, but **no credential makes it work against Griip's backend**, because it speaks MTProto/TL and Griip speaks REST/WebSocket with `griip-envelope-v1` (§3). A successful compile is therefore no evidence of Griip compatibility |
| Not attempted | Release, standalone or bundle builds (R8 full mode over 1.5M lines, all four ABIs, signing). Rebuilding the prebuilt native libraries from source. Instrumentation tests (they need a device and Telegram's test configuration) |

**Difficulties that would affect an ongoing migration:**
1. **Memory.** The default 8 GB heap request and R8 full mode don't fit the current 8 GB workstation. A debug build fitted in 3 GB.
   Release builds weren't tried.
2. **Toolchain pinning.** NDK 27.2 and CMake 3.22.1 sit alongside Griip's toolchain (Griip uses no NDK). Every Telegram release can move these pins.
3. **Native supply chain.** Committed prebuilt `.a` files would have to be rebuilt from source, both to trust them and to satisfy
   GPL "complete corresponding source" (§4.3). That means building FFmpeg, BoringSSL and TDLib for four ABIs; not measured here.
4. **Size.** About 2.2 GB of source and 2.4 GB of build output per checkout, against Griip's small project.
5. **Upstream churn.** Each release regenerates `TLRPC` for a new layer (232 historical layers are kept as test snapshots), so a fork
   that tracks upstream faces large merges in exactly the files it would have modified most.

---

## 6. Features that can be reused or adapted

"Reuse" here means the *behaviour or algorithm* re-implemented in Griip's Kotlin code, or a *permissive upstream library* taken from
its original project. It never means copying Telegram's GPL files.

| Capability | Telegram reference (file) | How Griip would reproduce it | Fixes | Effort |
|---|---|---|---|---|
| **Ordered, exactly-once delivery** | pts/qts/seq gap detection + `getDifference` (`MessagesController`) | The server assigns a per-device monotonic `seq` to every queue row. Live pushes carry it. The client buffers out-of-order frames, fetches the gap from `/v1/queue`, and dedupes control messages by id | P6, T2.1, T2.2 | M |
| **Never lose a message on local error** | Updates are re-fetched until applied | Ack only after a successful local commit; keep failed items for retry with a poison counter | T1.4 | S |
| **Resumable, chunked, parallel transfers** | `FileLoadOperation` (`.temp`/`.pt` part files, 4 parallel requests, 128 KB chunks), `FileUploadOperation` | Chunked AEAD format (for example 64 KB AES-GCM segments with per-chunk nonces derived from a file key), HTTP `Range` downloads, parallel PUT of parts, a persisted part map | Large-file memory use, restarts on interruption (`KNOWN_ISSUES.md`) | M |
| **Streaming playback** | `FileStreamLoadOperation` into ExoPlayer | Media3 (Apache-2.0, from Google) with a custom `DataSource` that decrypts chunks on demand | — | M |
| **Media cache lifecycle** | `CacheByChatsController` keep-media, `AutoDeleteMediaTask`, `DownloadController` presets | Auto-download preferences per network type; size and age cache limits; delete decrypted files when a message is deleted | T1.10 | S–M |
| **Database migrations** | 179 stepwise versions in `MessagesStorage` | Room `Migration` objects with exported schemas and migration tests | "Room schema migrations MISSING" | S |
| **Secure delete** | `PRAGMA secure_delete = ON` | Same pragma on the server's SQLite (and on SQLCipher) | Deleted ciphertext left in free pages (`FEATURE_INVENTORY.md` §11) | S |
| **Push with fallback** | FCM/HMS + `keepAliveService` | Self-hosted UnifiedPush (or FCM with data-only, content-free pings) to wake the app, which then fetches over its own socket. Restart "stay connected" on `BOOT_COMPLETED` | Push gap (D-08) | M |
| **Telecom integration** | `TelegramConnectionService`, `VoIPPreNotificationService` | A `ConnectionService` self-managed call for reliable incoming-call UI and audio focus | Background/locked incoming calls unverified | M |
| **Notification UX** | `NotificationsController` (MessagingStyle, grouping, inline reply) | Same Android APIs in `notify/` | — | S |
| **Protocol regression tests** | `TMessagesProj_AppTests` scheme snapshots | Golden test vectors for `griip-envelope-v1` shared by the Kotlin and TypeScript suites | — | S |

---

## 7. Features that would need replacement in a fork

| Telegram part | Why it can't be kept for Griip | Size |
|---|---|---|
| `tgnet` (native MTProto), `ConnectionsManager`, DNS fallback, DC config | Talks only to Telegram's servers | `jni/tgnet` + 1,568-line Java facade + 1,256 call sites |
| `TLRPC` data model and `tgnet/tl` | Its message, chat, user and update semantics are server-authoritative | 70,246 + 19 files; 31,453 references |
| `LoginActivity`, `UserConfig`, `AccountInstance` | Phone or SMS accounts versus Griip's key-based identities | 10,366 + support |
| `MessagesController`, `SendMessagesHelper`, `MessagesStorage` | Cloud sync, server IDs, TL blobs, plain SQLite | 25,608 + 12,506 + 19,064 |
| `SecretChatHelper` | Different E2E protocol | 2,068 |
| `VoIPService`, `GroupCallActivity`, `VoIPFragment`, `tgcalls`, WebRTC build, `tde2e` | Telegram call signalling and relays versus LiveKit | 5,852 + 10,785 + 3,259 + native |
| Push (`PushListenerController`, Firebase, HMS) | Registers with Telegram's servers; conflicts with D-08 | — |
| Payments, Stars, Premium, stories, bots, ads, wallet, Stripe, Billing, maps | Telegram services with no Griip counterpart; dead UI otherwise | Many screens; all of `wallet-engine` |
| Branding, `BuildVars`, Play/Huawei/Firebase configuration | Telegram identifiers | — |

---

## 8. Effort, risk and regressions per strategy

Estimates assume one experienced Android engineer plus part-time backend support, and are order-of-magnitude only.

| | **A: Preserve, port selected improvements** | **B: Fork and adapt Telegram** | **C: Behavioural reference, rebuild concepts** |
|---|---|---|---|
| Scope | Keep the Compose client, backend and protocol. Port only license-compatible components after review: permissive upstreams such as Media3, dav1d and libvpx, taken from their original projects | Telegram becomes the client. Replace transport, model mapping, login, storage encryption and calls; strip Telegram services; rebuild org/role UI | Keep everything. Re-implement §6 behaviours natively |
| Effort | Low. In practice it merges with C | **XL: roughly 9–18+ engineer-months to reach today's Griip parity**, plus a continuous rebase cost. Telegram ships often (13.0.2 build 7175) and changes `TLRPC` every layer | All of §6: roughly 3–8 engineer-months (sum of the S/M ranges), delivered item by item, each useful on its own |
| Benefit | Small, targeted | Telegram's UI polish and media stack, *after* the replacements | Most of the practical reliability and media gains |
| Regression risk | Low | **Very high:**<br>• E2EE downgrade paths<br>• unencrypted DB<br>• Google/Telegram network leakage<br>• loss of the org hierarchy, roles, device certificates, relay expiry and LiveKit E2EE until rebuilt<br>• the call stack just stabilised (`TESTING.md` §5a) | Low to medium, per item, each behind tests |
| Security regressions introduced | None | Plain SQLite instead of SQLCipher; server-plaintext rendering paths; FCM metadata; hard-coded third-party endpoints; a larger attack surface (1.5M lines, WebView, payments SDKs) | None expected |
| Licensing | None new (check each ported dependency's license) | GPL source obligations for the whole client; LGPL relinking; patent questions | None |
| Product constraints | Met | Breaks "don't redesign", the Compose design system (D-01, D-11) and the Kotlin/Compose mandate | Met |
| Verification | Existing tests plus new tests per port | A full re-qualification of every feature | Per-item tests (see §6) |

---

## 9. Migration steps *if* a fork were chosen anyway

A fork is **not** recommended. If the owners still choose one, these gates limit the damage:
1. **Legal gate:** written opinion on §4.3 questions 1–6 *before* any code is combined.
2. **Clean-room spike (2–3 weeks, throwaway):** a de-branded fork that boots with a stub `ConnectionsManager` answering *locally* with no
   network. It shows the chat list and one chat from a fixture database. Measure which screens crash or call unmapped methods.
3. **Network isolation:** delete the DC tables, RSA keys and DNS fallbacks. Add a test asserting no socket to non-Griip hosts.
4. **Identity and E2EE first:** port `crypto/` and the session layer. All inbound content goes through envelope verification, and nothing renders
   server text.
5. **Storage:** replace the SQLite amalgamation with SQLCipher and a Keystore-sealed key, then verify the database on a device.
6. **Model shim:** map Griip conversations, messages and receipts to `TLRPC` objects with local IDs and pts. Hide every unmapped feature behind flags.
7. **Org and roles UI:** rebuild Griip's org, branch, team and invite screens in the View framework.
8. **Calls:** replace `VoIPService`/`tgcalls` with the existing LiveKit `CallManager`. Re-run `TESTING.md` §5a, including wrong-key tests.
9. **Parity sign-off:** every row of `FEATURE_INVENTORY.md` re-verified. Production switch only after side-by-side internal use.
10. **Upstream policy:** decide whether to track Telegram releases (an ongoing merge cost) or freeze, which forfeits the main reason to fork.

---

## 10. Unanswered questions

1. The legal questions in §4.3, especially whether internal distribution triggers source obligations, and the GPLv2-only versus
   "or later" status of the combined work.
2. Does GriipBuddy intend to keep the client proprietary, or would publishing the client source be acceptable? This decides whether
   GPL code can ever be used.
3. Which Telegram capabilities do testers and owners actually miss? A ranked list would focus Strategy C.
   Candidates: media editor, stickers and animations, large-file transfers, push.
4. Is FCM acceptable, given D-08's privacy stance? Or should push be self-hosted (UnifiedPush/ntfy) on the VPS?
5. Is chunked attachment encryption acceptable as a protocol change? It needs a `griip-envelope-v1` attachment-format version bump.
6. Will Griip ever need Telegram-scale groups (thousands of members)? Today's fan-out design (one envelope per device) wouldn't
   scale. That is a backend and MLS question, not a client one.
7. Build reproducibility: should Griip adopt a Docker-based reproducible Android build like Telegram's `Dockerfile`?

---

## 11. Recommendation

**Preserve the existing Griip Message client and backend, and selectively adapt Telegram's *behaviours*, not its code
(Strategies A + C). Do not fork and rewrite on top of Telegram.**

Start with the §6 items that fix confirmed defects, in this order:
1. ordered, exactly-once delivery (P6), with ack-after-commit (T1.4);
2. Room migrations and the secure-delete pragma;
3. chunked, resumable attachments with a decrypted-file cache lifecycle (T1.10);
4. self-hosted push wake-up.

Revisit a fork only if the owners decide the client may be published under the GPL **and** want Telegram's UI wholesale. Even then,
pass the legal and spike gates in §9 first.
