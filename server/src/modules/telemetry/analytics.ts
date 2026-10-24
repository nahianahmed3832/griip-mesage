import type { DB } from "../../infrastructure/database/db.js";
import { utcDay } from "./activity.js";

/**
 * Analytics over the telemetry tables (docs/ANALYTICS_DEFINITIONS.md). Every query is bounded by a date range and uses
 * an index; [excluded] is a JSON array of user ids left out (load-test accounts, the operator's list, optionally
 * console admins), applied at query time so changing the list changes past figures too.
 */

export const DAY_MS = 86_400_000;
const NOT_EXCLUDED = "user_id NOT IN (SELECT value FROM json_each(@excluded))";

const dayStart = (day: string) => Date.parse(`${day}T00:00:00Z`);
export const addDays = (day: string, n: number) => utcDay(dayStart(day) + n * DAY_MS);

/** The UTC days from [from] to [to], inclusive (at most [max]). */
export function daysBetween(from: string, to: string, max = 400): string[] {
  const out: string[] = [];
  for (let d = from; d <= to && out.length < max; d = addDays(d, 1)) out.push(d);
  return out;
}

export interface ActiveUsersPoint {
  day: string;
  dau: number;
  wau: number;
  mau: number;
  newActive: number;
  returningActive: number;
}

/**
 * DAU, WAU and MAU for each day in [from, to]: distinct people with qualifying activity on the day, and over the 7 and
 * 30 UTC days ending with it. New: their identity was created that UTC day. Days before collection began are left out
 * by the caller (they'd read as zero, which isn't true).
 */
export function activeUsers(db: DB, from: string, to: string, excluded: string): ActiveUsersPoint[] {
  const distinct = db.prepare(`SELECT COUNT(DISTINCT user_id) n FROM activity_daily WHERE day >= @from AND day <= @to AND ${NOT_EXCLUDED}`);
  const split = db.prepare(
    `SELECT COUNT(DISTINCT CASE WHEN strftime('%Y-%m-%d', signup_at / 1000, 'unixepoch') = @day THEN user_id END) fresh,
            COUNT(DISTINCT user_id) total
       FROM activity_daily WHERE day = @day AND ${NOT_EXCLUDED}`,
  );
  const n = (f: string, t: string) => (distinct.get({ from: f, to: t, excluded }) as { n: number }).n;
  return daysBetween(from, to, 120).map((day) => {
    const s = split.get({ day, excluded }) as { fresh: number; total: number };
    return { day, dau: s.total, wau: n(addDays(day, -6), day), mau: n(addDays(day, -29), day), newActive: s.fresh, returningActive: s.total - s.fresh };
  });
}

/** Distinct active people over [from, to] (inclusive UTC days). */
export function distinctActive(db: DB, from: string, to: string, excluded: string): number {
  return (db.prepare(`SELECT COUNT(DISTINCT user_id) n FROM activity_daily WHERE day >= @from AND day <= @to AND ${NOT_EXCLUDED}`).get({ from, to, excluded }) as { n: number }).n;
}

/** Distinct active people per platform and per client version over [from, to]. A person on two platforms counts in both. */
export function activeByClient(db: DB, from: string, to: string, excluded: string) {
  const platforms = db
    .prepare(`SELECT platform, COUNT(DISTINCT user_id) users, SUM(requests) requests FROM activity_daily WHERE day >= @from AND day <= @to AND ${NOT_EXCLUDED} GROUP BY platform ORDER BY users DESC`)
    .all({ from, to, excluded }) as { platform: string; users: number; requests: number }[];
  const versions = db
    .prepare(
      `SELECT platform, client_version version, COUNT(DISTINCT user_id) users FROM activity_daily
        WHERE day >= @from AND day <= @to AND ${NOT_EXCLUDED} GROUP BY platform, client_version ORDER BY users DESC LIMIT 50`,
    )
    .all({ from, to, excluded }) as { platform: string; version: string; users: number }[];
  return { platforms, versions };
}

