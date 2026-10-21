# Usage metering, plan limits and billing readiness (round 7 slice C7, 2026-10-20)

> **Community rebuild (2026-10-21):** only file transfer is still counted (API requests, webhooks, workflows, AI and inbox counters went with their modules), and the limits left are `roles.custom`, `members` and `storage.bytes`; an environment still naming a removed limit starts and ignores it. See `COMMUNITY_REBUILD_2026-10-21.md`.

Groundwork for running Meldiq commercially later. **No plan, price or limit has been decided, nothing charges anyone,
and no upgrade is offered.** Out of the box every capability is unlimited; the answer to "which plan?" is `null`.

## Usage metering

Per workspace and UTC day (`usage_daily`), counts and sizes only: never message content, file names, or who did what.

| Metric | Kind | Measured from |
|---|---|---|
| `api.requests` | counter | each external API request (`/ext/v1`) |
| `webhooks.deliveries` | counter | each webhook delivery attempt |
| `workflows.runs`, `workflows.emails` | counter | each workflow run started; emails a workflow sent |
| `files.upload_bytes`, `files.download_bytes` | counter | bytes uploaded; bytes asked for on download (a range counts its own length; also for S3 links) |
| `seats.members` | gauge | members, guests included |
| `seats.active_30d` | gauge | members with a device seen in the last 30 days |
| `storage.bytes` | gauge | stored encrypted file bytes |
| `calls.participant_minutes` | gauge | minutes people spent in the workspace's calls that day (today and yesterday recomputed) |

Counters are added to as things happen; gauges are recomputed from the database (at most every ten minutes, in the
retention job) and replace the day's value, so recomputing never double-counts. Metering never blocks the action it
measures. Not metered, and why: email and push volume per workspace (account emails and push wake-ups belong to people
and devices, not workspaces; the platform health view counts them overall), recording and transcription (not built),
AI (slice C8, off).

**Who sees it:** the workspace's Usage screen (`workspace.usage.view`) shows the last 30 days; platform admins see every
workspace's totals (`GET /v1/admin/usage?days=`).

## Plan limits (entitlements)

Limited capabilities: members (guests included), stored file bytes, workflows, documents, user groups, active API keys,
outgoing webhooks, installed apps, incoming webhooks, app notifications, custom roles.

Two layers, both shown with their source on the Usage screen and `GET /v1/orgs/:orgId/plan`:

1. **Server-wide** (`ENTITLEMENT_LIMITS`, e.g. `members=50,storage.bytes=10737418240`), for every workspace.
2. **A workspace's own**, set by **platform admins only** (`PUT /v1/admin/orgs/:orgId/limits`, `null` clears one; audited
   as `plan.limits`). A workspace can never raise its own limits.

**Enforced on the server** when something is added: a member joining (invitation accepted), an upload (the file's size
counts before it's stored), creating a workflow, document, group, API key or webhook, plus the earlier apps and roles
checks. The answer is 409 `limit_reached` naming the limit. Existing data is never deleted or hidden when a limit is
lowered; only adding more is refused. The fixed technical maximums (25 workflows, 100 groups, 25 keys, 10 webhooks per
workspace) still apply under any plan limit.

## Billing readiness (not built, by design)

When plans are approved, the boundary is:

- a `subscriptions` record per workspace (plan id, state: trialing / active / past_due / cancelled, period end) written
  only from **verified, idempotent provider webhooks** (signature checked, event id stored once);
- the plan's limits written into `org_entitlements` by that handler, so enforcement stays exactly what's above;
- upgrades, downgrades, renewals, cancellation and failed payments as provider events; invoices stay with the provider;
- usage-based prices read `usage_daily`, which already holds the inputs.

No payment provider, price, trial or paywall exists; adding one needs the owner's approval.

Migration 026. Tests: `server/test/usage.test.ts` (counters and gauges, real actions metered, who may see usage,
limit sources, platform-admin-only limits, enforcement for members, documents, groups and storage, configuration
validation).
