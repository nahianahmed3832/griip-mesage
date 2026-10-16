import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { GriipClient } from "./support/griipClient.js";
import { type Harness, startServer } from "./support/harness.js";

let h: Harness;
let owner: GriipClient, inA: GriipClient, inB: GriipClient;
let org: string, branchA: string, branchB: string;
let teamA: string, teamB: string, deptA: string, channelA: string, privateB: string;

beforeAll(async () => {
  h = await startServer();
  owner = await h.client("actowner");
  inA = await h.client("actina");
  inB = await h.client("actinb");
  org = (await owner.req("POST", "/v1/orgs", { name: "Scope Co" })).org.id;
  branchA = (await owner.req("POST", `/v1/orgs/${org}/branches`, { name: "Dhaka" })).branch.id;
  branchB = (await owner.req("POST", `/v1/orgs/${org}/branches`, { name: "London" })).branch.id;
  deptA = (await owner.req("POST", `/v1/branches/${branchA}/departments`, { name: "Restructuring" })).department.id;
  teamA = (await owner.req("POST", `/v1/orgs/${org}/teams`, { name: "Confidential Layoff Planning", branchId: branchA })).team.id;
  teamB = (await owner.req("POST", `/v1/orgs/${org}/teams`, { name: "London Sales", branchId: branchB })).team.id;
  for (const [c, branch] of [
    [inA, branchA],
    [inB, branchB],
  ] as const) {
    const code = (await owner.req("POST", `/v1/orgs/${org}/invites`, { role: "MEMBER", branchId: branch })).invite.code;
    await c.req("POST", "/v1/invites/accept", { code });
  }
  channelA = (await owner.req("POST", `/v1/orgs/${org}/channels`, { name: "dhaka-general", scope: "BRANCH", scopeId: branchA, kind: "PUBLIC" })).channel.id;
  privateB = (await owner.req("POST", `/v1/orgs/${org}/channels`, { name: "london-board", scope: "BRANCH", scopeId: branchB, kind: "PRIVATE" })).channel.id;
  await owner.req("POST", "/v1/calls", { conversationId: channelA });
});
afterAll(async () => h.close());

const feed = async (who: GriipClient) => (await who.req("GET", `/v1/orgs/${org}/activity`)).activity as { action: string; target: string; detail: string }[];
const targets = (items: { target: string }[]) => items.map((a) => a.target);
const text = (items: unknown[]) => JSON.stringify(items);

describe("activity feed respects branch isolation (T1.8)", () => {
  it("a branch-B member sees nothing about branch A's teams, departments, channels or calls", async () => {
    const mine = await feed(inB);
    expect(text(mine)).not.toContain("Confidential Layoff Planning");
    expect(text(mine)).not.toContain("Restructuring");
    expect(text(mine)).not.toContain("dhaka-general");
    expect(targets(mine)).not.toContain(teamA);
    expect(targets(mine)).not.toContain(deptA);
    expect(targets(mine)).not.toContain(channelA);
    expect(mine.some((a) => a.action === "call.start")).toBe(false);
    // Their own branch's structure, and org-level events, are still shown.
    expect(targets(mine)).toContain(teamB);
    expect(mine.filter((a) => a.action === "branch.create").map((a) => a.detail).sort()).toEqual(["Dhaka", "London"]);
    expect(mine.some((a) => a.action === "invite.accept")).toBe(true);
    // A private channel in their own branch they aren't in stays hidden too.
    expect(targets(mine)).not.toContain(privateB);
  });

  it("a branch-A member sees branch A's events and the call in a channel they belong to", async () => {
    await inA.req("POST", `/v1/conversations/${channelA}/join`).catch(() => {});
    const mine = await feed(inA);
    expect(targets(mine)).toEqual(expect.arrayContaining([teamA, deptA, channelA]));
    expect(mine.some((a) => a.action === "call.start" && a.target === channelA)).toBe(true);
    expect(targets(mine)).not.toContain(teamB);
  });

  it("org admins still see everything", async () => {
    const all = await feed(owner);
    expect(targets(all)).toEqual(expect.arrayContaining([teamA, teamB, deptA, channelA, privateB]));
  });

  it("filters older audit rows by looking up their target, and hides rows whose target is gone", async () => {
    const insert = h.ctx.db.prepare("INSERT INTO audit_log (at, actor_id, org_id, action, target, detail) VALUES (?, ?, ?, ?, ?, ?)");
    insert.run(Date.now(), owner.userId, org, "team.create", teamA, "Old Row Team A");
    insert.run(Date.now(), owner.userId, org, "team.create", "t_deleted", "Deleted Team");
    const mine = await feed(inB);
    expect(text(mine)).not.toContain("Old Row Team A");
    expect(text(mine)).not.toContain("Deleted Team");
    expect(text(await feed(inA))).toContain("Old Row Team A");
  });
});
