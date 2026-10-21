# Community invitations: audit and specification (2026-10-21)

The owner's additional brief, part 5. Discord evidence comes from `MELDIQ_LIVE_DISCORD_INSPECTION.md` (ADMIN-CTRL-006
to 008, COMM-CTRL-001), `MELDIQ_SOCIAL_AND_COMMUNICATION_AUDIT.md` (GDM-CTRL-004, DM-CTRL-003), round 1 and Discord's
documentation. Batch: **B4** (administration), with "invite from a DM" in B6.

**Not observed live:** Discord's invite dialog creates or reuses a real invite on the server as soon as it opens, so it
was not opened on the owner's servers. Opening and joining with a link, expired, revoked and banned cases are therefore
**D** (documentation) or **U**, not **O**. A test server and second account (social audit §5) would let the next round
observe them.

## 1. Discord's invitation lifecycle

| ID | Step | Discord | Ev |
|---|---|---|---|
| INV-CTRL-001 | Entry points | <ul><li>Server header › Invite to Server;</li><li>rail icon menu;</li><li>person icon next to the server name and on channel rows ("Invite to Channel");</li><li>Server Guide › Invite;</li><li>DM and profile menus › "Invite to Server ›" (lists your servers);</li><li>Server Settings › Invites › Create Invite Link</li></ul> | O (`member-server-menu`, `server-icon-menu`, `voice-row-hover`, `server-guide`, `2-dm-invite-to-server`, `server-invites`) |
| INV-CTRL-002 | Invite dialog | <ul><li>Friends list with Invite buttons, which sends the link as a DM;</li><li>the link `discord.gg/CODE` with Copy;</li><li>"Your invite link expires in 7 days" with Edit invite link: expire after 30 min / 1 h / 6 h / 12 h / 1 day / 7 days / Never, max uses no limit / 1 / 5 / 10 / 25 / 50 / 100, grant temporary membership</li></ul> | D, U (not opened: it would create an invite) |
| INV-CTRL-003 | Server Settings › Invites | List of active invites (inviter, code, uses, expires) with revoke (✕ on hover); "No active invite links"; Pause Invites; Create Invite Link | O `server-invites` (empty list); list rows D |
| INV-CTRL-004 | Pause Invites | "Security Actions": duration, Pause Invites, Pause DMs, Cancel, Save | O `security-actions` |
| INV-CTRL-005 | Access › how people join | Invite Only / Apply to Join (join application with up to 5 questions; moderators approve) / Discoverable | O `access`, `join-application` |
| INV-CTRL-006 | Opening a link, signed in | Preview card: icon, name, online and member counts, Join / Accept Invite; then the server opens (with onboarding if set) | D |
| INV-CTRL-007 | Opening a link, signed out | Web page with the same preview, then sign up or log in, then join | D |
| INV-CTRL-008 | Invalid, expired, revoked or used-up | "Invite Invalid: this invite may be expired, or you might not have permission to join" | D |
| INV-CTRL-009 | Banned | Joining fails ("You are banned from this server") | D |
| INV-CTRL-010 | Group DM invite | 24-hour link inside "Invite to Group DM" | O `2-invite-to-group-dm` |

## 2. Meldiq today (checked in the code)

