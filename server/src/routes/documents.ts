import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { type AppContext, me, parse, requireAuth } from "../context.js";
import { DOC_TEMPLATES, zComment, zCreate, zSharing, zUpdate } from "../documents/model.js";

/** Workspace documents (docs/DOCUMENTS.md, round 7 slice C4). Access is checked by the service on every call. */
export default async function documentRoutes(app: FastifyInstance, ctx: AppContext) {
  const auth = { preHandler: requireAuth(ctx) };
  const docs = ctx.documents;
  const zId = z.object({ id: z.string().max(64) });

  app.get("/v1/documents/templates", auth, async () => ({ templates: DOC_TEMPLATES }));

  app.get("/v1/orgs/:orgId/documents", auth, async (req) => {
    const { orgId } = parse(z.object({ orgId: z.string().max(64) }), req.params);
    const q = parse(z.object({ archived: z.enum(["true", "false"]).optional(), channelId: z.string().max(64).optional() }), req.query);
    return { documents: docs.list(orgId, me(req).userId, { archived: q.archived === "true", channelId: q.channelId }) };
  });

  app.post("/v1/orgs/:orgId/documents", auth, async (req) => {
    const { orgId } = parse(z.object({ orgId: z.string().max(64) }), req.params);
    return { document: docs.create(orgId, me(req).userId, parse(zCreate, req.body)) };
  });

  app.get("/v1/documents/:id", auth, async (req) => {
    const { id } = parse(zId, req.params);
    const { d, role } = docs.require(id, me(req).userId);
    return { document: docs.view(d, role) };
  });

  app.put("/v1/documents/:id", auth, async (req) => {
    const { id } = parse(zId, req.params);
    return { document: docs.update(id, me(req).userId, parse(zUpdate, req.body)) };
  });

  app.put("/v1/documents/:id/sharing", auth, async (req) => {
    const { id } = parse(zId, req.params);
    return { document: docs.share(id, me(req).userId, parse(zSharing, req.body)) };
  });

  app.post("/v1/documents/:id/archive", auth, async (req) => ({ document: docs.setArchived(parse(zId, req.params).id, me(req).userId, true) }));
  app.post("/v1/documents/:id/restore", auth, async (req) => ({ document: docs.setArchived(parse(zId, req.params).id, me(req).userId, false) }));

  app.delete("/v1/documents/:id", auth, async (req) => {
    docs.remove(parse(zId, req.params).id, me(req).userId);
    return { ok: true };
  });

  /** The document as a Markdown file: an explicit export, for people who may read it. */
  app.get("/v1/documents/:id/export", auth, async (req, reply) => {
    const { d } = docs.require(parse(zId, req.params).id, me(req).userId);
    const name = (d.title.replace(/[^\p{L}\p{N} ._-]+/gu, "").trim() || "document").slice(0, 80);
    reply.header("content-type", "text/markdown; charset=utf-8").header("content-disposition", `attachment; filename="${encodeURIComponent(name)}.md"`);
    return `# ${d.title}\n\n${d.content}`;
  });

  app.get("/v1/documents/:id/versions", auth, async (req) => ({ versions: docs.versions(parse(zId, req.params).id, me(req).userId) }));

  app.get("/v1/documents/:id/versions/:rev", auth, async (req) => {
    const p = parse(z.object({ id: z.string().max(64), rev: z.coerce.number().int().min(1) }), req.params);
    return { version: docs.version(p.id, me(req).userId, p.rev) };
  });

  app.post("/v1/documents/:id/versions/:rev/restore", auth, async (req) => {
    const p = parse(z.object({ id: z.string().max(64), rev: z.coerce.number().int().min(1) }), req.params);
    return { document: docs.restoreVersion(p.id, me(req).userId, p.rev) };
  });

  app.get("/v1/documents/:id/comments", auth, async (req) => ({ comments: docs.comments(parse(zId, req.params).id, me(req).userId) }));

  app.post("/v1/documents/:id/comments", auth, async (req) => ({ comment: docs.comment(parse(zId, req.params).id, me(req).userId, parse(zComment, req.body)) }));

  app.post("/v1/document-comments/:id/resolve", auth, async (req) => {
    const { resolved } = parse(z.object({ resolved: z.boolean().default(true) }), req.body ?? {});
    docs.resolveComment(parse(zId, req.params).id, me(req).userId, resolved);
    return { ok: true };
  });

  app.delete("/v1/document-comments/:id", auth, async (req) => {
    docs.deleteComment(parse(zId, req.params).id, me(req).userId);
    return { ok: true };
  });
}
