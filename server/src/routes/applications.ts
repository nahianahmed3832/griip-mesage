import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { type AppContext, me, parse, requireAuth } from "../context.js";
import { ApiError, badRequest, conflict, forbidden, newId, notFound, now } from "../util.js";
import { noteJoin } from "../workspace/activityAlerts.js";
import { notifyOrg } from "./orgs.js";

/**
 * Join applications (batch B4b, Discord reference ADMIN-CTRL-008 "Apply to Join"): a public community can ask up to five
 * questions; people answer and wait; holders of `members.approve` approve (they join, with the same checks as joining
 * openly: bans, paused joining, the member limit) or deny. Answers are plaintext the applicant writes for moderators.
 */
export const MAX_QUESTIONS = 5;
const zId = z.string().min(1).max(64);

interface ApplicationRow {
  id: string;
  org_id: string;
  user_id: string;
  answers: string;
  status: "pending" | "approved" | "denied" | "withdrawn";
  created_at: number;
  handled_by: string | null;
  handled_at: number | null;
  note: string | null;
}

/** A community's questions, or null when it doesn't take applications. */
export function joinQuestions(org: { join_questions?: string | null }): string[] | null {
  if (!org.join_questions) return null;
  try {
    const q = JSON.parse(org.join_questions);
    return Array.isArray(q) ? q.filter((x): x is string => typeof x === "string") : null;
  } catch {
    return null;
  }
}

function approvers(ctx: AppContext, orgId: string): string[] {
  const members = ctx.db.prepare("SELECT user_id FROM org_members WHERE org_id = ?").all(orgId) as { user_id: string }[];
  return members.map((m) => m.user_id).filter((u) => ctx.authz.can(orgId, u, "members.approve"));
}

