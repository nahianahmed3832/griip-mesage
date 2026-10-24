# Alert rules and the incident lifecycle (Platform Admin 2.0, API 0.34.0)

## Rules

Evaluated once a minute by the console's job (`modules/platformAdmin/alerts.ts`), only when the console is configured
(`ADMIN_CONSOLE_ORIGIN`). Each rule measures one value over its window:

| Rule | Value | Default threshold | Window |
|---|---|---|---|
| `api.error_rate` | 5xx share of requests (needs 20 requests) | 5 % | 5 min |
| `api.latency_p95` | average per-minute p95 | 1,500 ms | 10 min |
| `api.memory` | container memory / limit | 90 % | 10 min |
| `host.cpu` | host CPU | 90 % | 10 min |
| `host.memory` | host memory used | 90 % | 10 min |
| `host.disk` | data disk used | 85 % | 5 min |
| `host.egress` | host outbound (collector) | 20 MB/s | 15 min |
| `egress.anomaly` | API outbound ÷ its 7-day hourly average (needs a day of history; ignored under 1 MB/s) | 3× | 30 min |
| `auth.failures` | wrong passwords + refused sockets | 200 | 15 min |
| `ws.abnormal` | dropped sockets | 200 | 15 min |
| `signups.spike` | new accounts (load-test left out) | 100 | 60 min |
| `jobs.failing` | background jobs whose last run failed | 0 | 15 min |
| `quota.blocked` | quota blocks and throttles | 50 | 60 min |
| `ai.errors` | failed AI requests | 20 | 60 min |
| `collector.down` | host collector configured but silent for 3 minutes | 0 | 5 min |

A rule **breaches** when its value passes the threshold. It **clears** only once the value is back under 90 % of the
threshold (hysteresis), so a value hovering at the line doesn't flap. With no data (a series not measured, too few
requests) a rule keeps its state and shows "no data", never a made-up value. Thresholds, windows, severities and
on/off are set in the console, and audited.

A breach opens **one incident per rule** (deduplicated by `rule:<key>` while unresolved). Later breaches add a note and
move its last-seen time. Clearing adds a note. Rules never resolve incidents; people do.

## Notifications

With `alerts.email` on and a mail transport configured, a new incident is emailed to the super and operations admins.
Each attempt is recorded in `alert_deliveries`: `sent` only when the mail server accepted it, `failed` with its error
code, `not_configured` when email is off or unavailable.

## Incident lifecycle

open → acknowledged → investigating → monitoring → resolved → closed, and reopening is allowed.

- Resolving needs a resolution summary: what happened and what fixed it.
- Each incident keeps its first and last seen, acknowledged, resolved and closed times, affected service, source (an admin or an alert rule) and assignee.
- Every change is an event on the incident and a platform audit row.
- Support cases are incidents of kind `support`, linked to a person or an organization.
