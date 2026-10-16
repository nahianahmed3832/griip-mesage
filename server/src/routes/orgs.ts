import type { FastifyInstance } from "fastify";
import { z } from "zod";
import type { BranchRow, ConversationRow, OrgRole, TeamRow } from "../authz.js";
import { type AppContext, me, parse, requireAuth, zName, zText } from "../context.js";
import { badRequest, conflict, forbidden, humanCode, newId, normalizeCode, notFound, now, sha256Hex } from "../util.js";
import { findOrgInvite, joinOrgByInvite } from "../invites.js";
import { userSummary } from "./auth.js";
import { evictRemovedMember } from "./calls.js";
import { conversationView } from "./conversations.js";

const zOptText = zText.nullable().optional();

function slugify(name: string): string {
  return (
    name
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-+|-+$/g, "")
      .slice(0, 40) || "org"
  );
}

export function orgSummary(ctx: AppContext, org: any, userId: string) {
  const role = ctx.authz.orgRole(org.id, userId);
  const memberCount = (ctx.db.prepare("SELECT COUNT(*) n FROM org_members WHERE org_id = ?").get(org.id) as { n: number }).n;
  return {
    id: org.id,
    name: org.name,
    slug: org.slug,
    description: org.description,
    myRole: role,
    memberCount,
    createdAt: org.created_at,
  };
}

/** Members who should be told that something in this org changed. */
function orgMemberIds(ctx: AppContext, orgId: string): string[] {
  return (ctx.db.prepare("SELECT user_id FROM org_members WHERE org_id = ?").all(orgId) as { user_id: string }[]).map((r) => r.user_id);
}

export function notifyOrg(ctx: AppContext, orgId: string) {
  ctx.hub.sendToUsers(orgMemberIds(ctx, orgId), { t: "org.updated", orgId });
}

export function notifyConversation(ctx: AppContext, convId: string, extraUserIds: string[] = []) {
  const ids = (ctx.db.prepare("SELECT user_id FROM conversation_members WHERE conversation_id = ?").all(convId) as { user_id: string }[]).map(
    (r) => r.user_id,
  );
  ctx.hub.sendToUsers(new Set([...ids, ...extraUserIds]), { t: "conv.updated", conversationId: convId });
}

