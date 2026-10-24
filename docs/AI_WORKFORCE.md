# Meldiq AI Workforce 1.0

Communities install AI agents, give them a name, an avatar and permissions, assign them to channels, and ask them for
work. Platform operators decide in the Platform Admin Console which providers, models, agents, tools and limits exist.
Both sides run on one runtime in the existing server (`server/src/modules/workforce`, `server/src/modules/ai`).

Progress, verification status and what's left: `AI_WORKFORCE_PROGRESS.md`.

## 1. What the audit found (2026-10-24)

| Area | What exists | Consequence |
|---|---|---|
| Database | SQLite (better-sqlite3) on one VPS; a few domains can live in MongoDB, production has none | New tables are SQLite, migrations 049 and 050, additive only |
| Messages and files | End-to-end encrypted: per-message AES-256-GCM key wrapped to every device with HPKE, signed with Ed25519; attachments are encrypted on the device and the server stores ciphertext | The server can't read channels or attachments, and agents run on the server (see §2) |
| AI | `modules/ai`: one gateway (kill switch, feature allowlist, model allowlist, caps, quota reservations, bounded retries, explicit fallback, usage rows with counts only), encrypted provider keys (`PLATFORM_SECRETS_KEY`), Anthropic/OpenAI/Google adapters for text only | Extended, not replaced: capabilities, routing, health, more adapters |
| Quotas | `modules/quotas`: counters, gauges, per-request ceilings, reservations settled or released, global/pool/plan/org/user scopes | AI Workforce adds three keys; enforcement reuses the engine |
| Audit | `PlatformAudit` (console, never pruned) and the community audit log (`ctx.audit`) | Console actions → platform audit; community actions → community audit |
| Jobs | No general queue. Retention runs on a timer; the retired integrations had a lease-based job table | A small lease-based SQLite job engine in the same style (§5) |
| Permissions | Community roles with a permission catalog, channel overwrites, bans, timeouts (`organizations/authz.ts`); console roles with deny-by-default permissions and passkey re-checks for sensitive actions | Three new community permissions, four new console permissions |
| Connectors | The app directory, OAuth connectors, webhooks and the old product AI were removed on 2026-10-21 | No external account can be reached: external actions are integration boundaries that report "not configured" (§7) |
| Media | No ffmpeg in the server image | Added to the Dockerfile; the server detects it at run time and the console shows its real state |
| Clients | Meldiq Web (React, hash routes, plain CSS design system); Android (Kotlin) | Web gets the AI Workforce area and the channel experience; Android doesn't (yet) |

## 2. Privacy model: agents never hold encryption keys

An agent is not a member device and is never given a message key. Content reaches an agent only when a person sends it
on purpose: the brief they type and the files they choose. Their browser decrypts those files and uploads them to the
job over HTTPS, after a notice saying the content leaves end-to-end encryption and goes to the AI provider the platform
has configured. The same consent model the earlier Meldiq Web AI used (`orbit_web/src/ui/ai/AiDialogs.tsx`).

**Sharing a channel's recent messages (2026-10-24).** From a channel, the Ask dialog can also send the channel's recent
messages with a request, for agents that read text (`definition.skill.readsMessages`). It's off until the person picks a
number (20, 50 or 150, never more than 150 messages or 30,000 characters).
- What's sent is exactly what the person's browser has decrypted when the dialog opens: each message's author's display
  name, text and time. Other people's messages are included; the notice says so before Send.
- The channel's request post adds "Shared the last N messages here with the agent (not end-to-end encrypted)", so every
  member sees it, and the audit entry records the count.
- The server keeps them in `ai_shared_messages` (migration 050) for the job only. Its skill gets them as an untrusted
  source (`channel-messages.txt`), and steps the Agent Manager delegates read them through the root job. No route
  returns them: the job shows only how many were shared. They're deleted after 7 days, even if the job is kept longer.
- No key leaves anyone's device, and the agent can't fetch more: only one person's explicit choice, per request.

What agents produce (replies, progress, deliverables) is stored on the server, **not end-to-end encrypted**, and every
client shows it with that label, like the integration notices the apps used to show. Access to it is checked on every
request: community membership, channel membership and the channel's view permission.

