/**
 * Reminder times (docs/REMINDERS.md): every instant is UTC milliseconds; a repeating reminder keeps its local wall-clock
 * time in an IANA time zone, so "09:00 every weekday in Asia/Dhaka" stays at 09:00 across daylight-saving changes.
 * Pure functions on top of the runtime's time-zone database (Intl), unit-tested in test/reminders_schedule.test.ts.
 */

export const RECURRENCES = ["daily", "weekdays", "weekly", "monthly"] as const;
export type Recurrence = (typeof RECURRENCES)[number];

/** A repeating reminder's rule: the local time and the first local date it applies from. */
export interface RepeatRule {
  recurrence: Recurrence;
  /** "HH:MM", 24-hour. */
  localTime: string;
  /** "YYYY-MM-DD": the first occurrence's local date (weekly: its weekday; monthly: its day of the month). */
  anchorDate: string;
  timeZone: string;
}

interface CivilDate {
  y: number;
  m: number; // 1–12
  d: number;
}

const MINUTE_MS = 60_000;
const DAY_MS = 86_400_000;
/** How far ahead the next occurrence is searched (a monthly rule on the 31st finds one well within this). */
const SEARCH_DAYS = 400;

export const LOCAL_TIME = /^([01]\d|2[0-3]):([0-5]\d)$/;
export const LOCAL_DATE = /^(\d{4})-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/;

const formatters = new Map<string, Intl.DateTimeFormat>();

function formatter(timeZone: string): Intl.DateTimeFormat {
  let f = formatters.get(timeZone);
  if (!f) {
    f = new Intl.DateTimeFormat("en-US", {
      timeZone,
      hourCycle: "h23",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
    formatters.set(timeZone, f);
  }
  return f;
}

/** Whether [tz] is an IANA zone this runtime knows ("Asia/Dhaka", "UTC"). */
export function isValidTimeZone(tz: string): boolean {
  if (tz.length === 0 || tz.length > 64) return false;
  try {
    formatter(tz);
    return true;
  } catch {
    return false;
  }
}

/** The wall-clock fields of instant [ms] in [tz]. */
export function localParts(ms: number, tz: string): { y: number; m: number; d: number; h: number; mi: number; s: number } {
  const p: Record<string, number> = {};
  for (const part of formatter(tz).formatToParts(new Date(ms))) if (part.type !== "literal") p[part.type] = Number(part.value);
  return { y: p.year, m: p.month, d: p.day, h: p.hour === 24 ? 0 : p.hour, mi: p.minute, s: p.second };
}

/** [tz]'s offset from UTC at instant [ms] (local minus UTC), in milliseconds. */
function offsetAt(ms: number, tz: string): number {
  const l = localParts(ms, tz);
  const asUtc = Date.UTC(l.y, l.m - 1, l.d, l.h, l.mi, l.s);
  return asUtc - Math.floor(ms / 1000) * 1000;
}

/**
 * The instant at which [tz]'s clocks show [date] [hh]:[mm]. A time skipped by a clock change ("02:30" on a
 * spring-forward night) lands just after the gap; a time that happens twice takes the first.
 */
export function zonedToUtc(date: CivilDate, hh: number, mm: number, tz: string): number {
  const wall = Date.UTC(date.y, date.m - 1, date.d, hh, mm);
  const first = wall - offsetAt(wall, tz);
  const second = wall - offsetAt(first, tz);
  if (first === second) return first;
  // Around a change: prefer the reading whose local time is the one asked for, the earlier one when both are.
  const ok = [Math.min(first, second), Math.max(first, second)].find((t) => {
    const l = localParts(t, tz);
    return l.h === hh && l.mi === mm;
  });
  return ok ?? Math.max(first, second);
}

function parseDate(s: string): CivilDate {
  const m = LOCAL_DATE.exec(s);
  if (!m) throw new Error(`bad date ${s}`);
  return { y: Number(m[1]), m: Number(m[2]), d: Number(m[3]) };
}

function parseTime(s: string): { hh: number; mm: number } {
  const m = LOCAL_TIME.exec(s);
  if (!m) throw new Error(`bad time ${s}`);
  return { hh: Number(m[1]), mm: Number(m[2]) };
}

/** Days since the Unix epoch of a civil date (calendar arithmetic only, no time zone involved). */
const dayNumber = (c: CivilDate) => Math.floor(Date.UTC(c.y, c.m - 1, c.d) / DAY_MS);

function fromDayNumber(n: number): CivilDate {
  const dt = new Date(n * DAY_MS);
  return { y: dt.getUTCFullYear(), m: dt.getUTCMonth() + 1, d: dt.getUTCDate() };
}

/** 0 = Sunday … 6 = Saturday. */
const weekday = (c: CivilDate) => new Date(Date.UTC(c.y, c.m - 1, c.d)).getUTCDay();
const daysInMonth = (y: number, m: number) => new Date(Date.UTC(y, m, 0)).getUTCDate();

function matches(rule: RepeatRule, anchor: CivilDate, c: CivilDate): boolean {
  switch (rule.recurrence) {
    case "daily":
      return true;
    case "weekdays": {
      const w = weekday(c);
      return w >= 1 && w <= 5;
    }
    case "weekly":
      return weekday(c) === weekday(anchor);
    case "monthly":
      // The 31st falls on the last day of shorter months.
      return c.d === Math.min(anchor.d, daysInMonth(c.y, c.m));
  }
}

/** Whether a repeat rule is well formed (zone, time and date), before it is stored. */
export function validRule(rule: RepeatRule): boolean {
  return (RECURRENCES as readonly string[]).includes(rule.recurrence) && LOCAL_TIME.test(rule.localTime) && LOCAL_DATE.test(rule.anchorDate) && isValidTimeZone(rule.timeZone);
}

/**
 * The first occurrence of [rule] strictly after [after] (never before its anchor date). Missed occurrences are not
 * replayed: a server that was down for three days fires a daily reminder once, then continues from today.
 */
export function nextOccurrence(rule: RepeatRule, after: number): number {
  const anchor = parseDate(rule.anchorDate);
  const { hh, mm } = parseTime(rule.localTime);
  const today = localParts(after, rule.timeZone);
  const start = Math.max(dayNumber({ y: today.y, m: today.m, d: today.d }) - 1, dayNumber(anchor));
  for (let n = start; n < start + SEARCH_DAYS; n++) {
    const c = fromDayNumber(n);
    if (!matches(rule, anchor, c)) continue;
    const t = zonedToUtc(c, hh, mm, rule.timeZone);
    if (t > after) return t;
  }
  throw new Error("no occurrence found");
}

/** Bounded exponential backoff for a failed delivery attempt: 30 s, 1 min, 2 min … at most 1 hour. */
export function retryDelayMs(attempt: number): number {
  return Math.min(30_000 * 2 ** Math.max(attempt - 1, 0), 60 * MINUTE_MS);
}
