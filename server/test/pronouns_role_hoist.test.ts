import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { GriipClient } from "./support/griipClient.js";
import { expectStatus, type Harness, startServer } from "./support/harness.js";

/** Batch B12: pronouns on profiles, and roles whose members are listed separately. */
let h: Harness;
let ada: GriipClient, bob: GriipClient, stranger: GriipClient;
let orgId: string;

beforeAll(async () => {
  h = await startServer();
  [ada, bob, stranger] = await Promise.all(["ada", "bob", "stranger"].map((n) => h.client(n)));
  orgId = (await ada.req("POST", "/v1/orgs", { name: "Pronoun Club" })).org.id;
  const code = (await ada.req("POST", `/v1/orgs/${orgId}/invites`, { role: "MEMBER" })).invite.code;
  await bob.req("POST", "/v1/invites/accept", { code });
});
afterAll(async () => h.close());

describe("pronouns", () => {
  it("are set by the person, shown with the bio and hidden by the bio's privacy setting", async () => {
    expect((await ada.req("GET", "/v1/server-info")).features).toEqual(expect.arrayContaining(["pronouns", "role-hoist"]));
    expect((await ada.req("PATCH", "/v1/users/me", { pronouns: "she/her" })).user.pronouns).toBe("she/her");
    await expectStatus(ada.req("PATCH", "/v1/users/me", { pronouns: "x".repeat(41) }), 400);
    // Changing another field keeps them.
    expect((await ada.req("PATCH", "/v1/users/me", { displayName: "Ada L" })).user.pronouns).toBe("she/her");
    expect((await stranger.req("GET", `/v1/users/${ada.userId}`)).user.pronouns).toBe("she/her");
    await ada.req("PUT", "/v1/users/me/privacy", { bio: "contacts" });
    expect((await stranger.req("GET", `/v1/users/${ada.userId}`)).user.pronouns).toBeUndefined();
    expect((await bob.req("GET", `/v1/users/${ada.userId}`)).user.pronouns).toBe("she/her");
    // An empty value clears them.
    expect((await ada.req("PATCH", "/v1/users/me", { pronouns: "" })).user.pronouns).toBeUndefined();
  });
});

describe("roles listed separately", () => {
  it("are created and edited with hoist, and the member list marks them", async () => {
    const mod = (await ada.req("POST", `/v1/orgs/${orgId}/roles`, { name: "Moderators", permissions: [], hoist: true })).role;
    expect(mod.hoist).toBe(true);
    const fan = (await ada.req("POST", `/v1/orgs/${orgId}/roles`, { name: "Fans", permissions: [] })).role;
    expect(fan.hoist).toBe(false);
    await ada.req("PUT", `/v1/roles/${mod.id}/members/${bob.userId}`, {});
    await ada.req("PUT", `/v1/roles/${fan.id}/members/${bob.userId}`, {});
    const members = (await ada.req("GET", `/v1/orgs/${orgId}/members`)).members;
    const b = members.find((m: { id: string }) => m.id === bob.userId);
    expect(b.customRoles).toEqual([
      { id: mod.id, name: "Moderators", color: null, hoist: true, position: mod.position },
      { id: fan.id, name: "Fans", color: null },
    ]);
    // Turning it off; other fields stay.
    const off = (await ada.req("PATCH", `/v1/roles/${mod.id}`, { baseRev: mod.rev, hoist: false })).role;
    expect(off).toMatchObject({ hoist: false, name: "Moderators" });
    // A member without role rights can't change it.
    await expectStatus(bob.req("PATCH", `/v1/roles/${fan.id}`, { baseRev: fan.rev, hoist: true }), 403);
  });
});
