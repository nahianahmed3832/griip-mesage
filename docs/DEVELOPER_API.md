# Meldiq developer API and webhooks (round 7 slice C6, 2026-10-20)

A small, supported way for a workspace's own tools to read workspace details, post updates into public channels, and
hear about workspace events. Everything else under `/v1` is the apps' internal API: it can change, and API keys don't
work there.

**What it never reaches:** messages, files and calls (end-to-end encrypted: the server can't read them), private
channels, restricted or channel documents, meetings in private rooms, who's invited to a meeting, emails, phone
numbers, presence, profile details.

## API keys

Made in the app (Workspace › API keys & webhooks) by people with **Manage API keys and webhooks**
(`developers.manage`: owners and admins by default, or a custom role).

- **Scopes:** `workspace:read`, `members:read`, `channels:read`, `documents:read`, `meetings:read`, `updates:write`.
- **Expiry:** 30, 90 or 365 days (at most 365). **Revoke** stops a key at once; **rotate** makes a new key with the same
  name and scopes and revokes the old one.
- The token (`mdq.<key id>.<secret>`) is shown **once**; Meldiq stores only a SHA-256 hash of the secret, plus its last
  four characters to tell keys apart, and when it was last used.
- A key works only while **its creator still holds `developers.manage`** in the workspace.
- **Rate limit:** 120 requests a minute per key (429 beyond). At most 25 active keys per workspace.
- Creating, rotating and revoking are in the workspace's audit log.

Send it as `Authorization: Bearer mdq.…`. Errors are `{ "error": { "code", "message" } }`: 401 (no, bad, revoked or
expired key, or its creator lost the right), 403 `missing_scope`, 404 (also for anything outside what the key may see),
429 `rate_limited`, 400 validation.

## External API (`/ext/v1`)

Lists page with `?limit=` (1–100, default 50) and `?cursor=` (from `next`; `null` at the end).

| Method and path | Scope | Returns |
|---|---|---|
| `GET /ext/v1/workspace` | `workspace:read` | id, name, slug, createdAt, memberCount |
| `GET /ext/v1/members` | `members:read` | id, username, displayName, role, title, joinedAt |
| `GET /ext/v1/channels` | `channels:read` | public and announcement channels: id, name, topic, kind, voice, archived, memberCount |
| `GET /ext/v1/documents` | `documents:read` | documents shared with the whole workspace: id, title, createdAt, updatedAt |
| `GET /ext/v1/documents/:id` | `documents:read` | the same, with its Markdown content |
| `GET /ext/v1/meetings` | `meetings:read` | upcoming meetings in rooms that aren't private: id, title, startsAt, endsAt, timeZone, cancelled, room |
| `POST /ext/v1/channels/:id/updates` | `updates:write` | posts `{ title, text?, url? }` (https links only) into a **public** channel, shown as "API · <key name>" and labelled as not end-to-end encrypted. Send `Idempotency-Key` so a retry posts once (`{ posted: false }` the second time) |

Example:

```bash
curl -s https://<server>/ext/v1/channels/<channel id>/updates \
  -H "Authorization: Bearer $MELDIQ_API_KEY" -H "Idempotency-Key: build-42" \
  -H "Content-Type: application/json" -d '{"title":"Build 42 passed","url":"https://ci.example.com/42"}'
```

## Webhooks

Up to 10 per workspace, each with an https address and a list of events:
`member.joined`, `member.left`, `member.role_changed`, `channel.created` (not private channels), `document.created`,
`document.updated` (workspace-shared documents only; title, never content), `meeting.created`, `meeting.updated`,
`meeting.cancelled` (rooms that aren't private; never who's invited). A new webhook, and **Send a test event**, deliver
a `ping`.

**Addresses** must be public https: no `http://`, no credentials in the address, no private, loopback or link-local
addresses (checked when saved, and the resolved address is checked again and pinned on every send, so DNS can't point
a delivery at an internal service). Redirects aren't followed; each delivery has 10 seconds.

**Each delivery** is `POST` with a JSON body `{ id, type, workspaceId, createdAt, data }` and headers:

| Header | |
|---|---|
| `Meldiq-Event` | the event type |
| `Meldiq-Delivery` | the event's id: **the same on every retry**; de-duplicate on it |
| `Meldiq-Webhook` | the webhook's id |
| `Meldiq-Timestamp` | Unix seconds when this attempt was signed |
| `Meldiq-Signature` | `v1=` + hex HMAC-SHA256 of `"<timestamp>.<raw body>"` with the webhook's signing secret |

**Verify** by recomputing the signature over the raw body with a constant-time compare, and refuse timestamps more than
five minutes old (replay protection). Reply with any 2xx quickly; do slow work afterwards.

**Retries:** anything but a 2xx is retried with backoff (30 s, 1 min, 2 min … at most an hour apart), up to 8 attempts,
then the delivery is marked failed. After **20 failed deliveries in a row** the webhook is paused and says so; turning
it back on resets the count. Delivery history (event, state, attempts, last status or error) is kept 7 days. The
signing secret is shown once, kept sealed with the server's integrations key (`INTEGRATIONS_TOKEN_KEY`; webhooks are
unavailable without it), and can be replaced at any time (**New signing secret**). Webhooks stop sending when their
creator loses `developers.manage`.

Verification example (Node):

```js
const expected = "v1=" + crypto.createHmac("sha256", secret).update(`${req.headers["meldiq-timestamp"]}.${rawBody}`).digest("hex");
const fresh = Math.abs(Date.now() / 1000 - Number(req.headers["meldiq-timestamp"])) < 300;
const ok = fresh && crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(String(req.headers["meldiq-signature"])));
```

## Management API (signed-in apps, `developers.manage`)

`GET /v1/developer/catalog` · `GET|POST /v1/orgs/:orgId/api-keys` · `POST /v1/orgs/:orgId/api-keys/:id/rotate` ·
`DELETE /v1/orgs/:orgId/api-keys/:id` · `GET|POST /v1/orgs/:orgId/webhooks` · `PUT|DELETE /v1/orgs/:orgId/webhooks/:id` ·
`POST /v1/orgs/:orgId/webhooks/:id/rotate-secret|test` · `GET /v1/orgs/:orgId/webhooks/:id/deliveries`. Server feature
`developer-api`; migration 025.

Tests: `server/test/developer.test.ts` (keys: permissions, one-time token, scopes, no private data, idempotent
updates, key/session separation, rotation, revocation, creator losing the right, rate limit; webhooks: address rules,
signature, ping, events without private data, stable delivery ids, retry and backoff, pausing, secret rotation).