Reading a channel's history would need the server to hold a device key in the conversation, i.e. a member that
decrypts on the server. That changes Meldiq's core promise and is the owner's decision, not built here.
An opt-in design, with its trade-offs, is proposed in `AI_AGENT_CHANNEL_READ_PROPOSAL.md` (not built).

## 3. Architecture

```
Platform Admin Console ─ /admin/v1/ai/*  ──┐
                                           ▼
                      AI policies (platform settings, tool policies, catalog state, entitlements, routing)
                                           │
Meldiq Web ─ /v1/orgs/:orgId/ai/* ─▶ Workforce service ─▶ Job engine (SQLite, leases) ─▶ Skill runtime
                                                                        │                  │
                                                         Tool gateway ◀─┘                  ▼
                                              (permissions, grants, approvals,       Model gateway (modules/ai)
                                               quotas, audit, media, files, posts)   routing, health, usage
```

- **Agent definitions and skills are code** (`workforce/catalog`): an agent definition names one skill, its version,
  category, capabilities, tools, required integrations, model capabilities, input and output schemas (zod), default
  policies. Code is the only safe place for something executable. The database records each version's publication
  state (draft, published, disabled, retired) and the platform's baseline policy for it.
- **Installations are the community's own** (`ai_agent_installs`): name, avatar, description, role, language, style,
  instructions, granted tools, execution mode, limits, who may invoke it. Pinned to a skill version; a new template
  version never overwrites them, and an upgrade is the community's explicit action.
- **Channel assignments** (`ai_agent_channels`) are explicit; there's no "all channels" default. Activation: mention,
  command, or explicit only.

## 4. Model gateway

Capabilities: `text`, `vision`, `transcription`, `speech`, `image_generation`, `embeddings`. A provider adapter
supports a fixed set (Anthropic: text, vision; OpenAI: all six; Google: text, vision, transcription, image generation,
embeddings). That's what the adapter code can send, not a claim about any model.

Per model, an operator turns capabilities on, then **verifies** each with a real minimal request. Routing uses only
capabilities whose last verification passed. `auto` routing: the category's default model, then its fallbacks, then any
other compatible model ordered by the category's preference (cost from the operator's prices, latency from measured
p50, or the operator's rank); excluding disabled providers and models, models the community isn't entitled to, and
providers whose circuit is open (3 consecutive failures in 5 minutes). Every decision is stored with the usage row.

## 5. Jobs

States: `queued`, `planning`, `running`, `awaiting_input`, `awaiting_approval`, `completed`, `failed`, `cancelled`.
A worker in the server process claims due jobs with a lease it renews while working. A lease that runs out (crash,
restart) puts the job back in the queue until its attempts run out. Every job has a deadline, a bounded number of
attempts, an idempotency key per requester, a parent and root for delegated work, and a depth (at most 2). A job is
completed only after its deliverables were stored and read back with a matching SHA-256.

Safety rules for running jobs: pausing executions or lowering concurrency stops new claims and lets running steps finish;
disabling a provider, model, skill or tool, or the kill switch, makes the next call of a running job fail with a clear
error (it's retried later only if the cause is transient); cancelling aborts provider calls and ffmpeg at once.

## 6. Tools and approvals

Every agent action goes through the tool gateway, which checks in order: the platform tool policy, the community's
entitlement, the installation's grants, the skill's declared tools, the requester's current permissions (membership,
channel access, timeouts, bans), quotas, and approval. It runs at execution time, not when the job was created.

Approvals are bound to the action and to the exact artifact (id and SHA-256) or payload hash. A revision supersedes the
artifact and invalidates its approval. People with `ai.approve` approve; the person who asked may approve an internal
action of their own request (the final export of their video) only while neither the platform's strict policy nor the
agent's template forces review. Agents and the Agent Manager never approve, and the approver's right is checked again
when the approved action runs. A rejected approval, an operator's cancel or the agent's removal ends a job for good.

## 7. External actions

`social.publish`, `ads.manage`, `email.send`, `calendar.write`, `crm.write`, `support.reply_external`,
`repository.write`, `website.update`, `deploy.production` and `payments.execute` are declared tools with their own
policy and approval rule. No connector exists since 2026-10-21, so they refuse with `integration_not_configured`, and
the agents produce the draft instead (post copy, email draft, `.ics` file, patch). `payments.execute` is disabled by a
hard platform rule that the console can't change.

