import { describe, expect, it } from "vitest";
import type { OrgRole } from "../src/modules/organizations/authz.js";
import { orgRoleChangeRefusal, removalAllowed, titleChangeAllowed } from "../src/modules/organizations/roleRules.js";

/**
 * The full role-change table (docs/WORKSPACE_ADMINISTRATION.md). The app's `WorkspaceRolesTest` checks the same table
 * against its mirror, so the two can't drift silently. Rows: actor → target → roles the actor may give, for someone
 * else and, separately, for themselves. Two owners exist unless stated.
 */
const ROLES: OrgRole[] = ["OWNER", "ADMIN", "MEMBER", "GUEST"];
const OTHERS: Record<OrgRole, Record<OrgRole, OrgRole[]>> = {
  OWNER: { OWNER: ["ADMIN", "MEMBER", "GUEST"], ADMIN: ["OWNER", "MEMBER", "GUEST"], MEMBER: ["OWNER", "ADMIN", "GUEST"], GUEST: ["OWNER", "ADMIN", "MEMBER"] },
  ADMIN: { OWNER: [], ADMIN: [], MEMBER: ["GUEST"], GUEST: ["MEMBER"] },
  MEMBER: { OWNER: [], ADMIN: [], MEMBER: [], GUEST: [] },
  GUEST: { OWNER: [], ADMIN: [], MEMBER: [], GUEST: [] },
};
const SELF: Record<OrgRole, OrgRole[]> = { OWNER: ["ADMIN", "MEMBER", "GUEST"], ADMIN: ["MEMBER", "GUEST"], MEMBER: [], GUEST: [] };

const allowed = (actor: OrgRole, target: OrgRole, self: boolean, owners = 2) =>
  ROLES.filter((r) => r !== target && orgRoleChangeRefusal(actor, target, r, self, owners) === null);

describe("organization role rules", () => {
  it("matches the table for changing someone else's role", () => {
    for (const actor of ROLES) for (const target of ROLES) expect([actor, target, allowed(actor, target, false)]).toEqual([actor, target, OTHERS[actor][target]]);
  });

  it("matches the table for changing your own role", () => {
    for (const actor of ROLES) expect([actor, allowed(actor, actor, true)]).toEqual([actor, SELF[actor]]);
  });

  it("protects the last owner with a conflict, not a refusal", () => {
    expect(orgRoleChangeRefusal("OWNER", "OWNER", "ADMIN", true, 1)).toEqual({ status: 409, message: "An organization needs at least one owner" });
    expect(orgRoleChangeRefusal("OWNER", "OWNER", "ADMIN", true, 2)).toBeNull();
    expect(orgRoleChangeRefusal("ADMIN", "MEMBER", "ADMIN", false, 1)?.status).toBe(403);
    expect(orgRoleChangeRefusal("MEMBER", "MEMBER", "MEMBER", true, 1)).toBeNull(); // no change is never refused
  });

  it("limits titles and removal", () => {
    expect(ROLES.filter((t) => titleChangeAllowed("ADMIN", t, false))).toEqual(["MEMBER", "GUEST"]);
    expect(ROLES.filter((t) => titleChangeAllowed("OWNER", t, false))).toEqual(ROLES);
    expect(ROLES.filter((t) => titleChangeAllowed("MEMBER", t, false))).toEqual([]);
    expect(titleChangeAllowed("GUEST", "GUEST", true)).toBe(true);
    expect(ROLES.filter((t) => removalAllowed("ADMIN", t))).toEqual(["MEMBER", "GUEST"]);
    expect(ROLES.filter((t) => removalAllowed("OWNER", t))).toEqual(ROLES);
    expect(ROLES.filter((t) => removalAllowed("MEMBER", t))).toEqual([]);
  });
});
