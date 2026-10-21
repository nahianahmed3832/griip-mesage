import crypto from "node:crypto";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { GriipClient } from "./support/griipClient.js";
import { expectStatus, type Harness, startServer } from "./support/harness.js";

/**
 * Communities (docs/COMMUNITY_REBUILD_2026-10-21.md): structure, invitations, roles, categories, channel permission
 * overwrites, forums, discovery, bans. Every rule is exercised through the API, the way an app (or someone bypassing
 * one) would call it.
 */
let h: Harness;
let owner: GriipClient, admin: GriipClient, mod: GriipClient, staff: GriipClient, member: GriipClient, guest: GriipClient, outsider: GriipClient, newcomer: GriipClient;
let orgId: string, staffRole: string, modRole: string;

async function invite(by: GriipClient, body: object, org = orgId) {
  return (await by.req("POST", `/v1/orgs/${org}/invites`, body)).invite.code as string;
}
async function join(c: GriipClient, code: string) {
  return c.req("POST", "/v1/invites/accept", { code });
}
const ids = async (c: GriipClient) => ((await c.req("GET", "/v1/conversations")).conversations as { id: string }[]).map((x) => x.id);

beforeAll(async () => {
  h = await startServer();
  owner = await h.client("cowner");
  admin = await h.client("cadmin");
  mod = await h.client("cmod");
  staff = await h.client("cstaff");
  member = await h.client("cmember");
  guest = await h.client("cguest");
  outsider = await h.client("coutsider");
  newcomer = await h.client("cnewcomer");
});
afterAll(async () => h.close());