/** Active people over [from, to] by account age on their last active day: under a day, a week, a month, older. */
export function activeByAccountAge(db: DB, from: string, to: string, excluded: string): { bucket: string; users: number }[] {
  const rows = db
    .prepare(
      `SELECT user_id, MAX(julianday(day) - julianday(date(signup_at / 1000, 'unixepoch'))) age FROM activity_daily
        WHERE day >= @from AND day <= @to AND signup_at IS NOT NULL AND ${NOT_EXCLUDED} GROUP BY user_id`,
    )
    .all({ from, to, excluded }) as { user_id: string; age: number }[];
  const buckets = [
    { bucket: "Signed up that day", max: 0 },
    { bucket: "1–7 days", max: 7 },
    { bucket: "8–30 days", max: 30 },
    { bucket: "31–90 days", max: 90 },
    { bucket: "Older", max: Infinity },
  ];
  return buckets.map((b, i) => ({ bucket: b.bucket, users: rows.filter((r) => r.age <= b.max && (i === 0 || r.age > buckets[i - 1]!.max)).length }));
}

/**
 * Retention cohorts: people who signed up on each UTC day in [from, to], and how many of them had qualifying activity
 * exactly 1, 7 and 30 days later. [signups] (per day, from the accounts module) are the denominators. A cohort whose
 * day N hasn't finished yet, or began before collection started, has null for that day (not yet measurable).
 */
export function retention(
  db: DB,
  signups: { day: string; count: number }[],
  excluded: string,
  today: string,
  collectingSince: string | null,
): { day: string; size: number; d1: number | null; d7: number | null; d30: number | null }[] {
  const returned = db.prepare(
    `SELECT COUNT(DISTINCT user_id) n FROM activity_daily
      WHERE day = @target AND signup_at >= @start AND signup_at < @end AND ${NOT_EXCLUDED}`,
  );
  return signups.map(({ day, count }) => {
    const at = (offset: number) => {
      const target = addDays(day, offset);
      if (target >= today) return null; // the day isn't over
      if (!collectingSince || target < collectingSince) return null; // nothing was collected then
      return (returned.get({ target, start: dayStart(day), end: dayStart(day) + DAY_MS, excluded }) as { n: number }).n;
    };
    return { day, size: count, d1: at(1), d7: at(7), d30: at(30) };
  });
}

/**
 * Activation: of people who signed up in [from, to] (and since collection began), how many made a meaningful action
 * within 7 days of signing up, and the median time to that first action.
 */
export function activation(db: DB, from: number, to: number, excluded: string): { activated: number; medianMsToFirstAction: number | null } {
  const rows = db
    .prepare(
      `SELECT user_id, MIN(first_action_at) - MIN(signup_at) ms FROM activity_daily
        WHERE signup_at >= @from AND signup_at < @to AND first_action_at IS NOT NULL AND first_action_at < signup_at + 7 * 86400000 AND ${NOT_EXCLUDED}
        GROUP BY user_id`,
    )
    .all({ from, to, excluded }) as { user_id: string; ms: number }[];
  const sorted = rows.map((r) => Math.max(0, r.ms)).sort((a, b) => a - b);
  const median = sorted.length === 0 ? null : sorted[Math.floor((sorted.length - 1) / 2)]!;
  return { activated: rows.length, medianMsToFirstAction: median };
}

/** Distinct active people among [memberIds] (an organization's members) over [from, to]. */
export function activeAmong(db: DB, memberIds: string, from: string, to: string, excluded: string): { users: number; lastAt: number | null } {
  return db
    .prepare(
      `SELECT COUNT(DISTINCT user_id) users, MAX(last_at) lastAt FROM activity_daily
        WHERE day >= @from AND day <= @to AND user_id IN (SELECT value FROM json_each(@members)) AND ${NOT_EXCLUDED}`,
    )
    .get({ members: memberIds, from, to, excluded }) as { users: number; lastAt: number | null };
}

