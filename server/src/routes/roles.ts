import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { type OrgRole, parsePermissionList } from "../authz.js";
import { type AppContext, me, parse, requireAuth } from "../context.js";
import {
  BASE_ROLE_PERMISSIONS,
  isPermission,
  type Permission,
  PERMISSIONS,
  ROLE_ICONS,
  ROLE_TEMPLATES,
} from "../permissions/catalog.js";
import { type Actor, canActOn, type RoleGrant, roleAssignmentRefusal, roleDefinitionRefusal, roleRemovalRefusal, type Refusal } from "../permissions/rules.js";
import { ApiError, conflict, forbidden, newId, notFound, now } from "../util.js";
import { reconcileAccess } from "./community.js";
import { notifyOrg } from "./orgs.js";

/**
 * Custom workspace roles (docs/WORKSPACE_ROLES.md): a workspace's own roles on top of the protected base roles, each
 * granting exactly the catalog permissions it lists. Every rule is in src/permissions/rules.ts; this file only loads,
 * checks and stores. Also here: ownership transfer, usage figures and the full audit log, which are gated by the same
 * permissions.
 */

/** At most this many active custom roles per workspace, and roles per person. */
export const MAX_ACTIVE_ROLES = 50;
export const MAX_ROLES_PER_MEMBER = 20;
const ROLE_NAME_MAX = 40;
const ROLE_DESCRIPTION_MAX = 200;
const AUDIT_PAGE_MAX = 100;
const BASE_NAMES: Record<OrgRole, string> = { OWNER: "Owner", ADMIN: "Admin", MEMBER: "Member", GUEST: "Guest" };

interface RoleRow {
  id: string;
  org_id: string;
  name: string;
  description: string | null;
  color: string | null;
  icon: string | null;
  permissions: string;
  denies: string;
  template: string | null;
  position: number;
  rev: number;
  created_by: string;
  created_at: number;
  updated_at: number;
  archived_at: number | null;
}

