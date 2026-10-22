import type { AppContext } from "../../../app/context.js";
import { ApiError, now } from "../../../shared/utilities/util.js";
import { nextOccurrence, type Recurrence, type RepeatRule, retryDelayMs } from "./schedule.js";

/** Reminders a person may have waiting (scheduled or fired) at once. */
export const MAX_ACTIVE_REMINDERS = 500;
/** Failed delivery attempts of one occurrence before it is marked failed and moved on. */
const MAX_ATTEMPTS = 8;
/** Due reminders handled per tick (the rest wait for the next one). */
const BATCH = 200;
/** Fired occurrences are remembered this long (dedupe), then forgotten. */
const OCCURRENCE_KEEP_MS = 30 * 86_400_000;
/** Done and dismissed reminders are kept this long for the Done list, then removed. */
const FINISHED_KEEP_MS = 30 * 86_400_000;

export type ReminderState = "scheduled" | "fired" | "done" | "dismissed";

export interface ReminderRow {
  id: string;
  user_id: string;
  sealed: string;
  due_at: number;
  time_zone: string;
  recurrence: Recurrence | null;
  local_time: string | null;
  anchor_date: string | null;
  state: ReminderState;
  fired_at: number | null;
  delivered_at: number | null;
  attempts: number;
  next_attempt_at: number | null;
  last_error: string | null;
  rev: number;
  created_at: number;
  updated_at: number;
  completed_at: number | null;
}

export interface ReminderView {
  id: string;
  sealed: string;
  dueAt: number;
  timeZone: string;
  repeat: { recurrence: Recurrence; localTime: string; anchorDate: string } | null;
  state: ReminderState;
  firedAt: number | null;
  deliveredAt: number | null;
  /** "failed" after every delivery attempt of the last occurrence failed; null otherwise. */
  delivery: "failed" | null;
  rev: number;
  createdAt: number;
  updatedAt: number;
  completedAt: number | null;
}

export interface ReminderInput {
  id: string;
  sealed: string;
  dueAt: number;
  timeZone: string;
  repeat?: { recurrence: Recurrence; localTime: string; anchorDate: string } | null;
}

export interface ReminderPatch {
  rev: number;
  sealed?: string;
  dueAt?: number;
  timeZone?: string;
  repeat?: { recurrence: Recurrence; localTime: string; anchorDate: string } | null;
  state?: ReminderState;
}

export interface TickResult {
  fired: number;
  retried: number;
  failed: number;
}

export function view(r: ReminderRow): ReminderView {
  return {
    id: r.id,
    sealed: r.sealed,
    dueAt: r.due_at,
    timeZone: r.time_zone,
    repeat: r.recurrence && r.local_time && r.anchor_date ? { recurrence: r.recurrence, localTime: r.local_time, anchorDate: r.anchor_date } : null,
    state: r.state,
    firedAt: r.fired_at,
    deliveredAt: r.delivered_at,
    delivery: r.last_error === "failed" ? "failed" : null,
    rev: r.rev,
    createdAt: r.created_at,
    updatedAt: r.updated_at,
    completedAt: r.completed_at,
  };
}

const ruleOf = (r: Pick<ReminderRow, "recurrence" | "local_time" | "anchor_date" | "time_zone">): RepeatRule | null =>
  r.recurrence && r.local_time && r.anchor_date ? { recurrence: r.recurrence, localTime: r.local_time, anchorDate: r.anchor_date, timeZone: r.time_zone } : null;

const notFound = () => new ApiError(404, "reminder_not_found", "Reminder not found");

/**
 * Reminders kept and fired by the server (docs/REMINDERS.md): durable (rows, not timers), idempotent (one fire per
 * occurrence, recorded before it is delivered), retried with bounded backoff, and only ever about the owner's own
 * sealed content. Delivery is a WebSocket event to the person's connected devices plus a payload-free push wake-up for
 * the others; the app then shows the reminder from its own decrypted copy.
 */
export class ReminderService {
  private timer: NodeJS.Timeout | null = null;
  private running = false;

  constructor(private readonly ctx: AppContext) {}

  private row(userId: string, id: string): ReminderRow {
    const r = this.ctx.db.prepare("SELECT * FROM reminders WHERE id = ? AND user_id = ?").get(id, userId) as ReminderRow | undefined;
    if (!r) throw notFound();
    return r;
  }

  list(userId: string, state?: ReminderState): ReminderView[] {
    const rows = (
      state
        ? this.ctx.db.prepare("SELECT * FROM reminders WHERE user_id = ? AND state = ? ORDER BY due_at, id LIMIT 1000").all(userId, state)
        : this.ctx.db.prepare("SELECT * FROM reminders WHERE user_id = ? ORDER BY due_at, id LIMIT 1000").all(userId)
    ) as ReminderRow[];
    return rows.map(view);
  }

  get(userId: string, id: string): ReminderView {
    return view(this.row(userId, id));
  }

