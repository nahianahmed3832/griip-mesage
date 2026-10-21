# Community-first rebuild (2026-10-21)

The owner's "community-first Android rebuild" brief, checked against this repository (`internal-beta` `f3c8855`,
API 0.11.0, Android 17 / 0.3.0-beta). Branch: `claude/meldiq-android-rebuild-8c8540`. The web client lives in a
separate repository (`orbit_web`) and is out of scope; §9 lists what it needs before this server can be deployed.

## 1. Baseline (before any change)

| Check | Command | Result |
|---|---|---|
| Server install | `npm ci` | pass, 0 vulnerabilities |
| Server typecheck | `npx tsc --noEmit -p .` | pass |
| Server tests (SQLite) | `npx vitest run` | 80 files: 79 passed, 1 skipped; **764 passed, 10 skipped, 0 failed** |
| Android unit tests | `./gradlew :app:testDebugUnitTest` | **559 passed, 0 failed, 0 skipped** |

Skipped server tests: the live-S3 file (needs a real bucket) and one MongoDB-only race test.

## 2. What the code actually is

- **Android** (Kotlin 2.2, Compose, Room + SQLCipher, LiveKit, ~56k lines): MVVM with repositories and manual DI
  (`di/AppContainer.kt`), string-route Navigation Compose (`ui/AppRoot.kt`), tabs Home / DMs / Activity. Messages,
  files, reminders and call media are end-to-end encrypted on the phone (`crypto/`, `data/repo/MessageRepository.kt`).
- **Server** (Node 22, Fastify 5, TypeScript, ~31k lines): SQLite canonical store, MongoDB for the accounts /
  preferences / integrations domains when configured, S3 for encrypted files (direct multipart uploads), LiveKit for
  calls, FCM and UnifiedPush wake-ups, Hostinger SMTP for account email. Authorisation lives in `src/authz.ts` and a
  capability catalogue (`src/permissions/`), already Discord-like (base roles + custom roles, deny wins).
- **The server never sees message content.** Anything a feature needs to enforce has to be either membership
  (who gets the ciphertext) or an upload / API call the server sees. Threads, forum titles, reactions and edits live
  inside the encrypted payload, so the server can't enforce them and the permission catalogue doesn't pretend to.

## 3. Feature inventory and decisions

KEEP: works and stays. ADAPT: stays, changed for communities. REMOVE: workplace-only or an optional connector.