export default async function applicationRoutes(app: FastifyInstance, ctx: AppContext) {
  const { db, authz } = ctx;
  const auth = { preHandler: requireAuth(ctx) };

  const view = (r: ApplicationRow) => {
    const u = db.prepare("SELECT id, username, display_name FROM users WHERE id = ?").get(r.user_id) as { id: string; username: string; display_name: string } | undefined;
    const by = r.handled_by ? (db.prepare("SELECT display_name FROM users WHERE id = ?").get(r.handled_by) as { display_name: string } | undefined) : undefined;
    const org = db.prepare("SELECT join_questions FROM orgs WHERE id = ?").get(r.org_id) as { join_questions: string | null } | undefined;
    return {
      id: r.id,
      orgId: r.org_id,
      user: u ? { id: u.id, username: u.username, displayName: u.display_name } : { id: r.user_id, username: null, displayName: null },
      questions: org ? (joinQuestions(org) ?? []) : [],
      answers: JSON.parse(r.answers) as string[],
      status: r.status,
      createdAt: r.created_at,
      handledAt: r.handled_at,
      handledByName: by?.display_name ?? null,
      note: r.note,
    };
  };

  /** Turns applications on with these questions, changes them, or turns them off (null). */
  app.put("/v1/orgs/:orgId/application", auth, async (req) => {
    const { orgId } = parse(z.object({ orgId: zId }), req.params);
    const actor = me(req).userId;
    const body = parse(z.object({ questions: z.array(z.string().trim().min(1).max(200)).min(1).max(MAX_QUESTIONS).nullable() }).strict(), req.body);
    authz.requireOrgMember(orgId, actor);
    if (!authz.can(orgId, actor, "workspace.edit")) throw forbidden("Your role doesn't include changing how people join");
    db.prepare("UPDATE orgs SET join_questions = ? WHERE id = ?").run(body.questions ? JSON.stringify(body.questions) : null, orgId);
    ctx.audit({ actorId: actor, orgId, action: body.questions ? "community.applications_on" : "community.applications_off", target: orgId });
    notifyOrg(ctx, orgId);
    return { questions: body.questions };
  });

  app.post("/v1/communities/:id/apply", { preHandler: requireAuth(ctx), config: { rateLimit: { max: 5, timeWindow: "1 minute" } } }, async (req) => {
    const userId = me(req).userId;
    const { id } = parse(z.object({ id: zId }), req.params);
    const body = parse(z.object({ answers: z.array(z.string().trim().max(1000)).max(MAX_QUESTIONS) }).strict(), req.body);
    const o = db.prepare("SELECT * FROM orgs WHERE id = ?").get(id) as any;
    const questions = o ? joinQuestions(o) : null;
    if (!o || o.visibility !== "public" || !questions) throw notFound("This community doesn't take applications");
    if (authz.orgRole(id, userId)) throw conflict("You're already a member");
    if (authz.isBanned(id, userId)) throw new ApiError(403, "banned", "You've been banned from this community");
    if (authz.pausedUntil(id, "invites", now())) throw new ApiError(403, "invites_paused", "This community has paused new members for now. Try again later.");
    if (body.answers.length !== questions.length || body.answers.some((a) => !a)) throw badRequest("Answer every question");
    const pending = db.prepare("SELECT * FROM join_applications WHERE org_id = ? AND user_id = ? AND status = 'pending'").get(id, userId) as ApplicationRow | undefined;
    if (pending) return { application: view(pending), repeated: true };
    const appId = newId("apl");
    db.prepare("INSERT INTO join_applications (id, org_id, user_id, answers, created_at) VALUES (?, ?, ?, ?, ?)").run(appId, id, userId, JSON.stringify(body.answers), now());
    ctx.hub.sendToUsers(approvers(ctx, id), { t: "org.applications", orgId: id });
    return { application: view(db.prepare("SELECT * FROM join_applications WHERE id = ?").get(appId) as ApplicationRow), repeated: false };
  });

  app.get("/v1/orgs/:orgId/applications", auth, async (req) => {
    const { orgId } = parse(z.object({ orgId: zId }), req.params);
    const actor = me(req).userId;
    const q = parse(z.object({ status: z.enum(["pending", "closed"]).default("pending") }), req.query);
    authz.requireOrgMember(orgId, actor);
    authz.requirePermission(orgId, actor, "members.approve", "Your role doesn't include applications");
    const rows = db
      .prepare(`SELECT * FROM join_applications WHERE org_id = ? AND (status = 'pending') = ? ORDER BY created_at DESC LIMIT 100`)
      .all(orgId, q.status === "pending" ? 1 : 0) as ApplicationRow[];
    const pending = (db.prepare("SELECT COUNT(*) n FROM join_applications WHERE org_id = ? AND status = 'pending'").get(orgId) as { n: number }).n;
    return { applications: rows.map(view), pending };
  });

  app.patch("/v1/applications/:id", auth, async (req) => {
    const actor = me(req).userId;
    const { id } = parse(z.object({ id: zId }), req.params);
    const body = parse(z.object({ status: z.enum(["approved", "denied"]), note: z.string().trim().max(300).optional() }).strict(), req.body);
    const r = db.prepare("SELECT * FROM join_applications WHERE id = ?").get(id) as ApplicationRow | undefined;
    if (!r || !authz.can(r.org_id, actor, "members.approve")) throw notFound("Application not found");
    if (r.status !== "pending") throw conflict("This application was already answered");
    const t = now();
    if (body.status === "approved") {
      if (authz.isBanned(r.org_id, r.user_id)) throw new ApiError(409, "banned", "This person is banned from the community");
      db.transaction(() => {
        ctx.entitlements.check("members", (db.prepare("SELECT COUNT(*) n FROM org_members WHERE org_id = ?").get(r.org_id) as { n: number }).n, r.org_id);
        db.prepare("INSERT OR IGNORE INTO org_members (org_id, user_id, role, joined_at) VALUES (?, ?, 'MEMBER', ?)").run(r.org_id, r.user_id, t);
        authz.autoJoinScope(r.org_id, r.user_id, t);
        db.prepare("UPDATE join_applications SET status = 'approved', handled_by = ?, handled_at = ?, note = ? WHERE id = ?").run(actor, t, body.note ?? null, id);
      })();
      noteJoin(ctx, r.org_id, t);
    } else {
      db.prepare("UPDATE join_applications SET status = 'denied', handled_by = ?, handled_at = ?, note = ? WHERE id = ?").run(actor, t, body.note ?? null, id);
    }
    ctx.audit({ actorId: actor, orgId: r.org_id, action: `application.${body.status}`, target: r.user_id });
    notifyOrg(ctx, r.org_id);
    ctx.hub.sendToUser(r.user_id, { t: "org.application", orgId: r.org_id, status: body.status });
    return { application: view(db.prepare("SELECT * FROM join_applications WHERE id = ?").get(id) as ApplicationRow) };
  });

  /** The person's own applications (pending and answered), newest first. */
  app.get("/v1/me/applications", auth, async (req) => {
    const userId = me(req).userId;
    const rows = db.prepare("SELECT * FROM join_applications WHERE user_id = ? ORDER BY created_at DESC LIMIT 50").all(userId) as ApplicationRow[];
    return {
      applications: rows.map((r) => ({
        id: r.id,
        orgId: r.org_id,
        orgName: (db.prepare("SELECT name FROM orgs WHERE id = ?").get(r.org_id) as { name: string } | undefined)?.name ?? null,
        status: r.status,
        createdAt: r.created_at,
      })),
    };
  });

  app.delete("/v1/applications/:id", auth, async (req) => {
    const userId = me(req).userId;
    const { id } = parse(z.object({ id: zId }), req.params);
    const r = db.prepare("SELECT * FROM join_applications WHERE id = ?").get(id) as ApplicationRow | undefined;
    if (!r || r.user_id !== userId) throw notFound("Application not found");
    if (r.status === "pending") db.prepare("UPDATE join_applications SET status = 'withdrawn', handled_at = ? WHERE id = ?").run(now(), id);
    return { ok: true };
  });
}