  /** Creates [input] (or returns it unchanged when the same id was already created by this person: a retried request). */
  create(userId: string, deviceId: string, input: ReminderInput): ReminderView {
    const existing = this.ctx.db.prepare("SELECT * FROM reminders WHERE id = ?").get(input.id) as ReminderRow | undefined;
    if (existing) {
      if (existing.user_id !== userId) throw new ApiError(409, "reminder_id_taken", "Choose another reminder id");
      return view(existing);
    }
    const active = (this.ctx.db.prepare("SELECT COUNT(*) AS n FROM reminders WHERE user_id = ? AND state IN ('scheduled','fired')").get(userId) as { n: number }).n;
    if (active >= MAX_ACTIVE_REMINDERS) throw new ApiError(409, "too_many_reminders", `You can have up to ${MAX_ACTIVE_REMINDERS} reminders waiting. Finish or delete some first.`);
    const t = now();
    const rule = input.repeat ? { ...input.repeat, timeZone: input.timeZone } : null;
    // A repeating reminder's first due time comes from its rule (so it matches the local time exactly), and is never
    // an occurrence that already passed.
    const dueAt = rule ? nextOccurrence(rule, Math.max(t, input.dueAt - 1)) : input.dueAt;
    this.ctx.db
      .prepare(
        `INSERT INTO reminders (id, user_id, sealed, due_at, time_zone, recurrence, local_time, anchor_date, state, rev, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'scheduled', 1, ?, ?)`,
      )
      .run(input.id, userId, input.sealed, dueAt, input.timeZone, rule?.recurrence ?? null, rule?.localTime ?? null, rule?.anchorDate ?? null, t, t);
    const created = this.row(userId, input.id);
    this.changed(userId, created, deviceId);
    return view(created);
  }

  /**
   * Edits, snoozes, reschedules, completes or dismisses. [patch.rev] must be the current one (another device may have
   * changed it meanwhile: 409 with the current copy). A new due time or rule puts the reminder back to scheduled.
   */
  update(userId: string, deviceId: string, id: string, patch: ReminderPatch): ReminderView {
    const r = this.row(userId, id);
    if (r.rev !== patch.rev) throw new ApiError(409, "stale_reminder", "This reminder changed on another device.", { current: view(r) });
    const t = now();
    const timeZone = patch.timeZone ?? r.time_zone;
    const repeat = patch.repeat === undefined ? (ruleOf(r) ? { recurrence: r.recurrence!, localTime: r.local_time!, anchorDate: r.anchor_date! } : null) : patch.repeat;
    const rescheduled = patch.dueAt !== undefined || patch.repeat !== undefined || patch.timeZone !== undefined;
    let dueAt = patch.dueAt ?? r.due_at;
    if (repeat && rescheduled && patch.dueAt === undefined) dueAt = nextOccurrence({ ...repeat, timeZone }, t);
    let state: ReminderState = patch.state ?? r.state;
    if (rescheduled && patch.state === undefined) state = "scheduled";
    const finished = state === "done" || state === "dismissed";
    const res = this.ctx.db
      .prepare(
        `UPDATE reminders SET sealed = ?, due_at = ?, time_zone = ?, recurrence = ?, local_time = ?, anchor_date = ?, state = ?,
           attempts = CASE WHEN ? THEN 0 ELSE attempts END, next_attempt_at = CASE WHEN ? THEN NULL ELSE next_attempt_at END,
           last_error = CASE WHEN ? THEN NULL ELSE last_error END,
           completed_at = ?, rev = rev + 1, updated_at = ? WHERE id = ? AND user_id = ? AND rev = ?`,
      )
      .run(
        patch.sealed ?? r.sealed,
        dueAt,
        timeZone,
        repeat?.recurrence ?? null,
        repeat?.localTime ?? null,
        repeat?.anchorDate ?? null,
        state,
        rescheduled ? 1 : 0,
        rescheduled ? 1 : 0,
        rescheduled ? 1 : 0,
        finished ? (r.completed_at ?? t) : null,
        t,
        id,
        userId,
        patch.rev,
      );
    if (res.changes !== 1) throw new ApiError(409, "stale_reminder", "This reminder changed on another device.", { current: view(this.row(userId, id)) });
    const updated = this.row(userId, id);
    this.changed(userId, updated, deviceId);
    return view(updated);
  }

  delete(userId: string, deviceId: string, id: string): void {
    const r = this.row(userId, id);
    this.ctx.db.prepare("DELETE FROM reminders WHERE id = ? AND user_id = ?").run(id, userId);
    this.ctx.hub.sendToUser(userId, { t: "reminders.changed", id: r.id, deleted: true }, deviceId);
  }

  /** A device showed the occurrence that fired at [firedAt]. */
  ack(userId: string, id: string, firedAt: number): void {
    const r = this.row(userId, id);
    if (r.fired_at !== firedAt) return;
    this.ctx.db.prepare("UPDATE reminders SET delivered_at = ? WHERE id = ? AND user_id = ? AND fired_at = ?").run(now(), id, userId, firedAt);
  }

