import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { GriipClient } from "./support/griipClient.js";
import { expectStatus, type Harness, startServer } from "./support/harness.js";

/**
 * Profiles and per-field privacy (2026-10-17, docs/PROFILE_AND_PRIVACY.md). alice and bob share a conversation (contacts);
 * eve shares nothing with alice (a stranger).
 */
let h: Harness;
let alice: GriipClient, bob: GriipClient, eve: GriipClient;
const jpeg = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(200, 7), Buffer.from([0xff, 0xd9])]);

beforeAll(async () => {
  h = await startServer();
  alice = await h.client("alice", "Alice A");
  bob = await h.client("bob");
  eve = await h.client("eve");
  await alice.req("POST", "/v1/conversations/dm", { userId: bob.userId });
  await alice.req("PATCH", "/v1/users/me", {
    firstName: "Alice", lastName: "Ahmed", statusText: "Working on Orbit", birthday: "1994-03-21", phone: "+880 1712-345678",
  });
});
afterAll(async () => h.close());

describe("profile", () => {
  it("the owner sees every field and the privacy defaults", async () => {
    const meRes = (await alice.req("GET", "/v1/users/me")).user;
    expect(meRes).toMatchObject({ firstName: "Alice", lastName: "Ahmed", birthday: "1994-03-21", phone: "+8801712345678" });
    expect(meRes.privacy).toMatchObject({ birthday: "nobody", phone: "nobody", lastSeen: "contacts", photo: "everyone", findByPhone: "nobody" });
  });

  it("birthday and phone are private by default; names and bio are public", async () => {
    for (const viewer of [bob, eve]) {
      const u = (await viewer.req("GET", `/v1/users/${alice.userId}`)).user;
      expect(u).toMatchObject({ username: alice.username, displayName: "Alice A", firstName: "Alice", statusText: "Working on Orbit" });
      expect(u.birthday).toBeUndefined();
      expect(u.phone).toBeUndefined();
      expect(u.privacy).toBeUndefined(); // only the owner sees their settings
    }
  });

  it("contacts-only fields are shown to contacts, not strangers", async () => {
    await alice.req("PUT", "/v1/users/me/privacy", { birthday: "contacts", bio: "contacts" });
    const toBob = (await bob.req("GET", `/v1/users/${alice.userId}`)).user;
    const toEve = (await eve.req("GET", `/v1/users/${alice.userId}`)).user;
    expect(toBob.birthday).toBe("1994-03-21");
    expect(toBob.statusText).toBe("Working on Orbit");
    expect(toEve.birthday).toBeUndefined();
    expect(toEve.statusText).toBeNull();
    // Lists and search show only what's public to everyone.
    const search = (await bob.req("GET", `/v1/users/search?q=${alice.username}`)).users;
    expect(search[0].statusText).toBeNull();
    await alice.req("PUT", "/v1/users/me/privacy", { bio: "everyone" });
  });

  it("someone the person blocked sees only the basics", async () => {
    await alice.req("POST", "/v1/blocks", { userId: bob.userId });
    const u = (await bob.req("GET", `/v1/users/${alice.userId}`)).user;
    expect(Object.keys(u).filter((k) => !["id", "username", "displayName", "avatarColor", "homeServer", "statusText", "identityKey", "fingerprint", "online", "lastSeenAt"].includes(k))).toEqual([]);
    expect(u.statusText).toBeNull();
    expect(u.online).toBeNull();
    await alice.req("DELETE", `/v1/blocks/${bob.userId}`);
  });

  it("validates fields and clears them with null", async () => {
    await expectStatus(alice.req("PATCH", "/v1/users/me", { birthday: "1994-02-30" }), 400);
    await expectStatus(alice.req("PATCH", "/v1/users/me", { birthday: "2999-01-01" }), 400);
    await expectStatus(alice.req("PATCH", "/v1/users/me", { phone: "01712345678" }), 400); // no country code
    await expectStatus(alice.req("PUT", "/v1/users/me/privacy", { birthday: "friends" }), 400);
    await expectStatus(alice.req("PUT", "/v1/users/me/privacy", { secret: "everyone" }), 400);
    expect((await alice.req("PATCH", "/v1/users/me", { birthday: "--02-29" })).user.birthday).toBe("--02-29"); // no year
    const cleared = (await alice.req("PATCH", "/v1/users/me", { lastName: null })).user;
    expect(cleared.lastName).toBeUndefined();
    expect(cleared).toMatchObject({ firstName: "Alice", phone: "+8801712345678", statusText: "Working on Orbit" }); // untouched fields stay
  });

  it("usernames change once an hour and stay unique", async () => {
    await expectStatus(eve.req("PATCH", "/v1/users/me", { username: alice.username }), 409);
    await expectStatus(eve.req("PATCH", "/v1/users/me", { username: "x" }), 400);
    expect((await eve.req("PATCH", "/v1/users/me", { username: `${eve.username}.k` })).user.username).toBe(`${eve.username}.k`);
    await expectStatus(eve.req("PATCH", "/v1/users/me", { username: `${eve.username}.k2` }), 429);
    expect((await eve.req("PATCH", "/v1/users/me", { username: `${eve.username}.K`.toUpperCase(), displayName: "Eve" })).user.displayName).toBe("Eve"); // same name, other case: not a change
  });
});