/** One person's activity by day (the user detail page). */
export function userActivity(db: DB, userId: string, from: string): { day: string; platform: string; version: string; requests: number; actions: number; firstAt: number; lastAt: number }[] {
  return db
    .prepare(
      `SELECT day, platform, client_version version, requests, actions, first_at firstAt, last_at lastAt FROM activity_daily
        WHERE user_id = ? AND day >= ? ORDER BY day DESC, last_at DESC LIMIT 400`,
    )
    .all(userId, from) as { day: string; platform: string; version: string; requests: number; actions: number; firstAt: number; lastAt: number }[];
}

/** One person's last qualifying activity and platforms ever seen (bounded). */
export function userLastActive(db: DB, userIds: string[]): Map<string, { lastAt: number; platforms: string[] }> {
  const out = new Map<string, { lastAt: number; platforms: string[] }>();
  if (userIds.length === 0) return out;
  const rows = db
    .prepare(
      `SELECT user_id, MAX(last_at) lastAt, GROUP_CONCAT(DISTINCT platform) platforms FROM activity_daily
        WHERE user_id IN (SELECT value FROM json_each(?)) GROUP BY user_id`,
    )
    .all(JSON.stringify(userIds)) as { user_id: string; lastAt: number; platforms: string | null }[];
  for (const r of rows) out.set(r.user_id, { lastAt: r.lastAt, platforms: (r.platforms ?? "").split(",").filter(Boolean) });
  return out;
}

// ───────────── Traffic ─────────────

/** Bytes per metric and UTC day over [from, to], [excluded] left out. */
export function trafficDays(db: DB, from: string, to: string, excluded: string): { day: string; metric: string; value: number }[] {
  return db
    .prepare(`SELECT day, metric, SUM(value) value FROM traffic_user_daily WHERE day >= @from AND day <= @to AND ${NOT_EXCLUDED} GROUP BY day, metric ORDER BY day`)
    .all({ from, to, excluded }) as { day: string; metric: string; value: number }[];
}

/** The people who moved the most bytes over [from, to] (ids only; the caller adds names it may show). */
export function topTraffic(db: DB, from: string, to: string, excluded: string, limit: number): { userId: string; total: number; byMetric: Record<string, number> }[] {
  const rows = db
    .prepare(
      `SELECT user_id, metric, SUM(value) value FROM traffic_user_daily
        WHERE day >= @from AND day <= @to AND user_id IN (
          SELECT user_id FROM traffic_user_daily WHERE day >= @from AND day <= @to AND ${NOT_EXCLUDED}
          GROUP BY user_id ORDER BY SUM(value) DESC LIMIT @limit)
        GROUP BY user_id, metric`,
    )
    .all({ from, to, excluded, limit }) as { user_id: string; metric: string; value: number }[];
  const by = new Map<string, Record<string, number>>();
  for (const r of rows) by.set(r.user_id, { ...(by.get(r.user_id) ?? {}), [r.metric]: r.value });
  return [...by.entries()]
    .map(([userId, byMetric]) => ({ userId, byMetric, total: Object.values(byMetric).reduce((a, b) => a + b, 0) }))
    .sort((a, b) => b.total - a.total);
}

/** One person's bytes per metric over [from, to]. */
export function userTraffic(db: DB, userId: string, from: string, to: string): Record<string, number> {
  const rows = db.prepare("SELECT metric, SUM(value) value FROM traffic_user_daily WHERE user_id = ? AND day >= ? AND day <= ? GROUP BY metric").all(userId, from, to) as {
    metric: string;
    value: number;
  }[];
  return Object.fromEntries(rows.map((r) => [r.metric, r.value]));
}

// ───────────── Presence and resources ─────────────

export interface PresencePoint {
  at: number;
  users: number;
  activeUsers: number;
  sockets: number;
  connects: number;
  disconnects: number;
  abnormal: number;
  authFailures: number;
}

