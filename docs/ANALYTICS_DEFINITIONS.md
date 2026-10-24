# Analytics definitions (Platform Admin 2.0, API 0.34.0)

Every figure in the console has one definition, the same in the API (`/admin/v1/analytics/*`, `/admin/v1/presence`)
and on the page. Figures are **measured** (exact counts from the server's own records), **approximate** (said so where
they're shown), or **not available** (null, with the reason). Nothing is estimated silently, and no figure is shown for
a time before its collection began.

## Where the data comes from

| Source | What | Written | Kept |
|---|---|---|---|
| `activity_daily` | One row per person, UTC day, platform and client version: first and last qualifying request, request count, meaningful actions, the first action's time, and when the identity was created | Batched in memory, written once a minute (`TELEMETRY_TICK_MS`) | 400 days |
| `traffic_user_daily` | Bytes per person and day: `api.in`, `api.out`, `ws.in`, `ws.out`, `files.upload`, `files.download`; and `messages` (a count) | Same | 400 days |
| `presence_samples` / `presence_hourly` | Live connections, sampled once a minute; hourly peak and average | Once a minute; rollup each hour | 30 days / 400 days |
| `session_clients` | The client kind last seen on each app session | Once a minute | 400 days after last use |
| `telemetry_epochs` | When each kind of collection began, and each server version's first start | At start-up | Kept |

Activity can't be reconstructed for days before collection began: `devices.last_seen_at` and
`account_sessions.last_used_at` hold only the latest time. The console says when collection began and leaves earlier
days out instead of showing zeros.

## Qualifying activity

An **authenticated app request that succeeded** (status below 400) to a route that isn't background traffic. These don't
count (`modules/telemetry/activity.ts` › `PASSIVE`): token refresh, queue fetch and acknowledgement, push registration,
experiment and analytics calls, presence polling, reminder acknowledgements, call-ringing receipts, and the WebSocket
upgrade. A realtime connection on its own doesn't count, so an app woken in the background to fetch its queue isn't
"active". Console requests never count (they aren't app requests).

A **meaningful action** is a server-verified write: a message envelope accepted with class `message` (never its content),
a file stored, a call started or joined, an organization or channel created, a community, invitation or conversation
joined, a direct or group conversation started.

## Definitions

| Figure | Definition | Window |
|---|---|---|
| DAU | Distinct people with qualifying activity on the UTC calendar day | The day (the overview shows the last complete day) |
| WAU | Distinct people with qualifying activity over the 7 UTC days ending with the day | Rolling, whole UTC days |
| MAU | The same over 30 UTC days | Rolling, whole UTC days |
| DAU/MAU, WAU/MAU | Ratios of the above for the same day | — |
| New / returning active | Active that day and created that UTC day / created earlier | The day |
| Active in a range | Distinct people whose activity on a day overlaps the range. Exact when the range ends now; the comparison window is approximate within a day (activity is stored as the first and last time per day) | The range |
| Activation | Of sign-ups since collection began that have had 7 full days: those who made a meaningful action within 7 days of signing up | 7 days from sign-up |
| Time to first action | Median of (first meaningful action − sign-up) among activated people | — |
| Day-N retention | Share of a sign-up day's cohort with qualifying activity on the UTC day N days later (N = 1, 7, 30). Blank when that day isn't over or was before collection began. The denominator is every sign-up that day, deleted accounts included | — |
| Connected people | Distinct people with an authenticated WebSocket open now (exact for this API instance) | Now |
| Actively using | Distinct people with qualifying activity in the last 5 minutes | Now |
| Devices / sockets | Devices with an open socket / individual sockets (one browser tab each) | Now |
| Dropped connections | Sockets closed without a close frame (code 1006), including the 30-second heartbeat's terminations | Per minute |
| Refused connections | Sockets closed for missing, wrong or expired credentials | Per minute |
| Messages accepted | Message envelopes the server accepted, counted per sender per day. The server can't read them | UTC days |

## Exclusions

Load-test (synthetic) accounts are always left out. Operators can list more internal or test accounts
(`analytics.excluded_users`) and choose whether console admins count (`analytics.exclude_admins`, on by default). Each
exclusion applies when figures are read, so changing it changes past figures too. Live counts leave the same people
out; device and socket counts don't (they measure load).

## Clients and versions

The platform is derived from what clients already send: OkHttp's default User-Agent is the Android app, browsers are
web, CFNetwork with an app name is iOS (with its build). App versions appear only when a client sends them; today
Android and web don't. Clients may send `X-Meldiq-Client: android/<version>` in a future release; the server accepts
it, checked against a strict pattern. Nothing is fingerprinted and no location is collected.

## Time

Everything is stored in UTC. Days, weeks and months are UTC days. The console shows times in the viewer's time zone,
with the exact UTC time on hover, and labels day buckets as UTC.