describe("communities", () => {
  it("start with categorised welcome channels, and keep the old structure fields empty for older apps", async () => {
    const o = await owner.req("POST", "/v1/orgs", { name: "Night Sky Club", description: "Amateur astronomy", rules: "Be kind. No spam." });
    orgId = o.org.id;
    expect(o.org).toMatchObject({ myRole: "OWNER", kind: "community", visibility: "private", joinPolicy: "invite", rules: "Be kind. No spam." });
    const full = await owner.req("GET", `/v1/orgs/${orgId}`);
    expect(full.branches).toEqual([]);
    expect(full.teams).toEqual([]);
    expect(full.departments).toEqual([]);
    expect(full.categories.map((c: any) => c.name)).toEqual(["Start here", "Talk"]);
    const channels = (await owner.req("GET", `/v1/orgs/${orgId}/channels`)).channels;
    expect(channels.map((c: any) => c.name).sort()).toEqual(["announcements", "general", "introductions", "lounge", "welcome"]);
    const general = channels.find((c: any) => c.name === "general");
    expect(general).toMatchObject({ categoryName: "Talk", format: "text", scope: "TEAM", scopeName: "Talk" });
    expect(channels.find((c: any) => c.name === "lounge").voice).toBe(true);
  });

  it("invite members, admins and guests; an invitation can hand out a role; branch and team invitations are refused", async () => {
    staffRole = (await owner.req("POST", `/v1/orgs/${orgId}/roles`, { name: "Staff", permissions: [], denies: [] })).role.id;
    modRole = (await owner.req("POST", `/v1/orgs/${orgId}/roles`, { name: "Moderator", permissions: ["members.remove", "members.ban", "channels.manage"], denies: [] })).role.id;
    await join(admin, await invite(owner, { role: "ADMIN" }));
    const staffCode = await invite(owner, { role: "MEMBER", roleId: staffRole, maxUses: 2 });
    const preview = await staff.req("POST", "/v1/invites/preview", { code: staffCode });
    expect(preview.org).toMatchObject({ name: "Night Sky Club", rules: "Be kind. No spam." });
    expect(preview.grantRole.name).toBe("Staff");
    await join(staff, staffCode);
    await join(mod, await invite(owner, { role: "MEMBER" }));
    await owner.req("PUT", `/v1/roles/${modRole}/members/${mod.userId}`);
    await join(member, await invite(admin, { role: "MEMBER" }));
    await join(guest, await invite(admin, { role: "GUEST" }));
    await expectStatus(owner.req("POST", `/v1/orgs/${orgId}/invites`, { role: "MEMBER", teamId: "t_old" }), 400);
    await expectStatus(owner.req("POST", `/v1/orgs/${orgId}/invites`, { role: "GUEST", roleId: staffRole }), 400);
    const roles = (await owner.req("GET", `/v1/roles/${staffRole}/members`)).members.map((m: any) => m.id);
    expect(roles).toEqual([staff.userId]);
    const revoked = (await admin.req("POST", `/v1/orgs/${orgId}/invites`, { role: "MEMBER" })).invite;
    await admin.req("DELETE", `/v1/invites/${revoked.id}`);
    await expectStatus(join(outsider, revoked.code), 404);
  });

  it("enforce every power on the server", async () => {
    await expectStatus(member.req("POST", `/v1/orgs/${orgId}/channels`, { name: "rogue" }), 403);
    await expectStatus(member.req("POST", `/v1/orgs/${orgId}/categories`, { name: "Rogue" }), 403);
    await expectStatus(member.req("POST", `/v1/orgs/${orgId}/invites`, { role: "MEMBER" }), 403);
    await expectStatus(member.req("PUT", `/v1/orgs/${orgId}/layout`, { categories: [] }), 403);
    await expectStatus(admin.req("POST", `/v1/orgs/${orgId}/invites`, { role: "ADMIN" }), 403);
    await expectStatus(admin.req("PATCH", `/v1/orgs/${orgId}/members/${owner.userId}`, { role: "MEMBER" }), 403);
    await expectStatus(outsider.req("GET", `/v1/orgs/${orgId}`), 404);
    await expectStatus(outsider.req("GET", `/v1/orgs/${orgId}/categories`), 404);
    await expectStatus(guest.req("GET", `/v1/orgs/${orgId}/members`), 403);
    await expectStatus(owner.req("PATCH", `/v1/orgs/${orgId}/members/${owner.userId}`, { role: "ADMIN" }), 409);
    // Branches and teams are gone; channel creation in their old scopes is refused.
    await expectStatus(owner.req("POST", `/v1/orgs/${orgId}/branches`, { name: "Dhaka" }), 404);
    await expectStatus(owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "x", scope: "TEAM", scopeId: "t1" }), 400);
    // Only owners list a community publicly or open it.
    await expectStatus(admin.req("PATCH", `/v1/orgs/${orgId}`, { visibility: "public" }), 403);
  });

  it("gate a category to a role with overwrites: the same people see it, assigning the role adds them, removing it takes them out", async () => {
    const room = (await owner.req("POST", `/v1/orgs/${orgId}/categories`, { name: "Staff room" })).category;
    await owner.req("PUT", `/v1/categories/${room.id}/overwrites`, { subject: "everyone", deny: ["channels.view"] });
    await owner.req("PUT", `/v1/categories/${room.id}/overwrites`, { subject: staffRole, allow: ["channels.view"] });
    const desk = (await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "front-desk", categoryId: room.id })).channel;
    const secret = (await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "payroll", categoryId: room.id, kind: "PRIVATE" })).channel;
    expect(await ids(staff)).toContain(desk.id);
    expect(await ids(member)).not.toContain(desk.id);
    await expectStatus(member.req("GET", `/v1/conversations/${desk.id}`), 404);
    await expectStatus(member.req("GET", `/v1/conversations/${desk.id}/devices`), 404);
    await expectStatus(member.req("POST", `/v1/conversations/${desk.id}/join`), 404);
    expect((await member.req("GET", `/v1/orgs/${orgId}`)).categories.map((c: any) => c.id)).not.toContain(room.id);
    // A private channel stays explicit: the role's allow doesn't add anyone to it.
    expect(await ids(staff)).not.toContain(secret.id);
    // Assigning the role adds the person; taking it away removes them.
    await owner.req("PUT", `/v1/roles/${staffRole}/members/${member.userId}`);
    expect(await ids(member)).toContain(desk.id);
    await owner.req("DELETE", `/v1/roles/${staffRole}/members/${member.userId}`);
    expect(await ids(member)).not.toContain(desk.id);
    // Adding someone who can't see it by hand is refused; guests may be added explicitly.
    await expectStatus(owner.req("PUT", `/v1/conversations/${desk.id}/members/${member.userId}`), 409);
    await owner.req("PUT", `/v1/conversations/${desk.id}/members/${guest.userId}`);
    expect(await ids(guest)).toEqual([desk.id]);
  });

  it("let channel overwrites restrict posting and sharing files; a role's restriction still wins over a channel allow", async () => {
    const quiet = (await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "quiet" })).channel;
    await owner.req("PUT", `/v1/conversations/${quiet.id}/overwrites`, { subject: "everyone", deny: ["messages.post", "messages.attach"] });
    await owner.req("PUT", `/v1/conversations/${quiet.id}/overwrites`, { subject: staffRole, allow: ["messages.post"] });
    expect((await member.req("GET", `/v1/conversations/${quiet.id}`)).conversation.canPost).toBe(false);
    expect((await staff.req("GET", `/v1/conversations/${quiet.id}`)).conversation.canPost).toBe(true);
    const err = await expectStatus(member.send(quiet.id, { type: "text", text: "hello?" }), 403);
    expect(err.error.code).toBe("forbidden");
    await staff.send(quiet.id, { type: "text", text: "staff only" });
    // Staff may post but not share files here.
    await expectStatus(staff.req("POST", `/v1/attachments?conversationId=${quiet.id}`, undefined, crypto.randomBytes(64)), 403);
    // A read-only role is community-wide: no channel allow lifts it.
    const readOnly = (await owner.req("POST", `/v1/orgs/${orgId}/roles`, { name: "Muted", permissions: [], denies: ["messages.post"] })).role.id;
    await owner.req("PUT", `/v1/roles/${readOnly}/members/${staff.userId}`);
    expect((await staff.req("GET", `/v1/conversations/${quiet.id}`)).conversation.canPost).toBe(false);
    await owner.req("DELETE", `/v1/roles/${readOnly}/members/${staff.userId}`);
  });

  it("refuse overwrites that hand out powers the writer doesn't hold", async () => {
    const den = (await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "den" })).channel;
    // A member given channels.manage on one channel only.
    await owner.req("PUT", `/v1/conversations/${den.id}/overwrites`, { subject: `user:${member.userId}`, allow: ["channels.manage"] });
    const mine = await member.req("GET", `/v1/conversations/${den.id}/overwrites`);
    expect(mine.canManage).toBe(true);
    await member.req("PUT", `/v1/conversations/${den.id}/overwrites`, { subject: "everyone", deny: ["messages.attach"] });
    const err = await expectStatus(member.req("PUT", `/v1/conversations/${den.id}/overwrites`, { subject: "everyone", allow: ["messages.announce"] }), 403);
    expect(err.error.code).toBe("beyond_your_powers");
    await expectStatus(member.req("PUT", `/v1/conversations/${den.id}/overwrites`, { subject: "everyone", allow: ["messages.post"], deny: ["messages.post"] }), 400);
    await expectStatus(member.req("PUT", `/v1/conversations/${den.id}/overwrites`, { subject: "role_nope", allow: ["messages.post"] }), 400);
    await expectStatus(guest.req("GET", `/v1/conversations/${den.id}/overwrites`), 404);
    // Elsewhere they manage nothing.
    const general = (await owner.req("GET", `/v1/orgs/${orgId}/channels`)).channels.find((c: any) => c.name === "general");
    await expectStatus(member.req("PUT", `/v1/conversations/${general.id}/overwrites`, { subject: "everyone", deny: ["messages.post"] }), 403);
  });

  it("keep a category whose permissions decide visibility until its channels move out; moving a channel takes the new category's rules", async () => {
    const full = await owner.req("GET", `/v1/orgs/${orgId}`);
    const room = full.categories.find((c: any) => c.name === "Staff room");
    const talk = full.categories.find((c: any) => c.name === "Talk");
    await expectStatus(owner.req("DELETE", `/v1/categories/${room.id}`), 409);
    const channels = (await owner.req("GET", `/v1/orgs/${orgId}/channels`)).channels;
    const general = channels.find((c: any) => c.name === "general");
    // general moves into the staff room: members without the role lose it.
    expect(await ids(member)).toContain(general.id);
    const moved = await owner.req("PUT", `/v1/orgs/${orgId}/layout`, { channels: [{ id: general.id, categoryId: room.id, position: 9 }] });
    expect(moved.removed).toBeGreaterThan(0);
    expect(await ids(member)).not.toContain(general.id);
    await owner.req("PUT", `/v1/orgs/${orgId}/layout`, { categories: [talk.id, room.id], channels: [{ id: general.id, categoryId: talk.id, position: 0 }] });
    expect(await ids(member)).toContain(general.id);
    expect((await owner.req("GET", `/v1/orgs/${orgId}`)).categories.slice(0, 2).map((c: any) => c.name)).toEqual(["Talk", "Staff room"]);
    // An empty category without rules goes; its channels (none) stay.
    const empty = (await owner.req("POST", `/v1/orgs/${orgId}/categories`, { name: "Spare" })).category;
    await owner.req("DELETE", `/v1/categories/${empty.id}`);
    await expectStatus(owner.req("PATCH", `/v1/categories/${empty.id}`, { name: "x" }), 404);
  });

  it("have forum channels; voice channels can't be forums", async () => {
    const forum = (await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "questions", format: "forum" })).channel;
    expect(forum.format).toBe("forum");
    await expectStatus(owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "x", voice: true, format: "forum" }), 400);
    const patched = await owner.req("PATCH", `/v1/conversations/${forum.id}`, { format: "text" });
    expect(patched.conversation.format).toBe("text");
    await expectStatus(member.req("PATCH", `/v1/conversations/${forum.id}`, { format: "forum" }), 403);
  });

  it("limit categories", async () => {
    const o = await owner.req("POST", "/v1/orgs", { name: "Limits" });
    const existing = (await owner.req("GET", `/v1/orgs/${o.org.id}`)).categories.length;
    for (let i = existing; i < 50; i++) await owner.req("POST", `/v1/orgs/${o.org.id}/categories`, { name: `c${i}` });
    await expectStatus(owner.req("POST", `/v1/orgs/${o.org.id}/categories`, { name: "one too many" }), 409);
  });

  it("hide private channel names in the activity log from members", async () => {
    const all = await owner.req("GET", `/v1/orgs/${orgId}/activity`);
    expect(all.activity.some((a: any) => String(a.detail).includes("payroll"))).toBe(true);
    const mine = await member.req("GET", `/v1/orgs/${orgId}/activity`);
    expect(mine.activity.some((a: any) => String(a.detail).includes("payroll"))).toBe(false);
  });
});

