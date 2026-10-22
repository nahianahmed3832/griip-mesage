import { createHmac } from "node:crypto";
import { EXPERIMENT_STATUSES, EXPERIMENTS, type ExperimentDef, type ExperimentStatus } from "./definitions.js";

/**
 * Who sees which variant (docs/product/EXPERIMENTS.md). Assignment is a pure function of the person's id, so it is the
 * same on every device and every request, and nothing about it is stored.
 *
 * - EXPERIMENTS_MODE=off (the default): nobody is in any experiment; everyone gets the control.
 * - EXPERIMENTS_MODE=preview: only preview people (EXPERIMENT_PREVIEW_USER_IDS and platform admins) are assigned, to
 *   experiments that are "preview" or "running". For internal testing on staging or production.
 * - EXPERIMENTS_MODE=on: "running" experiments, within their dates, assign everyone in their traffic share; preview
 *   people also get "preview" ones.
 * - EXPERIMENT_STATUS=id=status,…: the operator's override of a definition's status ("stopped" is the rollback).
 */
export type ExperimentsMode = "off" | "preview" | "on";

export interface ExperimentsConfig {
  mode: ExperimentsMode;
  overrides: ReadonlyMap<string, ExperimentStatus>;
  previewUserIds: ReadonlySet<string>;
  /** Changes every assignment when changed; not a secret. */
  salt: string;
}

const BUCKETS = 10_000;

export function experimentsConfig(env: NodeJS.ProcessEnv): ExperimentsConfig {
  const raw = (env.EXPERIMENTS_MODE ?? "off").trim().toLowerCase();
  if (raw !== "off" && raw !== "preview" && raw !== "on") throw new Error("EXPERIMENTS_MODE must be off, preview or on");
  const overrides = new Map<string, ExperimentStatus>();
  for (const pair of (env.EXPERIMENT_STATUS ?? "").split(",").map((s) => s.trim()).filter(Boolean)) {
    const [id, status] = pair.split("=").map((s) => s.trim());
    if (!id || !EXPERIMENT_STATUSES.includes(status as ExperimentStatus)) throw new Error(`EXPERIMENT_STATUS: "${pair}" must be id=draft|preview|running|stopped`);
    overrides.set(id, status as ExperimentStatus);
  }
  const previewUserIds = new Set((env.EXPERIMENT_PREVIEW_USER_IDS ?? "").split(",").map((s) => s.trim()).filter(Boolean));
  return { mode: raw, overrides, previewUserIds, salt: env.EXPERIMENT_SALT?.trim() || "meldiq-experiments-v1" };
}

export interface Assignment {
  experiment: string;
  variant: string;
  layer: string;
}

export function effectiveStatus(cfg: ExperimentsConfig, def: ExperimentDef): ExperimentStatus {
  return cfg.overrides.get(def.id) ?? def.status;
}

function bucket(salt: string, key: string, size: number): number {
  return createHmac("sha256", salt).update(key).digest().readUInt32BE(0) % size;
}

function withinDates(def: ExperimentDef, at: number): boolean {
  if (def.startsAt && at < Date.parse(def.startsAt)) return false;
  if (def.endsAt && at >= Date.parse(def.endsAt)) return false;
  return true;
}

function eligible(cfg: ExperimentsConfig, def: ExperimentDef, preview: boolean, at: number): boolean {
  const status = effectiveStatus(cfg, def);
  if (cfg.mode === "off") return false;
  if (status === "preview") return preview;
  if (status !== "running") return false;
  if (cfg.mode === "preview") return preview;
  // Preview people see a running experiment before its start date, so it can be checked first.
  return preview || withinDates(def, at);
}

/**
 * The experiments this person is in, at most one per layer. Within a layer each experiment owns a fixed slice of the
 * buckets in definition order, so stopping one experiment never moves anyone in another.
 */
export function assignments(cfg: ExperimentsConfig, userId: string, isPreview: boolean, at = Date.now(), defs: readonly ExperimentDef[] = EXPERIMENTS): Assignment[] {
  if (cfg.mode === "off") return [];
  const preview = isPreview || cfg.previewUserIds.has(userId);
  const out: Assignment[] = [];
  const offsets = new Map<string, number>();
  for (const def of defs) {
    const start = offsets.get(def.layer) ?? 0;
    const end = start + Math.round(def.traffic * BUCKETS);
    offsets.set(def.layer, end);
    if (!eligible(cfg, def, preview, at)) continue;
    if (out.some((a) => a.layer === def.layer)) continue;
    const b = bucket(cfg.salt, `layer:${def.layer}:${userId}`, BUCKETS);
    // Preview people are always in, so they can try every variant (which one is still stable per person).
    if (!preview && (b < start || b >= end)) continue;
    const total = def.variants.reduce((s, v) => s + v.weight, 0);
    let pick = (bucket(cfg.salt, `exp:${def.id}:${userId}`, 1_000_000) / 1_000_000) * total;
    const variant = def.variants.find((v) => (pick -= v.weight) < 0) ?? def.variants[def.variants.length - 1];
    out.push({ experiment: def.id, variant: variant.id, layer: def.layer });
  }
  return out;
}
