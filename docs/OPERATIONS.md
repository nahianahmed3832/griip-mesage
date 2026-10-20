# Operations: service health (round 7 slice C1, 2026-10-20)

What the Meldiq server measures about itself, and where platform admins see it. Disaster recovery is in
`DISASTER_RECOVERY.md`; the architecture in `MELDIQ_PLATFORM_EXPANSION_PLAN_2026-10-20.md` §1.

## Who sees it

Platform admins only: the user ids in `PLATFORM_ADMIN_USER_IDS` on the server. Owning or administering a workspace
gives no access. In the app: Settings › **Service health** (the row appears only for platform admins). API:
`GET /v1/admin/health` (403 for everyone else). Read-only.

## What it shows

| Section | Source | Notes |
|---|---|---|
| Checks | live on each request | database (size), MongoDB ping, LiveKit configured, file storage listing (3 s timeout) |
| Load | `src/ops/metrics.ts` | requests and server errors in the last 15 minutes, 95th-percentile response time (by bucket bound), event-loop delay, memory, devices online |
| Queues | database counts | waiting deliveries and the oldest one's age; reminders more than 5 minutes late and undelivered; reminders retrying; app jobs queued and given up; failed app deliveries in 24 h (not counted while integrations live in MongoDB: says so) |
| Background jobs | metrics | runs, failures, last error kind and duration: `retention.cleanup`, `reminders.tick`, `integrations.jobs` |
| Outside services | metrics | `mail.<transport>`, `push.unifiedpush`, `push.fcm`: successes, failures, last error kind |
| Failing or slow requests | metrics | route templates with server errors or a 95th percentile of 1 s or more |
| Warnings | `warningsFor` | plain-language problems from the values above (thresholds in `THRESHOLDS`, `src/ops/health.ts`) |

## Rules

- **Measured values only.** Anything the process can't measure is returned as `{ available: false, reason }` and shown
  as "not measured: …".
- **No personal data.** Requests are keyed by route template (`GET /v1/conversations/:id`), never the path with ids;
  errors by class or code, never their message; no user ids, conversation ids, addresses, tokens or content.
- **In memory, per process.** Counters reset on restart (the view shows uptime). A second API process would need these
  exported to a shared store first.
- **No polling.** The app fetches when the screen opens or Refresh is tapped.

## Not built yet

Cost view (AWS billing data is reachable through the ops role but not collected), LiveKit room and participant
counts, SMTP bounce data (Hostinger gives none to the API), alerting (nothing notifies anyone yet: a check from
outside the VPS, such as an uptime monitor on `/healthz`, would be the first step), web view of this page.
