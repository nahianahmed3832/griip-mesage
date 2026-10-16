import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { GriipClient } from "./support/griipClient.js";
import { type Harness, startServer } from "./support/harness.js";

/**
 * T2.1 / probe P6: with 105 items queued for an offline device, a message sent while the device is
 * still receiving its backlog must neither overtake items 101–105 nor arrive twice.
 */
let h: Harness;
let alice: GriipClient, bob: GriipClient;
let dm: string;

beforeAll(async () => {
  h = await startServer();
  alice = await h.client("ordalice");
  bob = await h.client("ordbob");
  dm = (await alice.req("POST", "/v1/conversations/dm", { userId: bob.userId })).conversation.id;
});
afterAll(async () => h.close());

/** Every queue item id Bob received, in arrival order, across live pushes and backlog batches. */
const arrived = (c: GriipClient) => c.events.filter((e) => e.t === "queue").flatMap((e) => e.items.map((i: any) => i.id as number));

describe("ordered, exactly-once delivery (T2.1, P6)", () => {
  it("a live message waits for the backlog and arrives once, in queue order", async () => {
    for (let i = 1; i <= 105; i++) await alice.send(dm, { type: "text", text: `backlog ${i}` });
    bob.autoAck = false; // hold the first batch unacknowledged, like a slow phone
    await bob.connect();
    const first = await bob.waitFor(() => bob.events.find((e) => e.t === "queue" && e.backlog));
    expect(first.items).toHaveLength(100);
    expect(first.more).toBe(true);

    await alice.send(dm, { type: "text", text: "live while flushing" }); // must not be pushed now
    await new Promise((r) => setTimeout(r, 150));
    expect(bob.events.filter((e) => e.t === "queue")).toHaveLength(1);

    bob.ws!.send(JSON.stringify({ t: "ack", ids: first.items.map((i: any) => i.id), backlog: true }));
    await bob.waitFor(() => bob.events.find((e) => e.t === "queue.drained"));
    const ids = arrived(bob);
    expect(ids).toHaveLength(106);
    expect(new Set(ids).size).toBe(106);
    expect([...ids].sort((a, b) => a - b)).toEqual(ids);
    // On the wire, the live message is the very last item (the test client's `received` list is filled
    // by concurrent handlers, so it isn't an order record).
    const live = await bob.waitFor(() => bob.received.find((m) => m.payload.text === "live while flushing"));
    const lastItem = bob.events.filter((e) => e.t === "queue").flatMap((e) => e.items).at(-1);
    expect(lastItem.messageId).toBe(live.messageId);

    // After the backlog has drained, new messages are pushed live again.
    bob.autoAck = true;
    bob.ws!.send(JSON.stringify({ t: "ack", ids: ids.slice(100), backlog: false }));
    await alice.send(dm, { type: "text", text: "after drain" });
    await bob.waitFor(() => bob.received.find((m) => m.payload.text === "after drain"));
    expect(new Set(arrived(bob)).size).toBe(arrived(bob).length);
    await bob.disconnect();
  });

  it("a burst of messages to a connected device arrives complete and in order", async () => {
    await bob.connect();
    await bob.waitFor(() => bob.events.find((e) => e.t === "queue.drained"));
    const before = bob.received.length;
    await Promise.all(Array.from({ length: 60 }, (_, i) => alice.send(dm, { type: "text", text: `burst ${i}` })));
    await bob.waitFor(() => bob.received.length >= before + 60, 15_000);
    const ids = arrived(bob);
    expect(new Set(ids).size).toBe(ids.length);
    await bob.disconnect();
  });
});
