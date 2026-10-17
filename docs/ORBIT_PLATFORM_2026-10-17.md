# Orbit platform round (2026-10-17)

Brief: "Core Communications Platform, Privacy Architecture, and Premium Android Experience". Built on `internal-beta`
at `25f7b40` and committed on top of it (see the end); **not pushed or deployed**. Physical-device and two-phone testing
is still to do (checklist below).

## 1. Implemented and remaining

| Area | Status | Where |
|---|---|---|
| Navigation: Chats, Calls, Contacts, Settings; no floating button; Workspace opens from Contacts | Done | `AppRoot`, `ContactsScreen`, docs/SETTINGS.md |
| Profile: username, first/last/display name, photo, bio, birthday, phone; per-field privacy; blocked list | Done (server + app) | docs/PROFILE_AND_PRIVACY.md |
| Settings restructure: Accounts, Chats, Privacy & Security, Notifications & Sounds, Calls, Data & Storage, Language, hidden developer options | Done | docs/SETTINGS.md |
| Account switcher (isolated per-account keys, database, files; sign out of one) | Done, on the existing `ProfileStorage` containers | docs/SETTINGS.md |
| Chat settings: theme, accent, wallpaper, text size, animated emoji, links, auto-download | Done | `ChatSettingsScreen` |
| Interface in English, 简体中文, বাংলা with an in-app language switch | **Partial**: infrastructure complete; main surfaces translated, many screens still English | docs/LANGUAGES.md |
| Message translation, on the phone (ML Kit), with consent before the model download | Done (unverified on device) | docs/LANGUAGES.md |
| Composer: up to 10 photos/videos/files; camera photo and video; contact cards; one-time location; forwarding; cancel before sending | Done (unverified on device) | docs/ATTACHMENTS.md, docs/RICH_MESSAGES.md |
| Files up to 500 MB (chunked encryption above 25 MB, server-advertised limit) | Done in code; **needs server env and proxy** | docs/ATTACHMENTS.md › Large files |
| Video sent as original by default; compression only when chosen; size estimates | Done | `BatchComposer` |
| Animated emoji (app-drawn motion, 20 emoji, static when disabled) | Done | docs/RICH_MESSAGES.md |
| Links: http(s) only, Custom Tab / external / ask, misleading-address warnings, sender-side previews (off by default) | Done | docs/LINKS.md |
| Calls: reconnection (client rejoin 45 s, 1:1 peer grace 30 s on client and server) | Done (unverified on phones) | docs/CALLING_AND_E2EE.md |
| Calls: verification code from the actual call key (HKDF), with an honest explanation | Done | `CallVerification` |
| Calls: picture-in-picture with Mute / Hang up | Done (unverified on device) | `CallPip` |
| Notification sounds: Orbit tone and ringtone (original), channel migration keeping blocked/quiet choices, group and preview controls | Done | docs/NOTIFICATIONS.md |
| Encrypted backup: now, automatic (file chosen via Android's picker, e.g. Google Drive), restore with checks | Done (unverified on device) | docs/BACKUP.md |
| Server storage audit | Done (document) | docs/STORAGE_AND_DATA.md |
| Onboarding: server choice hidden from ordinary sign-up; early username validation | Done (small) | `Onboarding.kt` |
| Removing the permanent "Orbit is connected" notification without losing delivery | **Not done**: needs a push service (FCM/UnifiedPush) | docs/NOTIFICATIONS.md |
| Native Google Drive API backup | **Not done**: needs an OAuth client for the signing key | docs/BACKUP.md |
| Login/registration redesign, broad visual polish beyond the screens above | **Partial** | — |
| Profile photos and new profile fields for people on other servers | **Not done**: not relayed by federation yet | — |

Earlier fixes (invitations/QR, viewer, duplicate workspaces, call history, swipe actions, reply cleanup, mentions, group
tiles, echo, quality, insets, Delete Workspace confirmation-only) are kept unchanged; their tests still pass.

## 2. Privacy and storage changes
- Profile fields are published data with per-field audiences enforced by the server; birthday, phone and phone discovery
  start as "nobody". Profile photos are **not end-to-end encrypted** (the server stores and serves them by audience).
- Fixed: online status was shown in conversation and workspace member lists and live presence regardless of "last seen";
  it now follows the setting everywhere.
- Username lookups from other servers respect "find me by username".
- Link previews never make recipients fetch a page; translation never sends text off the phone; locations are one fix,
  never tracked; contacts are one picked entry, never the address book.
- Backups hold no private keys; restore never replaces or deletes local data.
- Server data and retention: docs/STORAGE_AND_DATA.md. No existing data is deleted or migrated; migration 008 only adds
  nullable columns.

## 3. Assumptions and credentials
No credentials were added to the app or server. Not configured, and needed for the parts marked above: Firebase Cloud
Messaging (push), a Google Cloud OAuth client (Drive API). ML Kit downloads translation models from Google on first use
(on phones without access to Google's servers, translation reports that it can't download the model). New dependencies:
Media3 Transformer 1.9.0 (earlier), AndroidX Browser 1.8.0, ML Kit Translate 17.0.3 and Language ID 17.0.6, WorkManager
2.10.5. ML Kit adds about 17 MB per Play install (45 MB in the universal APK).

## 4–5. Tests, lint and builds

| Check | Result |
|---|---|
| Server suite (vitest) | **168/168** (25 files), incl. new `profile_privacy` (11) and `call_reconnect` (4) |
| Server typecheck | pass |
| Android unit tests | **239/239** (50 classes), 29 new: `ProfileRulesTest` 4, `ChunkedAttachmentCryptoTest` 3, `RichRulesTest` 7, `CallVerificationTest` 4, `BackupCryptoTest` 3, `UiRulesTest` 6, `MigrationsTest` +1, `AttachmentPolicyTest` +1 |
| Android lint | 0 errors, 80 warnings (mostly newer library versions, KTX suggestions, unused resources), 5 hints |
| `assembleRelease` (R8) | pass, 115.4 MB unsigned universal APK |
| `bundleRelease` | pass, 63.6 MB unsigned (language splits off, so every language ships) |
| `assembleDebug` (standard and side-by-side) | pass |
| Emulator / two-phone | **not run** this round |

## 6. For physical-device testing
1. Settings: every screen; accent, wallpaper, text size; switch language to 中文 and বাংলা and back (layout of long strings).
2. Accounts: add a second account, switch both ways, sign out of one (the other stays), remove one.
3. Profile: photo (camera roll), birthday without year, phone; check what a contact and a stranger see; block and unblock.
4. Composer: camera photo and video (permission prompt), 10-item album, a 200–500 MB file (after the server change),
   cancel an upload, contact card (Find on Orbit), location (permission, indoor accuracy), forward a photo and a voice note.
5. Links: open in Orbit / another app / ask; a punycode link warning; previews on (sender) and the recipient's view.
6. Translation: first-use consent and model download; Bengali → English; offline failure message.
7. Calls: verification code matches on both phones; toggle airplane mode for 10 s mid-call (it should recover), then for
   60 s (it should end as lost, with history); picture-in-picture with video and voice; Mute/Hang up from the PiP window.
8. Notifications: new tone and ringtone; turning sound or vibration off; group notifications off (mentions still notify);
   message text hidden; a channel blocked in Android before updating stays blocked.
9. Backup: back up now (with and without media) to phone storage and to Google Drive; automatic daily; restore on a
   second phone signed in to the same account; wrong password; a backup of another account is refused.

## 7. Backend changes needing a Docker deploy (not performed)
Service **`api`** of `griip-internal-messaging`: migration **008** (profile columns), profile/privacy/photo/phone
endpoints, presence privacy, member-list privacy, federation username-lookup privacy, the 1:1 reconnection grace period,
higher attachment defaults (500 MB per file; daily 2 GB, stored 4 GB, total 10 GB, in flight 1 GB). Before raising
`ATTACHMENT_MAX_BYTES` in production: nginx `client_max_body_size` (≥ 520 MB) and upload timeouts on the API site.
Profile photos are stored under `DATA_DIR/profile-photos` (inside the existing data volume). Back up first. LiveKit: none.

## 8. Unresolved decisions
- Application id: the brief says `com.griipbuddy.orbit`; the Play Console app is `com.griipbuddy.Orbit` (case-sensitive,
  permanent). The build keeps `com.griipbuddy.Orbit`; the code namespace is `com.griipbuddy.orbit`.
- Push service (FCM or UnifiedPush) to drop the persistent background notification.
- Native Drive API backups (OAuth client) versus the file-picker approach.
- Whether profile photos should become end-to-end encrypted (per-contact keys), and whether profiles should federate.
- Username changes: once an hour; old `@name:server` addresses stop resolving.
- ML Kit's size: keep, or move translation to an on-demand feature module.
- Native-speaker review of the Chinese and Bengali text, and the remaining English screens.

## 9. APKs

| | `artifacts/Orbit-Platform-2026-10-17-testing.apk` | `artifacts/Orbit-Platform-2026-10-17-testing-standard-id.apk` |
|---|---|---|
| Package | `com.griipbuddy.Orbit.test` ("Orbit Test"), installs beside the Play app | `com.griipbuddy.Orbit` ("Orbit") |
| Version | 0.1.2-beta (3), debug | 0.1.2-beta (3), debug |
| Signature | Android Debug key, v2 (cert SHA-256 `b602dcff…0fe587`) | same |
| Size | 141,708,978 bytes | 141,708,962 bytes |
| SHA-256 | `ffdb5ad5edd9c3761d3655d41b0f569dd532ea95a5b5689135757f6c4cd2c8ab` | `2b20fde69f6f2dea1e6215ccc84b1eb8ce82b7af4ecbe84b4f7b8a5f0032de1e` |

Built from `25f7b40` plus this round's changes before they were committed, from the same source as the commits below
(the tested source). Earlier APKs in `artifacts/` are untouched.

## 10. Git
Branch `internal-beta`, on top of `25f7b40` (= `origin/internal-beta`), five local commits, **not pushed**: server
profiles and privacy (`8f46a9c`), 1:1 call reconnection (`1998a58`), 500 MB attachment limits (`9219ac8`), the Android
app (`59047d0`; one commit because its features share files), and these docs.

Commit identity, set for this repository only (the global Git config is unchanged): `MD.NAHIAN AHMED
<254077813+nahianahmed3832@users.noreply.github.com>`, the GitHub noreply address of `nahianahmed3832` (account id
254077813), as author and committer. These commits carry no AI co-author line. Earlier commits keep their original
attribution.