const zPermission = z.string().refine(isPermission, { message: "Unknown permission" }) as unknown as z.ZodType<Permission>;
const zPermissionList = z.array(zPermission).max(64).transform((l) => [...new Set(l)]);
const zRoleName = z.string().trim().min(1).max(ROLE_NAME_MAX);
const zColor = z.string().regex(/^#[0-9A-Fa-f]{6}$/, "Use a colour like #E39B7B").nullable();
const zIcon = z.enum(ROLE_ICONS).nullable();
const zId = z.string().max(64);

const grantOf = (r: RoleRow): RoleGrant => ({ permissions: parsePermissionList(r.permissions), denies: parsePermissionList(r.denies) });
const sameKeys = (a: readonly string[], b: readonly string[]) => a.length === b.length && a.every((x) => b.includes(x));

function throwRefusal(r: Refusal): never {
  throw new ApiError(r.status, r.code, r.message);
}

/** What a workspace uses of each limited capability (round 7 adds members, storage, workflows and more). */
export function planUsage(ctx: AppContext, orgId: string, customRoles: number) {
  const n = (sql: string, ...p: unknown[]) => (ctx.db.prepare(sql).get(...p) as { n: number | null }).n ?? 0;
  const t = now();
  return {
    "roles.custom": customRoles,
    members: n("SELECT COUNT(*) n FROM org_members WHERE org_id = ?", orgId),
    "storage.bytes": n("SELECT SUM(a.size) n FROM attachments a JOIN conversations c ON c.id = a.conversation_id WHERE c.org_id = ? AND a.expires_at > ?", orgId, t),
  };
}

export default async function roleRoutes(app: FastifyInstance, ctx: AppContext) {
  const auth = { preHandler: requireAuth(ctx) };
  const { authz, db } = ctx;

  const roleRow = (id: string) => db.prepare("SELECT * FROM org_roles WHERE id = ?").get(id) as RoleRow | undefined;
  const memberCount = (roleId: string) => (db.prepare("SELECT COUNT(*) n FROM org_role_members WHERE role_id = ?").get(roleId) as { n: number }).n;
  const holders = (roleId: string) => (db.prepare("SELECT user_id FROM org_role_members WHERE role_id = ?").all(roleId) as { user_id: string }[]).map((r) => r.user_id);

  /**
   * Changing what a role does (its powers, its limits, or whether it counts) changes the access of everyone holding it,
   * so it is allowed only when the caller may act on each of them: nobody restricts another admin, or someone with
   * powers they lack, by editing a role that person holds. Owners aren't affected by roles, so they don't count.
   */
  const requireReachOverHolders = (role: RoleRow, actor: Actor, actorId: string) => {
    const holders = db.prepare("SELECT user_id FROM org_role_members WHERE role_id = ?").all(role.id) as { user_id: string }[];
    for (const { user_id } of holders) {
      const t = authz.actor(role.org_id, user_id);
      if (!t || t.base === "OWNER") continue;
      if (!canActOn(actor, { base: t.base, perms: t.perms, self: user_id === actorId })) {
        throw new ApiError(403, "holder_out_of_reach", "Someone holding this role is an admin or has powers you don't, so only an owner can change what it does");
      }
    }
  };

  /** The role and the caller in its workspace; outsiders get 404 so a role id says nothing about the workspace. */
  const loadRole = (roleId: string, userId: string): { role: RoleRow; actor: Actor } => {
    const role = roleRow(roleId);
    const actor = role ? authz.actor(role.org_id, userId) : null;
    if (!role || !actor) throw notFound("Role not found");
    return { role, actor };
  };

  const view = (r: RoleRow, actor: Actor) => {
    const grant = grantOf(r);
    return {
      id: r.id,
      name: r.name,
      description: r.description,
      color: r.color,
      icon: r.icon,
      permissions: grant.permissions,
      denies: grant.denies,
      template: r.template,
      position: r.position,
      rev: r.rev,
      memberCount: memberCount(r.id),
      archived: r.archived_at !== null,
      archivedAt: r.archived_at,
      createdBy: r.created_by,
      createdAt: r.created_at,
      updatedAt: r.updated_at,
      // What the caller may do with it (the server checks again on every call).
      canEdit: roleDefinitionRefusal(actor, grant, grant) === null,
      canAssign: actor.perms.has("roles.assign") && !roleAssignmentRefusal(actor, { base: "MEMBER", perms: new Set(), self: false }, grant),
    };
  };

  /** Active roles' names are unique in a workspace (case-insensitive), and never a base role's name. */
  const nameTaken = (orgId: string, name: string, exceptId?: string): RoleRow | { id: null } | undefined => {
    if (Object.values(BASE_NAMES).some((b) => b.toLowerCase() === name.trim().toLowerCase())) return { id: null };
    return db
      .prepare("SELECT * FROM org_roles WHERE org_id = ? AND archived_at IS NULL AND lower(name) = lower(?) AND id != ?")
      .get(orgId, name.trim(), exceptId ?? "") as RoleRow | undefined;
  };
  const refuseTaken = (taken: RoleRow | { id: null }, name: string): never => {
    throw new ApiError(409, "role_name_taken", `A role called "${name.trim()}" already exists in this workspace`, taken.id ? { roleId: taken.id } : undefined);
  };
  const activeCount = (orgId: string) => (db.prepare("SELECT COUNT(*) n FROM org_roles WHERE org_id = ? AND archived_at IS NULL").get(orgId) as { n: number }).n;

  // ───────────── The catalog and the workspace's roles ─────────────
  app.get("/v1/orgs/:orgId/roles", auth, async (req) => {
    const { userId } = me(req);
    const { orgId } = parse(z.object({ orgId: zId }), req.params);
    const { includeArchived } = parse(z.object({ includeArchived: z.enum(["true", "false"]).optional() }), req.query);
    authz.requirePermission(orgId, userId, "roles.view", "Your role doesn't include viewing roles");
    const actor = authz.actor(orgId, userId)!;
    const rows = db
      .prepare(`SELECT * FROM org_roles WHERE org_id = ? ${includeArchived === "true" ? "" : "AND archived_at IS NULL"} ORDER BY archived_at IS NOT NULL, position, name`)
      .all(orgId) as RoleRow[];
    const baseCounts = new Map(
      (db.prepare("SELECT role, COUNT(*) n FROM org_members WHERE org_id = ? GROUP BY role").all(orgId) as { role: OrgRole; n: number }[]).map((r) => [r.role, r.n]),
    );
    return {
      catalog: PERMISSIONS,
      templates: ROLE_TEMPLATES,
      icons: ROLE_ICONS,
      baseRoles: (Object.keys(BASE_NAMES) as OrgRole[]).map((key) => ({
        key,
        name: BASE_NAMES[key],
        permissions: [...BASE_ROLE_PERMISSIONS[key]],
        memberCount: baseCounts.get(key) ?? 0,
        protected: true,
      })),
      roles: rows.map((r) => view(r, actor)),
      limits: { activeRoles: MAX_ACTIVE_ROLES, rolesPerMember: MAX_ROLES_PER_MEMBER, nameMax: ROLE_NAME_MAX, descriptionMax: ROLE_DESCRIPTION_MAX },
      me: {
        base: actor.base,
        permissions: [...actor.perms].sort(),
        canManage: actor.perms.has("roles.manage"),
        canAssign: actor.perms.has("roles.assign"),
        canDelegate: actor.perms.has("roles.delegate"),
      },
    };
  });

  app.post("/v1/orgs/:orgId/roles", auth, async (req) => {
    const { userId } = me(req);
    const { orgId } = parse(z.object({ orgId: zId }), req.params);
    const body = parse(
      z.object({
        name: zRoleName.optional(),
        description: z.string().trim().max(ROLE_DESCRIPTION_MAX).nullable().optional(),
        color: zColor.optional(),
        icon: zIcon.optional(),
        permissions: zPermissionList.optional(),
        denies: zPermissionList.optional(),
        // Start from a starter template or by cloning an existing role; explicit fields override what's copied.
        template: z.string().max(40).optional(),
        cloneOf: zId.optional(),
      }),
      req.body,
    );
    authz.requireOrgMember(orgId, userId);
    const actor = authz.actor(orgId, userId)!;
    let start: { name: string; description: string | null; color: string | null; icon: string | null; grant: RoleGrant; template: string | null } = {
      name: "",
      description: null,
      color: null,
      icon: null,
      grant: { permissions: [], denies: [] },
      template: null,
    };
    if (body.template) {
      const t = ROLE_TEMPLATES.find((x) => x.key === body.template);
      if (!t) throw new ApiError(400, "unknown_template", "Unknown role template");
      start = { name: t.name, description: t.description, color: t.color, icon: t.icon, grant: { permissions: t.permissions, denies: t.denies }, template: t.key };
    } else if (body.cloneOf) {
      const src = roleRow(body.cloneOf);
      if (!src || src.org_id !== orgId) throw notFound("Role not found");
      start = { name: `${src.name} copy`.slice(0, ROLE_NAME_MAX), description: src.description, color: src.color, icon: src.icon, grant: grantOf(src), template: src.template };
    }
    const name = (body.name ?? start.name).trim();
    if (!name) throw new ApiError(400, "bad_request", "Give the role a name");
    const grant: RoleGrant = { permissions: body.permissions ?? start.grant.permissions, denies: body.denies ?? start.grant.denies };
    const refusal = roleDefinitionRefusal(actor, grant);
    if (refusal) throwRefusal(refusal);
    const id = newId("rl");
    const t = now();
    db.transaction(() => {
      // Checked inside the write lock, so two creates can't both take a name or the last slot.
      const taken = nameTaken(orgId, name);
      if (taken) refuseTaken(taken, name);
      if (activeCount(orgId) >= MAX_ACTIVE_ROLES) throw conflict(`A workspace can have at most ${MAX_ACTIVE_ROLES} active roles. Archive one first.`);
      ctx.entitlements.check("roles.custom", activeCount(orgId), orgId);
      const position = ((db.prepare("SELECT MAX(position) p FROM org_roles WHERE org_id = ?").get(orgId) as { p: number | null }).p ?? 0) + 1;
      db.prepare(
        `INSERT INTO org_roles (id, org_id, name, description, color, icon, permissions, denies, template, position, rev, created_by, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)`,
      ).run(
        id,
        orgId,
        name,
        body.description === undefined ? start.description : body.description,
        body.color === undefined ? start.color : body.color,
        body.icon === undefined ? start.icon : body.icon,
        JSON.stringify(grant.permissions),
        JSON.stringify(grant.denies),
        start.template,
        position,
        userId,
        t,
        t,
      );
    })();
    ctx.audit({ actorId: userId, orgId, action: "role.create", target: id, detail: name });
    notifyOrg(ctx, orgId);
    return { role: view(roleRow(id)!, actor) };
  });

  app.patch("/v1/roles/:roleId", auth, async (req) => {
    const { userId } = me(req);
    const { roleId } = parse(z.object({ roleId: zId }), req.params);
    const body = parse(
      z.object({
        baseRev: z.number().int().min(1),
        name: zRoleName.optional(),
        description: z.string().trim().max(ROLE_DESCRIPTION_MAX).nullable().optional(),
        color: zColor.optional(),
        icon: zIcon.optional(),
        permissions: zPermissionList.optional(),
        denies: zPermissionList.optional(),
        archived: z.boolean().optional(),
      }),
      req.body,
    );
    const { role, actor } = loadRole(roleId, userId);
    const before = grantOf(role);
    const after: RoleGrant = { permissions: body.permissions ?? before.permissions, denies: body.denies ?? before.denies };
    // Editing anything (archiving too) needs the right to define this role, before and after the change.
    const refusal = roleDefinitionRefusal(actor, after, before);
    if (refusal) throwRefusal(refusal);
    const archiveFlip = body.archived !== undefined && body.archived !== (role.archived_at !== null);
    if (archiveFlip || !sameKeys(after.permissions, before.permissions) || !sameKeys(after.denies, before.denies)) requireReachOverHolders(role, actor, userId);
    const t = now();
    const result = db.transaction(() => {
      const current = roleRow(roleId);
      if (!current) throw notFound("Role not found");
      if (current.rev !== body.baseRev) return { stale: current };
      const archiving = body.archived === undefined ? null : body.archived;
      const name = body.name ?? current.name;
      if ((archiving === false && current.archived_at) || (body.name && !current.archived_at)) {
        const taken = nameTaken(current.org_id, name, current.id);
        if (taken) refuseTaken(taken, name);
      }
      if (archiving === false && current.archived_at && activeCount(current.org_id) >= MAX_ACTIVE_ROLES) {
        throw conflict(`A workspace can have at most ${MAX_ACTIVE_ROLES} active roles. Archive one first.`);
      }
      db.prepare(
        `UPDATE org_roles SET name = ?, description = ?, color = ?, icon = ?, permissions = ?, denies = ?, archived_at = ?, rev = rev + 1, updated_at = ?
         WHERE id = ? AND rev = ?`,
      ).run(
        name,
        body.description === undefined ? current.description : body.description,
        body.color === undefined ? current.color : body.color,
        body.icon === undefined ? current.icon : body.icon,
        JSON.stringify(after.permissions),
        JSON.stringify(after.denies),
        archiving === null ? current.archived_at : archiving ? t : null,
        t,
        roleId,
        body.baseRev,
      );
      return { stale: null, archiveChange: archiving !== null && archiving !== (current.archived_at !== null), archiving };
    })();
    if (result.stale) {
      throw new ApiError(409, "stale", "Someone else changed this role. Review the latest version and try again.", { role: view(result.stale, actor) });
    }
    const action = result.archiveChange ? (result.archiving ? "role.archive" : "role.restore") : "role.update";
    ctx.audit({ actorId: userId, orgId: role.org_id, action, target: roleId, detail: body.name ?? role.name });
    // A role's restrictions and archiving change channel access (categories it opens, `channels.view` it restricts).
    await reconcileAccess(ctx, role.org_id, holders(roleId));
    return { role: view(roleRow(roleId)!, actor) };
  });

  /** Deleting is for archived roles only: archive first (assignments stop counting), then delete for good. */
  app.delete("/v1/roles/:roleId", auth, async (req) => {
    const { userId } = me(req);
    const { roleId } = parse(z.object({ roleId: zId }), req.params);
    const { role, actor } = loadRole(roleId, userId);
    const refusal = roleDefinitionRefusal(actor, grantOf(role), grantOf(role));
    if (refusal) throwRefusal(refusal);
    if (!role.archived_at) throw new ApiError(409, "not_archived", "Archive the role before deleting it");
    const removed = memberCount(roleId);
    db.transaction(() => {
      db.prepare("DELETE FROM channel_overwrites WHERE org_id = ? AND subject = ?").run(role.org_id, roleId);
      db.prepare("DELETE FROM org_roles WHERE id = ? AND archived_at IS NOT NULL").run(roleId);
    })();
    ctx.audit({ actorId: userId, orgId: role.org_id, action: "role.delete", target: roleId, detail: `${role.name} (${removed} assignment(s))` });
    notifyOrg(ctx, role.org_id);
    return { ok: true };
  });

  // ───────────── Assignments ─────────────
  app.get("/v1/roles/:roleId/members", auth, async (req) => {
    const { userId } = me(req);
    const { roleId } = parse(z.object({ roleId: zId }), req.params);
    const { role, actor } = loadRole(roleId, userId);
    if (!actor.perms.has("roles.view") || !actor.perms.has("members.view")) throw forbidden("Your role doesn't include seeing who holds roles");
    const rows = db
      .prepare(
        `SELECT u.id, u.username, u.display_name, m.role AS org_role, a.assigned_at, a.assigned_by FROM org_role_members a
         JOIN users u ON u.id = a.user_id JOIN org_members m ON m.org_id = a.org_id AND m.user_id = a.user_id
         WHERE a.role_id = ? ORDER BY u.display_name LIMIT 1000`,
      )
      .all(role.id) as { id: string; username: string; display_name: string; org_role: OrgRole; assigned_at: number; assigned_by: string }[];
    return {
      members: rows.map((r) => ({ id: r.id, username: r.username, displayName: r.display_name, orgRole: r.org_role, assignedAt: r.assigned_at, assignedBy: r.assigned_by })),
    };
  });

  const targetIn = (orgId: string, targetId: string, actorId: string) => {
    const t = authz.actor(orgId, targetId);
    if (!t) throw notFound("That person is not a member of this workspace");
    return { base: t.base, perms: t.perms, self: targetId === actorId };
  };

  app.put("/v1/roles/:roleId/members/:userId", auth, async (req) => {
    const actorId = me(req).userId;
    const p = parse(z.object({ roleId: zId, userId: zId }), req.params);
    const { role, actor } = loadRole(p.roleId, actorId);
    if (role.archived_at) throw new ApiError(409, "archived", "This role is archived. Restore it before assigning it.");
    const target = targetIn(role.org_id, p.userId, actorId);
    const refusal = roleAssignmentRefusal(actor, target, grantOf(role));
    if (refusal) throwRefusal(refusal);
    const added = db.transaction(() => {
      if (db.prepare("SELECT 1 FROM org_role_members WHERE role_id = ? AND user_id = ?").get(role.id, p.userId)) return false;
      const held = (db.prepare("SELECT COUNT(*) n FROM org_role_members WHERE org_id = ? AND user_id = ?").get(role.org_id, p.userId) as { n: number }).n;
      if (held >= MAX_ROLES_PER_MEMBER) throw conflict(`A person can hold at most ${MAX_ROLES_PER_MEMBER} roles`);
      db.prepare("INSERT INTO org_role_members (role_id, org_id, user_id, assigned_by, assigned_at) VALUES (?, ?, ?, ?, ?)").run(role.id, role.org_id, p.userId, actorId, now());
      return true;
    })();
    // Assigning twice is harmless (a retry or a double tap): the same answer, no second audit entry.
    if (added) {
      ctx.audit({ actorId, orgId: role.org_id, action: "role.assign", target: `${role.id}:${p.userId}`, detail: role.name });
      await reconcileAccess(ctx, role.org_id, [p.userId]);
    }
    return { ok: true, permissions: [...authz.permissions(role.org_id, p.userId)].sort() };
  });

  app.delete("/v1/roles/:roleId/members/:userId", auth, async (req) => {
    const actorId = me(req).userId;
    const p = parse(z.object({ roleId: zId, userId: zId }), req.params);
    const { role, actor } = loadRole(p.roleId, actorId);
    const target = targetIn(role.org_id, p.userId, actorId);
    const refusal = roleRemovalRefusal(actor, target, grantOf(role));
    if (refusal) throwRefusal(refusal);
    const removed = db.prepare("DELETE FROM org_role_members WHERE role_id = ? AND user_id = ?").run(role.id, p.userId).changes > 0;
    if (removed) {
      ctx.audit({ actorId, orgId: role.org_id, action: "role.unassign", target: `${role.id}:${p.userId}`, detail: role.name });
      await reconcileAccess(ctx, role.org_id, [p.userId]);
    }
    return { ok: true, permissions: [...authz.permissions(role.org_id, p.userId)].sort() };
  });

  /** One person's effective permissions and where each comes from (their base role, which custom roles). */
  app.get("/v1/orgs/:orgId/members/:userId/permissions", auth, async (req) => {
    const viewer = me(req).userId;
    const p = parse(z.object({ orgId: zId, userId: zId }), req.params);
    authz.requireOrgMember(p.orgId, viewer);
    if (viewer !== p.userId && !(authz.can(p.orgId, viewer, "roles.view") && authz.can(p.orgId, viewer, "members.view"))) {
      throw forbidden("Your role doesn't include seeing other people's permissions");
    }
    const target = authz.actor(p.orgId, p.userId);
    if (!target) throw notFound("Member not found");
    const roles = authz.assignedRoles(p.orgId, p.userId);
    const sources: Record<string, string[]> = {};
    for (const perm of target.perms) {
      const from = BASE_ROLE_PERMISSIONS[target.base].has(perm) ? ["base"] : [];
      for (const r of roles) if (r.permissions.includes(perm)) from.push(r.id);
      sources[perm] = from;
    }
    const restricted = [...new Set(roles.flatMap((r) => r.denies))].filter((d) => !target.perms.has(d));
    return {
      base: target.base,
      roles: roles.map((r) => ({ id: r.id, name: r.name, color: r.color })),
      effective: [...target.perms].sort(),
      sources,
      restricted,
    };
  });

  // ───────────── Ownership transfer ─────────────
  // Owners only, after typing the workspace's exact name. The new owner must already be a member (not a guest); the
  // previous owner becomes an admin, so there is always exactly the owners someone chose.
  app.post("/v1/orgs/:orgId/ownership", auth, async (req) => {
    const actorId = me(req).userId;
    const { orgId } = parse(z.object({ orgId: zId }), req.params);
    const body = parse(z.object({ userId: zId, confirmName: z.string().max(200) }), req.body);
    authz.requirePermission(orgId, actorId, "workspace.transfer", "Only an owner can transfer ownership");
    if (body.userId === actorId) throw new ApiError(400, "bad_request", "Choose someone else to become the owner");
    const org = db.prepare("SELECT name FROM orgs WHERE id = ?").get(orgId) as { name: string };
    if (body.confirmName.trim() !== org.name.trim()) throw new ApiError(400, "confirm_mismatch", "Type the workspace's name exactly as it appears to transfer it");
    const targetRole = authz.orgRole(orgId, body.userId);
    if (!targetRole) throw notFound("That person is not a member of this workspace");
    if (targetRole === "GUEST") throw conflict("Make this guest a member before handing them the workspace");
    if (targetRole === "OWNER") throw conflict("This person is already an owner");
    db.transaction(() => {
      // Checked again inside the write lock: the actor may have stopped being an owner a moment ago.
      if (authz.orgRole(orgId, actorId) !== "OWNER") throw forbidden("Only an owner can transfer ownership");
      db.prepare("UPDATE org_members SET role = 'OWNER' WHERE org_id = ? AND user_id = ?").run(orgId, body.userId);
      db.prepare("UPDATE org_members SET role = 'ADMIN' WHERE org_id = ? AND user_id = ?").run(orgId, actorId);
      authz.autoJoinScope(orgId, body.userId, now());
    })();
    ctx.audit({ actorId, orgId, action: "org.ownership.transfer", target: body.userId, detail: `${targetRole}→OWNER; previous owner→ADMIN` });
    notifyOrg(ctx, orgId);
    return { ok: true, newOwner: body.userId, yourRole: "ADMIN" as OrgRole };
  });

  // ───────────── Usage and storage ─────────────
  app.get("/v1/orgs/:orgId/usage", auth, async (req) => {
    const { userId } = me(req);
    const { orgId } = parse(z.object({ orgId: zId }), req.params);
    authz.requirePermission(orgId, userId, "workspace.usage.view", "Your role doesn't include usage figures");
    const count = (sql: string, ...args: unknown[]) => (db.prepare(sql).get(...args) as { n: number | null }).n ?? 0;
    const byRole = new Map(
      (db.prepare("SELECT role, COUNT(*) n FROM org_members WHERE org_id = ? GROUP BY role").all(orgId) as { role: OrgRole; n: number }[]).map((r) => [r.role, r.n]),
    );
    const t = now();
    const files = db
      .prepare(
        `SELECT COUNT(*) n, COALESCE(SUM(a.size), 0) bytes FROM attachments a JOIN conversations c ON c.id = a.conversation_id
         WHERE c.org_id = ? AND a.expires_at > ?`,
      )
      .get(orgId, t) as { n: number; bytes: number };
    return {
      members: {
        total: [...byRole.values()].reduce((a, b) => a + b, 0),
        owners: byRole.get("OWNER") ?? 0,
        admins: byRole.get("ADMIN") ?? 0,
        members: byRole.get("MEMBER") ?? 0,
        guests: byRole.get("GUEST") ?? 0,
      },
      structure: {
        // Zero since branches and teams became roles and categories; kept for apps that read them.
        branches: 0,
        departments: 0,
        teams: 0,
        categories: count("SELECT COUNT(*) n FROM channel_categories WHERE org_id = ?", orgId),
        channels: count("SELECT COUNT(*) n FROM conversations WHERE org_id = ? AND type = 'CHANNEL' AND archived_at IS NULL", orgId),
        archivedChannels: count("SELECT COUNT(*) n FROM conversations WHERE org_id = ? AND type = 'CHANNEL' AND archived_at IS NOT NULL", orgId),
      },
      // Sizes of the encrypted files the server still holds for the workspace's channels (it never sees their content).
      files: { count: files.n, bytes: files.bytes },
      invitations: { active: count("SELECT COUNT(*) n FROM invitations WHERE org_id = ? AND revoked_at IS NULL AND expires_at > ? AND uses < max_uses", orgId, t) },
      roles: { custom: count("SELECT COUNT(*) n FROM org_roles WHERE org_id = ? AND archived_at IS NULL", orgId) },
      limits: { fileMaxBytes: ctx.config.fileMaxBytes, orgStoredBytes: ctx.config.abuse.orgStoredBytes || null },
      // Round 7: measured usage over the last 30 days, per day (docs/USAGE_AND_PLANS.md). Counts and sizes only.
      metered: (() => {
        ctx.usage.snapshot();
        return { days: 30, metrics: ctx.usage.series(orgId, 30) };
      })(),
    };
  });

  // ───────────── Plan and limits (docs/PLANS.md) ─────────────
  // Every member may see what their workspace can use. No plans exist yet: the answer says so, with any server-wide limits.
  app.get("/v1/orgs/:orgId/plan", auth, async (req) => {
    const { userId } = me(req);
    const { orgId } = parse(z.object({ orgId: zId }), req.params);
    authz.requireOrgMember(orgId, userId);
    const customRoles = (db.prepare("SELECT COUNT(*) n FROM org_roles WHERE org_id = ? AND archived_at IS NULL").get(orgId) as { n: number }).n;
    return ctx.entitlements.plan(planUsage(ctx, orgId, customRoles), orgId);
  });

  // ───────────── The full audit log ─────────────
  // For people with `audit.view`: every recorded change, newest first, a page at a time (`before` is the last id seen).
  app.get("/v1/orgs/:orgId/audit", auth, async (req) => {
    const { userId } = me(req);
    const { orgId } = parse(z.object({ orgId: zId }), req.params);
    const q = parse(
      z.object({
        before: z.coerce.number().int().min(1).optional(),
        limit: z.coerce.number().int().min(1).max(AUDIT_PAGE_MAX).default(50),
        action: z.string().regex(/^[a-z_.]{1,40}$/).optional(),
        /** Batch B4: only changes made by this person (Discord's "Filter by User"). */
        actorId: z.string().min(1).max(64).optional(),
      }),
      req.query,
    );
    authz.requirePermission(orgId, userId, "audit.view", "Your role doesn't include the audit log");
    const rows = db
      .prepare(
        `SELECT a.*, u.display_name AS actor_name FROM audit_log a LEFT JOIN users u ON u.id = a.actor_id
         WHERE a.org_id = ? AND a.id < ? AND (? IS NULL OR a.action = ? OR a.action LIKE ? ESCAPE '\\') AND (? IS NULL OR a.actor_id = ?)
         ORDER BY a.id DESC LIMIT ?`,
      )
      .all(orgId, q.before ?? Number.MAX_SAFE_INTEGER, q.action ?? null, q.action ?? null, q.action ? `${q.action.replace(/[%_\\]/g, (c) => "\\" + c)}.%` : null, q.actorId ?? null, q.actorId ?? null, q.limit + 1) as any[];
    const page = rows.slice(0, q.limit);
    return {
      entries: page.map((r) => ({ id: r.id, at: r.at, action: r.action, actorId: r.actor_id, actorName: r.actor_name ?? null, target: r.target, detail: r.detail })),
      nextBefore: rows.length > q.limit ? page[page.length - 1].id : null,
    };
  });
}