describe("discovery and joining", () => {
  let pub: string;
  it("list only public communities; open ones can be joined, invite-only ones can't", async () => {
    expect((await newcomer.req("GET", "/v1/communities/discover?q=night")).communities).toEqual([]);
    await owner.req("PATCH", `/v1/orgs/${orgId}`, { visibility: "public", joinPolicy: "open" });
    const found = (await newcomer.req("GET", "/v1/communities/discover?q=night")).communities;
    expect(found).toHaveLength(1);
    expect(found[0]).toMatchObject({ id: orgId, joinPolicy: "open", joined: false });
    const preview = await newcomer.req("GET", `/v1/communities/${orgId}/preview`);
    expect(preview.community.rules).toBe("Be kind. No spam.");
    const names = preview.channels.map((c: any) => c.name);
    expect(names).toContain("general");
    expect(names).not.toContain("front-desk");
    expect(names).not.toContain("payroll");
    const joined = await newcomer.req("POST", `/v1/communities/${orgId}/join`);
    expect(joined.org.myRole).toBe("MEMBER");
    const general = (await owner.req("GET", `/v1/orgs/${orgId}/channels`)).channels.find((c: any) => c.name === "general");
    expect(await ids(newcomer)).toContain(general.id);
    expect((await newcomer.req("POST", `/v1/communities/${orgId}/join`)).alreadyMember).toBe(true);

    pub = (await owner.req("POST", "/v1/orgs", { name: "Invite Only Society", visibility: "public" })).org.id;
    await expectStatus(newcomer.req("POST", `/v1/communities/${pub}/join`), 403);
    const priv = (await owner.req("POST", "/v1/orgs", { name: "Hidden" })).org.id;
    await expectStatus(newcomer.req("GET", `/v1/communities/${priv}/preview`), 404);
    await expectStatus(newcomer.req("POST", `/v1/communities/${priv}/join`), 404);
  });

  it("bans: a moderator removes someone and keeps them out of invites and open joining until lifted", async () => {
    await expectStatus(member.req("POST", `/v1/orgs/${orgId}/bans`, { userId: newcomer.userId }), 403);
    await expectStatus(mod.req("POST", `/v1/orgs/${orgId}/bans`, { userId: owner.userId }), 403);
    await expectStatus(mod.req("POST", `/v1/orgs/${orgId}/bans`, { userId: admin.userId }), 403);
    await mod.req("POST", `/v1/orgs/${orgId}/bans`, { userId: newcomer.userId, reason: "spam" });
    await expectStatus(newcomer.req("GET", `/v1/orgs/${orgId}`), 404);
    expect((await newcomer.req("GET", "/v1/conversations")).conversations.filter((c: any) => c.orgId === orgId)).toEqual([]);
    const bans = (await mod.req("GET", `/v1/orgs/${orgId}/bans`)).bans;
    expect(bans).toHaveLength(1);
    expect(bans[0]).toMatchObject({ userId: newcomer.userId, reason: "spam", bannedBy: mod.userId });
    const err = await expectStatus(newcomer.req("POST", `/v1/communities/${orgId}/join`), 403);
    expect(err.error.code).toBe("banned");
    expect((await newcomer.req("GET", "/v1/communities/discover?q=night")).communities).toEqual([]);
    await expectStatus(join(newcomer, await invite(owner, { role: "MEMBER" })), 403);
    // Lifting it lets them back.
    await mod.req("DELETE", `/v1/orgs/${orgId}/bans/${newcomer.userId}`);
    await expectStatus(mod.req("DELETE", `/v1/orgs/${orgId}/bans/${newcomer.userId}`), 404);
    expect((await newcomer.req("POST", `/v1/communities/${orgId}/join`)).org.myRole).toBe("MEMBER");
  });
});

