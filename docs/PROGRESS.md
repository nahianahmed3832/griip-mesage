# Progress log

## Feature-first phase (from 2026-10-16)

The UI/UX redesign is postponed until the prioritised functionality is implemented and verified. These identifiers are unchanged:
- Android package `com.griipbuddy.message`;
- Compose project `griip-internal-messaging`.

Plan: `FEATURE_ROADMAP.md`. Status of every feature: `FEATURE_INVENTORY.md`. Debt register: `TECHNICAL_DEBT.md`.

| Step | Status | Evidence |
|---|---|---|
| Audit of the whole prototype (server, schema, Docker, transport, crypto, LiveKit, org system, Android, tests) | ✅ | `FEATURE_INVENTORY.md`. It found 6 BROKEN rows, many UNVERIFIED rows and no MOCKED features |
| Audit probes P1–P7 against the real API | ✅ | All 7 suspected defects confirmed. The probe file was deleted after recording (results in the inventory) |
| Baseline checks | ✅ | Server 32/32 tests, `tsc` clean; Android `compileDebugKotlin` and `assembleRelease` (R8, 64 MB unsigned) succeed. The release APK hasn't been run on a device. Production `/healthz` OK |
| **T1.1 Demotion to Guest revokes access** (P1) | ✅ server | See below |
| T1.2 Enforce pinned identity keys | ⏳ | Owner decision (2026-10-16 master prompt): **block** sending and receiving on an identity change until the user accepts. Needs T2.4 |
| **T1.3 Evict removed members from live calls** (P3) | ✅ server | See below. Not deployed |
| T1.4 Never discard messages on local failure | ⏳ | Needs T2.4 |
| **T1.6 Registration gating, quotas, limits** (P5) | ✅ server | See below. Android invite field pending; not deployed |
| **Task 1: Android video calls and screen sharing** | ✅ phone + bot | See below. Two-phone Android ↔ Android still needs testers |

### T1.6 Invite-only registration, upload quotas, container limits (2026-10-16)

- **Defect (probe P5):** anyone on the internet could register, DM anyone, upload 25 MB per request with no quota, and create orgs. Uploads were
  buffered whole in a 512 MB container. Compose limited memory only, on a VPS shared with production apps.
- **Owner decision:** invite-only registration for the internal beta.
- **Change (server):**
  - `REGISTRATION_MODE` defaults to `invite`. `POST /v1/auth/register` needs `inviteCode`, which is either:
    - a **registration invite** (new table `registration_invites`, migration 002; issued only by `admin.js reg-invite`; listed with `reg-invites`; revoked with `reg-invite-revoke`); or
    - a valid **org invite**, which also joins the person to that org, branch or team (shared `joinOrgByInvite`, extracted from `/v1/invites/accept`).
  - Lookup and use counting happen inside the registration transaction. Codes are stored as SHA-256 only.
  - Existing accounts are untouched, because login doesn't check invites.
  - **Attachments** stream from the request to a `.part` file through a byte counter (no buffering). Limits are checked before reading:
    - per-user bytes per 24 h and stored bytes (`429 quota_exceeded`);
    - per-user and global concurrent uploads and in-flight bytes (`429 too_many_uploads`);
    - a server-wide stored-byte cap (`507 storage_full`).

    Oversized or interrupted streams delete their `.part` file, and leftovers are removed at startup.
  - **Deploy:** Compose gets `cpus` (api 1.0, LiveKit 1.5) and `pids_limit` (256, 512). `.env.example` documents the new settings, and `verify.sh` warns below 5 GB free disk.
  - **Reference client and smoke test:** `register(inviteCode?)`. `prod-smoke.ts` reads `GRIIP_INVITE`. The test harness runs in `open` mode except for the gate's own suite.
- **Tests:** `server/test/registration_quotas.test.ts` has 12 tests:
  - no code or a bad code refused, with nothing created;
  - use limits, with loose typing accepted;
  - only a hash stored;
  - expired and revoked codes refused;
  - an org-invite registration that joins the org and team and consumes the use;
  - existing login unaffected;
  - no HTTP route mints registration invites;
  - a chunked upload of unknown length streamed and served back byte-exact;
  - oversized declared and streamed bodies refused, with no `.part` left;
  - the per-user concurrency limit;
  - the daily quota;
  - the global cap;
  - stale `.part` cleanup.
