# Meldiq platform expansion plan (2026-10-20): cloud, search, workflows, documents, APIs, AI, metering

The owner's "cloud architecture, intelligent workspace & SaaS expansion" brief (14 phases), checked against this
worktree (internal-beta `35aa4b5` + uncommitted round 7: slice A and the call/channel bug fixes, both awaiting the
phone test). Nothing in this file is implemented yet.

## 1. Architecture today

```
 Android (Kotlin/Compose)        Web (React/Vite)            ── E2EE clients: keys, decrypted history (SQLCipher on
   │  HTTPS + WebSocket            │                             Android), message search on the device
   ▼                               ▼
 host nginx (TLS, msg.griipbuddy.cloud) ── Hostinger VPS 72.60.107.88 (2 vCPU, shared with other projects)
   │
   ├─ Meldiq API 0.7.1 (Node/Fastify, one container): auth, orgs/roles, relay queue, encrypted history,
   │    reminders (sealed), calls, integrations, admin CLI, background jobs in-process
   │      ├─ SQLite (docker volume): canonical store for everything except the MongoDB domains
   │      ├─ MongoDB (accounts, preferences, integrations domains when configured)
   │      ├─ AWS S3 ap-southeast-1: encrypted attachments; object-locked backup bucket (tooling: offsite-backup.sh)
   │      ├─ Hostinger SMTP (account email) · ntfy (UnifiedPush wake-ups) · FCM (code ready, no Firebase project)
   │      └─ connector APIs (GitHub, GitLab, Figma, Jira, Linear, Google, Microsoft, Notion, Asana, Zoom)
   └─ LiveKit SFU (separate container, UDP 7882 / TCP 7881; no TURN)
```

Trust boundaries: the server sees metadata and ciphertext for messages, files and reminders; it sees plaintext for
org structure, membership, roles, channel names, integration data and account records.

**Single points of failure:** one VPS (API, database, SFU, nginx); SQLite on one volume; in-process jobs. Backups:
manual pre-deploy snapshots on the VPS; encrypted off-site tooling exists, its schedule on the VPS is unverified.
**Scaling limit:** one API process and one SQLite writer; LiveKit on 2 shared vCPUs (12-person rooms, untested at
load). None of this can honestly be called highly available.

## 2. The constraint that shapes this brief: end-to-end encryption

Messages, files, reminders and call media are end-to-end encrypted. The server can't search, summarise or automate
on their content, and the brief rightly forbids a master key. So, per feature:

| Feature | Can use | Can't use |
|---|---|---|
| Universal search | server: people, channels, workspaces, documents (if server-readable), workflows, connector metadata; device: messages, files, pins (already local) | server-side message search |
| Workflows | events the server sees: invitation accepted, member/role changes, channel created, reminder due (not its text), connector webhooks, call started; later tasks/shifts | "message contains…" triggers |
| AI | content the user selects on the device and agrees to send; server-readable documents | background summaries of E2EE channels |
| Documents | a design choice for the owner (below) | — |

## 3. Brief vs code

| Phase | Area | Status | Notes |
|---|---|---|---|
| 1 | Architecture audit | Done (this file) | |
| 1.5 | Infrastructure as code | Partly | CloudFormation for S3, mail, backups, ops role (`deploy/aws`); Compose + scripts (`deploy/scripts`: preflight, validate-env, deploy, verify, restore) |
| 2.1 | Health dashboard | New | `/healthz` only. No request metrics, job/queue view, push/SMTP/connector error counts |
| 2.2 | Structured logs | Partly | pino JSON logs with request ids; no job or provider correlation |
| 2.3 | Backups | Partly | off-site encrypted, object-locked tooling exists; schedule, monitoring and a restore drill to verify |
| 2.4 | DR runbook | Partly | rollback images + backups per deploy; no written DR runbook covering VPS loss |
| 2.5 | Cost view | New | AWS cost data readable through the ops role; nothing collected |
| 3 | Universal search | New (server) / Have (device messages) | no `/v1/search` |
| 4 | Workflow engine | Partly | connector "post there" routing only |
| 5 | Documents | New | |
| 6.1 | Workspace templates | New | |
| 6.2 | User groups | New | teams exist (membership-bearing); groups for mentions/recipients don't |
| 6.3 | Guests | Partly | GUEST role exists; no expiry, per-guest channel scope UI, or guest policy |
| 6.4 | Scheduled meetings | New | was slice B (conference channels) |
| 6.5 | Org dashboard | Partly | workspace Home (unread, voice rooms, setup card) |
| 7 | API keys, outgoing webhooks | New | incoming webhooks exist (HMAC, replay window) |
| 8 | AI | New | needs a provider, a key and spending approval |
| 9 | Entitlements, metering | Partly | `entitlements.ts` (4 keys, unlimited); no usage counters |
| 10 | Platform admin | Partly | `PLATFORM_ADMIN_USER_IDS` for integrations policy; admin CLI; no admin UI for health/orgs |
| 11–14 | Tests, phone gate, release, report | per slice | |