## 8. What each side can see

Operators see counts, states, durations, costs, error kinds and configuration through `ctx.workforce.console` only
(`modules/workforce/consoleView.ts`; `test/platform_admin_permissions.test.ts` fails if console code reaches anything
else). Job events hold kinds and numbers, never content: a revision note travels in the job's checkpoint, not its events.
A community's own people see their jobs and deliverables: who asked, people with `ai.manage`, and members of the job's
channel. Channel messages shared with a request are seen by nobody through the API, only by the job's skill and the AI
provider (§2); people see how many were shared.

## 9. Configuration

| Setting | Where | Default |
|---|---|---|
| `PLATFORM_SECRETS_KEY` | server environment | required to store provider keys |
| `AI_WORKFORCE_TICK_MS` | server environment | 1000 (how often the worker looks for due jobs; 0 turns the worker off) |
| `FFMPEG_PATH`, `FFPROBE_PATH`, `MEDIA_FONT_FILE` | server environment (optional) | `ffmpeg`, `ffprobe` on the path; DejaVu Sans if present |
| `ai.enabled` | console › Settings (kill switch) | off: nothing calls a provider |
| `ai.workforce.enabled` | console › AI Control Center › Overview | off: no community can install or run agents |
| `ai.workforce.paused` | console, or `node dist/admin.js ai-workforce-pause <reason>` | off |
| `ai.workforce.max_concurrency` | console › AI Control Center › Overview | 2 |
| `ai.workforce.approval_floor` | console › AI Control Center › Overview | `standard` (`strict` forces review everywhere) |
| Quotas `ai.jobs`, `ai.active_jobs`, `ai.media_seconds`, `ai.file_bytes` (and `ai.requests`, `ai.tokens`, `ai.cost_micro`) | console › Resources & Quotas | none set: nothing limited |
| ffmpeg and a font | server image (`apk add ffmpeg font-dejavu`) | detected at start, shown in the console |

To make an agent work: add a provider and its key, approve models, turn on and **verify** their capabilities (Models &
routing), switch AI and AI Workforce on, publish the agent. A community admin then installs it and assigns channels.

## 10. The catalog

Every agent has its own skill (`workforce/catalog/skills`), input and output schema, tools, model needs, limits and
documented error behaviour. Deterministic code does what a model mustn't be trusted with; the model only does language.

| Agent | Skill | What the code checks or computes | Needs (besides a text model) |
|---|---|---|---|
| Agent Manager | `manager.orchestrate` | plan uses only agents the requester may use, dependencies only on earlier steps (no cycles), one level of delegation, reports from job records | other installed agents |
| General Assistant | `general.assist` | — | — |
| Research and Knowledge | `research.report` | passage ranking (BM25); every citation's quote must appear verbatim in its source or it's dropped and counted | documents, or `web.fetch` allowed |
| Coding and Engineering | `coding.engineer` | a proposed patch must apply hunk by hunk to the files sent | source files; repository writes need a connection (none) |
| QA and Testing | `qa.testing` | numbered cases; bug-report evidence must be real failure lines of the logs | logs for bug reports |
| Project Manager | `project.plan` | unknown dependencies and cycles removed and listed; schedule and critical path computed | — |
| Content Writer | `content.write` | word count against the requested length, one rewrite | — |
| Graphic and Image Designer | `design.image` | generated files must be PNG/JPEG/WebP | image-generation model (else: brief and prompts only, said so) |
| Product Video Editor | `video.edit` | real durations from ffprobe; clips clamped to the footage (fallback plan otherwise); captions moved onto the output timeline; ffmpeg arguments built by the server; preview → approval of that exact file → final | ffmpeg; speech-to-text for captions (optional) |
| Social Media Manager | `social.plan` | character and hashtag limits per platform, one rewrite, then flagged | publishing needs a connection (none) |
| Performance Marketing | `marketing.performance` | CTR, CPC, CPA, ROAS, flags computed from the CSV | ad export CSV; ad changes need a connection (none) |
| SEO and Website Growth | `seo.audit` | on-page checks (title, description, h1, headings, alt text, noindex, viewport, canonical, lang, word count) | HTML files, or `web.fetch` allowed |
| E-commerce Operations | `ecommerce.ops` | stock status and stock value from the CSV | product CSV for inventory |
| Customer Support | `support.assist` | knowledge-base retrieval; quotes verified; escalation when unconfirmed | knowledge-base files; sending needs a connection (none) |
| Sales and CRM | `sales.crm` | pipeline counts and amounts by stage and owner | CRM CSV for summaries |
| Data and Analytics | `data.analyze` | column types and statistics; the model may only pick an aggregation over real columns, computed in code | CSV |
| HR and Workplace Operations | `hr.ops` | shift scheduler respecting availability, hours and overlaps; gaps listed | — |
| Finance and Operations Analyst | `finance.analyze` | totals by month and category; duplicates, robust outliers, weekend and round payments; payments impossible | transactions CSV |
| Meeting and Documentation | `meeting.docs` | — | transcripts, or recordings and speech-to-text |
| Email and Calendar | `email.calendar` | `.ics` with a validated start and duration | sending and calendar changes need a connection (none) |
| Translation and Localization | `translation.localize` | subtitle cues and timings kept exactly; code, links and placeholders must survive | — |

