import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { GriipClient } from "./support/griipClient.js";
import { type Harness, startServer } from "./support/harness.js";

/**
 * Profile and settings changes reach other devices at once (the owner, 2026-10-23: a name or photo changed on the web
 * showed on the phone only after a reload). `user.updated` names whose profile changed; clients fetch it again.
 */
let h: Harness;
let ada: GriipClient, bob: GriipClient, stranger: GriipClient;

const updates = (c: GriipClient, userId: string) =>
  (c.events as Record<string, unknown>[]).filter((f) => f.t === "user.updated" && f.userId === userId).length;
const nextUpdate = (c: GriipClient, userId: string, before: number) => c.waitFor(() => updates(c, userId) > before);
/** Lets frames already on their way arrive, so "nothing came" means something. */
const settle = () => new Promise((r) => setTimeout(r, 300));

beforeAll(async () => {
  h = await startServer();
  [ada, bob, stranger] = await Promise.all(["ada", "bob", "stranger"].map((n) => h.client(n)));
  await ada.req("POST", "/v1/conversations/dm", { userId: bob.userId });
  for (const c of [ada, bob, stranger]) await c.connect();
});

afterAll(async () => h.close());

describe("live profile changes", () => {
  it("a new name or status reaches the person's own devices and the people they talk with, not strangers", async () => {
    const [a, b, s] = [updates(ada, ada.userId), updates(bob, ada.userId), updates(stranger, ada.userId)];
    await ada.req("PATCH", "/v1/users/me", { displayName: "Ada L.", statusText: "Writing" });
    await nextUpdate(ada, ada.userId, a);
    await nextUpdate(bob, ada.userId, b);
    await settle();
    expect(updates(stranger, ada.userId)).toBe(s);
  });

  it("a new or removed photo is announced", async () => {
    const b = updates(bob, ada.userId);
    await ada.req("DELETE", "/v1/users/me/photo");
    await nextUpdate(bob, ada.userId, b);
  });

  it("privacy changes are announced, since what others may see changed", async () => {
    const b = updates(bob, ada.userId);
    await ada.req("PUT", "/v1/users/me/privacy", { lastSeen: "nobody" });
    await nextUpdate(bob, ada.userId, b);
  });

  it("blocking and message settings reach only the person's own devices", async () => {
    const [a, b] = [updates(ada, ada.userId), updates(bob, ada.userId)];
    await ada.req("POST", "/v1/blocks", { userId: bob.userId });
    await nextUpdate(ada, ada.userId, a);
    await ada.req("DELETE", `/v1/blocks/${bob.userId}`);
    await ada.req("PUT", "/v1/users/me/messaging", { dmPolicy: "everyone" });
    await nextUpdate(ada, ada.userId, a + 2);
    await settle();
    expect(updates(bob, ada.userId)).toBe(b); // the blocked person is never told
  });
});
