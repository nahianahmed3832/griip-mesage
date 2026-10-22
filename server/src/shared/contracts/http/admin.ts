import { z } from "zod/v4";
import { zCount, zTime } from "./primitives.js";
import { route } from "./route.js";

/**
 * Platform operations (docs/OPERATIONS.md, docs/CONNECTORS.md, docs/USAGE_AND_PLANS.md): platform admins only
 * (PLATFORM_ADMIN_USER_IDS), never a workspace role. Measured values and counts only: no content, member lists, user
 * ids or secrets.
 */

/** Connector ids that can be checked without sending anything to anyone or costing anything. */
export const CONNECTOR_CHECKS = ["email", "storage", "push-fcm", "mongodb"] as const;

/** A value this process measured, or why it couldn't. */
const measured = <T extends Record<string, z.ZodType>>(shape: T) =>
  z.discriminatedUnion("available", [
    z.strictObject({ available: z.literal(true), ...shape }),
    z.strictObject({ available: z.literal(false), reason: z.string() }),
  ]);

const zMs = z.number().nonnegative();

export const MetricsSnapshot = z.strictObject({
  since: zTime,
  /** The last [minutes] minutes. */
  window: z.strictObject({ minutes: zCount, requests: zCount, serverErrors: zCount, errorRate: z.number().nullable(), p95Ms: zMs.nullable() }),
  routes: z.array(
    z.strictObject({ route: z.string(), count: zCount, clientErrors: zCount, serverErrors: zCount, avgMs: zMs, p95Ms: zMs.nullable(), maxMs: zMs }),
  ),
  jobs: z.array(
    z.strictObject({
      name: z.string(),
      runs: zCount,
      failures: zCount,
      lastRunAt: zTime.nullable(),
      lastDurationMs: zMs.nullable(),
      lastFailureAt: zTime.nullable(),
      lastErrorKind: z.string().nullable(),
    }),
  ),
  providers: z.array(
    z.strictObject({
      name: z.string(),
      ok: zCount,
      failed: zCount,
      lastOkAt: zTime.nullable(),
      lastFailureAt: zTime.nullable(),
      lastErrorKind: z.string().nullable(),
    }),
  ),
});

export const HealthReport = z.strictObject({
  version: z.string(),
  generatedAt: zTime,
  uptimeSeconds: zCount,
  checks: z.strictObject({
    database: measured({ ok: z.boolean(), sizeBytes: zCount }),
    mongodb: measured({ ok: z.boolean() }),
    livekit: measured({ configured: z.boolean() }),
    storage: measured({ backend: z.string(), reachable: z.boolean(), errorKind: z.string().optional() }),
  }),
  process: z.strictObject({ rssBytes: zCount, heapUsedBytes: zCount, eventLoopDelayP99Ms: zMs.nullable() }),
  realtime: z.strictObject({ onlineDevices: zCount }),
  queues: z.strictObject({
    delivery: z.strictObject({ items: zCount, oldestAgeSeconds: z.number().int().nullable() }),
    reminders: z.strictObject({ overdueUndelivered: zCount, retrying: zCount }),
    /** Always unavailable since apps were removed; kept because Android 0.3.0 and older require it. */
    integrations: measured({ jobsQueued: zCount, jobsDead: zCount, deliveriesFailed24h: zCount }),
  }),
  metrics: MetricsSnapshot,
  /** Plain-language problems worth a look. */
  warnings: z.array(z.string()),
});

export const ConnectorStatus = z.strictObject({
  id: z.string(),
  name: z.string(),
  category: z.string(),
  state: z.enum(["working", "configured", "failing", "needs_configuration", "disabled", "awaiting_provider", "planned"]),
  /** Environment names still missing (names only, never values). */
  missing: z.array(z.string()),
  /** One plain sentence about what it does here and now. */
  detail: z.string(),
  /** Counts only (workspaces, connections, devices…). */
  usage: z.record(z.string(), z.number()),
  lastOkAt: zTime.nullable(),
  lastFailureAt: zTime.nullable(),
  lastErrorKind: z.string().nullable(),
  /** Whether POST /v1/admin/connectors/:id/check can test it. */
  checkable: z.boolean(),
  /** What the owner has to do outside Meldiq, when anything. */
  ownerAction: z.string().nullable(),
  docs: z.string(),
});

export const WorkspaceLimit = z.strictObject({ key: z.string(), limit: zCount });

export const ConnectorParams = z.strictObject({ id: z.enum(CONNECTOR_CHECKS) });
export const UsageQuery = z.object({ days: z.coerce.number().int().min(1).max(90).default(30) });
export const LimitsParams = z.strictObject({ orgId: z.string().max(64) });
export const LimitsBody = z.object({ limits: z.record(z.string(), z.number().int().min(0).nullable()) });

const admin = (c: Omit<Parameters<typeof route>[0], "tag" | "auth">) => route({ tag: "admin", auth: true, ...c });

export const adminRoutes = [
  admin({
    method: "GET",
    url: "/v1/platform/me",
    summary: "Whether the caller is a platform admin (the app then shows Service health).",
    response: z.strictObject({ platformAdmin: z.boolean() }),
  }),
  admin({
    method: "GET",
    url: "/v1/admin/health",
    summary: "The platform health report: measured values only.",
    response: z.strictObject({ health: HealthReport }),
    errors: ["forbidden"],
  }),
  admin({
    method: "GET",
    url: "/v1/admin/connectors",
    summary: "Every outside service with its measured state and the setting names still missing.",
    response: z.strictObject({ connectors: z.array(ConnectorStatus), checkable: z.array(z.enum(CONNECTOR_CHECKS)) }),
    errors: ["forbidden"],
  }),
  admin({
    method: "POST",
    url: "/v1/admin/connectors/:id/check",
    summary: "Check a connector without sending anything to anyone (sign in to SMTP, list storage, FCM token, Mongo ping). Audited.",
    params: ConnectorParams,
    response: z.strictObject({
      result: z.strictObject({ ok: z.boolean(), errorKind: z.string().nullable() }),
      connectors: z.array(ConnectorStatus),
    }),
    errors: ["forbidden"],
  }),
  admin({
    method: "GET",
    url: "/v1/admin/usage",
    summary: "Every workspace with its measured usage over [days] and its own limits: counts only.",
    query: UsageQuery,
    response: z.strictObject({
      days: zCount,
      workspaces: z.array(
        z.strictObject({
          id: z.string(),
          name: z.string(),
          createdAt: zTime,
          /** Metric name → total over the window. */
          usage: z.record(z.string(), z.number()),
          limits: z.array(WorkspaceLimit),
        }),
      ),
    }),
    errors: ["forbidden"],
  }),
  admin({
    method: "PUT",
    url: "/v1/admin/orgs/:orgId/limits",
    summary: "Set a workspace's own limits; null clears one (the server-wide limit applies again). Audited.",
    params: LimitsParams,
    body: LimitsBody,
    response: z.strictObject({ limits: z.array(WorkspaceLimit) }),
    errors: ["forbidden", "not_found"],
  }),
];
