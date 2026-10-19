# Apps and integrations (2026-10-19)

How Meldiq connects workspaces to the tools they already use: the app directory, installing and approving apps,
connecting accounts, sharing items from apps, app notifications in channels, incoming webhooks and workflows. Code:
`server/src/integrations/`, `server/src/permissions/`; app: `ui/screens/workspace/apps/`, `ui/screens/conversation/`
(ShareFromApp, AppUpdates).

**Status:** every connector below is implemented against its provider's documented API and tested against **mocked**
provider HTTP and the in-process **Meldiq Sandbox**. No real provider credentials exist yet, so no connector has been
tested live. On a server without credentials, every provider app shows **Needs configuration**, honestly.

## 1. Concepts

- **Connector**: one app in the directory (Google Drive, GitHub). **Provider**: whose sign-in it uses (Google).
- **Capability**: one thing a connector does (`files.search`, `issues.create`), with the exact provider scopes it
  needs, whose account it uses (`member` or `workspace`) and its kind (`read`, `write` on explicit confirmation,
  `notify` into channels). A connector lists only capabilities its code implements (a unit test checks this).
- **Install (A)**: a workspace enables a connector with the capabilities an admin grants (least-privilege defaults
  preselected). One record per workspace and connector: `requested → installed | rejected`, `installed ↔ disabled`,
  `→ uninstalled`. Changed only by compare-and-set on its revision.
- **Connection (B)**: an external account. A **member** connection is always the person's own (OAuth consent at the
  provider). A **workspace** connection (GitHub App installation, sandbox organisation) is made only by *linking*, and
  only to targets the linking admin's own account can reach. A personal account never becomes a shared one.
- **States** shown to each person: `coming_soon`, `needs_configuration`, `blocked`, `available`, `approval_required`,
  `disabled`, `installed`, `connected`, `error` (most blocking first, `policy.ts › connectorState`). `connected` only
  after the provider accepted the token and the identity check succeeded.

## 2. Authorisation (enforced on every call)

`Kernel.requireUsable` runs for every search, browse, lookup, preview and action:

