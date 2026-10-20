# Meldiq round 7 plan (2026-10-19): audit of the master brief against the code

The owner's "ultimate master engineering prompt" (24 phases) was checked against this worktree
(`worktree-orbit-integrations` = internal-beta `35aa4b5`, API 0.7.1 in production) and the web repo
(`/Volumes/code_files/orbit_web`, React + Vite, E2EE client of the same server). This file records what already
exists, what is new, where the brief conflicts with the architecture, and the order of work. Nothing here is
implemented yet.

Baseline: `cd server && npm test` → 61 files, **589 passed**, 10 skipped (2026-10-19 23:26). Working tree clean apart
from the untracked `brand/meldiq-email-avatar-512.png`.

## 1. Identity, package and domain

| Item | Brief | Actual | Decision |
|---|---|---|---|
| Android package | `com.meldeq.android` | `com.meldiq.android` (versionCode 10, 0.2.3-beta) | "meldeq" was confirmed a typo in round 6. Keep `com.meldiq.android`; no change |
| Domain | verify `meldiq.app` | owned (bought 2026-10-19), DNS on Hostinger | done |
| Sender | `no-reply@meldeq.app` | `no-reply@meldiq.app`, live on Hostinger SMTP since 14:10 UTC | done (Phase 16 complete; rotate the pasted password) |
| API hostname | — | app still defaults to `https://msg.griipbuddy.cloud` | moving it to a meldiq.app hostname is the infrastructure plan's step (`MELDIQ_INFRASTRUCTURE_PLAN_2026-10-19.md`) |

## 2. The one architectural conflict: "no local database" vs end-to-end encryption

Phase 3 asks that the server be the source of truth and that persistent local stores be removed. Meldiq is end-to-end
encrypted: the server never holds message plaintext. Today:

- **Server (canonical, encrypted):** the relay queue, plus encrypted message history per account
  (`history_items`, `MESSAGE_HISTORY.md`). A phone that signs in gets its history back from the server.
- **Phone (working copy, encrypted at rest):** Room + SQLCipher with 10 tables (`conversations`, `messages`,
  `outbox`, `users`, `call_log`, `call_keys`, `kv`, `quarantine`, `pending_controls`, `history_outbox`). It holds the
  decrypted messages the phone shows and searches, the outbox for sends that wait for the network, and keys.

