# Meldiq full-stack specification: next features (2026-10-21)

Master prompt §4. What is built is described by the matrix (`MELDIQ_FEATURE_GAP_MATRIX.md`) and the earlier specs:
- `MELDIQ_COMMUNITY_CHAT_SPEC.md`: Android screens;
- `MELDIQ_ANDROID_INTERACTION_MATRIX.md`: every action and permission;
- `MELDIQ_AUTH_FLOW_AUDIT.md`: sign-in;
- `docs/EMOJI.md`: the emoji picker.

This file specifies the **next** features end to end, so each can be built without re-deciding: data, API, permission,
realtime event, Android, web and tests.

## 1. Clients and conventions

- **Android** (Kotlin, Compose) is the lead client. **Meldiq Web** (`/Volumes/code_files/orbit_web`, React) follows.
  The web's community rebuild (rail, panel, categories, forum, channel and category menus, removing the retired
  workplace screens) is its own project: see `MELDIQ_IMPLEMENTATION_PLAN.md` W1.
- **API:**
  - JSON over HTTPS;
  - zod-validated input;
  - errors `{ error: { code, message, details? } }` with specific codes (for example `timed_out`, `quota_exceeded`);
  - lists paginate with a cursor;
  - mutations are idempotent where retried (message ids, upload ids).
- **Every permission is enforced on the server** (`authz.ts`, `permissions/`). The apps only mirror rules to hide
  controls (`*Rules` objects with unit tests).
- **Realtime:** WebSocket events tell apps what to reload (`conv.updated`, `org.updated`, `call`), never content.
- **States:** every screen has loading, empty, error-with-retry and offline states. Android queues sends offline
  (outbox); admin actions need a connection and say so.
- **Accessibility:** 48 dp targets, TalkBack labels on icon buttons, headings on section titles, no information by
  colour alone.

## 2. Moderation (MOD-06 done; MOD-07, CH-14)

### 2.1 Timeouts: built 2026-10-21

See matrix MOD-06.

### 2.2 Reports (MOD-07)

- **What:** a member reports a message or a person to the community's moderators (in-community) or, for illegal
  content and spam, to the server operators (platform).
- **Encryption:** the reporter's app includes the decrypted text and attachment hashes of the reported message, plus
  up to 5 messages of context they can read. The report sheet says plainly that this content is shared with the
  moderators. Nothing is sent without the reporter's action.
- **Data:** `reports` (id, org_id or null for platform, reporter, target_user, conversation_id, message_id, reason
  code from a fixed list plus free text ≤ 500, content JSON encrypted at rest with the server key, status
  open → actioned / dismissed, handled_by, timestamps). Retention: deleted 90 days after closing.
- **API:**
  - `POST /v1/reports`, rate limited to 10 an hour per person;
  - `GET /v1/orgs/:id/reports?status=open` for people holding `reports.view` (new permission, in the Moderator
    template);
  - `PATCH /v1/reports/:id { status, note }`.
  - Platform reports go to an operator queue (`admin reports`).
- **Events:** `org.reports` with a count to moderators.
- **Android:** message menu › Report and profile › Report (reason chips, optional note, a consent line). Community
  settings › Moderation › Reports (list, open, timeout / remove / ban / dismiss).
- **Tests:**
  - only reporters and moderators can read a report;
  - a report can't target a conversation the reporter can't read;
  - the rate limit;
  - status changes are audited.

### 2.3 Slowmode (CH-14)

- **Data:** `conversations.slowmode_sec` (0–21,600) and `conversation_members.last_post_at`.
- **Enforcement:** `POST /v1/messages` refuses with `slowmode` and `retryAfter` when
  `now - last_post_at < slowmode_sec`. This applies to message envelopes only, not controls like reactions or
  receipts. People with `channels.manage` or the new `messages.bypass_slowmode` are exempt.
- **Android:** Edit channel › Slowmode (off, 5 s, 10 s, 30 s, 1 min, 5 min, 15 min, 1 h, 6 h). The composer shows a
  countdown after posting.