/**
 * Concurrency over [from, to] (ms): per-minute samples for up to 2 days, hourly rollups beyond. Peak is the highest
 * sample in the range.
 */
export function presenceSeries(db: DB, from: number, to: number): { resolution: "minute" | "hour"; points: PresencePoint[]; peakUsers: number | null; peakAt: number | null } {
  const minuteRange = to - from <= 2 * DAY_MS;
  if (minuteRange) {
    const rows = db
      .prepare(
        `SELECT minute, users, active_users, sockets, connects, disconnects, abnormal, auth_failures FROM presence_samples
          WHERE minute >= ? AND minute <= ? ORDER BY minute`,
      )
      .all(Math.floor(from / 60_000), Math.floor(to / 60_000)) as {
      minute: number;
      users: number;
      active_users: number;
      sockets: number;
      connects: number;
      disconnects: number;
      abnormal: number;
      auth_failures: number;
    }[];
    const points = rows.map((r) => ({ at: r.minute * 60_000, users: r.users, activeUsers: r.active_users, sockets: r.sockets, connects: r.connects, disconnects: r.disconnects, abnormal: r.abnormal, authFailures: r.auth_failures }));
    const peak = points.reduce<PresencePoint | null>((a, p) => (!a || p.users > a.users ? p : a), null);
    return { resolution: "minute", points, peakUsers: peak?.users ?? null, peakAt: peak?.at ?? null };
  }
  const rows = db
    .prepare("SELECT hour, peak_users, avg_users, peak_sockets, connects, disconnects, abnormal, auth_failures FROM presence_hourly WHERE hour >= ? AND hour <= ? ORDER BY hour")
    .all(Math.floor(from / 3_600_000), Math.floor(to / 3_600_000)) as {
    hour: number;
    peak_users: number;
    avg_users: number;
    peak_sockets: number;
    connects: number;
    disconnects: number;
    abnormal: number;
    auth_failures: number;
  }[];
  const points = rows.map((r) => ({ at: r.hour * 3_600_000, users: r.peak_users, activeUsers: Math.round(r.avg_users), sockets: r.peak_sockets, connects: r.connects, disconnects: r.disconnects, abnormal: r.abnormal, authFailures: r.auth_failures }));
  // The hours not rolled up yet (the current one, at least), from their minute samples.
  const after = (rows.length ? rows[rows.length - 1]!.hour + 1 : Math.floor(from / 3_600_000)) * 60;
  const recent = db
    .prepare(
      `SELECT minute / 60 hour, MAX(users) peak, AVG(active_users) active, MAX(sockets) sockets, SUM(connects) c, SUM(disconnects) d, SUM(abnormal) a, SUM(auth_failures) f
         FROM presence_samples WHERE minute >= ? AND minute <= ? GROUP BY minute / 60 ORDER BY hour`,
    )
    .all(after, Math.floor(to / 60_000)) as { hour: number; peak: number; active: number; sockets: number; c: number; d: number; a: number; f: number }[];
  for (const r of recent) points.push({ at: r.hour * 3_600_000, users: r.peak, activeUsers: Math.round(r.active), sockets: r.sockets, connects: r.c, disconnects: r.d, abnormal: r.a, authFailures: r.f });
  const peak = points.reduce<PresencePoint | null>((a, p) => (!a || p.users > a.users ? p : a), null);
  return { resolution: "hour", points, peakUsers: peak?.users ?? null, peakAt: peak?.at ?? null };
}

export interface SeriesStats {
  series: string;
  resolution: "minute" | "hour";
  points: { at: number; value: number; min?: number; max?: number }[];
  latest: number | null;
  latestAt: number | null;
  min: number | null;
  avg: number | null;
  max: number | null;
  p95: number | null;
}

