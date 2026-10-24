import type { DB } from "../../infrastructure/database/db.js";
import type { ClientInfo, Platform } from "./client.js";

/**
 * Activity and traffic counters (docs/ANALYTICS_DEFINITIONS.md). The API's own request handling feeds them (a trusted,
 * server-side signal: clients can't report activity for anyone else). They're kept in memory and written once a minute
 * in one transaction, so a request never waits on them; at most a minute of counts is lost if the process dies.
 */

/** Background traffic: refreshing tokens, fetching or acknowledging the queue, push registration, feature probes. */
const PASSIVE = new Set([
  "POST /v2/auth/refresh",
  "GET /v1/queue",
  "POST /v1/queue/ack",
  "PUT /v1/devices/current/fcm",
  "DELETE /v1/devices/current/fcm",
  "PUT /v1/devices/current/push",
  "DELETE /v1/devices/current/push",
  "GET /v1/experiments",
  "POST /v1/analytics/events",
  "GET /v1/platform/me",
  "GET /v1/presence",
  "POST /v1/reminders/:id/ack",
  "POST /v1/calls/:id/alerted",
  "GET /v1/ws",
]);

/** Server-verified meaningful actions (a message, an upload, a call, joining or creating a space). */
const ACTIONS = new Set([
  "POST /v1/attachments",
  "POST /v1/attachments/uploads/:id/complete",
  "POST /v1/calls",
  "POST /v1/calls/:id/join",
  "POST /v1/orgs",
  "POST /v1/orgs/:orgId/channels",
  "POST /v1/communities/:id/join",
  "POST /v1/invites/accept",
  "POST /v1/conversations/:id/join",
  "POST /v1/conversations/dm",
  "POST /v1/conversations/group",
]);

export type RequestKind = "passive" | "activity" | "action";

/** How a successful authenticated request counts (pure; unit-tested). Messages count only when they're messages. */
export function requestKind(method: string, route: string, body?: unknown): RequestKind {
  const key = `${method} ${route}`;
  if (PASSIVE.has(key)) return "passive";
  if (key === "POST /v1/messages") return (body as { cls?: unknown } | undefined)?.cls === "message" ? "action" : "activity";
  return ACTIONS.has(key) ? "action" : "activity";
}

/** Bytes per person and day, plus one count: "messages", message envelopes accepted (cls "message"; never their content). */
export const TRAFFIC_METRICS = ["api.in", "api.out", "ws.in", "ws.out", "files.upload", "files.download", "messages"] as const;
export type TrafficMetric = (typeof TRAFFIC_METRICS)[number];

/** People counted as "actively using" when their last qualifying request is this recent. */
export const ACTIVE_NOW_MS = 5 * 60_000;

interface DayEntry {
  day: string;
  userId: string;
  platform: Platform;
  version: string;
  firstAt: number;
  lastAt: number;
  requests: number;
  actions: number;
  firstActionAt: number | null;
}

interface SessionSeen {
  sessionId: string;
  client: ClientInfo;
  firstAt: number;
  lastAt: number;
}

export const utcDay = (t: number) => new Date(t).toISOString().slice(0, 10);

export class ActivityTracker {
  private days = new Map<string, DayEntry>();
  private traffic = new Map<string, { day: string; userId: string; metric: TrafficMetric; bytes: number }>();
  private sessions = new Map<string, SessionSeen>();
  private lastActive = new Map<string, number>();
  private lastPlatform = new Map<string, Platform>();

  /** One qualifying request (or action) by [userId] from [client] at [t]. */
  record(userId: string, client: ClientInfo, t: number, action: boolean): void {
    const day = utcDay(t);
    const key = `${day}|${userId}|${client.platform}|${client.version}`;
    const e = this.days.get(key) ?? { day, userId, platform: client.platform, version: client.version, firstAt: t, lastAt: t, requests: 0, actions: 0, firstActionAt: null };
    this.days.set(key, {
      ...e,
      lastAt: Math.max(e.lastAt, t),
      firstAt: Math.min(e.firstAt, t),
      requests: e.requests + 1,
      actions: e.actions + (action ? 1 : 0),
      firstActionAt: action ? Math.min(e.firstActionAt ?? t, t) : e.firstActionAt,
    });
    if (t >= (this.lastActive.get(userId) ?? 0)) {
      this.lastActive.set(userId, t);
      this.lastPlatform.set(userId, client.platform);
    }
  }