**Owner's decisions (2026-10-20):** build the next slice now and phone-test everything together; C1 Operations first;
documents server-readable and labelled as not end-to-end encrypted; AI built with a provider abstraction but off until
a key and budget are approved.

## 4. Proposed order (each slice: tests, APK, phone gate, push, deploy)

| Slice | Contents | Needs from the owner |
|---|---|---|
| **C1. Operations** | in-process metrics (requests, latency, errors by route group; job runs and failures; push, SMTP, connector failures; WebSocket connections), platform-admin health API and a web/app view with "unavailable" where a value isn't measured; job/provider correlation ids; DR runbook; verify off-site backup schedule and run a restore drill on a non-production copy | approval to check/enable the backup timer on the VPS |
| **C2. Search** | `/v1/search` over server-readable content with the same authorisation as each resource, cursor pagination; app search screen merging it with on-device message search | — |
| **C3. Workflows** | trigger › conditions › actions model, durable jobs, idempotency, loop guard, per-org limits, run history, templates; triggers and actions limited to real server events | — |
| **C4. Documents** | pages, versions, comments, permissions, search | the encryption decision below |
| **C5. Workspace** | templates, user groups, guest expiry and policy, scheduled meetings (with conference channels) | — |
| **C6. Developer platform** | org-scoped API keys with scopes, expiry, rotation, audit; outgoing signed webhooks with retries and history; API guide | — |
| **C7. Metering** | usage counters (seats, bytes, transfer, media minutes, jobs, email/push, API, AI) and entitlement enforcement | — |
| **C8. AI** | server orchestration with a provider abstraction, off by default, per-org opt-in; client-side "summarise what I selected" and writing help | provider choice, API key, budget |

## 5. Slice C1 (Operations): built 2026-10-20, awaiting the phone gate

| Item | Status | Where |
|---|---|---|
| In-process metrics: requests by route template (counts, 4xx/5xx, latency buckets, 15-minute window), jobs (cleanup, reminder ticks, app jobs), outside services (mail, UnifiedPush, FCM) | Implemented, server-tested | `server/src/ops/metrics.ts`, hooks in `app.ts`, `jobs.ts`, `reminders/service.ts`, `integrations/index.ts`, `push/*`, `accounts/mailer.ts` |
| Platform-admin health API with measured checks, queues, warnings; "not measured" instead of invented numbers | Implemented, server-tested | `GET /v1/admin/health`, `server/src/ops/health.ts`, `routes.ts`; `test/ops_health.test.ts`, `test/ops_metrics.test.ts` |
| Service health screen (Settings, platform admins only) | Implemented, compiles; phone test pending | `ui/screens/profile/ServiceHealthScreen.kt` |
| Backup schedule check | Done (read-only): **no scheduled or off-site backups exist**; no backup bucket | `DISASTER_RECOVERY.md` §1 |
| Restore drill | Done on non-production data (identical row counts, restored server answered); production not drilled | `DISASTER_RECOVERY.md` §1 |
| DR runbook | Written | `DISASTER_RECOVERY.md`, `OPERATIONS.md` |
| Off-site backups switched on | **Awaiting owner**: age public key, approval for the backup bucket (cents a month) and a VPS timer | `DISASTER_RECOVERY.md` §2 |
| Cost view, alerting, LiveKit room counts, web view | Deferred | `OPERATIONS.md` › Not built yet |

