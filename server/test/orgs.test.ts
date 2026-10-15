import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { GriipClient } from "./support/griipClient.js";
import { expectStatus, type Harness, startServer } from "./support/harness.js";

let h: Harness;
let owner: GriipClient, admin: GriipClient, bdManager: GriipClient, bdMember: GriipClient, intlMember: GriipClient, lead: GriipClient, guest: GriipClient, outsider: GriipClient;
let orgId: string, bd: string, intl: string, opsTeam: string, supportTeam: string, intlTeam: string, crossTeam: string;

async function invite(by: GriipClient, body: object) {
  return (await by.req("POST", `/v1/orgs/${orgId}/invites`, body)).invite.code as string;
}
async function join(c: GriipClient, code: string) {
  return c.req("POST", "/v1/invites/accept", { code });
}

beforeAll(async () => {
  h = await startServer();
  owner = await h.client("owner");
  admin = await h.client("admin");
  bdManager = await h.client("bdmanager");
  bdMember = await h.client("bdmember");
  intlMember = await h.client("intlmember");
  lead = await h.client("lead");
  guest = await h.client("guest");
  outsider = await h.client("outsider");
});
afterAll(async () => h.close());

describe("organization hierarchy", () => {
  it("creates org, branches, departments, teams", async () => {
    const o = await owner.req("POST", "/v1/orgs", { name: "GriipBuddy" });
    orgId = o.org.id;
    expect(o.org.myRole).toBe("OWNER");
    bd = (await owner.req("POST", `/v1/orgs/${orgId}/branches`, { name: "Bangladesh", location: "Dhaka" })).branch.id;
    intl = (await owner.req("POST", `/v1/orgs/${orgId}/branches`, { name: "International" })).branch.id;
    const dept = (await owner.req("POST", `/v1/branches/${bd}/departments`, { name: "Operations" })).department.id;
    opsTeam = (await owner.req("POST", `/v1/orgs/${orgId}/teams`, { name: "Operations", departmentId: dept })).team.id;
    supportTeam = (await owner.req("POST", `/v1/orgs/${orgId}/teams`, { name: "Customer Support", branchId: bd })).team.id;
    intlTeam = (await owner.req("POST", `/v1/orgs/${orgId}/teams`, { name: "Marketing", branchId: intl })).team.id;
    crossTeam = (await owner.req("POST", `/v1/orgs/${orgId}/teams`, { name: "Product & Development" })).team.id;
    const full = await owner.req("GET", `/v1/orgs/${orgId}`);
    expect(full.branches).toHaveLength(2);
    expect(full.teams.map((t: any) => t.name).sort()).toEqual(["Customer Support", "Marketing", "Operations", "Product & Development"]);
    expect(full.teams.find((t: any) => t.id === crossTeam).crossBranch).toBe(true);
  });

  it("invites members with scoped roles; invites can be previewed, used up and revoked", async () => {
    await join(admin, await invite(owner, { role: "ADMIN" }));
    await join(bdManager, await invite(owner, { role: "MEMBER", branchId: bd }));
    await owner.req("PUT", `/v1/branches/${bd}/members/${bdManager.userId}`, { role: "MANAGER" });
    const bdCode = await invite(bdManager, { role: "MEMBER", teamId: supportTeam, maxUses: 2 });
    const preview = await bdMember.req("POST", "/v1/invites/preview", { code: bdCode });
    expect(preview.org.name).toBe("GriipBuddy");
    expect(preview.team.name).toBe("Customer Support");
    await join(bdMember, bdCode);
    await join(lead, bdCode);
    await expectStatus(join(outsider, bdCode), 404); // used up (maxUses 2)
    await join(intlMember, await invite(owner, { role: "MEMBER", teamId: intlTeam }));
    await join(guest, await invite(admin, { role: "GUEST" }));

    const revoked = (await admin.req("POST", `/v1/orgs/${orgId}/invites`, { role: "MEMBER" })).invite;
    await admin.req("DELETE", `/v1/invites/${revoked.id}`);
    await expectStatus(join(outsider, revoked.code), 404);
    const roles = await owner.req("GET", `/v1/orgs/${orgId}/members`);
    const r = Object.fromEntries(roles.members.map((m: any) => [m.id, m.orgRole]));
    expect(r[admin.userId]).toBe("ADMIN");
    expect(r[guest.userId]).toBe("GUEST");
    expect(r[outsider.userId]).toBeUndefined();
  });

  it("enforces role permissions on the server (direct API calls)", async () => {
    // Members cannot create branches/teams/org channels
    await expectStatus(bdMember.req("POST", `/v1/orgs/${orgId}/branches`, { name: "Rogue" }), 403);
    await expectStatus(bdMember.req("POST", `/v1/orgs/${orgId}/teams`, { name: "Rogue", branchId: bd }), 403);
    await expectStatus(bdMember.req("POST", `/v1/orgs/${orgId}/channels`, { name: "rogue", scope: "ORG" }), 403);
    // Members cannot invite
    await expectStatus(bdMember.req("POST", `/v1/orgs/${orgId}/invites`, { role: "MEMBER" }), 403);
    // Admins cannot invite admins or touch owners
    await expectStatus(admin.req("POST", `/v1/orgs/${orgId}/invites`, { role: "ADMIN" }), 403);
    await expectStatus(admin.req("PATCH", `/v1/orgs/${orgId}/members/${owner.userId}`, { role: "MEMBER" }), 403);
    // Branch manager manages own branch only
    await bdManager.req("POST", `/v1/orgs/${orgId}/teams`, { name: "Community/Gaming", branchId: bd });
    await expectStatus(bdManager.req("POST", `/v1/orgs/${orgId}/teams`, { name: "x", branchId: intl }), 403);
    await expectStatus(bdManager.req("PATCH", `/v1/branches/${intl}`, { name: "Hijacked" }), 403);
    await expectStatus(bdManager.req("POST", `/v1/orgs/${orgId}/teams`, { name: "x" }), 403); // cross-branch
    // Branch managers can't appoint other managers
    await expectStatus(bdManager.req("PUT", `/v1/branches/${bd}/members/${bdMember.userId}`, { role: "MANAGER" }), 403);
    // Team lead: can add members to own team, cannot appoint leads
    await bdManager.req("PUT", `/v1/teams/${supportTeam}/members/${lead.userId}`, { role: "LEAD" });
    await lead.req("PUT", `/v1/teams/${supportTeam}/members/${bdMember.userId}`, { role: "MEMBER" });
    await expectStatus(lead.req("PUT", `/v1/teams/${supportTeam}/members/${bdMember.userId}`, { role: "LEAD" }), 403);
    await expectStatus(lead.req("PATCH", `/v1/teams/${opsTeam}`, { name: "nope" }), 403);
    // Outsiders see nothing
    await expectStatus(outsider.req("GET", `/v1/orgs/${orgId}`), 404);
    await expectStatus(outsider.req("GET", `/v1/orgs/${orgId}/members`), 404);
    // Guests cannot browse the directory
    await expectStatus(guest.req("GET", `/v1/orgs/${orgId}/members`), 403);
    // Last owner can't be demoted
    await expectStatus(owner.req("PATCH", `/v1/orgs/${orgId}/members/${owner.userId}`, { role: "ADMIN" }), 409);
  });

  it("controls cross-branch access explicitly", async () => {
    // Intl member can't see BD branch members or BD teams
    await expectStatus(intlMember.req("GET", `/v1/branches/${bd}/members`), 404);
    await expectStatus(intlMember.req("GET", `/v1/teams/${supportTeam}/members`), 404);
    const view = await intlMember.req("GET", `/v1/orgs/${orgId}`);
    expect(view.teams.map((t: any) => t.id)).not.toContain(supportTeam);
    expect(view.branches.find((b: any) => b.id === bd).canAccess).toBe(false);
    // Adding an Intl-only member to a BD team is refused until they're added to the branch
    await expectStatus(owner.req("PUT", `/v1/teams/${supportTeam}/members/${intlMember.userId}`, {}), 409);
    // Cross-branch team accepts members from any branch
    await owner.req("PUT", `/v1/teams/${crossTeam}/members/${intlMember.userId}`, {});
    await owner.req("PUT", `/v1/teams/${crossTeam}/members/${bdMember.userId}`, {});
  });

  it("enforces channel visibility: public/private/announcement, branch scope", async () => {
    const general = (await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "general", scope: "ORG", kind: "PUBLIC", autoJoin: true })).channel;
    const announce = (await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "announcements", scope: "ORG", kind: "ANNOUNCEMENT" })).channel;
    const bdAnnounce = (await bdManager.req("POST", `/v1/orgs/${orgId}/channels`, { name: "bd-announcements", scope: "BRANCH", scopeId: bd, kind: "ANNOUNCEMENT" })).channel;
    const mgmt = (
      await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "management", scope: "ORG", kind: "PRIVATE", memberIds: [admin.userId, bdManager.userId] })
    ).channel;
    const supportChat = (await lead.req("POST", `/v1/orgs/${orgId}/channels`, { name: "support-chat", scope: "TEAM", scopeId: supportTeam })).channel;

    // auto-joined
    const bdConvs = (await bdMember.req("GET", "/v1/conversations")).conversations.map((c: any) => c.id);
    expect(bdConvs).toEqual(expect.arrayContaining([general.id, announce.id, bdAnnounce.id, supportChat.id]));
    expect(bdConvs).not.toContain(mgmt.id);
    const intlConvs = (await intlMember.req("GET", "/v1/conversations")).conversations.map((c: any) => c.id);
    expect(intlConvs).toContain(announce.id);
    expect(intlConvs).not.toContain(bdAnnounce.id);
    expect(intlConvs).not.toContain(supportChat.id);

    // private channel: not visible, not readable, not joinable, no keys, can't post
    const browse = (await bdMember.req("GET", `/v1/orgs/${orgId}/channels`)).channels.map((c: any) => c.id);
    expect(browse).not.toContain(mgmt.id);
    await expectStatus(bdMember.req("GET", `/v1/conversations/${mgmt.id}`), 404);
    await expectStatus(bdMember.req("GET", `/v1/conversations/${mgmt.id}/devices`), 404);
    await expectStatus(bdMember.req("POST", `/v1/conversations/${mgmt.id}/join`), 404);
    await expectStatus(bdMember.req("GET", `/v1/conversations/${mgmt.id}/members`), 404);
    // other branch: can't join BD branch channel
    await expectStatus(intlMember.req("POST", `/v1/conversations/${bdAnnounce.id}/join`), 404);
    await expectStatus(intlMember.req("GET", `/v1/conversations/${supportChat.id}/devices`), 404);
    // guest: no scope access, explicit add only
    const guestConvs = (await guest.req("GET", "/v1/conversations")).conversations;
    expect(guestConvs).toHaveLength(0);
    await expectStatus(guest.req("POST", `/v1/conversations/${general.id}/join`), 404);
    await owner.req("PUT", `/v1/conversations/${general.id}/members/${guest.userId}`, {});
    expect((await guest.req("GET", "/v1/conversations")).conversations.map((c: any) => c.id)).toEqual([general.id]);
    // members can't add people to private channels they don't manage
    await expectStatus(bdMember.req("PUT", `/v1/conversations/${mgmt.id}/members/${bdMember.userId}`, {}), 403);
    // channel archive by manager, not by member
    await expectStatus(bdMember.req("PATCH", `/v1/conversations/${supportChat.id}`, { archived: true }), 403);
    await lead.req("PATCH", `/v1/conversations/${supportChat.id}`, { topic: "Tickets & escalations" });
  });

  it("moves members between teams and revokes team channel access on removal", async () => {
    const community = (await owner.req("GET", `/v1/orgs/${orgId}`)).teams.find((t: any) => t.name === "Community/Gaming").id;
    const teamChan = (await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "support-private", scope: "TEAM", scopeId: supportTeam, kind: "PRIVATE", memberIds: [bdMember.userId] })).channel;
    expect((await bdMember.req("GET", `/v1/conversations/${teamChan.id}`)).conversation.joined).toBe(true);
    await expectStatus(lead.req("POST", `/v1/teams/${supportTeam}/members/${bdMember.userId}/move`, { toTeamId: community }), 403);
    await bdManager.req("POST", `/v1/teams/${supportTeam}/members/${bdMember.userId}/move`, { toTeamId: community });
    const members = (await bdManager.req("GET", `/v1/teams/${community}/members`)).members.map((m: any) => m.id);
    expect(members).toContain(bdMember.userId);
    // no longer in the support team → lost the private team channel
    await expectStatus(bdMember.req("GET", `/v1/conversations/${teamChan.id}`), 404);
  });

  it("removes a member from the org and revokes all channel access", async () => {
    const convsBefore = (await intlMember.req("GET", "/v1/conversations")).conversations.length;
    expect(convsBefore).toBeGreaterThan(0);
    await admin.req("DELETE", `/v1/orgs/${orgId}/members/${intlMember.userId}`);
    expect((await intlMember.req("GET", "/v1/conversations")).conversations.filter((c: any) => c.orgId === orgId)).toHaveLength(0);
    await expectStatus(intlMember.req("GET", `/v1/orgs/${orgId}`), 404);
  });

  it("shows org activity; private channel names hidden from members", async () => {
    const all = await owner.req("GET", `/v1/orgs/${orgId}/activity`);
    expect(all.activity.some((a: any) => a.detail?.startsWith("PRIVATE"))).toBe(true);
    const mine = await bdMember.req("GET", `/v1/orgs/${orgId}/activity`);
    expect(mine.activity.length).toBeGreaterThan(0);
    expect(mine.activity.some((a: any) => String(a.detail).startsWith("PRIVATE"))).toBe(false);
  });
});
