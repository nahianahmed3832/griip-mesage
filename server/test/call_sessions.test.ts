import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { runCleanup } from "../src/jobs.js";
import type { LiveKitService } from "../src/livekit.js";
import type { GriipClient } from "./support/griipClient.js";
import { expectStatus, type Harness, startServer } from "./support/harness.js";

/**
 * Call sessions (UI-003): a device that rejoins replaces its old LiveKit session, and the old session's late "left"
 * webhook must not mark the device as gone; records LiveKit no longer has are cleaned up by the reconcile job; a call
 * has a participant cap that a reconnect never trips.
 */
class RoomsLiveKit {
  publicUrl = "ws://livekit.test";
  rooms = new Map<string, Set<string>>();
  unreachable = false;

  join(room: string, c: GriipClient) {
    if (!this.rooms.has(room)) this.rooms.set(room, new Set());
    this.rooms.get(room)!.add(`${c.userId}.${c.deviceId}`);
  }
  leave(room: string, c: GriipClient) {
    this.rooms.get(room)?.delete(`${c.userId}.${c.deviceId}`);
  }
  async token(p: { identity: string }) {
    return `token:${p.identity}`;
  }
  async deleteRoom(room: string) {
    this.rooms.delete(room);
  }
  async participantIdentities(room: string) {
    return this.unreachable ? null : [...(this.rooms.get(room) ?? [])];
  }
  async participantUsers(room: string) {
    const ids = await this.participantIdentities(room);
    return ids && new Set(ids.map((i) => i.split(".")[0]));
  }
  async removeParticipant(room: string, identity: string) {
    return this.rooms.get(room)?.delete(identity) ? ("removed" as const) : ("absent" as const);
  }
  async receiveWebhook(body: string) {
    return JSON.parse(body);
  }
}

const GRACE_MS = 200;
let h: Harness;
let lk: RoomsLiveKit;
let alice: GriipClient, bob: GriipClient, carol: GriipClient;
let dmId: string;
let groupId: string;

beforeAll(async () => {
  // The reconcile job looks at calls past the ring window; 0 lets it see a call started a moment ago.
  h = await startServer({ CALL_PEER_GRACE_MS: String(GRACE_MS), RING_TIMEOUT_SECONDS: "0", CALL_MAX_PARTICIPANTS: "2" });
  alice = await h.client("csalice");
  bob = await h.client("csbob");
  carol = await h.client("cscarol");
  dmId = (await alice.req("POST", "/v1/conversations/dm", { userId: bob.userId })).conversation.id;
  groupId = (await alice.req("POST", "/v1/conversations/group", { name: "Standup", memberIds: [bob.userId, carol.userId] })).conversation.id;
});
afterAll(async () => h.close());
beforeEach(() => {
  lk = new RoomsLiveKit();
  h.ctx.livekit = lk as unknown as LiveKitService;
});

const hook = (event: string, room: string, c: GriipClient) =>
  fetch(`${h.baseUrl}/v1/livekit/webhook`, {
    method: "POST",
    headers: { "content-type": "application/webhook+json", authorization: "stub" },
    body: JSON.stringify({ event, room: { name: room }, participant: { identity: `${c.userId}.${c.deviceId}` } }),
  });
const state = async (id: string) => (await alice.req("GET", `/v1/calls/${id}`)).call;
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function activeDm() {
  const r = await alice.req("POST", "/v1/calls", { conversationId: dmId, video: false });
  for (const c of [alice, bob]) {
    if (c !== alice) await c.req("POST", `/v1/calls/${r.call.id}/join`);
    lk.join(r.media.room, c);
    await c.req("POST", `/v1/calls/${r.call.id}/connected`);
  }
  expect((await state(r.call.id)).state).toBe("ACTIVE");
  return { id: r.call.id as string, room: r.media.room as string };
}

describe("call sessions (UI-003)", () => {
  it("a replaced session's late 'left' doesn't end a live 1:1 call", async () => {
    const c = await activeDm();
    // Bob's app reconnected: LiveKit has his identity again, then delivers the old session's "left".
    await hook("participant_left", c.room, bob);
    await sleep(GRACE_MS * 2);
    const s = await state(c.id);
    expect(s.state).toBe("ACTIVE");
    expect(s.participants.map((p: { userId: string }) => p.userId).sort()).toEqual([alice.userId, bob.userId].sort());
    await alice.req("POST", `/v1/calls/${c.id}/end`);
  });

  it("a real 'left' (LiveKit no longer has the device) still starts the reconnect grace", async () => {
    const c = await activeDm();
    lk.leave(c.room, bob);
    await hook("participant_left", c.room, bob);
    await sleep(GRACE_MS * 2);
    expect((await state(c.id)).endReason).toBe("lost");
  });

  it("the reconcile job clears a device whose 'left' was lost and ends the 1:1 call after the grace", async () => {
    const c = await activeDm();
    lk.leave(c.room, bob); // gone from LiveKit, but no webhook reached the server
    await sleep(GRACE_MS + 50); // longer than the grace since Bob connected
    const r = await runCleanup(h.ctx);
    expect(r.callStaleParticipants).toBe(1);
    expect((await state(c.id)).participants.map((p: { userId: string }) => p.userId)).toEqual([alice.userId]);
    await sleep(GRACE_MS * 2);
    expect((await state(c.id)).endReason).toBe("lost");
  });

  it("the reconcile job leaves records alone while LiveKit can't be reached", async () => {
    const c = await activeDm();
    lk.unreachable = true;
    await sleep(GRACE_MS + 50);
    const r = await runCleanup(h.ctx);
    expect(r.callStaleParticipants).toBe(0);
    expect((await state(c.id)).state).toBe("ACTIVE");
    lk.unreachable = false;
    await alice.req("POST", `/v1/calls/${c.id}/end`);
  });

  it("a full call refuses one more device with call_full, but lets a participant reconnect", async () => {
    const r = await alice.req("POST", "/v1/calls", { conversationId: groupId, video: false });
    lk.join(r.media.room, alice);
    await alice.req("POST", `/v1/calls/${r.call.id}/connected`);
    await bob.req("POST", `/v1/calls/${r.call.id}/join`);
    lk.join(r.media.room, bob);
    await bob.req("POST", `/v1/calls/${r.call.id}/connected`);
    // CALL_MAX_PARTICIPANTS=2 here: Carol would be the third.
    const full = await expectStatus(carol.req("POST", `/v1/calls/${r.call.id}/join`), 409);
    expect(full.error.code).toBe("call_full");
    // Starting a call in that conversation joins the running one, so it's refused the same way.
    await expectStatus(carol.req("POST", "/v1/calls", { conversationId: groupId, video: false }), 409);
    // Bob's reconnect (same device) isn't counted against the cap.
    const again = await bob.req("POST", `/v1/calls/${r.call.id}/join`);
    expect(again.media.token).toContain(bob.userId);
    await alice.req("POST", `/v1/calls/${r.call.id}/end`);
  });
});
