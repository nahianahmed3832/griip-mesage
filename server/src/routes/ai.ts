import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { type AppContext, me, parse, requireAuth } from "../context.js";
import { AI_PROVIDER_IDS } from "../ai/provider.js";
import { WRITE_MODES, zCatchUpMessage } from "../ai/prompts.js";

/**
 * AI (docs/AI.md, round 7 slice C8). Every request that sends text needs `consent: true`: the app asks each time,
 * naming the provider that will receive it. The server keeps none of what it sends or receives.
 */
export default async function aiRoutes(app: FastifyInstance, ctx: AppContext) {
  const auth = { preHandler: requireAuth(ctx) };
  const ai = ctx.ai;
  const zOrg = z.object({ orgId: z.string().max(64) });
  const zConsent = z.boolean();

  app.get("/v1/orgs/:orgId/ai", auth, async (req) => {
    const { orgId } = parse(zOrg, req.params);
    return { ai: ai.status(orgId, me(req).userId) };
  });

  app.put("/v1/orgs/:orgId/ai", auth, async (req) => {
    const { orgId } = parse(zOrg, req.params);
    // provider: one of the configured ones, or null / "default" for the server's default (apps drop null fields).
    const body = parse(z.object({ enabled: z.boolean(), provider: z.enum([...AI_PROVIDER_IDS, "default"]).nullable().optional() }), req.body);
    const provider = body.provider === "default" ? null : body.provider;
    return { ai: ai.setPolicy(orgId, me(req).userId, { enabled: body.enabled, provider }) };
  });

  app.post("/v1/orgs/:orgId/ai/catch-up", auth, async (req) => {
    const { orgId } = parse(zOrg, req.params);
    const body = parse(z.object({ consent: zConsent, channelId: z.string().max(64), messages: z.array(zCatchUpMessage).min(1).max(300) }), req.body);
    return { result: await ai.catchUp(orgId, me(req).userId, body) };
  });

  app.post("/v1/orgs/:orgId/ai/write", auth, async (req) => {
    const { orgId } = parse(zOrg, req.params);
    const body = parse(z.object({ consent: zConsent, mode: z.enum(WRITE_MODES), text: z.string().trim().min(1).max(8000) }), req.body);
    return { result: await ai.write(orgId, me(req).userId, body) };
  });

  app.post("/v1/documents/:id/ai/summary", auth, async (req) => {
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const body = parse(z.object({ consent: zConsent }), req.body);
    return { result: await ai.documentSummary(id, me(req).userId, body.consent) };
  });

  app.post("/v1/orgs/:orgId/ai/ask", auth, async (req) => {
    const { orgId } = parse(zOrg, req.params);
    const body = parse(z.object({ consent: zConsent, question: z.string().trim().min(3).max(500) }), req.body);
    return { result: await ai.ask(orgId, me(req).userId, body) };
  });

  app.post("/v1/orgs/:orgId/ai/workflow-draft", auth, async (req) => {
    const { orgId } = parse(zOrg, req.params);
    const body = parse(z.object({ consent: zConsent, description: z.string().trim().min(5).max(1000), timeZone: z.string().max(64).default("UTC") }), req.body);
    return { result: await ai.workflowDraft(orgId, me(req).userId, body) };
  });
}
