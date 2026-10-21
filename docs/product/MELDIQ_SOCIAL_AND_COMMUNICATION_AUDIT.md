# Friends, DMs and group DMs: Discord controls compared with Meldiq (2026-10-21, round 3)

The owner's additional audit brief, parts 1–3. Method, evidence classes, status codes and the safety log are the same as
`MELDIQ_LIVE_DISCORD_INSPECTION.md` §1–2. Screenshots for this file are `research/discord/live-2026-10-21/2-*.jpg`
(git-ignored, they show real people). Private conversation content was looked past, never recorded: only controls,
layouts and states are described here.

**What was not done, and why:**
- **No friend request sent, accepted, declined or cancelled; nobody blocked, ignored or removed:**
  - each of these reaches a real person;
  - blocking a friend in Discord also removes the friendship, which can't be undone without a new request.
- **No DM sent, no call started:** same reason.
- **Error states:** invalid username, duplicate request and blocked user all need submitting the form, so they are
  documented (D) or unverified (U), not observed.

**What could be:** every list, tab, menu, profile view and form up to its final button. A designated test account pair
would let these flows be walked to the end; see §5.

## 1. Friends (Discord) and Meldiq's equivalent

| ID | Where / path | Control | Activation → result | Ev | Insp. | Meldiq today (file) | Meldiq | Batch |
|---|---|---|---|---|---|---|---|---|
| FRIEND-CTRL-001 | Home › Friends | Tabs Online, All, Pending, Suggestions (badge 3), Add Friend (primary button); "New Group DM" icon at the right | Switches the list | O `2-friends-all`, `friends-home` | Ins | No friends page. People come from shared communities and conversations (`profile.ts` `relation()`: "contact" = can see presence) | BUILD "People" (connections), see the spec | B6 |
| FRIEND-CTRL-002 | Friends | Info banner "Looking for accounts you've blocked or ignored? Go to settings" (dismissible) | Opens Settings › Messaging Permissions › Ignore & Block | O `2-ignore-block` | Ins | Settings › Privacy › Blocked (`PrivacyScreen.kt`) | KEEP | — |
| FRIEND-CTRL-003 | Friends | Search field (filters the current tab) | — | O | Ins | — | BUILD with People | B6 |
| FRIEND-CTRL-004 | All tab | "All friends — 45"; rows: avatar with presence, name, badges, status; Message button; ⋯ | ⋯ → Start Video Call, Start Voice Call, Remove Friend (red) | O `2-friends-all`, `friend-row-menu` | Ins (menu opened, nothing chosen) | — | BUILD | B6 |
| FRIEND-CTRL-005 | Pending tab | "Sent — 4": outgoing requests with ✕ (cancel). Incoming requests (none at the time) show ✓ accept and ✕ ignore | Cancel / accept / decline | O `2-friends-pending-sent`; incoming D/U | Ins (sent), Doc (incoming) | — | BUILD | B6 |
| FRIEND-CTRL-006 | Suggestions tab | "Friend Suggestions — 3": ✓ add, ✕ dismiss | — | O `2-friends-suggestions` | Ins | — | EXCLUDE (suggestions come from contact syncing and social-graph mining. Meldiq uploads no address book, `users.ts` by-phone comment) | — |
| FRIEND-CTRL-007 | Add Friend | "You can add friends with their Discord username."; username field; "Send Friend Request" (disabled until text); optional "Personalize your request" (120 characters, "also shows up in your DM if you become friends"); "Explore Discoverable Servers" | Submit → request or error | O `2-add-friend`; errors D | Ins (form, not submitted) | Exact-username lookup (`GET /v1/users/search` returns an exact username match for anyone who allows `findByUsername`), phone lookup (`by-phone`), invite QR | ADAPT: "Connect" request by exact username, optional note, privacy-respecting errors | B6 |
| FRIEND-CTRL-008 | Settings › Messaging Permissions › Friend Requests | "Allow Friend Requests from…": Everyone, Friend of friends, Server members (only from servers where DMs are allowed); Show personalized messages | — | O `2-friend-request-permissions` | Ins | Privacy audiences for profile parts (`PRIVACY_FIELDS`: everyone / contacts / nobody) | BUILD: "Who can send you requests": Everyone / People in my communities / Nobody | B6 |
| FRIEND-CTRL-009 | Settings › … › Ignore & Block | "Blocked accounts — 1" with Unblock; "Ignore" is a softer option (hides without blocking) | — | O `2-ignore-block` | Ins (Unblock not pressed) | Blocked list with Unblock (`PrivacyScreen.kt:201`); block from profile (`ProfileScreens.kt:409`); server drops messages from blocked senders silently (`messages.ts:106`) | KEEP block; Ignore EXCLUDE (Meldiq's Mute covers it) | — |
| FRIEND-CTRL-010 | Full profile | Message, friend-status button, ⋯; Member Since, Friends Since, Note (only visible to you); tabs Activity, Mutual Friends (5), Mutual Server (1); empty state "doesn't have any activity" | — | O `2-full-profile` | Ins | Profile screen: name, photo, about, status, shared communities, Message, Block | IMPROVE (private note; "connected since") | B6 |
| FRIEND-CTRL-011 | Full profile ⋯ / DM profile ⋯ | Share Profile, Invite to Server ›, Ignore, Block (red), Report User Profile (red), Copy User ID | — | O `2-full-profile-more`, `dm-profile-more` | Ins (nothing chosen) | Block. No report of a person | BUILD report person (with MOD-07) | B4 |
| FRIEND-CTRL-012 | Notifications | Incoming request notification, "accepted your friend request" | — | U | Doc | — | BUILD (push + Activity item) | B6 |

## 2. One-to-one DMs

| ID | Where / path | Control | Activation → result | Ev | Insp. | Meldiq today (file) | Meldiq | Batch |
|---|---|---|---|---|---|---|---|---|
| DM-CTRL-001 | Home sidebar | "Direct Messages" list: avatar with presence, name, member count for groups; "+" Create DM | Opens the DM | O `friends-home` | Ins | Chats list (`ChatsScreen.kt`) with unread, pinned, archived, swipe actions | KEEP | DM-01 |
| DM-CTRL-002 | "+" Create DM | "New Message": "Group DMs can have up to 10 members"; search; checkbox list of friends; Cancel, Create Message | One picked → DM; several → group | O `new-group-dm` | Ins (cancelled) | New chat (`NewChatScreen`, `ChatsScreen.kt:305`): search people, new group (up to 50, `MAX_GROUP`) | KEEP | DM-02 |
| DM-CTRL-003 | DM list, right-click | Mark As Read (disabled when read), Pin, Add to Favorites, Profile, Start a Call, Note (only visible to you), Add Friend Nickname, Close DM, Apps ›, Invite to Server › (lists your servers), Remove Friend, Ignore, Block (red), Mute @user ›, Copy User ID, Copy Channel ID | — | O `2-dm-list-menu`, `dm-invite-to-server` | Ins (nothing chosen) | Chat long-press (`ChatActionsSheet.kt`): mark unread/read, star (favourite), move to section, notification level, mute, archive | IMPROVE: "Invite to community" from a DM; private nickname/note; timed mute (B3) | B6 |
| DM-CTRL-004 | DM header | Name + presence; Start Voice Call; Start Video Call; Pinned Messages; Add Friends to DM (turns the DM into a new group); User Profile toggle; Search in DM | — | O `2-dm-header` | Ins (calls not started) | Conversation header: call buttons, pins, search, info (`ConversationChrome.kt`) | KEEP; "add people → new group": BUILD small | B6 |
| DM-CTRL-005 | DM profile panel (right) | Banner, avatar, name, username, mutual friends and servers, Member Since, View Full Profile; friend-status icon; ⋯ | — | O `2-dm-profile-panel` | Ins | Contact info screen (`ConversationInfoScreen.kt`, "Contact") | KEEP (phones show it as a screen) | — |
| DM-CTRL-006 | DM timeline | Voice messages with play, waveform, duration, 1× speed and volume; call system lines ("started a call that lasted 3 minutes") | — | O (layout only) | Ins | Voice messages (MSG-17), call history lines (`CALL_HISTORY.md`) | KEEP | — |
| DM-CTRL-007 | DM composer and message menu | Same as channels (`MELDIQ_LIVE_DISCORD_INSPECTION.md` §6) | — | O | Ins | Same as channels | see channel rows | B5 |
| DM-CTRL-008 | Settings › Messaging Permissions › Direct Messages | Per server: "Allow DMs from other server members"; "Message requests: filter messages from server members you may not know" | — | O `dm-permissions` | Ins | **Gap:** `POST /v1/conversations/dm` creates the DM for any user id without asking the recipient; only blocking stops delivery | BUILD (message requests, DM permission) | B6 (DM-03) |
| DM-CTRL-009 | Settings › Spam Filters | Filter all / from non-friends / don't filter → Spam inbox | — | O `spam-filter-2` | Ins | — | ADAPT: requests from strangers land in "Requests", not the main list | B6 |
| DM-CTRL-010 | Server Security Actions | Pause DMs between members for a duration | — | O `security-actions` | Ins (cancelled) | — | BUILD with B4 (pause invites and member DMs) | B4 |

## 3. Group DMs

| ID | Where / path | Control | Activation → result | Ev | Insp. | Meldiq today (file) | Meldiq | Batch |
|---|---|---|---|---|---|---|---|---|
| GDM-CTRL-001 | DM list, right-click group | Mark As Read, Pin, Add to Favorites, Edit Group, Mute Conversation ›, Leave Group (red), Copy Channel ID | — | O `2-group-dm-menu` | Ins | Chat long-press (as DM-CTRL-003) | KEEP | — |
| GDM-CTRL-002 | Edit Group | Group icon with edit pencil; name (defaults to the members' names); Cancel, Save | — | O `2-edit-group` | Ins (cancelled) | Rename group (`ConversationInfoScreen.kt:143`). **No group photo** | BUILD group photo | B6 |
| GDM-CTRL-003 | Group header | Voice call, Video call, Pinned, Add Friends to DM, Show Member List, Search | — | O `2-group-dm-header` | Ins | Same in group header | KEEP | — |
| GDM-CTRL-004 | Add Friends to DM | "Invite to Group DM: You can add 7 more people"; search; checkbox list; Add; "Or, send an invite link" (24-hour link, Copy) | Opening it showed a link (safety log 4) | O `2-invite-to-group-dm` | Ins (nothing added) | Add members (`ConversationInfoScreen.kt:144`, managers only, server `PUT …/members/:userId`, `MAX_GROUP` 50) | KEEP; group invite link DEFER (P3, a code for a private group is a leak risk) | — |
| GDM-CTRL-005 | Member list › member (not owner, as non-owner) | Profile, Mention, Message, Start a Call, Edit Note, Add Friend Nickname, Apps, Invite to Server, Remove Friend, Ignore, Block, Copy User ID. **No "Remove from Group"**: only the group owner (crown) has it | — | O `2-group-member-menu`; owner view D/U | Ins (member view) | Member row menu: Make admin / member, Remove from group (admins only) | KEEP (Meldiq allows several admins and keeps one, `conversations.ts` DELETE member) | — |
| GDM-CTRL-006 | Member list › yourself | Profile, Mention, Apps, Copy User ID | — | O `2-group-member-self-menu` | Ins | Leave from info | KEEP | — |
| GDM-CTRL-007 | Group size | Up to 10 people | — | O (`new-group-dm`, "add 7 more") | Ins | 50 | KEEP (end-to-end encryption to every device scales to 50; tested in group-call tiles) | — |
| GDM-CTRL-008 | Membership and encryption | Discord keeps history visible to people added later (server-readable) | — | U | Doc | Messages are encrypted to the devices present at send time (`deviceDirectory`), so people added later can't read earlier messages, and people removed can't read later ones. Removal also ends their call (`evictRemovedMember`) | KEEP (by design) | — |

## 4. Answers to the brief's questions

1. **Does Meldiq need a relationship model?**
   - Yes, a small one.
   - Today "contact" is derived from shared conversations and communities, and anyone can open a DM with anyone.
     Message requests and request-only DM permissions need to know whom you accepted.
   - The decision and the model are in `MELDIQ_FRIENDS_AND_DMS_SPEC.md` ("connections", one table, no friend graph
     mining, no suggestions).
   - This reverses matrix row DM-04 ("no friend graph") for the reasons given there, and needs the owner's approval
     before B6 starts.
2. **DMs:**
   - One-to-one messaging is complete and encrypted.
   - The gaps are consent (requests), DM permissions, "invite to community" from a DM, a private note, timed mute, and
     group photos.
3. **Group DMs:**
   - Rename, add, remove, admins, leave and mute work.
   - Missing: group photo, plus translations of the info screen's hard-coded English (`ConversationInfoScreen.kt`:
     "Group info", "Rename group", "Add members", "Leave", "Remove from group"…). The translations are a FIX for B5.

## 5. To walk the remaining flows

The owner can create two spare Discord accounts (or use one spare plus their own) and one empty test server. With
those, and the owner's go-ahead, the next round can walk these to the end and record their error states:
- send, accept, decline and cancel a friend request;
- block and unblock;
- message requests;
- group add and remove;
- voice and video calls;
- screen share;
- invite create, use, expiry and revoke;
- ban, rejoin.