| Feature | Where | Status | Category | Decision |
|---|---|---|---|---|
| Email/password accounts, sessions, email codes, password reset, recovery codes, key backup, legacy move, device linking, account deletion | `server/src/accounts`, `android/.../data/account`, `ui/screens/account` | working, tested | core | KEEP |
| DMs, groups, message history (on device + encrypted server history), replies, edits, deletes, reactions, mentions, @everyone, pins, read/delivery, typing, presence, statuses, drafts, search on device, forward/share, polls, stickers, contact/location cards, link previews, Later, reminders | `MessageRepository`, `ui/screens/conversation`, `server/src/routes/messages.ts`, `history.ts`, `reminders/` | working, tested | core messaging | KEEP |
| Threads (replies in a thread) | `threadRootId`, `ThreadActivityRules`, `conversation/{id}?thread=` | working | core messaging | KEEP; reused for forum posts |
| Files up to 1 GiB (chunked + direct S3 multipart), voice messages, albums, bulk save | `routes/attachments.ts`, `AttachmentUploader`, `AttachmentPolicy` | working, phone-tested 2026-10-18 | core | KEEP; re-verified (§8) |
| Voice/video calls, group calls, screen share, call history, PiP, voice rooms | `call/`, `routes/calls.ts` | working, phone-tested | core | KEEP; voice rooms become voice channels |
| Push (FCM, UnifiedPush, stay-connected service) | `push/`, `service/` | working | infrastructure | KEEP |
| Federation, server profiles | `federation/`, `ServerProfiles` | built, off by default | infrastructure | KEEP |
| Workspaces (`orgs`) with members, base roles, custom roles, invitations, avatar, delete, ownership transfer, audit | `routes/orgs.ts`, `routes/roles.ts`, `ui/screens/workspace` | working | community core | ADAPT → communities |
| Channels (public / private / announcement), channel members, archive, voice rooms | `routes/orgs.ts`, `routes/conversations.ts` | working | community core | ADAPT: categories, ordering, forum format, permission overwrites |
| Branches, departments, teams (and branch managers, team leads, cross-branch rules, archive) | `routes/orgs.ts`, `authz.ts`, `OrgScreens.kt`, `ScopeAdminViews.kt` | working | **workplace** | REMOVE; data migrated to roles + categories (§6) |
| Workspace templates (team templates) | `workspace/templates.ts` | working | workplace | REMOVE the team templates; keep the community starter channels |
| User groups (Marketing, Engineering…) | `workspace/groups.ts`, `GroupsScreen.kt` | working | workplace (duplicates roles) | REMOVE |
| Guest end dates and guest policy | `routes/workspaceC5.ts` | working | workplace | REMOVE (the Guest base role stays: explicit-access members) |
| Scheduled meetings in voice rooms | `workspace/meetings.ts`, `MeetingsScreen.kt` | working | general | ADAPT → "Events" in the app (same API) |
| Integrations framework, app directory, OAuth connectors (Google, Microsoft, GitHub, GitLab, Jira, Linear, Notion, Figma, Asana, Zoom, sandbox), incoming webhooks, app policies, platform registry | `server/src/integrations`, `routes`, `ui/screens/workspace/apps` | built, no provider configured in production | **connector** | REMOVE |
| Developer platform: API keys, `/ext/v1`, outgoing webhooks | `developer/`, `routes/developer.ts`, `DeveloperScreen.kt` | built | connector | REMOVE |
| Unified Inbox (Messenger, Instagram, WhatsApp, TikTok) | `inbox/`, `routes/inbox.ts`, `ui/screens/workspace/inbox` | built, never connected | connector + business | REMOVE |
| Workflows engine | `workflows/`, `WorkflowsScreen.kt` | built | workplace automation | REMOVE |
| Workspace documents | `documents/`, `DocumentsScreen.kt` | built | workplace productivity | REMOVE |
| AI (Claude, ChatGPT, Gemini), off by default | `ai/`, `AiScreen.kt` | built, never configured | connector | REMOVE |
| Connector register (`/v1/admin/connectors`) | `ops/connectors.ts`, `ConnectorsScreen.kt` | built | mixed | ADAPT: keep the core checks (email, storage, push, MongoDB), drop the provider rows |
| Service health, metrics, backups, admin CLI | `ops/`, `admin.ts`, `backup.ts` | working | infrastructure | KEEP |
| Usage and plan limits | `usage/`, `entitlements.ts` | working | infrastructure | KEEP the member/storage figures; counters for removed modules go |
| Experiments / analytics (off) | `experiments/` | off | infrastructure | KEEP |
| HR plan, job-title role templates (HR Manager, Project Manager, Finance, App Manager, Support Agent) | `docs/HR_INTEGRATION_PLAN.md`, `permissions/catalog.ts` | plan / templates | workplace | REMOVE; community templates instead |
| Public community discovery, open joining, rules, bans | — | missing | community | NEW |
| Channel categories, channel order, per-role channel permissions, forum channels | — | missing | community | NEW |

Nothing in this list is a Telegram module or a Discord connection; Discord is only a behavioural reference.

## 4. Discord reference (how it was studied)

