import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { type AppContext, me, parse, requireAuth } from "../context.js";
import { MEETING_LIMITS } from "../workspace/meetings.js";

/**
 * Scheduled meetings in voice channels (round 7, docs/WORKSPACE_C5.md), shown as community events in the app. The
 * service checks permissions itself; these routes only parse.
 */
export default async function meetingRoutes(app: FastifyInstance, ctx: AppContext) {
  const auth = { preHandler: requireAuth(ctx) };
  const zOrg = z.object({ orgId: z.string().max(64) });
  const zMeeting = z.object({
    roomId: z.string().max(64),
    title: z.string().trim().min(1).max(MEETING_LIMITS.titleChars),
    description: z.string().trim().max(MEETING_LIMITS.descriptionChars).nullish(),
    startsAt: z.number().int(),
    endsAt: z.number().int(),
    timeZone: z.string().max(64),
    participantIds: z.array(z.string().max(64)).max(MEETING_LIMITS.participants).default([]),
    // User groups were removed with the rebuild; older apps send an empty list.
    groupIds: z.array(z.string().max(64)).max(0, "User groups were removed: invite people instead").default([]),
  });

  app.get("/v1/orgs/:orgId/meetings", auth, async (req) => {
    const { orgId } = parse(zOrg, req.params);
    const q = parse(z.object({ from: z.coerce.number().int().optional(), to: z.coerce.number().int().optional() }), req.query);
    const from = q.from ?? Date.now() - 3_600_000;
    return { meetings: ctx.meetings.list(orgId, me(req).userId, from, q.to ?? from + 30 * 86_400_000) };
  });

  app.post("/v1/orgs/:orgId/meetings", auth, async (req) => ({ meeting: ctx.meetings.create(parse(zOrg, req.params).orgId, me(req).userId, parse(zMeeting, req.body)) }));

  app.get("/v1/meetings/:id", auth, async (req) => ({ meeting: ctx.meetings.get(parse(z.object({ id: z.string().max(64) }), req.params).id, me(req).userId) }));

  app.put("/v1/meetings/:id", auth, async (req) => {
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const body = parse(zMeeting.extend({ rev: z.number().int().min(1) }), req.body);
    return { meeting: ctx.meetings.update(id, me(req).userId, body.rev, body) };
  });

  app.post("/v1/meetings/:id/cancel", auth, async (req) => ({ meeting: ctx.meetings.cancel(parse(z.object({ id: z.string().max(64) }), req.params).id, me(req).userId) }));

  app.post("/v1/meetings/:id/respond", auth, async (req) => {
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const { response } = parse(z.object({ response: z.enum(["accepted", "declined", "tentative"]) }), req.body);
    return { meeting: ctx.meetings.respond(id, me(req).userId, response) };
  });
}