describe("profile photo", () => {
  it("accepts a JPEG up to 512 KB and serves it to its audience", async () => {
    await expectStatus(alice.req("PUT", "/v1/users/me/photo", undefined, Buffer.from("not a jpeg")), 400);
    await expectStatus(alice.req("PUT", "/v1/users/me/photo", undefined, Buffer.concat([jpeg, Buffer.alloc(600 * 1024)])), 413);
    const { photoRev } = await alice.req("PUT", "/v1/users/me/photo", undefined, jpeg);
    expect(photoRev).toBeGreaterThan(0);
    expect(Buffer.compare(await eve.req("GET", `/v1/users/${alice.userId}/photo`), jpeg)).toBe(0);
    expect((await eve.req("GET", `/v1/users/${alice.userId}`)).user.photoRev).toBe(photoRev);

    await alice.req("PUT", "/v1/users/me/privacy", { photo: "contacts" });
    await expectStatus(eve.req("GET", `/v1/users/${alice.userId}/photo`), 404); // same as "no photo"
    expect((await eve.req("GET", `/v1/users/${alice.userId}`)).user.photoRev).toBeUndefined();
    expect(Buffer.compare(await bob.req("GET", `/v1/users/${alice.userId}/photo`), jpeg)).toBe(0);

    await alice.req("DELETE", "/v1/users/me/photo");
    await expectStatus(bob.req("GET", `/v1/users/${alice.userId}/photo`), 404);
  });
});

describe("discovery and last seen", () => {
  it("nobody finds someone by phone unless they allow it, and the answer doesn't reveal the number", async () => {
    await expectStatus(eve.req("GET", "/v1/users/by-phone?phone=%2B8801712345678"), 404);
    await expectStatus(eve.req("GET", "/v1/users/by-phone?phone=%2B8801999999999"), 404);
    await alice.req("PUT", "/v1/users/me/privacy", { findByPhone: "everyone" });
    expect((await eve.req("GET", "/v1/users/by-phone?phone=%2B880%201712-345678")).user.id).toBe(alice.userId);
    await alice.req("PUT", "/v1/users/me/privacy", { findByPhone: "contacts" });
    await expectStatus(eve.req("GET", "/v1/users/by-phone?phone=%2B8801712345678"), 404);
    expect((await bob.req("GET", "/v1/users/by-phone?phone=%2B8801712345678")).user.id).toBe(alice.userId);
  });

  it("someone who limits username search isn't found by strangers", async () => {
    expect((await eve.req("GET", `/v1/users/search?q=${alice.username}`)).users.map((u: any) => u.id)).toContain(alice.userId);
    await alice.req("PUT", "/v1/users/me/privacy", { findByUsername: "contacts" });
    expect((await eve.req("GET", `/v1/users/search?q=${alice.username}`)).users).toEqual([]);
    expect((await bob.req("GET", `/v1/users/search?q=${alice.username}`)).users.map((u: any) => u.id)).toContain(alice.userId);
    await alice.req("PUT", "/v1/users/me/privacy", { findByUsername: "everyone" });
  });

  it("last seen follows its setting", async () => {
    await alice.connect();
    const seen = async (v: GriipClient) => (await v.req("GET", `/v1/presence?ids=${alice.userId}`)).presence.map((p: any) => p.userId);
    expect(await seen(bob)).toEqual([alice.userId]); // contacts by default
    expect(await seen(eve)).toEqual([]);
    await alice.req("PUT", "/v1/users/me/privacy", { lastSeen: "nobody" });
    expect(await seen(bob)).toEqual([]);
    expect((await bob.req("GET", `/v1/users/${alice.userId}`)).user.online).toBeNull();
    await alice.req("PUT", "/v1/users/me/privacy", { lastSeen: "everyone" });
    expect(await seen(eve)).toEqual([alice.userId]);
  });

  it("conversation member lists and live presence follow the setting too", async () => {
    const dm = (await bob.req("POST", "/v1/conversations/dm", { userId: alice.userId })).conversation;
    const online = async () => (await bob.req("GET", `/v1/conversations/${dm.id}/members`)).members.find((m: any) => m.id === alice.userId).online;
    expect(await online()).toBe(true);
    await alice.req("PUT", "/v1/users/me/privacy", { lastSeen: "nobody", photo: "contacts" });
    expect(await online()).toBeNull();
    await bob.connect();
    const before = bob.events.filter((e) => e.t === "presence" && e.userId === alice.userId).length;
    await alice.disconnect();
    await new Promise((r) => setTimeout(r, 300));
    expect(bob.events.filter((e) => e.t === "presence" && e.userId === alice.userId).length).toBe(before); // not announced
    await alice.req("PUT", "/v1/users/me/privacy", { lastSeen: "contacts" });
  });
});
