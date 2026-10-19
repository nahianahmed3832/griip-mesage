# Orbit: workspaces, custom roles and integrations (pre-device report, 2026-10-19)

Branch `worktree-orbit-integrations` (worktree `.claude/worktrees/orbit-integrations`), on `internal-beta` f92e59a.
**Nothing is committed or pushed. Production, the VPS and AWS were not touched. No emulator was used.**

How to read the status words used below:

| Word | Meaning |
|---|---|
| **Implemented** | Code exists and compiles |
| **Unit-tested** | Pure rules tested in isolation (server vitest, Android JUnit) |
| **Sandbox/integration-tested** | Exercised through the real HTTP API of a test server: Orbit Sandbox provider, mocked provider HTTP, both storage modes |
| **Ready for device** | In the Orbit Test APK, against the local test backend, not yet tried on the phone |
| **Physically verified** | Tried on the HONOR phone. **Nothing in this round is, yet** |
| **Deployed / post-deployment verified** | **Nothing in this round is** |

The UX video named in the brief could not be watched (only the page chrome loads here). The principles the brief
listed (smart defaults, honest progress, value before commitment, ethical warnings, honest contrast) were applied as
written, not from the video itself.

## 1. Custom roles and permission enforcement (verified state)

- **Catalog** (`server/src/permissions/catalog.ts`): 31 typed permission keys in workspace, members, roles, teams,
  channels, messages, integrations, audit and policies; each flagged admin / owner-only / guest-allowed /
  restrictable. Base roles are fixed and protected: Owner (everything), Admin (everything but owner-only and
  `roles.delegate`), Member, Guest. Six templates (Moderator, HR manager, Project manager, App manager, Finance,
  Read-only).
- **Engine** (`permissions/rules.ts`): effective = base ∪ role grants (no owner-only; guests only guest-allowed) −
  role restrictions (restrictable only; deny wins). Owners are never restricted. Deny-by-default on the server: every
  sensitive route asks `authz.requirePermission`/`can`, never the app.
