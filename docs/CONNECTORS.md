# Connectors: register, inventory and owner actions (2026-10-20)

Every outside service Meldiq can use, its real state, and what is still needed. The live answer is the **connector
register**: `GET /v1/admin/connectors` (platform admins only; app: Settings › Service health › Connectors).

## 1. How the register decides a state

| State | Meaning |
|---|---|
| `working` | A measured success: a real call, a connector check, a validated connection, or a received webhook. |
| `configured` | Settings are present but nothing has been measured yet. Never shown as working. |
| `failing` | The latest outcome is a failure from the last 24 hours. Only the error kind is shown. |
| `needs_configuration` | Settings are missing. The register lists their **names** (never values). |
| `awaiting_provider` | The provider has to grant access first (TikTok). |
| `disabled` | The operator turned it off (`INTEGRATIONS_DISABLED`). |
| `planned` | In the catalog with no capability claimed; nothing can be connected. |

`POST /v1/admin/connectors/:id/check` runs only checks that send nothing to anyone and cost nothing:

- `email`: an SMTP sign-in (`verify`).
- `storage`: lists the store.
- `push-fcm`: gets a Google access token with the service account.
- `mongodb`: a ping.

Each check is audited (`connector.check`). There are no checks for AI (billed per request), the inbox (it would message
customers) or OAuth apps (they need a person's own sign-in).

Code:
- `server/src/ops/connectors.ts`.
- `server/test/ops_connectors.test.ts`.
- Android: `ui/screens/profile/ConnectorsScreen.kt` and `ConnectorRules.kt`, tested by `ConnectorRulesTest`.

## 2. Inventory

"Code" says what Meldiq itself does; "Production" is what the owner's server can do today. Production settings weren't
read for this report (reading production was not permitted from this session), so production states come from the
deploy records in `docs/` and are marked *(record)*.

### Platform services

| Connector | Code | Production (record) | Blocker |
|---|---|---|---|
| Email: Hostinger SMTP | Done: verification, invitations, recovery, notices; SMTP check added | Working since API 0.7.1 | none |
| File storage: Amazon S3 | Done | Working since API 0.2.0 | none |
| Calls: LiveKit | Done | Working | none (left untouched) |
| UnifiedPush / ntfy | Done | ntfy container up; `PUSH_UNIFIEDPUSH_HOSTS` steps 3–6 of `docs/PUSH_SERVER.md` not applied | Owner go-ahead for the production steps |
| Firebase Cloud Messaging | Done (sender, token check added) | Not configured | Firebase project and service account (owner) |
| MongoDB | Done (accounts, preferences, integrations) | Not used | Atlas URI and cut-over approval |
| AI: Claude, ChatGPT, Gemini | Done, off by default | Not deployed (round 7) | API key and budget approval; deploy |
| Outgoing webhooks and API keys | Done (signed, retries, auto-disable) | Not deployed (round 7) | Deploy |
| Incoming webhooks | Done | Deployed since 0.6.0; use on production not verified (needs `INTEGRATIONS_TOKEN_KEY`) | none known |

### Unified Inbox (round 8, `docs/UNIFIED_INBOX.md`)

| Connector | Code | Production | Blocker |
|---|---|---|---|
| Facebook Messenger | Done; tested on the phone against a local stand-in for Meta | Not deployed | Meta app, `META_APP_SECRET`, verify token, a Page; deploy; App Review for other people's Pages |
| Instagram messaging | Done; tested the same way | Not deployed | Same, plus an Instagram professional account |
| WhatsApp Business Platform | Done (templates, 24 h window); tested the same way | Not deployed | Meta app with WhatsApp, a test number, business verification for live use |
| TikTok Business Messaging | Shown as awaiting access | — | TikTok approval; not offered in the US, EEA, Switzerland or UK |

### Workspace apps (OAuth, `docs/INTEGRATIONS.md`)

All of these are implemented, with least-privilege scopes, PKCE and state, sealed tokens, refresh and revocation, and
audit records. All of them **need configuration** on production: no provider app is registered yet.

| Connector | Settings | Provider review |
|---|---|---|
| Google Drive, Google Calendar | `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET` | Drive metadata is a restricted scope: Google verification plus a security assessment before public launch; up to 100 test users before that |
| OneDrive and Excel, SharePoint, Outlook Calendar | `MICROSOFT_OAUTH_CLIENT_ID`, `MICROSOFT_OAUTH_CLIENT_SECRET`, `MICROSOFT_OAUTH_TENANT` | Publisher verification recommended |
| GitHub | `GITHUB_APP_ID`, `GITHUB_APP_SLUG`, `GITHUB_APP_CLIENT_ID`, `GITHUB_APP_CLIENT_SECRET`, `GITHUB_APP_WEBHOOK_SECRET` | none for your own org |
| Jira | `ATLASSIAN_OAUTH_CLIENT_ID`, `ATLASSIAN_OAUTH_CLIENT_SECRET` | Distribution approval for other sites |
| Notion | `NOTION_OAUTH_CLIENT_ID`, `NOTION_OAUTH_CLIENT_SECRET` | Public integration review |
| Linear | `LINEAR_OAUTH_CLIENT_ID`, `LINEAR_OAUTH_CLIENT_SECRET` (+ `LINEAR_WEBHOOK_SECRET`) | none |
| Figma | `FIGMA_OAUTH_CLIENT_ID`, `FIGMA_OAUTH_CLIENT_SECRET` | Figma review to publish |
| GitLab | `GITLAB_OAUTH_CLIENT_ID`, `GITLAB_OAUTH_CLIENT_SECRET` (`GITLAB_BASE_URL`) | none |
| Asana | `ASANA_OAUTH_CLIENT_ID`, `ASANA_OAUTH_CLIENT_SECRET` | none |
| Zoom | `ZOOM_OAUTH_CLIENT_ID`, `ZOOM_OAUTH_CLIENT_SECRET` | Zoom app review to publish |
| Meldiq sandbox | `INTEGRATIONS_SANDBOX=on` (development only) | — |

Each OAuth app needs:

- **Redirect URIs:** `https://web.meldiq.app/…`, `https://msg.griipbuddy.cloud/…` (the production `PUBLIC_API_URL` in
  `docs/DEPLOYMENT.md`) and `http://localhost:18098/…`, each ending in `/v1/integrations/oauth/callback`. See §4.
- **Webhook URL** (GitHub, Linear): `https://web.meldiq.app/v1/integrations/webhooks/<connector>`. `web.meldiq.app`
  forwards `/v1` to the same API.

Planned, with nothing claimed: Gmail, Google Meet, Outlook mail, Teams, Planner, Bitbucket, Sentry, Datadog, PagerDuty,
Jenkins, CircleCI, Confluence, Trello, monday.com, ClickUp, Coda, Airtable, Canva, Miro, Dropbox, Box, Salesforce,
HubSpot, Zendesk, Intercom, Zapier and Make. The alerting and automation tools can already post to an incoming webhook.

## 3. What changed in this round

- **Connector register:** one list for platform admins, covering platform services, inbox channels and catalog apps.
  It shows a measured state, missing setting names, counts only and error kinds only.
- **Free checks:** SMTP sign-in (`Mailer.verify`), storage listing, FCM credential check (`FcmService.checkCredentials`)
  and MongoDB ping. Each one is audited.
- **Android:** a Connectors screen under Service health, grouped problems first, with a Check button where a free check
  exists. It's English only, like the other operator screens.
- **Catalog:** removed the stale "AI assistants · coming soon" entry. AI is a real feature since round 7 C8 and appears
  as three AI provider connectors.
- **`deploy/.env.example`:** added the AI, Unified Inbox and `LINEAR_WEBHOOK_SECRET` setting names, which were missing.
- **Data:** no database or schema change.

## 4. Browser setup status

### Google: done in the browser on 2026-10-20

- **Project:** Google Cloud project `meldiq` with no billing account.
- **APIs enabled:** Drive, Calendar and Sheets.
- **Consent screen:** app Meldiq, External, Testing. The owner accepted the Google API Services User Data Policy.
- **Scopes:** `userinfo.email` (non-sensitive); `calendar.events`, `calendar.events.readonly` and
  `spreadsheets.readonly` (sensitive); `drive.metadata.readonly` (restricted).
- **Test users:** one, the owner's console account. Testing mode allows up to 100.
- **Web OAuth client:** "Meldiq server (production)", client ID
  `425699156376-2e8sndbe4rgrbbs75bajb5fr98g8f74r.apps.googleusercontent.com`.
- **Redirect URIs:**
  - `https://web.meldiq.app/v1/integrations/oauth/callback`: the Meldiq domain. `web.meldiq.app` already forwards
    `/v1` to the same API.
  - `https://msg.griipbuddy.cloud/v1/integrations/oauth/callback`: what production sends today, until
    `INTEGRATIONS_PUBLIC_URL` is switched.
  - `http://localhost:18098/v1/integrations/oauth/callback`: local device tests.
- **Domain switch:** to move production to the Meldiq domain, set `INTEGRATIONS_PUBLIC_URL=https://web.meldiq.app`
  (approved change). After that, the `msg.griipbuddy.cloud` entry can be removed. Every other provider below is
  registered with the `web.meldiq.app` URI first.
### Firebase Cloud Messaging: set up in the browser on 2026-10-20

- **Project:** Firebase was added to the `meldiq` Cloud project. The owner accepted the Firebase terms. Google Analytics
  is off. Plan: Spark (free).
- **Android apps:**
  - `com.meldiq.android` ("Meldiq"), App ID `1:425699156376:android:fe0f3e6dee3c688da58bed`.
  - `com.meldiq.android.test` ("Meldiq Test"), App ID `1:425699156376:android:65c46ea38a53848fa58bed`.
- **API:** the Firebase Cloud Messaging API (V1) is enabled.
- **Verified on the phone 2026-10-20** against the local server: a real wake-up arrived about 1.6 s after sending (see
  `docs/PUSH_FCM.md`). In China, the phone's VPN must be in Global mode.
- **Production, still to do:** `FCM_PROJECT_ID=meldiq` and `FCM_SERVICE_ACCOUNT_FILE` on the server (owner's key,
  never in Git), plus a deploy.
- **Client secret: needs replacing before any use.** The only secret (`****72XH`) was exposed in a chat transcript on
  2026-10-20.
  - Fix: add a new secret on the client page, then disable and delete the old one. Google allows deleting a secret only
    once a second one exists.
  - Save the new secret in `~/.meldiq-secrets/google_oauth_client_secret` (chmod 600, outside Git). The file there now
    holds the wrong content.
  - The real Drive connection test was skipped at the owner's request.
- **Production:** needs `GOOGLE_OAUTH_CLIENT_ID` and `GOOGLE_OAUTH_CLIENT_SECRET_FILE` on the server, plus an approved
  restart.

### OAuth connector apps: registered in the browser on 2026-10-20

Every app below uses the same three callback URLs, in this order (where the provider takes several):

1. `https://web.meldiq.app/v1/integrations/oauth/callback`
2. `https://msg.griipbuddy.cloud/v1/integrations/oauth/callback`
3. `http://localhost:18098/v1/integrations/oauth/callback`

Client ids are public identifiers and are recorded here. Secrets are not: each goes in a file under
`~/.meldiq-secrets/` (chmod 600, outside Git), then into the server as `<NAME>_FILE`.

| Connector | Client id | Secret file in `~/.meldiq-secrets/` | State |
|---|---|---|---|
| GitHub App | `Iv23liVTng75C9dXJMoQ` (App ID `5393746`, slug `meldiq`) | `github_app_client_secret`, `github_app_webhook_secret` | Registered; secrets not saved yet |
| Notion | `3ffd872b-594c-8195-80a2-003773590e82` | `notion_oauth_client_secret` | Registered; secret not saved yet |
| Jira (Atlassian) | `JaODbOBFVbpG2HG1eoyZ8xiV5bixElWq` | `atlassian_oauth_client_secret` | Registered; secret not saved yet |
| Figma | `ZSczJsXgF3Q7KZsRcSLFBi` | `figma_oauth_client_secret` | Registered; **secret lost** (see below) |
| Microsoft | — | — | Blocked: personal account has no directory |
| Linear | `6176399d2f31d5ea6572c253d8b413b6` | `linear_oauth_client_secret` | Registered; secret not saved yet; webhook not set (see below) |
| GitLab, Asana, Zoom | — | — | Skipped for now (owner, 2026-10-20) |
| Meta (inbox) | App ID `1937328653907781`; Instagram app ID `1607863423514741` | `meta_app_secret`, `instagram_app_secret`, `meta_webhook_verify_token` | Created; webhook waits for the 0.8.0 deploy; business link and WhatsApp pending |

**GitHub App `meldiq`**
- Owner `@nahianahmed3832`. Installable only on that account. To let other organisations install it, use Advanced ›
  Make public.
- Homepage `https://web.meldiq.app`.
- Callbacks: all three. "Expire user authorization tokens" is on, so refresh tokens are issued; the provider handles them.
- Not requested during installation: user authorization (OAuth). Device flow is off.
- Webhook: active, `https://web.meldiq.app/v1/integrations/webhooks/github`, SSL verification on. The **secret field is
  empty** until the owner sets it.
- Repository permissions, read-only: Actions, Deployments, Issues, Metadata, Pull requests. No organisation or account
  permissions.
- Events: Issues, Pull request, Workflow run, Deployment status.
- **Release is not subscribed.** GitHub offers the Release event only with the Contents permission, which the catalog
  doesn't list (it would allow reading code). Decide whether to add Contents: read-only, or remove Release from the
  catalog's setup text.
- GitHub requires a private key to be generated before the app can be installed. Meldiq doesn't use it, so the owner
  keeps it outside the server.
- Server settings: `GITHUB_APP_ID=5393746`, `GITHUB_APP_SLUG=meldiq`, `GITHUB_APP_CLIENT_ID=Iv23liVTng75C9dXJMoQ`,
  `GITHUB_APP_CLIENT_SECRET_FILE`, `GITHUB_APP_WEBHOOK_SECRET_FILE`.

**Notion connection "Meldiq"**
- OAuth, public, installable in any workspace. Development workspace: GriipBuddy's Space. Not published to the
  Marketplace. The owner accepted Notion's Developer Terms.
- Redirect URIs: all three (Notion accepted the localhost one).
- Capabilities: Read content only. No update, insert or comment capabilities, and **No user information**. The code then
  labels the connection by workspace name instead of email.
- Server settings: `NOTION_OAUTH_CLIENT_ID=3ffd872b-594c-8195-80a2-003773590e82`, `NOTION_OAUTH_CLIENT_SECRET_FILE`.

**Atlassian OAuth 2.0 (3LO) app "Meldiq"** (console app id `80827079-9822-4aea-a59d-b24136d1ea75`)
- The owner accepted Atlassian's developer terms.
- Access type: Resource-level (only the site the person picks).
- Scopes: Jira platform REST API `read:jira-work` (classic) and User identity API `read:me`. `offline_access` is asked at
  sign-in and isn't configured in the console.
- `write:jira-work` was **not** added: creating issues and comments is off by default. Add it in the console before a
  workspace turns those on, or sign-ins asking for it will fail.
- Callbacks: all three. Atlassian enforces rotating refresh tokens. Distribution (sharing with other organisations) is
  off.
- Server settings: `ATLASSIAN_OAUTH_CLIENT_ID=JaODbOBFVbpG2HG1eoyZ8xiV5bixElWq`, `ATLASSIAN_OAUTH_CLIENT_SECRET_FILE`.

**Figma app "Meldiq"**
- Owner: GriipBuddy's team. Status: Draft (only the owner and the team's admins can connect). The owner accepted
  Figma's Developer Terms.
