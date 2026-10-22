import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { matchRank, page } from "../src/modules/messaging/search/service.js";
import type { GriipClient } from "./support/griipClient.js";
import { expectStatus, type Harness, startServer } from "./support/harness.js";

/** Round 7 slice C2: universal search (docs/SEARCH.md). Every kind must follow its resource's own access rule. */
let h: Harness;
let owner: GriipClient, member: GriipClient, guest: GriipClient, stranger: GriipClient, blocker: GriipClient;
let org1: string, org2: string;
const ids: Record<string, string> = {};

type Page = { items: { id: string; name?: string; displayName?: string; username?: string }[]; next: string | null };
const find = async (c: GriipClient, q: string, extra = "") =>
  (await c.req("GET", `/v1/search?q=${encodeURIComponent(q)}${extra}`)).results as Record<string, Page>;

beforeAll(async () => {
  h = await startServer();
  [owner, member, guest, stranger, blocker] = await Promise.all(
    [
      ["srowner", "Priya Search"],
      ["srmember", "Member Search"],
      ["srguest", "Guest Search"],
      ["srstranger", "Priya Stranger"],
      ["srblocker", "Priya Blocker"],
    ].map(([u, n]) => h.client(u, n)),
  );
  org1 = (await owner.req("POST", "/v1/orgs", { name: "Design Studio" })).org.id;
  org2 = (await owner.req("POST", "/v1/orgs", { name: "Design Secret Co" })).org.id;
  for (const [c, role] of [
    [member, "MEMBER"],
    [guest, "GUEST"],
    [blocker, "MEMBER"],
  ] as const) {
    const code = (await owner.req("POST", `/v1/orgs/${org1}/invites`, { role })).invite.code;
    await c.req("POST", "/v1/invites/accept", { code });
  }
  const ch = async (org: string, name: string, kind = "PUBLIC", extra = {}) =>
    (ids[name] = (await owner.req("POST", `/v1/orgs/${org}/channels`, { name, scope: "ORG", kind, ...extra })).channel.id);
  await ch(org1, "design");
  await ch(org1, "design-review", "PUBLIC", { autoJoin: false });
  await ch(org1, "ux-design");
  await ch(org1, "redesigned");
  await ch(org1, "design-leadership", "PRIVATE");
  await ch(org2, "design-board");
  for (let i = 1; i <= 15; i++) await ch(org1, `proj-${String(i).padStart(2, "0")}`);
  ids.group = (await member.req("POST", "/v1/conversations/group", { name: "Weekend Hikers", memberIds: [owner.userId] })).conversation.id;
  await blocker.req("POST", "/v1/blocks", { userId: member.userId });
});
afterAll(async () => h.close());

describe("universal search (round 7, C2)", () => {
  it("ranks exact, then prefix, then word start, then contains", async () => {
    const names = (await find(member, "design", "&types=channels")).channels.items.map((c) => c.name);
    expect(names.slice(0, 4)).toEqual(["design", "design-review", "ux-design", "redesigned"]);
    expect(matchRank("Design", "design")).toBe(0);
    expect(matchRank("ux design", "des")).toBe(2);
    expect(matchRank("abc", "z")).toBeNull();
  });

  it("finds public channels you haven't joined, never private ones you're not in, nor other workspaces'", async () => {
    const names = (await find(member, "design", "&types=channels")).channels.items.map((c) => c.name);
    expect(names).toContain("design-review"); // opt-in, not joined
    expect(names).not.toContain("design-leadership");
    expect(names).not.toContain("design-board");
    const own = (await find(owner, "design", "&types=channels")).channels.items.map((c) => c.name);
    expect(own).toEqual(expect.arrayContaining(["design-leadership", "design-board"]));
  });

  it("people: shared-workspace members by partial name; strangers only by exact username; never someone who blocked you", async () => {
    const people = (await find(member, "priya", "&types=people")).people.items.map((u) => u.id);
    expect(people).toContain(owner.userId);
    expect(people).not.toContain(stranger.userId);
    expect(people).not.toContain(blocker.userId);
    expect((await find(member, stranger.username, "&types=people")).people.items.map((u) => u.id)).toEqual([stranger.userId]);
    expect((await find(member, blocker.username, "&types=people")).people.items).toEqual([]);
  });

  it("a guest doesn't browse the workspace's people", async () => {
    expect((await find(guest, "search", "&types=people")).people.items).toEqual([]);
  });

  it("workspaces and groups: only yours", async () => {
    const r = await find(member, "design");
    expect(r.workspaces.items.map((o) => o.name)).toEqual(["Design Studio"]);
    expect((await find(owner, "hikers")).groups.items.map((g) => g.id)).toEqual([ids.group]);
    expect((await find(guest, "hikers")).groups.items).toEqual([]);
  });

  it("pages one kind with a cursor", async () => {
    const first = (await find(member, "proj", "&types=channels&limit=10")).channels;
    expect(first.items).toHaveLength(10);
    expect(first.next).toBeTruthy();
    const second = (await find(member, "proj", `&types=channels&limit=10&cursor=${first.next}`)).channels;
    expect(second.items).toHaveLength(5);
    expect(second.next).toBeNull();
    expect(new Set([...first.items, ...second.items].map((c) => c.id)).size).toBe(15);
    expect(page([1, 2, 3], "bm90LWEtbnVtYmVy", 2).items).toEqual([1, 2]); // a garbage cursor starts over
  });

  it("filters by a workspace you're in, and refuses one you're not", async () => {
    expect((await find(owner, "design", `&types=channels&orgId=${org2}`)).channels.items.map((c) => c.name)).toEqual(["design-board"]);
    await expectStatus(member.req("GET", `/v1/search?q=design&orgId=${org2}`), 404);
    await expectStatus(member.req("GET", "/v1/search?q="), 400);
  });

  it("is advertised to apps", async () => {
    expect((await member.req("GET", "/v1/server-info")).features).toContain("search");
  });
});
