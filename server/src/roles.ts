import type { OrgRole } from "./authz.js";
import { BASE_ROLE_PERMISSIONS, type Permission } from "./permissions/catalog.js";
import { type Actor, canActOn } from "./permissions/rules.js";

/**
 * Who may change an organization member's base role or title, or remove them (docs/WORKSPACE_ADMINISTRATION.md,
 * docs/WORKSPACE_ROLES.md). Pure rules, so the route, the tests and the app's mirror (`WorkspaceRoles.kt`) can be
 * compared line by line.
 *
 * - Owners may give anyone any role, but an organization always keeps at least one owner.
 * - Admins, and anyone a custom role gives `members.roles`, switch members and guests only (Member ↔ Guest). They can't
 *   create admins or owners, can't touch admins, owners or anyone holding admin powers they lack, and admins may step
 *   down themselves (to Member or Guest).
 * - Everyone else can't change roles at all, not even their own.
 *
 * The actor is a base role (its permissions alone) or an [Actor] with the effective permissions of custom roles.
 */
export interface RoleRefusal {
  status: 403 | 409;
  message: string;
}

const LOW_ROLES: readonly OrgRole[] = ["MEMBER", "GUEST"];
const isLow = (r: OrgRole): boolean => LOW_ROLES.includes(r);

type ActorLike = OrgRole | Actor;
const asActor = (a: ActorLike): Actor => (typeof a === "string" ? { base: a, perms: BASE_ROLE_PERMISSIONS[a] } : a);
/** Without the target's permissions (the pure tables), a target holds what its base role does. */
const targetOf = (role: OrgRole, self: boolean, perms?: ReadonlySet<Permission>) => ({ base: role, self, perms: perms ?? BASE_ROLE_PERMISSIONS[role] });

export function orgRoleChangeRefusal(
  actorLike: ActorLike,
  targetRole: OrgRole,
  newRole: OrgRole,
  self: boolean,
  ownerCount: number,
  targetPerms?: ReadonlySet<Permission>,
): RoleRefusal | null {
  if (newRole === targetRole) return null;
  const actor = asActor(actorLike);
  if (actor.base === "OWNER") {
    if (targetRole === "OWNER" && ownerCount <= 1) return { status: 409, message: "An organization needs at least one owner" };
    return null;
  }
  if (self && actor.base === "ADMIN" && targetRole === "ADMIN" && isLow(newRole)) return null;
  if (actor.perms.has("members.roles")) {
    if (isLow(targetRole) && isLow(newRole) && canActOn(actor, targetOf(targetRole, self, targetPerms))) return null;
    return { status: 403, message: "Admins can only switch members between Member and Guest" };
  }
  return { status: 403, message: "Only owners and admins can change roles" };
}

/** Titles: your own, or anyone's for an owner, or members' and guests' with `members.titles` (admins). */
export function titleChangeAllowed(actorLike: ActorLike, targetRole: OrgRole, self: boolean, targetPerms?: ReadonlySet<Permission>): boolean {
  const actor = asActor(actorLike);
  if (self || actor.base === "OWNER") return true;
  return actor.perms.has("members.titles") && isLow(targetRole) && canActOn(actor, targetOf(targetRole, self, targetPerms));
}

/** Removing someone else: owners remove anyone (the last owner is protected separately); `members.remove` removes members and guests. */
export function removalAllowed(actorLike: ActorLike, targetRole: OrgRole, targetPerms?: ReadonlySet<Permission>): boolean {
  const actor = asActor(actorLike);
  if (actor.base === "OWNER") return true;
  return actor.perms.has("members.remove") && isLow(targetRole) && canActOn(actor, targetOf(targetRole, false, targetPerms));
}

/**
 * Banning someone: like removing them, with `members.ban` instead of `members.remove`; owners ban anyone but another
 * owner (an owner leaves by handing over ownership, never by a ban). [targetRole] null: not a member (a ban ahead of
 * time), which needs only the permission.
 */
export function banAllowed(actorLike: ActorLike, targetRole: OrgRole | null, targetPerms?: ReadonlySet<Permission>): boolean {
  const actor = asActor(actorLike);
  if (targetRole === "OWNER") return false;
  if (actor.base === "OWNER") return true;
  if (!actor.perms.has("members.ban")) return false;
  return targetRole === null || (isLow(targetRole) && canActOn(actor, targetOf(targetRole, false, targetPerms)));
}
