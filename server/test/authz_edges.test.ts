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
