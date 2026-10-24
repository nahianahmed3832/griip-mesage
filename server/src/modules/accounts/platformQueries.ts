import type { DB } from "../../infrastructure/database/db.js";

/**
 * What the Platform Admin Console (modules/platformAdmin) may read about people: identity metadata, devices and
 * counts. Never keys, certificates, profile fields people restrict with privacy settings, or anything they wrote.
 * Federated shadows of other servers' users are left out.
 */
export type UserStatus = "active" | "suspended" | "deleted";

export interface UserSummaryRow {
  id: string;
  username: string;
  display_name: string;
  created_at: number;
  disabled_at: number | null;
  deleted_at: number | null;
  limited_until: number | null;
  active_devices: number;
  last_seen_at: number | null;
}

export interface DeviceSummaryRow {
  id: string;
  name: string;
  created_at: number;
  last_seen_at: number | null;
  revoked_at: number | null;
}

export interface UserCounts {
  total: number;
  suspended: number;
  deleted: number;
  seen24h: number;
  seen30d: number;
  newPerDay: { day: string; count: number }[];
}

const DAY_MS = 86_400_000;
const SUMMARY = `SELECT u.id, u.username, u.display_name, u.created_at, u.disabled_at, u.deleted_at, u.limited_until,
    (SELECT COUNT(*) FROM devices d WHERE d.user_id = u.id AND d.revoked_at IS NULL) AS active_devices,
    (SELECT MAX(d.last_seen_at) FROM devices d WHERE d.user_id = u.id) AS last_seen_at
  FROM users u`;

const STATUS_SQL: Record<UserStatus, string> = {
  active: "u.disabled_at IS NULL AND u.deleted_at IS NULL",
  suspended: "u.disabled_at IS NOT NULL AND u.deleted_at IS NULL",
  deleted: "u.deleted_at IS NOT NULL",
};

/** "%" and "_" typed by an admin are literal characters, not wildcards. */
const likeEscape = (s: string) => s.replace(/[\\%_]/g, (c) => `\\${c}`);

/**
 * People, newest first, a page at a time. [q]: the start of a username, part of a display name, or an exact user id.
 * [after]: the last row of the previous page (keyset pagination: stable while people sign up).
 */
export function searchUsers(
  db: DB,
  opts: { q?: string; status?: UserStatus; after?: { createdAt: number; id: string }; limit: number; ids?: string[]; notIds?: string[]; createdFrom?: number; createdTo?: number },
): UserSummaryRow[] {
  const where = ["u.home_server_id IS NULL"];
  const args: (string | number)[] = [];
  if (opts.q) {
    where.push("(u.username LIKE ? ESCAPE '\\' OR u.display_name LIKE ? ESCAPE '\\' OR u.id = ?)");
    args.push(`${likeEscape(opts.q)}%`, `%${likeEscape(opts.q)}%`, opts.q);
  }
  if (opts.ids) {
    if (opts.ids.length === 0) return [];
    where.push("u.id IN (SELECT value FROM json_each(?))");
    args.push(JSON.stringify(opts.ids));
  }
  if (opts.notIds?.length) {
    where.push("u.id NOT IN (SELECT value FROM json_each(?))");
    args.push(JSON.stringify(opts.notIds));
  }
  if (opts.createdFrom !== undefined) {
    where.push("u.created_at >= ?");
    args.push(opts.createdFrom);
  }
  if (opts.createdTo !== undefined) {
    where.push("u.created_at < ?");
    args.push(opts.createdTo);
  }
  if (opts.status) where.push(STATUS_SQL[opts.status]);
  if (opts.after) {
    where.push("(u.created_at < ? OR (u.created_at = ? AND u.id < ?))");
    args.push(opts.after.createdAt, opts.after.createdAt, opts.after.id);
  }
  return db.prepare(`${SUMMARY} WHERE ${where.join(" AND ")} ORDER BY u.created_at DESC, u.id DESC LIMIT ?`).all(...args, opts.limit) as UserSummaryRow[];
}

export function userSummary(db: DB, userId: string): UserSummaryRow | undefined {
  return db.prepare(`${SUMMARY} WHERE u.id = ? AND u.home_server_id IS NULL`).get(userId) as UserSummaryRow | undefined;
}

/** The person's devices, newest first: names they chose, when they were added and last seen, and revocations. */
export function userDevices(db: DB, userId: string): DeviceSummaryRow[] {
  return db.prepare("SELECT id, name, created_at, last_seen_at, revoked_at FROM devices WHERE user_id = ? ORDER BY created_at DESC LIMIT 50").all(userId) as DeviceSummaryRow[];
}

