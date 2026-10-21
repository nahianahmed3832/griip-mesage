import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { type AppContext, me, parse, requireAuth } from "../context.js";
import { ApiError, forbidden, newId } from "../util.js";
import { notifyOrg } from "./orgs.js";

/**
 * The community guide (batch B8b, matrix COM-17, Discord's Server Guide): a welcome line, up to five "Get started"
 * tasks that each open a channel, and up to five resources. Plaintext configuration like the community's rules; set
 * by people who can edit the community. Which tasks someone did is kept on their phone.
 */
export const GUIDE_LIMITS = { welcome: 500, items: 5, title: 80 } as const;

export interface GuideItem {
  id: string;
  title: string;
  channelId: string;
}
export interface Guide {
  welcome: string | null;
  tasks: GuideItem[];
  resources: GuideItem[];
}

/**
 * A community's guide as [viewerId] sees it, or null without one. Items whose channel is gone are left out, and so are
 * channels the viewer can't see (B13: a private channel's id reached every member), except for people who edit the
 * guide, so saving it never drops what they can't open.
 */
export function guideOf(ctx: AppContext, org: { id: string; guide?: string | null }, viewerId: string): Guide | null {
  if (!org.guide) return null;
  try {
    const g = JSON.parse(org.guide) as Guide;
    const editor = ctx.authz.can(org.id, viewerId, "workspace.edit");
    const live = (i: GuideItem) => {
      const c = ctx.authz.conversation(i.channelId);
      return !!c && c.org_id === org.id && !c.archived_at && (editor || ctx.authz.canViewChannel(c, viewerId));
    };
    return { welcome: g.welcome ?? null, tasks: (g.tasks ?? []).filter(live), resources: (g.resources ?? []).filter(live) };
  } catch {
    return null;
  }
}

export default async function guideRoutes(app: FastifyInstance, ctx: AppContext) {
  const { db, authz } = ctx;
  const auth = { preHandler: requireAuth(ctx) };
  const zItem = z.object({ id: z.string().max(32).optional(), title: z.string().trim().min(1).max(GUIDE_LIMITS.title), channelId: z.string().min(1).max(64) });

  app.put("/v1/orgs/:orgId/guide", auth, async (req) => {
    const { orgId } = parse(z.object({ orgId: z.string().min(1).max(64) }), req.params);
    const userId = me(req).userId;
    const body = parse(
      z
        .object({
          welcome: z.string().trim().max(GUIDE_LIMITS.welcome).nullable(),
          tasks: z.array(zItem).max(GUIDE_LIMITS.items),
          resources: z.array(zItem).max(GUIDE_LIMITS.items),
        })
        .strict()
        .nullable(),
      req.body,
    );
    authz.requireOrgMember(orgId, userId);
    if (!authz.can(orgId, userId, "workspace.edit")) throw forbidden("Your role doesn't include changing the community guide");
    const items = (list: z.infer<typeof zItem>[]) =>
      list.map((i) => {
        const c = authz.conversation(i.channelId);
        if (!c || c.type !== "CHANNEL" || c.org_id !== orgId) throw new ApiError(400, "channel_not_found", "Pick a channel of this community", { channelId: i.channelId });
        return { id: i.id && /^[A-Za-z0-9_-]{1,32}$/.test(i.id) ? i.id : newId("g"), title: i.title, channelId: i.channelId };
      });
    const guide: Guide | null = body && { welcome: body.welcome || null, tasks: items(body.tasks), resources: items(body.resources) };
    const empty = !guide || (!guide.welcome && guide.tasks.length === 0 && guide.resources.length === 0);
    db.prepare("UPDATE orgs SET guide = ? WHERE id = ?").run(empty ? null : JSON.stringify(guide), orgId);
    ctx.audit({ actorId: userId, orgId, action: "community.guide", target: orgId });
    notifyOrg(ctx, orgId);
    return { guide: empty ? null : guide };
  });
}
