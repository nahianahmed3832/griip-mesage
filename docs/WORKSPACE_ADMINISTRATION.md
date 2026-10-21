# Workspace administration (2026-10-19)

> **Community rebuild (2026-10-21):** branches and teams are gone; channel access is decided by categories' and channels' permission overwrites and changed by holders of `channels.visibility`, not by branch managers or team leads. Settings gained rules, visibility (Discover) and who may join. See `COMMUNITY_REBUILD_2026-10-21.md`.

The organisational workspace features added in the 2026-10-19 round: managing a workspace, its people and roles,
channel access, archiving, and each person's own arrangement of Home. Every protected action is decided on the server
(`server/src/authz.ts`, `server/src/roles.ts`); the app only hides what the server would refuse, using the permissions
the server sends. Server feature flags: `workspace-admin`, `nav-preferences`, `org-photos` (older servers keep the
previous behaviour; the app checks the flags).

References studied for interaction patterns only (no branding, code or colours copied): Slack (Home/DMs/Activity,
sections, channel details), Discord (role hierarchy, channel-level access, delegated admin), Linear (search, team pages)
and Notion (team wiki). The signed-in UI stays in Meldiq's Cocoa design system (`DESIGN_SYSTEM.md`).

## 1. Workspace settings

- **Name, description, avatar** (owners and admins): Home › workspace switcher › Workspace settings. The avatar is a
  JPEG like people's photos (re-encoded on the phone, ≤ 512 KB), stored per revision in the profile-photos store, shown
  to members only (`GET /v1/orgs/:id/photo`), removed with the workspace. It isn't end-to-end encrypted (like the name).
- **Leave**: everyone but the last owner (`permissions.canLeave`); leaving removes branch, team and channel
  memberships and the person's personal arrangement of that workspace. Direct messages stay.
- **Archived branches and teams** (`GET /v1/orgs/:id/archived`): owners and admins see every archived branch and team,
  branch managers their branch's teams; restoring brings them back read-write. A team in an archived branch comes back
  with its branch.

## 2. Roles

Organisation roles: Owner, Admin, Member, Guest; branch managers and team leads are scoped roles on top.

| Change | Owner | Admin | Member / Guest |
|---|---|---|---|
| Any role for anyone | ✓ (one owner always remains) | – | – |
| Member ↔ Guest for members and guests | ✓ | ✓ | – |
| Step down themselves (to Member or Guest) | ✓ (not the last owner) | ✓ | – |
| Titles | anyone's | own, members' and guests' | own |
| Remove someone | anyone (not the last owner) | members and guests | only themselves (leave) |

Server: `roles.ts` (`orgRoleChangeRefusal`, `titleChangeAllowed`, `removalAllowed`), tested in `roles.test.ts` and as
direct API calls in `workspace_admin.test.ts` (self-promotion, admins touching admins, the last owner, guests). App:
`WorkspaceRules.kt` mirrors the same table, tested line by line in `WorkspaceRolesTest`.

## 3. Channel access modes

Public (anyone in the channel's scope may join), Private (explicit members only) and Announcement (everyone in scope,
only managers post). Changing the mode (`PATCH /v1/conversations/:id { kind }`) is for the people who manage the
channel's scope: owners and admins, the branch's managers for a branch channel, the team's leads and managers for a team
channel. A channel admin by role alone may not, so nobody can make a private channel public just because they were made
its admin. Nobody is added or removed by a change; an archived channel must be restored first. Audit: `channel.access`.

## 4. Invitations

One state per invitation, in this order: revoked, expired, used up, active (`status`). Admins see every invitation,
others only their own; each shows who created it. No email is sent: codes are shared by the people who create them.

## 5. Personal sections (Home)

Each person arranges Home for themselves, on every device (`/v1/me/preferences/:workspaceId`, `NavPreferences.kt`):

- **Starred**: conversations at the top of Home, in the person's order.
- **Custom sections**: named groups (up to 30) the person moves conversations into (long-press › Move to section);
  renamed or deleted from the section's Edit; deleting puts its conversations back in their usual groups.
