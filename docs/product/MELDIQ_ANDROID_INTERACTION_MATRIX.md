# Meldiq Android interaction matrix (2026-10-21)

Every gesture and menu action on the community and chat screens: what it does, who may do it, where the server checks
it, and the test that covers it. "Client" means the app only hides the control; the server is the authority for
everything marked with a server check. Reference evidence: `MELDIQ_DISCORD_REFERENCE_AUDIT.md`.

Permissions are Meldiq's (`server/src/permissions/catalog.ts`, channel capabilities in `permissions/channel.ts`).

## Home (rail and channel panel)

| Where | Gesture | Result | Who | Server check | Test |
|---|---|---|---|---|---|
| Rail community | tap | Selects it; the panel shows its channels; the choice is remembered | members | `GET /v1/orgs/:id` (member) | `CommunityPanelRulesTest` |
| Rail + | tap | Join or create a community | everyone | invite / create routes | existing |
| Rail compass | tap | Discover public communities | everyone, servers with discovery | `GET /v1/communities/discover` | existing server |
| Panel header | tap | Community sheet | members | — | — |
| Panel search | tap | Universal search | members | search route | existing |
| Panel invite | tap | Invitations | `members.invite` | invite routes | existing server |
| Events / Members | tap | Events screen / Members screen | members / `members.view` | routes | existing |
| Category header | tap | Collapse or expand (synced personal preference) | everyone | nav-preferences | `CommunityPanelRulesTest` |
| Category header | long-press | Category menu | everyone (manage items: managers) | — | `CommunityPanelRulesTest` |
| Category + | tap | Create channel in the category | `channels.create` in the category | `POST /v1/orgs/:id/channels` | `orgs.test.ts` |
| Channel row | tap | Opens it; a dimmed (not joined) channel is joined first | members who may see it | `POST /v1/conversations/:id/join` | `orgs.test.ts` |
| Channel row | long-press | Channel menu | everyone (manage items: managers) | — | `ChannelMenuRulesTest` |
| Voice row | tap Join | Joins the voice channel | `voice.connect` | `POST /v1/calls` | `voice_permissions.test.ts` |

## Community sheet

| Item | Result | Who | Server check | Test |
|---|---|---|---|---|
| Invite | Invitations | `members.invite` | yes | existing |
| Notifications | Mutes or unmutes every channel of the community on this account | everyone | conversation mute | `CommunityPanelRulesTest` |
| Settings | Community settings | members (rows by permission) | per route | — |
| Mark as read | Marks every channel of the community read on this phone | everyone | — (local; channels send no read receipts) | `CommunityPanelRulesTest` |
| Create channel / category | Create sheets | `channels.create` / managers | yes | `orgs.test.ts` |
| Hide muted channels | Personal switch | everyone | — (local) | `CommunityPanelRulesTest` |
| Copy name | Clipboard | everyone | — | — |
| Leave | Confirm, then leave | members except the owner | `DELETE /v1/orgs/:id/members/:yourUserId` | existing |

## Category menu

| Item | Who | Server check | Test |
|---|---|---|---|
| Mark as read | everyone | — | `CommunityPanelRulesTest` |
| Collapse / Collapse all | everyone | — | `CommunityPanelRulesTest` |
| Mute / Unmute category | everyone | conversation mute | `CommunityPanelRulesTest` |
| Create channel | `channels.create` in it | yes | `orgs.test.ts` |
| Edit (rename) | `channels.manage` | `PATCH /v1/categories/:id` | `orgs.test.ts` |
| Permissions | `channels.manage` | `PUT /v1/categories/:id/overwrites` | `channel_permissions.test.ts` |
| Move up / down | `channels.manage` | `PUT /v1/orgs/:id/layout` | `orgs.test.ts` |
| Delete (confirm) | `channels.manage` | `DELETE /v1/categories/:id` (409 while a channel in it has its own permissions) | `orgs.test.ts` |

## Channel menu

| Item | Who | Server check | Test |
|---|---|---|---|
| Mark as read | everyone | — | `ChannelMenuRulesTest` |
| Favourite | everyone | nav-preferences (synced) | emulator |
| Mute | everyone | conversation mute | existing |
| Copy name | everyone | — | — |
| Edit channel | `channels.manage` on it | `PATCH /v1/conversations/:id` | `orgs.test.ts` |
| Make private / public | `channels.visibility` | same route, access check | `orgs.test.ts` |
| Permissions; Sync with category | `channels.manage`; changing view needs `channels.visibility` | `PUT /v1/conversations/:id/overwrites` | `channel_permissions.test.ts`, `CommunityRulesTest` |
| Move to category / up / down | `channels.manage` | layout route | `orgs.test.ts` |
| Archive / Restore | `channels.manage` | PATCH `archived` | `orgs.test.ts` |
| Delete (confirm) | `channels.manage` on it | `DELETE /v1/conversations/:id` | `channel_delete.test.ts` |

## Conversation

| Where | Gesture | Result | Who | Server check | Test |
|---|---|---|---|---|---|
| Header title | tap | Details (members, files, settings) | members | — | existing |
| Message | long-press | Message menu: reply, react, edit (yours), delete (yours or managers), copy, forward, pin (where you may post), thread, save for later, remind, translate, mark unread, info | as listed | edit/delete/pin routes | existing; `UnreadMarkerTest` (mark unread) |
| Message | swipe | Reply | where you may post | send route (`messages.post`) | existing |
| Composer + | tap | Attachment sheet: photos, camera, files, location, profile, poll | `messages.attach` for files | upload start (`canAttach`) | `files_1g_e2e.ts` |
| Composer | send | Sends; failed sends keep a Retry | `messages.post` (`messages.announce` in announcement channels) | send route | existing |
| Unread divider | — | Shown above the first unread message | — | — | `UnreadMarkerTest` |
| Jump to latest | tap | Scrolls to the newest message | — | — | existing |

## Forum

| Gesture | Result | Who | Server check | Test |
|---|---|---|---|---|
| New post | Title, text, tags | `messages.post` | send route | `ForumRulesTest` |
| Tag chip | Filter posts (any selected tag) | everyone | — (posts' tags are encrypted) | `ForumRulesTest` |
| Sort | Recent activity / Newest | everyone | — | `ForumRulesTest` |
| Search posts | Filter by title and text on the phone | everyone | — | `ForumRulesTest` |
| Edit tags (channel) | Set the forum's tags | `channels.manage` | `PATCH /v1/conversations/:id` `forumTags` | `forum_tags.test.ts` |

## Who sees what (permission cases the tests cover)

| Case | Expected | Test |
|---|---|---|
| Not a member of the community | No channels, 404 on its routes | `orgs.test.ts` |
| Member without access to a channel (view denied) | The channel is absent from lists; joining is refused | `channel_permissions.test.ts` |
| Can view, can't post (role denies `messages.post`) | Reads; the composer says why; sending refused | `channel_permissions.test.ts` |
| Can post, can't manage | No manage items; PATCH/DELETE refused | `channel_delete.test.ts` |
| Explicit role allow / deny | Allow and deny applied in order (everyone, roles, member) | `channel_permissions.test.ts` |
| Moderator | Manage items, bans | `workspace_admin.test.ts` |
| Banned | Removed, can't rejoin through invites or discovery | `orgs.test.ts` |
| Guest | Only explicitly granted channels | `channel_permissions.test.ts` |
| Voice: may join, may not speak | Joins listen-only | `voice_permissions.test.ts` |
| Voice: may not join | Join refused | `voice_permissions.test.ts` |
