import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { type AppContext } from "../../app/context.js";
import { me, requireAuth } from "../auth/index.js";
import { parse } from "../../shared/validation/index.js";
import { isPermission, permissionDef } from "./permissions/catalog.js";
import { ApiError, badRequest, forbidden } from "../../shared/errors.js";
import { newId, now } from "../../shared/utilities/util.js";
import { reconcileAccess } from "./communityRoutes.js";
import { MAX_ROLES_PER_MEMBER } from "./rolesRoutes.js";

/**
 * Onboarding questions (batch B8b, matrix COM-07, Discord's Onboarding): a community asks new members a few questions;
 * each answer can give roles and open public channels. Roles that carry administrative permissions are never given
 * this way (anyone who can join could pick them), and only public channels can be opened.
 */
export const ONBOARDING_LIMITS = { prompts: 5, options: 10, title: 100, rolesPerOption: 5, channelsPerOption: 10 } as const;

export interface OnboardingOption {
  id: string;
  title: string;
  emoji?: string | null;
  roleIds: string[];
  channelIds: string[];
}
export interface OnboardingPrompt {
  id: string;
  title: string;
  single: boolean;
  required: boolean;
  options: OnboardingOption[];
}

const zId = z.string().min(1).max(64);
const zOption = z.object({
  id: z.string().max(32).optional(),
  title: z.string().trim().min(1).max(ONBOARDING_LIMITS.title),
  emoji: z.string().trim().max(16).nullish(),
  roleIds: z.array(zId).max(ONBOARDING_LIMITS.rolesPerOption).default([]),
  channelIds: z.array(zId).max(ONBOARDING_LIMITS.channelsPerOption).default([]),
});
const zPrompt = z.object({
  id: z.string().max(32).optional(),
  title: z.string().trim().min(1).max(ONBOARDING_LIMITS.title),
  single: z.boolean().default(false),
  required: z.boolean().default(false),
  options: z.array(zOption).min(1).max(ONBOARDING_LIMITS.options),
});

function stored(ctx: AppContext, orgId: string): { enabled: boolean; prompts: OnboardingPrompt[] } {
  const r = ctx.db.prepare("SELECT enabled, prompts FROM community_onboarding WHERE org_id = ?").get(orgId) as { enabled: number; prompts: string } | undefined;
  if (!r) return { enabled: false, prompts: [] };
  try {
    return { enabled: !!r.enabled, prompts: JSON.parse(r.prompts) as OnboardingPrompt[] };
  } catch {
    return { enabled: false, prompts: [] };
  }
}

/** A role onboarding may give: this community's, not archived, and without administrative permissions. */
function givableRole(ctx: AppContext, orgId: string, roleId: string): boolean {
  const r = ctx.db.prepare("SELECT permissions, archived_at FROM org_roles WHERE id = ? AND org_id = ?").get(roleId, orgId) as { permissions: string; archived_at: number | null } | undefined;
  if (!r || r.archived_at) return false;
  const perms = (JSON.parse(r.permissions) as string[]).filter(isPermission);
  return perms.every((p) => !permissionDef(p).admin && !permissionDef(p).ownerOnly);
}

/** A channel onboarding may open: a public text or voice channel of this community. */
function openableChannel(ctx: AppContext, orgId: string, channelId: string): boolean {
  const c = ctx.authz.conversation(channelId);
  return !!c && c.type === "CHANNEL" && c.org_id === orgId && c.kind === "PUBLIC" && !c.archived_at;
}

/** Whether [orgId] asks onboarding questions (enabled, with at least one prompt). */
export function onboardingOn(ctx: AppContext, orgId: string): boolean {
  const s = stored(ctx, orgId);
  return s.enabled && s.prompts.length > 0;
}

