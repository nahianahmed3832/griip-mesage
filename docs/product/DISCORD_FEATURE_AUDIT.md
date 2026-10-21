# Discord feature audit (reference for Meldiq), 2026-10-21

What Discord does, feature by feature, and the evidence behind each statement. Meldiq's status for every ID is in
`MELDIQ_FEATURE_GAP_MATRIX.md` (same IDs). How Meldiq's community screens were designed from this is in
`MELDIQ_DISCORD_REFERENCE_AUDIT.md`, the first round's detailed record with screenshots, which this file extends rather
than repeats.

Nothing from Discord is copied into Meldiq: no code, assets, logos, icons, names or brand. Discord's screens are a
reference for structure and behaviour only.

## Evidence classes

| Class | Meaning | Sources used |
|---|---|---|
| **O** observed | Seen in Discord's signed-in desktop app (owner's account, viewed only, 2026-10-21 round 1) | `research/discord/` captures (git-ignored: they show real people) |
| **D** documented | Read in Discord's official documentation | Developer docs via REA round 1 (Permissions, Channels); this round: developer docs pages Guild (features, verification, MFA level, onboarding, welcome screen, member timeout), Channel (types, slowmode, threads, forum fields, stage, video quality, bitrate), Guild Scheduled Event, Auto Moderation, Poll, Emoji; help center articles read in round 1 (mobile layout, categories, forums, announcements) |
| **U** unverified | Known product behaviour not confirmed from a source in this audit | Marked so; check before building on it |

**Not observed (both rounds):**
- Discord's Android and iOS apps.
- Voice, video and screen share in use.
- Stage channels, onboarding questions and AutoMod rules in a live server.
- Discord's account security screens (2FA setup, recovery codes).

**Access limits:**
- No signed-in mobile session.
- The help center blocks headless browsers (a Cloudflare check, which was not worked around).
- The onboarding page of the developer docs moved: `guild-onboarding` gives a 404, and the object is now
  documented on the Guild page.

## A. Accounts and authentication

| ID | Feature | Discord behaviour | Ev. |
|---|---|---|---|
| AUTH-01 | Registration | Email, display name, username, password, date of birth; email verification afterwards | U |
| AUTH-02 | Login | Email or phone + password; "Forgot your password?" inline; QR login from the mobile app on desktop | O (desktop login page, round 1), U (QR) |
| AUTH-03 | Email verification | Link emailed; some actions blocked until verified; server verification level LOW requires it | D (verification levels) |
| AUTH-04 | Password reset | Emailed link | U |
| AUTH-05 | 2FA | Authenticator app (TOTP), SMS backup, backup codes; servers can require 2FA for moderators (MFA level ELEVATED) | D (MFA level), U (setup) |
| AUTH-06 | New device / sessions | Devices list with log-out per session | U |
| AUTH-07 | Account recovery without email | Backup codes for 2FA; otherwise support ticket | U |
| AUTH-08 | Recovery options hidden by default | Login shows email, password and one "Forgot your password?" link; recovery is a separate flow | O |

## B. Navigation and identity

| ID | Feature | Discord behaviour | Ev. |
|---|---|---|---|
| NAV-01 | Server rail | Icons with unread pill, selected marker, mention count; add and discover at the end | O |
| NAV-02 | DM list | Search ("find or start a conversation"), friends, DMs with presence | O |
| NAV-03 | Mobile tabs | Home, Notifications, You | D (help) |
| NAV-04 | Activity / mentions inbox | Notifications tab: mentions, replies, friend requests | D (help) |
| NAV-05 | Unread indicators | Bold channels, mention badges, "NEW" divider | O |
| NAV-06 | Search | Per server and per DM, with filters (from:, has:, in:, before:) | O, U (filters) |
| NAV-07 | Profiles | Avatar, banner, about me, pronouns, roles in the server, mutual servers | U |
| NAV-08 | Presence and custom status | Online, idle, do not disturb, invisible; custom status with emoji and expiry | U |
| NAV-09 | Settings | User settings: account, profile, privacy and safety, devices, notifications, appearance, accessibility, voice and video | U |
| NAV-10 | Back behaviour (mobile) | System back closes sheets, then returns | D (help) |

## C. Communities and discovery