1. the caller is a member of the workspace (outsiders get 404);
2. `integrations.connect` (a role can restrict it: a read-only member can't use apps);
3. the platform hasn't blocked the connector (`INTEGRATIONS_DISABLED` or the platform policy) and it is configured;
4. the workspace policy allows it (`blockUnlisted`, `allowedConnectors`, `memberAccounts`);
5. it is installed and not disabled, **with this capability granted**;
6. the app's access setting includes the caller (everyone, or holders of chosen custom roles);
7. the caller's own connection exists, is healthy, and its **granted scopes** cover the capability.

Installing needs `integrations.install`, requesting `integrations.request` (+ policy), configuring
`integrations.configure`, linking `integrations.connect_workspace`, uninstalling or removing others' connections
`integrations.uninstall`, activity `integrations.activity`, the policy `policies.manage`. Platform administrators
(`PLATFORM_ADMIN_USER_IDS`) manage the global registry only; that gives them nothing inside any workspace.

## 3. Connecting an account (OAuth 2.0)

1. `POST /v1/orgs/:org/integrations/:app/connect` checks everything above, then stores a single-use flow (10 minutes):
   only `sha256(state)`, a one-time begin token's hash, and the state and PKCE verifier **sealed** to the flow.
2. The app opens `…/oauth/begin/:flow?t=…` in a Custom Tab. The begin token is spent and an HttpOnly, SameSite=Lax
   cookie (`Secure` over https) binds the flow to that browser; then a redirect to the provider with `state`, S256
   PKCE (Google, Microsoft, GitHub, Linear, sandbox), least scopes, `prompt=consent`.
3. `…/oauth/callback`: the state must match a live flow, the cookie must match (the provider's redirect can't be
   finished in another browser), the flow is spent whatever happens and the rules are checked again. **Nothing is
   connected yet**: the provider's code is sealed to the flow (status `approved`, 3 minutes to finish) and the page's
   "Return to Meldiq" link carries a one-time finish code (`meldiq://integrations?flow=…&finish=…`). Pages are
   self-contained HTML with `default-src 'none'`, `frame-ancestors 'none'`, `no-referrer`, `no-store`.
4. The app, signed in as **the person who started the sign-in**, sends `POST /v1/integrations/flows/:flow/finish`
   `{code}`. Only then is the code exchanged with the verifier, the identity call validates the token, granted scopes
   are recorded (normalised: Google's `userinfo.email`, Microsoft's resource prefix) and tokens are sealed and stored.
   So a begin link forwarded to someone else can't attach *their* provider account to the sender: the finish code
   lands in the other person's browser and app, and their Meldiq session isn't the one that asked (404, "started from
   another Meldiq account"). The app also asks `GET /v1/integrations/flows/:flow` when it comes back to the foreground.

Denied consent, expired flows, replayed callbacks, wrong browsers, someone else's sign-in and narrower grants (`note`
on the finished flow) each have their own message. Residual risk: an app on the same phone that registered the `griip`
scheme could read a finish code, but would still need the starter's Meldiq session to use it; verified https App Links
would remove that, and are a follow-up.

## 4. Credentials

- Sealed with **AES-256-GCM** under `INTEGRATIONS_TOKEN_KEY` (32 bytes), the AEAD the project already uses; no new
  protocol. Associated data binds each value to its purpose and record, so a copied ciphertext doesn't open elsewhere.
  Format `v1.<key id>.<nonce>.<ciphertext+tag>`. Rotation: put the old key in `INTEGRATIONS_TOKEN_KEY_PREVIOUS`, the new
  one in `INTEGRATIONS_TOKEN_KEY`, run `admin.js integrations-reseal --yes`, then drop the old key.
- Credentials live in their own table (`integration_credentials`), apart from account records, and never leave the
  server: no API answer, log, audit entry or report contains them. Client secrets are only sent to providers' token
  endpoints. The Android app never sees a token or password.
- Refresh: before expiry (60 s margin) and once after a 401. One refresh at a time per account on a server (callers
  share it), a compare-and-swap on the stored token keeps the first renewal, and a refusal (`invalid_grant`) is first
  checked against the stored token: if another server renewed it a moment ago with the same single-use refresh token
  (GitHub, Atlassian, sandbox), the newer token is used. Otherwise `invalid_grant` or a second 401 marks the account
  `expired` (Reconnect).
- Disconnect: provider revocation where it exists (Google `revoke`, GitHub's single-token `DELETE
  /applications/{client_id}/token`, sandbox), always followed by deleting Meldiq's copy. Revocation is skipped while the
  same provider account still backs another Meldiq connection (another workspace, or another app of that provider: Google
  ends the whole grant, so revoking Calendar would break Drive). Microsoft and Notion have no per-app revocation call:
  people remove the grant at myapps.microsoft.com or in Notion's settings.
- Logs: request logs of `/v1/integrations/*` drop the query string (OAuth codes, states and begin tokens).

## 5. Connectors

| Connector | Capabilities (granted by default unless noted) | Scopes | Status |
|---|---|---|---|
| Google Drive | search, browse (My Drive + shared drives), link, previews; Sheets tabs (off by default) | `openid email drive.metadata.readonly`; `spreadsheets.readonly` for tabs | Implemented, mock-tested, **needs credentials** |
| Google Calendar | upcoming/search, share events; **create event** (write, off by default) | `calendar.events.readonly`; `calendar.events` for create | Implemented, mock-tested, needs credentials |
| OneDrive and Excel | search, browse, link, previews (sharing links via `/shares`), worksheets (+ used-range size) and tables of .xlsx | `offline_access User.Read Files.Read` | Implemented, mock-tested, needs credentials |
| SharePoint | site search, followed sites, libraries, link, workbook details | `Sites.Read.All Files.Read.All` (+ base) | Implemented, mock-tested, needs credentials |
| Outlook Calendar | upcoming/search (calendar view, UTC), share events | `Calendars.Read` (+ base) | Implemented, mock-tested, needs credentials |
| GitHub | member: issue/PR search, previews; workspace: notifications (PRs, issues, releases, workflow runs, deployments) | GitHub App permissions (read-only), user tokens via PKCE; no private key (Meldiq never acts as the app) | Implemented, mock-tested (HMAC webhooks), needs a GitHub App |
| Jira | search (JQL), previews; **create issue**, **comment** (write, off by default) | `read:jira-work read:me offline_access`; `write:jira-work` | Implemented, mock-tested, needs credentials. Notifications planned |
| Notion | search shared pages, link, previews | none (integration set to Read content; pages chosen at consent) | Implemented, mock-tested, needs credentials |
| Linear | issue search, previews | `read` | Implemented, mock-tested, needs credentials |
| Figma (2026-10-19) | previews of Design, FigJam, Slides and prototype links (name, project, editor, last edit), from file metadata only | `current_user:read file_metadata:read`; HTTP Basic token requests, PKCE, refresh at `/v1/oauth/refresh` | Implemented, mock-tested, **needs credentials** (and Figma's app review to open beyond the owner's organisation). Figma's API has no file search; comment notifications planned |
| GitLab (2026-10-19) | search open issues and merge requests, previews of their links; gitlab.com or `GITLAB_BASE_URL` | `read_api read_user` (GitLab's narrowest read scope; only issue and MR endpoints are called), PKCE, rotating refresh tokens, revoke on disconnect | Implemented, mock-tested, needs credentials. Channel updates planned (a project webhook can post to an incoming webhook today) |
| Asana (round 6) | previews of task links (classic and new URL forms): name, project, assignee, due or done | `users:read tasks:read projects:read`, PKCE, secret in the form body, revoke by refresh token at `/-/oauth_revoke` | Implemented, mock-tested, **needs credentials**. Asana's task search is a paid API, so nothing is searched; project updates planned |
| Zoom (round 6) | previews of meeting links the person can see: topic, start, length, time zone; passcodes never stored or shown | granular `user:read:user meeting:read:meeting` (confirm in Zoom's reference when registering), HTTP Basic, PKCE, rotating refresh tokens, revoke at `/oauth/revoke` | Implemented, mock-tested, **needs credentials** and Zoom's app review for other accounts |
| Incoming webhooks | a secret URL per channel for your own tools | none (per-webhook secret) | **Implemented and tested end to end** (no provider needed) |
| Meldiq Sandbox | every flow with made-up data (test servers only) | `sandbox.*` | Implemented, tested end to end; refused in production |
| Gmail, Outlook mail, Teams, Planner/To Do, Bitbucket, Sentry, Datadog, PagerDuty, Jenkins, CircleCI, Confluence, Trello, monday.com, ClickUp, Coda, Airtable, Canva, Miro, Dropbox, Box, Google Meet, Salesforce, HubSpot, Zendesk, Intercom, Zapier, Make, AI assistants | none (Coming soon) | none requested | **Planned only.** Several say how to use incoming webhooks today |

Provider review before a public launch: Google verification (restricted `drive.metadata.readonly` needs a security
assessment; `calendar.events*` and `spreadsheets.readonly` are sensitive; Testing mode allows 100 listed users);
Microsoft publisher verification for multi-tenant use (some tenants require admin consent for `Sites.Read.All` /
`Files.Read.All`); Atlassian distribution (sharing) for other organisations.

Excel: the workbook API works only for `.xlsx` in OneDrive for Business or SharePoint (not consumer OneDrive, not
`.xls`). Meldiq lists worksheets, tables and each visible sheet's used-range address and size, never cell values, and
never edits a workbook. A shared link is a link; workbook details are a separate, explicit capability.

## 6. Sharing and previews

- **Share from an app** (attach panel › From an app): search or browse with your own account, or search **all** your
  connected apps at once; the item's name and link go into a normal end-to-end encrypted message with a link card. The
  item stays at the provider, which decides who can open it, and the sheet says so first.
- **Link details**: under a shared provider link, the viewer's own connection may add a line (status, owner). The server
  only recognises provider link formats and asks the provider *as the viewer*; it never fetches a pasted URL (no SSRF),
  and someone without access sees nothing extra. Only recognised provider hosts are ever sent to the server.
- **Actions** (message menu): "Create a Jira issue", "Add to Google Calendar", when that write capability is granted
  and connected. Fields are prefilled and editable; the sheet states that this text leaves end-to-end encryption.

## 7. Notifications, incoming webhooks and workflows

- **App notifications** (GitHub, sandbox): an admin links the workspace (own account proves access), then subscribes a
  channel they're in to a resource **their own account** can see within the linked target (GitHub: `GET
  /user/installations/{id}/repositories` with their token, never the installation's, so several workspaces linking one
  installation can't watch private repositories their admins can't read), and to event types (common ones
  preselected). One subscription per app, channel and resource (a unique key). Webhooks are
  verified over the raw body (GitHub `X-Hub-Signature-256`, sandbox HMAC with a 5-minute window), duplicates refused by
  delivery id, reduced at once to title/link/summary/actor (the rest is dropped), queued as idempotent jobs (lease,
  backoff, 6 attempts), and delivered only after re-checking the channel, the install, the link and that the
  subscription's creator can still `integrations.configure` (else it pauses with a reason). Each change posts once
  (dedupe key). Notifications are server-visible, kept 30 days, readable only by the channel's members, and labelled
  "not end-to-end encrypted" in the app.
- **Incoming webhooks**: `POST /v1/integrations/hooks/:id` with JSON `{title, text, url, actor}` (or `text` alone) and
  `Authorization: Bearer <secret>`, or `X-Meldiq-Timestamp` + `X-Meldiq-Signature: sha256=<HMAC(secret, "<ts>.<body>")>`
  (5-minute window). A signed request posts once per signature, so a captured one can't be replayed (a tool retries
  by resending the same signed request); with the bearer secret, `Idempotency-Key` posts once. A delivery whose job
  can't be queued is forgotten, so the provider's retry is processed. 60 posts/minute per webhook. Secrets are sealed and shown once;
  rotating replaces it at once. The same re-checks as above apply; a webhook pauses when its channel goes or its creator
  loses the right.
- **Workflows** (Apps › Workflows): every trigger → action in the workspace with its owner, last run and problem. The
  action set is fixed and typed (post a notification to a channel); nobody can run their own code on the server.

## 8. Data (integrations domain)

Tables (migration 016): `integration_installs`, `integration_accounts`, `integration_credentials`,
`integration_oauth_states`, `integration_subscriptions`, `integration_deliveries`, `integration_jobs`,
`integration_channel_events`, `integration_policies` (workspaces and `platform`), `integration_hooks`. All async
through `src/store/rows.ts`, so the domain is authoritative in **SQLite or MongoDB** (`MONGODB_DOMAINS=integrations`),
with the same documents the migration tools copy and verify (docs/MONGODB.md). Unique keys: one install per workspace
and connector, one account per workspace/connector/owner, one subscription per workspace/app/channel/resource,
single-use flow states, delivery ids, job idempotency keys, notification dedupe keys. Retention: flows a day after expiry, delivery ids 7 days, notifications 30 days, finished jobs
14 days (`INTEGRATIONS_*_RETENTION_DAYS`). Removal: workspace deletion revokes and deletes everything; a member leaving
loses their personal connections; uninstalling revokes every connection of that app.

## 9. Configuration and operations

Environment (deploy/.env.example): `INTEGRATIONS_PUBLIC_URL` (https in production), `INTEGRATIONS_TOKEN_KEY`
(`openssl rand -base64 32`), per-provider client credentials (`*_FILE` variants for mounted secrets),
`PLATFORM_ADMIN_USER_IDS`, `INTEGRATIONS_DISABLED`, `INTEGRATIONS_SANDBOX=on` (refused in production),
`ENTITLEMENT_LIMITS` (docs/PLANS.md). Each provider's setup steps are in its catalog entry (shown to admins while it
needs configuration) and on `GET /v1/platform/integrations` for platform admins, with the redirect URI
(`<public URL>/v1/integrations/oauth/callback`) and webhook URLs.

Operator commands: `admin.js integrations-status` (what's missing by name, counts, stuck jobs) and
`integrations-reseal [--yes]` (after a key rotation).

## 10. Not built (and why)

- Live testing with real providers: no credentials or test tenants exist yet.
- Jira and Linear notifications, Microsoft Graph change notifications: Jira's dynamic webhooks for OAuth apps aren't
  documented well enough to verify; planned with a verified design.
- Gmail and Outlook mail: high-sensitivity scopes; need an approved narrow design first.
- Shared documents, lists and tasks inside Meldiq: need an end-to-end encrypted document model (the server can't hold
  plaintext); a design is the next step, not a placeholder screen.
- Server-side message search and AI summaries: messages are end-to-end encrypted; nothing is sent to an AI service.