Checks: server `tsc` clean, `npm test` 64 files **610 passed** / 10 skipped, `npm audit` 0 vulnerabilities; Android
`testDebugUnitTest` **490 passed**; web (unchanged) `tsc -b` clean, `npm test` 187 passed, 1 file failed to load
(`call-live.test.ts` imports the server from the main checkout, which has no `node_modules`: environment, not code).

## 6. Slice C2 (Search): built 2026-10-20, awaiting the phone gate

`GET /v1/search` (people, channels in every workspace you're in, workspaces, groups; each with its resource's own
access rule; ranked; cursor paging; `search` feature) and the app's search screen (filters All / Messages / Files /
Pinned / People / Channels / Workspaces, show more, recent searches on the phone, file-name and pinned search in the
phone's history, fallback for older servers). Details: `SEARCH.md`. Tests: `server/test/search.test.ts` (8),
`SearchRulesTest` (3). Checks: server 66 files **618 passed**; Android **493 passed**. Phone test pending.

## 7. Slice C3 (Workflows): built 2026-10-20, awaiting the phone gate

Trigger › conditions › actions engine on real server events (member joined / left / role changed, channel created,
schedule), actions post a Workflows update, add the member to channels, email admins; durable runs unique per event,
leased, per-action outcomes saved so retries never repeat an action, bounded retries, per-workspace limits, loop guard,
dry run, cancel, audit; `workflows.manage` permission; app list and editor with templates and run history. Details:
`WORKFLOWS.md`. Tests: `server/test/workflows.test.ts` (10), `WorkflowRulesTest` (3). Checks: server 67 files
**628 passed**; Android **496 passed**. Phone test pending.

## 8. Slice C4 (Documents): built 2026-10-20, awaiting the phone gate

Server-readable workspace documents (labelled as not end-to-end encrypted): workspace / channel / restricted access with
per-person roles (view, comment, edit, admin), 404 for anyone without a role, versions (50 kept) with restore, stale-save
409, comments with resolve, archive and delete, Markdown export, templates, and documents in `/v1/search` with
excerpts. App: Workspace › Documents list (active / archived), document view with a Markdown subset, editor, comments,
versions, sharing, share as text; Documents filter in search. Details: `DOCUMENTS.md`. Tests:
`server/test/documents.test.ts` (8), `DocRulesTest` (3). Checks: server 68 files **636 passed**; Android **499 passed**.
Phone test pending.

## 9. Slice C5 (Workspace): built 2026-10-20, awaiting the phone gate