- Redirect URLs: all three.
- Scopes: `current_user:read` and `file_metadata:read` only.
- **Secret lost.** Figma shows the secret once, at creation, and it wasn't saved. The console has no way to issue a new
  one. Either recover it (if it was copied somewhere) or delete the app and create it again, which gives a new client id.
- Figma requires apps to be re-published under its new rules before people outside the team can connect.
- Server settings: `FIGMA_OAUTH_CLIENT_ID=ZSczJsXgF3Q7KZsRcSLFBi`, `FIGMA_OAUTH_CLIENT_SECRET_FILE`.

**Microsoft Entra: blocked**
- Signed in as a personal Microsoft account. Entra says creating apps outside a directory has been deprecated.
- Needs a directory: an Azure sign-up (asks for a card), the Microsoft 365 Developer Program, or a work account. Owner
  decision; left for later.

**Linear OAuth application "Meldiq"** (console id `e1adb6c5-a73b-4d7c-a2b8-af79be90af14`)
- Workspace `linear.app/meldiq`, region United States, free plan. Created with the owner's approval. Onboarding steps
  were skipped and email subscriptions turned off.
- Developer "Meldiq", URL `https://web.meldiq.app`. Redirect URIs: all three.
- Availability: private to this workspace. Turn on "Public" before other workspaces can connect.
- Client credentials grant: off. Scopes aren't set in the console; Meldiq asks for `read` at sign-in.
- **Webhook not set.** The Linear connector has no webhook handler yet (channel updates are planned), and
  `LINEAR_WEBHOOK_SECRET` is read but unused, so deliveries to `/v1/integrations/webhooks/linear` would be refused.
  Enable it when that handler exists.
