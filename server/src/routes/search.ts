import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { type AppContext, me, parse, requireAuth } from "../context.js";
import { search, SEARCH_KINDS, type SearchKind } from "../search/service.js";

/** Universal search over what the server can read (docs/SEARCH.md, round 7 slice C2). */
export default async function searchRoutes(app: FastifyInstance, ctx: AppContext) {
  const auth = { preHandler: requireAuth(ctx) };

  app.get("/v1/search", auth, async (req) => {
    const userId = me(req).userId;
    const p = parse(
      z.object({
        q: z.string().trim().min(1).max(60),
        // Comma-separated kinds; all of them when absent.
        types: z.string().max(80).optional(),
        orgId: z.string().max(64).optional(),
        limit: z.coerce.number().int().min(1).max(50).default(10),
        cursor: z.string().max(40).optional(),
      }),
      req.query,
    );
    const kinds = (p.types?.split(",").map((s) => s.trim()).filter(Boolean) ?? [...SEARCH_KINDS]).filter((k): k is SearchKind =>
      (SEARCH_KINDS as readonly string[]).includes(k),
    );
    // A workspace filter only for a workspace you're in (404 otherwise, like every other workspace route).
    if (p.orgId) ctx.authz.requireOrgMember(p.orgId, userId);
    return { query: p.q, results: search(ctx, userId, p.q, kinds, { orgId: p.orgId, limit: p.limit, cursor: p.cursor }) };
  });
}
