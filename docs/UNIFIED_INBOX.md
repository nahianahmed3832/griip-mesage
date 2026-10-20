# Unified Inbox (round 8, 2026-10-20)

Customer conversations from a workspace's connected business accounts, worked by its agents in Meldiq. The server
code, migration and Android app are built and tested, but nothing is connected to a real business account yet.

- **Facebook Messenger, Instagram, WhatsApp Business:** built against Meta's documented APIs, but not yet tested
  against Meta itself.
- **TikTok:** stays **awaiting access**.
- **Production:** nothing is deployed.

## 1. What the audit found

- **Reused:**
  - the integrations keyring (`INTEGRATIONS_TOKEN_KEY`, AES-256-GCM sealing bound to each row);
  - the raw-body webhook pattern;
  - custom roles and permissions, the audit log, usage metering and plan limits;
  - the C8 AI layer (`ctx.ai`, consent, limits, counts only);
  - the workflow engine (new triggers);
  - the realtime hub and payload-free push wake-ups;
  - documents (as approved knowledge).
- **New:** the existing integrations framework connects *tools* (GitHub, Drive). Customer messaging is its own
  domain, so it lives in `server/src/inbox/`, with its own tables in the same database (migration `028_inbox.sql`).
  It isn't a second messaging database: Meldiq's own messages stay end-to-end encrypted and untouched.
- **Encryption:** customer messages **cannot** be end-to-end encrypted, because Meta delivers them to the server.
  Meldiq seals message bodies, tokens and raw webhook payloads at rest. Both apps say plainly that these messages
  aren't end-to-end encrypted.
- **Web app:** `/Volumes/code_files/orbit_web` is a separate repository outside this worktree. Its inbox screens are a
  follow-up, built on the same API (section 12).

## 2. Platform capability matrix

