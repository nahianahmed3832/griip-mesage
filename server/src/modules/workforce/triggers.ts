import crypto from "node:crypto";
import { z } from "zod/v4";
import type { DB } from "../../infrastructure/database/db.js";
import { ApiError } from "../../shared/errors.js";
import { newId } from "../../shared/utilities/util.js";

/**
 * Durable triggers (docs/AI_WORKFORCE.md §5b): work an agent starts with nobody present. Three kinds, all stored here
 * and fired by the workforce worker, so a restart loses nothing:
 *   schedule     every hour, day or week at a local time; the slot is claimed atomically, so it fires once
 *   webhook      an outside system POSTs to /v1/ai/hooks/:id with the trigger's secret
 *   after_agent  another agent's request finished: its result and files are handed on
 * Each run is an ordinary job created as the person who set the trigger up, so every permission, quota and approval
 * applies as if they had asked, checked again at the moment it fires.
 */
export const TRIGGER_KINDS = ["schedule", "webhook", "after_agent"] as const;
export type TriggerKind = (typeof TRIGGER_KINDS)[number];

/** Handoffs in a row (after_agent of after_agent …): a loop of agents stops here. */
export const MAX_CHAIN = 3;
/** Consecutive failures that switch a trigger off. */
export const MAX_FAILURES = 5;
const HHMM = /^([01]\d|2[0-3]):([0-5]\d)$/;

export const ScheduleConfig = z.strictObject({
  every: z.enum(["hour", "day", "week"]),
  /** Local time; for "hour" only the minutes count. */
  at: z.string().regex(HHMM).default("09:00"),
  /** 0 Sunday … 6 Saturday, for "week". */
  weekday: z.number().int().min(0).max(6).default(1),
  timeZone: z.string().min(1).max(64).refine(validTimeZone, "Unknown time zone."),
});
export type ScheduleConfig = z.infer<typeof ScheduleConfig>;
export const AfterAgentConfig = z.strictObject({ on: z.literal("completed").default("completed") });
export const ActiveHours = z.strictObject({ from: z.string().regex(HHMM), to: z.string().regex(HHMM), timeZone: z.string().min(1).max(64).refine(validTimeZone, "Unknown time zone.") });
export type ActiveHours = z.infer<typeof ActiveHours>;

export function validTimeZone(tz: string): boolean {
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: tz });
    return true;
  } catch {
    return false;
  }
}