export default async function orgRoutes(app: FastifyInstance, ctx: AppContext) {
  const auth = { preHandler: requireAuth(ctx) };
  const { authz, db } = ctx;

  const loadBranch = (id: string, userId: string): BranchRow => {
    const b = authz.branch(id);
    if (!b) throw notFound("Branch not found");
    authz.requireOrgMember(b.org_id, userId);
    return b;
  };
  const loadTeam = (id: string, userId: string): TeamRow => {
    const t = authz.team(id);
    if (!t) throw notFound("Team not found");
    authz.requireOrgMember(t.org_id, userId);
    return t;
  };

  // ───────────── Organisations ─────────────
  app.post("/v1/orgs", auth, async (req) => {
    const { userId } = me(req);
    const body = parse(z.object({ name: zName, description: zOptText }), req.body);
    const id = newId("o");
    const t = now();
    let slug = slugify(body.name);
    if (db.prepare("SELECT 1 FROM orgs WHERE slug = ?").get(slug)) slug = `${slug}-${humanCode(4).toLowerCase()}`;
    db.transaction(() => {
      db.prepare("INSERT INTO orgs (id, name, slug, description, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)").run(
        id,
        body.name,
        slug,
        body.description ?? null,
        userId,
        t,
        t,
      );
      db.prepare("INSERT INTO org_members (org_id, user_id, role, joined_at) VALUES (?, ?, 'OWNER', ?)").run(id, userId, t);
    })();
    ctx.audit({ actorId: userId, orgId: id, action: "org.create", target: id });
    return { org: orgSummary(ctx, db.prepare("SELECT * FROM orgs WHERE id = ?").get(id), userId) };
  });

  app.get("/v1/orgs", auth, async (req) => {
    const { userId } = me(req);
    const rows = db
      .prepare("SELECT o.* FROM orgs o JOIN org_members m ON m.org_id = o.id WHERE m.user_id = ? ORDER BY o.name")
      .all(userId);
    return { orgs: rows.map((o) => orgSummary(ctx, o, userId)) };
  });

  /** Full navigable structure visible to the caller: branches → departments → teams, plus permissions. */
  app.get("/v1/orgs/:orgId", auth, async (req) => {
    const { userId } = me(req);
    const { orgId } = parse(z.object({ orgId: z.string().max(64) }), req.params);
    const role = authz.requireOrgMember(orgId, userId);
    const org = db.prepare("SELECT * FROM orgs WHERE id = ?").get(orgId);
    const isAdmin = role === "OWNER" || role === "ADMIN";
    const branches = (db.prepare("SELECT * FROM branches WHERE org_id = ? AND archived_at IS NULL ORDER BY name").all(orgId) as BranchRow[]).map(
      (b) => ({
        id: b.id,
        name: b.name,
        description: b.description,
        location: b.location,
        myRole: authz.branchRole(b.id, userId),
        canAccess: authz.canAccessBranch(b, userId),
        canManage: authz.canManageBranch(b, userId),
        memberCount: (db.prepare("SELECT COUNT(*) n FROM branch_members WHERE branch_id = ?").get(b.id) as { n: number }).n,
      }),
    );
    const departments = (db.prepare("SELECT * FROM departments WHERE org_id = ? ORDER BY name").all(orgId) as any[])
      .filter((d) => {
        const b = authz.branch(d.branch_id);
        return b && authz.canAccessBranch(b, userId);
      })
      .map((d) => ({ id: d.id, branchId: d.branch_id, name: d.name, description: d.description }));
    const teams = (db.prepare("SELECT * FROM teams WHERE org_id = ? AND archived_at IS NULL ORDER BY name").all(orgId) as TeamRow[])
      .filter((t) => role !== "GUEST" && (isAdmin || authz.canAccessTeam(t, userId) || (t.branch_id && authz.branchRole(t.branch_id, userId))))
      .map((t) => ({
        id: t.id,
        name: t.name,
        description: t.description,
        branchId: t.branch_id,
        departmentId: t.department_id,
        crossBranch: t.branch_id === null,
        myRole: authz.teamRole(t.id, userId),
        canAccess: authz.canAccessTeam(t, userId),
        canManage: authz.canManageTeam(t, userId),
        memberCount: (db.prepare("SELECT COUNT(*) n FROM team_members WHERE team_id = ?").get(t.id) as { n: number }).n,
      }));
    const channelCount = (
      db.prepare("SELECT COUNT(*) n FROM conversations WHERE org_id = ? AND type = 'CHANNEL' AND archived_at IS NULL").get(orgId) as { n: number }
    ).n;
    return {
      org: orgSummary(ctx, org, userId),
      branches,
      departments,
      teams,
      stats: { members: orgSummary(ctx, org, userId).memberCount, teams: teams.length, channels: channelCount, branches: branches.length },
      permissions: {
        canManageOrg: isAdmin,
        canInvite: isAdmin || branches.some((b) => b.canManage) || teams.some((t) => t.canManage),
        canCreateChannels: isAdmin || branches.some((b) => b.canManage) || teams.some((t) => t.canManage),
        canViewDirectory: role !== "GUEST",
        isOwner: role === "OWNER",
      },
    };
  });

  app.patch("/v1/orgs/:orgId", auth, async (req) => {
    const { userId } = me(req);
    const { orgId } = parse(z.object({ orgId: z.string().max(64) }), req.params);
    authz.requireOrgAdmin(orgId, userId);
    const body = parse(z.object({ name: zName.optional(), description: zOptText }), req.body);
    const o = db.prepare("SELECT * FROM orgs WHERE id = ?").get(orgId) as any;
    db.prepare("UPDATE orgs SET name = ?, description = ?, updated_at = ? WHERE id = ?").run(
      body.name ?? o.name,
      body.description === undefined ? o.description : body.description,
      now(),
      orgId,
    );
    ctx.audit({ actorId: userId, orgId, action: "org.update" });
    notifyOrg(ctx, orgId);
    return { org: orgSummary(ctx, db.prepare("SELECT * FROM orgs WHERE id = ?").get(orgId), userId) };
  });

  // ───────────── Branches & departments ─────────────
  app.post("/v1/orgs/:orgId/branches", auth, async (req) => {
    const { userId } = me(req);
    const { orgId } = parse(z.object({ orgId: z.string().max(64) }), req.params);
    authz.requireOrgAdmin(orgId, userId);
    const body = parse(z.object({ name: zName, description: zOptText, location: zOptText }), req.body);
    const id = newId("b");
    const t = now();
    db.prepare("INSERT INTO branches (id, org_id, name, description, location, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)").run(
      id,
      orgId,
      body.name,
      body.description ?? null,
      body.location ?? null,
      t,
      t,
    );
    ctx.audit({ actorId: userId, orgId, action: "branch.create", target: id, detail: body.name });
    notifyOrg(ctx, orgId);
    return { branch: { id, orgId, name: body.name, description: body.description ?? null, location: body.location ?? null } };
  });

  app.patch("/v1/branches/:id", auth, async (req) => {
    const { userId } = me(req);
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const b = loadBranch(id, userId);
    if (!authz.canManageBranch(b, userId)) throw forbidden();
    const body = parse(z.object({ name: zName.optional(), description: zOptText, location: zOptText, archived: z.boolean().optional() }), req.body);
    if (body.archived !== undefined && !authz.isOrgAdmin(b.org_id, userId)) throw forbidden("Only org admins can archive branches");
    db.prepare("UPDATE branches SET name = ?, description = ?, location = ?, archived_at = ?, updated_at = ? WHERE id = ?").run(
      body.name ?? b.name,
      body.description === undefined ? b.description : body.description,
      body.location === undefined ? b.location : body.location,
      body.archived === undefined ? b.archived_at : body.archived ? now() : null,
      now(),
      id,
    );
    ctx.audit({ actorId: userId, orgId: b.org_id, action: "branch.update", target: id });
    notifyOrg(ctx, b.org_id);
    return { ok: true };
  });

  app.post("/v1/branches/:id/departments", auth, async (req) => {
    const { userId } = me(req);
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const b = loadBranch(id, userId);
    if (!authz.canManageBranch(b, userId)) throw forbidden();
    const body = parse(z.object({ name: zName, description: zOptText }), req.body);
    const did = newId("dp");
    const t = now();
    db.prepare("INSERT INTO departments (id, org_id, branch_id, name, description, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)").run(
      did,
      b.org_id,
      b.id,
      body.name,
      body.description ?? null,
      t,
      t,
    );
    ctx.audit({ actorId: userId, orgId: b.org_id, action: "department.create", target: did, detail: body.name });
    notifyOrg(ctx, b.org_id);
    return { department: { id: did, branchId: b.id, name: body.name, description: body.description ?? null } };
  });

  app.patch("/v1/departments/:id", auth, async (req) => {
    const { userId } = me(req);
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const d = db.prepare("SELECT * FROM departments WHERE id = ?").get(id) as any;
    if (!d) throw notFound("Department not found");
    const b = loadBranch(d.branch_id, userId);
    if (!authz.canManageBranch(b, userId)) throw forbidden();
    if (b.archived_at) throw conflict("This branch is archived"); // D-14
    const body = parse(z.object({ name: zName.optional(), description: zOptText }), req.body);
    db.prepare("UPDATE departments SET name = ?, description = ?, updated_at = ? WHERE id = ?").run(
      body.name ?? d.name,
      body.description === undefined ? d.description : body.description,
      now(),
      id,
    );
    ctx.audit({ actorId: userId, orgId: b.org_id, action: "department.update", target: id, detail: body.name ?? d.name });
    notifyOrg(ctx, b.org_id);
    return { ok: true };
  });

  /** Deletes a department. Its teams stay in the branch, ungrouped: a department grants no access (D-15). */
  app.delete("/v1/departments/:id", auth, async (req) => {
    const { userId } = me(req);
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const d = db.prepare("SELECT * FROM departments WHERE id = ?").get(id) as any;
    if (!d) throw notFound("Department not found");
    const b = loadBranch(d.branch_id, userId);
    if (!authz.canManageBranch(b, userId)) throw forbidden();
    if (b.archived_at) throw conflict("This branch is archived"); // D-14
    db.transaction(() => {
      db.prepare("UPDATE teams SET department_id = NULL, updated_at = ? WHERE department_id = ?").run(now(), id);
      db.prepare("DELETE FROM departments WHERE id = ?").run(id);
    })();
    ctx.audit({ actorId: userId, orgId: b.org_id, action: "department.delete", target: id, detail: d.name });
    notifyOrg(ctx, b.org_id);
    return { ok: true };
  });

  // ───────────── Teams ─────────────
  app.post("/v1/orgs/:orgId/teams", auth, async (req) => {
    const { userId } = me(req);
    const { orgId } = parse(z.object({ orgId: z.string().max(64) }), req.params);
    authz.requireOrgMember(orgId, userId);
    const body = parse(
      z.object({ name: zName, description: zOptText, branchId: z.string().max(64).nullable().optional(), departmentId: z.string().max(64).nullable().optional() }),
      req.body,
    );
    let branchId = body.branchId ?? null;
    if (body.departmentId) {
      const d = db.prepare("SELECT * FROM departments WHERE id = ? AND org_id = ?").get(body.departmentId, orgId) as any;
      if (!d) throw badRequest("Unknown department");
      if (branchId && branchId !== d.branch_id) throw badRequest("Department belongs to a different branch");
      branchId = d.branch_id;
    }
    if (branchId) {
      const b = authz.branch(branchId);
      if (!b || b.org_id !== orgId) throw badRequest("Unknown branch");
      if (!authz.canManageBranch(b, userId)) throw forbidden("Only org admins or this branch's managers can create teams here");
    } else if (!authz.isOrgAdmin(orgId, userId)) {
      throw forbidden("Only org admins can create cross-branch teams");
    }
    const id = newId("t");
    const t = now();
    db.transaction(() => {
      db.prepare(
        "INSERT INTO teams (id, org_id, branch_id, department_id, name, description, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
      ).run(id, orgId, branchId, body.departmentId ?? null, body.name, body.description ?? null, t, t);
    })();
    ctx.audit({ actorId: userId, orgId, action: "team.create", target: id, detail: body.name });
    notifyOrg(ctx, orgId);
    return { team: { id, orgId, branchId, departmentId: body.departmentId ?? null, name: body.name, crossBranch: branchId === null } };
  });

  app.patch("/v1/teams/:id", auth, async (req) => {
    const { userId } = me(req);
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const team = loadTeam(id, userId);
    if (!authz.canManageTeam(team, userId)) throw forbidden();
    const body = parse(
      z.object({ name: zName.optional(), description: zOptText, archived: z.boolean().optional(), departmentId: z.string().max(64).nullable().optional() }),
      req.body,
    );
    if (authz.scopeArchived(team.branch_id, null)) throw conflict("This team's branch is archived"); // D-14: unarchive the branch first
    if (body.departmentId) {
      // A department groups one branch's teams (D-15): cross-branch teams have none.
      const dep = db.prepare("SELECT branch_id FROM departments WHERE id = ?").get(body.departmentId) as { branch_id: string } | undefined;
      if (!dep || !team.branch_id || dep.branch_id !== team.branch_id) throw badRequest("That department isn't in this team's branch");
    }
    db.prepare("UPDATE teams SET name = ?, description = ?, archived_at = ?, department_id = ?, updated_at = ? WHERE id = ?").run(
      body.name ?? team.name,
      body.description === undefined ? team.description : body.description,
      body.archived === undefined ? team.archived_at : body.archived ? now() : null,
      body.departmentId === undefined ? team.department_id : body.departmentId,
      now(),
      id,
    );
    ctx.audit({ actorId: userId, orgId: team.org_id, action: "team.update", target: id });
    notifyOrg(ctx, team.org_id);
    return { ok: true };
  });

  app.get("/v1/teams/:id/members", auth, async (req) => {
    const { userId } = me(req);
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const team = loadTeam(id, userId);
    if (!authz.canAccessTeam(team, userId) && !(team.branch_id && authz.branchRole(team.branch_id, userId))) throw notFound("Team not found");
    const rows = db
      .prepare("SELECT u.*, tm.role AS team_role FROM team_members tm JOIN users u ON u.id = tm.user_id WHERE tm.team_id = ? ORDER BY tm.role, u.display_name")
      .all(id) as any[];
    return { members: rows.map((u) => ({ ...userSummary(u), role: u.team_role, online: ctx.hub.isUserOnline(u.id) })) };
  });

  app.get("/v1/branches/:id/members", auth, async (req) => {
    const { userId } = me(req);
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const b = loadBranch(id, userId);
    if (!authz.canAccessBranch(b, userId)) throw notFound("Branch not found");
    const rows = db
      .prepare("SELECT u.*, bm.role AS branch_role FROM branch_members bm JOIN users u ON u.id = bm.user_id WHERE bm.branch_id = ? ORDER BY bm.role, u.display_name")
      .all(id) as any[];
    return { members: rows.map((u) => ({ ...userSummary(u), role: u.branch_role, online: ctx.hub.isUserOnline(u.id) })) };
  });

  // ───────────── Membership & roles ─────────────
  app.get("/v1/orgs/:orgId/members", auth, async (req) => {
    const { userId } = me(req);
    const { orgId } = parse(z.object({ orgId: z.string().max(64) }), req.params);
    const role = authz.requireOrgMember(orgId, userId);
    if (role === "GUEST") throw forbidden("Guests cannot browse the organization directory");
    const { q } = parse(z.object({ q: z.string().trim().max(40).optional() }), req.query);
    const like = q ? `%${q.toLowerCase().replace(/[%_\\]/g, (c) => "\\" + c)}%` : "%";
    const rows = db
      .prepare(
        `SELECT u.*, m.role AS org_role, m.title FROM org_members m JOIN users u ON u.id = m.user_id
         WHERE m.org_id = ? AND (lower(u.display_name) LIKE ? ESCAPE '\\' OR u.username LIKE ? ESCAPE '\\')
         ORDER BY CASE m.role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 WHEN 'MEMBER' THEN 2 ELSE 3 END, u.display_name LIMIT 500`,
      )
      .all(orgId, like, like) as any[];
    const branchRows = db
      .prepare("SELECT bm.user_id, bm.branch_id, bm.role FROM branch_members bm JOIN branches b ON b.id = bm.branch_id WHERE b.org_id = ?")
      .all(orgId) as any[];
    const teamRows = db
      .prepare("SELECT tm.user_id, tm.team_id, tm.role FROM team_members tm JOIN teams t ON t.id = tm.team_id WHERE t.org_id = ?")
      .all(orgId) as any[];
    return {
      members: rows.map((u) => ({
        ...userSummary(u),
        orgRole: u.org_role,
        title: u.title,
        online: ctx.hub.isUserOnline(u.id),
        branches: branchRows.filter((r) => r.user_id === u.id).map((r) => ({ id: r.branch_id, role: r.role })),
        teams: teamRows.filter((r) => r.user_id === u.id).map((r) => ({ id: r.team_id, role: r.role })),
      })),
    };
  });

  app.patch("/v1/orgs/:orgId/members/:userId", auth, async (req) => {
    const actor = me(req).userId;
    const p = parse(z.object({ orgId: z.string().max(64), userId: z.string().max(64) }), req.params);
    const body = parse(z.object({ role: z.enum(["OWNER", "ADMIN", "MEMBER", "GUEST"]).optional(), title: zOptText }), req.body);
    const actorRole = authz.requireOrgMember(p.orgId, actor);
    const targetRole = authz.orgRole(p.orgId, p.userId);
    if (!targetRole) throw notFound("Member not found");
    if (body.role && body.role !== targetRole) {
      // Owners manage everything; admins manage MEMBER/GUEST only and cannot touch other admins/owners.
      if (actorRole === "OWNER") {
        if (targetRole === "OWNER" && body.role !== "OWNER") {
          const owners = (db.prepare("SELECT COUNT(*) n FROM org_members WHERE org_id = ? AND role = 'OWNER'").get(p.orgId) as { n: number }).n;
          if (owners <= 1) throw conflict("An organization needs at least one owner");
        }
      } else if (actorRole === "ADMIN") {
        const low = (r: OrgRole) => r === "MEMBER" || r === "GUEST";
        if (!low(targetRole) || !low(body.role)) throw forbidden("Admins can only switch members between Member and Guest");
      } else throw forbidden();
    } else if (body.title !== undefined && !authz.isOrgAdmin(p.orgId, actor) && actor !== p.userId) {
      throw forbidden();
    }
    const removedFrom: string[] = [];
    db.transaction(() => {
      if (body.role) db.prepare("UPDATE org_members SET role = ? WHERE org_id = ? AND user_id = ?").run(body.role, p.orgId, p.userId);
      if (body.title !== undefined) db.prepare("UPDATE org_members SET title = ? WHERE org_id = ? AND user_id = ?").run(body.title, p.orgId, p.userId);
      if (body.role) {
        // Guests only keep what a manager grants them explicitly after the demotion.
        if (body.role === "GUEST" && targetRole !== "GUEST") removedFrom.push(...authz.demoteToGuest(p.orgId, p.userId));
        else removedFrom.push(...authz.revalidateChannelMemberships(p.orgId, p.userId));
        authz.autoJoinScope(p.orgId, p.userId, now());
      }
    })();
    if (body.role) ctx.audit({ actorId: actor, orgId: p.orgId, action: "member.role", target: p.userId, detail: `${targetRole}→${body.role}` });
    notifyOrg(ctx, p.orgId);
    // The person's devices drop the channels; members refresh them. Envelopes still addressed to
    // the person's devices are refused by the recipient-set check (409 device_mismatch).
    for (const c of removedFrom) notifyConversation(ctx, c, [p.userId]);
    await evictRemovedMember(ctx, p.userId, removedFrom);
    return { ok: true };
  });

  app.delete("/v1/orgs/:orgId/members/:userId", auth, async (req) => {
    const actor = me(req).userId;
    const p = parse(z.object({ orgId: z.string().max(64), userId: z.string().max(64) }), req.params);
    const actorRole = authz.requireOrgMember(p.orgId, actor);
    const targetRole = authz.orgRole(p.orgId, p.userId);
    if (!targetRole) throw notFound("Member not found");
    const self = actor === p.userId;
    if (!self) {
      if (actorRole === "ADMIN" && (targetRole === "OWNER" || targetRole === "ADMIN")) throw forbidden("Admins cannot remove owners or admins");
      if (actorRole !== "OWNER" && actorRole !== "ADMIN") throw forbidden();
    }
    if (targetRole === "OWNER") {
      const owners = (db.prepare("SELECT COUNT(*) n FROM org_members WHERE org_id = ? AND role = 'OWNER'").get(p.orgId) as { n: number }).n;
      if (owners <= 1) throw conflict("Transfer ownership before the last owner leaves");
    }
    const affected: string[] = [];
    db.transaction(() => {
      db.prepare("DELETE FROM branch_members WHERE user_id = ? AND branch_id IN (SELECT id FROM branches WHERE org_id = ?)").run(p.userId, p.orgId);
      db.prepare("DELETE FROM team_members WHERE user_id = ? AND team_id IN (SELECT id FROM teams WHERE org_id = ?)").run(p.userId, p.orgId);
      db.prepare("DELETE FROM org_members WHERE org_id = ? AND user_id = ?").run(p.orgId, p.userId);
      affected.push(...authz.revalidateChannelMemberships(p.orgId, p.userId));
    })();
    ctx.audit({ actorId: actor, orgId: p.orgId, action: self ? "member.leave" : "member.remove", target: p.userId });
    notifyOrg(ctx, p.orgId);
    for (const c of affected) notifyConversation(ctx, c, [p.userId]);
    ctx.hub.sendToUser(p.userId, { t: "org.updated", orgId: p.orgId });
    await evictRemovedMember(ctx, p.userId, affected);
    return { ok: true };
  });

  const setScopedMember = async (
    kind: "branch" | "team",
    scopeId: string,
    actor: string,
    target: string,
    role: string | null,
  ) => {
    let orgId: string;
    if (kind === "branch") {
      const b = loadBranch(scopeId, actor);
      orgId = b.org_id;
      if (!authz.canManageBranch(b, actor)) throw forbidden();
      // Only org admins may appoint/remove branch managers.
      const current = authz.branchRole(b.id, target);
      if ((role === "MANAGER" || current === "MANAGER") && !authz.isOrgAdmin(orgId, actor)) throw forbidden("Only org admins can change branch managers");
    } else {
      const t = loadTeam(scopeId, actor);
      orgId = t.org_id;
      if (!authz.canManageTeam(t, actor)) throw forbidden();
      const current = authz.teamRole(t.id, target);
      const isLeadOnly = !authz.isOrgAdmin(orgId, actor) && !(t.branch_id && authz.branchRole(t.branch_id, actor) === "MANAGER");
      if (isLeadOnly && (role === "LEAD" || current === "LEAD")) throw forbidden("Only managers can change team leads");
      // Cross-branch control: a branch team may only contain members of that branch.
      if (role && t.branch_id && authz.branchRole(t.branch_id, target) === null) {
        const targetOrgRole = authz.orgRole(orgId, target);
        if (targetOrgRole !== "OWNER" && targetOrgRole !== "ADMIN") {
          throw conflict("This person is not a member of the team's branch. Add them to the branch first.");
        }
      }
    }
    const targetOrgRole = authz.orgRole(orgId, target);
    if (!targetOrgRole) throw notFound("That person is not a member of this organization");
    if (role && authz.scopeArchived(kind === "branch" ? scopeId : null, kind === "team" ? scopeId : null)) {
      throw conflict(`This ${kind} is archived. Unarchive it before adding people.`);
    }
    if (role && targetOrgRole === "GUEST") throw conflict("Guests cannot join branches or teams; add them to specific channels instead");
    const t = now();
    const removedFrom: string[] = [];
    db.transaction(() => {
      if (kind === "branch") {
        if (role)
          db.prepare("INSERT INTO branch_members (branch_id, user_id, role, joined_at) VALUES (?, ?, ?, ?) ON CONFLICT(branch_id, user_id) DO UPDATE SET role = excluded.role").run(
            scopeId,
            target,
            role,
            t,
          );
        else {
          db.prepare("DELETE FROM branch_members WHERE branch_id = ? AND user_id = ?").run(scopeId, target);
          // Leaving a branch also removes the person from that branch's teams.
          db.prepare("DELETE FROM team_members WHERE user_id = ? AND team_id IN (SELECT id FROM teams WHERE branch_id = ?)").run(target, scopeId);
        }
      } else if (role)
        db.prepare("INSERT INTO team_members (team_id, user_id, role, joined_at) VALUES (?, ?, ?, ?) ON CONFLICT(team_id, user_id) DO UPDATE SET role = excluded.role").run(
          scopeId,
          target,
          role,
          t,
        );
      else db.prepare("DELETE FROM team_members WHERE team_id = ? AND user_id = ?").run(scopeId, target);
      removedFrom.push(...authz.revalidateChannelMemberships(orgId, target));
      authz.autoJoinScope(orgId, target, t);
    })();
    ctx.audit({ actorId: actor, orgId, action: `${kind}.member.${role ? "set" : "remove"}`, target: `${scopeId}:${target}`, detail: role });
    notifyOrg(ctx, orgId);
    for (const c of removedFrom) notifyConversation(ctx, c, [target]);
    await evictRemovedMember(ctx, target, removedFrom);
    return { ok: true };
  };

  app.put("/v1/branches/:id/members/:userId", auth, async (req) => {
    const p = parse(z.object({ id: z.string().max(64), userId: z.string().max(64) }), req.params);
    const { role } = parse(z.object({ role: z.enum(["MANAGER", "MEMBER"]).default("MEMBER") }), req.body ?? {});
    return setScopedMember("branch", p.id, me(req).userId, p.userId, role);
  });
  app.delete("/v1/branches/:id/members/:userId", auth, async (req) => {
    const p = parse(z.object({ id: z.string().max(64), userId: z.string().max(64) }), req.params);
    return setScopedMember("branch", p.id, me(req).userId, p.userId, null);
  });
  app.put("/v1/teams/:id/members/:userId", auth, async (req) => {
    const p = parse(z.object({ id: z.string().max(64), userId: z.string().max(64) }), req.params);
    const { role } = parse(z.object({ role: z.enum(["LEAD", "MEMBER"]).default("MEMBER") }), req.body ?? {});
    return setScopedMember("team", p.id, me(req).userId, p.userId, role);
  });
  app.delete("/v1/teams/:id/members/:userId", auth, async (req) => {
    const p = parse(z.object({ id: z.string().max(64), userId: z.string().max(64) }), req.params);
    return setScopedMember("team", p.id, me(req).userId, p.userId, null);
  });

  /** Move a member between two teams; requires management rights on both. */
  app.post("/v1/teams/:id/members/:userId/move", auth, async (req) => {
    const actor = me(req).userId;
    const p = parse(z.object({ id: z.string().max(64), userId: z.string().max(64) }), req.params);
    const { toTeamId } = parse(z.object({ toTeamId: z.string().max(64) }), req.body);
    const from = loadTeam(p.id, actor);
    const to = loadTeam(toTeamId, actor);
    if (from.org_id !== to.org_id) throw badRequest("Teams belong to different organizations");
    if (!authz.canManageTeam(from, actor) || !authz.canManageTeam(to, actor)) throw forbidden("You need to manage both teams to move members");
    if (!authz.teamRole(from.id, p.userId)) throw notFound("That person is not in the source team");
    await setScopedMember("team", to.id, actor, p.userId, "MEMBER");
    await setScopedMember("team", from.id, actor, p.userId, null);
    return { ok: true };
  });

  // ───────────── Invitations ─────────────
  app.post("/v1/orgs/:orgId/invites", auth, async (req) => {
    const actor = me(req).userId;
    const { orgId } = parse(z.object({ orgId: z.string().max(64) }), req.params);
    const body = parse(
      z.object({
        role: z.enum(["ADMIN", "MEMBER", "GUEST"]).default("MEMBER"),
        branchId: z.string().max(64).nullable().optional(),
        teamId: z.string().max(64).nullable().optional(),
        maxUses: z.number().int().min(1).max(500).default(1),
        expiresInHours: z.number().int().min(1).max(24 * 30).default(72),
      }),
      req.body,
    );
    const actorRole = authz.requireOrgMember(orgId, actor);
    let branchId = body.branchId ?? null;
    if (body.teamId) {
      const team = authz.team(body.teamId);
      if (!team || team.org_id !== orgId) throw badRequest("Unknown team");
      if (branchId && team.branch_id && branchId !== team.branch_id) throw badRequest("Team is not in that branch");
      branchId = team.branch_id ?? branchId;
    }
    const branch = branchId ? authz.branch(branchId) : undefined;
    if (branchId && (!branch || branch.org_id !== orgId)) throw badRequest("Unknown branch");
    if (body.role === "ADMIN" && actorRole !== "OWNER") throw forbidden("Only owners can invite admins");
    if (authz.scopeArchived(branchId, body.teamId ?? null)) throw conflict("You can't invite people into an archived branch or team");
    if (!authz.canIssueInvite(orgId, actor, body.role, branchId, body.teamId ?? null)) {
      throw forbidden("You can only invite people into branches or teams you manage");
    }
    const code = humanCode(12);
    const id = newId("inv");
    const t = now();
    const expiresAt = t + body.expiresInHours * 3600_000;
    db.prepare(
      `INSERT INTO invitations (id, org_id, code_hash, code_hint, role, branch_id, team_id, max_uses, created_by, created_at, expires_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).run(id, orgId, sha256Hex(code), code.slice(-4), body.role, branchId, body.teamId ?? null, body.maxUses, actor, t, expiresAt);
    ctx.audit({ actorId: actor, orgId, action: "invite.create", target: id, detail: body.role });
    return {
      invite: { id, code: `${code.slice(0, 4)}-${code.slice(4, 8)}-${code.slice(8)}`, role: body.role, branchId, teamId: body.teamId ?? null, maxUses: body.maxUses, uses: 0, expiresAt },
    };
  });

  app.get("/v1/orgs/:orgId/invites", auth, async (req) => {
    const actor = me(req).userId;
    const { orgId } = parse(z.object({ orgId: z.string().max(64) }), req.params);
    authz.requireOrgMember(orgId, actor);
    const isAdmin = authz.isOrgAdmin(orgId, actor);
    const rows = db.prepare("SELECT * FROM invitations WHERE org_id = ? ORDER BY created_at DESC LIMIT 100").all(orgId) as any[];
    return {
      invites: rows
        .filter((r) => isAdmin || r.created_by === actor)
        .map((r) => ({
          id: r.id,
          hint: r.code_hint,
          role: r.role,
          branchId: r.branch_id,
          teamId: r.team_id,
          maxUses: r.max_uses,
          uses: r.uses,
          createdAt: r.created_at,
          expiresAt: r.expires_at,
          revokedAt: r.revoked_at,
          active: !r.revoked_at && r.expires_at > now() && r.uses < r.max_uses,
        })),
    };
  });

  app.delete("/v1/invites/:id", auth, async (req) => {
    const actor = me(req).userId;
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const inv = db.prepare("SELECT * FROM invitations WHERE id = ?").get(id) as any;
    if (!inv) throw notFound("Invite not found");
    authz.requireOrgMember(inv.org_id, actor);
    if (inv.created_by !== actor && !authz.isOrgAdmin(inv.org_id, actor)) throw forbidden();
    db.prepare("UPDATE invitations SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL").run(now(), id);
    ctx.audit({ actorId: actor, orgId: inv.org_id, action: "invite.revoke", target: id });
    return { ok: true };
  });

  const findInvite = (code: string) => {
    const inv = findOrgInvite(db, authz, code);
    if (!inv) throw notFound("This invite is invalid, expired or already used");
    return inv;
  };

  const inviteRate = { preHandler: requireAuth(ctx), config: { rateLimit: { max: 20, timeWindow: "1 minute" } } };

  app.post("/v1/invites/preview", inviteRate, async (req) => {
    const { code } = parse(z.object({ code: z.string().min(8).max(40) }), req.body);
    const inv = findInvite(code);
    const org = db.prepare("SELECT * FROM orgs WHERE id = ?").get(inv.org_id) as any;
    return {
      org: { id: org.id, name: org.name, description: org.description },
      role: inv.role,
      branch: inv.branch_id ? (db.prepare("SELECT id, name FROM branches WHERE id = ?").get(inv.branch_id) as any) : null,
      team: inv.team_id ? (db.prepare("SELECT id, name FROM teams WHERE id = ?").get(inv.team_id) as any) : null,
      alreadyMember: !!authz.orgRole(inv.org_id, me(req).userId),
    };
  });

  app.post("/v1/invites/accept", inviteRate, async (req) => {
    const userId = me(req).userId;
    const { code } = parse(z.object({ code: z.string().min(8).max(40) }), req.body);
    const t = now();
    const result = db.transaction(() => joinOrgByInvite(ctx, findInvite(code), userId, t))();
    ctx.audit({ actorId: userId, orgId: result.orgId, action: "invite.accept", target: result.inviteId, detail: result.role });
    notifyOrg(ctx, result.orgId);
    return { org: orgSummary(ctx, db.prepare("SELECT * FROM orgs WHERE id = ?").get(result.orgId), userId) };
  });

  // ───────────── Channels ─────────────
  app.post("/v1/orgs/:orgId/channels", auth, async (req) => {
    const actor = me(req).userId;
    const { orgId } = parse(z.object({ orgId: z.string().max(64) }), req.params);
    const body = parse(
      z.object({
        name: z.string().trim().min(1).max(60),
        topic: zOptText,
        scope: z.enum(["ORG", "BRANCH", "TEAM"]),
        scopeId: z.string().max(64).nullable().optional(),
        kind: z.enum(["PUBLIC", "PRIVATE", "ANNOUNCEMENT"]).default("PUBLIC"),
        autoJoin: z.boolean().optional(),
        memberIds: z.array(z.string().max(64)).max(200).default([]),
      }),
      req.body,
    );
    authz.requireOrgMember(orgId, actor);
    let scopeId: string | null = null;
    if (body.scope === "ORG") {
      if (!authz.isOrgAdmin(orgId, actor)) throw forbidden("Only org admins can create organization-wide channels");
    } else if (body.scope === "BRANCH") {
      const b = body.scopeId ? authz.branch(body.scopeId) : undefined;
      if (!b || b.org_id !== orgId) throw badRequest("Unknown branch");
      if (!authz.canManageBranch(b, actor)) throw forbidden("Only this branch's managers can create its channels");
      if (authz.scopeArchived(b.id, null)) throw conflict("This branch is archived");
      scopeId = b.id;
    } else {
      const tm = body.scopeId ? authz.team(body.scopeId) : undefined;
      if (!tm || tm.org_id !== orgId) throw badRequest("Unknown team");
      if (!authz.canManageTeam(tm, actor)) throw forbidden("Only this team's leads and managers can create its channels");
      if (authz.scopeArchived(null, tm.id)) throw conflict("This team is archived");
      scopeId = tm.id;
    }
    const id = newId("c");
    const t = now();
    const autoJoin = body.kind === "ANNOUNCEMENT" ? true : body.kind === "PRIVATE" ? false : body.autoJoin ?? body.scope !== "ORG";
    db.transaction(() => {
      db.prepare(
        `INSERT INTO conversations (id, type, org_id, scope, scope_id, kind, name, topic, auto_join, created_by, created_at, updated_at)
         VALUES (?, 'CHANNEL', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      ).run(id, orgId, body.scope, scopeId, body.kind, body.name, body.topic ?? null, autoJoin ? 1 : 0, actor, t, t);
      db.prepare("INSERT INTO conversation_members (conversation_id, user_id, role, joined_at) VALUES (?, ?, 'ADMIN', ?)").run(id, actor, t);
      const conv = authz.conversation(id)!;
      const addIds = new Set(body.memberIds);
      if (autoJoin) {
        for (const r of db.prepare("SELECT user_id FROM org_members WHERE org_id = ?").all(orgId) as { user_id: string }[]) addIds.add(r.user_id);
      }
      for (const uid of addIds) {
        if (uid === actor) continue;
        const explicit = body.memberIds.includes(uid);
        const role = authz.orgRole(orgId, uid);
        // Explicitly listed guests may be added; everyone else must be scope-eligible.
        if (authz.scopeEligible(conv, uid) || (explicit && role === "GUEST")) {
          db.prepare("INSERT OR IGNORE INTO conversation_members (conversation_id, user_id, role, joined_at) VALUES (?, ?, 'MEMBER', ?)").run(id, uid, t);
        } else if (explicit) {
          throw conflict(`Cannot add a member who has no access to this ${body.scope.toLowerCase()}`, { userId: uid });
        }
      }
    })();
    ctx.audit({ actorId: actor, orgId, action: "channel.create", target: id, detail: `${body.kind} ${body.name}` });
    notifyConversation(ctx, id);
    return { channel: conversationView(ctx, authz.conversation(id)!, actor) };
  });

  /** Browse channels visible to the caller (joined + joinable public ones). */
  app.get("/v1/orgs/:orgId/channels", auth, async (req) => {
    const actor = me(req).userId;
    const { orgId } = parse(z.object({ orgId: z.string().max(64) }), req.params);
    authz.requireOrgMember(orgId, actor);
    const { q, includeArchived } = parse(z.object({ q: z.string().trim().max(40).optional(), includeArchived: z.enum(["true", "false"]).optional() }), req.query);
    const rows = db
      .prepare("SELECT * FROM conversations WHERE org_id = ? AND type = 'CHANNEL' ORDER BY name")
      .all(orgId) as ConversationRow[];
    return {
      channels: rows
        .filter((c) => (includeArchived === "true" || !c.archived_at) && authz.canViewChannel(c, actor))
        .filter((c) => !q || (c.name ?? "").toLowerCase().includes(q.toLowerCase()) || (c.topic ?? "").toLowerCase().includes(q.toLowerCase()))
        .map((c) => conversationView(ctx, c, actor)),
    };
  });

  // ───────────── Activity ─────────────
  // Non-admins see an event only if they could see its target in the org structure (GET /v1/orgs/:id)
  // or the conversation itself. The target is looked up live, so older audit rows without any scope
  // information are filtered the same way; a target that no longer exists is hidden.
  const activityVisibleTo = (r: { action: string; target: string | null }, viewer: string): boolean => {
    const target = r.target ?? "";
    switch (r.action) {
      case "branch.create":
        return true; // every branch name is listed for org members
      case "department.create": {
        const d = db.prepare("SELECT branch_id FROM departments WHERE id = ?").get(target) as { branch_id: string } | undefined;
        const b = d && authz.branch(d.branch_id);
        return !!b && authz.canAccessBranch(b, viewer);
      }
      case "team.create": {
        const t = authz.team(target);
        return !!t && (authz.canAccessTeam(t, viewer) || (!!t.branch_id && !!authz.branchRole(t.branch_id, viewer)));
      }
      case "channel.create": {
        const c = authz.conversation(target);
        return !!c && authz.canViewChannel(c, viewer);
      }
      case "call.start":
        return authz.isMember(target, viewer);
      case "invite.accept":
      case "member.leave":
        return true; // joining and leaving are org-level, like the member directory
      default:
        return false;
    }
  };
  app.get("/v1/orgs/:orgId/activity", auth, async (req) => {
    const actor = me(req).userId;
    const { orgId } = parse(z.object({ orgId: z.string().max(64) }), req.params);
    const role = authz.requireOrgMember(orgId, actor);
    if (role === "GUEST") throw forbidden();
    const isAdmin = role === "OWNER" || role === "ADMIN";
    const rows = db.prepare("SELECT * FROM audit_log WHERE org_id = ? ORDER BY id DESC LIMIT 500").all(orgId) as any[];
    const names = new Map<string, string>();
    const nameOf = (id: string | null) => {
      if (!id) return null;
      if (!names.has(id)) names.set(id, (db.prepare("SELECT display_name FROM users WHERE id = ?").get(id) as any)?.display_name ?? "Someone");
      return names.get(id)!;
    };
    return {
      activity: rows
        .filter((r) => isAdmin || activityVisibleTo(r, actor))
        .slice(0, 60)
        .map((r) => ({ id: r.id, at: r.at, action: r.action, actorId: r.actor_id, actorName: nameOf(r.actor_id), target: r.target, detail: r.detail })),
    };
  });
}