## 11. API (implemented)

Communities (`modules/workforce/routes.ts`, request shapes in `shared/contracts/http/aiWorkforce.ts`):

| Route | Who | |
|---|---|---|
| `GET /v1/orgs/:orgId/ai` | members | switches, media engine, your permissions, installed agents, the notice |
| `GET /v1/orgs/:orgId/ai/catalog` | members | published agents, availability here, what's missing to work |
| `POST /v1/orgs/:orgId/ai/agents` | `ai.manage` | install (name checked against members and reserved words) |
| `GET /v1/orgs/:orgId/ai/agents/:installId` | members | settings, definition, grantable tools, channels, update available |
| `PATCH /v1/orgs/:orgId/ai/agents/:installId` | `ai.manage` | identity, behaviour, tools, mode, limits, invokers, on/off, upgrade (`rev` required) |
| `PUT` / `GET /v1/orgs/:orgId/ai/agents/:installId/avatar` | `ai.manage` / members | PNG, JPEG or WebP up to 512 KB |
| `DELETE /v1/orgs/:orgId/ai/agents/:installId` | `ai.manage` | uninstall (cancels its jobs, removes channels) |
| `PUT` / `DELETE /v1/orgs/:orgId/ai/agents/:installId/channels/:conversationId` | `ai.manage` | assign (`mention`, `command`, `explicit`) or remove |
| `POST /v1/orgs/:orgId/ai/files?name=&type=` | `ai.use` | upload an input (streamed, type sniffed, 500 MB, read back) |
| `POST /v1/orgs/:orgId/ai/jobs` | `ai.use` (+ channel and invokers) | create a job; `Idempotency-Key` or `idempotencyKey`; `channelMessages` only with a channel and an agent that reads text (§2) |
| `GET /v1/orgs/:orgId/ai/jobs?scope=mine\|all` · `GET …/jobs/:jobId` | requester, channel members, `ai.manage` | list (root jobs) and detail |
| `POST …/jobs/:jobId/cancel` · `/retry` · `/revise` · `/answer` | requester or `ai.manage` | steer a job |
| `GET /v1/orgs/:orgId/ai/approvals` · `POST …/approvals/:approvalId` | `ai.approve` (the requester for internal actions of their own job) | approve or reject exactly one file version |
| `GET /v1/orgs/:orgId/ai/deliverables` · `GET …/files/:fileId` | who may read the file | download (range requests, `nosniff`, sandboxed CSP, inline only for safe types) |
| `GET /v1/conversations/:id/ai/agents` · `GET …/ai/posts` | channel members | agents in the channel, your permissions; agent posts (not end-to-end encrypted) |

Realtime frames: `ai.posts { conversationId }` and `ai.job { orgId, jobId }` (ids only; the fetch decides access).
Console routes: `PLATFORM_ADMIN_CONSOLE.md` › AI Control Center routes.

## 12. Security controls

- Tenant isolation: every route resolves the community from the record and checks membership; unknown and invisible
  answer the same 404. Files are readable only within their request tree and by the people above.
