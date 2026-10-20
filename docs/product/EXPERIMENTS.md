# Experiments and product analytics (Meldiq 2.0, 2026-10-21)

Off by default, everywhere. Nobody is in an experiment and nothing is collected until the owner approves it and the
operator turns it on. Code: `server/src/experiments/`, Android `data/repo/ProductSignals.kt`, web
`src/sync/signals.ts`.

## 1. Switches (server environment)

| Variable | Default | Meaning |
|---|---|---|
| `EXPERIMENTS_MODE` | `off` | `off`: nobody assigned. `preview`: only preview people (below), for internal testing on staging or production. `on`: running experiments, within their dates, for everyone in their traffic share |
| `EXPERIMENT_STATUS` | empty | Operator override per experiment, `id=draft\|preview\|running\|stopped`. **`stopped` is the immediate rollback** (restart applies it); a bad value refuses to start |
| `EXPERIMENT_PREVIEW_USER_IDS` | empty | Extra preview people; platform admins (`PLATFORM_ADMIN_USER_IDS`) always preview |
| `EXPERIMENT_SALT` | `meldiq-experiments-v1` | Changing it reshuffles every assignment; not a secret |
| `ANALYTICS_ENABLED` | `false` | Whether the server accepts analytics events at all |

## 2. Experiments

**Definitions.** They live in `server/src/experiments/definitions.ts`, so every change is code-reviewed, and
`definitionProblems()` is unit-tested. Each one has:
- an id and a description;
- a **layer**: experiments in one layer never overlap, so one person is in at most one of them;
- a traffic share and a control;
- weighted variants;
- start and end dates;
- metrics, the first being the primary one;
- success criteria in plain words.

**Assignment.** An HMAC of the layer and the user id picks a bucket out of 10,000; each experiment owns a fixed slice
of its layer. A second hash picks the variant. Nothing is stored, the result is the same on every device, and stopping
one experiment moves nobody in another.

**Clients.** They ask `GET /v1/experiments` and show the control unless the answer names a variant. A client records
`experiment.exposed` only when it actually renders a variant.

**Drafts.** All three are drafts, assigned to nobody:

| Id | Layer | Question | Success criterion |
|---|---|---|---|
| `home-layout` | navigation | Section list (control) vs cards first | More conversations opened from Home per active day, no rise in searches for conversations people already belong to |
| `community-onboarding` | onboarding | Land in the channel list (control) vs #welcome with a checklist | More new members send a first message within a day |
| `directory-layout` | integrations | Recommended first (control) vs grouped by task | More connections per directory visit, no rise in week-one disconnections |

None of them is wired into a screen yet. A variant gets built only once the owner wants that experiment run.

**Safeguards.**
- Experiments never change billing, plans, permissions or security settings, and never decide what someone may see.
- Rollback is `EXPERIMENT_STATUS=<id>=stopped` or `EXPERIMENTS_MODE=off`; everyone is then on the control.

## 3. Analytics events

**The catalogue is closed.** It lives in `server/src/experiments/events.ts`.
- An unknown event, or a property outside its list of allowed values, is dropped.
- No property is free text. Message content, names, email addresses, channel and workspace names or ids can't travel
  this way.
- A connector property must name an app in the catalogue.

**Storage is aggregate only.** `analytics_daily` (migration 029) holds daily counts per event, allowed property
values, platform, and experiment variant. There is no user id, device id or IP address. Each event adds one overall
row and one row per experiment the sender is in.

| Event | Properties | Value |
|---|---|---|
| `app.opened` | none | |
| `onboarding.completed` | `kind` team/community | ms since sign-up |
| `first_action.done` | `surface` | ms since sign-up |
| `conversation.opened` | `surface` | |
| `message.sent` | `surface` (channel/thread) | |
| `community.created`, `workspace.created`, `community.joined` | none | |
| `channel.joined` | `kind` | |
| `search.performed`, `search.result_opened` | `scope`, `results` bucket 0/1/2-5/6+ | |
| `integration.viewed`, `integration.connected` | `connector` (catalogue id) | |
| `call.joined` | `type` voice/video/room | |
| `error.shown` | `code` (server error code) | |
| `experiment.exposed` | `experiment`, `variant` | |

**Sent by clients today.**
- Android: app opens, sends, searches, app views, workspace and community creation.
- Web: app opens, sends, searches, workspace and community creation.
- The rest are defined for later.

**Clients.** Each queues at most 200 events, sends at most 50 at a time, waits 30 s between sends, and also sends when
the app goes to the background. They send nothing unless the server says analytics is on.

**Report.** `GET /v1/admin/experiments?days=28` (platform admins only) shows totals per event and per experiment
variant.

**Not measurable with aggregate counts.** Retention, and time to first action per person beyond an average. Measuring
those needs a pseudonymous per-person daily marker. That is a privacy decision (consent, retention period, privacy
notice), so it waits for the owner.

## 4. Before turning anything on in production

1. The owner approves the experiment and its success criterion, in writing.
2. Update the privacy notice for analytics, and decide whether consent is needed in the owner's markets.
3. Try it on staging with `EXPERIMENTS_MODE=preview`.
4. Set `ANALYTICS_ENABLED=true`, then `EXPERIMENT_STATUS=<id>=running` and `EXPERIMENTS_MODE=on`.
5. Decide how long `analytics_daily` rows are kept. No automatic cleanup exists yet; that's a follow-up.
