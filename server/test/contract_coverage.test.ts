import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { GriipClient } from "./support/griipClient.js";
import { type Harness, startServer } from "./support/harness.js";

/**
 * Exercises frames and routes no other suite reaches with a socket open, so the contract checks
 * (test/support/contracts.ts) see them and docs/api/COVERAGE.md can call them verified.
 */
let h: Harness;
let owner: GriipClient, mod: GriipClient, member: GriipClient, applicant: GriipClient;
let orgId: string;

const frame = (c: GriipClient, t: string, extra: (f: Record<string, unknown>) => boolean = () => true) =>
  c.waitFor(() => (c.events as Record<string, unknown>[]).find((f) => f.t === t && extra(f)));

beforeAll(async () => {
  h = await startServer();
  [owner, mod, member, applicant] = await Promise.all(["owner", "mod", "member", "applicant"].map((n) => h.client(n)));
  orgId = (await owner.req("POST", "/v1/orgs", { name: "Contract Lab", visibility: "public", joinPolicy: "invite" })).org.id;
  const code = (await owner.req("POST", `/v1/orgs/${orgId}/invites`, { role: "MEMBER", maxUses: 5 })).invite.code;
  for (const c of [mod, member]) await c.req("POST", "/v1/invites/accept", { code });
  const role = (await owner.req("POST", `/v1/orgs/${orgId}/roles`, { template: "moderator" })).role;
  await owner.req("PUT", `/v1/roles/${role.id}/members/${mod.userId}`);
  for (const c of [owner, mod, member, applicant]) await c.connect();
});
afterAll(async () => h.close());

describe("contract coverage", () => {
  it("acknowledges queue items over REST", async () => {
    const dm = (await owner.req("POST", "/v1/conversations/dm", { userId: member.userId })).conversation.id;
    await member.disconnect();
    await owner.send(dm, { type: "text", text: "hi" });
    const q = await member.req("GET", "/v1/queue");
    expect(q.items.length).toBeGreaterThan(0);
    expect(await member.req("POST", "/v1/queue/ack", { ids: q.items.map((i: { id: number }) => i.id) })).toEqual({ removed: q.items.length });
    await member.connect();
  });

  it("tells a group call's starter that someone declined", async () => {
    // In a ringing 1:1 call a decline ends the call instead (call.ended).
    const group = (await owner.req("POST", "/v1/conversations/group", { name: "Contract call", memberIds: [mod.userId, member.userId] })).conversation.id;
    const r = await owner.req("POST", "/v1/calls", { conversationId: group, video: false });
    await mod.req("POST", `/v1/calls/${r.call.id}/decline`, { reason: "busy" });
    expect(await frame(owner, "call", (f) => f.event === "declined")).toMatchObject({ userId: mod.userId, reason: "busy" });
  });

  it("tells approvers about applications and the applicant about the decision", async () => {
    await owner.req("PUT", `/v1/orgs/${orgId}/application`, { questions: ["Why?"] });
    const a = await applicant.req("POST", `/v1/communities/${orgId}/apply`, { answers: ["curious"] });
    await frame(mod, "org.applications");
    await mod.req("PATCH", `/v1/applications/${a.application.id}`, { status: "approved" });
    expect(await frame(applicant, "org.application")).toMatchObject({ orgId, status: "approved" });
  });

  it("tells moderators about reports", async () => {
    const channel = (await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "talk", scope: "ORG" })).channel.id;
    await member.req("POST", "/v1/reports", { targetUserId: applicant.userId, conversationId: channel, messageId: "m_contract_1", reason: "spam" });
    expect(await frame(mod, "org.reports")).toMatchObject({ orgId });
  });

  it("tells both people when a connection changes", async () => {
    await member.req("POST", "/v1/connections", { userId: applicant.userId });
    await frame(applicant, "connections.updated");
  });

  it("tells moderators about a burst of joins", async () => {
    const open = (await owner.req("POST", "/v1/orgs", { name: "Busy Contract Lab", visibility: "public", joinPolicy: "open" })).org.id;
    const joiners = await Promise.all(Array.from({ length: 9 }, (_, i) => h.client(`cjoiner${i}`)));
    for (const j of joiners) await j.req("POST", `/v1/communities/${open}/join`);
    expect(await frame(owner, "org.alert")).toMatchObject({ orgId: open, kind: "joins" });
  });

  it("shows a safety-number fingerprint", async () => {
    expect((await owner.req("GET", `/v1/fingerprint/${member.userId}`))).toBeTruthy();
  });

  it("lists and renames categories", async () => {
    const created = await owner.req("POST", `/v1/orgs/${orgId}/categories`, { name: "Contract category" });
    const list = await owner.req("GET", `/v1/orgs/${orgId}/categories`);
    expect(list.categories.length).toBeGreaterThan(0);
    const id = created.category?.id ?? list.categories[0].id;
    expect(await owner.req("PATCH", `/v1/categories/${id}`, { name: "Renamed" })).toBeTruthy();
  });

  it("answers the platform administration routes", async () => {
    expect(await owner.req("GET", "/v1/platform/me")).toEqual({ platformAdmin: false });
    (h.ctx.config.platformAdmins as Set<string>).add(owner.userId);
    expect(await owner.req("GET", "/v1/platform/me")).toEqual({ platformAdmin: true });
    expect(await owner.req("GET", "/v1/admin/experiments")).toBeTruthy();
  });
});
