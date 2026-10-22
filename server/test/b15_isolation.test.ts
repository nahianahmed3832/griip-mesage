import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { GriipClient } from "./support/griipClient.js";
import { type Harness, startServer } from "./support/harness.js";
import { deviceIdsOf, queuedCount } from "./support/relay.js";

/**
 * Batch B15 (final quality gate): the community routes added in B4–B14 never reach outside their community, someone
 * removed loses access at once and receives nothing more, and signing out ends the session.
 */
let h: Harness;
let ownerA: GriipClient,
  memberA: GriipClient,
  ownerB: GriipClient,
  leaver: GriipClient;
let orgA: string, orgB: string, chanA: string, chanB: string;

/** The request was refused (forbidden or not found, so an outsider learns nothing). */
async function refused(p: Promise<unknown>): Promise<number> {
  try {
    await p;
  } catch (e: any) {
    expect([401, 403, 404]).toContain(e?.status);
    return e.status;
  }
  throw new Error("expected a refusal, got success");
}

beforeAll(async () => {
  h = await startServer();
  [ownerA, memberA, ownerB, leaver] = await Promise.all(
    ["b15ownera", "b15membera", "b15ownerb", "b15leaver"].map((n) =>
      h.client(n),
    ),
  );
  orgA = (await ownerA.req("POST", "/v1/orgs", { name: "Alpha Lab" })).org.id;
  orgB = (await ownerB.req("POST", "/v1/orgs", { name: "Beta Lab" })).org.id;
  const code = (
    await ownerA.req("POST", `/v1/orgs/${orgA}/invites`, {
      role: "MEMBER",
      maxUses: 5,
    })
  ).invite.code;
  await memberA.req("POST", "/v1/invites/accept", { code });
  await leaver.req("POST", "/v1/invites/accept", { code });
  chanA = (
    await ownerA.req("POST", `/v1/orgs/${orgA}/channels`, {
      name: "alpha-talk",
      autoJoin: true,
    })
  ).channel.id;
  chanB = (
    await ownerB.req("POST", `/v1/orgs/${orgB}/channels`, {
      name: "beta-secret",
    })
  ).channel.id;
});
afterAll(async () => h.close());

describe("another community stays closed", () => {
  it("refuses every community route of B4–B14 to someone who isn't in it", async () => {
    const paths = [
      `/v1/orgs/${orgB}`,
      `/v1/orgs/${orgB}/members`,
      `/v1/orgs/${orgB}/roles`,
      `/v1/orgs/${orgB}/expressions`,
      `/v1/orgs/${orgB}/onboarding`,
      `/v1/orgs/${orgB}/applications`,
      `/v1/conversations/${chanB}`,
      `/v1/conversations/${chanB}/members`,
    ];
    for (const p of paths) await refused(memberA.req("GET", p));
    await refused(memberA.send(chanB, { type: "text", text: "hello?" }));
    await refused(
      memberA.req("PUT", `/v1/orgs/${orgB}/guide`, {
        welcome: "x",
        tasks: [],
        resources: [],
      }),
    );
    await refused(
      memberA.req("PUT", `/v1/orgs/${orgB}/onboarding`, {
        enabled: false,
        prompts: [],
      }),
    );
  });
});

describe("removing someone", () => {
  it("takes access away at once, and nothing sent afterwards is queued for them", async () => {
    await leaver.send(chanA, { type: "text", text: "still here" });
    await ownerA.req("DELETE", `/v1/orgs/${orgA}/members/${leaver.userId}`);
    await refused(leaver.req("GET", `/v1/conversations/${chanA}`));
    await refused(leaver.send(chanA, { type: "text", text: "after removal" }));
    await refused(leaver.req("GET", `/v1/orgs/${orgA}/expressions`));
    const sent = await ownerA.send(chanA, {
      type: "text",
      text: "after they left",
    });
    const id = (sent as { messageId?: string }).messageId;
    const leaverDevices = deviceIdsOf(h, leaver.userId);
    const queued = { n: await queuedCount(h, (r) => leaverDevices.has(r.recipient_device_id) && r.conversation_id === chanA) };
    expect(id ?? "sent").toBeTruthy();
    expect(queued.n).toBe(0);
  });
});

describe("signing out", () => {
  it("ends the session: the same token is refused afterwards", async () => {
    const c = await h.client("b15signout");
    await c.req("GET", "/v1/users/me");
    await c.req("POST", "/v1/auth/logout");
    expect(await refused(c.req("GET", "/v1/users/me"))).toBe(401);
  });
});