/** Usernames for [ids] (owners of a community, an incident's person). */
export function usernames(db: DB, ids: string[]): Map<string, string> {
  if (ids.length === 0) return new Map();
  const rows = db.prepare(`SELECT id, username FROM users WHERE id IN (${ids.map(() => "?").join(",")})`).all(...ids) as { id: string; username: string }[];
  return new Map(rows.map((r) => [r.id, r.username]));
}

/** "Seen" is a device's last sign-in or connection (devices.last_seen_at): an approximate activity signal. */
export function userCounts(db: DB, t: number, days: number): UserCounts {
  const one = (sql: string, ...args: number[]) => (db.prepare(sql).get(...args) as { n: number }).n;
  const seen = (since: number) =>
    one("SELECT COUNT(DISTINCT d.user_id) n FROM devices d JOIN users u ON u.id = d.user_id WHERE d.revoked_at IS NULL AND d.last_seen_at > ? AND u.home_server_id IS NULL AND u.deleted_at IS NULL", since);
  const from = t - days * DAY_MS;
  const perDay = db
    .prepare("SELECT strftime('%Y-%m-%d', created_at / 1000, 'unixepoch') AS day, COUNT(*) AS count FROM users WHERE created_at > ? AND home_server_id IS NULL GROUP BY day ORDER BY day")
    .all(from) as { day: string; count: number }[];
  return {
    total: one("SELECT COUNT(*) n FROM users WHERE home_server_id IS NULL AND deleted_at IS NULL"),
    suspended: one("SELECT COUNT(*) n FROM users WHERE home_server_id IS NULL AND deleted_at IS NULL AND disabled_at IS NOT NULL"),
    deleted: one("SELECT COUNT(*) n FROM users WHERE home_server_id IS NULL AND deleted_at IS NOT NULL"),
    seen24h: seen(t - DAY_MS),
    seen30d: seen(t - 30 * DAY_MS),
    newPerDay: perDay,
  };
}

/** Sets or clears ([at] null) an identity's suspension (the same column `admin.js disable` sets); true when it changed. */
export function setIdentitySuspended(db: DB, userId: string, at: number | null): boolean {
  if (at === null) return db.prepare("UPDATE users SET disabled_at = NULL WHERE id = ? AND disabled_at IS NOT NULL AND deleted_at IS NULL").run(userId).changes > 0;
  return db.prepare("UPDATE users SET disabled_at = ? WHERE id = ? AND disabled_at IS NULL AND deleted_at IS NULL").run(at, userId).changes > 0;
}

/** When each identity in [ids] was created (telemetry stores it with activity rows, for cohorts). */
export function signupTimes(db: DB, ids: string[]): Map<string, number> {
  const out = new Map<string, number>();
  for (let i = 0; i < ids.length; i += 500) {
    const chunk = ids.slice(i, i + 500);
    const rows = db.prepare(`SELECT id, created_at FROM users WHERE id IN (${chunk.map(() => "?").join(",")})`).all(...chunk) as { id: string; created_at: number }[];
    for (const r of rows) out.set(r.id, r.created_at);
  }
  return out;
}

/** Synthetic (load-test) identities: always left out of analytics. */
export function syntheticUserIds(db: DB): string[] {
  return (db.prepare("SELECT id FROM users WHERE synthetic = 1").all() as { id: string }[]).map((r) => r.id);
}

/**
 * Sign-ups per UTC day in [from, to) (ms), [excluded] left out (a JSON array of user ids): the denominators of
 * activation and retention. Deleted accounts count: they signed up.
 */
export function signupsPerDay(db: DB, from: number, to: number, excluded: string): { day: string; count: number }[] {
  return db
    .prepare(
      `SELECT strftime('%Y-%m-%d', created_at / 1000, 'unixepoch') AS day, COUNT(*) AS count FROM users
        WHERE created_at >= ? AND created_at < ? AND home_server_id IS NULL AND id NOT IN (SELECT value FROM json_each(?))
        GROUP BY day ORDER BY day`,
    )
    .all(from, to, excluded) as { day: string; count: number }[];
}

/**
 * Identities that can be used now (not suspended, deleted or synthetic), synthetic ones, and new accounts still under
 * the new-account limits (limited_until).
 */
