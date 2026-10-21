# Meldiq feature gap matrix (2026-10-21)

Meldiq's status for every feature in `DISCORD_FEATURE_AUDIT.md`, by the same ID. Control-level evidence (round 3, 2026-10-21) is in `MELDIQ_LIVE_DISCORD_INSPECTION.md`, `MELDIQ_SOCIAL_AND_COMMUNICATION_AUDIT.md`, `MELDIQ_COMMUNITY_INVITATION_SPEC.md` and `MELDIQ_CALLING_SCREENSHARE_AUDIT.md`; the Batch column there (B1–B13) is the order of work, each batch gated on the owner's phone test. Kept current as work lands: update
the row in the same change.

**Status:**
- **R** researched
- **S** specified
- **Des** designed
- **I** implemented
- **UT** unit-tested
- **IT** integration-tested (server tests with a real database, or Playwright against a real server)
- **E2E** end-to-end verified on the emulator or a phone
- **B** blocked
- **D** deferred
- **—** won't build (with the reason)

The highest level reached is listed. "Phone" means a physical device.

**Columns:** A = Android, W = Meldiq Web (`/Volumes/code_files/orbit_web`), S = server.
**Priority:** P1 next, P2 soon, P3 later.

Meldiq differs from Discord by design in two ways:
- Message content is end-to-end encrypted, so server-side features that read messages (link embeds fetched by the
  server, AutoMod on text, server search of text) are not possible as Discord builds them. Equivalents run on the
  device or use metadata only.
- Meldiq keeps no paid tiers or boosts.

## A. Accounts and authentication

| ID | Feature | Status | A | W | S | Pri | Notes / next |
|---|---|---|---|---|---|---|---|
| AUTH-01 | Registration with email code | E2E | I | I | I | — | Emulator 2026-10-21; web Playwright `accounts.spec` |
| AUTH-02 | Login | E2E | I | I | I | — | |
| AUTH-03 | Email verification | E2E | I | I | I | — | Owner reports it working; emulator rechecked |
| AUTH-04 | Password reset | IT | I | I | I | — | Web Playwright through the new help view |
| AUTH-05 | 2FA (TOTP) | R | — | — | — | P2 | Not built. Needs a TOTP secret per account (server), enrolment and challenge steps, recovery via the existing recovery codes, and community setting "2FA for moderators" (MOD-12). Spec in `MELDIQ_FULLSTACK_SPEC.md` §3 |
| AUTH-06 | Sessions and devices | I | I | I | I | — | Settings › Sessions; remove a session |
| AUTH-07 | Recovery without email | E2E | I | I | I | — | Recovery codes move the account to a new address |
| AUTH-08 | Recovery choices hidden by default | E2E | I | IT | — | — | 2026-10-21, `MELDIQ_AUTH_FLOW_AUDIT.md` |
| AUTH-09 | Encryption keys on a new device | E2E | I | IT | I | — | Emailed code (key backup), another device, recovery key, start fresh |
| AUTH-10 | Legacy account move | I | I | I | I | — | Behind "Having trouble signing in?" |
| AUTH-11 | Delete account | I | I | I | I | — | `docs/ACCOUNT_DELETION.md` |

## B. Navigation and identity

| ID | Feature | Status | A | W | S | Pri | Notes / next |
|---|---|---|---|---|---|---|---|
| NAV-01 | Community rail | E2E | I | D | — | P1 (web) | Web still shows the workspace layout: the web community rebuild is the largest web gap |
| NAV-02 | DM list | E2E | I | I | — | — | |
| NAV-03 | Tabs: Home, DMs, Activity, You | E2E | I | D | — | P1 (web) | |
| NAV-04 | Activity (mentions, replies) | E2E | I | I | I | — | |
| NAV-05 | Unread and mention signals, New line | E2E | I | I | — | — | Rechecked on the emulator 2026-10-21 (B2): rail bar and mention count, Home and Activity badges |
| NAV-06 | Search | I | I | I | I | P2 | Metadata search on the server; message text searched on the device (encrypted) |
| NAV-07 | Profiles | I | I | I | I | P3 | No banner or pronouns yet |
| NAV-08 | Presence, custom status | I | I | I | I | — | |
| NAV-09 | Settings | I | I | I | — | — | |
| NAV-10 | Back behaviour | E2E | I | — | — | — | |
| NAV-11 | Reorder communities in the rail | E2E (emulator) | I | — | I | — | B2 2026-10-21: long-press the rail or community sheet › Arrange communities; `railOrder` in the personal document (`community_navigation.test.ts`, `CommunityNavigationRulesTest`). Phone test pending. Web: not yet |

