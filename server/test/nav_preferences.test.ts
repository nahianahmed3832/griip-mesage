import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { GriipClient } from "./support/griipClient.js";
import { expectStatus, type Harness, startServer } from "./support/harness.js";

/**
 * Personal navigation preferences and organisation avatars (docs/WORKSPACE_ADMINISTRATION.md): who can read and write
 * them, optimistic concurrency between devices, normalisation, isolation between people and workspaces, and clean-up
 * when someone leaves or an organisation is deleted.
 */
let h: Harness;
let alice: GriipClient, bob: GriipClient, outsider: GriipClient;
let orgId: string, general: string, random: string, privateCh: string, dm: string;

const jpeg = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(200, 7), Buffer.from([0xff, 0xd9])]);
const prefsOf = (c: GriipClient, ws: string) => c.req("GET", `/v1/me/preferences/${ws}`);
const put = (c: GriipClient, ws: string, baseRev: number, prefs: object) => c.req("PUT", `/v1/me/preferences/${ws}`, { baseRev, prefs });

beforeAll(async () => {
  h = await startServer();
  [alice, bob, outsider] = await Promise.all(["alice", "bob", "outsider"].map((n) => h.client(n)));
  orgId = (await alice.req("POST", "/v1/orgs", { name: "Prefs Lab" })).org.id;
  const code = (await alice.req("POST", `/v1/orgs/${orgId}/invites`, { role: "MEMBER" })).invite.code;
  await bob.req("POST", "/v1/invites/accept", { code });
  general = (await alice.req("POST", `/v1/orgs/${orgId}/channels`, { name: "general", scope: "ORG" })).channel.id;
  random = (await alice.req("POST", `/v1/orgs/${orgId}/channels`, { name: "random", scope: "ORG" })).channel.id;
  privateCh = (await alice.req("POST", `/v1/orgs/${orgId}/channels`, { name: "secret", scope: "ORG", kind: "PRIVATE" })).channel.id;
  for (const c of [general, random]) await bob.req("POST", `/v1/conversations/${c}/join`).catch(() => undefined);
  dm = (await alice.req("POST", "/v1/conversations/dm", { userId: bob.userId })).conversation.id;
});
afterAll(async () => h.close());

describe("navigation preferences", () => {
  it("advertises the feature and starts empty at revision 0", async () => {
    expect((await alice.req("GET", "/v1/server-info")).features).toEqual(expect.arrayContaining(["nav-preferences", "org-photos"]));
    expect(await prefsOf(alice, orgId)).toMatchObject({ workspaceId: orgId, rev: 0, updatedAt: null, prefs: { starred: [], sections: [], collapsed: [], notify: {} } });
  });

  it("stores starred conversations, sections, collapsed groups and notification levels, and bumps the revision", async () => {
    const r = await put(alice, orgId, 0, {
      starred: [general],
      sections: [{ id: "s_projects", name: "  Projects ", items: [random], collapsed: true }],
      collapsed: ["dms", "branch:b_x"],
      notify: { [general]: "mentions", [random]: "all" },
    });
    expect(r.rev).toBe(1);
    // Trimmed names, and "all" (the default) isn't stored.
    expect(r.prefs).toEqual({
      starred: [general],
      sections: [{ id: "s_projects", name: "Projects", items: [random], collapsed: true }],
      collapsed: ["dms", "branch:b_x"],
      notify: { [general]: "mentions" },
    });
    expect((await prefsOf(alice, orgId)).prefs).toEqual(r.prefs);
  });

  it("refuses a write based on a stale revision and returns the current copy", async () => {
    const e = await expectStatus(put(alice, orgId, 0, { starred: [random] }), 409);
    expect(e.error.code).toBe("stale_preferences");
    expect(e.error.details.current.rev).toBe(1);
    expect(e.error.details.current.prefs.starred).toEqual([general]);
    // The same write against the current revision goes through.
    expect((await put(alice, orgId, 1, { starred: [random, general] })).rev).toBe(2);
  });

  it("lists each conversation once and drops conversations the person isn't in", async () => {
    const r = await put(bob, orgId, 0, {
      // Bob isn't in the private channel; "c_unknown" doesn't exist; random appears twice.
      starred: [random, privateCh, "c_unknown", random],
      sections: [{ id: "s_a", name: "A", items: [random, general] }, { id: "s_a", name: "dupe", items: [] }],
      notify: { [privateCh]: "none", [general]: "none" },
    });
    expect(r.prefs.starred).toEqual([random]);
    expect(r.prefs.sections).toEqual([{ id: "s_a", name: "A", items: [general], collapsed: false }]);
    expect(r.prefs.notify).toEqual({ [general]: "none" });
  });

  it("keeps each person's and each workspace's preferences apart", async () => {
    expect((await prefsOf(bob, orgId)).prefs.starred).toEqual([random]);
    expect((await prefsOf(alice, orgId)).prefs.starred).toEqual([random, general]);
    const personal = await put(alice, "personal", 0, { starred: [dm] });
    expect(personal.prefs.starred).toEqual([dm]);
    expect((await prefsOf(alice, orgId)).prefs.starred).toEqual([random, general]);
  });

  it("hides organisations the person isn't in, with the same answer as one that doesn't exist", async () => {
    const a = await expectStatus(prefsOf(outsider, orgId), 404);
    const b = await expectStatus(prefsOf(outsider, "o_doesnotexist"), 404);
    expect(a.error.code).toBe("workspace_not_found");
    expect(b.error.code).toBe("workspace_not_found");
    await expectStatus(put(outsider, orgId, 0, { starred: [] }), 404);
  });

  it("validates the document: unknown fields, levels, sizes and names", async () => {
    await expectStatus(put(alice, orgId, 2, { starred: [], extra: true }), 400);
    await expectStatus(put(alice, orgId, 2, { notify: { [general]: "loud" } }), 400);
    await expectStatus(put(alice, orgId, 2, { sections: [{ id: "s_x", name: "", items: [] }] }), 400);
    await expectStatus(put(alice, orgId, 2, { starred: Array.from({ length: 201 }, (_, i) => `c_${i}xx`) }), 400);
    await expectStatus(put(alice, orgId, -1, { starred: [] }), 400);
    await expectStatus(alice.req("PUT", `/v1/me/preferences/${orgId}`, { prefs: {} }), 400);
    expect((await prefsOf(alice, orgId)).rev).toBe(2);
  });

  it("tells the person's other devices (not the one that wrote), without content", async () => {
    const sent: { userId: string; msg: any; except?: string }[] = [];
    const original = h.ctx.hub.sendToUser.bind(h.ctx.hub);
    h.ctx.hub.sendToUser = (userId, msg, except) => {
      sent.push({ userId, msg, except });
      original(userId, msg, except);
    };
    try {
      await put(alice, "personal", (await prefsOf(alice, "personal")).rev, { starred: [dm], collapsed: ["dms"] });
    } finally {
      h.ctx.hub.sendToUser = original;
    }
    const frame = sent.find((s) => s.msg.t === "prefs.updated");
    expect(frame).toMatchObject({ userId: alice.userId, except: alice.deviceId, msg: { t: "prefs.updated", workspaceId: "personal" } });
    expect(Object.keys(frame!.msg).sort()).toEqual(["rev", "t", "workspaceId"]);
  });

  it("removes someone's arrangement of a workspace when they leave it", async () => {
    await bob.req("DELETE", `/v1/orgs/${orgId}/members/${bob.userId}`);
    // Back in through a new invitation: nothing of the old arrangement is left.
    const code = (await alice.req("POST", `/v1/orgs/${orgId}/invites`, { role: "MEMBER" })).invite.code;
    await bob.req("POST", "/v1/invites/accept", { code });
    expect(await prefsOf(bob, orgId)).toMatchObject({ rev: 0, prefs: { starred: [] } });
  });
});