describe("demotion to guest", () => {
  let o: GriipClient, m: GriipClient;
  let org: string, role: string;
  let privateChannel: string, gated: string, announce: string, lobby: string, dm: string, group: string;

  beforeAll(async () => {
    o = await h.client("demoteowner");
    m = await h.client("demotemember");
    org = (await o.req("POST", "/v1/orgs", { name: "Demotion Co" })).org.id;
    role = (await o.req("POST", `/v1/orgs/${org}/roles`, { name: "Finance", permissions: [], denies: [] })).role.id;
    const cat = (await o.req("POST", `/v1/orgs/${org}/categories`, { name: "Finance" })).category.id;
    await o.req("PUT", `/v1/categories/${cat}/overwrites`, { subject: "everyone", deny: ["channels.view"] });
    await o.req("PUT", `/v1/categories/${cat}/overwrites`, { subject: role, allow: ["channels.view"] });
    const code = (await o.req("POST", `/v1/orgs/${org}/invites`, { role: "MEMBER", roleId: role })).invite.code;
    await m.req("POST", "/v1/invites/accept", { code });
    privateChannel = (await o.req("POST", `/v1/orgs/${org}/channels`, { name: "payroll", categoryId: cat, kind: "PRIVATE", memberIds: [m.userId] })).channel.id;
    gated = (await o.req("POST", `/v1/orgs/${org}/channels`, { name: "finance", categoryId: cat })).channel.id;
    announce = (await o.req("POST", `/v1/orgs/${org}/channels`, { name: "news", kind: "ANNOUNCEMENT" })).channel.id;
    lobby = (await o.req("POST", `/v1/orgs/${org}/channels`, { name: "lobby", autoJoin: false })).channel.id;
    await m.req("POST", `/v1/conversations/${lobby}/join`);
    dm = (await o.req("POST", "/v1/conversations/dm", { userId: m.userId })).conversation.id;
    group = (await o.req("POST", "/v1/conversations/group", { name: "Lunch", memberIds: [m.userId] })).conversation.id;
    expect(await ids(m)).toEqual(expect.arrayContaining([privateChannel, gated, announce, lobby, dm, group]));
  });

  it("removes every channel membership in the community, and tells the person's devices", async () => {
    await m.connect();
    await o.req("PATCH", `/v1/orgs/${org}/members/${m.userId}`, { role: "GUEST" });
    expect((await ids(m)).sort()).toEqual([dm, group].sort()); // DMs and groups are not community-scoped
    for (const c of [privateChannel, gated, announce, lobby]) {
      await expectStatus(m.req("GET", `/v1/conversations/${c}`), 404);
      const keys = (await o.req("GET", `/v1/conversations/${c}/devices`)).users.map((u: any) => u.userId);
      expect(keys).not.toContain(m.userId);
    }
    expect((await o.req("GET", `/v1/orgs/${org}/members`)).members.find((x: any) => x.id === m.userId).orgRole).toBe("GUEST");
    await m.waitFor(() => m.events.some((e) => e.t === "conv.updated" && e.conversationId === privateChannel));
    await m.disconnect();
  });

  it("refuses envelopes still addressed to the guest's devices", async () => {
    const stale = await o.conversationKeys(lobby);
    const guestKeys = await o.userKeys(m.userId);
    const devices = [...stale.flatMap((u) => o.verifiedDevices(u)), ...o.verifiedDevices(guestKeys)].filter((d) => d.id !== o.deviceId);
    const env = await o.encrypt(lobby, { type: "text", text: "after demotion" }, "message", devices);
    const err = await expectStatus(o.req("POST", "/v1/messages", env), 409);
    expect(err.error.code).toBe("device_mismatch");
    expect(err.error.details.extra).toContain(m.deviceId);
  });

  it("keeps channels a manager grants the guest explicitly afterwards", async () => {
    await o.req("PUT", `/v1/conversations/${lobby}/members/${m.userId}`, {});
    await o.req("PATCH", `/v1/orgs/${org}/members/${m.userId}`, { role: "GUEST" });
    expect((await ids(m)).sort()).toEqual([dm, group, lobby].sort());
  });

  it("promotion back to member restores what the person's roles let them see; private channels need a new invitation", async () => {
    await o.req("PATCH", `/v1/orgs/${org}/members/${m.userId}`, { role: "MEMBER" });
    const convs = await ids(m);
    // They kept the Finance role through the demotion, so the gated channel comes back with it.
    expect(convs).toEqual(expect.arrayContaining([dm, lobby, announce, gated]));
    expect(convs).not.toContain(privateChannel);
  });
});
