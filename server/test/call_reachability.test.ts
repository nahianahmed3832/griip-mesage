import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { GriipClient } from "./support/griipClient.js";
import { expectStatus, type Harness, startServer } from "./support/harness.js";

/**
 * Call reachability (2026-10-16): the caller can tell "invite sent" from "recipient is ringing", a
 * recipient who wasn't connected at ring time can find the ringing call, and a busy recipient ends a
 * 1:1 ring with an explicit reason instead of leaving the caller waiting.
 */
let h: Harness;
let alice: GriipClient, bob: GriipClient, eve: GriipClient;
let dmId: string;

beforeAll(async () => {
  h = await startServer();
  alice = await h.client("alice");
  bob = await h.client("bob");
  eve = await h.client("eve");
  dmId = (await alice.req("POST", "/v1/conversations/dm", { userId: bob.userId })).conversation.id;
  await alice.connect();
});
afterAll(async () => h.close());

describe("call reachability", () => {
  it("a recipient that wasn't connected when the call rang finds it in /v1/calls/active", async () => {
    // bob has no socket: the live ring frame can't reach him.
    const r = await alice.req("POST", "/v1/calls", { conversationId: dmId, video: false });
    const active = await bob.req("GET", "/v1/calls/active");
    const found = active.calls.find((c: any) => c.id === r.call.id);
    expect(found?.state).toBe("RINGING");
    expect(found?.createdBy).toBe(alice.userId);
    expect(found?.alertedAt).toBeNull();
    await expectStatus(eve.req("GET", `/v1/calls/${r.call.id}`), 404); // not a member
    await alice.req("POST", `/v1/calls/${r.call.id}/end`);
  });

  it("the caller learns when a recipient device is actually ringing, once", async () => {
    const r = await alice.req("POST", "/v1/calls", { conversationId: dmId, video: true });
    await expectStatus(alice.req("POST", `/v1/calls/${r.call.id}/alerted`), 409); // the caller can't alert
    await expectStatus(eve.req("POST", `/v1/calls/${r.call.id}/alerted`), 404); // nor can a non-member
    const first = await bob.req("POST", `/v1/calls/${r.call.id}/alerted`);
    expect(first.call.alertedAt).toBeGreaterThan(0);
    const ev = await alice.waitFor(() => alice.events.find((e) => e.t === "call" && e.event === "alerted" && e.call.id === r.call.id));
    expect(ev.call.alertedAt).toBe(first.call.alertedAt);
    const again = await bob.req("POST", `/v1/calls/${r.call.id}/alerted`); // a second device or a retry
    expect(again.call.alertedAt).toBe(first.call.alertedAt);
    expect(alice.events.filter((e) => e.t === "call" && e.event === "alerted" && e.call.id === r.call.id)).toHaveLength(1);
    await alice.req("POST", `/v1/calls/${r.call.id}/end`);
    await expectStatus(bob.req("POST", `/v1/calls/${r.call.id}/alerted`), 410); // ended calls can't ring
  });

  it("a busy recipient ends a 1:1 ring with reason 'busy'; a plain decline stays 'declined'", async () => {
    const r = await alice.req("POST", "/v1/calls", { conversationId: dmId, video: false });
    const busy = await bob.req("POST", `/v1/calls/${r.call.id}/decline`, { reason: "busy" });
    expect(busy.call.state).toBe("ENDED");
    expect(busy.call.endReason).toBe("busy");
    const ended = await alice.waitFor(() => alice.events.find((e) => e.t === "call" && e.event === "ended" && e.call.id === r.call.id));
    expect(ended.call.endReason).toBe("busy");

    const r2 = await alice.req("POST", "/v1/calls", { conversationId: dmId, video: false });
    expect((await bob.req("POST", `/v1/calls/${r2.call.id}/decline`)).call.endReason).toBe("declined");
    await expectStatus(bob.req("POST", `/v1/calls/${r2.call.id}/decline`, { reason: "nonsense" }), 400);
  });

  it("decline and alert after the call ended are harmless (idempotent terminal state)", async () => {
    const r = await alice.req("POST", "/v1/calls", { conversationId: dmId, video: false });
    await alice.req("POST", `/v1/calls/${r.call.id}/end`);
    const late = await bob.req("POST", `/v1/calls/${r.call.id}/decline`, { reason: "busy" });
    expect(late.call.state).toBe("ENDED");
    expect(late.call.endReason).toBe("hangup"); // the first terminal reason wins
  });
});
