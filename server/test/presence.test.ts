import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { GriipClient } from "./support/griipClient.js";
import { type Harness, startServer } from "./support/harness.js";

let h: Harness;
let target: GriipClient, stranger: GriipClient, dmPeer: GriipClient, colleague: GriipClient, guest: GriipClient;
let org: string;

beforeAll(async () => {
  h = await startServer();
  target = await h.client("prtarget");
  stranger = await h.client("prstranger");
  dmPeer = await h.client("prpeer");
  colleague = await h.client("prcolleague");
  guest = await h.client("prguest");
  await dmPeer.req("POST", "/v1/conversations/dm", { userId: target.userId });
  org = (await target.req("POST", "/v1/orgs", { name: "Presence Co" })).org.id;
  for (const [c, role] of [
    [colleague, "MEMBER"],
    [guest, "GUEST"],
  ] as const) {
    const code = (await target.req("POST", `/v1/orgs/${org}/invites`, { role })).invite.code;
    await c.req("POST", "/v1/invites/accept", { code });
  }
  await target.connect(); // online from here on
});
afterAll(async () => h.close());

const view = async (who: GriipClient) => {
  const u = (await who.req("GET", `/v1/users/${target.userId}`)).user;
  const p = (await who.req("GET", `/v1/presence?ids=${target.userId}`)).presence;
  const s = (await who.req("GET", `/v1/users/search?q=${target.username}`)).users.find((x: any) => x.id === target.userId);
  return { online: u.online, lastSeenAt: u.lastSeenAt, presenceListed: p.length > 0, searchOnline: s?.online ?? null };
};

describe("presence privacy (T1.7)", () => {
  it("a stranger who knows the username learns nothing about online state", async () => {
    const v = await view(stranger);
    expect(v).toEqual({ online: null, lastSeenAt: null, presenceListed: false, searchOnline: null });
    // The directory itself still works: the profile and its identity key are returned.
    expect((await stranger.req("GET", `/v1/users/${target.userId}`)).user.identityKey).toBeTruthy();
  });

  it("a DM peer and a non-guest org colleague see it; the person sees themself", async () => {
    for (const who of [dmPeer, colleague, target]) {
      const v = await view(who);
      expect(v.online).toBe(true);
      expect(v.presenceListed).toBe(true);
    }
  });

  it("a guest sees presence only after sharing a conversation", async () => {
    expect((await view(guest)).online).toBeNull();
    const channel = (await target.req("POST", `/v1/orgs/${org}/channels`, { name: "visitors", scope: "ORG", kind: "PRIVATE", memberIds: [guest.userId] })).channel.id;
    expect(channel).toBeTruthy();
    expect((await view(guest)).online).toBe(true);
  });

  it("realtime presence events reach conversation peers, not strangers", async () => {
    await dmPeer.connect();
    await stranger.connect();
    await target.disconnect();
    await dmPeer.waitFor(() => dmPeer.events.find((e) => e.t === "presence" && e.userId === target.userId && e.online === false));
    await new Promise((r) => setTimeout(r, 100));
    expect(stranger.events.some((e) => e.t === "presence" && e.userId === target.userId)).toBe(false);
    await dmPeer.disconnect();
    await stranger.disconnect();
  });
});