/** [series] over [from, to] (ms): per minute for up to 2 days (at most 1440 points, downsampled), hourly beyond. */
export function resourceSeries(db: DB, series: string[], from: number, to: number): SeriesStats[] {
  const minuteRange = to - from <= 2 * DAY_MS;
  return series.map((name) => {
    let points: { at: number; value: number; min?: number; max?: number }[];
    if (minuteRange) {
      const rows = db.prepare("SELECT minute, value FROM resource_samples WHERE series = ? AND minute >= ? AND minute <= ? ORDER BY minute").all(name, Math.floor(from / 60_000), Math.floor(to / 60_000)) as {
        minute: number;
        value: number;
      }[];
      points = rows.map((r) => ({ at: r.minute * 60_000, value: r.value }));
    } else {
      const rows = db.prepare("SELECT hour, avg, min, max FROM resource_hourly WHERE series = ? AND hour >= ? AND hour <= ? ORDER BY hour").all(name, Math.floor(from / 3_600_000), Math.floor(to / 3_600_000)) as {
        hour: number;
        avg: number;
        min: number;
        max: number;
      }[];
      points = rows.map((r) => ({ at: r.hour * 3_600_000, value: r.avg, min: r.min, max: r.max }));
      // Hours not rolled up yet, from their minute samples.
      const after = (rows.length ? rows[rows.length - 1]!.hour + 1 : Math.floor(from / 3_600_000)) * 60;
      const recent = db
        .prepare("SELECT minute / 60 hour, AVG(value) avg, MIN(value) min, MAX(value) max FROM resource_samples WHERE series = ? AND minute >= ? AND minute <= ? GROUP BY minute / 60 ORDER BY hour")
        .all(name, after, Math.floor(to / 60_000)) as { hour: number; avg: number; min: number; max: number }[];
      for (const r of recent) points.push({ at: r.hour * 3_600_000, value: r.avg, min: r.min, max: r.max });
    }
    const values = points.map((p) => p.value);
    const sorted = [...values].sort((a, b) => a - b);
    const last = points[points.length - 1];
    return {
      series: name,
      resolution: minuteRange ? "minute" : "hour",
      points: downsample(points, 720),
      latest: last?.value ?? null,
      latestAt: last?.at ?? null,
      min: sorted.length ? Math.min(...points.map((p) => p.min ?? p.value)) : null,
      avg: values.length ? values.reduce((a, b) => a + b, 0) / values.length : null,
      max: sorted.length ? Math.max(...points.map((p) => p.max ?? p.value)) : null,
      p95: sorted.length ? sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * 0.95) - 1)]! : null,
    };
  });
}

/** Keeps at most [max] points, averaging neighbours (and keeping their min and max). */
function downsample(points: { at: number; value: number; min?: number; max?: number }[], max: number) {
  if (points.length <= max) return points;
  const step = Math.ceil(points.length / max);
  const out: { at: number; value: number; min?: number; max?: number }[] = [];
  for (let i = 0; i < points.length; i += step) {
    const chunk = points.slice(i, i + step);
    out.push({
      at: chunk[0]!.at,
      value: chunk.reduce((a, p) => a + p.value, 0) / chunk.length,
      min: Math.min(...chunk.map((p) => p.min ?? p.value)),
      max: Math.max(...chunk.map((p) => p.max ?? p.value)),
    });
  }
  return out;
}

/** The newest value of every series measured in the last [withinMs] (the inventory's "now"). */
export function latestResources(db: DB, now: number, withinMs: number): Map<string, { value: number; at: number }> {
  const rows = db
    .prepare(
      `SELECT r.series, r.value, r.minute FROM resource_samples r
        JOIN (SELECT series, MAX(minute) m FROM resource_samples WHERE minute >= ? GROUP BY series) x ON x.series = r.series AND x.m = r.minute`,
    )
    .all(Math.floor((now - withinMs) / 60_000)) as { series: string; value: number; minute: number }[];
  return new Map(rows.map((r) => [r.series, { value: r.value, at: r.minute * 60_000 }]));
}

