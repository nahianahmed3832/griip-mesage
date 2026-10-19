import type { OrgRole } from "./authz.js";

/**
 * Who may change an organization member's role or title (docs/WORKSPACE_ADMINISTRATION.md). Pure rules, so the route,
 * the tests and the app's mirror (`WorkspaceRoles.kt`) can be compared line by line.
 *
 * - Owners may give anyone any role, but an organization always keeps at least one owner.
 * - Admins manage members and guests only (Member ↔ Guest). They can't create admins or owners, can't touch other
 *   admins or owners, and can step down themselves (to Member or Guest).
 * - Members and guests can't change roles at all, not even their own.
 */
export interface RoleRefusal {
  status: 403 | 409;
  message: string;
}

const LOW_ROLES: readonly OrgRole[] = ["MEMBER", "GUEST"];
const isLow = (r: OrgRole): boolean => LOW_ROLES.includes(r);

export function orgRoleChangeRefusal(
  actorRole: OrgRole,
  targetRole: OrgRole,
  newRole: OrgRole,
  self: boolean,
  ownerCount: number,
): RoleRefusal | null {
  if (newRole === targetRole) return null;
  if (actorRole === "OWNER") {
    if (targetRole === "OWNER" && ownerCount <= 1) return { status: 409, message: "An organization needs at least one owner" };
    return null;
  }
  if (actorRole === "ADMIN") {
    if (self && targetRole === "ADMIN" && isLow(newRole)) return null;
    if (isLow(targetRole) && isLow(newRole)) return null;
    return { status: 403, message: "Admins can only switch members between Member and Guest" };
  }
  return { status: 403, message: "Only owners and admins can change roles" };
}

/** Titles: your own, or anyone's for an owner, or members' and guests' for an admin. */
export function titleChangeAllowed(actorRole: OrgRole, targetRole: OrgRole, self: boolean): boolean {
  if (self || actorRole === "OWNER") return true;
  return actorRole === "ADMIN" && isLow(targetRole);
}

/** Removing someone else: owners remove anyone (the last owner is protected separately); admins remove members and guests. */
export function removalAllowed(actorRole: OrgRole, targetRole: OrgRole): boolean {
  if (actorRole === "OWNER") return true;
  return actorRole === "ADMIN" && isLow(targetRole);
}