- **REA** (`rea-agents` 6.7.0 from npm, the upstream's official channel). The supplied fork
  `nahianahmed3832/rea` is 0 commits ahead and 181 behind `morluto/rea`, so it adds nothing; the upstream README
  itself says to install only from the official sources. REA was run from a scratch folder, not registered with any
  agent: `list-browser-targets` and `capture-web-screenshot` worked against a throwaway headless Chrome on
  `https://discord.com/` (evidence `ev_461fba0e…`). `analyze-web-bundle` crashed (heap out of memory): `rea doctor`
  reports Node 25.8 as unsupported (it needs 22.19+, 24.11+ or 26+). Discord's app needs a signed-in session, which
  was not used, so REA could only see the public marketing page.
- **Discord's developer documentation** (docs.discord.com, Permissions and Channel resources) for the documented
  behaviour: base permissions are @everyone plus every held role; channel overwrites apply @everyone deny then allow,
  then all of the member's roles' denies then allows; administrators skip overwrites; denying View hides a channel and
  everything in it; categories hold up to 50 channels, deleting one leaves its channels uncategorised; a forum channel
  holds only posts (a thread with a title and a first message), with tags and a sort order.
- **The earlier screen recording of Discord mobile** (docs/product/MELDIQ_2_DECISIONS.md §1): server rail with unread
  markers, the channel list with collapsible categories, long-press channel sheet, server menu (invite, notifications,
  create channel / category), server settings grouped as Settings / People / Moderation.

| Observed | Lesson for Meldiq | Android decision |
|---|---|---|
| Rail of servers, unread dot (recording) | one tap between communities | keep Home's rail; it is the community switcher |
| Collapsible categories with channels in order (recording, docs) | channels need an order and a place | categories on Home, collapsed state synced through the existing nav preferences |
| Overwrite order @everyone → roles, deny then allow (docs) | predictable conflicts | same order, implemented on the server (§5) |
| Admin skips overwrites (docs) | someone can always fix a mistake | owners and admins skip channel overwrites |
| Forum = titled threads (docs) | posts need a title and replies | forum channels: posts are messages with a title; replies are the existing threads |
| Channel long-press sheet, server menu (recording) | power actions in sheets, not more buttons | category and channel actions live in bottom sheets |
| Settings grouped Settings / People / Moderation (recording) | fewer flat admin items | Community settings screen in three groups |

Not copied: no Discord code, assets, icons, colours or names. Meldiq keeps its own design system (Graphite, indigo
`#4B4FC9`).

## 5. The community model

`orgs` stays the community table and `/v1/orgs/...` stays the API path: both clients, the web and every Play build
already use it, and renaming paths would duplicate endpoints for no gain. The apps say "community" everywhere.

```
Community (orgs) ─┬─ CommunityMembership (org_members: base role OWNER/ADMIN/MEMBER/GUEST)
                  ├─ Role (org_roles, org_role_members): capabilities, colour, position
                  ├─ ChannelCategory (channel_categories): name, position          [new]
                  ├─ Channel (conversations type=CHANNEL): category, position, kind
                  │     PUBLIC | PRIVATE | ANNOUNCEMENT, format text | forum, voice flag
                  ├─ PermissionOverwrite (channel_overwrites): on a category or a channel,
                  │     for @everyone or a role: allow / deny of channel capabilities  [new]
                  ├─ Ban (org_bans)                                                [new]
                  └─ Invitation (invitations), optionally granting a role
Thread / forum post / reply / reaction / attachment refs: inside the encrypted message payload.
Attachment rows: per channel, authorised by membership, bytes in S3 (or disk).
```

New community fields: `visibility` (private | public: listed in discovery), `join_policy` (invite | open: anyone may
join from discovery), `rules` (shown before joining and on the community home).

**Channel capabilities that overwrites can change** (all enforced by the server):
`channels.view` (see and be a member), `messages.post`, `messages.attach` (file uploads to the channel),
`messages.announce` (post in an announcement channel), `channels.manage` (manage this channel).

**Evaluation** for a member of a community, per channel:
1. owners and admins: everything, overwrites skipped;
2. start from the person's community permissions (base role + custom roles, as today) plus `channels.view` and
   `messages.attach` for every non-guest member;
3. the channel's category overwrites, then the channel's own overwrites; at each level @everyone deny, @everyone
   allow, then the denies of every role the person holds, then their allows;
4. `channels.view` missing → the channel is invisible and its membership is removed.

**Invariants:**
- Private channels are explicit membership only: an overwrite can never add someone to one, so a category change
  never exposes a private channel. Overwrites can still take a private channel's view away from a role.
- Guests are never eligible by overwrites; they keep only channels someone adds them to (as before).
- Every change that can affect access (role assigned or removed, overwrite changed, channel moved between
  categories, member removed or banned) re-checks memberships in the same transaction and evicts lost members from
  calls, as removals already did.
- Bans remove the membership and stop every way back in (invite, open join) until lifted.
- Limits: 50 categories and 500 channels per community, 50 channels per category (Discord's documented 50).

## 6. Data migration (`032_communities.sql` + `server/src/communityMigration.ts`)

Additive first, never destructive on the first run:

1. New tables and columns (categories, overwrites, bans, community fields, `conversations.category_id`,
   `position`, `format`, `invitations.grant_role_id`).
2. Every branch and every team becomes a **custom role** with the same name and the same people (branch managers and
   team leads become channel admins of that scope's channels, which keeps their power over those channels and
   nothing more), and a **category** holding its channels.
3. A branch or team channel that was public or announcement-only becomes role-gated with one category overwrite:
   @everyone deny `channels.view`, that role allow `channels.view`. So exactly the same people see it, and nobody
   is added or removed. Private channels stay private with their members.
4. Invitations naming a branch or team grant the matching role instead.
5. Archived branches/teams: their categories are kept and their channels are archived, as they were read-only.
6. The branch/team tables are **kept** (no data dropped). `admin community-migration --verify` compares every
   person's visible channels before and after; dropping the old tables is a later, separate step once production
   has run and verified it (§9).

The migration is idempotent (`community_migration_runs` records each source row it converted) and runs at start-up
after the SQL migrations.

## 7. API changes

New: `GET/POST /v1/orgs/:orgId/categories`, `PATCH/DELETE /v1/categories/:id`, `PUT /v1/orgs/:orgId/layout`
(category order, channel order and placement in one atomic write), `GET/PUT /v1/categories/:id/overwrites`,
`GET/PUT /v1/conversations/:id/overwrites`, `GET /v1/communities/discover`, `GET /v1/communities/:id/preview`,
`POST /v1/communities/:id/join`, `GET/POST /v1/orgs/:orgId/bans`, `DELETE /v1/orgs/:orgId/bans/:userId`.

Changed (additive fields only): org summary gains `visibility`, `joinPolicy`, `rules`, `kind` is always
`community`; `GET /v1/orgs/:id` gains `categories` and keeps `branches`, `departments`, `teams` as empty lists so
Play builds 0.3.0 and older still decode it; conversation views gain `categoryId`, `position`, `format`; channel
create takes `categoryId` and `format` (`scope` is accepted and must be `ORG`).

Removed: branches, departments, teams, team moves, workspace templates, groups, guest policy and expiry, integrations
(all `/v1/integrations/*`, `/v1/orgs/:id/apps|integrations|hooks|policy`, `/v1/platform/*`), developer
(`/v1/orgs/:id/api-keys|webhooks`, `/ext/v1/*`, `/v1/developer/catalog`), inbox (`/v1/inbox/*`, `/v1/orgs/:id/inbox*`,
`/v1/admin/inbox/health`), workflows, documents, AI. Server features `integrations`, `workflows`, `documents`,
`workspace-templates`, `groups`, `guest-expiry`, `developer-api`, `ai`, `unified-inbox` are no longer advertised, so
clients that check features hide those entries; new features `community-channels`, `community-discovery`,
`community-moderation`.
