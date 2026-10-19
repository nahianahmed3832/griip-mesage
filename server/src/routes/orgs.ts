import type { FastifyInstance } from "fastify";
import { z } from "zod";
import type { BranchRow, ConversationRow, TeamRow } from "../authz.js";
import { type AppContext, me, parse, requireAuth, zName, zText } from "../context.js";
import { memberView } from "../profile.js";
import { ApiError, badRequest, conflict, forbidden, humanCode, newId, normalizeCode, notFound, now, sha256Hex } from "../util.js";
import { INVITE_PROBLEMS, inviteAlreadySatisfied, joinOrgByInvite, lookupOrgInvite, orgInviteProblem } from "../invites.js";
import { userSummary } from "./auth.js";
import { deleteBlobNow, processBlobDeletes, queueBlobDelete } from "./attachments.js";
import { photoBlobId, readJpegBody, storePhoto } from "../photos.js";
import { evictRemovedMember } from "./calls.js";
import { conversationView } from "./conversations.js";
import { orgRoleChangeRefusal, removalAllowed, titleChangeAllowed } from "../roles.js";

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

export type InviteStatus = "active" | "revoked" | "expired" | "used_up";

/** An invitation's one state at [t]: revoked, else expired, else used up, else active. */
export function inviteStatus(r: { revoked_at: number | null; expires_at: number; uses: number; max_uses: number }, t: number): InviteStatus {
  if (r.revoked_at) return "revoked";
  if (r.expires_at <= t) return "expired";
  if (r.uses >= r.max_uses) return "used_up";
  return "active";
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
    // The organisation's avatar revision (GET /v1/orgs/:orgId/photo), null without one.
    photoRev: org.photo_rev ?? null,
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
  const ownerCount = (orgId: string): number =>
    (db.prepare("SELECT COUNT(*) n FROM org_members WHERE org_id = ? AND role = 'OWNER'").get(orgId) as { n: number }).n;
  const loadTeam = (id: string, userId: string): TeamRow => {
    const t = authz.team(id);
    if (!t) throw notFound("Team not found");
    authz.requireOrgMember(t.org_id, userId);
    return t;
  };

  // ───────────── Organisations ─────────────
  app.post("/v1/orgs", auth, async (req) => {
    const { userId } = me(req);
    const body = parse(z.object({ name: zName, description: zOptText, requestId: z.string().trim().min(8).max(64).optional() }), req.body);
    // Idempotent: the app sends one request id per create form, so a retry or a double tap returns the first result.
    if (body.requestId) {
      const prior = db.prepare("SELECT * FROM orgs WHERE created_by = ? AND create_request_id = ?").get(userId, body.requestId);
      if (prior) return { org: orgSummary(ctx, prior, userId) };
    }
    // One person owning two workspaces with the same name can't tell them apart: refuse, and point at the existing one.
    const sameName = db
      .prepare(
        `SELECT o.id FROM orgs o JOIN org_members m ON m.org_id = o.id AND m.user_id = ? AND m.role = 'OWNER'
          WHERE lower(trim(o.name)) = lower(trim(?)) LIMIT 1`,
      )
      .get(userId, body.name) as { id: string } | undefined;
    if (sameName) throw new ApiError(409, "org_name_taken", `You already own a workspace called "${body.name.trim()}". Open it, or choose another name.`, { orgId: sameName.id });
    const id = newId("o");
    const t = now();
    let slug = slugify(body.name);
    if (db.prepare("SELECT 1 FROM orgs WHERE slug = ?").get(slug)) slug = `${slug}-${humanCode(4).toLowerCase()}`;
    db.transaction(() => {
      db.prepare(
        "INSERT INTO orgs (id, name, slug, description, created_by, created_at, updated_at, create_request_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
      ).run(id, body.name, slug, body.description ?? null, userId, t, t, body.requestId ?? null);
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
    const perms = authz.permissions(orgId, userId);
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
        canManageOrg: perms.has("workspace.edit"),
        canInvite: perms.has("members.invite") || branches.some((b) => b.canManage) || teams.some((t) => t.canManage),
        canCreateChannels: perms.has("channels.create") || branches.some((b) => b.canManage) || teams.some((t) => t.canManage),
        canViewDirectory: perms.has("members.view"),
        isOwner: role === "OWNER",
        // Everyone may leave except the last owner, who must make someone else an owner first.
        canLeave: role !== "OWNER" || ownerCount(orgId) > 1,
        // Owners and admins see archived branches and teams (GET /v1/orgs/:id/archived); branch managers see their teams.
        canRestore: perms.has("teams.manage") || branches.some((b) => b.canManage),
        // Custom roles and apps (2026-10-19, docs/WORKSPACE_ROLES.md, docs/INTEGRATIONS.md). Additive: older apps ignore them.
        canViewRoles: perms.has("roles.view"),
        canManageRoles: perms.has("roles.manage"),
        canAssignRoles: perms.has("roles.assign"),
        canViewAudit: perms.has("audit.view"),
        canViewUsage: perms.has("workspace.usage.view"),
        canViewApps: perms.has("integrations.view"),
        canManageApps: perms.has("integrations.install") || perms.has("integrations.configure"),
        canManagePolicies: perms.has("policies.manage"),
        canTransferOwnership: perms.has("workspace.transfer"),
        effective: [...perms].sort(),
      },
      // The custom roles the caller holds here (name and colour, for their own profile).
      myRoles: authz.assignedRoles(orgId, userId).map((r) => ({ id: r.id, name: r.name, color: r.color })),
    };
  });

  app.patch("/v1/orgs/:orgId", auth, async (req) => {
    const { userId } = me(req);
    const { orgId } = parse(z.object({ orgId: z.string().max(64) }), req.params);
    authz.requirePermission(orgId, userId, "workspace.edit", "Only people who can edit the workspace can do that");
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

  // ───────────── Organization avatar (2026-10-19, docs/WORKSPACE_ADMINISTRATION.md) ─────────────
  // A JPEG like people's photos, stored per revision. Owners and admins set it; every member can see it. It isn't
  // end-to-end encrypted: like the organisation's name, the server can see it.
  app.put("/v1/orgs/:orgId/photo", auth, async (req) => {
    const { userId } = me(req);
    const { orgId } = parse(z.object({ orgId: z.string().max(64) }), req.params);
    authz.requirePermission(orgId, userId, "workspace.edit", "Only people who can edit the workspace can change its photo");
    const buf = await readJpegBody(req, "Workspace photos");
    const previous = (db.prepare("SELECT photo_rev FROM orgs WHERE id = ?").get(orgId) as { photo_rev: number | null } | undefined)?.photo_rev ?? null;
    const rev = Math.max(now(), (previous ?? 0) + 1);
    await storePhoto(ctx, photoBlobId(orgId, rev), buf);
    // The organisation may have been deleted while the photo uploaded: then the new blob is removed again.
    if (db.prepare("UPDATE orgs SET photo_rev = ?, updated_at = ? WHERE id = ?").run(rev, now(), orgId).changes === 0) {
      await deleteBlobNow(ctx, "profile-photos", photoBlobId(orgId, rev));
      throw notFound("Organization not found");
    }
    if (previous) await deleteBlobNow(ctx, "profile-photos", photoBlobId(orgId, previous));
    ctx.audit({ actorId: userId, orgId, action: "org.photo" });
    notifyOrg(ctx, orgId);
    return { photoRev: rev };
  });

  app.delete("/v1/orgs/:orgId/photo", auth, async (req) => {
    const { userId } = me(req);
    const { orgId } = parse(z.object({ orgId: z.string().max(64) }), req.params);
    authz.requirePermission(orgId, userId, "workspace.edit", "Only people who can edit the workspace can change its photo");
    const previous = (db.prepare("SELECT photo_rev FROM orgs WHERE id = ?").get(orgId) as { photo_rev: number | null }).photo_rev;
    db.prepare("UPDATE orgs SET photo_rev = NULL, updated_at = ? WHERE id = ?").run(now(), orgId);
    if (previous) {
      await deleteBlobNow(ctx, "profile-photos", photoBlobId(orgId, previous));
      ctx.audit({ actorId: userId, orgId, action: "org.photo", detail: "removed" });
      notifyOrg(ctx, orgId);
    }
    return { photoRev: null };
  });

  app.get("/v1/orgs/:orgId/photo", auth, async (req, reply) => {
    const { userId } = me(req);
    const { orgId } = parse(z.object({ orgId: z.string().max(64) }), req.params);
    authz.requireOrgMember(orgId, userId);
    const rev = (db.prepare("SELECT photo_rev FROM orgs WHERE id = ?").get(orgId) as { photo_rev: number | null }).photo_rev;
    const stream = rev ? await ctx.blobs.get("profile-photos", photoBlobId(orgId, rev)) : null;
    if (!stream) throw notFound("No photo");
    return reply.header("content-type", "image/jpeg").header("cache-control", "private, max-age=3600").header("etag", `"${rev}"`).send(stream);
  });

  // ───────────── Deleting an organization (2026-10-17, docs/WORKSPACES.md › Deleting a workspace) ─────────────
  // Owners only, after typing the organization's exact name. One immediate transaction removes the organization with
  // its branches, departments, teams, invitations, memberships and channels (and the channels' calls, queued messages
  // and files); people's accounts, direct messages, groups and other organizations are untouched. A retried request
  // with the same requestId gets the same answer; anything else afterwards gets 404.
  app.delete("/v1/orgs/:orgId", auth, async (req) => {
    const actor = me(req).userId;
    const { orgId } = parse(z.object({ orgId: z.string().max(64) }), req.params);
    const body = parse(z.object({ confirmName: z.string().max(200), requestId: z.string().min(8).max(64).optional() }), req.body ?? {});
    const previous = () =>
      db.prepare("SELECT * FROM org_deletions WHERE org_id = ?").get(orgId) as
        | { deleted_by: string; request_id: string | null; members: number; channels: number; files: number }
        | undefined;
    const replay = () => {
      const d = previous();
      if (d && body.requestId && d.request_id === body.requestId && d.deleted_by === actor) {
        return { deleted: true, orgId, members: d.members, channels: d.channels, files: d.files };
      }
      throw notFound("Organization not found");
    };
    if (previous()) return replay();
    const role = authz.requireOrgMember(orgId, actor);
    if (role !== "OWNER") throw forbidden("Only an owner can delete the organization");
    const org = db.prepare("SELECT name FROM orgs WHERE id = ?").get(orgId) as { name: string };
    if (body.confirmName.trim() !== org.name.trim()) throw new ApiError(400, "confirm_mismatch", "Type the organization's name exactly as it appears to delete it");

    const result = db
      .transaction(() => {
        // Checked again inside the write lock: a concurrent delete may have just finished.
        if (!db.prepare("SELECT 1 FROM orgs WHERE id = ?").get(orgId)) return null;
        if (authz.orgRole(orgId, actor) !== "OWNER") return null;
        const members = (db.prepare("SELECT user_id FROM org_members WHERE org_id = ?").all(orgId) as { user_id: string }[]).map((r) => r.user_id);
        const channels = (db.prepare("SELECT id FROM conversations WHERE org_id = ?").all(orgId) as { id: string }[]).map((r) => r.id);
        const channelMembers = new Map<string, string[]>();
        const files: string[] = [];
        const rooms: string[] = [];
        for (const c of channels) {
          channelMembers.set(c, (db.prepare("SELECT user_id FROM conversation_members WHERE conversation_id = ?").all(c) as { user_id: string }[]).map((r) => r.user_id));
          files.push(...(db.prepare("SELECT id FROM attachments WHERE conversation_id = ?").all(c) as { id: string }[]).map((r) => r.id));
          rooms.push(...(db.prepare("SELECT room_name FROM calls WHERE conversation_id = ? AND state != 'ENDED'").all(c) as { room_name: string }[]).map((r) => r.room_name));
          // Rows without a foreign key to the conversation.
          db.prepare("DELETE FROM delivery_queue WHERE conversation_id = ?").run(c);
          db.prepare("DELETE FROM submitted_messages WHERE conversation_id = ?").run(c);
          db.prepare("DELETE FROM mention_all_events WHERE conversation_id = ?").run(c);
        }
        for (const f of files) queueBlobDelete(ctx, "attachments", f);
        const photoRev = (db.prepare("SELECT photo_rev FROM orgs WHERE id = ?").get(orgId) as { photo_rev: number | null }).photo_rev;
        if (photoRev) queueBlobDelete(ctx, "profile-photos", photoBlobId(orgId, photoRev));
        // Direct uploads still open in its channels: their objects or parts are removed by the retention job.
        for (const c of channels) {
          db.prepare(
            "INSERT OR IGNORE INTO abandoned_uploads (id, multipart_id, attempts, next_at) SELECT id, multipart_id, 0, 0 FROM attachment_uploads WHERE conversation_id = ?",
          ).run(c);
        }
        // Cascades to org_members, branches (and their members), departments, teams (and members), invitations and
        // the organization's conversations (members, attachment rows, calls and call participants).
        db.prepare("DELETE FROM orgs WHERE id = ?").run(orgId);
        db.prepare(
          "INSERT INTO org_deletions (org_id, name_sha256, deleted_by, deleted_at, request_id, members, channels, files) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        ).run(orgId, sha256Hex(org.name), actor, now(), body.requestId ?? null, members.length, channels.length, files.length);
        return { members, channelMembers, files, rooms };
      })
      .immediate();
    if (!result) return replay();
    await ctx.prefs.deleteForWorkspace(orgId);
    // Its apps: every connection revoked at the provider (best effort) and every record removed.
    await ctx.integrations.forgetWorkspace(orgId);

    ctx.audit({
      actorId: actor,
      orgId,
      action: "org.delete",
      target: orgId,
      detail: JSON.stringify({ members: result.members.length, channels: result.channelMembers.size, files: result.files.length }),
    });
    // Every device updates: apps from before this change refresh the organization list and each channel (now 404, so
    // they show it as left); newer apps also get org.deleted.
    for (const [c, ids] of result.channelMembers) ctx.hub.sendToUsers(new Set(ids), { t: "conv.updated", conversationId: c });
    ctx.hub.sendToUsers(new Set(result.members), { t: "org.updated", orgId });
    ctx.hub.sendToUsers(new Set(result.members), { t: "org.deleted", orgId });
    for (const room of result.rooms) await ctx.livekit?.deleteRoom(room).catch(() => {});
    await processBlobDeletes(ctx).catch(() => 0);
    return { deleted: true, orgId, members: result.members.length, channels: result.channelMembers.size, files: result.files.length };
  });

  // ───────────── Branches & departments ─────────────
  app.post("/v1/orgs/:orgId/branches", auth, async (req) => {
    const { userId } = me(req);
    const { orgId } = parse(z.object({ orgId: z.string().max(64) }), req.params);
    authz.requirePermission(orgId, userId, "teams.create", "Only organization owners and admins can create branches");
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
    if (body.archived !== undefined && !authz.can(b.org_id, userId, "teams.manage")) throw forbidden("Only org admins can archive branches");
    db.prepare("UPDATE branches SET name = ?, description = ?, location = ?, archived_at = ?, updated_at = ? WHERE id = ?").run(
      body.name ?? b.name,
      body.description === undefined ? b.description : body.description,
      body.location === undefined ? b.location : body.location,
      body.archived === undefined ? b.archived_at : body.archived ? now() : null,
      now(),
      id,
    );
    const archiveChange = body.archived !== undefined && body.archived !== !!b.archived_at;
    ctx.audit({
      actorId: userId,
      orgId: b.org_id,
      action: archiveChange ? (body.archived ? "branch.archive" : "branch.restore") : "branch.update",
      target: id,
      detail: body.name ?? b.name,
    });
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
      if (!authz.canManageBranch(b, userId) && !authz.can(orgId, userId, "teams.create")) {
        throw forbidden("Only org admins or this branch's managers can create teams here");
      }
    } else if (!authz.can(orgId, userId, "teams.create")) {
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
    if (body.archived !== undefined && body.archived !== !!team.archived_at && !authz.canArchiveTeam(team, userId)) {
      throw forbidden("Only org admins or this branch's managers can archive or restore a team");
    }
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
    const archiveChange = body.archived !== undefined && body.archived !== !!team.archived_at;
    ctx.audit({
      actorId: userId,
      orgId: team.org_id,
      action: archiveChange ? (body.archived ? "team.archive" : "team.restore") : "team.update",
      target: id,
      detail: body.name ?? team.name,
    });
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
    return { members: rows.map((u) => memberView(ctx, me(req).userId, u, { ...userSummary(u), role: u.team_role })) };
  });

  app.get("/v1/branches/:id/members", auth, async (req) => {
    const { userId } = me(req);
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const b = loadBranch(id, userId);
    if (!authz.canAccessBranch(b, userId)) throw notFound("Branch not found");
    const rows = db
      .prepare("SELECT u.*, bm.role AS branch_role FROM branch_members bm JOIN users u ON u.id = bm.user_id WHERE bm.branch_id = ? ORDER BY bm.role, u.display_name")
      .all(id) as any[];
    return { members: rows.map((u) => memberView(ctx, me(req).userId, u, { ...userSummary(u), role: u.branch_role })) };
  });

  /**
   * Archived branches and teams the caller may restore (docs/WORKSPACE_ADMINISTRATION.md): every archived branch and
   * team for owners and admins; a branch manager's archived teams in their branch. Others get empty lists. Archived
   * channels are listed by GET /v1/orgs/:id/channels?includeArchived=true.
   */
  app.get("/v1/orgs/:orgId/archived", auth, async (req) => {
    const { userId } = me(req);
    const { orgId } = parse(z.object({ orgId: z.string().max(64) }), req.params);
    authz.requireOrgMember(orgId, userId);
    const branches = authz.can(orgId, userId, "teams.manage")
      ? (db.prepare("SELECT * FROM branches WHERE org_id = ? AND archived_at IS NOT NULL ORDER BY name").all(orgId) as BranchRow[])
      : [];
    const teams = (db.prepare("SELECT * FROM teams WHERE org_id = ? AND archived_at IS NOT NULL ORDER BY name").all(orgId) as TeamRow[]).filter((t) =>
      authz.canArchiveTeam(t, userId),
    );
    return {
      branches: branches.map((b) => ({ id: b.id, name: b.name, description: b.description, location: b.location, archivedAt: b.archived_at })),
      teams: teams.map((t) => ({
        id: t.id,
        name: t.name,
        description: t.description,
        branchId: t.branch_id,
        crossBranch: t.branch_id === null,
        archivedAt: t.archived_at,
        // A team in an archived branch comes back with its branch (D-14).
        branchArchived: authz.scopeArchived(t.branch_id, null),
      })),
    };
  });

  // ───────────── Membership & roles ─────────────
  app.get("/v1/orgs/:orgId/members", auth, async (req) => {
    const { userId } = me(req);
    const { orgId } = parse(z.object({ orgId: z.string().max(64) }), req.params);
    const role = authz.requireOrgMember(orgId, userId);
    if (role === "GUEST") throw forbidden("Guests cannot browse the organization directory");
    if (!authz.can(orgId, userId, "members.view")) throw forbidden("Your role doesn't include the member directory");
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
    // Custom roles (docs/WORKSPACE_ROLES.md): names and colours only; what each grants is on GET /v1/orgs/:id/roles.
    const roleRows = db
      .prepare(
        `SELECT m.user_id, r.id, r.name, r.color FROM org_role_members m JOIN org_roles r ON r.id = m.role_id
         WHERE m.org_id = ? AND r.archived_at IS NULL ORDER BY r.position, r.name`,
      )
      .all(orgId) as { user_id: string; id: string; name: string; color: string | null }[];
    return {
      members: rows.map((u) =>
        memberView(ctx, userId, u, {
          ...userSummary(u),
          orgRole: u.org_role,
          title: u.title,
          branches: branchRows.filter((r) => r.user_id === u.id).map((r) => ({ id: r.branch_id, role: r.role })),
          teams: teamRows.filter((r) => r.user_id === u.id).map((r) => ({ id: r.team_id, role: r.role })),
          customRoles: roleRows.filter((r) => r.user_id === u.id).map((r) => ({ id: r.id, name: r.name, color: r.color })),
        }),
      ),
    };
  });

  app.patch("/v1/orgs/:orgId/members/:userId", auth, async (req) => {
    const actor = me(req).userId;
    const p = parse(z.object({ orgId: z.string().max(64), userId: z.string().max(64) }), req.params);
    const body = parse(z.object({ role: z.enum(["OWNER", "ADMIN", "MEMBER", "GUEST"]).optional(), title: zOptText }), req.body);
    authz.requireOrgMember(p.orgId, actor);
    const actorNow = authz.actor(p.orgId, actor)!;
    const targetRole = authz.orgRole(p.orgId, p.userId);
    if (!targetRole) throw notFound("Member not found");
    const self = actor === p.userId;
    const targetPerms = authz.permissions(p.orgId, p.userId);
    // Rules in roles.ts (docs/WORKSPACE_ADMINISTRATION.md, WORKSPACE_ROLES.md): owners manage everything but keep one
    // owner; admins (and `members.roles`) switch members and guests, admins may step down; nobody else changes roles.
    if (body.role && body.role !== targetRole) {
      // The actor must reach the person both as they are and as they'd be (a guest's held roles count again once promoted).
      const reach = new Set([...targetPerms, ...authz.permissionsAs(p.orgId, p.userId, body.role)]);
      const refusal = orgRoleChangeRefusal(actorNow, targetRole, body.role, self, ownerCount(p.orgId), reach);
      if (refusal) throw refusal.status === 409 ? conflict(refusal.message) : forbidden(refusal.message);
    }
    if (body.title !== undefined && !titleChangeAllowed(actorNow, targetRole, self, targetPerms)) {
      throw forbidden("You can't change this person's title");
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
    if (!self && !removalAllowed(authz.actor(p.orgId, actor)!, targetRole, authz.permissions(p.orgId, p.userId))) {
      throw forbidden(
        actorRole === "ADMIN"
          ? "Admins cannot remove owners or admins"
          : authz.can(p.orgId, actor, "members.remove")
            ? "You can't remove someone who holds powers you don't have"
            : "Only owners and admins can remove people",
      );
    }
    if (targetRole === "OWNER" && ownerCount(p.orgId) <= 1) throw conflict("Transfer ownership before the last owner leaves");
    const affected: string[] = [];
    db.transaction(() => {
      db.prepare("DELETE FROM branch_members WHERE user_id = ? AND branch_id IN (SELECT id FROM branches WHERE org_id = ?)").run(p.userId, p.orgId);
      db.prepare("DELETE FROM team_members WHERE user_id = ? AND team_id IN (SELECT id FROM teams WHERE org_id = ?)").run(p.userId, p.orgId);
      db.prepare("DELETE FROM org_members WHERE org_id = ? AND user_id = ?").run(p.orgId, p.userId);
      affected.push(...authz.revalidateChannelMemberships(p.orgId, p.userId));
    })();
    // Their arrangement of this workspace (starred, sections) goes with them.
    await ctx.prefs.deleteForMember(p.userId, p.orgId);
    // And their personal app connections in this workspace (revoked, deleted).
    await ctx.integrations.forgetMember(p.orgId, p.userId);
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
      if ((role === "MANAGER" || current === "MANAGER") && !authz.can(orgId, actor, "teams.manage")) throw forbidden("Only org admins can change branch managers");
    } else {
      const t = loadTeam(scopeId, actor);
      orgId = t.org_id;
      if (!authz.canManageTeam(t, actor)) throw forbidden();
      const current = authz.teamRole(t.id, target);
      const isLeadOnly = !authz.can(orgId, actor, "teams.manage") && !(t.branch_id && authz.branchRole(t.branch_id, actor) === "MANAGER");
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
    const isAdmin = authz.can(orgId, actor, "members.invites.manage");
    const rows = db
      .prepare("SELECT i.*, u.display_name AS creator_name FROM invitations i LEFT JOIN users u ON u.id = i.created_by WHERE i.org_id = ? ORDER BY i.created_at DESC LIMIT 100")
      .all(orgId) as any[];
    const t = now();
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
          active: inviteStatus(r, t) === "active",
          // One state, in this order: revoked beats expired beats used up (docs/WORKSPACE_ADMINISTRATION.md).
          status: inviteStatus(r, t),
          createdBy: r.created_by,
          createdByName: r.creator_name ?? null,
        })),
    };
  });

  app.delete("/v1/invites/:id", auth, async (req) => {
    const actor = me(req).userId;
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const inv = db.prepare("SELECT * FROM invitations WHERE id = ?").get(id) as any;
    if (!inv) throw notFound("Invite not found");
    authz.requireOrgMember(inv.org_id, actor);
    if (inv.created_by !== actor && !authz.can(inv.org_id, actor, "members.invites.manage")) throw forbidden();
    db.prepare("UPDATE invitations SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL").run(now(), id);
    ctx.audit({ actorId: actor, orgId: inv.org_id, action: "invite.revoke", target: id });
    return { ok: true };
  });

  /** The usable invitation for [code]; otherwise 404 with a code saying why (not found, revoked, expired, used up, unavailable). */
  const findInvite = (code: string) => {
    const inv = lookupOrgInvite(db, code);
    const problem = orgInviteProblem(authz, inv);
    if (problem) throw new ApiError(404, problem, INVITE_PROBLEMS[problem]);
    return inv!;
  };

  const inviteRate = { preHandler: requireAuth(ctx), config: { rateLimit: { max: 20, timeWindow: "1 minute" } } };

  app.post("/v1/invites/preview", inviteRate, async (req) => {
    const { code } = parse(z.object({ code: z.string().min(8).max(40) }), req.body);
    const inv = findInvite(code);
    const org = db.prepare("SELECT * FROM orgs WHERE id = ?").get(inv.org_id) as any;
    return {
      // Same org shape as everywhere else (the app decodes it with the same model; without the slug it refused the response).
      org: { id: org.id, name: org.name, slug: org.slug, description: org.description },
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
    // One transaction: two taps (or two people racing for the last use) can't both pass the checks and over-use it.
    const result = db.transaction(() => {
      const known = lookupOrgInvite(db, code);
      // Already joined through this invite (a retry or a double tap): succeed again without using it up, even if
      // that first accept took its last use. Grants nothing the person doesn't already have.
      if (known && inviteAlreadySatisfied(authz, db, known, userId)) return { orgId: known.org_id, inviteId: known.id, role: authz.orgRole(known.org_id, userId)!, repeated: true };
      return { ...joinOrgByInvite(ctx, findInvite(code), userId, t), repeated: false };
    })();
    if (!result.repeated) {
      ctx.audit({ actorId: userId, orgId: result.orgId, action: "invite.accept", target: result.inviteId, detail: result.role });
      notifyOrg(ctx, result.orgId);
    }
    return { org: orgSummary(ctx, db.prepare("SELECT * FROM orgs WHERE id = ?").get(result.orgId), userId), alreadyMember: result.repeated };
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
        /** A voice room (docs/VOICE_ROOMS.md): a channel whose call anyone in it can join, without ringing. */
        voice: z.boolean().default(false),
      }),
      req.body,
    );
    authz.requireOrgMember(orgId, actor);
    if (body.voice && body.kind === "ANNOUNCEMENT") throw badRequest("A voice room can be public or private, not an announcement channel");
    let scopeId: string | null = null;
    if (body.scope === "ORG") {
      if (!authz.can(orgId, actor, "channels.create")) throw forbidden("Only org admins can create organization-wide channels");
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
        `INSERT INTO conversations (id, type, org_id, scope, scope_id, kind, name, topic, auto_join, created_by, created_at, updated_at, voice)
         VALUES (?, 'CHANNEL', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      ).run(id, orgId, body.scope, scopeId, body.kind, body.name, body.topic ?? null, autoJoin ? 1 : 0, actor, t, t, body.voice ? 1 : 0);
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
    ctx.audit({ actorId: actor, orgId, action: body.voice ? "voice_room.create" : "channel.create", target: id, detail: `${body.kind} ${body.name}` });
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
      case "branch.archive":
      case "branch.restore":
        return true; // every branch name is listed for org members
      case "department.create": {
        const d = db.prepare("SELECT branch_id FROM departments WHERE id = ?").get(target) as { branch_id: string } | undefined;
        const b = d && authz.branch(d.branch_id);
        return !!b && authz.canAccessBranch(b, viewer);
      }
      case "team.create":
      case "team.archive":
      case "team.restore": {
        const t = authz.team(target);
        return !!t && (authz.canAccessTeam(t, viewer) || (!!t.branch_id && !!authz.branchRole(t.branch_id, viewer)));
      }
      case "channel.create":
      case "channel.access": {
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
    const isAdmin = authz.can(orgId, actor, "audit.view");
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