| | Messenger | Instagram | WhatsApp Business | TikTok |
| --- | --- | --- | --- | --- |
| State in Meldiq | Built, needs Meta setup | Built, needs Meta setup | Built, needs Meta setup | **Awaiting access** |
| Official API | Messenger Platform (Send API, `/{page-id}/messages`) | Instagram API with Instagram Login (`graph.instagram.com/{ig-id}/messages`) | WhatsApp Cloud API (`/{phone-number-id}/messages`) | Business Messaging API (TikTok API for Business) |
| Account | A Facebook Page | An Instagram **professional** account (not personal) | A Cloud API phone number and its business account (not a personal WhatsApp) | A TikTok Business Account, not in the US, EEA, Switzerland or UK |
| Authorization | Page access token | Instagram user token (60 days, refreshed by Meldiq in its last week) | System user token | OAuth via TikTok's developer app, after TikTok approves the app |
| Permissions | `pages_messaging`, `pages_manage_metadata`, `pages_show_list` | `instagram_business_basic`, `instagram_business_manage_messages` | `whatsapp_business_messaging`, `whatsapp_business_management` | TikTok-granted Business Messaging scopes |
| App review | Advanced Access for Pages you don't own | Advanced Access for accounts you don't own | Business verification for higher limits | TikTok's approval before any call |
| Inbound | Text, image, video, audio, file, sticker | Text, image, video, audio, file, shares | Text, image, video, audio, document, sticker (location shows as unsupported) | n/a until access |
| Outbound in Meldiq | Text | Text (max 1000 bytes) | Text, approved templates | None |
| Reply window | 24 h; 7 days for people with the **Human Agent** tag (needs Meta's approval, `META_HUMAN_AGENT_TAG=on`) | Same as Messenger | 24 h after the customer's last message, then **templates only** | 48 h, user-started threads only |
| Delivery states | Delivered, read | Read | Sent, delivered, read, failed | n/a |
| Webhooks | `messages`, `message_deliveries`, `message_reads` | `messages`, `messaging_seen` | `messages` (messages and statuses) | `DIRECT_MESSAGE` |
| Signature | `X-Hub-Signature-256` (HMAC-SHA256, app secret) | Same (Instagram app secret) | Same | Signed with the app secret (header not verified from TikTok's own pages) |
| Not built yet | Outbound media, quick replies, reactions | Outbound media, story replies | Outbound media, interactive messages, template variables | Everything until access |

**Sources checked on 2026-10-20:**
- Meta Graph API changelog: v25.0 and v26.0 are current; Meldiq defaults to `META_GRAPH_VERSION=v25.0`.
- Meta's Instagram messaging guide.
- Meta's webhook setup guide.
- TikTok's Business Messaging pages; their details render only in a browser, so the access, regional and window rules
  above come from Chatwoot's and Infobip's integration guides.

**Re-check every row against the providers' current documentation before going live:** limits and approvals change.

## 3. Connecting accounts (inbox admins)

Workspace → **Customer inbox** → settings icon → **Platforms**.

- **What each platform shows:** its state, what the server still needs (setting names only), and the webhook URL.
- **Connecting:** an inbox admin pastes the token Meta issued for that business account (never a password), with:
  - the Page id for Messenger;
  - nothing extra for Instagram;
  - the phone number id and business account id for WhatsApp.
- **Checks:** Meldiq checks the token with Meta, uses Meta's own account name, subscribes the app to the account's
  webhooks, and stores the token sealed. The token is never shown again.
- **One workspace per account:** an account connected to one workspace can't be connected to another until it's
  disconnected.
- **Disconnecting** erases the token. Conversations stay until erased or removed by retention.
- **Health:**
  - A refused token marks the account **Needs reconnecting**.
  - The inbox admins get a notice, and the `inbox.connection_failed` workflow trigger fires.
  - **Check connection** re-tests the account.

Meta's Facebook Login for Business and WhatsApp Embedded Signup flows are the next step. They need a live Meta app
with app review, so for now accounts connect with tokens issued in Meta's Business Manager, which Meta documents for
these APIs.

## 4. Server setup (operator, owner approval needed)

| Setting | What it is |
| --- | --- |
| `INTEGRATIONS_TOKEN_KEY` | Already used for app tokens; also seals inbox tokens and messages. |
| `META_APP_SECRET` (or `_FILE`) | The Meta app's secret: signs Messenger and WhatsApp webhooks. |
| `INSTAGRAM_APP_SECRET` (or `_FILE`) | The Instagram app secret, if different (defaults to the Meta app secret). |
| `META_WEBHOOK_VERIFY_TOKEN` (or `_FILE`) | A random string, entered in Meta's webhook settings too. |
| `META_GRAPH_VERSION` | Default `v25.0`. |
| `META_HUMAN_AGENT_TAG` | `on` only after Meta approves the Human Agent feature. |
| `INBOX_TICK_MS` | Worker interval, default 3000 (0: off). |
| `TIKTOK_APP_ID` | Shown only as "setup started"; TikTok stays awaiting access whatever is set. |

In Meta's app dashboard, set the webhook callback to `https://<PUBLIC_API_URL>/v1/inbox/webhooks/meta` with the verify
token. Then subscribe the products' fields from section 2.

**Owner actions still needed:**
1. Create or choose the Meta app (developer account, business verification).
2. Add the Messenger, Instagram and WhatsApp products.
3. Get a WhatsApp test number. Meta offers one for development, with up to five test recipients.
4. Approve the secrets going into production `.env`.
5. Request app review for Advanced Access (and Human Agent if wanted).
6. Apply for TikTok Business Messaging access.

## 5. Data and storage

Tables in `028_inbox.sql`:

| Table | Holds |
| --- | --- |
| `inbox_accounts` | Connected accounts; token sealed; a live key enforces one workspace per account. |
| `inbox_contacts` | One per account and customer; only the name the provider gave; **never merged across accounts or platforms**. |
| `inbox_conversations` | Status, priority, assignee, queue, handling, tags, revision, times. |
| `inbox_messages` | Customer messages, replies and notes; body sealed; attachment references sealed. |
| `inbox_events` | Every verified webhook delivery, sealed. |
| `inbox_queues` | Team queues and their members. |
| `inbox_settings` | Each workspace's inbox policy. |

Every external message keeps its provider, account, provider message id and provider time.

Attachments stay at Meta. Meldiq fetches one only when an agent opens it, and only from Meta's media hosts. It isn't
copied to S3.

All tables are mapped to the `workspace` domain for the MongoDB migration catalog.

## 6. Reliability

- **Intake:**
  - Each webhook is verified (HMAC over the raw body, constant-time compare), sealed and stored *before* Meta gets its
    200. A worker or provider outage never loses a message.
  - Meta's redeliveries have the same hash and are stored once.
- **Processing:**
  - Processing retries with backoff (2 s up to 1 h, 8 attempts).
  - A message stored twice is ignored (unique per conversation and provider id).
  - Times only move forward when deliveries arrive out of order.
- **Replies:**
  - Replies wait in an outbox.
  - Passing failures (busy, 5xx, timeouts) retry up to 5 times. A refused token fails the reply and marks the account.
  - The window is checked again at send time.
- **Delivery states:** a reply becomes delivered or read **only** when the provider says so, and never moves backwards.

## 7. Agents and permissions

- **Permissions** (Settings → Roles):
  - `inbox.view` (see conversations, notes, assign);
  - `inbox.reply` (write to customers);
  - `inbox.manage` (accounts, queues, AI, reports, erasing a customer's data).
- **Defaults:** owners and admins have all three. The new role template **Support Agent** grants view and reply.
  Members get none by default.
- **Views:** Open, Unassigned, Mine, Waiting for us, Waiting for customer, AI, Resolved. Plus platform and queue filters,
  and search over customer names, WhatsApp numbers and tags. Message bodies are sealed, so they aren't searched on the
  server.
- **Collaboration:**
  - Claim, assign, queue, priority, tags, resolve and reopen.
  - Every change carries the conversation's revision, so a stale change is refused, and every change is audited.
  - Notes are stored apart from replies and never sent.
- **Queues:** route new conversations (settings: default queue). Workload-aware and round-robin assignment are not
  built yet.

## 8. AI (on Meldiq's AI layer, docs/AI.md)

Inbox AI needs the workspace's AI switch and a configured provider (Claude, ChatGPT or Gemini). Inbox admins then
choose a mode. **Off is the default.**

- **Drafts for review:**
  - An agent presses **Draft a reply with AI**, after a disclosure naming the provider; that press is the consent.
  - The draft lands in the composer, labelled, and the person edits and sends it. Reports count it as "sent from an AI
    draft".
  - Summaries and triage suggestions (intent, priority, tags, lead, escalation) work the same way and change nothing
    by themselves.
- **Automatic replies:** only when an inbox admin turns them on. Conditions for every automatic reply:
  - it's a topic from the allowed list;
  - it's above the confidence threshold;
  - it's answered only from **approved documents**, which must be shared with the whole workspace;
  - it's inside the provider's free window (never the Human Agent tag, never templates);
  - it's within the per-conversation hourly limit.
- **Never automatic:** refunds, payments, complaints, legal, account changes, personal data, or anger.
- **Escalation:**
  - Anything uncertain, sensitive or unanswerable escalates: **Needs a person**, with the reason shown.
  - The admin's holding reply goes out at most once an hour.
  - The queue or the assignee gets a notice.
- **Pausing:** the pause switch stops automatic replies everywhere.
- **Consent basis:** the workspace's admins switching automatic mode on; there's no person to ask per message.
- **What the AI provider receives:** conversation text and approved documents only, **never internal notes**.
- **Records:** prompts and answers are never stored or logged, only counts (`ai.*`, `inbox.ai_auto_replies`) and
  audit actions.

## 9. Handoff between AI and people

Each conversation has a handling state: `human`, `ai_draft`, `ai_auto`, `awaiting_review` or `escalated`.

- **A person replies or claims the conversation:** it becomes `human`, and automatic replies stop there.
- **An automatic reply is about to go out:** it's queued only if the conversation's revision is unchanged
  (compare-and-set). If a person changed or answered the conversation meanwhile, the AI answer is dropped, so the two
  never send conflicting replies.
- **Hand over to a person:** returns an AI conversation to people.

## 10. Notifications, workflows, reporting

- **Live events:** the hub sends ids only, never a name or text. The Android app refreshes, and shows a notice for
  escalations, overdue customers, undelivered replies and broken accounts.
- **Offline wake-ups:** go to the assignee or queue members, payload-free.
- **Response target:** once per wait, a customer waiting past the target triggers an overdue notice and the
  `inbox.conversation_overdue` trigger.
- **Workflow triggers:**
  - `inbox.conversation_created`, `inbox.conversation_overdue`, `inbox.connection_failed`;
  - conditions on `inbox.provider` and `inbox.queue`;
  - text variables `{provider}`, `{account}`, `{queue}`;
  - never the customer or their message.
- **Report** (inbox admins, last 1–90 days), from recorded events only:
  - conversations started, resolved, open and unassigned;
  - messages received, sent by people, sent from AI drafts, sent automatically, failed;
  - median first response and resolution times; escalations;
  - per-platform counts; queue sizes; reply counts per person (activity, not a ranking); account health.
- **Metering:** `inbox.messages_in`, `inbox.messages_out`, `inbox.ai_auto_replies`. The plan limit `inbox.accounts`
  is available but unset. No billing.
- **Platform admins:** `GET /v1/admin/inbox/health` shows counts by state only. Being a platform admin gives no access
  to conversations.

## 11. Privacy

- **Data minimisation:** only what the provider sends is stored.
- **Identities:** no cross-platform identity merging.
- **Retention:** per workspace, in days; old webhook payloads are dropped after 7 days.
- **Customer data:** inbox admins can **export** a conversation and **erase** a customer's data from the workspace.
  Both actions are audited. Messages already held by Meta aren't affected.
- **Push notices** never carry customer content.

## 12. Web app

The web app is a separate repository and is **not changed yet**. It can use the same endpoints:

- `GET /v1/orgs/:orgId/inbox/{providers,accounts,queues,agents,settings,report,conversations}`
- `GET|PATCH|DELETE /v1/inbox/conversations/:id`
- `POST /v1/inbox/conversations/:id/{claim,read,notes,replies,handoff,ai/draft,ai/summary,ai/triage}`
- `GET /v1/inbox/conversations/:id/export`
- `GET /v1/inbox/messages/:id/attachments/:n`

Its live refresh uses the hub events `inbox.updated`, `inbox.escalated`, `inbox.overdue`, `inbox.delivery_failed` and
`inbox.account_failed`. The server advertises the feature as `unified-inbox`.

## 13. Tests

- **Server:** `server/test/inbox.test.ts` (20) runs against a fake Meta implementing the documented endpoints, with
  synthetic customers. It covers:
  - provider states, TikTok refusal, the verify handshake;
  - token check, sealing and the one-workspace rule;
  - signatures, duplicates, cross-workspace isolation, permission checks, sealed bodies;
  - ignoring unconnected accounts;
  - replies and forward-only delivery states, notes never sent;
  - Messenger window and WhatsApp template rules;
  - retry and reconnect;
  - revisions and audit, queues and search;
  - AI drafts (consent, no notes), automatic replies and escalation, takeover, pause;
  - export and erase, report and platform health, disconnect;
  - parsing, and the agents list.
- **Android:** `InboxRulesTest` (6).

## 14. Known limits

- **Not live:** no real Meta account has been connected and no live end-to-end test has run. Every Meta row stays
  "built, needs Meta setup" until one passes with a test number or Page.
- **TikTok:** not callable.
- **Outbound:** no outbound media, no template variables, no interactive messages.
- **Connecting:** no OAuth or Embedded Signup yet.
- **Search:** no message-text search on the server.
- **Routing:** no round-robin or workload-aware assignment.
- **No web screens yet.**

## 15. Phone test (2026-10-20, HONOR ELI-AN00, Meldiq Test build, local server)

Run against a local stand-in for Meta (`META_TEST_BASE_URL`, development only and refused in production) with
synthetic customers. No real Meta account was used.

**Passed:**
- connecting a Page and a WhatsApp number from the app (fake test tokens);
- signed webhooks arriving live;
- platform badges, overdue after the response target;
- notes kept internal;
- replies with live sent → delivered → read;
- the Messenger window closing at 24 h;
- WhatsApp's template-only state and template send;
- priority, tags, resolve and the Resolved view;
- photo attachments opening through the server;
- the new workflow trigger posting in a channel;
- usage counters;
- TikTok shown as awaiting access;
- AI choices refused while no provider is configured.

**Fixed after the test, then retested on the phone:**
- the platform filter's "All platforms" label;
- "Members: n" wording;
- the save result shown beside the Save button;
- number fields that couldn't be emptied;
- time and overdue aligned right;
- delivery and read reports now refresh open screens;
- same-second WhatsApp messages keep their arrival order;
- workflow conditions read "platform" and "queue";
- the channel picker row toggles when tapped anywhere.
