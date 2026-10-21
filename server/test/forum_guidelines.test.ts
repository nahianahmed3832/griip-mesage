import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { GriipClient } from "./support/griipClient.js";
import { expectStatus, type Harness, startServer } from "./support/harness.js";

/** Batch B8: a forum's post guidelines. */
let h: Harness;
let owner: GriipClient, member: GriipClient;
let forum: string, text: string;

beforeAll(async () => {
  h = await startServer();
  [owner, member] = await Promise.all(["owner", "member"].map((n) => h.client(n)));
  const orgId = (await owner.req("POST", "/v1/orgs", { name: "Forums" })).org.id;
  const code = (await owner.req("POST", `/v1/orgs/${orgId}/invites`, { role: "MEMBER" })).invite.code;
  await member.req("POST", "/v1/invites/accept", { code });
  const make = async (name: string) => (await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name })).channel.id;
  forum = await make("ideas");
  text = await make("chat");
  await owner.req("PATCH", `/v1/conversations/${forum}`, { format: "forum" });
});
afterAll(async () => h.close());

describe("forum guidelines", () => {
  it("are set by people who manage the forum and shown to everyone in it", async () => {
    expect((await owner.req("GET", "/v1/server-info")).features).toContain("forum-guidelines");
    await expectStatus(member.req("PATCH", `/v1/conversations/${forum}`, { guidelines: "Be kind" }), 403);
    await owner.req("PATCH", `/v1/conversations/${forum}`, { guidelines: "  Search before posting. One question per post.  " });
    expect((await member.req("GET", `/v1/conversations/${forum}`)).conversation.guidelines).toBe("Search before posting. One question per post.");
    await owner.req("PATCH", `/v1/conversations/${forum}`, { guidelines: null });
    expect((await member.req("GET", `/v1/conversations/${forum}`)).conversation.guidelines).toBeNull();
  });

  it("are only for forums, and bounded", async () => {
    await expectStatus(owner.req("PATCH", `/v1/conversations/${text}`, { guidelines: "x" }), 400);
    await expectStatus(owner.req("PATCH", `/v1/conversations/${forum}`, { guidelines: "x".repeat(2001) }), 400);
    expect((await owner.req("GET", `/v1/conversations/${text}`)).conversation.guidelines).toBeNull();
  });
});
