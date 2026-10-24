# Unified quotas (Platform Admin 2.0, API 0.34.0)

One policy store, one resolution rule and one accounting path for every quota the server enforces beyond its own
environment limits (`modules/quotas`). **Nothing is enforced until an operator writes a policy.** With no policy for a
key, a check is a no-op: no database read or write.

## Keys

| Key | Kind | Period | Scopes | Modes beyond observe/warn | Enforced at |
|---|---|---|---|---|---|
| `storage.bytes` | gauge | — | global, org, user | soft, block | uploads, before anything is received |
| `files.upload_bytes` | counter | month | global, org, user | soft, block | uploads: reserved at the start, committed when stored, released on failure |
| `files.download_bytes` | counter | month | global, org, user | soft, block | downloads: the bytes asked for |
| `files.max_upload_bytes` | per request | — | global, org, user | block | uploads; never above `FILE_MAX_BYTES` |
| `ws.connections` | gauge | — | pool, user | block | opening a realtime connection |
| `sessions.active` | gauge | — | user | — | sign-in (observe/warn only: sign-in is never refused by a quota) |
| `api.requests` | counter | day | global, org, user | — | every authenticated request (observe/warn; the per-minute rate limiter throttles) |
| `ai.requests` | counter | day | global, org, user | soft, throttle, block | the AI gateway |
| `ai.tokens` | counter | month | global, org, user | soft, block | the AI gateway (reserved, then settled with the provider's count) |
| `ai.cost_micro` | counter | month | global, org, user | soft, block | the AI gateway (estimate from operator prices) |

Gauges are measured at their source at check time (stored bytes from the file store, open sockets from the hub). Open
reservations are added to them. Counters are the engine's own (`quota_counters`, per scope, key and period).

## Resolution

For each scope a request touches (its person, its organization if the space belongs to one, the platform total, a
capacity pool):

1. a temporary exception (override) for that scope, until it expires;
2. the scope's own policy;
3. the policy of the plan the scope is on;
4. the platform default for that kind of scope (`global` / `default:user` or `default:org`).

A **hard** platform default caps 1–3: they may set a lower limit, never a higher one. The platform total
(`global` / `""`) and capacity pools are scopes of their own, checked against their own usage, so nothing narrower can
raise them. **A request passes only if every scope it touches passes**, so a person's exception doesn't get past their
organization's limit. The server's environment limits (`FILE_MAX_BYTES`, `ATTACHMENT_*`, `MAX_DEVICES_PER_USER`,
`RATE_LIMIT_PER_MINUTE`, `ENTITLEMENT_LIMITS` and workspace limits) still apply on top. The console lists them beside
each effective quota.

## Modes

| Mode | Under the limit | Over the limit |
|---|---|---|
| observe | allowed | allowed; recorded as `observed_over` |
| warn | allowed; `warned` once usage reaches `warn_pct` | allowed, `warned` |
| soft | allowed | allowed up to `grace_pct` over (`soft_over`), then refused |
| throttle | allowed | refused with the period's reset time (429 `quota_exceeded`, `resetsAt`) |
| block | allowed | refused (429 `quota_exceeded`) |

Refusals name the allowance, its limit, what's used and when it resets. Decisions are recorded in `quota_events`:
the same scope, key and outcome at most every 10 minutes, kept 180 days.

## Accounting

A check and its reservation run in one immediate SQLite transaction, so two parallel requests can't both take the
last of an allowance. A reservation is committed with what was really used, or released when the operation fails.
Reservations made with an idempotency key (an upload's id) are found again by it, so a retry reserves nothing twice.
Reservations nobody settles expire (30 minutes, or the upload session's lifetime plus an hour). The cleanup job then
releases them, recorded as `expired_reservation`. Counters begin when a key gets its first policy (telemetry holds
the history before). A new period is a new row: earlier periods are never deleted. Operator corrections (`adjust`) are
counter changes with a reason, recorded as `adjusted`, never edits of history.

Ownership: an upload into a community counts once against the uploader's counter and once against the community's.
Each limit is checked against its own counter, so the same bytes never count twice against one allowance.

## Policies, plans and history

Changing a policy inserts a new version and marks the old one superseded (`quota_policies`). Ending a policy leaves its
row. Plans (`quota_plans`) are named sets of policies with no prices; assigning one is recorded in the platform audit.
Every change is audited with its before and after.

## Rollout

Start with `observe` (or `warn`) on a platform default. Watch the ledger and the usage table, then move to `soft` or
`block`. Saving a policy shows how many scopes are already over the new limit.
