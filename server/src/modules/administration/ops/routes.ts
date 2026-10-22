import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { type AppContext, me, parse, requireAuth } from "../../../app/context.js";
import { ENTITLEMENT_KEYS, type EntitlementKey, isEntitlementKey } from "../entitlements.js";
import { badRequest, forbidden, notFound } from "../../../shared/utilities/util.js";
import { CHECKS, checkConnector, connectorReport } from "./connectors.js";
import { healthReport } from "./health.js";

/**
 * Platform operations API (docs/OPERATIONS.md): platform admins only (PLATFORM_ADMIN_USER_IDS), never a workspace
 * role. Health and usage are read-only; the only change here is a workspace's own limits (audited). Nothing here exposes
 * messages, member lists or anyone's data.
 */
export default async function opsRoutes(app: FastifyInstance, ctx: AppContext, version: string) {
  const auth = { preHandler: requireAuth(ctx) };
  const requirePlatformAdmin = (userId: string) => {
    if (!ctx.config.platformAdmins.has(userId)) throw forbidden("Only platform administrators can do that");
  };

  /** Whether the caller is a platform admin (the app shows Service health then). Moved here from the removed apps API. */
  app.get("/v1/platform/me", auth, async (req) => ({ platformAdmin: ctx.config.platformAdmins.has(me(req).userId) }));

  app.get("/v1/admin/health", auth, async (req) => {
    requirePlatformAdmin(me(req).userId);
    return { health: await healthReport(ctx, version) };
  });

  // ───────────── Connector register (docs/CONNECTORS.md) ─────────────
  /** Every outside service with its measured state and the setting names still missing (never values). */
  app.get("/v1/admin/connectors", auth, async (req) => {
    requirePlatformAdmin(me(req).userId);
    return { connectors: await connectorReport(ctx), checkable: CHECKS };
  });

  /** A check that sends nothing to anyone and costs nothing (sign in to SMTP, list storage, FCM token, Mongo ping). */
  app.post("/v1/admin/connectors/:id/check", auth, async (req) => {
    const userId = me(req).userId;
    requirePlatformAdmin(userId);
    const { id } = parse(z.object({ id: z.enum(CHECKS) }), req.params);
    const result = await checkConnector(ctx, id);
    ctx.audit({ actorId: userId, action: "connector.check", target: id, detail: result.ok ? "ok" : result.errorKind });
    return { result, connectors: await connectorReport(ctx) };
  });

  // ───────────── Usage and limits across workspaces (round 7 slice C7, docs/USAGE_AND_PLANS.md) ─────────────
  /** Every workspace with its size and measured usage over [days]: counts only, no content or member names. */
  app.get("/v1/admin/usage", auth, async (req) => {
    requirePlatformAdmin(me(req).userId);
    const { days } = parse(z.object({ days: z.coerce.number().int().min(1).max(90).default(30) }), req.query);
    ctx.usage.snapshot();
    const totals = new Map(ctx.usage.totals(days).map((r) => [r.orgId, r.metrics]));
    const orgs = ctx.db.prepare("SELECT id, name, created_at FROM orgs ORDER BY created_at").all() as { id: string; name: string; created_at: number }[];
    return {
      days,
      workspaces: orgs.map((o) => ({
        id: o.id, name: o.name, createdAt: o.created_at, usage: totals.get(o.id) ?? {},
        limits: (ctx.db.prepare("SELECT key, limit_value FROM org_entitlements WHERE org_id = ?").all(o.id) as { key: string; limit_value: number }[]).map((r) => ({ key: r.key, limit: r.limit_value })),
      })),
    };
  });

  /** A workspace's own limits; null clears one (the server-wide limit, if any, applies again). Audited. */
  app.put("/v1/admin/orgs/:orgId/limits", auth, async (req) => {
    const actor = me(req).userId;
    requirePlatformAdmin(actor);
    const { orgId } = parse(z.object({ orgId: z.string().max(64) }), req.params);
    if (!ctx.db.prepare("SELECT 1 FROM orgs WHERE id = ?").get(orgId)) throw notFound("Workspace not found");
    const { limits } = parse(z.object({ limits: z.record(z.string(), z.number().int().min(0).nullable()) }), req.body);
    const unknown = Object.keys(limits).filter((k) => !isEntitlementKey(k));
    if (unknown.length) throw badRequest(`Unknown limits: ${unknown.join(", ")}`, { known: ENTITLEMENT_KEYS });
    ctx.entitlements.setLimits(orgId, actor, limits as Partial<Record<EntitlementKey, number | null>>, Date.now());
    ctx.audit({ actorId: actor, orgId, action: "plan.limits", detail: Object.entries(limits).map(([k, v]) => `${k}=${v ?? "default"}`).join(",") });
    return { limits: (ctx.db.prepare("SELECT key, limit_value FROM org_entitlements WHERE org_id = ?").all(orgId) as { key: string; limit_value: number }[]).map((r) => ({ key: r.key, limit: r.limit_value })) };
  });
}
