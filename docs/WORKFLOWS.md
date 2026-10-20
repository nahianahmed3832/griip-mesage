# Workflows (round 7 slice C3, 2026-10-20)

"When this happens in the workspace, do that." A workflow has a trigger, optional conditions and one to five actions.
It runs on the server with its owner's rights. Only real server events can trigger it, and only a fixed set of
actions is available: nothing runs code, posts as a person, or reaches a channel its owner couldn't.

Not the same as the apps' "post there" routing (`INTEGRATIONS.md` › Workflows): that one forwards connector events into
channels; this one is the workspace's own automation.

## Who

`workflows.manage`: owners and admins by default, or a custom role that grants it (category Apps). Workspace ›
Workflows in the app. **Whoever saves a workflow becomes its owner**; on every run the server checks the owner still
holds `workflows.manage` and can reach each channel. If not, the run fails with `owner_not_allowed` (or that action
fails), and the list says so; saving it again hands it to the saver.

## Triggers (real events only)

| Trigger | When | Event data |
|---|---|---|
| `member.joined` | someone accepts an invitation and becomes a member | name, role |
| `member.left` | a member leaves or is removed | name, role, `left` / `removed` |
| `member.role_changed` | owner / admin / member / guest changes | name, old and new role |
| `channel.created` | any channel is created | name, kind, scope |
| `schedule` | daily, weekdays, weekly or monthly at a local time in an IANA time zone (DST-correct; missed occurrences aren't replayed) | the occurrence time |

Not available, and why (listed in the catalog so the app can say so): task and shift events (those modules aren't
built), message events (end-to-end encrypted), reminder events (sealed: the server knows when, not what).

## Conditions

`member.role`, `member.new_role`, `member.leave_reason`, `channel.kind`, `channel.scope`, `channel.name` with `is`,
`is not` or `contains` (case-insensitive). Each applies only to the triggers that carry it.

## Actions

| Action | What | Checks |
|---|---|---|
| `post_channel` | posts a Workflows update in a channel (`{member}`, `{role}`, `{channel}`, `{workspace}` filled in, 300 characters) | the owner can see the channel; announcement channels need a channel manager as owner; archived channels fail. Shown as an app update: **not end-to-end encrypted**, like other app updates |
| `add_to_channels` | adds the event's member to up to 10 channels | member triggers only; public channels in the member's reach, private channels only if the owner manages them |
| `email_admins` | emails the workspace's owners and admins who have a verified account email (at most 20) | skipped, not faked, when the server can't send email or no admin has a verified address |

## Running

- An event creates one run per matching enabled workflow, **unique per workflow and event**: a repeated event runs once.
- A timer (every 3 s, `WORKFLOW_TICK_MS`) claims due runs with a 60 s lease, performs the actions in order and saves
  each action's outcome straight away, so a retry never repeats an action that already happened (channel posts are
  also de-duplicated by run and action).
- Problems retrying can't fix (a channel gone, a right lost) fail that action; the others still run. Other errors are
  retried with backoff (30 s, 1 min, 2 min…), at most 5 attempts, each run within 15 s.
- Bounds: 25 workflows per workspace, 5 conditions, 5 actions, 300 runs started per workspace per hour (more are
  recorded as skipped `rate_limited`, never dropped silently), and a loop guard: events caused by a workflow carry a
  depth; past 2 they're skipped as `loop_guard`. No action today raises a trigger, so loops can't form yet.
- A queued run can be cancelled. Every create, change, delete and cancel is in the audit log.
- **Test** runs a dry run for a sample event: it validates and says what each action would do, changing nothing.

## API

`GET /v1/orgs/:orgId/workflows/catalog` (triggers, conditions, actions, limits, templates) · `GET|POST
/v1/orgs/:orgId/workflows` · `PUT|DELETE /v1/orgs/:orgId/workflows/:id` (PUT needs `rev`; 409 when stale) ·
`POST /v1/orgs/:orgId/workflows/test` · `GET /v1/orgs/:orgId/workflows/:id/runs` ·
`POST /v1/orgs/:orgId/workflow-runs/:id/cancel`. Server feature `workflows`. Tables `workflows`, `workflow_runs`
(migration 022).

## Templates

Welcome new members (add to channels + greeting), Tell admins when someone leaves (email), Announce new channels,
Weekly reminder post. The brief's shift, missed-checkout, task-deadline and GitHub templates wait for those modules.

Tests: `server/test/workflows.test.ts` (permissions, validation, welcome run end to end and idempotent, conditions,
email to verified admins, dry run, lost owner rights and takeover, stale revision, announcement channel and schedule,
loop guard, duplicate events, cancel), `WorkflowRulesTest`.