/** Average, max, sum and sample count of [series] since [sinceMinute] (alert rules). */
export function windowStats(db: DB, series: string, sinceMinute: number): { avg: number | null; max: number | null; sum: number | null; samples: number } {
  return db.prepare("SELECT AVG(value) avg, MAX(value) max, SUM(value) sum, COUNT(*) samples FROM resource_samples WHERE series = ? AND minute > ?").get(series, sinceMinute) as {
    avg: number | null;
    max: number | null;
    sum: number | null;
    samples: number;
  };
}

/** Connection events summed since [sinceMinute] (alert rules). */
export function presenceWindow(db: DB, sinceMinute: number): { samples: number; abnormal: number; authFailures: number; connects: number; peakUsers: number | null } {
  const r = db
    .prepare("SELECT COUNT(*) samples, COALESCE(SUM(abnormal),0) abnormal, COALESCE(SUM(auth_failures),0) authFailures, COALESCE(SUM(connects),0) connects, MAX(users) peakUsers FROM presence_samples WHERE minute > ?")
    .get(sinceMinute) as { samples: number; abnormal: number; authFailures: number; connects: number; peakUsers: number | null };
  return r;
}

/** The hourly average of [series] over [fromHour, toHour] (a baseline for anomaly rules), with how many hours it covers. */
export function hourlyBaseline(db: DB, series: string, fromHour: number, toHour: number): { avg: number | null; hours: number } {
  return db.prepare("SELECT AVG(avg) avg, COUNT(*) hours FROM resource_hourly WHERE series = ? AND hour >= ? AND hour <= ?").get(series, fromHour, toHour) as { avg: number | null; hours: number };
}

/** The people with qualifying activity over [from, to] (inclusive UTC days), at most [limit] (filters in the console). */
export function activeUserIds(db: DB, from: string, to: string, limit: number): string[] {
  return (db.prepare("SELECT DISTINCT user_id FROM activity_daily WHERE day >= ? AND day <= ? LIMIT ?").all(from, to, limit) as { user_id: string }[]).map((r) => r.user_id);
}

/** The client each of [sessionIds] was last seen with. */
export function sessionClients(db: DB, sessionIds: string[]): Map<string, { platform: string; client: string; version: string; os: string; lastSeenAt: number }> {
  if (sessionIds.length === 0) return new Map();
  const rows = db
    .prepare("SELECT session_id, platform, client, client_version, os, last_seen_at FROM session_clients WHERE session_id IN (SELECT value FROM json_each(?))")
    .all(JSON.stringify(sessionIds)) as { session_id: string; platform: string; client: string; client_version: string; os: string; last_seen_at: number }[];
  return new Map(rows.map((r) => [r.session_id, { platform: r.platform, client: r.client, version: r.client_version, os: r.os, lastSeenAt: r.last_seen_at }]));
}

/** Device ids whose latest session was seen on [platform] (inventory filter), from the sessions telemetry knows. */
export function sessionsOnPlatform(db: DB, platform: string, limit: number): string[] {
  return (db.prepare("SELECT session_id FROM session_clients WHERE platform = ? ORDER BY last_seen_at DESC LIMIT ?").all(platform, limit) as { session_id: string }[]).map((r) => r.session_id);
}

/** Every server version this database has run, with its first start (telemetry_epochs "version:*"). */
export function versions(db: DB): { version: string; firstStartedAt: number }[] {
  return (db.prepare("SELECT kind, started_at FROM telemetry_epochs WHERE kind LIKE 'version:%' ORDER BY started_at DESC LIMIT 50").all() as { kind: string; started_at: number }[]).map((r) => ({
    version: r.kind.slice(8),
    firstStartedAt: r.started_at,
  }));
}