## C. Communities and discovery

| ID | Feature | Status | A | W | S | Pri | Notes / next |
|---|---|---|---|---|---|---|---|
| COM-01 | Create (starter channels) | E2E | I | I | I | — | B2 2026-10-21: starter templates (Starter, Friends, Gaming, Study group, Club) with forum and voice channels, `GET /v1/community-templates`; emulator-checked (Gaming). Phone test pending. Web: starter set only |
| COM-02 | Name, icon, description | I | I | I | I | P3 | Banner: deferred |
| COM-03 | Visibility and join policy | IT | I | I | I | — | Invite only / public with invites / public and open |
| COM-04 | Discover and preview | E2E | I | D | I | P1 (web) | |
| COM-05 | Invites (expiry, uses, QR) | E2E | I | I | I | P1 (B4) | Server side complete and tested (expiry, uses, revoke, role safety, bans, races). B4 2026-10-21: more choices (1, 10, 50, 100, 500 uses; 1 hour to 30 days) and pausing (MOD-13). **Still open (B4b):** links are `meldiq://` only, no https App Link, no signed-out web preview, invite from a DM. `MELDIQ_COMMUNITY_INVITATION_SPEC.md` |
| COM-06 | Welcome screen | I | I | — | I | P2 | #welcome channel with welcome actions; no "recommended channels" card |
| COM-07 | Onboarding questions | R | — | — | — | P2 | Not built. Plan: `community_onboarding` (prompts → options → role ids and channel ids), applied by the server when a member answers; channels follow from roles. `MELDIQ_FULLSTACK_SPEC.md` §5 |
| COM-08 | Rules shown before joining | I | I | I | I | — | |
| COM-09 | Member list | I | I | I | I | P3 | Grouping by hoisted role: P3 |
| COM-10 | Leave | E2E | I | I | I | — | Phone 2026-10-21 |
| COM-11 | Delete | I | I | I | I | — | |
| COM-12 | Ownership transfer | I | I | I | I | — | `POST /v1/orgs/:id/ownership` |
| COM-13 | Community notifications | E2E (emulator) | I | I | I | — | Mute all, per-channel levels. B2 2026-10-21: community level, Ignore @everyone, channel override list (sheet `CommunityNotificationsSheet.kt`); emulator-checked with two real clients (suppressed @everyone didn't notify or count; unsuppressed did; "Mentions only" silenced plain messages; an "All" override notified). Phone test pending. Web: levels only |
| COM-14 | Verification level | R | — | — | — | P3 | Email is always verified in Meldiq; "account age" levels: P3 |
| COM-15 | System messages | D | — | — | — | P3 | |
| COM-16 | Hide channels without leaving, channel browser | E2E (emulator) | I | — | I | — | B3 2026-10-21: channel menu › Hide from channel list; "N hidden · Browse channels" row; community sheet › Browse channels with a switch per channel and Show all; hidden channels come back while they mention you. `hidden` in the community document. Phone test pending |
| COM-17 | Server Guide with Get Started tasks | R | — | — | — | P2 (B8) | Tasks tied to channels, ticked on visit (COMM-CTRL-003/004/005) |
| COM-18 | Join applications (apply to join, moderator approval) | R | — | — | — | P2 (B4) | Discord Access › Apply to Join with up to 5 questions (ADMIN-CTRL-008) |

## D. Categories and channels

| ID | Feature | Status | A | W | S | Pri | Notes / next |
|---|---|---|---|---|---|---|---|
| CH-01 | Category create / rename / delete | E2E | I | D | I | P1 (web) | |
| CH-02 | Category order and collapse | E2E | I | D | I | P1 (web) | |
| CH-03 | Channel create (types, private, members) | E2E | I | I | I | — | |
| CH-04 | Channel edit (name, topic, type) | E2E | I | I | I | — | |
| CH-05 | Channel delete | E2E | I | D | I | P1 (web) | API 0.13.0 |
| CH-06 | Move and order | I | I | D | I | — | Move up/down, move to category |
| CH-07 | Private channels | IT | I | I | I | — | Server `channel_access.test.ts` |
| CH-08 | Sync with category | UT | I | D | I | — | |
| CH-09 | Overwrites (deny/inherit/allow) | IT | I | I | I | — | |
| CH-10 | Announcement channels | IT | I | I | I | — | Following into other communities: — (Meldiq has no cross-community feed) |
| CH-11 | Forum channels and tags | E2E | I | D | I | P1 (web) | Required tag, gallery view: P3 |
| CH-12 | Voice channels | E2E | I | I | I | — | Phone: speak and listen-only 2026-10-21 |
| CH-13 | Stage channels | D | — | — | — | P3 | Calls have raise hand and host moderation, a base for it |
| CH-14 | Slowmode | E2E (emulator) | I | — | I | — | B3 2026-10-21: Edit channel › Slowmode (off, 5 s to 6 h); server refuses a member's second new message within the interval (429 `slowmode` with `retryAfter`, `nextPostAt`), edits and reactions free, retries of accepted messages pass; exempt: channel managers and `messages.bypass_slowmode` (Moderator template). Composer countdown; queued messages go out when allowed (`channel_controls.test.ts`). Phone test pending. Web: shows the refusal only |
| CH-15 | Channel and category menus | E2E | I | D | — | P1 (web) | |
| CH-16 | Media channels | — | | | | | Forum with attachments covers it |
| CH-17 | Timed mute (15 min to 24 h) and category level | E2E (emulator) | I | — | I | — | B3 2026-10-21: Mute for 15 min / 1 h / 3 h / 8 h / 24 h / until turned back on, for channels, categories, the community and DMs (`mutedUntil`; ends by itself on the phone, the server drops ended ones); category › Notification settings (`categoryNotify`; channel → category → community). Phone test pending |
| CH-18 | Thread list per channel | R | — | — | — | P2 (B5) | CHAN-CTRL-011 |

## E. Conversations and content

| ID | Feature | Status | A | W | S | Pri | Notes / next |
|---|---|---|---|---|---|---|---|
| MSG-01 | Compose and send | E2E | I | I | I | — | |
| MSG-02 | Edit | E2E | I | I | — | — | |
| MSG-03 | Delete (own; managers any) | I | I | I | — | — | |
| MSG-04 | Reply | E2E | I | I | — | — | |
| MSG-05 | Threads | I | I | I | — | P3 | Archive durations and locking: P3 |
| MSG-06 | Reactions with full picker | E2E | I | IT | — | — | 2026-10-21 `docs/EMOJI.md`; reactions validated on send and receive |
| MSG-07 | Pins | I | I | I | — | — | |
| MSG-08 | Forward | I | I | I | — | — | Re-encrypted for the destination |
| MSG-09 | Link previews | I | I | I | — | — | On the device, no server fetch (privacy); music and video cards |
| MSG-10 | Mentions (@user, @everyone) | I | I | I | I | P3 | @role mentions: P3 |
| MSG-11 | Unread line, mark unread | E2E | I | I | — | — | |
| MSG-12 | Polls | I | I | I | — | P3 | In the encrypted message; votes are encrypted controls |
| MSG-13 | Events | I | I | I | I | P2 | Meetings with RSVP; recurrence and "voice channel" events: P2 |
| MSG-14 | Typing indicator | I | I | I | I | — | |
| MSG-15 | Save for later, reminders | I | I | I | I | — | |
| MSG-16 | Attachments, 1 GiB target | E2E (600 MB, USB) | I | I | I | P1 | 1 GiB over real Wi-Fi and mobile data to production: owner's test (`MELDIQ_SESSION_HANDOFF.md`) |
| MSG-17 | Voice messages | I | I | I | — | — | |
| MSG-18 | Markdown | I | I | I | — | — | |
| MSG-19 | Message menu translations | FIX | I | — | — | P1 (B5) | Hard-coded English in `ConversationScreen.kt` (Pin, Save to Gallery/Files, Share) and `ConversationInfoScreen.kt` (Group info, Rename group, Add members, Leave, Remove from group) |
| MSG-20 | Copy message link (members only) | R | — | — | — | P3 (B5) | CHAT-MSG-004 |
| MSG-21 | Poll duration and answer emoji | R | — | — | — | P2 (B8) | POLL-CTRL-001 |

## F. DMs and groups

| ID | Feature | Status | A | W | S | Pri | Notes / next |
|---|---|---|---|---|---|---|---|
| DM-01 | One-to-one DM | E2E | I | IT | I | — | |
| DM-02 | Group DM | I | I | I | I | — | |
| DM-03 | Message requests and DM permissions | R (observed) S | — | — | — | P1 (B6) | **Gap found:** `POST /v1/conversations/dm` creates a DM with anyone; only blocking stops delivery. Spec `MELDIQ_FRIENDS_AND_DMS_SPEC.md` |
| DM-04 | Connections (request, accept, decline, cancel, remove) | R (observed) S | — | — | — | P1 (B6) | **Proposed reversal of "no friend graph", needs the owner's approval:** a small `connections` model (no suggestions, no contact mining). `MELDIQ_FRIENDS_AND_DMS_SPEC.md` |
| DM-05 | Block | I | I | I | I | — | |
| DM-06 | Favourite, archive, mute DMs | I | I | I | I | — | |
| DM-07 | Group photo | R | — | — | — | P2 (B6) | GDM-CTRL-002 |
| DM-08 | Invite to community from a DM or profile | R | — | — | — | P2 (B4/B6) | INV-B-05 |
| DM-09 | Report a person | E2E (emulator) | I | — | I | — | B4: profile › Report (send to Meldiq's team or one of your communities' moderators; the server checks the person is in it), community member sheet › Report |

## G. Roles, permissions and moderation

| ID | Feature | Status | A | W | S | Pri | Notes / next |
|---|---|---|---|---|---|---|---|
| MOD-01 | Roles: create, order, colour | IT | I | I | I | — | Role icons: P3 |
| MOD-02 | Base permissions | IT | I | I | I | — | |
| MOD-03 | Assign roles | IT | I | I | I | — | |
| MOD-04 | Remove (kick) | IT | I | I | I | — | `members.remove` |
| MOD-05 | Ban / unban | IT | I | I | I | — | |
| MOD-06 | Timeout | E2E (Android) | I | D | I | — | 2026-10-21: `PUT /v1/orgs/:id/members/:userId/timeout` (≤ 28 days, reason in the audit log), permission `members.timeout` (in the Moderator template); takes posting, files, announcements and voice in every channel of the community (as a restriction no overwrite lifts), removes them from live voice calls; owners never, admins only by the owner. Android: Members › Time out (60 s – 1 week), Timed out tag, End timeout, composer says until when. Server `member_timeout.test.ts` (6); emulator screenshots `timeout-*.png`. Web: P2 |
| MOD-07 | Report | E2E (emulator) | I | — | I | — | B4 2026-10-21: message menu › Report: reason, then a review step (who reads it, what is shared: the message and up to 5 before it, as decrypted on the phone), optional note, Send. Channels go to the community's moderators (`reports.view`, Moderator template), DMs, groups and illegal content to Meldiq's team. Sealed at rest (`REPORTS_KEY` or `reports.key`), 10 an hour, a repeat returns the first, moderators never see who reported, a moderator can't close a report about themselves. Queue: Community settings › Reports (Open/Closed, handled/dismiss with a note, reopen). Store on SQLite or MongoDB (`reports` domain). Deleted 90 days after closing. Operator queue: not built (B4b or later). Phone test pending. Web: not yet |
| MOD-08 | AutoMod | R | — | — | — | P3 | Text rules can't run on the server (encrypted). Possible: on-device keyword filter rules distributed by the community, mention-spam limits (the server sees mention counts), join-rate limits. Spec §6 |
| MOD-09 | Audit log | E2E (emulator) | I | I | I | — | `audit.view`. B4: filters by kind of change (Members, Channels, Categories, Roles, Invites, Community, Reports) and by person (`actorId`) |
| MOD-10 | Raid / abuse protection | I | — | — | I | P2 | Rate limits on joins, invites and sign-ups (`abuse.ts`); alerts to moderators: P2 (B4, Discord Activity Alerts, ADMIN-CTRL-002) |
| MOD-11 | Explicit media filter | — | | | | | Server can't see media (encrypted); on-device options: P3 |
| MOD-12 | 2FA for moderators | R | — | — | — | P3 | After AUTH-05 |
| MOD-13 | Pause invites and member DMs for a time | E2E (emulator) | I | — | I | — | B4: Community settings › Security actions: pause joining (invites and open joins, `invites_paused`) and new DMs between members (`dms_paused`; existing DMs and moderators unaffected) for 1 h to 7 days, or resume; audited |

## H. Calls, voice and video

| ID | Feature | Status | A | W | S | Pri | Notes / next |
|---|---|---|---|---|---|---|---|
| CALL-01 | 1:1 voice and video | I | I | I | I | P1 | End-to-end encrypted media (LiveKit with frame encryption) |
| CALL-02 | Group calls | I | I | I | I | P1 | Group-call tiles; phone validation open |
| CALL-03 | Voice channels | E2E | I | I | I | — | |
| CALL-04 | Camera switch, mute | I (fixed in code) | I | I | — | P1 | Rear-camera mirroring and switch race fixed 2026-10-20 (`docs/MELDIQ_CALL_BUGS_2026-10-20.md`); **awaiting phone validation**. B10 traces it stage by stage (`MELDIQ_CALLING_SCREENSHARE_AUDIT.md` §3) |
| CALL-05 | Screen share | I | I | I | — | P1 | Android MediaProjection with display-rotation following; awaiting phone validation |
| CALL-06 | Game streaming ("Go Live") | S (B11) | — | — | — | P2 | Screen share at up to 1080p is the base; high-frame-rate encoding, system audio capture (Android 10+ `AudioPlaybackCapture`, with consent) and viewer quality choice are specified in `MELDIQ_REALTIME_MEDIA_PLAN.md` |
| CALL-07 | Stage (raise hand) | I (calls) | I | I | I | P3 | Raise hand and host moderation in calls |
| CALL-08 | Noise suppression | I | I | I | — | — | WebRTC NS/AEC/AGC; hardware NS off |
| CALL-09 | Reconnect | I | I | I | — | P2 | Measure on phones |
| CALL-10 | Soundboard, activities | — | | | | | Not planned |
| CALL-11 | Deafen, pre-call mic and camera check | R | — | — | — | P2 (B10) | CALL-CTRL-004/005/006 |

## I. Expression

| ID | Feature | Status | A | W | S | Pri | Notes / next |
|---|---|---|---|---|---|---|---|
| EXP-01 | Emoji picker (search, categories, recent) | E2E | I | IT | — | — | 2026-10-21 |
| EXP-02 | Reaction picker | E2E | I | IT | — | — | |
| EXP-03 | Skin tone | E2E | I | IT | — | — | |
| EXP-04 | Custom community emoji | S | — | — | — | P2 | `community_emoji` (name, S3 object, sha256, animated, creator); permission `expressions.manage`; 128×128, ≤ 256 KiB, PNG/GIF/WebP; message carries `:name:` plus the emoji id and hash, so clients verify the image |
| EXP-05 | Community stickers | S | — | — | — | P3 | Same store as EXP-04 |
| EXP-06 | GIF search | — | | | | | Would send typing to a third party (Tenor); not without an opt-in. P3 |
| EXP-07 | Built-in stickers and animated emoji | I | I | I | — | — | Meldiq's own |

## J. Platform (master prompt §6–§8, §11, §14)

| ID | Item | Status | Notes / next |
|---|---|---|---|
| PLAT-01 | MongoDB as the only server database | I (2 of 9 live domains) | accounts (with key backups and recovery codes) and preferences can run on MongoDB; the other 7 domains can only be copied and verified. Production runs SQLite only. `MELDIQ_MONGODB_MIGRATION.md` |
| PLAT-02 | MongoDB hosting (Atlas on AWS) | B | Owner: create the cluster and credentials (`MELDIQ_AWS_ARCHITECTURE.md`) |
| PLAT-03 | S3 for file bytes | IT | Production bucket in ap-southeast-1; CORS fix confirmed on production (web) |
| PLAT-04 | Infrastructure as code | S | `MELDIQ_AWS_ARCHITECTURE.md` |
| PLAT-05 | AI service boundary | S | `MELDIQ_AI_ARCHITECTURE.md`; nothing reads encrypted content |
| PLAT-06 | Workplace modules removed | I | Android and server (API 0.12.0); **web still has AI, workflows, documents and inbox screens**, to remove in the web community rebuild |