describe("organisation photo", () => {
  it("lets owners and admins set it, members see it, and nobody else", async () => {
    await expectStatus(bob.req("PUT", `/v1/orgs/${orgId}/photo`, undefined, jpeg), 403);
    await expectStatus(alice.req("PUT", `/v1/orgs/${orgId}/photo`, undefined, Buffer.from("not a jpeg")), 400);
    await expectStatus(alice.req("PUT", `/v1/orgs/${orgId}/photo`, undefined, Buffer.concat([jpeg, Buffer.alloc(600 * 1024)])), 413);
    const { photoRev } = await alice.req("PUT", `/v1/orgs/${orgId}/photo`, undefined, jpeg);
    expect(photoRev).toBeGreaterThan(0);
    expect(Buffer.compare(await bob.req("GET", `/v1/orgs/${orgId}/photo`), jpeg)).toBe(0);
    expect((await bob.req("GET", `/v1/orgs/${orgId}`)).org.photoRev).toBe(photoRev);
    await expectStatus(outsider.req("GET", `/v1/orgs/${orgId}/photo`), 404);
  });

  it("replaces the stored file on a new upload and removes it on delete", async () => {
    const first = (await alice.req("GET", `/v1/orgs/${orgId}`)).org.photoRev as number;
    const second = (await alice.req("PUT", `/v1/orgs/${orgId}/photo`, undefined, jpeg)).photoRev as number;
    expect(second).toBeGreaterThan(first);
    expect(await h.ctx.blobs.exists("profile-photos", `${orgId}-${first}`)).toBe(false);
    expect(await h.ctx.blobs.exists("profile-photos", `${orgId}-${second}`)).toBe(true);
    await expectStatus(bob.req("DELETE", `/v1/orgs/${orgId}/photo`), 403);
    expect((await alice.req("DELETE", `/v1/orgs/${orgId}/photo`)).photoRev).toBeNull();
    expect(await h.ctx.blobs.exists("profile-photos", `${orgId}-${second}`)).toBe(false);
    await expectStatus(bob.req("GET", `/v1/orgs/${orgId}/photo`), 404);
  });

  it("deleting the organisation removes its photo and everyone's preferences for it", async () => {
    const rev = (await alice.req("PUT", `/v1/orgs/${orgId}/photo`, undefined, jpeg)).photoRev as number;
    await alice.req("DELETE", `/v1/orgs/${orgId}`, { confirmName: "Prefs Lab" });
    expect(await h.ctx.blobs.exists("profile-photos", `${orgId}-${rev}`)).toBe(false);
    expect(await h.ctx.prefs.get(alice.userId, orgId)).toBeNull();
    // Alice's personal workspace is untouched.
    expect((await prefsOf(alice, "personal")).prefs.starred).toEqual([dm]);
  });
});
