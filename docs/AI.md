# AI in Meldiq (round 7 slice C8, 2026-10-20)

AI is **built but off**, per the owner's decision. It stays off until both of these are true:

1. the operator configures at least one provider on the server;
2. a workspace's admins turn it on for that workspace.

No key, budget or provider account has been set up, and none is needed for anything else to work.

## Providers

The server supports three providers. The operator may configure any of them, in any order.

| Provider | `AI_PROVIDERS` id | API used | Key | Model |
| --- | --- | --- | --- | --- |
| Claude (Anthropic) | `anthropic` | Messages API, `api.anthropic.com/v1/messages` | `AI_ANTHROPIC_API_KEY` | `AI_ANTHROPIC_MODEL`, default `claude-haiku-5-5` |
| ChatGPT (OpenAI) | `openai` | Chat Completions, `api.openai.com/v1/chat/completions` | `AI_OPENAI_API_KEY` | `AI_OPENAI_MODEL`, **required** |
| Gemini (Google) | `gemini` | `generateContent`, `generativelanguage.googleapis.com/v1beta` | `AI_GEMINI_API_KEY` | `AI_GEMINI_MODEL`, **required** |

- **Keys:** each key may come from `NAME_FILE` instead of `NAME`, such as a Docker secret. Keys stay on the server, and the apps never see them.
- **Models:** only Claude has a default model. Meldiq doesn't guess model names for the others; the operator names the model their account can use.
- **Default provider:** the first provider in `AI_PROVIDERS` is the server default. Each workspace's admins may pick another configured provider, or "Server default".
- **Removed providers:** if the operator later removes a workspace's chosen provider, that workspace falls back to the server default.
- **Startup checks:** the server refuses to start when a listed provider has no key, when ChatGPT or Gemini has no model, or when `AI_PROVIDERS` names an unknown id.

### Other settings

| Setting | Default | Meaning |
| --- | --- | --- |
| `AI_MONTHLY_REQUESTS_PER_ORG` | 500 | AI requests per workspace per calendar month (UTC). A plan limit `ai.requests_monthly` can lower it per workspace. |
| `AI_REQUESTS_PER_USER_PER_MINUTE` | 6 | Requests per person per minute; more get HTTP 429. |
| `AI_MAX_INPUT_CHARS` | 40000 | Largest text sent in one request. |
| `AI_TIMEOUT_MS` | 30000 | Time allowed per provider call. A passing failure (busy, 5xx, timeout) is retried once. |

Example: Claude as the default, Gemini as an option.

```
AI_PROVIDERS=anthropic,gemini
AI_ANTHROPIC_API_KEY_FILE=/run/secrets/anthropic_key
AI_GEMINI_API_KEY_FILE=/run/secrets/gemini_key
AI_GEMINI_MODEL=<a Gemini model your account can use>
```

**Before turning a provider on, read its current API data terms.** Every provider's terms differ and change over time.

- Some providers keep API inputs for a while for abuse monitoring.
- Google states that Gemini API traffic on its unpaid tier may be used to improve its products.
- So a production workspace should use a paid Gemini tier.

The owner approves the provider, the key and the budget. Nothing here buys anything.

## Gates (server, `src/ai/service.ts`)

Every request passes these checks, in order:

1. **A provider is configured.** Otherwise the server answers 409 `ai_not_configured`.
2. **The workspace switch is on.** Otherwise 409 `ai_off`. Admins with `workspace.edit` change it: `PUT /v1/orgs/:orgId/ai {enabled, provider}`, and the change is audited.
3. **The person is a member and not a guest.** Outsiders get 404 and guests get 403.
4. **Consent for this request** (`consent: true`). The app only sends it when the person presses **Send to <provider>**. The disclosure under the button names the provider and says what's sent.
5. **The per-minute rate.**
6. **The monthly allowance:** the server cap, then any plan limit (`ai.requests_monthly`, docs/USAGE_AND_PLANS.md).
7. **The input size.**

What's recorded, and what isn't:

- **Counts only:** usage counters `ai.requests`, `ai.input_tokens` and `ai.output_tokens`; health metrics per provider (`ai.anthropic` etc.); and an audit entry with the feature name and provider id.
- **Never recorded:** the text sent or the answer. That applies to the log, the audit trail and the database.
- **Failures:** they're reported by kind only (`rate_limited`, `unavailable`, `rejected`, `timeout`, `bad_response`). The kind is used because a provider's error message can echo the input.

## Features

| Feature | Endpoint | What's sent |
| --- | --- | --- |
| Writing help (composer → attach panel) | `POST /v1/orgs/:orgId/ai/write` | The draft, and one of: improve, shorter, friendlier, formal, fix spelling. |
| Catch up (channel → attach panel) | `POST /v1/orgs/:orgId/ai/catch-up` | The newest readable messages: up to 150 messages and 30,000 characters, picked and **decrypted on the phone**. |
| Document summary (document → More) | `POST /v1/documents/:id/ai/summary` | The document's title and text. |
| Ask your documents (workspace → AI) | `POST /v1/orgs/:orgId/ai/ask` | The question, plus up to 5 matching documents (6,000 characters each). |
| Workflow draft (Workflows → New) | `POST /v1/orgs/:orgId/ai/workflow-draft` | The description, plus the names and ids of channels the person can see. |

Notes on each feature:

- **Writing help:** "Use this" replaces the draft. Nothing is sent automatically.
- **Catch up:**
  - The server can't read encrypted messages, so the phone sends them.
  - The server checks that the person can read the channel.
- **Document summary:** access follows `DocumentService.roleOf`, so restricted documents stay restricted. Documents are server-readable (docs/DOCUMENTS.md), and the sheet says so.
- **Ask your documents:**
  - Only documents the person may read are searched.
  - If none match, nothing is sent to the provider.
  - Answers cite their sources as [1], [2], and the sources open the documents.
- **Workflow draft:**
  - Needs `workflows.manage`.
  - The draft is validated like a saved workflow and comes back with any problems.
  - It opens in the editor switched off. It's never saved until the person saves it.

What people wrote always goes inside `<material>` tags. The instruction to the provider treats it as material, never as instructions. Answers are only shown to the person who asked, and nothing acts on them automatically.

**Not built:** meeting notes. Meldiq doesn't record or transcribe calls.

## Apps

- **Android:**
  - Workspace → **AI**: the switch and provider choice for admins, the monthly count, Ask your documents, and what's sent.
  - AI sheets in the attach panel, the document More menu and the workflow New sheet. They appear only when `/v1/orgs/:orgId/ai` says AI is available.
  - Unit tests: `AiRulesTest`.
- **Web:** not yet.

## Tests

`server/test/ai.test.ts` (17) uses a fake provider and fake HTTP for the three APIs. It covers:

- off by default, admin-only switch, provider choice and fallback;
- consent, guests and outsiders;
- metering and the monthly limit, the per-minute rate;
- retry, and failures by kind;
- access for each feature, and no draft saved;
- no text in the audit trail;
- request and response shapes for Claude, ChatGPT and Gemini, and the config checks.