- **Collapsed groups** and **notification levels** (mute) follow the person to every device.
- **Saved for later** (2026-10-19, server feature `saved-items`): message menu › Save for later, with an optional
  reminder (in 1 or 3 hours, tomorrow at 9:00, next Monday at 9:00). Home › Later lists them (In progress / Done) and
  its card counts overdue reminders. Stored in the personal document as `saved: [{conversationId, messageId, savedAt,
  remindAt, done}]`: ids and times only, never content (the message is read from the phone's own copy). Up to 500. A
  client that doesn't know the field leaves it out of its write and the server keeps the stored list
  (`preferences/routes.ts`), so the web app and older phones can't empty it. Reminders are scheduled on each of the
  person's phones (WorkManager) and shown once; done or removed items cancel them everywhere.
- **A community's own notification settings** (batch B2, 2026-10-21, server feature `community-preferences`):
  community sheet › Notification settings. Stored in that community's document as `community: {level, suppressEveryone}`.
  - `level` (all / mentions / none) is what channels follow when they have no level of their own in `notify`. An
    explicit "all" in `notify` is kept only while the community's level is quieter.
  - `suppressEveryone`: @everyone doesn't notify or count as a mention in that community.
  - Decided on the phone (wake-ups carry nothing).
  - Left out of a write, the stored settings are kept, as for `saved`. The field is dropped from the personal document.
- **Rail order** (same feature): long-press a community in the rail, or community sheet › Arrange communities. Stored in
  the personal document as `railOrder` (community ids, at most 200), kept only for communities the person belongs to;
  new communities go at the end. Left out of a write, the stored order is kept.
- **Timed mutes, category levels and hidden channels** (batch B3, 2026-10-21, server feature `channel-preferences`).
  Like `saved`, each is kept as stored when a write leaves it out.
  - `mutedUntil: {conversationId: epoch ms}` (any document; DMs in the personal one): the conversation behaves as
    "none" until then. The phone unmutes on time, and the server drops ended entries.
  - `categoryNotify: {categoryId: level}` (community documents): the level a category's channels follow. The order is
    the channel's own level, then its category's, then the community's.
  - `hidden: [channelId]` (community documents): channels left out of the panel. They stay joined, and come back while
    they mention you.
- **Slowmode** (same batch, feature `slowmode`, migration 035): a channel setting (`slowmode_sec`), enforced by
  `POST /v1/messages` for new messages only. Channel managers and `messages.bypass_slowmode` are exempt.

One document per person and workspace ("personal" for DMs and groups, an organisation id for its channels), with a
revision: a write names the revision it was based on, and a stale write gets the current copy back (409). The phone
keeps its changes until the server has them and replays them onto the latest copy, so two devices (or offline edits)
merge instead of overwriting each other. Only the person's own conversations are kept; anything else is dropped. It
only arranges what the person can already see and grants nothing. Privacy: the server learns which conversations you
starred or grouped and your section names (never content). The documents live in SQLite or, with
`MONGODB_DOMAINS=preferences`, in MongoDB (`docs/MONGODB.md`).

## 6. Channel details

About (topic, where it lives, whether you can post), Privacy and access (the mode, and the switch for scope managers),
Notifications (mute, synced), Members, Pinned messages, and Files: the newest files in this phone's history of the
conversation (no server-side index exists, since file names are end-to-end encrypted).

## 7. Audit events

Security-relevant changes are recorded without content: `org.update`, `org.photo`, `org.delete`, `branch.archive`,
`branch.restore`, `team.archive`, `team.restore`, `channel.access`, `channel.archive`, `channel.restore`,
`member.leave`, `member.remove`, role and invitation events. Members see the events about places they can see
(`GET /v1/orgs/:id/activity`).

## 8. Not built (and why)

- Organisation-managed user groups and group mentions: mentions are inside end-to-end encrypted messages, so a group
  mention needs a protocol change (the server-attested `@everyone` model, extended); left for a dedicated round.
- Server-side message search: messages are end-to-end encrypted; search runs over the phone's own history.
- Team docs, task lists, templates, automations, webhooks, usage reports (medium priority): need new collaboration
  storage and permissions; documented for later instead of half-built.
