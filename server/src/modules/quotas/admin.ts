import type { DB } from "../../infrastructure/database/db.js";
import type { PolicyRow } from "./engine.js";

/** Reads and plan bookkeeping for the Platform Admin Console (modules/platformAdmin), which owns none of these tables. */

export function activePolicies(db: DB): PolicyRow[] {
  return db.prepare("SELECT * FROM quota_policies WHERE superseded_at IS NULL ORDER BY quota_key, scope_type, scope_id").all() as PolicyRow[];
}

export function currentPolicy(db: DB, scopeType: string, scopeId: string, key: string, override: boolean): PolicyRow | undefined {
  return db.prepare("SELECT * FROM quota_policies WHERE scope_type = ? AND scope_id = ? AND quota_key = ? AND override = ? AND superseded_at IS NULL").get(scopeType, scopeId, key, override ? 1 : 0) as
    | PolicyRow
    | undefined;
}

export function policiesFor(db: DB, keyPrefix: string): PolicyRow[] {
  return db.prepare("SELECT * FROM quota_policies WHERE superseded_at IS NULL AND quota_key LIKE ? ORDER BY quota_key").all(`${keyPrefix}%`) as PolicyRow[];
}

export interface PlanRow {
  id: string;
  name: string;
  description: string;
  created_at: number;
  members: number;
}

export function plans(db: DB): PlanRow[] {
  return db
    .prepare("SELECT p.id, p.name, p.description, p.created_at, (SELECT COUNT(*) FROM quota_plan_members m WHERE m.plan_id = p.id) members FROM quota_plans p WHERE archived_at IS NULL ORDER BY name")
    .all() as PlanRow[];
}

export function planExists(db: DB, id: string): boolean {
  return !!db.prepare("SELECT 1 FROM quota_plans WHERE id = ? AND archived_at IS NULL").get(id);
}

export function planNameTaken(db: DB, name: string): boolean {
  return !!db.prepare("SELECT 1 FROM quota_plans WHERE name = ?").get(name);
}

export function createPlan(db: DB, id: string, name: string, description: string, actor: string, at: number): void {
  db.prepare("INSERT INTO quota_plans (id, name, description, created_by, created_at) VALUES (?, ?, ?, ?, ?)").run(id, name, description, actor, at);
}

export function planOfScope(db: DB, scopeType: string, scopeId: string): string | null {
  return (db.prepare("SELECT plan_id FROM quota_plan_members WHERE scope_type = ? AND scope_id = ?").get(scopeType, scopeId) as { plan_id: string } | undefined)?.plan_id ?? null;
}

export function assignPlan(db: DB, scopeType: string, scopeId: string, planId: string, actor: string, at: number): void {
  db.prepare(
    `INSERT INTO quota_plan_members (scope_type, scope_id, plan_id, assigned_by, assigned_at) VALUES (?, ?, ?, ?, ?)
     ON CONFLICT (scope_type, scope_id) DO UPDATE SET plan_id = excluded.plan_id, assigned_by = excluded.assigned_by, assigned_at = excluded.assigned_at`,
  ).run(scopeType, scopeId, planId, actor, at);
}

export function unassignPlan(db: DB, scopeType: string, scopeId: string): void {
  db.prepare("DELETE FROM quota_plan_members WHERE scope_type = ? AND scope_id = ?").run(scopeType, scopeId);
}

/** This period's busiest counters for [key] (bounded). */
export function topCounters(db: DB, key: string, period: string, limit: number): { scope_type: string; scope_id: string; period_start: string; used: number; reserved: number }[] {
  return db
    .prepare("SELECT scope_type, scope_id, period_start, used, reserved FROM quota_counters WHERE quota_key = ? AND period_start = ? ORDER BY used + reserved DESC LIMIT ?")
    .all(key, period, limit) as { scope_type: string; scope_id: string; period_start: string; used: number; reserved: number }[];
}

/** How many [scopeType] counters (optionally one [scopeId]) for [key] in [period] use more than [limit]. */
export function countersOver(db: DB, key: string, period: string, scopeType: string, scopeId: string | null, limit: number): number {
  const args: (string | number)[] = [key, period, scopeType];
  if (scopeId !== null) args.push(scopeId);
  args.push(limit);
  return (db.prepare(`SELECT COUNT(*) n FROM quota_counters WHERE quota_key = ? AND period_start = ? AND scope_type = ? ${scopeId === null ? "" : "AND scope_id = ?"} AND used + reserved > ?`).get(...args) as { n: number }).n;
}

export function eventCounts(db: DB, since: number): Record<string, number> {
  return Object.fromEntries((db.prepare("SELECT outcome, COUNT(*) n FROM quota_events WHERE at > ? GROUP BY outcome").all(since) as { outcome: string; n: number }[]).map((r) => [r.outcome, r.n]));
}

export interface EventRow {
  id: number;
  at: number;
  quota_key: string;
  scope_type: string;
  scope_id: string;
  outcome: string;
  amount: number;
  used: number;
  limit_value: number | null;
  actor: string | null;
  detail: string | null;
}

export function eventsPage(db: DB, q: { key?: string; scopeType?: string; scopeId?: string; cursor?: number; limit: number; from?: number; to?: number }): EventRow[] {
  const where = ["1 = 1"];
  const args: (string | number)[] = [];
  for (const [col, v] of [["quota_key", q.key], ["scope_type", q.scopeType], ["scope_id", q.scopeId]] as const) {
    if (v) {
      where.push(`${col} = ?`);
      args.push(v);
    }
  }
  if (q.cursor) {
    where.push("id < ?");
    args.push(q.cursor);
  }
  if (q.from !== undefined) {
    where.push("at >= ?");
    args.push(q.from);
  }
  if (q.to !== undefined) {
    where.push("at < ?");
    args.push(q.to);
  }
  return db.prepare(`SELECT * FROM quota_events WHERE ${where.join(" AND ")} ORDER BY id DESC LIMIT ?`).all(...args, q.limit) as EventRow[];
}

/** Retention for the decision ledger (180 days); counters, policies and plans are kept. */
export function trimEvents(db: DB, before: number): number {
  return db.prepare("DELETE FROM quota_events WHERE at < ?").run(before).changes;
}
