import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { canonical, type GriipClient, IdentityChangedError, newEd25519, newX25519, sign } from "./support/griipClient.js";
import { type Harness, startServer } from "./support/harness.js";

/**
 * T1.2 against a malicious relay: the server swaps Bob's identity key and device keys for keys it
 * controls ("Mallory"), with a valid certificate signed by the swapped identity. A client that pinned
 * Bob's original identity must neither encrypt to the swapped device nor accept messages from it,
 * until it explicitly accepts the new key; then held messages open and sending resumes.
 */
let h: Harness;
let alice: GriipClient, bob: GriipClient;
let dm: string;
const b64 = (b: Buffer) => b.toString("base64");

beforeAll(async () => {
  h = await startServer();
  alice = await h.client("pinalice");
  bob = await h.client("pinbob");
  dm = (await alice.req("POST", "/v1/conversations/dm", { userId: bob.userId })).conversation.id;
  await alice.send(dm, { type: "text", text: "hi Bob" }); // Alice pins Bob's identity here
  expect(alice.pins.get(bob.userId)).toBe(b64(bob.identity.pub));
});
afterAll(async () => h.close());

const queued = () => (h.ctx.db.prepare("SELECT COUNT(*) n FROM delivery_queue").get() as { n: number }).n;

describe("pinned identities against a key-substituting relay (T1.2)", () => {
  const mallory = { identity: newEd25519(), device: newEd25519(), enc: newX25519() };

  it("the relay swaps Bob's keys: Alice refuses to encrypt to them and posts nothing", async () => {
    const identityKey = b64(mallory.identity.pub);
    const cert = sign(mallory.identity, canonical.deviceCert(identityKey, b64(mallory.device.pub), b64(mallory.enc.pub)));
    h.ctx.db.prepare("UPDATE users SET identity_key = ? WHERE id = ?").run(identityKey, bob.userId);
    h.ctx.db
      .prepare("UPDATE devices SET signing_key = ?, encryption_key = ?, certificate = ? WHERE id = ?")
      .run(b64(mallory.device.pub), b64(mallory.enc.pub), cert, bob.deviceId);
    alice.clearKeyCache();

    const before = queued();
    const err = await alice.send(dm, { type: "text", text: "salary figures" }).catch((e) => e);
    expect(err).toBeInstanceOf(IdentityChangedError);
    expect((err as IdentityChangedError).userIds).toEqual([bob.userId]);
    expect(queued()).toBe(before);
  });

  it("a message signed by the swapped device is held, not shown as Bob's", async () => {
    // The relay controls Bob's account now: it signs as the swapped device with a valid session.
    bob.identity = mallory.identity;
    bob.device = mallory.device;
    bob.enc = mallory.enc;
    bob.pins.set(alice.userId, b64(alice.identity.pub));
    await alice.connect();
    await bob.send(dm, { type: "text", text: "send me the files" });
    await alice.waitFor(() => alice.events.find((e) => e.t === "identity_changed"));
    expect(alice.received.some((m) => m.payload?.text === "send me the files")).toBe(false);
    expect(alice.held).toHaveLength(1);
  });

  it("after Alice accepts the key she compared, held messages open and sending resumes", async () => {
    await alice.acceptIdentity(bob.userId, b64(mallory.identity.pub));
    expect(alice.received.some((m) => m.payload?.text === "send me the files")).toBe(true);
    expect(alice.held).toHaveLength(0);
    await expect(alice.send(dm, { type: "text", text: "ok" })).resolves.toBeTruthy();
    await alice.disconnect();
  });

  it("accepting one key doesn't trust a second swap", async () => {
    const other = newEd25519();
    h.ctx.db.prepare("UPDATE users SET identity_key = ? WHERE id = ?").run(b64(other.pub), bob.userId);
    alice.clearKeyCache();
    await expect(alice.send(dm, { type: "text", text: "again?" })).rejects.toBeInstanceOf(IdentityChangedError);
  });
});
