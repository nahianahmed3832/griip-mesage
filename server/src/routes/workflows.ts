import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { type AppContext, me, parse, requireAuth } from "../context.js";
import { CATALOG, TEMPLATES, zWorkflowInput } from "../workflows/model.js";

/**
 * Workspace workflows (docs/WORKFLOWS.md, round 7 slice C3). Everything here needs `workflows.manage` in the workspace
 * (owners and admins by default, or a custom role that grants it).
 */
export default async function workflowRoutes(app: FastifyInstance, ctx: AppContext) {
  const auth = { preHandler: requireAuth(ctx) };
  const wf = ctx.workflows;
  const zOrg = z.object({ orgId: z.string().max(64) });
  const zOne = z.object({ orgId: z.string().max(64), id: z.string().max(64) });

  app.get("/v1/orgs/:orgId/workflows/catalog", auth, async (req) => {
    const { orgId } = parse(zOrg, req.params);
    wf.requireManager(orgId, me(req).userId);
    return { catalog: CATALOG, templates: TEMPLATES };
  });

  app.get("/v1/orgs/:orgId/workflows", auth, async (req) => {
    const { orgId } = parse(zOrg, req.params);
    wf.requireManager(orgId, me(req).userId);
    return { workflows: wf.list(orgId).map((w) => wf.view(w)) };
  });

  app.post("/v1/orgs/:orgId/workflows", auth, async (req) => {
    const { orgId } = parse(zOrg, req.params);
    const actor = me(req).userId;
    wf.requireManager(orgId, actor);
    return { workflow: wf.view(wf.create(orgId, actor, parse(zWorkflowInput, req.body))) };
  });

  /** Whoever saves becomes the owner: the workflow then runs with their rights. */
  app.put("/v1/orgs/:orgId/workflows/:id", auth, async (req) => {
    const p = parse(zOne, req.params);
    const actor = me(req).userId;
    wf.requireManager(p.orgId, actor);
    const body = parse(zWorkflowInput.extend({ rev: z.number().int().min(1) }), req.body);
    return { workflow: wf.view(wf.update(p.orgId, actor, p.id, body.rev, body)) };
  });

  app.delete("/v1/orgs/:orgId/workflows/:id", auth, async (req) => {
    const p = parse(zOne, req.params);
    const actor = me(req).userId;
    wf.requireManager(p.orgId, actor);
    wf.remove(p.orgId, actor, p.id);
    return { ok: true };
  });

  /** A dry run: what the (unsaved) workflow would do for a sample event, changing nothing. */
  app.post("/v1/orgs/:orgId/workflows/test", auth, async (req) => {
    const { orgId } = parse(zOrg, req.params);
    const actor = me(req).userId;
    wf.requireManager(orgId, actor);
    const body = parse(zWorkflowInput, req.body);
    return { test: await wf.dryRun(orgId, actor, body, body.name) };
  });

  app.get("/v1/orgs/:orgId/workflows/:id/runs", auth, async (req) => {
    const p = parse(zOne, req.params);
    wf.requireManager(p.orgId, me(req).userId);
    return { runs: wf.runs(p.orgId, p.id) };
  });

  app.post("/v1/orgs/:orgId/workflow-runs/:id/cancel", auth, async (req) => {
    const p = parse(zOne, req.params);
    const actor = me(req).userId;
    wf.requireManager(p.orgId, actor);
    wf.cancel(p.orgId, actor, p.id);
    return { ok: true };
  });
}