- **Results:** `npm test` gives **56/56** on three consecutive runs, and `tsc` is clean. Not run: `docker compose config` (Docker isn't installed on this workstation).
- **Not done:**
  - **Android onboarding needs an invitation-code field** before this is deployed (Android phase).
  - **Not deployed** (`RELEASE_PLAN.md`).
- **Security:** closes P5, D5 and D26 once deployed, and fixes the server half of D15. Residual: an org owner can invite people to register.

### T1.3 Evict removed members from live calls (2026-10-16)

- **Defect (probe P3):** removing someone from a conversation, or from the org, branch or team that gives them a channel, left them in its LiveKit room. They kept sending and receiving media.
- **Cause:** none of the membership-removal paths touched calls. `SECURITY.md` §7 claimed they did.
- **Change (`server/src`):**
  - `routes/calls.ts`:
    - `callAdmission` (member, own non-revoked device, account not disabled);
    - `evictFromCall`: removes non-admitted identities found in LiveKit *or* in `call_participants`, marks them left, sends `ended` with `endReason: "removed"` to the removed person's devices (only the revoked device when the person stays a member), then updates the call or ends it when empty;
    - `evictRemovedMember` and `evictUserDevicesFromCalls`.
  - **Called from:**
    - conversation member removal and leave;
    - org role change, including demotion to Guest;
    - org removal and leave;
    - branch and team removal (also covers team moves);
    - device revocation.
  - The LiveKit `participant_joined` webhook ejects a non-admitted identity at once. That covers someone rejoining with a pre-removal token, which stays valid for up to 10 minutes.
  - `jobs.ts` runs eviction over every live call each cleanup interval. This repairs removals missed while LiveKit was down and catches accounts disabled through the admin CLI (a separate process). It also gained an in-flight guard (D19).
  - `livekit.ts` lists identities, reports a removal as removed, absent or error, and bounds each LiveKit call to 5 s.
- **Tests:** `server/test/call_eviction.test.ts` has 8 tests against a recording LiveKit stub:
  - group removal, with a removal notice, 404 on join and connected, and others staying in the call;
  - team, branch, Guest-demotion and org-leave paths in one org;
  - an unrelated call left untouched;
  - nothing removed when membership doesn't shrink;
  - the last member's removal ending the call;
  - device revocation;
  - LiveKit unreachable, then repaired by cleanup;
  - a webhook rejoin;
  - a CLI-disabled account.
- **Results:** with the fix, `npm test` gives **44/44** and `tsc` is clean. With the `server/src` changes stashed, **7 of 8 fail**. The one that passes is the "nobody removed" negative test.
- **Not done:**
  - **Not deployed** (`RELEASE_PLAN.md`).
  - A production check with Griip Bot is part of the release smoke test.
  - The media key isn't rotated after a removal (T5.4). The removed person can't get SFU packets once ejected, but could decrypt any they had captured.
- **Security:** closes P3 and D3 once deployed. The Android app needs no change: it already tears down on an `ended` event for its current call.

### T1.1 Demotion to Guest (2026-10-16)

- **Defect (probe P1):** demoting an org member to Guest left all their branch, team and channel memberships in place, including private team channels. Other members' clients kept encrypting those channels' messages to the guest's devices.
- **Change:**
  - `Authz.demoteToGuest` (`server/src/authz.ts`) runs when the role changes *to* Guest. It deletes the person's branch and team memberships in that org and all of their channel memberships there.
  - DMs and groups aren't org-scoped and are kept.
  - The role-change handler (`server/src/routes/orgs.ts`) now sends `conv.updated` for every conversation the person was removed from, for any role change. Before, it re-validated silently. The Android client already reacts by refetching, getting 404 and marking the channel removed, so no app change was needed.
- **Unchanged:**
  - Existing guests' explicit grants.
  - Promotion back to Member auto-joins only scope-wide channels. Branch and team access must be granted again.
- **Tests:** a new `server/test/orgs.test.ts` › "demotion to guest" block covers four things:
  1. Removal from every channel kind, plus removal from branch, team and device lists, with `conv.updated` received on the person's socket.
  2. An envelope still addressed to the guest's device is refused with 409 `device_mismatch`.
  3. An explicit re-grant survives later re-validation.
  4. Promotion restores only scope-wide channels.
- **Results:** with the fix, `npm test` gives **36 / 36 pass** and `tsc --noEmit` is clean. With the two source changes temporarily stashed, the 4 new tests **fail**, confirming they detect the defect.
- **Not done:**
  - **Not deployed.** Production still runs the old behaviour until an approved redeploy (`deploy/scripts/deploy.sh`).
  - A demoted person who is in a live call stays in it. That's T1.3.
- **Phase 1 re-verification (2026-10-16, feature pass):**
  - The source and diff were re-read.
  - The test now also proves a non-org **group** membership survives demotion. Before, only the DM was checked.
  - `npm test` gives 36/36 and `tsc` is clean.
  - The release diff, deployment steps and rollback are in `RELEASE_PLAN.md`. **Still not deployed**: production needs explicit approval.

### Task 1: Android video calls and screen sharing (2026-10-16)

- **Reports:** testers couldn't see the other person's camera (A) or screen share (B).
- **Diagnosis:** a debug-only `CallDiagnostics` (`CallDiag` log tag) logs room and track events and, every 3 s, encode and
  decode counters per video track. It never logs keys, tokens or content. On the phone, frames were decoded with E2EE OK and
  the renderer was attached at full size, yet nothing was drawn. So the fault was after decode, in rendering.
- **Root cause (shared by A and B):** `VideoTile` released its renderer straight after creating it (an effect keyed on
  renderer state disposed with the live renderer). That hid every remote camera, every received screen share and the
  self-preview. Publishing, the SFU, signalling and E2EE were never at fault, and the server wasn't changed.
- **Changes:**
  - `VideoSinkBinding.kt` (new) owns the renderer's sink attachments and its single release.
  - `CallOverlay.kt`: `VideoTile` uses it, releases in `AndroidView(onRelease)`, and applies mirror and scaling on update.
    Fitted (screen-share) tiles letterbox instead of cropping. The group "You" tile shows the camera, not your own share.
  - `CallManager.kt` wires the diagnostics and logs when the system stops a screen capture.
  - `bot.ts`: `GRIIP_BOT_SCREEN=1` publishes a screen-share track mid-call.
- **Tests:** `VideoSinkBindingTest` 8/8. Phone acceptance against the bot is in `TESTING.md` §5a: camera both ways, screen share
  both ways, wrong-key screen-share E2EE (0 frames), 3-party grid, end-of-call cleanup, with no crashes.
- **Open:**
  - Two-phone Android ↔ Android verification.
  - The group "You" tile while sharing, on a device.
  - One post-fix SIGABRT in the E2EE frame-cryptor path from an earlier session (`KNOWN_ISSUES.md` › Calls).

---

## Internal beta build (2026-10-16, before the audit)

| Milestone | Status | Notes |
|---|---|---|
| 1. Environment & design foundation | ✅ | Toolchain verified. Reference analysed with sampled colours (`DESIGN_SYSTEM.md`). Compose design system written: tokens, glass components, hero art. Isolated Compose project. |
| 2. Identity & real messaging | ✅ | Ed25519 identity and device certificates, Keystore-sealed keys, challenge auth, `griip-envelope-v1` E2EE, outbox with retry, offline queue, 7-day TTL with cleanup, receipts. |
| 3. Organisation & branch system | ✅ | Orgs, branches, departments, teams (including cross-branch), public/private/announcement channels, invites (codes, QR, links), 6 roles enforced on the server, directory, member and role management, activity feed. |
| 4. Messaging experience | ✅ (mostly) | Replies, threads, reactions, mentions, edit and delete, pins, read receipts, typing, presence, unread counts, mute, drafts, local search, attachments. Emulator/UI screenshots of every screen are still pending. |
| 5. Voice & video | ✅ | LiveKit 1.13.10 deployed. Short-lived room tokens, voice and video calls, controls, ring, decline and missed states, reconnect states. **Verified on a physical phone** after fixing a native-init crash. |
| 6. Screen sharing & security | ✅ (partial) | MediaProjection consent flow, sharing indicator and Stop verified on the phone, with the remote screen track received. Media E2EE verified on production with right and wrong keys (Node peers) and phone ↔ bot. Wrong-key test for the screen track is still to do. |
| 7. Visual & functional regression | 🟡 | Physical-phone run found and fixed four defects: call crash, dark-mode contrast, stale draft, media-key overwrite. Full per-screen visual pass remains. |
| 8. Packaging & report | ✅ | APK in `artifacts/`, docs complete, deployment verified and isolated. |

## Timeline (2026-10-16, local)
- Inspected the environment and the design reference, then wrote the design system.
- Built the backend and its 32 integration tests, all green. A config env-parsing bug was caught by the tests and fixed.
- Built the Android app, which compiles cleanly against verified dependency APIs.
- **The VPS couldn't be reached at first:** the only saved SSH host was an unrelated IP with a changed host key, and I didn't bypass that.
  Once Hostinger access was available, I found the real VPS (72.60.107.88), and with your approval added this Mac's SSH key and inspected the server read-only.
- With your approval, deployed:
  - DNS records for `msg` and `rtc`, and firewall rules for 7881/tcp and 7882/udp.
  - A new nginx site and certificate.
  - The `griip-internal-messaging` Compose project.
- Production smoke test 8/8, and the E2EE media right-key/wrong-key test passed.
- APK defaults to production.
- **Physical phone testing (HONOR, Android 16):**
  - Messaging interop both ways, attachments, receipts and typing.
  - Calls crashed. The cause was found and fixed, then voice, video and screen share were verified.