export default async function onboardingRoutes(app: FastifyInstance, ctx: AppContext) {
  const { db, authz } = ctx;
  const auth = { preHandler: requireAuth(ctx) };

  const myAnswer = (orgId: string, userId: string) =>
    (db.prepare("SELECT option_ids, answered_at FROM onboarding_answers WHERE org_id = ? AND user_id = ?").get(orgId, userId) as { option_ids: string; answered_at: number } | undefined) ?? null;

  app.get("/v1/orgs/:orgId/onboarding", auth, async (req) => {
    const { orgId } = parse(z.object({ orgId: zId }), req.params);
    const userId = me(req).userId;
    authz.requireOrgMember(orgId, userId);
    const s = stored(ctx, orgId);
    const mine = myAnswer(orgId, userId);
    return { enabled: s.enabled, prompts: s.prompts, answered: mine ? (JSON.parse(mine.option_ids) as string[]) : null, answeredAt: mine?.answered_at ?? null };
  });

  /** Sets the questions (people who can edit the community); every role and channel is checked now and again on answering. */
  app.put("/v1/orgs/:orgId/onboarding", auth, async (req) => {
    const { orgId } = parse(z.object({ orgId: zId }), req.params);
    const userId = me(req).userId;
    const body = parse(z.object({ enabled: z.boolean(), prompts: z.array(zPrompt).max(ONBOARDING_LIMITS.prompts) }).strict(), req.body);
    authz.requireOrgMember(orgId, userId);
    if (!authz.can(orgId, userId, "workspace.edit")) throw forbidden("Your role doesn't include changing onboarding");
    const prompts: OnboardingPrompt[] = body.prompts.map((p) => ({
      id: p.id && /^[A-Za-z0-9_-]{1,32}$/.test(p.id) ? p.id : newId("q"),
      title: p.title,
      single: p.single,
      required: p.required,
      options: p.options.map((o) => ({
        id: o.id && /^[A-Za-z0-9_-]{1,32}$/.test(o.id) ? o.id : newId("a"),
        title: o.title,
        emoji: o.emoji || null,
        roleIds: [...new Set(o.roleIds)],
        channelIds: [...new Set(o.channelIds)],
      })),
    }));
    for (const o of prompts.flatMap((p) => p.options)) {
      const badRole = o.roleIds.find((r) => !givableRole(ctx, orgId, r));
      if (badRole) throw new ApiError(400, "role_not_givable", "Onboarding can only give this community's roles without administrative permissions", { roleId: badRole });
      const badChannel = o.channelIds.find((c) => !openableChannel(ctx, orgId, c));
      if (badChannel) throw new ApiError(400, "channel_not_public", "Onboarding can only open this community's public channels", { channelId: badChannel });
    }
    if (body.enabled && prompts.length === 0) throw badRequest("Add a question before turning onboarding on");
    db.prepare(
      `INSERT INTO community_onboarding (org_id, enabled, prompts, updated_at) VALUES (?, ?, ?, ?)
       ON CONFLICT (org_id) DO UPDATE SET enabled = excluded.enabled, prompts = excluded.prompts, updated_at = excluded.updated_at`,
    ).run(orgId, body.enabled ? 1 : 0, JSON.stringify(prompts), now());
    ctx.audit({ actorId: userId, orgId, action: body.enabled ? "community.onboarding_on" : "community.onboarding_off", target: orgId });
    return { enabled: body.enabled, prompts };
  });

  /**
   * A member's answers: the chosen options' roles are given and their public channels opened. Answering again takes
   * back the roles onboarding gave before and the new answers no longer include.
   */
  app.post("/v1/orgs/:orgId/onboarding/answers", { preHandler: requireAuth(ctx), config: { rateLimit: { max: 10, timeWindow: "1 minute" } } }, async (req) => {
    const { orgId } = parse(z.object({ orgId: zId }), req.params);
    const userId = me(req).userId;
    const { optionIds } = parse(z.object({ optionIds: z.array(z.string().max(32)).max(ONBOARDING_LIMITS.prompts * ONBOARDING_LIMITS.options) }).strict(), req.body);
    const role = authz.requireOrgMember(orgId, userId);
    if (role === "GUEST") throw forbidden("Guests don't answer onboarding questions");
    const s = stored(ctx, orgId);
    if (!s.enabled || s.prompts.length === 0) throw new ApiError(409, "onboarding_off", "This community doesn't ask onboarding questions");
    const chosen = new Set(optionIds);
    for (const p of s.prompts) {
      const picked = p.options.filter((o) => chosen.has(o.id));
      if (p.required && picked.length === 0) throw new ApiError(400, "answer_required", `Answer "${p.title}"`, { promptId: p.id });
      if (p.single && picked.length > 1) throw new ApiError(400, "one_answer", `Pick one answer for "${p.title}"`, { promptId: p.id });
    }
    const options = s.prompts.flatMap((p) => p.options).filter((o) => chosen.has(o.id));
    // Checked again now: a role may have gained an administrative permission, a channel may have become private.
    const roles = [...new Set(options.flatMap((o) => o.roleIds))].filter((r) => givableRole(ctx, orgId, r));
    const channels = [...new Set(options.flatMap((o) => o.channelIds))].filter((c) => openableChannel(ctx, orgId, c));
    const before = myAnswer(orgId, userId);
    const previouslyGiven = before ? (JSON.parse((db.prepare("SELECT role_ids FROM onboarding_answers WHERE org_id = ? AND user_id = ?").get(orgId, userId) as { role_ids: string }).role_ids) as string[]) : [];
    const t = now();
    const given = db.transaction(() => {
      for (const r of previouslyGiven.filter((r) => !roles.includes(r))) {
        db.prepare("DELETE FROM org_role_members WHERE role_id = ? AND user_id = ? AND assigned_by = 'onboarding'").run(r, userId);
      }
      const out: string[] = [];
      for (const r of roles) {
        const held = (db.prepare("SELECT COUNT(*) n FROM org_role_members WHERE org_id = ? AND user_id = ?").get(orgId, userId) as { n: number }).n;
        const has = db.prepare("SELECT assigned_by FROM org_role_members WHERE role_id = ? AND user_id = ?").get(r, userId) as { assigned_by: string } | undefined;
        if (has) {
          if (has.assigned_by === "onboarding") out.push(r);
          continue;
        }
        if (held >= MAX_ROLES_PER_MEMBER) continue;
        db.prepare("INSERT INTO org_role_members (role_id, org_id, user_id, assigned_by, assigned_at) VALUES (?, ?, ?, 'onboarding', ?)").run(r, orgId, userId, t);
        out.push(r);
      }
      const join = db.prepare("INSERT OR IGNORE INTO conversation_members (conversation_id, user_id, role, joined_at) VALUES (?, ?, 'MEMBER', ?)");
      for (const c of channels) {
        const conv = authz.conversation(c);
        if (conv && authz.canViewChannel(conv, userId)) join.run(c, userId, t);
      }
      db.prepare(
        `INSERT INTO onboarding_answers (org_id, user_id, option_ids, role_ids, answered_at) VALUES (?, ?, ?, ?, ?)
         ON CONFLICT (org_id, user_id) DO UPDATE SET option_ids = excluded.option_ids, role_ids = excluded.role_ids, answered_at = excluded.answered_at`,
      ).run(orgId, userId, JSON.stringify([...chosen]), JSON.stringify(out), t);
      return out;
    })();
    ctx.audit({ actorId: userId, orgId, action: "community.onboarding_answered", target: userId, detail: `${given.length} roles` });
    await reconcileAccess(ctx, orgId, [userId]);
    return { roleIds: given, channelIds: channels, permissions: [...authz.permissions(orgId, userId)].sort() };
  });
}
