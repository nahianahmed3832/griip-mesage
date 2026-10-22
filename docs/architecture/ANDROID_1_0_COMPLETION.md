# Architecture 1.0, Android-first completion (2026-10-23)

The owner's "Architecture 1.0: Android-first completion" brief for the Android app (`android/`), against the current
backend. Branch `architecture-1.0-android` (local, not pushed), built on `architecture-1.0` (rebased onto its `d55f994`). Not merged
into `internal-beta`, nothing deployed, no database or env change. Nothing here is phone-verified.

## Results

| Check | Result |
|---|---|
| Android JVM tests (`./gradlew :app:testDebugUnitTest`) | **677 passed, 0 failed** (baseline at `ec7b7b1`: 654 passed) |
| Contract and serialization tests | 35 passed: `ContractFixturesTest` 3, `RequestContractTest` 3 (new), `RealtimeCodecTest` 10 (new), `ApiRoutesTest` 2 (new), `InviteContractTest` 6, `PushContractTest` 5, `AccountApiContractTest` 6 |
| Crypto interop (shared vectors; the server's `interop_vectors.test.ts` reads the same file) | 9 passed: `EnvelopeInteropTest` 6, `PayloadVectorsTest` 3. Other crypto: 35 passed |
| Lint (`:app:lintDebug`) | **0 errors**, 761 warnings, 11 hints (none in code this work added; five in code it moved) |
| Debug APK | **Built** (`assembleDebug -Pmeldiq.environment=production -Pmeldiq.sideBySide=true`) |
| Server suite | Not rerun: no server file changed (last run on `9201ff1`: 755 passed, 10 skipped, both stores) |

**Unit-tested versus phone-tested.** Every result above is a JVM test or a build. No phone or emulator run was made, so
calls, camera, screen share, notifications and gestures are checked only by their unit tests and by reading the code.
The phone checklist at the end is for the owner.

## What was done, by phase

| Phase | Commit | What |
|---|---|---|
| A. Audit | (none) | `architecture-1.0` is at `ec7b7b1` (F1 on top of D2). Phase A is `4b172a8` and B–D2 are in `internal-beta` at `9201ff1` (local, not pushed): both confirmed. Baseline 654/0 |
| B. Contracts | `f65735a` (F1, earlier) and `8811ac8` | Responses: F1's fixture test (154 answers, every frame and error). Requests (new): `RequestContractTest` checks the 60 bodies the app sends against `docs/api/openapi.json`. It found four production bugs (below) |
| C. Realtime | `95fa3d4` | One sealed `ServerEvent` (one subtype per server frame, `Unknown` for a newer server's), parsed only in `RealtimeCodec`; outbound `ClientEvent` (ping, ack, typing) built by the serializer, so ids are escaped. `MessageRepository` dispatches with an exhaustive `when`; `CallManager` reads `ServerEvent.Call`. Malformed frames are logged, not dropped silently. `RealtimeCodecTest` checks the event set and every outbound frame against `docs/api/realtime.schema.json`. Auth (header, 4401/4001 reauth), the 25 s ping, reconnect, acks and the REST fallback are unchanged |
| D. Duplicates | `b07fcfb`, `f3cd4be` | `MessageColumns` is the one decoder for `attachmentsJson`, `callJson`, `richJson`, the id lists and reactions. `MessagePreview` replaces the four previews. `MessageRepository` (1,289 lines) is split into `MessageSignals` (socket events and their flows), `MessageReceiver` (queue items: verify, decrypt, apply, acknowledge, quarantine) and `MessageControls` (react, edit, delete, vote, pin, read, typing). It keeps sending, call history and queries (819 lines) and delegates the rest, so no caller changed |
| E. Retired routes | `50b65c6` | All three were traced. `GET /v1/orgs/:orgId/groups` fed the meeting editor's group picker, which could never appear (404, and the server refuses any `groupIds`): removed with the field. `GET /v1/workspace-templates` and `POST /v1/orgs/:orgId/apply-template` belonged to a template sheet with no caller left: removed. No working feature lost. `ApiRoutesTest` checks every `/v1` and `/v2` path literal against the OpenAPI document; a mutation run showed it fails on a retired route |
| F. Feature check | `8811ac8` | See "Bugs found" and "Feature inventory" below |
| G. AI | (none) | Skipped. The server has no AI module, route or realtime event (the provider choice is still open, plan §10), so nothing was added. The 42 unused `ai_*` strings stay for the future sheet |
| H. Wrap-up | this file | Tests, lint, APK, this report |

## Bugs found and fixed

| Bug | Since | Effect | Fix |
|---|---|---|---|
| GriipJson leaves nulls out, but four routes require the key and read null as "clear it" (`z.….nullable()`, production 0.26.0 too) | Before 1.0 | These requests fail with a validation error: **ending a member's timeout**, **removing a guest's expiry**, **"No limit" in the guest policy**, and **moving or reordering channels outside a category** (`LayoutChannel.categoryId`) | `@SendNulls` on `TimeoutReq`, `GuestExpiryReq`, `GuestPolicyReq` and `LayoutReq`; `encodeBody` writes their nulls. The security route already wrote its body by hand for the same reason |
| The four chat previews disagreed | Before 1.0 | A received photo read "📎 IMG_1.jpg" but "📷 Photo" for its sender; a restored voice message lost its length; a restored location lost its name | `MessagePreview` (visible change: a single received photo or video now reads "📷 Photo" / "🎬 Video", a sent caption shows "📎 caption" like a received one) |
| The meeting editor's group picker | Groups were retired | Never shown; dead calls | Removed (phase E) |

Known gaps the request test allowlists, each harmless in practice: `MeetingReq.rev` (null when creating, always set when
editing), `HistoryItemDto.body` (always set on upload) and `.seq` (the answer's field, ignored on upload).

## Refactor regressions

None found. What was checked:
- Each typed frame requires exactly the fields its old handler guarded with `?: return`, and defaults the fields the old
  handler defaulted (`queue.items`, `typing.on`, `presence.online`). One difference: an `org.updated` frame without an
  `orgId` used to refresh the conversation list anyway and is now skipped as malformed. The server's contract always
  sends `orgId`.
- The receive path (`MessageReceiver.apply`) is the old code moved unchanged apart from the references (checked in the
  diff); the one force-unwrap of the signed-in user became the `not_signed_in` error the caller already uses.
- Notifications, mention counting, call-key handling, call frames and call-history writes are untouched.
- Not covered by a test: `MessageSignals` itself (it needs the Room database); the codec it relies on is tested.

## The reported problem areas

Checked by reading the code and its tests; none was changed by this work except where noted.

| Area | Code | Automated evidence | Phone |
|---|---|---|---|
| Calls (1:1, ring, recovery after reconnect) | `CallManager`, `RingRecovery` (`CallReachability.kt`) | `CallReachabilityTest`, `CallVerificationTest`, `CallKeyVerifierTest`; call frames now typed (`RealtimeCodecTest`) | Not run |
| Camera and microphone permissions | `CallService` (FGS type from granted permissions), `PreCallCheck` | `PreCallCheckTest`, `CallMediaPolicyTest` | Not run |
| Screen share | LiveKit's mediaProjection-typed `ScreenCaptureService` with Meldiq's notification (`CallManager`), manifest `FOREGROUND_SERVICE_MEDIA_PROJECTION` | `CallTilesTest`, `VideoSinkBindingTest`, `FrameCryptorGuardTest` | Not run |
| Media (attachments, voice, batches) | `MessageRepository` sending, `AttachmentUploader`, `AttachmentPolicy` | `AttachmentPolicyTest`, `AttachmentUploaderTest`, `ChunkedAttachmentCryptoTest`, `MessagePreviewTest` | Not run |
| Notifications | `MessageReceiver.apply` → `notify`, `MentionNotify` | `NotificationHealthTest`, `NotificationHistoryTest`, `MentionRulesTest` | Not run |
| Reply gestures | `SwipeToReply.kt` | `SwipeToReplyTest`, `ReplyTargetTest` | Not run |
| Call logs | `CallHistory`, `recordCall`, `CallsScreen` | `CallHistoryTest`, `CallLogTextTest` | Not run |
| Group mentions | `MentionRules` (server-attested @everyone, members-only resolution) | `MentionRulesTest`, `ConversationMarkersTest` | Not run |
| Multi-person calls | `CallTiles`, voice rooms (hands, moderation) | `CallTilesTest`, `CallAudioTest` | Not run |

## Feature inventory (Android, from the source)

Status: **unit** = JVM tests cover the rules or codec; **build** = compiles, no test; nothing is phone-verified here.

| Family | Main code | Evidence |
|---|---|---|
| Accounts v2, device setup, recovery codes, key backup, legacy migration | `data/account/*`, `ui/screens/onboarding`, `ui/screens/account` | unit (`AccountApiContractTest`, `KeyBackupCryptoTest`, `RecoveryCodeTest`, `AuthFlowTest`) |
| DMs, groups, message requests, connections, blocks | `MessageRepository` + split classes, `ConversationRepository`, `ConnectionRepository` | unit (queue processing, mentions, request rules, fixtures) |
| Encrypted envelopes, history sync, backups | `crypto/*`, `HistorySync`, `BackupManager` | unit (interop vectors, `HistorySyncTest`, `HistoryCryptoTest`, `BackupCryptoTest`) |
| Rich messages: contacts, locations, animated emoji, polls, stickers, link previews, forwarding | `RichRules`, `PollRules`, `LinkPreviews` | unit |
| Attachments up to 1 GiB, voice messages, batches | `AttachmentUploader`, `AttachmentPolicy`, `voice/*` | unit |
| Communities: channels, categories, layout, roles, overwrites, onboarding, guide, expressions, forums, threads | `OrgRepository`, `RoleRepository`, `ui/screens/community`, `ui/screens/workspace` | unit (rules, fixtures, requests) |
| Moderation: reports, applications, bans, timeouts, guests, security pauses, audit, usage | `ReportRepository`, `ApplicationRepository`, `ui/screens/moderation` | unit (requests now include the four null fixes) |
| Meetings and reminders | `ui/screens/workspace/meetings`, `ReminderRepository` | unit |
| Calls, group calls, voice rooms, screen share | `call/*` | unit (see the table above) |
| Realtime: typing, presence, live queue, community and reminder signals | `RealtimeClient`, `RealtimeCodec`, `MessageSignals` | unit (codec) |
| Push wake-ups: FCM, UnifiedPush, "stay connected" | `service/*`, `PushRegistration`, `WakeFetch` | unit (`PushContractTest`, `WakeFetchTest`) |
| Translation (on-device), search, navigation preferences, activity feed | `MessageTranslator`, `NavPreferences`, `ui/screens/home` | unit (rules) |
| Federation | `Federation.kt` | unit (fixtures); server feature is off |
| AI | none | not present (phase G) |

## APK

- **File:** `/Volumes/code_files/meldiq_android/.claude/worktrees/wonderful-margulis-615060/android/app/build/outputs/apk/debug/meldiq-test-arch10-android-production-debug.apk`
  (141 MB; the same bytes as `app-debug.apk` next to it). It's in this branch's worktree, not the main folder, because
  the branch isn't merged.
- **SHA-256:** `3608bf36d3e8008804a6096fef4f9e053e74fe32ee7849bfbba36c26c4756caa`
- **Variant:** debug, side by side ("Meldiq Test", `com.meldiq.android.test`, versionCode 19, 0.5.0-beta), production
  server `https://msg.meldiq.app` (checked in the generated `BuildConfig`). It works with API 0.26.0: the changes are
  client-side only, and 0.26.0 accepts the nulls the fix now sends.

## Owner's phone checklist for this branch

1. **Messages:** send and receive text, a photo, a voice message and a file; check the chat list line on both phones.
   **Expected:** the same preview on sender and receiver ("📷 Photo", "🎤 Voice message (0:05)", "📎 report.pdf").
2. **Typing and presence:** type in a DM. **Expected:** the other phone shows typing; online dots update.
3. **Calls:** a 1:1 call, a group of three, decline, and a call received after the app was in the background.
   **Expected:** ringing, joining and the call history line as before.
4. **Moderation fixes:** time a member out, then end the timeout; set a guest expiry, then remove it; set the guest
   policy to "No limit"; move a channel out of its category. **Expected:** each succeeds (all four failed before).
5. **Meetings:** create and edit a meeting. **Expected:** no "Invite groups" row, saving works.
6. **Report back:** pass or fail per step.

## Follow-ups (not done here)

- Merge `architecture-1.0-android` into `architecture-1.0`, then into `internal-beta`, on the owner's go-ahead.
- A release (R8) build of this branch before signing: the new sealed types and the `@SendNulls` annotation are read
  through generated serializers, which the library's rules keep, but no release build was run here.
- Request enum values (presence modes, report reasons, moderation actions) aren't checked against the schema's enums yet.
- The web client has its own version of the realtime and request checks to do (the web parity session's work).
- Like F1's fixture test, the new tests read `docs/api/*` from outside the module, so Gradle doesn't rerun them when only
  those files change: run with `--rerun` locally after a contract change (CI builds clean). Declaring the inputs needs
  `build.gradle.kts`, left alone because of the owner's uncommitted version edit there.
