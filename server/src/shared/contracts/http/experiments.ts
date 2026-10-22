import { z } from "zod/v4";
import { zCount } from "./primitives.js";
import { route } from "./route.js";

/**
 * Experiments and product analytics (docs/product/EXPERIMENTS.md). Off unless the operator turns them on
 * (EXPERIMENTS_MODE, ANALYTICS_ENABLED). Analytics events come from a closed catalogue with no free text; the server
 * keeps daily counts only, never who sent an event.
 */

const ExperimentsMode = z.enum(["off", "preview", "on"]);
const ExperimentStatus = z.enum(["draft", "preview", "running", "stopped"]);

/** The longest an analytics event's value may be: 31 days, in milliseconds. */
const MAX_EVENT_VALUE_MS = 31 * 24 * 3600 * 1000;

/** A batch of analytics events; events or properties outside the catalogue are dropped, not refused. */
export const AnalyticsBatch = z.object({
  platform: z.enum(["android", "web"]),
  events: z
    .array(
      z.object({
        name: z.string().max(40),
        props: z.record(z.string().max(40), z.string().max(40)).optional(),
        value: z.number().finite().min(0).max(MAX_EVENT_VALUE_MS).optional(),
      }),
    )
    .min(1)
    .max(50),
});
export type AnalyticsBatch = z.infer<typeof AnalyticsBatch>;

/** The experiment and variant this person is in, at most one per layer. */
export const ExperimentAssignment = z.strictObject({ experiment: z.string(), variant: z.string(), layer: z.string() });

const EventCount = { event: z.string(), count: zCount, valueCount: zCount, valueSum: z.number() };

export const ExperimentReport = z.strictObject({
  mode: ExperimentsMode,
  analytics: z.boolean(),
  /** First day counted (YYYY-MM-DD, UTC). */
  from: z.string(),
  /** The event catalogue; value: what an event's number holds ("ms") or null. */
  events: z.array(z.strictObject({ name: z.string(), value: z.literal("ms").nullable(), description: z.string() })),
  /** Every event's totals, in or out of experiments. */
  overall: z.array(z.strictObject(EventCount)),
  experiments: z.array(
    z.strictObject({
      id: z.string(),
      description: z.string(),
      layer: z.string(),
      /** Share of the layer's people this experiment may take, 0–1. */
      traffic: z.number(),
      control: z.string(),
      variants: z.array(z.strictObject({ id: z.string(), weight: z.number() })),
      status: ExperimentStatus,
      startsAt: z.string().nullable(),
      endsAt: z.string().nullable(),
      metrics: z.array(z.string()),
      successCriteria: z.string(),
      /** The status after the operator's override (EXPERIMENT_STATUS). */
      effectiveStatus: ExperimentStatus,
      results: z.array(z.strictObject({ variant: z.string(), ...EventCount })),
    }),
  ),
});

/** Longest report window, in days. */
export const MAX_REPORT_DAYS = 400;
export const ExperimentReportQuery = z.object({ days: z.coerce.number().int().min(1).max(MAX_REPORT_DAYS).default(28) });

export const experimentRoutes = [
  route({
    method: "GET",
    url: "/v1/experiments",
    tag: "experiments",
    summary: "The experiments mode, whether analytics is on, and this person's experiment variants.",
    auth: true,
    response: z.strictObject({ mode: ExperimentsMode, analytics: z.boolean(), assignments: z.array(ExperimentAssignment) }),
  }),
  route({
    method: "POST",
    url: "/v1/analytics/events",
    tag: "experiments",
    summary: "Count a batch of analytics events (accepted: how many fit the catalogue; enabled: false when analytics is off).",
    auth: true,
    body: AnalyticsBatch,
    status: 202,
    response: z.strictObject({ accepted: zCount, enabled: z.boolean() }),
  }),
  route({
    method: "GET",
    url: "/v1/admin/experiments",
    tag: "experiments",
    summary: "Experiment definitions and event counts per variant over [days]. Platform admins only.",
    auth: true,
    query: ExperimentReportQuery,
    response: ExperimentReport,
    errors: ["forbidden"],
  }),
];