| ID | Feature | Discord behaviour | Ev. |
|---|---|---|---|
| COM-01 | Create | Template choice, name, icon | U |
| COM-02 | Identity | Name, icon, banner (feature-gated), description, invite splash | D (features BANNER, INVITE_SPLASH) |
| COM-03 | Visibility and joining | Invite only, apply to join (membership screening), discoverable | O, D (DISCOVERABLE, MEMBER_VERIFICATION_GATE_ENABLED) |
| COM-04 | Discovery and preview | Directory; preview before joining (PREVIEW_ENABLED) | D |
| COM-05 | Invites | Expiring, limited-use links; pausing invites (INVITES_DISABLED) | D, U (expiry options) |
| COM-06 | Welcome screen | Description and up to 5 recommended channels | D |
| COM-07 | Onboarding questions | Prompts (multiple choice or dropdown) whose options grant roles and channels; default channels (at least 7, 5 writable by everyone) | D |
| COM-08 | Rules screening | Rules accepted before talking | D (membership screening), O |
| COM-09 | Member list | Grouped by hoisted role, online/offline | O |
| COM-10 | Leave | Leave server (not the owner) | O |
| COM-11 | Delete | Owner only, confirm by typing the name | U |
| COM-12 | Ownership transfer | Owner picks a member | U |
| COM-13 | Server notifications | All / mentions / nothing, suppress @everyone, mute for a duration | O (menu), U (details) |
| COM-14 | Verification level | None → verified email → account age → membership age → phone | D |
| COM-15 | System messages | Join messages, setup tips, flags to suppress | D |

## D. Categories and channels

| ID | Feature | Discord behaviour | Ev. |
|---|---|---|---|
| CH-01 | Category create / rename / delete | Server menu and category menu | O |
| CH-02 | Category order, collapse | Drag; collapsed shows unread channels only | D (help), O |
| CH-03 | Channel create | Type, name, private toggle, then members and roles | O |
| CH-04 | Channel edit | Name, topic (1024; forum 4096), slowmode, age-restricted | O, D |
| CH-05 | Channel delete | With confirmation | O |
| CH-06 | Channel move / order | Drag; sync prompt when moving into a category | D |
| CH-07 | Private channels | Visible only to allowed roles and members | O |
| CH-08 | Category permission sync | "Not synced, Sync now" | O |
| CH-09 | Overwrites | Deny / inherit / allow per role and member, in the documented order | D (REA round 1) |
| CH-10 | Announcement channels | Followable into other servers; publish | D (help) |
| CH-11 | Forum channels | Up to 20 tags, required tag flag, default sort, list or gallery, default reaction | D |
| CH-12 | Voice channels | User limit up to 99, bitrate by tier, video quality auto/720p | D |
| CH-13 | Stage channels | Speakers and audience, up to 10,000 | D |
| CH-14 | Slowmode | 0–21,600 s per user; bypass permission | D |
| CH-15 | Channel and category context menus | Mark read, mute, notifications, move, edit, delete | O |
| CH-16 | Media channels | Gallery-first forum variant | D (type 16) |

## E. Conversations and content

| ID | Feature | Discord behaviour | Ev. |
|---|---|---|---|
| MSG-01 | Compose and send | Text, markdown, mentions, emoji, attachments | O |
| MSG-02 | Edit | Own messages; "(edited)" | O |
| MSG-03 | Delete | Own, or any with Manage Messages | O |
| MSG-04 | Reply | Quoted reply with optional ping | O |
| MSG-05 | Threads | From a message; auto-archive 1 h / 24 h / 3 d / 7 d; locked; private threads | D |
| MSG-06 | Reactions | Quick reactions plus full picker; super reactions (paid) | O |
| MSG-07 | Pins | Per channel | O |
| MSG-08 | Forward | To another channel or DM | O |
| MSG-09 | Link previews | Server-fetched embeds | U |
| MSG-10 | Mentions | @user, @role, @everyone/@here (permission-gated) | O, U |
| MSG-11 | Unread divider, mark unread | "NEW" line; Mark Unread in the menu | O |
| MSG-12 | Polls | Up to 10 answers (55 chars), question 300 chars, up to 32 days, multiselect, owner ends early, no editing | D |
| MSG-13 | Scheduled events | Stage, voice or external; scheduled → active → completed/cancelled; weekly/monthly/yearly recurrence; "interested" subscribers; 100 open per server | D |
| MSG-14 | Typing indicator | "X is typing…" | U |
| MSG-15 | Bookmarks / reminders | Save message, remind me | O (menu) |
| MSG-16 | Attachments | Multiple files, previews, spoiler | U |
| MSG-17 | Voice messages | Mobile hold-to-record | U |
| MSG-18 | Markdown | Bold, italic, code, quotes, spoilers, headers, lists | U |

