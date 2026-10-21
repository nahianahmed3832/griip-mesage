import { localParts, zonedToUtc } from "../reminders/schedule.js";

/**
 * Batch B8b (Discord reference EVENT-CTRL-002 "Event Frequency"): how a community event repeats. Each occurrence keeps
 * the first one's wall-clock time in the event's time zone, so it stays at 18:00 across daylight-saving changes.
 * "monthly" is the same weekday of the month (the third Wednesday; a fifth one that a month lacks becomes the last).
 */
export const EVENT_REPEATS = ["weekly", "biweekly", "monthly", "yearly"] as const;
export type EventRepeat = (typeof EVENT_REPEATS)[number];

interface Civil {
  y: number;
  m: number;
  d: number;
}

const DAY_MS = 86_400_000;
const civilToDays = (c: Civil) => Date.UTC(c.y, c.m - 1, c.d) / DAY_MS;
const daysToCivil = (days: number): Civil => {
  const d = new Date(days * DAY_MS);
  return { y: d.getUTCFullYear(), m: d.getUTCMonth() + 1, d: d.getUTCDate() };
};
const weekday = (c: Civil) => new Date(Date.UTC(c.y, c.m - 1, c.d)).getUTCDay();
const daysInMonth = (y: number, m: number) => new Date(Date.UTC(y, m, 0)).getUTCDate();

/** The [n]th (1–5) [wd] weekday of month [m] of [y]; the last one when the month has fewer. */
function nthWeekday(y: number, m: number, wd: number, n: number): Civil {
  const first = weekday({ y, m, d: 1 });
  let d = 1 + ((wd - first + 7) % 7) + (n - 1) * 7;
  while (d > daysInMonth(y, m)) d -= 7;
  return { y, m, d };
}

/** The local date of the occurrence after the one on [c]. */
function nextDate(c: Civil, repeat: EventRepeat): Civil {
  switch (repeat) {
    case "weekly":
      return daysToCivil(civilToDays(c) + 7);
    case "biweekly":
      return daysToCivil(civilToDays(c) + 14);
    case "monthly": {
      const n = Math.ceil(c.d / 7);
      const [y, m] = c.m === 12 ? [c.y + 1, 1] : [c.y, c.m + 1];
      return nthWeekday(y, m, weekday(c), n);
    }
    case "yearly":
      return { y: c.y + 1, m: c.m, d: Math.min(c.d, daysInMonth(c.y + 1, c.m)) };
  }
}

/** When the occurrence after the one starting at [startsAt] starts, at the same local time in [tz]. */
export function nextEventStart(startsAt: number, repeat: EventRepeat, tz: string): number {
  const l = localParts(startsAt, tz);
  // Seconds and milliseconds carry over unchanged (time-zone offsets are whole minutes).
  return zonedToUtc(nextDate({ y: l.y, m: l.m, d: l.d }, repeat), l.h, l.mi, tz) + (((startsAt % 60_000) + 60_000) % 60_000);
}

/** The first occurrence that hasn't ended by [now] (the event lasts [durationMs]); [startsAt] itself if it hasn't. */
export function currentOccurrence(startsAt: number, durationMs: number, repeat: EventRepeat, tz: string, now: number): number {
  let s = startsAt;
  // Bounded: an event far in the past (a server that was off for years) still stops after a few hundred steps.
  for (let i = 0; i < 600 && s + durationMs <= now; i++) s = nextEventStart(s, repeat, tz);
  return s;
}
