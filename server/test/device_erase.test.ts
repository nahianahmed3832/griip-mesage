import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { GriipClient } from "./support/griipClient.js";
import { expectStatus, type Harness, startServer } from "./support/harness.js";

/** T1.9: "Erase this device" revokes the device first, so contacts stop encrypting to keys that are gone. */
let h: Harness;
let alice: GriipClient, bob: GriipClient;
let dm: string;

beforeAll(async () => {
  h = await startServer();
  alice = await h.client("erasealice");
  bob = await h.client("erasebob");
  dm = (await alice.req("POST", "/v1/conversations/dm", { userId: bob.userId })).conversation.id;
});
afterAll(async () => h.close());

describe("a device revoking itself before erasing (T1.9)", () => {
  it("drops out of contacts' recipient lists, loses its queue and can't sign in again", async () => {
    await alice.send(dm, { type: "text", text: "queued for Bob" });
    const queuedForBob = () => (h.ctx.db.prepare("SELECT COUNT(*) n FROM delivery_queue WHERE recipient_device_id = ?").get(bob.deviceId) as { n: number }).n;
    expect(queuedForBob()).toBe(1);

    await bob.req("DELETE", `/v1/devices/${bob.deviceId}`);

    const keys = (await alice.req("GET", `/v1/conversations/${dm}/devices`)).users.find((u: any) => u.userId === bob.userId);
    expect(keys.devices).toEqual([]);
    expect(queuedForBob()).toBe(0);
    await expectStatus(bob.req("GET", "/v1/conversations"), 401);
    await expectStatus(bob.login(), 401);
  });
});