- Server settings: `LINEAR_OAUTH_CLIENT_ID=6176399d2f31d5ea6572c253d8b413b6`, `LINEAR_OAUTH_CLIENT_SECRET_FILE`.

**Not yet registered: skipped for now at the owner's request (2026-10-20)**
- GitLab: a new gitlab.com account exists. Register an application under User settings › Applications: confidential,
  scopes `read_api` and `read_user`, three redirect URIs.
- Asana: the account is set up. Create an app in the developer console with scopes `users:read`, `tasks:read`,
  `projects:read` and three redirect URLs.
- Zoom: create a General app (user-managed) with scopes `user:read:user` and `meeting:read:meeting` only.

**Secret files still to save** (none of these were in `~/.meldiq-secrets/` at the end of the session; only
`fcm-service-account.json` and `google_oauth_client_secret` were):
- `github_app_client_secret`, `github_app_webhook_secret` (also pasted into the GitHub App's webhook settings)
- `notion_oauth_client_secret`
- `atlassian_oauth_client_secret`
- `figma_oauth_client_secret` (lost: recreate the Figma app or recover it)
- `linear_oauth_client_secret`
- `meta_app_secret`, `instagram_app_secret`, `meta_webhook_verify_token`
- The Google secret still needs rotating (see the Firebase/Google section above).

**Meta app "Meldiq"** (App ID `1937328653907781`)
- The owner accepted the Meta Platform Terms and Developer Policies. Contact email: the owner's Meta account email.
  Mode: in development (unpublished).
- **No business portfolio yet.** Linking GriipBuddy needed Meta's passkey (fingerprint) session check, which the
  Claude app's browser pane can't do. So the app was created without one, as Meta offers. The owner links it in Brave:
  Business Settings › Accounts › Apps › Add › Connect an app ID.
- Messenger use case:
  - permissions `pages_messaging`, `pages_manage_metadata`, `pages_show_list` (as in `docs/UNIFIED_INBOX.md`);
  - Meta also added `business_management` and `public_profile` by default.
- Instagram use case (API setup with Instagram login):
  - Instagram app "Meldiq-IG", ID `1607863423514741`, with its own secret (→ `INSTAGRAM_APP_SECRET`);
  - permissions `instagram_business_basic` and `instagram_business_manage_messages` only. `manage_comments` was left
    out.
- The owner reported linking GriipBuddy in Brave. Right after, the developer app list still showed Meldiq with no
  business, while GriipBuddy Automation shows "Business: GriipBuddy". Check Business Settings › Accounts › Apps.
- **WhatsApp: skipped for now** at the owner's request. Meta doesn't offer it at creation without a business
  portfolio. Add it after the GriipBuddy link, and ask the owner before any WhatsApp terms or anything billed.
- **Webhook: not set yet.**
  - The callback is `https://web.meldiq.app/v1/inbox/webhooks/meta`, with the verify token.
  - Meta checks the callback on save, but production answered 404 there on 2026-10-20: the inbox (0.8.0) isn't
    deployed.
  - After the deploy, with `META_WEBHOOK_VERIFY_TOKEN` set: enter the URL and token in Messenger API Settings and in
    Instagram's "Configure webhooks", then subscribe:
    - Messenger: `messages`, `message_deliveries`, `message_reads`;
    - Instagram: `messages`, `messaging_seen`.
  - Instagram webhooks also need the app published.
- Server settings: `META_APP_SECRET_FILE`, `INSTAGRAM_APP_SECRET_FILE`, `META_WEBHOOK_VERIFY_TOKEN_FILE`.

**How it was done**
- Brave can't be driven: the desktop-control tool only allows browsers in read-only mode. So the work used the Claude
  app's browser pane, where the owner signed in.
- Passwords, MFA codes and client secrets are never typed or copied by Claude. Claude fills in the non-secret settings
  (app names, redirect URIs, scopes, webhook URLs). The owner copies each secret into its file.

## 5. Owner actions, by priority

1. **Approve a production deploy** of rounds 7 and 8 (API 0.8.0): backup, migrations 022–028, then a reviewed
   `channel-access-repair`. AI, outgoing webhooks, the inbox and this register reach production only with it.
2. **Google**:
   - Sign in to Google Cloud in the browser pane.
   - Claude creates the OAuth client (Web), its redirect URI and the consent screen in Testing mode, with you as a test
     user.
   - You put the client id and secret in the `GOOGLE_OAUTH_*_FILE` files.
   - No cost.
3. **Meta**:
   - Sign in to Meta for Developers.
   - Claude creates a Business app with Messenger and WhatsApp, and a webhook pointing at
     `/v1/inbox/webhooks/meta`. The webhook can only be verified after the deploy in step 1.
   - You put the app secret and verify token in files.
   - WhatsApp's test number is free, but conversations beyond the free tier are billed by Meta, so they need your
     approval.
4. **GitHub App**:
   - Claude can register it through the browser, with you signed in to GitHub there.
   - Webhook secret and private key: the same rule applies (you copy them).
5. **Microsoft Entra, Atlassian, Notion, Linear, Figma, GitLab, Asana, Zoom**: the same pattern, one sign-in each.
6. **Firebase**:
   - Create a project for `com.meldiq.android`. It's free on the Spark plan.
   - Add the service account file to the server.
   - Add `google-services.json` to the build.
7. **AI**: approve a monthly budget and create one provider key. **Billed per request.**
8. **UnifiedPush**: apply `docs/PUSH_SERVER.md` steps 3–6 on production (needs approval).
9. **TikTok**: apply for Business Messaging access for an eligible region.

## 6. Costs that need approval

- AI provider usage: per request, set by `AI_MONTHLY_REQUESTS_PER_ORG`.
- WhatsApp conversations beyond Meta's free allowance.
- Google's security assessment for restricted Drive scopes before a public launch, done by a third-party assessor.
- MongoDB Atlas, if used beyond the free tier.

Google and Microsoft OAuth apps, the GitHub App, Firebase Spark, Linear, Notion, GitLab, Asana and Zoom developer apps
are free.

## 7. Safe plan for the rest

1. Deploy 0.8.0 after approval. The register then shows production's real state.
2. Register one provider at a time in the browser pane:
   - Put its secrets in `/opt/griip-internal-messaging/secrets/*` (chmod 600) and restart the API (approved change).
   - Connect a test account from the app, and confirm the register shows `working` from a validated connection.
3. For Meta:
   - Verify the webhook, connect a test Page or test number, and message it from the owner's own test account (never a
     real customer).
   - Confirm signed delivery, Sent → Delivered → Read, then apply for App Review.
4. Keep every planned connector as `planned` until it is built with tested capabilities.