/** The total of a per-second rate series over [from, to] (bytes from bytes-per-second samples), or null with no samples. */
export function seriesTotal(db: DB, series: string, from: number, to: number): number | null {
  if (to - from <= 2 * DAY_MS) {
    const r = db.prepare("SELECT SUM(value) * 60 total, COUNT(*) n FROM resource_samples WHERE series = ? AND minute >= ? AND minute <= ?").get(series, Math.floor(from / 60_000), Math.floor(to / 60_000)) as {
      total: number | null;
      n: number;
    };
    return r.n ? r.total : null;
  }
  const r = db.prepare("SELECT SUM(avg * samples * 60) total, COUNT(*) n FROM resource_hourly WHERE series = ? AND hour >= ? AND hour <= ?").get(series, Math.floor(from / 3_600_000), Math.floor(to / 3_600_000)) as {
    total: number | null;
    n: number;
  };
  return r.n ? r.total : null;
}

/** Sum of a per-minute count series over [from, to] (requests, errors), or null with no samples. */
export function seriesSum(db: DB, series: string, from: number, to: number): number | null {
  const r = db.prepare("SELECT SUM(value) total, COUNT(*) n FROM resource_samples WHERE series = ? AND minute >= ? AND minute <= ?").get(series, Math.floor(from / 60_000), Math.floor(to / 60_000)) as {
    total: number | null;
    n: number;
  };
  if (r.n) return r.total;
  const h = db.prepare("SELECT SUM(avg * samples) total, COUNT(*) n FROM resource_hourly WHERE series = ? AND hour >= ? AND hour <= ?").get(series, Math.floor(from / 3_600_000), Math.floor(to / 3_600_000)) as {
    total: number | null;
    n: number;
  };
  return h.n ? h.total : null;
}

/** Average of a series over [from, to] (minute samples, or hourly averages beyond 2 days), or null. */
export function seriesAvg(db: DB, series: string, from: number, to: number): number | null {
  if (to - from <= 2 * DAY_MS) {
    return (db.prepare("SELECT AVG(value) v FROM resource_samples WHERE series = ? AND minute >= ? AND minute <= ?").get(series, Math.floor(from / 60_000), Math.floor(to / 60_000)) as { v: number | null }).v;
  }
  return (db.prepare("SELECT SUM(avg * samples) / SUM(samples) v FROM resource_hourly WHERE series = ? AND hour >= ? AND hour <= ?").get(series, Math.floor(from / 3_600_000), Math.floor(to / 3_600_000)) as { v: number | null }).v;
}

/** Distinct people with qualifying activity whose activity that day overlaps [from, to] (exact when [to] is now). */
export function activeBetween(db: DB, from: number, to: number, excluded: string): number {
  return (
    db
      .prepare(`SELECT COUNT(DISTINCT user_id) n FROM activity_daily WHERE day >= @fromDay AND day <= @toDay AND last_at >= @from AND first_at <= @to AND ${NOT_EXCLUDED}`)
      .get({ fromDay: utcDay(from), toDay: utcDay(to), from, to, excluded }) as { n: number }
  ).n;
}

/** The trend of a gauge series over the last [days] days of hourly rollups: value per day (least squares), or null. */
export function dailySlope(db: DB, series: string, now: number, days: number): number | null {
  const rows = db.prepare("SELECT hour, avg FROM resource_hourly WHERE series = ? AND hour >= ? ORDER BY hour").all(series, Math.floor((now - days * DAY_MS) / 3_600_000)) as { hour: number; avg: number }[];
  if (rows.length < 24) return null;
  const xs = rows.map((r) => r.hour / 24);
  const ys = rows.map((r) => r.avg);
  const mx = xs.reduce((a, b) => a + b, 0) / xs.length;
  const my = ys.reduce((a, b) => a + b, 0) / ys.length;
  const num = xs.reduce((a, x, i) => a + (x - mx) * (ys[i]! - my), 0);
  const den = xs.reduce((a, x) => a + (x - mx) ** 2, 0);
  return den === 0 ? null : num / den;
}