export function accountStateCounts(db: DB, t: number): { usable: number; synthetic: number; newAccountLimited: number } {
  const one = (sql: string, ...a: number[]) => (db.prepare(sql).get(...a) as { n: number }).n;
  return {
    usable: one("SELECT COUNT(*) n FROM users WHERE home_server_id IS NULL AND deleted_at IS NULL AND disabled_at IS NULL AND synthetic = 0"),
    synthetic: one("SELECT COUNT(*) n FROM users WHERE home_server_id IS NULL AND deleted_at IS NULL AND synthetic = 1"),
    newAccountLimited: one("SELECT COUNT(*) n FROM users WHERE home_server_id IS NULL AND deleted_at IS NULL AND limited_until IS NOT NULL AND limited_until > ?", t),
  };
}

/** Identities created since [since] (ms), synthetic ones left out (alert rules: a sign-up spike). */
export function signupsSince(db: DB, since: number): number {
  return (db.prepare("SELECT COUNT(*) n FROM users WHERE created_at > ? AND home_server_id IS NULL AND synthetic = 0").get(since) as { n: number }).n;
}

export interface DeviceInventoryRow {
  id: string;
  user_id: string;
  username: string | null;
  name: string;
  created_at: number;
  last_seen_at: number | null;
  revoked_at: number | null;
}

/**
 * Devices across everyone, newest first, a page at a time ([after]: the previous page's last row). [staleBefore]:
 * only devices not seen since then. Federated shadows are left out.
 */
export function devicesPage(
  db: DB,
  opts: { status?: "active" | "revoked"; staleBefore?: number; ids?: string[]; after?: { createdAt: number; id: string }; limit: number },
): DeviceInventoryRow[] {
  const where = ["u.home_server_id IS NULL"];
  const args: (string | number)[] = [];
  if (opts.status === "active") where.push("d.revoked_at IS NULL");
  if (opts.status === "revoked") where.push("d.revoked_at IS NOT NULL");
  if (opts.staleBefore !== undefined) {
    where.push("d.revoked_at IS NULL AND COALESCE(d.last_seen_at, d.created_at) < ?");
    args.push(opts.staleBefore);
  }
  if (opts.ids) {
    where.push("d.id IN (SELECT value FROM json_each(?))");
    args.push(JSON.stringify(opts.ids));
  }
  if (opts.after) {
    where.push("(d.created_at < ? OR (d.created_at = ? AND d.id < ?))");
    args.push(opts.after.createdAt, opts.after.createdAt, opts.after.id);
  }
  return db
    .prepare(
      `SELECT d.id, d.user_id, u.username, d.name, d.created_at, d.last_seen_at, d.revoked_at FROM devices d JOIN users u ON u.id = d.user_id
        WHERE ${where.join(" AND ")} ORDER BY d.created_at DESC, d.id DESC LIMIT ?`,
    )
    .all(...args, opts.limit) as DeviceInventoryRow[];
}

export function deviceCounts(db: DB): { active: number; revoked: number } {
  return db.prepare("SELECT COALESCE(SUM(revoked_at IS NULL), 0) active, COALESCE(SUM(revoked_at IS NOT NULL), 0) revoked FROM devices").get() as { active: number; revoked: number };
}

/** A device of [userId], if it is one of theirs. */
export function deviceOf(db: DB, userId: string, deviceId: string): DeviceInventoryRow | undefined {
  return db
    .prepare("SELECT d.id, d.user_id, u.username, d.name, d.created_at, d.last_seen_at, d.revoked_at FROM devices d JOIN users u ON u.id = d.user_id WHERE d.id = ? AND d.user_id = ?")
    .get(deviceId, userId) as DeviceInventoryRow | undefined;
}

/** The most recently used app session of each device (SQLite account store; empty with accounts in MongoDB). */
export function latestSessionOfDevices(db: DB, deviceIds: string[]): Map<string, string> {
  if (deviceIds.length === 0) return new Map();
  const rows = db
    .prepare(
      `SELECT device_id, id FROM account_sessions s WHERE device_id IN (SELECT value FROM json_each(?))
        AND last_used_at = (SELECT MAX(last_used_at) FROM account_sessions s2 WHERE s2.device_id = s.device_id)`,
    )
    .all(JSON.stringify(deviceIds)) as { device_id: string; id: string }[];
  return new Map(rows.map((r) => [r.device_id, r.id]));
}

/** Identities created in [from, to) (ms), synthetic ones left out. */
export function signupsBetween(db: DB, from: number, to: number): number {
  return (db.prepare("SELECT COUNT(*) n FROM users WHERE created_at >= ? AND created_at < ? AND home_server_id IS NULL AND synthetic = 0").get(from, to) as { n: number }).n;
}