Deleting the local store would mean decrypting the whole history from the server on every launch, losing offline
sending, and losing search (the server can't search ciphertext). That weakens the product without making the server
any more authoritative than it already is.

**Recommendation:** keep the SQLCipher store as the documented E2EE exception the brief allows (§3.3), treat server
history as canonical, and remove only what is genuinely obsolete (see §4, "Legacy inventory"). Everything that is not
message content (workforce, friends, presence, stories metadata, invitations, notification history, reminders) is
server-side plaintext business data from the start.

**Owner's decisions (2026-10-19):** keep the encrypted on-phone store (recommended option); start with slice A; the
workforce pilot is the GriipBuddy workspace.

## 3. Brief vs code, phase by phase

Status: **Have** = built and tested; **Partly** = exists, needs the change the brief asks for; **New** = not built.

| Phase | Item | Status | What exists / what's missing |
|---|---|---|---|
| 2 | Cloud reliability | Partly | Health checks, rate limits, idempotent sends, durable reminder jobs, backups and rollback images exist. Missing: worker metrics, alerting, single VPS = single point of failure (documented, not fixed: needs paid capacity) |
| 4 | Dark default, light theme, tokens | Have | Graphite dark default (round 6), light theme, design tokens, reduced motion |
| 5 | Workspace admin, roles, channel ownership | Partly | Six roles plus custom roles, archive, invitations, audit log. Missing: explicit channel owner/manager and ownership transfer |
| 6 | Text / voice / video / conference channels | Partly | `conversations.voice` flag (round 6 voice rooms). **Text channels and groups still show voice/video call buttons in the header** (`ConversationScreen.kt:429`). No video-room or conference type, no scheduled meetings |
| 7 | Media quality | Partly | Self-hosted LiveKit, E2EE media, 12-party calls. No TURN (port-443-only networks fail), no quality stats in UI |
| 8 | Workforce: shifts, clock in/out, attendance, missed checkout | **New** | Nothing. `HR_INTEGRATION_PLAN.md` designed a link to the legacy HR site; the brief now says build it natively and leave the HR site alone |
| 9 | Presence states | Partly | Online / last seen with privacy rules. No idle, do-not-disturb, invisible or expiring custom status |
| 9 | Friends / connections, DM invitations | **New** | Blocking exists; no requests, accept/decline, or "message requests" |
| 9 | External invitations (link, QR, expiry, limits) | Have | Org invites with hashed codes, QR, expiry, max uses, revoke |
| 10 | Contact cards without phone numbers | Partly | Contact cards today **send phone numbers and emails picked from the address book** (`RichRules.kt`). Needs the Meldiq profile card instead |
| 11 | One-time location | Have | Encrypted location message, OSM link |
| 11 | Live location | **New** | — |
| 12 | 24-hour stories | **New** | — |
| 13 | Pinned messages | Partly | Pins exist (encrypted control messages) and show in a context strip under the header. Needs the pin button in the header; padlock is already small |
| 13 | Composer: hide the two left buttons while typing | **New** | Attach and emoji buttons are always shown (`ConversationScreen.kt:976`) |
| 14 | Bulk select and one-action download | **New** | Albums exist; no multi-select of messages/media, no batch save |
| 15 | Server reminders | Have | Round 6: durable, UTC + IANA zone, snooze, recurrence, restart-safe |
| 15 | FCM | Partly | Server side ready (`push/fcm.ts`, migration 020). Firebase project not created (owner action); UnifiedPush/ntfy is live |
| 15 | Notification centre | Partly | Activity tab exists (mentions, reactions, replies). No category preferences, quiet hours |
| 16 | Hostinger email | Have | Live in production |
| 17 | Onboarding introduction | Partly | Intro slides exist; need updating for the new features (only after they ship) |
| 18 | Integrations, stickers | Have | 10+ connectors, licensed sticker catalogue |
| 18 | YouTube/Spotify embeds | Partly | Link previews; no embeds |
| 19 | Entitlements and metering | Partly | `entitlements.ts` (4 keys, unlimited by default). No usage metering |

## 4. Legacy inventory (Phase 3)

| Component | Keep / replace | Why |
|---|---|---|
| Room + SQLCipher message store | Keep (E2EE exception) | see §2 |
| Contact-card phone/email payload | Replace | Phase 10; old cards still render, new ones carry a Meldiq profile reference |
| `READ_CONTACTS` use for contact cards | Remove after the replacement ships | no other feature needs the address book |
| SES mailer path | Keep, unused | rollback for Hostinger SMTP |
| Federation tables | Keep | working feature, not legacy |

## 5. Order of work

Each slice ends with automated tests, an APK and a phone test before the next starts. Web parity follows Android, in
its own session.

| Slice | Contents | Server change |
|---|---|---|
| **A. Conversation UX** | text channels without call buttons; pin button in the header; composer button hide/restore (2 s idle, immediate on outside tap); bulk select and save to a chosen folder; profile contact cards without phone numbers | small (none for A except contact profile link) |
| **B. Channel types** | video rooms and conference channels (title, schedule, organiser, lobby, reminders via the existing reminder engine), channel owner and transfer | migration: channel type column, meetings table |
| **C. Presence and connections** | idle / DND / invisible / custom status with expiry; friend requests, accept/decline/cancel/remove; message requests for strangers | migrations: presence state, connections |
| **D. Workforce (pilot org only)** | shifts, clock in/out with server time, breaks, corrections with manager review and audit, missed-checkout reminders | new module and migrations; feature flag per org |
| **E. Stories and live location** | 24 h stories with server expiry and blob deletion; time-boxed live location with revoke | migrations, cleanup jobs |
| **F. Notifications and push** | notification categories, quiet hours; FCM once the Firebase project exists | small |
| **G. Release** | phone acceptance, push, API deploy with backup and rehearsal | — |

## 6. Slice A: built (2026-10-20), not yet phone-tested

| Item | What changed | Tests |
|---|---|---|
| Text channels without calls | App: no voice/video buttons in a text channel's header, and the Calls tab can't call back an old text-channel call. Server: `POST /v1/calls` refuses a new call in a text channel (409 `text_channel_no_calls`); a call already running there can still be joined; DMs, groups and voice rooms unchanged | `voice_rooms.test.ts` (new case); `activity_scope` and `call_eviction` now call in voice rooms |
| Voice-room events in the activity feed | Found by the test above: `voice_room.create` / `voice_room.open` were missing from the feed's visibility list, so non-admins never saw them. Same rules as channels and calls now | `activity_scope.test.ts` |
| Pin button | Header pin button (with a badge when anything is pinned) opens a sheet of pinned messages: tap jumps to the message (loading older pages if needed), Unpin where you may post. The "Pinned" chip under the header is gone; the padlock keeps the security details | compile; phone test pending |
| Composer | Attach and emoji step aside while typing (the field takes their space), come back after 2 s without typing, at once on any touch on the messages, on focus loss, or when the field empties. Generation-keyed timer, so stale timers can't fire; reduced motion snaps | `ComposerAuxTest` (8) |
| Profile cards | The attach panel's Contact now shares a Meldiq profile (yours or someone you talk to): name, user id, username. No phone number, no address-book access, no contacts permission. Received cards with numbers from older apps still show. A profile card received with numbers in it is stripped to the profile | `RichRulesTest` (2 new) |
| Bulk save | See `ATTACHMENTS.md` › Saving several files | `BulkSaveTest` (6) |

Checks: server `tsc` clean, `npm test` 590 passed / 10 skipped; Android `testDebugUnitTest` 485 passed, 0 failed
(the language-parity test now covers every generated string table, up to round 7); `compileDebugKotlin` clean.
Strings: `android/i18n/strings_round7.py` (English, Chinese, Bengali), generated by `gen.py`.

Test APK: `artifacts/meldiq-test-2026-10-20-r7.apk`, package `com.meldiq.android.test` ("Meldiq Test"), built with
`-Pmeldiq.sideBySide=true` so it installs beside the Play build without touching it.
