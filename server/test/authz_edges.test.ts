import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { runCleanup } from "../src/jobs.js";
import type { GriipClient } from "./support/griipClient.js";
import { expectStatus, type Harness, startServer } from "./support/harness.js";

let h: Harness;
let owner: GriipClient, manager: GriipClient;
let org: string, branch: string;

beforeAll(async () => {
  h = await startServer();
  owner = await h.client("edgeowner");
  manager = await h.client("edgemanager");
  org = (await owner.req("POST", "/v1/orgs", { name: "Edge Co" })).org.id;
  branch = (await owner.req("POST", `/v1/orgs/${org}/branches`, { name: "Dhaka" })).branch.id;
  const code = (await owner.req("POST", `/v1/orgs/${org}/invites`, { role: "MEMBER", branchId: branch })).invite.code;
  await manager.req("POST", "/v1/invites/accept", { code });
  await owner.req("PUT", `/v1/branches/${branch}/members/${manager.userId}`, { role: "MANAGER" });
});
afterAll(async () => h.close());

describe("invites are re-checked against their creator's current rights (T3.1)", () => {
  it("an invite from a branch manager stops working once they're no longer a manager", async () => {
    const code = (await manager.req("POST", `/v1/orgs/${org}/invites`, { role: "MEMBER", branchId: branch, maxUses: 5 })).invite.code;
    const early = await h.client("edgeearly");
    await early.req("POST", "/v1/invites/accept", { code }); // works while the manager still manages the branch

    await owner.req("PUT", `/v1/branches/${branch}/members/${manager.userId}`, { role: "MEMBER" });
    const late = await h.client("edgelate");
    await expectStatus(late.req("POST", "/v1/invites/preview", { code }), 404);
    await expectStatus(late.req("POST", "/v1/invites/accept", { code }), 404);
    expect((await owner.req("GET", `/v1/orgs/${org}/members`)).members.map((m: any) => m.id)).not.toContain(late.userId);
  });

  it("an admin invite stops working when its owner becomes an admin; others' invites are unaffected", async () => {
    const coOwner = await h.client("edgecoowner");
    const c1 = (await owner.req("POST", `/v1/orgs/${org}/invites`, { role: "ADMIN" })).invite.code;
    await coOwner.req("POST", "/v1/invites/accept", { code: c1 });
    await owner.req("PATCH", `/v1/orgs/${org}/members/${coOwner.userId}`, { role: "OWNER" });

    const adminInvite = (await owner.req("POST", `/v1/orgs/${org}/invites`, { role: "ADMIN" })).invite.code;
    const memberInvite = (await coOwner.req("POST", `/v1/orgs/${org}/invites`, { role: "MEMBER" })).invite.code;
    await coOwner.req("PATCH", `/v1/orgs/${org}/members/${owner.userId}`, { role: "ADMIN" });

    const x = await h.client("edgex");
    await expectStatus(x.req("POST", "/v1/invites/accept", { code: adminInvite }), 404);
    await x.req("POST", "/v1/invites/accept", { code: memberInvite });
    expect((await coOwner.req("GET", `/v1/orgs/${org}/members`)).members.find((m: any) => m.id === x.userId).orgRole).toBe("MEMBER");
  });
});

describe("accounts disabled from the admin CLI are disconnected (T3.3)", () => {
  it("closes their open sockets at the next cleanup run", async () => {
    const quiet = await h.client("edgequiet");
    await quiet.connect();
    const closed = new Promise<void>((r) => quiet.ws!.once("close", () => r()));
    // What `admin.js disable` writes from its own process: the API's hub doesn't hear about it.
    h.ctx.db.prepare("UPDATE users SET disabled_at = ? WHERE id = ?").run(Date.now(), quiet.userId);
    h.ctx.db.prepare("DELETE FROM sessions WHERE user_id = ?").run(quiet.userId);
    const r = await runCleanup(h.ctx);
    expect(r.disabledDisconnected).toBe(1);
    await closed;
  });
});

describe("archived branches and teams are read-only (T3.2, D-14)", () => {
  it("blocks posts, calls, new members, invites and channels until unarchived", async () => {
    const o = await h.client("archowner");
    const m = await h.client("archmember");
    const extra = await h.client("archextra");
    const orgId = (await o.req("POST", "/v1/orgs", { name: "Archive Co" })).org.id;
    const br = (await o.req("POST", `/v1/orgs/${orgId}/branches`, { name: "Old site" })).branch.id;
    const team = (await o.req("POST", `/v1/orgs/${orgId}/teams`, { name: "Wind-down", branchId: br })).team.id;
    for (const c of [m, extra]) {
      const code = (await o.req("POST", `/v1/orgs/${orgId}/invites`, { role: "MEMBER", branchId: br })).invite.code;
      await c.req("POST", "/v1/invites/accept", { code });
    }
    await o.req("PUT", `/v1/teams/${team}/members/${m.userId}`, {});
    const ch = (await o.req("POST", `/v1/orgs/${orgId}/channels`, { name: "wind-down", scope: "TEAM", scopeId: team, kind: "PUBLIC", memberIds: [m.userId] })).channel.id;
    const pendingInvite = (await o.req("POST", `/v1/orgs/${orgId}/invites`, { role: "MEMBER", teamId: team })).invite.code;
    await m.send(ch, { type: "text", text: "before archiving" });

    await o.req("PATCH", `/v1/branches/${br}`, { archived: true }); // archiving the branch archives its teams too
    await expectStatus(m.send(ch, { type: "text", text: "after" }), 403);
    await expectStatus(m.req("POST", "/v1/calls", { conversationId: ch }), 409);
    await expectStatus(o.req("PUT", `/v1/teams/${team}/members/${extra.userId}`, {}), 409);
    await expectStatus(o.req("POST", `/v1/orgs/${orgId}/invites`, { role: "MEMBER", teamId: team }), 409);
    await expectStatus(o.req("POST", `/v1/orgs/${orgId}/channels`, { name: "new", scope: "TEAM", scopeId: team, kind: "PUBLIC" }), 409);
    await expectStatus(extra.req("POST", "/v1/invites/accept", { code: pendingInvite }), 404);
    // Existing members keep access to the (read-only) channel.
    expect((await m.req("GET", `/v1/conversations/${ch}`)).conversation.canPost).toBe(false);

    await o.req("PATCH", `/v1/branches/${br}`, { archived: false });
    await m.send(ch, { type: "text", text: "back" });
    await o.req("PUT", `/v1/teams/${team}/members/${extra.userId}`, {});
  });
});