- Least privilege at run time: each tool call re-checks the platform policy, the entitlement, the installation's
  grants, the skill's tools, the requester's membership, ban, timeout and `ai.use`, the channel and the assignment.
- The Agent Manager delegates as the requester, only to agents they may use, never to a coordinator, one level deep,
  at most 12 jobs per request.
- Approvals are bound to the action and the file's SHA-256; revisions supersede the file and void the approval; the
  approver's right is checked again when the action runs.
- No arbitrary execution: ffmpeg runs with argument arrays the server builds from a checked plan (text goes through
  files, never into filter strings); `web.fetch` is off by default, https and port 443 only, public addresses only, the
  connection pinned to the checked address, redirects re-checked, 2 MB and 15 s.
- Prompt injection: everything from people, files and tools is wrapped as untrusted data; models get no tools of their
  own, only the skill's code calls tools; every model answer that drives code is validated with a schema.
- Credentials: AES-256-GCM sealed provider keys, never returned or logged; the gateway's base URLs are fixed.
- Quotas are checked and charged in one synchronous transaction with the job insert (parallel requests can't both
  pass a limit; `ai_workforce_runtime.test.ts` fires six at once); retries are admitted the same way.
- Uploads: at most 3 at once and 2 GB of unsent inputs per person even without quotas; an `ai.file_bytes` quota is
  reserved for the whole upload. Files are hashed and read back as streams; renders go from disk to the store; a skill
  can read at most 50 MB into memory (media goes through the media engine).
- Asking in a channel needs the right to post there; agents post there only while the requester still may.
- Names: an agent can't use a member's name or a staff/system word; every client shows its AI badge.

## 13. Tests

`server/test/ai_workforce_acceptance.test.ts` (the brief's scenario end to end, real ffmpeg, fake provider),
`ai_workforce_runtime.test.ts` (console controls reaching the runtime, the CLI pause, quotas and races, crash
recovery, cancel and retry, provider failures, isolation, roles, revocation, uploads, the Agent Manager),
`ai_workforce_skills.test.ts` (each of the 21 agents end to end, and the deterministic checks),
`ai_workforce_shared_messages.test.ts` (shared channel messages: prompt, notice, never returned, delegation, limits,
expiry), `ai_gateway.test.ts`
(adapters, routing, fallback, circuit breaker, cost ceilings, kill switch, quotas, the address guard). Console:
`e2e/ai.spec.ts` (Chromium). Web: `test/agent-rules.test.ts`, `e2e/agents.spec.ts` (Chromium).

The providers in these tests are fakes: they prove the runtime around a provider, not any provider's behaviour. No
real provider has been verified (no key on the development machine); `AI_WORKFORCE_PROGRESS.md` lists what's left.

## 14. Deploying and rolling back

1. Follow `DEPLOYMENT.md` (backup first; compare the VPS sources with this checkout, other sessions deploy too).
2. Build the image (it now installs `ffmpeg` and `font-dejavu`); migrations 049 and 050 run at start (additive only).
3. Deploy the console and Meldiq Web builds.
4. In the console: providers and keys, models, capabilities, verify, routing; then switch AI and AI Workforce on and
   publish agents. Nothing changes for communities before that.

Rollback: switch AI Workforce off (console or `ai-workforce-pause`), then roll back the image. Migrations 049 and 050 only add
tables and nullable columns; an older server ignores them. The blob store's `ai-files/` folder holds AI files; delete
it only after deciding to drop that data.

## 15. Known limitations

- Agents can't read channel history (§2). Android doesn't show agent posts or the AI agents area yet.
- External actions (social, ads, email, calendar, CRM, help desk, repositories, websites, deployments) have no
  connectors: those tools refuse with `integration_not_configured`.
- Speech: OpenAI only; Anthropic adapters do text and vision only. Gemini transcription timings are model estimates.
- No PDF reading (no text extraction on the server).
- The worker runs in the API process; one server process (SQLite) is the supported setup.
- Local ffmpeg (Homebrew 9.0.2) lacks `subtitles` and `drawtext`: captions become a subtitle track and the title is
  metadata only. Whether Alpine's ffmpeg in the image has them is checked at start and shown in the console.