/** The wall clock at [at] in [timeZone]. */
export function localParts(at: number, timeZone: string): { weekday: number; hour: number; minute: number } {
  const parts = new Intl.DateTimeFormat("en-US", { timeZone, weekday: "short", hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).formatToParts(at);
  const get = (t: string) => parts.find((p) => p.type === t)?.value ?? "";
  return { weekday: ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"].indexOf(get("weekday")), hour: Number(get("hour")), minute: Number(get("minute")) };
}

const MINUTE = 60_000;

/** The first slot strictly after [after], minute precision, in the schedule's own time zone (DST included). */
export function nextRun(c: ScheduleConfig, after: number): number {
  const [hh, mm] = c.at.split(":").map(Number) as [number, number];
  let t = Math.floor(after / MINUTE) * MINUTE + MINUTE;
  // At most eight days of minutes: every weekly slot exists in that span, even across a DST change.
  for (let i = 0; i < 8 * 24 * 60; i++, t += MINUTE) {
    const p = localParts(t, c.timeZone);
    if (p.minute !== mm) continue;
    if (c.every === "hour") return t;
    if (p.hour !== hh) continue;
    if (c.every === "day" || p.weekday === c.weekday) return t;
  }
  throw new ApiError(400, "bad_request", "That schedule never happens.");
}

/** Whether [at] falls in the window (a window across midnight, like 22:00–06:00, works too). */
export function withinHours(h: ActiveHours | null, at: number): boolean {
  if (!h) return true;
  const p = localParts(at, h.timeZone);
  const now = p.hour * 60 + p.minute;
  const [fh, fm] = h.from.split(":").map(Number) as [number, number];
  const [th, tm] = h.to.split(":").map(Number) as [number, number];
  const from = fh * 60 + fm;
  const to = th * 60 + tm;
  return from <= to ? now >= from && now < to : now >= from || now < to;
}

export interface TriggerRow {
  id: string;
  org_id: string;
  install_id: string;
  kind: TriggerKind;
  enabled: number;
  label: string;
  brief: string;
  conversation_id: string | null;
  config: string;
  source_install_id: string | null;
  secret_hash: string | null;
  run_as: string;
  next_run_at: number | null;
  last_fired_at: number | null;
  last_job_id: string | null;
  last_error: string | null;
  failures: number;
  rev: number;
  created_by: string;
  created_at: number;
  updated_at: number;
}

export interface NewTrigger {
  orgId: string;
  installId: string;
  kind: TriggerKind;
  label: string;
  brief: string;
  conversationId: string | null;
  config: unknown;
  sourceInstallId: string | null;
  runAs: string;
}

const hash = (secret: string) => crypto.createHash("sha256").update(secret).digest("hex");

export class TriggerStore {
  constructor(
    private readonly db: DB,
    private readonly clock: () => number,
  ) {}

  get(id: string): TriggerRow | null {
    return (this.db.prepare("SELECT * FROM ai_triggers WHERE id = ?").get(id) as TriggerRow | undefined) ?? null;
  }

  ofInstall(installId: string): TriggerRow[] {
    return this.db.prepare("SELECT * FROM ai_triggers WHERE install_id = ? ORDER BY created_at").all(installId) as TriggerRow[];
  }

  ofOrg(orgId: string): TriggerRow[] {
    return this.db.prepare("SELECT * FROM ai_triggers WHERE org_id = ? ORDER BY created_at").all(orgId) as TriggerRow[];
  }

  /** Creates a trigger; a webhook's secret is returned once and only its hash is kept. */
  create(t: NewTrigger, actor: string): { row: TriggerRow; secret: string | null } {
    const now = this.clock();
    const id = newId("trg");
    const secret = t.kind === "webhook" ? crypto.randomBytes(24).toString("base64url") : null;
    const next = t.kind === "schedule" ? nextRun(t.config as ScheduleConfig, now) : null;
    this.db
      .prepare(
        `INSERT INTO ai_triggers (id, org_id, install_id, kind, enabled, label, brief, conversation_id, config, source_install_id, secret_hash, run_as,
                                  next_run_at, failures, rev, created_by, created_at, updated_at)
         VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, 0, 1, ?, ?, ?)`,
      )
      .run(id, t.orgId, t.installId, t.kind, t.label, t.brief, t.conversationId, JSON.stringify(t.config), t.sourceInstallId, secret ? hash(secret) : null, t.runAs, next, actor, now, now);
    return { row: this.get(id)!, secret };
  }

  /** Changes what a person may change; a new schedule starts from now, and re-enabling clears the failure count. */
  update(id: string, rev: number, patch: { enabled?: boolean; label?: string; brief?: string; conversationId?: string | null; config?: unknown }): TriggerRow {
    const cur = this.get(id);
    if (!cur) throw new ApiError(404, "not_found", "No such trigger.");
    if (cur.rev !== rev) throw new ApiError(409, "conflict", "Someone changed this trigger meanwhile. Reload and try again.");
    const now = this.clock();
    const config = patch.config === undefined ? (JSON.parse(cur.config) as unknown) : patch.config;
    const enabled = patch.enabled ?? cur.enabled === 1;
    const next = cur.kind === "schedule" && (patch.config !== undefined || (enabled && cur.enabled === 0)) ? nextRun(config as ScheduleConfig, now) : cur.next_run_at;
    this.db
      .prepare(
        `UPDATE ai_triggers SET enabled = ?, label = ?, brief = ?, conversation_id = ?, config = ?, next_run_at = ?, failures = CASE WHEN ? = 1 AND enabled = 0 THEN 0 ELSE failures END,
                last_error = CASE WHEN ? = 1 AND enabled = 0 THEN NULL ELSE last_error END, rev = rev + 1, updated_at = ? WHERE id = ?`,
      )
      .run(
        enabled ? 1 : 0,
        patch.label ?? cur.label,
        patch.brief ?? cur.brief,
        patch.conversationId === undefined ? cur.conversation_id : patch.conversationId,
        JSON.stringify(config),
        next,
        enabled ? 1 : 0,
        enabled ? 1 : 0,
        now,
        id,
      );
    return this.get(id)!;
  }

  remove(id: string): void {
    this.db.prepare("DELETE FROM ai_triggers WHERE id = ?").run(id);
  }

  removeOfInstall(installId: string): void {
    this.db.prepare("DELETE FROM ai_triggers WHERE install_id = ? OR source_install_id = ?").run(installId, installId);
  }

  /** Enabled schedules whose slot has come. */
  dueSchedules(now: number, limit = 50): TriggerRow[] {
    return this.db.prepare("SELECT * FROM ai_triggers WHERE kind = 'schedule' AND enabled = 1 AND next_run_at IS NOT NULL AND next_run_at <= ? ORDER BY next_run_at LIMIT ?").all(now, limit) as TriggerRow[];
  }

  /**
   * Takes a schedule's slot: moves next_run_at on only if it still is [slot]. One caller wins, so a slot fires once
   * however many workers look. Slots missed while the server was down fire once, then the schedule moves to the future.
   */
  claimSlot(t: TriggerRow, slot: number): boolean {
    const next = nextRun(JSON.parse(t.config) as ScheduleConfig, Math.max(slot, this.clock()));
    return this.db.prepare("UPDATE ai_triggers SET next_run_at = ? WHERE id = ? AND next_run_at = ? AND enabled = 1").run(next, t.id, slot).changes === 1;
  }

  /** Enabled handoffs waiting for [sourceInstallId]'s finished work. */
  afterAgent(sourceInstallId: string): TriggerRow[] {
    return this.db.prepare("SELECT * FROM ai_triggers WHERE kind = 'after_agent' AND enabled = 1 AND source_install_id = ?").all(sourceInstallId) as TriggerRow[];
  }

  /** Whether [secret] is this webhook's (constant-time). */
  secretMatches(t: TriggerRow, secret: string): boolean {
    if (!t.secret_hash) return false;
    const a = Buffer.from(t.secret_hash, "hex");
    const b = Buffer.from(hash(secret), "hex");
    return a.length === b.length && crypto.timingSafeEqual(a, b);
  }

  fired(id: string, jobId: string): void {
    this.db.prepare("UPDATE ai_triggers SET last_fired_at = ?, last_job_id = ?, last_error = NULL, failures = 0 WHERE id = ?").run(this.clock(), jobId, id);
  }

  /** Records why a run didn't start; [counts] false for waits that aren't failures (outside active hours, paused). */
  failed(id: string, why: string, counts = true): void {
    this.db
      .prepare(`UPDATE ai_triggers SET last_error = ?, failures = failures + ?, enabled = CASE WHEN failures + ? >= ? THEN 0 ELSE enabled END WHERE id = ?`)
      .run(why.slice(0, 500), counts ? 1 : 0, counts ? 1 : 0, MAX_FAILURES, id);
  }
}

/** What clients see of a trigger (never the secret or its hash). */
export function triggerView(t: TriggerRow) {
  return {
    id: t.id,
    installId: t.install_id,
    kind: t.kind,
    enabled: t.enabled === 1,
    label: t.label,
    brief: t.brief,
    conversationId: t.conversation_id,
    config: JSON.parse(t.config) as unknown,
    sourceInstallId: t.source_install_id,
    runAs: t.run_as,
    nextRunAt: t.next_run_at,
    lastFiredAt: t.last_fired_at,
    lastJobId: t.last_job_id,
    lastError: t.last_error,
    failures: t.failures,
    rev: t.rev,
    createdAt: t.created_at,
    updatedAt: t.updated_at,
  };
}
