import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { GriipClient } from "./support/griipClient.js";
import { expectStatus, type Harness, startServer } from "./support/harness.js";

/** Phase 16: departments can be renamed, deleted and used to regroup teams (D-15), only by branch managers, never in archived branches (D-14). */
let h: Harness;
let owner: GriipClient, manager: GriipClient, member: GriipClient;
let orgId: string, dhaka: string, intl: string, ops: string, finance: string, intlDept: string, team: string, crossTeam: string;

const detail = () => owner.req("GET", `/v1/orgs/${orgId}`);

beforeAll(async () => {
  h = await startServer();
  owner = await h.client("owner");
  manager = await h.client("manager");
  member = await h.client("member");
  orgId = (await owner.req("POST", "/v1/orgs", { name: "Acme" })).org.id;
  dhaka = (await owner.req("POST", `/v1/orgs/${orgId}/branches`, { name: "Dhaka" })).branch.id;
  intl = (await owner.req("POST", `/v1/orgs/${orgId}/branches`, { name: "International" })).branch.id;
  ops = (await owner.req("POST", `/v1/branches/${dhaka}/departments`, { name: "Operations" })).department.id;
  finance = (await owner.req("POST", `/v1/branches/${dhaka}/departments`, { name: "Finance" })).department.id;
  intlDept = (await owner.req("POST", `/v1/branches/${intl}/departments`, { name: "Sales" })).department.id;
  team = (await owner.req("POST", `/v1/orgs/${orgId}/teams`, { name: "Payroll", branchId: dhaka, departmentId: ops })).team.id;
  crossTeam = (await owner.req("POST", `/v1/orgs/${orgId}/teams`, { name: "Product" })).team.id;
  for (const [c, role] of [[manager, "MANAGER"], [member, "MEMBER"]] as const) {
    const code = (await owner.req("POST", `/v1/orgs/${orgId}/invites`, { role: "MEMBER", branchId: dhaka })).invite.code;
    await c.req("POST", "/v1/invites/accept", { code });
    await owner.req("PUT", `/v1/branches/${dhaka}/members/${c.userId}`, { role });
  }
});
afterAll(async () => h.close());

describe("departments", () => {
  it("a branch manager renames one; a member can't", async () => {
    await manager.req("PATCH", `/v1/departments/${ops}`, { name: "Operations & Logistics", description: "Warehouses" });
    const d = (await detail()).departments.find((x: any) => x.id === ops);
    expect(d.name).toBe("Operations & Logistics");
    expect(d.description).toBe("Warehouses");
    await expectStatus(member.req("PATCH", `/v1/departments/${ops}`, { name: "Mine now" }), 403);
    await expectStatus(manager.req("PATCH", `/v1/departments/${intlDept}`, { name: "Not my branch" }), 403);
  });

  it("moves a team between its branch's departments, and nowhere else", async () => {
    await manager.req("PATCH", `/v1/teams/${team}`, { departmentId: finance });
    expect((await detail()).teams.find((t: any) => t.id === team).departmentId).toBe(finance);
    await expectStatus(owner.req("PATCH", `/v1/teams/${team}`, { departmentId: intlDept }), 400); // another branch's department
    await expectStatus(owner.req("PATCH", `/v1/teams/${crossTeam}`, { departmentId: ops }), 400); // cross-branch teams have none
    await owner.req("PATCH", `/v1/teams/${team}`, { departmentId: null });
    expect((await detail()).teams.find((t: any) => t.id === team).departmentId ?? null).toBeNull();
  });

  it("deleting one leaves its teams in the branch, ungrouped", async () => {
    await manager.req("PATCH", `/v1/teams/${team}`, { departmentId: finance });
    await expectStatus(member.req("DELETE", `/v1/departments/${finance}`), 403);
    await manager.req("DELETE", `/v1/departments/${finance}`);
    const d = await detail();
    expect(d.departments.map((x: any) => x.id)).not.toContain(finance);
    const t = d.teams.find((x: any) => x.id === team);
    expect(t.departmentId ?? null).toBeNull();
    expect(t.branchId).toBe(dhaka);
    await expectStatus(manager.req("DELETE", `/v1/departments/${finance}`), 404);
  });

  it("nothing in an archived branch can be edited until it is unarchived", async () => {
    await owner.req("PATCH", `/v1/branches/${dhaka}`, { archived: true });
    await expectStatus(owner.req("PATCH", `/v1/departments/${ops}`, { name: "x" }), 409);
    await expectStatus(owner.req("DELETE", `/v1/departments/${ops}`), 409);
    await expectStatus(owner.req("PATCH", `/v1/teams/${team}`, { name: "x" }), 409);
    await owner.req("PATCH", `/v1/branches/${dhaka}`, { archived: false });
    await owner.req("PATCH", `/v1/departments/${ops}`, { name: "Operations" });
  });

  it("department changes are audited and visible to admins only", async () => {
    const actions = (await owner.req("GET", `/v1/orgs/${orgId}/activity`)).activity.map((a: any) => a.action);
    expect(actions).toEqual(expect.arrayContaining(["department.update", "department.delete"]));
    const seen = (await member.req("GET", `/v1/orgs/${orgId}/activity`)).activity.map((a: any) => a.action);
    expect(seen).not.toContain("department.update");
    expect(seen).not.toContain("department.delete");
  });
});