Workspace templates (six; add only what's missing, plus a welcome document), user groups (grant nothing by themselves;
add current members to channels or meetings, checked one by one; `groups.manage`), guest end dates (from the invitation
or set by an admin; expired guests leave through the same path as a removal) and a guest policy (allow, maximum days),
scheduled meetings in voice rooms (visibility, invitees must reach the room, responses, edit / cancel with stale check,
"starting soon" ten minutes ahead, add to the phone's calendar). Details: `WORKSPACE_C5.md`. Tests:
`server/test/workspace_c5.test.ts` (11), `MeetingRulesTest` (3). Checks: server 69 files **647 passed**; Android
**502 passed**. Phone test pending.

## 10. Slice C6 (Developer platform): built 2026-10-20, awaiting the phone gate

Workspace API keys (scopes, expiry, one-time token with only a hash stored, rotate, revoke, last used, 120/min per key,
creator must keep `developers.manage`), a small external API at `/ext/v1` (workspace, members, public channels,
workspace documents, meetings in non-private rooms, posting updates into public channels with idempotency), and
outgoing webhooks (public-https addresses pinned per send, HMAC-SHA256 signatures over timestamp and body, stable
delivery ids, backoff retries, pausing after 20 failures, 7-day history, sealed and rotatable secrets; events without
private data). App: Workspace › API keys & webhooks. Guide: `DEVELOPER_API.md`. Tests: `server/test/developer.test.ts`
(11). Checks: server 70 files **658 passed**; Android **502 passed**. Phone test pending.

## 11. Slice C7 (Metering and plan limits): built 2026-10-20, awaiting the phone gate

Daily usage per workspace (API requests, webhook deliveries, workflow runs and emails, file bytes up and down; members,
active members, stored bytes and call minutes as recomputed gauges), shown on the Usage screen and to platform admins
across workspaces; plan limits for members, storage, workflows, documents, groups, API keys and webhooks (server-wide or
per workspace, the latter set only by platform admins and audited), enforced on the server; billing boundary designed,
not built. Details: `USAGE_AND_PLANS.md`. Tests: `server/test/usage.test.ts` (8). Checks: server 71 files
**666 passed**; Android **502 passed**. Phone test pending.

## 12. Slice C8 (AI): built 2026-10-20, off, awaiting the phone gate

Server-side AI with three providers (owner, 2026-10-20: "Keep Gemini, ChatGPT, Claude"): Claude (Anthropic), ChatGPT
(OpenAI) and Gemini (Google). The operator configures any of them (`AI_PROVIDERS` + per-provider key and model) and
each workspace's admins switch AI on and pick one. Off by default everywhere: no provider, key or budget is set up.
Every request needs the person's consent (the "Send to …" press, naming the provider), passes rate, monthly and size
limits, and is metered as counts only; no prompt or answer is stored or logged. Features: writing help, channel
catch-up (messages decrypted on the phone), document summary, ask your documents (permission-aware, cited, no call
when nothing matches) and workflow drafts (validated, off, never saved). Migration `027_ai.sql` (orgs.ai_enabled,
ai_provider). Details: `AI.md`. Tests: `server/test/ai.test.ts` (17), Android `AiRulesTest` (5). Checks: server 72
files **683 passed**; Android **507 passed**. Needs the owner: a provider, key and monthly budget. Phone test pending.

## 13. Round 8 (Unified Inbox): built 2026-10-20, not connected, awaiting the phone gate

Customer conversations from connected business accounts (owner's Unified Inbox prompt, 2026-10-20). Messenger,
Instagram and WhatsApp built on Meta's documented APIs (token connection checked with Meta, signed webhooks stored
sealed before acknowledging, outbox with retries, forward-only delivery states, 24 h / Human Agent / template window
rules); TikTok is "awaiting access" (TikTok must approve the app; not offered in the US, EEA, Switzerland or UK).
Agents, queues, notes, assignment with revisions, AI drafts / summaries / triage (consent, no notes sent), opt-in
automatic replies from approved documents with escalation and compare-and-set handoff, workflow triggers, reporting,
export and erase, payload-free notices. Migration `028_inbox.sql`; permissions `inbox.view`, `inbox.reply`,
`inbox.manage`; role template Support Agent. Details: `UNIFIED_INBOX.md` (capability matrix, setup, limits). Tests:
`server/test/inbox.test.ts` (20), Android `InboxRulesTest` (6). Checks: server 73 files **703 passed**; Android
**513 passed**. Web screens not built (separate repository). Needs the owner: a Meta app, secrets, a test number or
Page, app review; TikTok access. Phone test pending.

## 14. Phone acceptance (2026-10-20)

On the HONOR phone, Meldiq Test (com.meldiq.android.test) against the local server and a local fake Meta:

- **Unified Inbox:** end to end.
- **C1–C7:** service health, search, workflows (including the new inbox trigger), documents, groups, meetings, API
  keys, usage.
- **C8:** correctly hidden without a provider.
- **Slice A:** composer buttons step aside while typing, pinning for everyone, profile cards. A text channel offers
  no calls.

There were no crashes. Nine findings were fixed and retested on the phone (`UNIFIED_INBOX.md` §15). Checks: server
**705 passed**, Android **514 passed**.

**Not covered:**
- the call-quality fixes (voice, camera, screen share, loudness), which need the owner's own call with mic and camera
  permissions;
- saving several files at once;
- real Meta accounts;
- AI with a real provider.
