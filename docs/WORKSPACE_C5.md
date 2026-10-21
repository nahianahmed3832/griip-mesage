# Workspace templates, groups, guests and meetings (round 7 slice C5, 2026-10-20)

> **Community rebuild (2026-10-21):** workspace templates and user groups were removed (every community starts with its own channels; roles do what groups did). Guests and meetings stay; the app calls meetings events. See `COMMUNITY_REBUILD_2026-10-21.md`.

## Workspace templates

General business, software development, marketing, ecommerce operations, customer support, agency or client work.
Each adds the channels that kind of team usually needs (an announcement channel, public channels with topics, a voice
room) and a "Welcome to <workspace>" document shared with the workspace for comments. Applying a template **adds only
what's missing** (by channel name), so running it again changes nothing; it never adds people, apps, paid services or
private channels. Needs `workspace.edit` and `channels.create`. App: Workspace › Workspace templates.
`GET /v1/workspace-templates`, `POST /v1/orgs/:orgId/apply-template`. New channels are auto-joined by everyone in scope
and raise the workflows' `channel.created` trigger.

## User groups

Named sets of members (Marketing, Engineering…) with a handle (`@marketing-sales`, unique per workspace), kept apart
from accounts and roles. **A group grants nothing by itself**: adding it to a channel adds its *current* members, each
checked like adding them one by one (in the channel's scope; guests are skipped; private channels only by their
managers). Members see groups and who's in them; guests don't. `groups.manage` (admins by default, or a custom role)
creates, changes and deletes them. Someone who leaves the workspace leaves its groups. Up to 100 groups, 500 members
each. App: Workspace › Groups; Channel info › Add a group. Meetings can invite groups.
`GET|POST /v1/orgs/:orgId/groups`, `PUT|DELETE /v1/orgs/:orgId/groups/:id`, `POST /v1/conversations/:id/add-group`.
Not built: `@group` mentions in the composer (the handle is reserved for it).

## Guests

- **End dates.** A guest invitation can say how long the membership lasts (`guestDays`); the clock starts when they
  join. People who can change member access set or clear a guest's end date (Members › the guest). The retention job
  takes guests past their end date out of the workspace exactly as a removal does (channels, branches, teams, groups,
  meeting invitations, app connections) and records `member.expire` in the audit log.
- **Policy** (workspace settings, `workspace.edit`): allow guests or not, and the longest a guest may stay. A shorter
  maximum brings current guests' end dates forward; turning guests off refuses new guest invitations but removes nobody
  by itself.
- **Labels.** The directory shows "Guest until <date>". Guests already see only channels they're added to; they can't
  create documents or meetings, and don't see groups.
- Not built: download restrictions for guests (files are end-to-end encrypted: the server can't enforce what a
  recipient's device does with a file it decrypted).

`PUT /v1/orgs/:orgId/guest-policy`, `PUT /v1/orgs/:orgId/members/:userId/expiry`; `guestDays` on
`POST /v1/orgs/:orgId/invites`; `guestsAllowed`, `guestMaxDays` on the workspace; `expiresAt` in the member directory.

## Meetings

A meeting belongs to a **voice room**, the workspace's dedicated meeting channel: joining it joins that room's call.
Never on a text channel (text channels have no calls since round 7). Title, notes, times (UTC with the organizer's
IANA time zone), organizer and invited people (individually or through groups). Everyone invited must already be able
to open the room, or the server refuses and lists them. People who can see a meeting: its organizer, the people
invited, and anyone who can open the room; nobody else, not even its title. The organizer or the room's managers
change or cancel it (a stale change is refused); a new time asks everyone again. Invitees answer going / maybe / not
going. Everyone involved gets a live update when it changes and a **"starting soon" notification ten minutes before**
(once; again after a time change; never for a cancelled meeting or one you declined). "Add to my calendar" hands the
meeting to the phone's calendar app; Meldiq reads nothing from it and asks for no calendar permission. Guests can't
schedule meetings. Meeting titles and times are readable by the server, like a channel's name.

`GET|POST /v1/orgs/:orgId/meetings`, `GET|PUT /v1/meetings/:id`, `POST /v1/meetings/:id/cancel|respond`. Live events
`meeting.updated`, `meeting.soon`. Not built: syncing with Google or Microsoft calendars (the connectors don't write
calendars yet), recurring meetings, a lobby, recording (never without consent; not built at all).

## Organisation dashboard

Partly: the workspace's Home already shows unread channels, voice rooms and the setup card; Meetings, Documents,
Groups and Workflows are in the Workspace menu. An "upcoming meetings" card on Home is a follow-up.

Migration 024; server features `workspace-templates`, `groups`, `guest-expiry`, `meetings`; permission `groups.manage`.
Tests: `server/test/workspace_c5.test.ts` (11), `MeetingRulesTest` (3).
