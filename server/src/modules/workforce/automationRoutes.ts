import type { FastifyInstance } from "fastify";
import type { AppContext } from "../../app/context.js";
import { AiHookBody, AiHookParams, AiInstallParams, AiTriggerBody, AiTriggerParams, AiTriggerPatch } from "../../shared/contracts/http/aiWorkforce.js";
import { ApiError } from "../../shared/errors.js";
import { parse } from "../../shared/validation/index.js";
import { me, requireAuth } from "../auth/index.js";
import { activity, readiness, TEST_BRIEF } from "./readiness.js";
import { triggerView } from "./triggers.js";
import { jobDetail } from "./views.js";
import type { WorkforceModule } from "./index.js";

/**
 * An installed agent's operations (docs/AI_WORKFORCE.md §5b): its readiness, recent activity, a real test request,
 * and its triggers. Readiness and activity are for the community's members (counts and checks, never content); a test
 * and triggers need "Manage AI agents". The webhook route takes no session: its trigger's secret is the credential.
 */
export function automationRoutes(app: FastifyInstance, ctx: AppContext, w: WorkforceModule): void {
  const auth = { preHandler: requireAuth(ctx) };
  const limited = (max: number) => ({ preHandler: requireAuth(ctx), config: { rateLimit: { max, timeWindow: "1 minute" } } });
  const member = (orgId: string, userId: string) => {
    if (!ctx.authz.orgRole(orgId, userId)) throw new ApiError(404, "not_found", "Not found, or not visible to you.");
  };
  const manage = (orgId: string, userId: string) => ctx.authz.requirePermission(orgId, userId, "ai.manage", "Managing AI agents needs the \"Manage AI agents\" permission.");
  const installOf = (orgId: string, installId: string) => {
    const i = w.store.install(installId);
    if (!i || i.orgId !== orgId || i.state === "uninstalled") throw new ApiError(404, "not_found", "No such agent in this community.");
    return i;
  };

  app.get("/v1/orgs/:orgId/ai/agents/:installId/readiness", auth, async (req) => {
    const { userId } = me(req);
    const { orgId, installId } = parse(AiInstallParams, req.params);
    member(orgId, userId);
    return { readiness: await readiness(w, installOf(orgId, installId)) };
  });

  app.get("/v1/orgs/:orgId/ai/agents/:installId/activity", auth, async (req) => {
    const { userId } = me(req);
    const { orgId, installId } = parse(AiInstallParams, req.params);
    member(orgId, userId);
    const a = activity(w, installOf(orgId, installId));
    // Triggers (their requests and who they run as) are for people who manage agents; others see the counts.
    return { activity: ctx.authz.can(orgId, userId, "ai.manage") ? a : { ...a, triggers: [] } };
  });

  // A real request through the real path (quotas, model routing, the agent's skill), asking only for a short reply.
  app.post("/v1/orgs/:orgId/ai/agents/:installId/test", limited(10), async (req, reply) => {
    const { userId } = me(req);
    const { orgId, installId } = parse(AiInstallParams, req.params);
    manage(orgId, userId);
    const job = w.service.createJob({ orgId, installId: installOf(orgId, installId).id, userId, input: { brief: TEST_BRIEF }, conversationId: null, threadRoot: null, fileIds: [], idempotencyKey: null, requestText: null });
    reply.code(202);
    return { job: jobDetail(w, job) };
  });

  app.get("/v1/orgs/:orgId/ai/agents/:installId/triggers", auth, async (req) => {
    const { userId } = me(req);
    const { orgId, installId } = parse(AiInstallParams, req.params);
    manage(orgId, userId);
    return { triggers: w.triggers.ofInstall(installOf(orgId, installId).id).map(triggerView) };
  });

  app.post("/v1/orgs/:orgId/ai/agents/:installId/triggers", limited(30), async (req, reply) => {
    const { userId } = me(req);
    const { orgId, installId } = parse(AiInstallParams, req.params);
    manage(orgId, userId);
    const { row, secret } = w.automation.create(orgId, installId, userId, parse(AiTriggerBody, req.body));
    reply.code(201);
    // The secret is shown this once; only its hash is stored.
    return { trigger: triggerView(row), secret, hookUrl: row.kind === "webhook" ? `/v1/ai/hooks/${row.id}` : null };
  });

  app.patch("/v1/orgs/:orgId/ai/agents/:installId/triggers/:triggerId", limited(60), async (req) => {
    const { userId } = me(req);
    const { orgId, installId, triggerId } = parse(AiTriggerParams, req.params);
    manage(orgId, userId);
    return { trigger: triggerView(w.automation.update(orgId, installId, triggerId, userId, parse(AiTriggerPatch, req.body))) };
  });

  app.delete("/v1/orgs/:orgId/ai/agents/:installId/triggers/:triggerId", limited(60), async (req) => {
    const { userId } = me(req);
    const { orgId, installId, triggerId } = parse(AiTriggerParams, req.params);
    manage(orgId, userId);
    w.automation.remove(orgId, installId, triggerId, userId);
    return { removed: true as const };
  });

  app.post("/v1/orgs/:orgId/ai/agents/:installId/triggers/:triggerId/run", limited(10), async (req, reply) => {
    const { userId } = me(req);
    const { orgId, installId, triggerId } = parse(AiTriggerParams, req.params);
    manage(orgId, userId);
    const job = w.automation.runNow(orgId, installId, triggerId, userId);
    reply.code(202);
    return { job: jobDetail(w, job) };
  });

  // Outside systems: no session; "Authorization: Bearer <the trigger's secret>". Same answer for unknown and wrong.
  app.post("/v1/ai/hooks/:triggerId", { config: { rateLimit: { max: 30, timeWindow: "1 minute" } } }, async (req, reply) => {
    const { triggerId } = parse(AiHookParams, req.params);
    const auth = String(req.headers.authorization ?? "");
    const secret = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
    if (!secret) throw new ApiError(404, "not_found", "Not found.");
    const b = parse(AiHookBody, req.body ?? {});
    const key = typeof req.headers["idempotency-key"] === "string" ? req.headers["idempotency-key"].slice(0, 100) : null;
    const r = w.automation.webhook(triggerId, secret, b.text, key);
    reply.code(r.started ? 202 : 200);
    return r;
  });
}
