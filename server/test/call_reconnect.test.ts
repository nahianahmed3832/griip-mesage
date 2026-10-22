import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { LiveKitService } from "../src/modules/calls/livekit.js";
import type { GriipClient } from "./support/griipClient.js";
import { type Harness, startServer } from "./support/harness.js";

/**
 * 1:1 calls survive a dropped connection (2026-10-17): LiveKit reporting that one person left doesn't end the call at
 * once; it waits CALL_PEER_GRACE_MS for them to come back, then ends it as "lost". Hanging up still ends it at once.
 */
class FakeLiveKit {
  publicUrl = "ws://livekit.test";
  async token(p: { identity: string }) { return `token:${p.identity}`; }
  async deleteRoom() {}
  async participantIdentities() { return []; }
  async participantUsers() { return new Set<string>(); }
  async removeParticipant() { return "absent" as const; }
  async receiveWebhook(body: string) { return JSON.parse(body); }
}

let h: Harness;
let alice: GriipClient, bob: GriipClient;
let dmId: string;

beforeAll(async () => {
  h = await startServer({ CALL_PEER_GRACE_MS: "300" });
  alice = await h.client("alice");
  bob = await h.client("bob");
  dmId = (await alice.req("POST", "/v1/conversations/dm", { userId: bob.userId })).conversation.id;
});
afterAll(async () => h.close());
beforeEach(() => {
  h.ctx.livekit = new FakeLiveKit() as unknown as LiveKitService;
});

const hook = (event: string, room: string, c: GriipClient) =>
  fetch(`${h.baseUrl}/v1/livekit/webhook`, {
    method: "POST",
    headers: { "content-type": "application/webhook+json", authorization: "stub" },
    body: JSON.stringify({ event, room: { name: room }, participant: { identity: `${c.userId}.${c.deviceId}` } }),
  });
const state = async (id: string) => (await alice.req("GET", `/v1/calls/${id}`)).call;
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function activeCall() {
  const r = await alice.req("POST", "/v1/calls", { conversationId: dmId, video: false });
  await alice.req("POST", `/v1/calls/${r.call.id}/connected`);
  await bob.req("POST", `/v1/calls/${r.call.id}/connected`);
  expect((await state(r.call.id)).state).toBe("ACTIVE");
  return { id: r.call.id as string, room: r.media.room as string };
}

describe("1:1 call reconnection", () => {
  it("a dropped connection that comes back keeps the call going", async () => {
    const c = await activeCall();
    await hook("participant_left", c.room, bob);
    expect((await state(c.id)).state).toBe("ACTIVE"); // not ended at once
    await hook("participant_joined", c.room, bob); // back within the window
    await sleep(500);
    expect((await state(c.id)).state).toBe("ACTIVE");
    await alice.req("POST", `/v1/calls/${c.id}/end`);
  });

  it("someone who doesn't come back ends the call as lost, with its real duration", async () => {
    const c = await activeCall();
    await hook("participant_left", c.room, bob);
    await sleep(500);
    const ended = await state(c.id);
    expect(ended.state).toBe("ENDED");
    expect(ended.endReason).toBe("lost");
    expect(ended.answeredAt).toBeGreaterThan(0);
  });

  it("hanging up still ends the call at once", async () => {
    const c = await activeCall();
    await bob.req("POST", `/v1/calls/${c.id}/end`);
    expect((await state(c.id)).endReason).toBe("hangup");
  });

  it("when both are gone the call ends at once as empty", async () => {
    const c = await activeCall();
    await hook("participant_left", c.room, bob);
    await hook("participant_left", c.room, alice);
    expect((await state(c.id)).endReason).toBe("empty");
  });
});
