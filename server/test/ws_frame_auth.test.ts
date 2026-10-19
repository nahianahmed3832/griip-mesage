/**
 * Browsers can't set headers on a WebSocket, so a socket opened without an Authorization header may authenticate with
 * its first frame, {t:"auth", token} (docs/ORBIT_WEB_ARCHITECTURE.md › G1). Header authentication is unchanged.
 */
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import WebSocket from "ws";
import type { GriipClient } from "./support/griipClient.js";
import { type Harness, startServer } from "./support/harness.js";

let h: Harness;
let alice: GriipClient, bob: GriipClient;
let dm: string;

beforeAll(async () => {
  h = await startServer({ WS_AUTH_TIMEOUT_MS: "300" });
  alice = await h.client("wsalice");
  bob = await h.client("wsbob");
  dm = (await alice.req("POST", "/v1/conversations/dm", { userId: bob.userId })).conversation.id;
});
afterAll(async () => h.close());

interface Socket {
  ws: WebSocket;
  frames: any[];
  closed: Promise<{ code: number; reason: string }>;
  next: (pred: (f: any) => boolean, timeoutMs?: number) => Promise<any>;
}

/** A socket opened the way a browser opens one: no headers at all. */
function browserSocket(): Promise<Socket> {
  const ws = new WebSocket(h.baseUrl.replace(/^http/, "ws") + "/v1/ws");
  const frames: any[] = [];
  const closed = new Promise<{ code: number; reason: string }>((r) => ws.on("close", (code, reason) => r({ code, reason: reason.toString() })));
  ws.on("message", (d) => frames.push(JSON.parse(d.toString())));
  const next = async (pred: (f: any) => boolean, timeoutMs = 3000) => {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const f = frames.find(pred);
      if (f) return f;
      if (Date.now() > deadline) throw new Error("frame not received");
      await new Promise((r) => setTimeout(r, 20));
    }
  };
  return new Promise((resolve, reject) => {
    ws.once("open", () => resolve({ ws, frames, closed, next }));
    ws.once("error", reject);
  });
}

describe("WebSocket authentication with the first frame (browsers)", () => {
  it("a valid auth frame opens the session: hello, then queued envelopes, then acknowledgements work", async () => {
    await alice.send(dm, { type: "text", text: "queued before the browser connects" });
    const s = await browserSocket();
    s.ws.send(JSON.stringify({ t: "auth", token: bob.token }));
    const hello = await s.next((f) => f.t === "hello");
    expect(hello).toMatchObject({ userId: bob.userId, deviceId: bob.deviceId });
    const batch = await s.next((f) => f.t === "queue" && f.items.length > 0);
    expect(batch.items[0]).toMatchObject({ kind: "envelope", conversationId: dm, senderUserId: alice.userId });
    s.ws.send(JSON.stringify({ t: "ack", ids: batch.items.map((i: any) => i.id), backlog: true }));
    await s.next((f) => f.t === "queue.drained");
    // Acknowledged on the server: nothing is left in this device's queue.
    expect((await bob.req("GET", "/v1/queue")).items).toHaveLength(0);
    s.ws.close();
  });

  it("live envelopes reach an authenticated browser socket without a reconnect", async () => {
    const s = await browserSocket();
    s.ws.send(JSON.stringify({ t: "auth", token: bob.token }));
    await s.next((f) => f.t === "queue.drained");
    await alice.send(dm, { type: "text", text: "live" });
    const live = await s.next((f) => f.t === "queue" && !f.backlog && f.items.length > 0);
    expect(live.items[0].conversationId).toBe(dm);
    s.ws.close();
  });

  it("a wrong token closes the socket with 4401 and never says hello", async () => {
    const s = await browserSocket();
    s.ws.send(JSON.stringify({ t: "auth", token: "x".repeat(43) }));
    expect((await s.closed).code).toBe(4401);
    expect(s.frames.some((f) => f.t === "hello")).toBe(false);
  });

  it("a first frame that isn't auth closes the socket with 4401", async () => {
    const s = await browserSocket();
    s.ws.send(JSON.stringify({ t: "ping" }));
    expect((await s.closed).code).toBe(4401);
    expect(s.frames).toHaveLength(0);
  });

  it("a socket that doesn't authenticate in time is closed with 4401", async () => {
    const s = await browserSocket();
    expect((await s.closed).code).toBe(4401);
    expect(s.frames).toHaveLength(0);
  });

  it("a revoked device can't authenticate with its old token", async () => {
    const carol = await h.client("wscarol");
    const token = carol.token;
    await carol.req("DELETE", `/v1/devices/${carol.deviceId}`);
    const s = await browserSocket();
    s.ws.send(JSON.stringify({ t: "auth", token }));
    expect((await s.closed).code).toBe(4401);
  });

  it("header authentication (the Android app) still works unchanged", async () => {
    await bob.connect();
    expect(bob.events.some((e) => e.t === "hello" && e.deviceId === bob.deviceId)).toBe(true);
    await bob.disconnect();
  });
});