## 3. Two-factor sign-in (AUTH-05)

- **Factors:** TOTP (RFC 6238, 30 s, 6 digits, SHA-1 for compatibility with authenticator apps). The existing
  recovery codes are the backup factor. No SMS.
- **Data:** `account_mfa` (account_id, secret encrypted with the server key, created_at, last_used_step for replay
  protection). It sits in the accounts domain, so it is MongoDB-ready.
- **Flow:**
  - Settings › Security › Two-step sign-in: password re-check, show the secret and QR code, confirm one code, done.
  - At sign-in: email and password first; if MFA is on, `login` answers `mfa_required` with a short-lived ticket;
    the app shows "Enter the code from your authenticator" with the help link "Use a recovery code instead".
  - Recovery choices stay behind help links (`MELDIQ_AUTH_FLOW_AUDIT.md`).
- **Community setting (MOD-12):** "Moderators need two-step sign-in" makes the server refuse moderation actions from
  accounts without MFA.
- **Tests:**
  - wrong, reused and expired codes;
  - rate limits (5 tries, then 15 minutes);
  - the ticket expires;
  - recovery code instead;
  - disabling needs a code.

## 4. Custom community emoji (EXP-04)

- **Data:** `community_emoji` (id, org_id, name `[a-z0-9_]{2,32}` unique per community, object key, sha256, animated,
  created_by, created_at). Up to 100 per community at first.
- **Upload:** the existing upload path into S3 (`emoji/<org>/<id>`). The server checks the PNG, GIF or WebP header,
  ≤ 256 KiB, and decodes the size to 128×128 or smaller. These images are not end-to-end encrypted: they are
  community branding, like the icon.
- **Messages:** text carries `:name:`. The encrypted message also carries a map `name → {id, sha256}`, so receivers
  show the right image even after renames, and verify its hash.
- **Permission:** `expressions.manage`.
- **Picker:** a section per community at the top of `EmojiPanel`.

## 5. Onboarding questions (COM-07)

- **Data:** `community_onboarding` (org_id, enabled, prompts JSON: `[{id, title, single, required,
  options:[{id, title, description, emoji, roleIds, channelIds}]}]`, default_channel_ids). This is plaintext
  configuration, like roles.
- **Flow:**
  - After joining (or from "Customise community"), the app shows the prompts as cards.
  - `POST /v1/orgs/:id/onboarding/answers {optionIds}`: the server grants the options' roles (only roles marked
    `self_assignable` through onboarding) and joins the listed public channels.
  - Re-answering removes roles granted by earlier answers.
- **Permission:** `workspace.edit` to configure.
- **Tests:**
  - only self-assignable roles are ever granted;
  - private channels are never granted directly;
  - re-answering;
  - the required-prompt check.

## 6. Automatic moderation within encryption (MOD-08)

The server can't read messages, so Discord-style keyword rules can't run there. Meldiq does what is possible:

- **Mention spam:** the server sees mention metadata (`mentionAll` and the mention count sent with envelopes). Rule:
  more than N user mentions in one message, or @everyone more than M times an hour, is refused (`mention_limit`) and
  optionally times the sender out (MOD-06).
- **Join raids:** joins per minute above a threshold pause open joining and alert moderators (`abuse.ts` has the rate
  primitives).
- **Keyword filter on devices:** the community publishes a word list (plaintext configuration). Senders' apps warn
  before posting a match and receivers' apps blur matches. This is advisory: a modified client can ignore it, so it
  is documented as such.
- **New-member limits:** posting with links or files waits N minutes after joining (server-checked from
  `joined_at`; links are detected by the sender's app, files by the upload).

## 7. Tests for every feature

- **Server:**
  - Vitest suites for permissions (allowed, refused, hierarchy), validation, rate limits and audit entries;
  - run on SQLite and with the feature's domains in MongoDB once moved.
- **Android:** `*Rules` unit tests, ViewModel tests with fakes, an emulator walk-through with screenshots in
  `docs/product/screens-*`.
- **Web:** Vitest for rules, plus Playwright against a real local server.
