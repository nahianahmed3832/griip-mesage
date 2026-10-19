import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { PERSONAL_WORKSPACE_ID } from "../accounts/workspaces.js";
import { type AppContext, me, parse, requireAuth } from "../context.js";
import { ApiError, now } from "../util.js";
import { EMPTY_PREFS, normalizePrefs, type StoredPrefs, zNavPrefs } from "./schema.js";

const zWorkspaceId = z.string().min(1).max(64).regex(/^[A-Za-z0-9_-]+$/);

function view(workspaceId: string, s: StoredPrefs | null) {
  return { workspaceId, rev: s?.rev ?? 0, updatedAt: s?.updatedAt ?? null, prefs: s?.prefs ?? EMPTY_PREFS };
}

/**
 * Personal navigation preferences (docs/WORKSPACE_ADMINISTRATION.md › Personal sections): starred conversations, custom
 * sections, collapsed groups and notification levels, kept on the server so every device of the person shows the same
 * Home. Only the person themselves can read or write theirs.
 */
export default async function preferenceRoutes(app: FastifyInstance, ctx: AppContext) {
  const auth = { preHandler: requireAuth(ctx) };

  /** "personal", or an organisation the person belongs to. Same 404 for one that doesn't exist and one they aren't in. */
  const requireWorkspace = (workspaceId: string, userId: string) => {
    if (workspaceId === PERSONAL_WORKSPACE_ID) return;
    if (!ctx.authz.orgRole(workspaceId, userId)) throw new ApiError(404, "workspace_not_found", "Workspace not found");
  };

  app.get("/v1/me/preferences/:workspaceId", auth, async (req) => {
    const { userId } = me(req);
    const { workspaceId } = parse(z.object({ workspaceId: zWorkspaceId }), req.params);
    requireWorkspace(workspaceId, userId);
    return view(workspaceId, await ctx.prefs.get(userId, workspaceId));
  });

  app.put("/v1/me/preferences/:workspaceId", { ...auth, config: { rateLimit: { max: 120, timeWindow: "1 minute" } } }, async (req) => {
    const { userId, deviceId } = me(req);
    const { workspaceId } = parse(z.object({ workspaceId: zWorkspaceId }), req.params);
    const body = parse(z.object({ baseRev: z.number().int().min(0), prefs: zNavPrefs }), req.body);
    requireWorkspace(workspaceId, userId);
    // Only the person's own conversations can be arranged; anything else (left meanwhile, never joined) is dropped.
    const mine = new Set(
      (ctx.db.prepare("SELECT conversation_id FROM conversation_members WHERE user_id = ?").all(userId) as { conversation_id: string }[]).map(
        (r) => r.conversation_id,
      ),
    );
    const prefs = normalizePrefs(body.prefs, (id) => mine.has(id));
    const result = await ctx.prefs.put(userId, workspaceId, prefs, body.baseRev, now());
    if (!result.ok) {
      throw new ApiError(409, "stale_preferences", "These preferences changed on another device. Apply your change to this copy and try again.", {
        current: view(workspaceId, result.current),
      });
    }
    // The person's other devices fetch the new copy (no content in the frame).
    ctx.hub.sendToUser(userId, { t: "prefs.updated", workspaceId, rev: result.doc.rev }, deviceId);
    return view(workspaceId, result.doc);
  });
}