  private changed(userId: string, r: ReminderRow, exceptDeviceId?: string): void {
    this.ctx.hub.sendToUser(userId, { t: "reminders.changed", id: r.id, rev: r.rev }, exceptDeviceId);
  }

  // ───────────── Firing ─────────────

  /** Fires every reminder that is due at [t]. Safe to run twice for the same time or after a crash. */
  tick(t = now()): TickResult {
    const result: TickResult = { fired: 0, retried: 0, failed: 0 };
    const due = this.ctx.db
      .prepare(
        "SELECT * FROM reminders WHERE state = 'scheduled' AND due_at <= ? AND (next_attempt_at IS NULL OR next_attempt_at <= ?) ORDER BY due_at LIMIT ?",
      )
      .all(t, t, BATCH) as ReminderRow[];
    for (const r of due) {
      try {
        this.fire(r, t);
        result.fired++;
      } catch (err) {
        const attempts = r.attempts + 1;
        const gaveUp = attempts >= MAX_ATTEMPTS;
        this.ctx.log.warn({ reminderId: r.id, attempts, errorType: err instanceof Error ? err.name : typeof err }, "reminder delivery failed");
        if (gaveUp) {
          // Move on (a repeating reminder to its next occurrence) rather than retry for ever; the app shows "failed".
          this.advance(r, t, "failed");
          result.failed++;
        } else {
          this.ctx.db.prepare("UPDATE reminders SET attempts = ?, next_attempt_at = ?, last_error = 'retrying' WHERE id = ? AND rev = ?").run(attempts, t + retryDelayMs(attempts), r.id, r.rev);
          result.retried++;
        }
      }
    }
    return result;
  }

  /**
   * One occurrence: recorded first (so a crash after delivering can't deliver it again), delivered, then the reminder
   * moves on. The record and the move are one transaction; delivery is to whoever is reachable right now.
   */
  private fire(r: ReminderRow, t: number): void {
    const firstTime = this.ctx.db.transaction(() => {
      const inserted = this.ctx.db.prepare("INSERT OR IGNORE INTO reminder_occurrences (reminder_id, occurrence_at, fired_at) VALUES (?, ?, ?)").run(r.id, r.due_at, t).changes === 1;
      this.advance(r, t, null);
      return inserted;
    })();
    if (!firstTime) return;
    // Best effort from here: the fired state is stored, so a device that misses this event still finds the reminder
    // when it next syncs (GET /v1/reminders), and shows it then.
    try {
      this.ctx.hub.sendToUser(r.user_id, { t: "reminder.due", id: r.id, occurrenceAt: r.due_at, firedAt: t });
      this.ctx.push?.remind(r.user_id);
      this.ctx.fcm?.wakeUser(r.user_id);
    } catch (err) {
      this.ctx.log.warn({ reminderId: r.id, errorType: err instanceof Error ? err.name : typeof err }, "reminder event not sent");
    }
  }

  private advance(r: ReminderRow, t: number, error: "failed" | null): void {
    const rule = ruleOf(r);
    if (rule) {
      this.ctx.db
        .prepare("UPDATE reminders SET due_at = ?, fired_at = ?, attempts = 0, next_attempt_at = NULL, last_error = ?, rev = rev + 1, updated_at = ? WHERE id = ? AND rev = ?")
        .run(nextOccurrence(rule, Math.max(t, r.due_at)), t, error, t, r.id, r.rev);
    } else {
      this.ctx.db
        .prepare("UPDATE reminders SET state = 'fired', fired_at = ?, attempts = 0, next_attempt_at = NULL, last_error = ?, rev = rev + 1, updated_at = ? WHERE id = ? AND rev = ?")
        .run(t, error, t, r.id, r.rev);
    }
  }

  /** Old occurrence records and long-finished reminders (run by the cleanup job). */
  purge(t = now()): number {
    const occ = this.ctx.db.prepare("DELETE FROM reminder_occurrences WHERE fired_at <= ?").run(t - OCCURRENCE_KEEP_MS).changes;
    const finished = this.ctx.db.prepare("DELETE FROM reminders WHERE state IN ('done','dismissed') AND completed_at <= ?").run(t - FINISHED_KEEP_MS).changes;
    return occ + finished;
  }

  /** Starts the firing loop (every [intervalMs]); stop() ends it. A tick never overlaps the previous one. */
  start(intervalMs: number): void {
    if (this.timer || intervalMs <= 0) return;
    this.timer = setInterval(() => {
      if (this.running || !this.ctx.db.open) return;
      this.running = true;
      const t0 = performance.now();
      try {
        const r = this.tick();
        this.ctx.metrics?.recordJob("reminders.tick", performance.now() - t0);
        if (r.fired || r.retried || r.failed) this.ctx.log.info(r, "reminders fired");
      } catch (err) {
        this.ctx.metrics?.recordJob("reminders.tick", performance.now() - t0, err);
        this.ctx.log.error({ errorType: err instanceof Error ? err.name : typeof err }, "reminder tick failed");
      } finally {
        this.running = false;
      }
    }, intervalMs);
    this.timer.unref();
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }
}
