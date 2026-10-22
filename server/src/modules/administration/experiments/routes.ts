import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { type AppContext } from "../../../app/context.js";
import { me, requireAuth } from "../../auth/index.js";
import { parse } from "../../../shared/validation/index.js";
import { forbidden } from "../../../shared/errors.js";
import { type Assignment, assignments, effectiveStatus } from "./assign.js";
import { EXPERIMENTS } from "./definitions.js";
import { ANALYTICS_EVENTS, type CleanEvent, cleanEvents, zAnalyticsBatch } from "./events.js";

/** Analytics batches per person per minute: an app sends one every minute or so at most. */
const ANALYTICS_RATE_LIMIT = { max: 30, timeWindow: "1 minute" };
const DAY_MS = 86_400_000;
/** Longest report window, in days. */
const MAX_REPORT_DAYS = 400;

/**
 * Experiments and product analytics (docs/product/EXPERIMENTS.md). Off unless the operator turns them on
 * (EXPERIMENTS_MODE, ANALYTICS_ENABLED); the reports are for platform admins only.
 */
export default async function experimentRoutes(app: FastifyInstance, ctx: AppContext) {
  const auth = { preHandler: requireAuth(ctx) };
  const cfg = ctx.config.experiments;
  const isPlatformAdmin = (userId: string) => ctx.config.platformAdmins.has(userId);
  const assignmentsFor = (userId: string): Assignment[] => assignments(cfg, userId, isPlatformAdmin(userId));

  app.get("/v1/experiments", auth, async (req) => {
    const { userId } = me(req);
    return { mode: cfg.mode, analytics: ctx.config.analyticsEnabled, assignments: assignmentsFor(userId) };
  });

  const bump = ctx.db.prepare(
    `INSERT INTO analytics_daily (day, event, dims, platform, experiment, variant, count, value_count, value_sum)
     VALUES (@day, @event, @dims, @platform, @experiment, @variant, 1, @hasValue, @value)
     ON CONFLICT (day, event, dims, platform, experiment, variant)
     DO UPDATE SET count = count + 1, value_count = value_count + @hasValue, value_sum = value_sum + @value`,
  );
  const record = ctx.db.transaction((events: CleanEvent[], platform: string, inExperiments: Assignment[], day: string) => {
    for (const e of events) {
      const base = { day, event: e.name, dims: e.dims, platform, hasValue: e.value === null ? 0 : 1, value: e.value ?? 0 };
      bump.run({ ...base, experiment: "", variant: "" });
      for (const a of inExperiments) bump.run({ ...base, experiment: a.experiment, variant: a.variant });
    }
  });

  app.post("/v1/analytics/events", { ...auth, config: { rateLimit: ANALYTICS_RATE_LIMIT } }, async (req, reply) => {
    if (!ctx.config.analyticsEnabled) return reply.code(202).send({ accepted: 0, enabled: false });
    const batch = parse(zAnalyticsBatch, req.body);
    const events = cleanEvents(batch);
    const day = new Date().toISOString().slice(0, 10);
    record(events, batch.platform, assignmentsFor(me(req).userId), day);
    return reply.code(202).send({ accepted: events.length, enabled: true });
  });

  app.get("/v1/admin/experiments", auth, async (req) => {
    const { userId } = me(req);
    if (!isPlatformAdmin(userId)) throw forbidden("Only platform administrators can do that");
    const q = parse(z.object({ days: z.coerce.number().int().min(1).max(MAX_REPORT_DAYS).default(28) }), req.query);
    const from = new Date(Date.now() - (q.days - 1) * DAY_MS).toISOString().slice(0, 10);
    const rows = ctx.db
      .prepare(
        `SELECT experiment, variant, event, SUM(count) count, SUM(value_count) valueCount, SUM(value_sum) valueSum
           FROM analytics_daily WHERE day >= ? GROUP BY experiment, variant, event ORDER BY experiment, variant, event`,
      )
      .all(from) as { experiment: string; variant: string; event: string; count: number; valueCount: number; valueSum: number }[];
    return {
      mode: cfg.mode,
      analytics: ctx.config.analyticsEnabled,
      from,
      events: Object.entries(ANALYTICS_EVENTS).map(([name, d]) => ({ name, value: d.value, description: d.description })),
      overall: rows.filter((r) => r.experiment === "").map(({ event, count, valueCount, valueSum }) => ({ event, count, valueCount, valueSum })),
      experiments: EXPERIMENTS.map((d) => ({
        ...d,
        effectiveStatus: effectiveStatus(cfg, d),
        results: rows.filter((r) => r.experiment === d.id).map(({ variant, event, count, valueCount, valueSum }) => ({ variant, event, count, valueCount, valueSum })),
      })),
    };
  });
}