| Part | Where | State |
|---|---|---|
| Create | `POST /v1/orgs/:orgId/invites` (`server/src/routes/orgs.ts:496`) | <ul><li>Role (Member, Guest; Admin by owners only), optional custom role, checked with `roleAssignmentRefusal` (no one can hand out more than they hold);</li><li>max uses 1–500 (default 1);</li><li>expiry 1 h–30 days (default 72 h);</li><li>guest end date;</li><li>code from an alphabet without look-alikes;</li><li>**only its hash is stored**, so the code is shown once</li></ul> |
| Android create | Community › Invites (`OrgScreens.kt:280–330`) | Role, custom role, uses 1 / 10 / 50, expiry 1 / 3 / 7 days, guest days; "Invite ready" sheet with QR, code, Share (text plus `meldiq://invite/CODE`) |
| List and revoke | `GET /v1/orgs/:orgId/invites`, `DELETE /v1/invites/:id`; Android list with All / Active / Ended filters, status, "n of m used", expiry, Revoke with confirmation | Works. Revoke by the creator or `members.invites.manage` |
| Preview | `POST /v1/invites/preview` (signed in, rate limited) | Community summary, `alreadyMember`, `banned`, problem codes `invite_not_found / _revoked / _expired / _used_up / _unavailable` |
| Accept | `POST /v1/invites/accept` | <ul><li>Bans rechecked (`403 banned`);</li><li>idempotent for double taps;</li><li>the race for the last use is safe;</li><li>an invite whose creator lost the right stops working;</li><li>new-account limits</li></ul> |
| Links | `meldiq://invite/CODE` (Android intent filter; web `inviteCodes.ts` parses it) | **Gap:** a custom scheme isn't a tappable link in most messengers and email, and there is no `https://` link, no Android App Link and no web page for someone without the app or not signed in |
| Tests | `server/test/invites_workspaces.test.ts`, `accounts_invites.test.ts` | Formats, problems, signed-out refusal, double tap, race, upgrade, registration with an invite |

**Verdict:** creation, validation, limits, role safety, bans and revocation are real and tested. The gaps are
mostly about the link and the sharing journey.

## 3. What to build (B4)

| ID | Change | Server | Android | Web | Tests |
|---|---|---|---|---|---|
| INV-B-01 | **https invite links**: `https://meldiq.app/invite/CODE` | — | App Link intent filter for `meldiq.app/invite/*` with `autoVerify`; share text uses the https link; QR keeps working for both forms | Route `/invite/:code` on meldiq.app and `/.well-known/assetlinks.json` with the app's signing-certificate fingerprints. **Owner:** the release fingerprint comes from the owner's signing key, which this session must never read, so the owner supplies it | Android `InviteCodes` parses both forms; web route test (Playwright) |
| INV-B-02 | **Public preview for signed-out visitors**: `GET /v1/invites/:code/public` returns name, icon, member count only (no channels, no members) | New route, 30/min per IP, same problem codes; nothing about bans (the visitor is unknown) | — | Landing: preview card; "Open in the app", "Sign in" and "Create an account". The code is carried through sign-up, as the Android app already does | Server: limits, no extra fields, revoked and expired answers |
| INV-B-03 | **Expiry and use choices**: add 1 hour and 30 days, uses 100 and 500 ("No limit" stays out: an endless code is a leak risk) | already allowed | Chips | — | Rules test |
| INV-B-04 | **Pause invites** for 1 h / 6 h / 24 h / until resumed | `orgs.invites_paused_until`; accept refuses with `invites_paused`; `workspace.edit` or `members.invites.manage`; audit | Community settings › Invites › Pause | — | Server test: paused, resumed, expires by itself |
| INV-B-05 | **Invite to community from a DM or profile** | — | Profile ⋯ and chat menu › "Invite to community ›" creates a 1-use, 7-day invite and sends it as an encrypted invite card in that DM | Card renders, opens preview | Card parse test; emulator walk |
| INV-B-06 | **Join confirmation** | — | After joining: "You joined {name}" with the rules and first channel (current flow), plus the onboarding questions when B8 lands | — | — |
| INV-B-07 | **Copy link later** | Not possible with hashed codes | The "Invite ready" sheet says "Copy now: it's shown once". Lost codes are revoked and recreated | — | — |

## 4. Acceptance tests (B4)

- **Server:**
  - expired, revoked and used-up codes;
  - paused community;
  - banned user returns;
  - a member invite can't grant a role above its creator, and an admin invite needs the owner;
  - the creator lost the right;
  - a double join uses the invite once;
  - two people race for the last use;
  - the public preview never returns more than name, icon and count.
- **Android:**
  - an https link opens the preview in the app;
  - a signed-out phone goes through sign-in and lands on the preview;
  - each problem shows its sentence;
  - joining lands in the community.
- **Phone (owner):**
  - share the link to another app;
  - tap it on a second phone with and without Meldiq installed;
  - try an expired and a revoked code;
  - try to rejoin as a banned test account.
