import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { type AppContext, me, parse, requireAuth } from "../context.js";
import { badRequest, conflict, forbidden, notFound } from "../util.js";
import { MEETING_LIMITS } from "../workspace/meetings.js";
import { applyWorkspaceTemplate, WORKSPACE_TEMPLATES } from "../workspace/templates.js";

/**
 * Round 7 slice C5 (docs/WORKSPACE_C5.md): workspace templates, user groups, guest end dates and policy, meetings.
 * Each service checks permissions itself; these routes only parse.
 */
export default async function workspaceC5Routes(app: FastifyInstance, ctx: AppContext) {
  const auth = { preHandler: requireAuth(ctx) };
  const { db, authz } = ctx;
  const zOrg = z.object({ orgId: z.string().max(64) });

  // ───────────── Templates ─────────────
  app.get("/v1/workspace-templates", auth, async () => ({ templates: WORKSPACE_TEMPLATES }));

  app.post("/v1/orgs/:orgId/apply-template", auth, async (req) => {
    const { orgId } = parse(zOrg, req.params);
    const { templateId } = parse(z.object({ templateId: z.string().max(40) }), req.body);
    authz.requireOrgMember(orgId, me(req).userId);
    return { applied: applyWorkspaceTemplate(ctx, orgId, me(req).userId, templateId) };
  });

  // ───────────── Groups ─────────────
  const zGroup = z.object({ name: z.string().trim().min(1).max(60), description: z.string().trim().max(300).nullish(), memberIds: z.array(z.string().max(64)).max(500).default([]) });

  app.get("/v1/orgs/:orgId/groups", auth, async (req) => ({ groups: ctx.groups.list(parse(zOrg, req.params).orgId, me(req).userId) }));

  app.post("/v1/orgs/:orgId/groups", auth, async (req) => ({ group: ctx.groups.create(parse(zOrg, req.params).orgId, me(req).userId, parse(zGroup, req.body)) }));

  app.put("/v1/orgs/:orgId/groups/:id", auth, async (req) => {
    const p = parse(z.object({ orgId: z.string().max(64), id: z.string().max(64) }), req.params);
    return { group: ctx.groups.update(p.orgId, me(req).userId, p.id, parse(zGroup, req.body)) };
  });

  app.delete("/v1/orgs/:orgId/groups/:id", auth, async (req) => {
    const p = parse(z.object({ orgId: z.string().max(64), id: z.string().max(64) }), req.params);
    ctx.groups.remove(p.orgId, me(req).userId, p.id);
    return { ok: true };
  });

  app.post("/v1/conversations/:id/add-group", auth, async (req) => {
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const { groupId } = parse(z.object({ groupId: z.string().max(64) }), req.body);
    return ctx.groups.addToChannel(id, me(req).userId, groupId);
  });

  // ───────────── Guests ─────────────
  app.put("/v1/orgs/:orgId/guest-policy", auth, async (req) => {
    const { orgId } = parse(zOrg, req.params);
    const actor = me(req).userId;
    authz.requirePermission(orgId, actor, "workspace.edit", "Only people who can edit the workspace can change the guest policy");
    const body = parse(z.object({ guestsAllowed: z.boolean(), guestMaxDays: z.number().int().min(1).max(365).nullable() }), req.body);
    db.prepare("UPDATE orgs SET guests_allowed = ?, guest_max_days = ?, updated_at = ? WHERE id = ?").run(body.guestsAllowed ? 1 : 0, body.guestMaxDays, Date.now(), orgId);
    // A shorter maximum brings current guests' end dates forward; turning guests off doesn't remove anyone by itself.
    if (body.guestMaxDays) {
      const cap = Date.now() + body.guestMaxDays * 86_400_000;
      db.prepare("UPDATE org_members SET expires_at = ? WHERE org_id = ? AND role = 'GUEST' AND (expires_at IS NULL OR expires_at > ?)").run(cap, orgId, cap);
    }
    ctx.audit({ actorId: actor, orgId, action: "org.guest_policy", detail: `${body.guestsAllowed ? "allowed" : "off"} ${body.guestMaxDays ?? "∞"}` });
    return { guestsAllowed: body.guestsAllowed, guestMaxDays: body.guestMaxDays };
  });

  /** A guest's end date (null: none, within the policy's maximum). Members and admins have no end date. */
  app.put("/v1/orgs/:orgId/members/:userId/expiry", auth, async (req) => {
    const p = parse(z.object({ orgId: z.string().max(64), userId: z.string().max(64) }), req.params);
    const actor = me(req).userId;
    if (!authz.can(p.orgId, actor, "members.roles")) throw forbidden("Only people who can change member access can set a guest's end date");
    const role = authz.orgRole(p.orgId, p.userId);
    if (!role) throw notFound("Member not found");
    if (role !== "GUEST") throw badRequest("Only guests have an end date");
    const { expiresAt } = parse(z.object({ expiresAt: z.number().int().nullable() }), req.body);
    const policy = db.prepare("SELECT guest_max_days FROM orgs WHERE id = ?").get(p.orgId) as { guest_max_days: number | null };
    if (expiresAt !== null && expiresAt <= Date.now()) throw badRequest("Pick a date in the future, or remove the guest instead");
    if (policy.guest_max_days && (expiresAt === null || expiresAt > Date.now() + policy.guest_max_days * 86_400_000)) {
      throw conflict(`This workspace keeps guests for at most ${policy.guest_max_days} days`);
    }
    db.prepare("UPDATE org_members SET expires_at = ? WHERE org_id = ? AND user_id = ?").run(expiresAt, p.orgId, p.userId);
    ctx.audit({ actorId: actor, orgId: p.orgId, action: "member.expiry", target: p.userId });
    return { expiresAt };
  });

  // ───────────── Meetings ─────────────
  const zMeeting = z.object({
    roomId: z.string().max(64),
    title: z.string().trim().min(1).max(MEETING_LIMITS.titleChars),
    description: z.string().trim().max(MEETING_LIMITS.descriptionChars).nullish(),
    startsAt: z.number().int(),
    endsAt: z.number().int(),
    timeZone: z.string().max(64),
    participantIds: z.array(z.string().max(64)).max(MEETING_LIMITS.participants).default([]),
    groupIds: z.array(z.string().max(64)).max(20).default([]),
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