## F. DMs and groups

| ID | Feature | Discord behaviour | Ev. |
|---|---|---|---|
| DM-01 | One-to-one DM | Header with calls, pins, profile | O |
| DM-02 | Group DM | Up to 10 people, name and icon, add people | O, U (limit) |
| DM-03 | Message requests | DMs from non-friends go to a requests list | U |
| DM-04 | Friends | Requests, accept, block | O (list) |
| DM-05 | Block | Hides messages, prevents DMs | U |
| DM-06 | Close / favourite DMs | Close from the list; favourite on mobile | O, D |

## G. Roles, permissions and moderation

| ID | Feature | Discord behaviour | Ev. |
|---|---|---|---|
| MOD-01 | Roles: create, order, colour, hoist, mentionable, icon | Role list with member counts; drag to reorder | O, D (ROLE_ICONS) |
| MOD-02 | Base permissions (@everyone) | Default permissions card | O |
| MOD-03 | Assign roles | Per member | O |
| MOD-04 | Kick | Remove a member (they can rejoin) | U |
| MOD-05 | Ban / unban | With reason, delete recent messages | O |
| MOD-06 | Timeout | Up to 28 days; not on admins or the owner; Moderate Members | D |
| MOD-07 | Report | Report a message or user to Discord's Trust & Safety | U |
| MOD-08 | AutoMod | Keyword (6 rules), spam, preset lists, mention spam, member profile; actions block, alert, timeout (≤ 4 weeks), block interaction; up to 20 exempt roles and 50 channels | D |
| MOD-09 | Audit log | Every administrative action with actor and reason | O (settings), U (contents) |
| MOD-10 | Raid protection | Join raid alerts, DM spam alerts | D (RAID_ALERTS_DISABLED) |
| MOD-11 | Explicit media filter | Off / members without roles / all | D |
| MOD-12 | 2FA for moderators | MFA level ELEVATED | D |

## H. Calls, voice and video

| ID | Feature | Discord behaviour | Ev. |
|---|---|---|---|
| CALL-01 | 1:1 voice and video | From DM header | O |
| CALL-02 | Group calls | In group DMs | O |
| CALL-03 | Voice channels | Drop in/out, participant list in the channel list | O, D |
| CALL-04 | Camera switch, mute, deafen | Standard controls | U |
| CALL-05 | Screen share | Desktop window/screen; mobile screen share; up to 1080p60 with Nitro | U |
| CALL-06 | "Go Live" game streaming | Stream a game into a voice channel; viewers join the stream | U |
| CALL-07 | Stage | Raise hand, invite to speak | D (stage), U (flow) |
| CALL-08 | Noise suppression, echo cancellation | Krisp-style suppression | U |
| CALL-09 | Reconnect | Automatic | U |
| CALL-10 | Soundboard, activities | Short sounds; embedded games | D (SOUNDBOARD) |

## I. Expression

| ID | Feature | Discord behaviour | Ev. |
|---|---|---|---|
| EXP-01 | Emoji picker | Search, categories, recent/frequent, skin tone, server emoji sections | O (picker in menu), U (details) |
| EXP-02 | Reaction picker | Same picker from Add Reaction | O |
| EXP-03 | Skin tone | One global choice | U |
| EXP-04 | Custom server emoji | 128×128, up to 256 KiB, JPEG/PNG/GIF/WebP/AVIF, animated, role-restricted; Create/Manage Expressions | D |
| EXP-05 | Server stickers | Per server, slots by tier | D (MORE_STICKERS) |
| EXP-06 | GIF picker | Tenor integration | U |
| EXP-07 | Super reactions, Nitro emoji anywhere | Paid | U |

## Changes since round 1

This round added the D rows above from the developer documentation:
- guild features;
- verification and MFA levels;
- onboarding and the welcome screen;
- member timeout;
- channel types, slowmode, thread archiving, forum fields, stage and bitrate;
- scheduled events;
- AutoMod;
- polls;
- custom emoji.

It also identified the features Meldiq lacks entirely. The matrix lists them as gaps with a plan.