  addTraffic(userId: string, metric: TrafficMetric, bytes: number, t: number): void {
    if (!(bytes > 0)) return;
    const day = utcDay(t);
    const key = `${day}|${userId}|${metric}`;
    const prev = this.traffic.get(key)?.bytes ?? 0;
    this.traffic.set(key, { day, userId, metric, bytes: prev + Math.round(bytes) });
  }

  /** The client an app session was seen with (for the device inventory). */
  noteSession(sessionId: string, client: ClientInfo, t: number): void {
    const prev = this.sessions.get(sessionId);
    this.sessions.set(sessionId, { sessionId, client, firstAt: prev?.firstAt ?? t, lastAt: t });
  }

  /** People with qualifying activity since [since], overall and per platform (also prunes older entries). */
  activeSince(since: number): { users: number; platforms: Record<string, number> } {
    const platforms: Record<string, number> = {};
    let users = 0;
    for (const [userId, at] of this.lastActive) {
      if (at < since) {
        this.lastActive.delete(userId);
        this.lastPlatform.delete(userId);
        continue;
      }
      users += 1;
      const p = this.lastPlatform.get(userId) ?? "unknown";
      platforms[p] = (platforms[p] ?? 0) + 1;
    }
    return { users, platforms };
  }

  /** Whether [userId] had qualifying activity since [since]. */
  isActiveSince(userId: string, since: number): boolean {
    return (this.lastActive.get(userId) ?? 0) >= since;
  }

  /**
   * Writes everything gathered since the last flush (one transaction) and clears it. Returns rows written.
   * [signupAt]: the identities' creation times (the accounts module's), stored with each row for cohorts.
   */
  flush(db: DB, signupAt: (userIds: string[]) => Map<string, number> = () => new Map()): number {
    if (!db.open) return 0;
    const days = [...this.days.values()];
    const traffic = [...this.traffic.values()];
    const sessions = [...this.sessions.values()];
    if (days.length + traffic.length + sessions.length === 0) return 0;
    const signups = days.length ? signupAt([...new Set(days.map((d) => d.userId))]) : new Map<string, number>();
    const upDay = db.prepare(
      `INSERT INTO activity_daily (day, user_id, platform, client_version, first_at, last_at, requests, actions, first_action_at, signup_at)
       VALUES (@day, @userId, @platform, @version, @firstAt, @lastAt, @requests, @actions, @firstActionAt, @signupAt)
       ON CONFLICT (day, user_id, platform, client_version) DO UPDATE SET
         first_at = MIN(first_at, excluded.first_at), last_at = MAX(last_at, excluded.last_at),
         requests = requests + excluded.requests, actions = actions + excluded.actions,
         first_action_at = COALESCE(MIN(first_action_at, excluded.first_action_at), first_action_at, excluded.first_action_at),
         signup_at = COALESCE(signup_at, excluded.signup_at)`,
    );
    const upTraffic = db.prepare(
      `INSERT INTO traffic_user_daily (day, user_id, metric, value) VALUES (?, ?, ?, ?)
       ON CONFLICT (day, user_id, metric) DO UPDATE SET value = value + excluded.value`,
    );
    const upSession = db.prepare(
      `INSERT INTO session_clients (session_id, platform, client, client_version, os, first_seen_at, last_seen_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT (session_id) DO UPDATE SET platform = excluded.platform, client = excluded.client,
         client_version = excluded.client_version, os = excluded.os, last_seen_at = MAX(last_seen_at, excluded.last_seen_at)`,
    );
    db.transaction(() => {
      for (const d of days) upDay.run({ ...d, signupAt: signups.get(d.userId) ?? null });
      for (const x of traffic) upTraffic.run(x.day, x.userId, x.metric, x.bytes);
      for (const s of sessions) upSession.run(s.sessionId, s.client.platform, s.client.client, s.client.version, s.client.os, s.firstAt, s.lastAt);
    })();
    // Cleared only once written: a failed write keeps the counts for the next flush (synchronous, so nothing arrives
    // in between).
    this.days = new Map();
    this.traffic = new Map();
    this.sessions = new Map();
    return days.length + traffic.length + sessions.length;
  }
}