- **Anti-escalation**: grant or restrict only what you hold; admin powers need `roles.delegate` (owners delegate);
  nobody but owners acts on owners or other admins or on people holding admin powers they lack; a restricted member
  can't lift their own restriction. Fixed in the security pass: editing what a role does now also needs reach over
  every holder (an admin can't restrict another admin through a role edit), and promoting a guest back is checked
  against the powers their kept roles would return.
- **Workspace management**: roles CRUD with revisions (stale edits refused), archive → delete, assignment (≤ 20 per
  person, ≤ 50 active per workspace), "where does this permission come from", ownership transfer by typing the exact
  name, usage figures, full audit log with paging and filter.
- Status: **Implemented, unit-tested (18 rule tests), integration-tested (20 API tests, both storage modes), ready for
  device.** Not physically verified.

## 2. Integrations framework improvements

One connector framework (`server/src/integrations/`): registry and catalog, installs (A) separate from connections
(B), OAuth 2.0 with single-use state (hash only), S256 PKCE, sealed verifier, one-time begin token and browser-bound
cookie, AES-256-GCM sealed tokens with key rotation and reseal command, refresh with compare-and-swap, verified
webhooks with dedupe, a leased job queue with backoff, re-checks at delivery, audit, health, workspace and platform
policy, entitlements hook, incoming webhooks, workflows list, cross-app search, link previews, typed actions.

**Security review (independent read-only pass, then fixed and re-tested):**

| # | Finding | Fix |
|---|---|---|
| 1 high | GitHub notifications were checked per installation: a workspace admin could watch private repositories of a shared installation they can't read | Repositories are listed and checked with the admin's **own** token (`/user/installations/{id}/repositories`); the installation token and the app private key are gone |
| 2 med | A forwarded begin link let the recipient's provider account attach to the sender | The browser no longer connects anything: the callback seals the code; the starter's own signed-in app finishes it with a one-time code (`POST /v1/integrations/flows/:id/finish`) |
| 3 med | Two refreshes at once could mark a working connection expired (single-use refresh tokens) | One refresh per account at a time; a refusal is checked against the stored token first |
| 4 med | Disconnecting in one workspace killed the person's GitHub/Google connections elsewhere | GitHub revokes the single token; revocation is skipped while the same provider account backs another Orbit connection |
| 5 med-low | Editing a role could restrict admins the editor can't act on | Reach over every holder required for changes to powers, restrictions or archiving |
| 6 low-med | Promoting a guest back returned admin powers the promoter lacks | Checked against the person as they are and as they would be |
| 7 low | A member's request reset a disabled app's settings | Requests refused while disabled (409) |
| 8 low | Signed incoming-webhook requests could be replayed in the 5-minute window | Signed requests post once per signature |
| 9 low | OAuth codes and begin tokens reached the request log | `/v1/integrations/*` URLs logged without their query (checked on the running test server) |
| minor | Delivery accepted before its job was queued; duplicate subscriptions under a race; Jira previews ignored the link's site | Delivery released if queuing fails; unique key per app/channel/resource; previews only for the person's own Jira site |

Reviewed and found sound: token sealing, store queries (no SQL/NoSQL injection), page escaping and CSP, callback
replay, SSRF (fixed provider hosts, `redirect: "error"`), constant-time comparisons, cross-workspace id tampering,
platform admins having no workspace powers, no tokens in API answers or audit.

Status: **Implemented, integration-tested against the Sandbox and mocked providers (both storage modes), ready for
device.**

## 3. Status of each connector

Advertised capabilities equal implemented ones (a unit test enforces it). "Mock-tested" means tested against
recorded provider HTTP following each provider's published API, **not** against the real provider.

| Connector | What it does | Status |
|---|---|---|
| Orbit Sandbox | Every flow with made-up data: consent, search, browse, share, previews, linking, notifications, refresh, revocation | Sandbox-tested end to end; ready for device; refused in production |
| Incoming webhooks | Secret URL per channel; Bearer or HMAC; idempotent; pauses on lost rights | Integration-tested end to end; ready for device (needs no provider) |
| Google Drive | Search, browse (My Drive, shared drives), share links, previews; Sheets tabs (off by default) | Mock-tested; **awaiting OAuth config** |
| Google Calendar | Upcoming/search, share events, create event (write, off by default) | Mock-tested; awaiting OAuth config |
| OneDrive and Excel | Search, browse, share links (`/shares`), worksheets, tables, used-range size (no cell values) | Mock-tested; awaiting OAuth config |
| SharePoint | Site search, followed sites, libraries, workbook details | Mock-tested; awaiting OAuth config |
| Outlook Calendar | Upcoming/search (UTC), share events | Mock-tested; awaiting OAuth config |
| GitHub | Member: issue/PR search, previews. Workspace: PR/issue/release/workflow/deployment notifications | Mock-tested (HMAC webhooks, user-token repository scoping); awaiting a GitHub App |
| Jira | JQL search, previews (own site only), create issue, comment (writes off by default) | Mock-tested; awaiting OAuth config |
| Notion | Search shared pages, share links, previews | Mock-tested; awaiting OAuth config |
| Linear | Issue search, previews | Mock-tested; awaiting OAuth config |
| 32 more (Gmail, Outlook mail, Teams, Planner, GitLab, Bitbucket, Sentry, Datadog, PagerDuty, Jenkins, CircleCI, Confluence, Trello, Asana, monday.com, ClickUp, Coda, Airtable, Figma, Canva, Miro, Dropbox, Box, Zoom, Google Meet, Salesforce, HubSpot, Zendesk, Intercom, Zapier, Make, AI assistants) | Nothing | **Planned only** ("Coming soon", no scopes); several point to incoming webhooks for today |

None is **live**: no provider credentials exist, so on a server without them every provider app shows "Needs
configuration" with its setup steps.

## 4. Newly completed and proposed connectors

Completed this round: Outlook Calendar, Linear, Incoming webhooks, SharePoint workbook details (used ranges), Google
Calendar create event, Jira create issue/comment, cross-app search, GitHub repository scoping by the admin's own
access. Proposed next (in order): Jira and Linear notifications (needs a verified webhook design), Microsoft Graph
change notifications, Confluence (same Atlassian sign-in), GitLab, then the mail connectors only with an approved
narrow design.

## 5. Workplace features completed and deferred

Completed: custom roles and delegated administration, audit log, usage, ownership transfer, workspace setup card,
app directory with request/approve flow, workspace app policy, per-role app access, channel app notifications
(labelled "not end-to-end encrypted"), incoming webhooks, workflows list (typed actions only, no user code), share
from an app, link details under provider links, message actions (create a Jira issue, add to Google Calendar, with a
warning that the text leaves end-to-end encryption), plans/entitlements abstraction (no plans or prices invented, no
upgrade offered).

Deferred, with the reason: shared documents/lists/tasks inside Orbit (needs an end-to-end encrypted document model);
server-side message search and AI summaries (messages are end-to-end encrypted; nothing is sent to an AI service);
scheduled or multi-step workflows (needs a typed workflow design and review); mail connectors (high-sensitivity
scopes).

## 6. Design and UX

Cocoa design kept (GlassCard, ListCard, pills, banners); Home/DMs/Activity navigation, org switcher top-left and
profile top-right unchanged; no per-app bottom tabs. Apps screen with Directory, Installed, Requests, Mine, Workflows
and Activity. Each app shows what it does, the data it reaches and its setup progress before anyone commits; install
preselects least-privilege defaults; connect explains what will be allowed before the browser opens; outcomes in
plain words (approved, connected, connected with less than asked, denied, expired, someone else's sign-in). Danger
actions (uninstall, disconnect, transfer ownership) state their consequences factually, without pressure. Usage shows
limits as they are and says no plan is assigned. All new text in English, Simplified Chinese and Bengali (258 keys).

## 7. Source files and Git status

- Server: `src/permissions/`, `src/routes/roles.ts`, `src/integrations/` (kernel, lifecycle, accounts, resources,
  notifications, hooks, store, policy, tokens, http, catalog, providers/*), `src/store/rows.ts`, `src/entitlements.ts`,
  `migrations/016_roles_integrations.sql`; changes in `app.ts`, `authz.ts`, `roles.ts`, `routes/orgs.ts`, `config.ts`,
  `context.ts`, `jobs.ts`, `admin.ts`, `mongo/*`. Version 0.6.0.
- Android: `data/api/AdminModels.kt`, `IntegrationModels.kt`, `data/repo/RoleRepository.kt`,
  `IntegrationRepository.kt`, `ui/screens/workspace/roles/`, `ui/screens/workspace/apps/`, `AdminScreens.kt`,
  `WorkspaceSetup.kt`, `ui/screens/conversation/AppUpdates.kt`, `ShareFromApp.kt`; changes in `MainActivity.kt`
  (sign-in return), `AppRoot.kt`, conversation screens, `AndroidManifest.xml` (`griip://integrations`), i18n.
- Docs: `docs/INTEGRATIONS.md`, `docs/WORKSPACE_ROLES.md`, `docs/PLANS.md`, this report; `docs/MONGODB.md`,
  `docs/TESTING.md`, `deploy/.env.example`, `deploy/scripts/validate-env.sh`, CI workflow (Mongo job covers the
  integrations domain).
- Git: 32 tracked files modified (+486/−74) and about 14,000 lines in new files; **uncommitted, unpushed**. The
  owner's own uncommitted `android/app/build.gradle.kts` version bump and untracked `CLAUDE.md` in the main checkout
  were not touched.

## 8. Automated test and build results (this machine, 2026-10-19)

All after the security fixes, on the final code:

| Check | Result |
|---|---|
| Server typecheck (`tsc --noEmit`) and build | Pass |
| Server tests, SQLite | **518 passed, 0 failed**, 10 skipped (the live S3 tests, which need a real bucket) |
| Server tests, MongoDB (`ORBIT_TEST_MONGO_DOMAINS=accounts,preferences,integrations`, in-memory replica set) | **518 passed, 0 failed**, 10 skipped (same) |
| New suites inside those | permissions rules 18, custom roles 20, framework 29, providers 29, unit 12, hooks/workflows/search/limits 11 (119) |
| Android unit tests (`testDebugUnitTest`) | **446 passed, 0 failed** |
| Android lint (`lintDebug`) | **0 errors**, 104 warnings, 5 hints |
| Orbit Test APK (`assembleDebug -Porbit.sideBySide=true`) | Built (an earlier `assembleRelease` of this round also passed; not rebuilt after the fixes) |
| Smoke test against the running local test backend (MongoDB integrations domain) | 8/8: honest directory states, browser sign-in, "approved" until the app finishes, someone else's app refused (404), the starter's app connects, search works |

Not run: anything on a phone, anything against a real provider, anything in production.

## 9. APK

`artifacts/orbit-test-integrations-2026-10-19.apk` in the worktree (git-ignored): `com.griipbuddy.Orbit.test`
(installs beside the normal Orbit app), 0.2.1-beta (8), debug, 146 MB, built 14:08 after every fix, SHA-256
`36361931e999579022570f2451fb87e05b4190e6e6d240d2f1e7c39e3c78974e`. It replaces the earlier build of the same name.

## 10. Missing provider credentials and external approvals

Nothing is invented and nothing is in the app or Git. Needed, each by name in `deploy/.env.example`:
`INTEGRATIONS_PUBLIC_URL` (https), `INTEGRATIONS_TOKEN_KEY`; Google OAuth client; Microsoft app registration (tenant
`organizations` by default); a GitHub App (id, slug, client id/secret, webhook secret; no private key); Atlassian
OAuth app; Notion public integration; Linear OAuth app. Approvals before a public launch: Google verification
(`drive.metadata.readonly` is restricted and needs a security assessment; Calendar and Sheets scopes are sensitive;
Testing mode allows 100 listed users), Microsoft publisher verification (some tenants need admin consent for
`Sites.Read.All`/`Files.Read.All`), Atlassian distribution. Secrets belong in AWS Secrets Manager (via `*_FILE`), not
the environment file, when rolled out.

## 11. Remaining risks

- Every provider connector is proven only against mocks; live behaviour (scope names, error shapes, pagination) needs
  configured test accounts.
- App notifications are server-visible by design (titles, links, summaries; 30 days). Labelled in the app.
- A GitHub subscription is checked when created; if its admin later loses access to the repository it keeps posting
  until removed (revalidation per event would need GitHub calls per delivery). Workspace links pause when the admin
  loses `integrations.configure`.
- A malicious app on the same phone registering the `griip` scheme could read a sign-in finish code (still useless
  without the starter's Orbit session). Verified https App Links would close it.
- Rate limits and the refresh lock are per server process; several API instances behind a balancer would need a shared
  limiter (the stored-token re-check already covers refresh races between servers).
- The local test backend runs with the sandbox on and http on localhost; production must use https and no sandbox
  (enforced at start-up).
- Production MongoDB (Atlas) for the integrations domain is not set up; this round ran on a local replica set only.

## 12. Production

Not changed: no deploy, no VPS or AWS access, no DNS, port-forwarding or endpoint change, no production data touched
or migrated. The production database migration (016) and any rollout wait for the device-test gate and the owner's
approval.

## 13. Emulator

No Android emulator was launched or used. Nothing has run on a device yet.

## 14. Physical-device checklist (HONOR phone, Orbit Test build, local test backend)

Setup: SDK adb (not Homebrew's), `adb reverse tcp:18098 tcp:18098` and the LiveKit ports, install the side-by-side
Orbit Test APK (tap Continue), sign in to `http://localhost:18098`. Bots can join with `seed.mts join <code>`.

1. Workspace: create one, check the setup card, invite (code), bots join, change Member ↔ Guest, titles, remove.
2. Custom roles: create from a template and from scratch, edit (stale-revision message), archive, restore, delete;
   assign to a bot; "where does this come from"; Read-only stops posting; an admin can't edit a role an admin holds.
3. Ownership transfer: typing the exact name, the previous owner becomes admin.
4. Audit and usage screens; usage says no plan is assigned.
5. Apps directory: honest states (provider apps "Needs configuration", 32 "Coming soon", Sandbox "Available"),
   search and filters, app detail before commitment.
6. Install the Sandbox with defaults; request flow as a member, approve/decline as admin; disable keeps settings.
7. Connect the Sandbox: Custom Tab, consent, "Return to Orbit", app finishes it, "connected"; deny; let one expire.
8. Share from an app (attach panel): search, browse, share; the link card; link details under the link.
9. Link the workspace to the Sandbox, subscribe a channel, "Send a test event", the channel's app updates bar.
10. Incoming webhook: create, copy the secret once, post with `seed.mts hook …`, see it in the channel; rotate; delete.
11. Workflows and Activity tabs; disconnect and uninstall messages.
12. Main collaboration: send, reply in a thread, an attachment, a channel with app updates next to normal messages;
    a call only if a second device is available (bots don't take calls).
13. Language switch (English, 中文, বাংলা) on the new screens; light and dark.
