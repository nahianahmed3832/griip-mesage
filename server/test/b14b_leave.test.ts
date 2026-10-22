import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { GriipClient } from "./support/griipClient.js";
import { type Harness, startServer } from "./support/harness.js";

/**
 * Batch B14b (master prompt §5.2): people leave a community, not one of its channels. A manager can still take someone
 * out of a channel, and a group can still be left.
 */
let h: Harness;
let owner: GriipClient, member: GriipClient;
let org: string, chan: string;

async function status(
  p: Promise<unknown>,
): Promise<{ status: number; code?: string }> {
  try {
    await p;
  } catch (e: any) {
    return { status: e?.status, code: e?.body?.error ?? e?.code };
  }
  return { status: 200 };
}

beforeAll(async () => {
  h = await startServer();
  [owner, member] = await Promise.all(
    ["b14bowner", "b14bmember"].map((n) => h.client(n)),
  );
  org = (await owner.req("POST", "/v1/orgs", { name: "Leave Lab" })).org.id;
  const code = (
    await owner.req("POST", `/v1/orgs/${org}/invites`, {
      role: "MEMBER",
      maxUses: 2,
    })
  ).invite.code;
  await member.req("POST", "/v1/invites/accept", { code });
  chan = (
    await owner.req("POST", `/v1/orgs/${org}/channels`, {
      name: "lounge",
      autoJoin: true,
    })
  ).channel.id;
});
afterAll(async () => h.close());

describe("leaving", () => {
  it("refuses leaving one channel of a community, and the member keeps it", async () => {
    const res = await status(
      member.req(
        "DELETE",
        `/v1/conversations/${chan}/members/${member.userId}`,
      ),
    );
    expect(res.status).toBe(403);
    const members = await owner.req("GET", `/v1/conversations/${chan}/members`);
    expect(JSON.stringify(members)).toContain(member.userId);
  });

  it("lets a manager take someone out of a channel", async () => {
    await owner.req(
      "DELETE",
      `/v1/conversations/${chan}/members/${member.userId}`,
    );
    const members = await owner.req("GET", `/v1/conversations/${chan}/members`);
    expect(JSON.stringify(members)).not.toContain(member.userId);
  });

  it("still lets someone leave a group", async () => {
    const group = (
      await owner.req("POST", "/v1/conversations/group", {
        name: "Trip",
        memberIds: [member.userId],
      })
    ).conversation.id;
    await member.req(
      "DELETE",
      `/v1/conversations/${group}/members/${member.userId}`,
    );
    const res = await status(member.req("GET", `/v1/conversations/${group}`));
    expect([403, 404]).toContain(res.status);
  });
});
