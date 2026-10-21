# Meldiq AI foundation (2026-10-21)

Master prompt §14. **Status: specified, not built.** The earlier AI module was removed with the optional connectors
in API 0.12.0. It was a per-workspace provider choice (Claude, ChatGPT or Gemini), off by default, never configured
in production (`docs/COMMUNITY_REBUILD_2026-10-21.md`). Meldiq Web still calls its removed routes, and that code goes
in the web community rebuild. This file defines the replacement: one optional service, built so that adding AI
features never needs a backend rewrite and never touches end-to-end encryption without the person's explicit action.

## 1. The constraint that shapes everything

Messages, files and calls are end-to-end encrypted, so the server holds only ciphertext and cannot summarise,
translate, search or moderate them. Therefore:

- **Server-side AI may only see what a person deliberately sends it,** after a consent step that names the
  destination (for example "Amazon Bedrock, Claude Haiku, Singapore region") and exactly what goes. The person's own
  app decrypts and sends it. Nothing is sent automatically, in the background, or for anyone else's messages unless
  that person can already read them.
- **On-device processing is preferred where it is good enough.** Translation already runs on the phone (ML Kit,
  `docs/LANGUAGES.md`). Keyword filters for moderation (MOD-08) also run on devices.
- **Plaintext metadata the server already has** (channel names and topics, forum tag names, member counts, event
  titles) may be used for community features, such as describing a community in Discover, with the community's
  admin turning it on.
- **No training on customer data.** Bedrock does not use prompts for training, and that is part of choosing it.
  Retention is "none": the server keeps neither the text nor the answer, only a usage record (§3).

## 2. Service boundary

```
App (decrypts, shows consent) ──POST /v1/ai/tasks {kind, input, consent:true}──▶ API: AiService
     ▲                                                                              │ checks: feature flag, community setting,
     └──────── GET /v1/ai/tasks/:id (or a WebSocket event) ◀── result (not stored) ─┤ permission, quota, rate limit, size
                                                                                    ▼
                                                    SQS ai-tasks ──▶ worker ──▶ provider adapter (Bedrock first)
                                                                         └──▶ usage record (MongoDB: ai_usage)
```

- `AiService` is one module (`src/ai/`) with a `ModelProvider` interface: `generate(prompt, opts) → text, tokens`.
  Bedrock is the first adapter. Others can be added without touching the routes.
- **Tasks:**
  - asynchronous through SQS when they're long (summaries);
  - synchronous with a short timeout when they're short (rewriting a draft).
  - A failed or slow model never blocks sending, calls, uploads or sign-in. The task fails on its own with a clear
    message.
- **Prompts are versioned** (`prompt_id@version`) in the code. The model id and the prompt version go into the usage
  record.
- **Controls:**
  - server switch `AI_ENABLED`;
  - per community "AI features" off by default, turned on by people with `workspace.edit`;
  - per feature flags;
  - per person and per community monthly quotas;
  - rate limits.
- **Records** (`ai_usage`): community, person, kind, model, prompt version, tokens in and out, cost estimate, time.
  Never the input or output. An audit event when a community turns AI on or off.

## 3. Features, in order

| Feature | Data it uses | Who triggers | Where it runs | Notes |
|---|---|---|---|---|
| Draft help (rewrite, shorten, fix spelling) | The draft the person typed | The person, per use | Bedrock after consent, or on-device later | Nothing from others |
| Catch-up summary of a channel | Messages the person can read, decrypted on their device, capped (150 messages, 30,000 characters, as before) | The person, per use, with consent naming what goes | Bedrock | The summary isn't posted or stored |
| Translation | One message | The person | On the device (exists) | No server |
| Community FAQ assistant | Pinned rules and FAQ that the community's admins publish as plaintext "community knowledge" | Members | Bedrock | Admins choose what is public knowledge |
| Moderation assistance | Reports a member chose to send (MOD-07) | Moderators | Bedrock, optional | Suggests a category; a human decides |
| Event recap, voice-call summaries | Only with every participant's consent and an on-device transcript. **Deferred:** needs a consent design for everyone in the call | — | — | Not planned for the beta |

## 4. Region and cost

Bedrock model availability in ap-southeast-1 is to be checked per model before choosing. If the chosen model isn't
there, use cross-region inference within Asia Pacific, and say so in the consent text. Cost is per token: a catch-up
of about 2,000 tokens in and 300 out on a small model costs a fraction of a cent. Quotas keep a community's spend
bounded (`MELDIQ_AWS_ARCHITECTURE.md` §4).

## 5. Next steps

1. `src/ai/` with the provider interface, a fake provider for tests, the routes, quotas and `ai_usage`, all behind
   `AI_ENABLED=false`.
2. Bedrock adapter with an IAM task role allowing only `bedrock:InvokeModel` on the chosen model.
3. Android and web: one "AI" sheet with the consent step, starting with draft help.
