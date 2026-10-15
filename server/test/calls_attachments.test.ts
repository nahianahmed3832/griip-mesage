import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { runCleanup } from "../src/jobs.js";
import type { GriipClient } from "./support/griipClient.js";
import { expectStatus, type Harness, startServer } from "./support/harness.js";

let h: Harness;
let alice: GriipClient, bob: GriipClient, eve: GriipClient;
let dmId: string;

function decodeJwt(token: string) {
  return JSON.parse(Buffer.from(token.split(".")[1], "base64url").toString());
}

beforeAll(async () => {
  h = await startServer({ RING_TIMEOUT_SECONDS: "1" });
  alice = await h.client("alice");
  bob = await h.client("bob");
  eve = await h.client("eve");
  dmId = (await alice.req("POST", "/v1/conversations/dm", { userId: bob.userId })).conversation.id;
  await bob.connect();
});
afterAll(async () => h.close());

describe("call signalling & media tokens", () => {
  it("issues a short-lived token scoped to one room and rings the callee", async () => {
    const r = await alice.req("POST", "/v1/calls", { conversationId: dmId, video: true });
    expect(r.call.state).toBe("RINGING");
    const claims = decodeJwt(r.media.token);
    expect(claims.video.room).toBe(r.media.room);
    expect(claims.video.roomJoin).toBe(true);
    expect(claims.sub).toBe(`${alice.userId}.${alice.deviceId}`);
    expect(claims.exp - claims.nbf).toBeLessThanOrEqual(600);
    const ring = await bob.waitFor(() => bob.events.find((e) => e.t === "call" && e.event === "ring" && e.call.id === r.call.id));
    expect(ring.from.userId).toBe(alice.userId);
    // Non-members can neither see nor join
    await expectStatus(eve.req("POST", `/v1/calls/${r.call.id}/join`), 404);
    await expectStatus(eve.req("POST", "/v1/calls", { conversationId: dmId }), 404);
    // Callee joins → separate identity, same room
    const j = await bob.req("POST", `/v1/calls/${r.call.id}/join`);
    expect(decodeJwt(j.media.token).video.room).toBe(r.media.room);
    await alice.req("POST", `/v1/calls/${r.call.id}/connected`);
    const c = await bob.req("POST", `/v1/calls/${r.call.id}/connected`);
    expect(c.call.state).toBe("ACTIVE");
    const ended = await bob.req("POST", `/v1/calls/${r.call.id}/end`);
    expect(ended.call.state).toBe("ENDED");
    await bob.waitFor(() => bob.events.find((e) => e.t === "call" && e.event === "ended" && e.call.id === r.call.id));
    await expectStatus(bob.req("POST", `/v1/calls/${r.call.id}/join`), 410);
  });

  it("declining a 1:1 call ends it; unanswered calls become missed", async () => {
    const r = await alice.req("POST", "/v1/calls", { conversationId: dmId, video: false });
    const d = await bob.req("POST", `/v1/calls/${r.call.id}/decline`);
    expect(d.call.state).toBe("ENDED");
    expect(d.call.endReason).toBe("declined");
    const r2 = await alice.req("POST", "/v1/calls", { conversationId: dmId });
    await new Promise((res) => setTimeout(res, 1100));
    await runCleanup(h.ctx);
    const after = await alice.req("GET", `/v1/calls/${r2.call.id}`);
    expect(after.call.state).toBe("ENDED");
    expect(after.call.endReason).toBe("missed");
  });

  it("group calls: one live call per conversation, starting again joins it", async () => {
    const g = (await alice.req("POST", "/v1/conversations/group", { name: "Standup", memberIds: [bob.userId] })).conversation;
    const a = await alice.req("POST", "/v1/calls", { conversationId: g.id, video: true });
    const b = await bob.req("POST", "/v1/calls", { conversationId: g.id, video: true });
    expect(b.joinedExisting).toBe(true);
    expect(b.call.id).toBe(a.call.id);
    // Only the starter/manager can end it for everyone
    await expectStatus(bob.req("POST", `/v1/calls/${a.call.id}/end`), 403);
    await bob.req("POST", `/v1/calls/${a.call.id}/leave`);
    await alice.req("POST", `/v1/calls/${a.call.id}/end`);
  });
});

describe("encrypted attachments", () => {
  it("stores opaque bytes, authorises downloads per conversation, and expires", async () => {
    const blob = crypto.randomBytes(200_000); // stands in for client-side AES-GCM ciphertext
    const up = await alice.req("POST", `/v1/attachments?conversationId=${dmId}`, undefined, blob);
    expect(up.id).toMatch(/^a_[A-Za-z0-9_-]{20,}$/);
    const down: Buffer = await bob.req("GET", `/v1/attachments/${up.id}`);
    expect(Buffer.compare(down, blob)).toBe(0);
    await expectStatus(eve.req("GET", `/v1/attachments/${up.id}`), 404);
    await expectStatus(eve.req("POST", `/v1/attachments?conversationId=${dmId}`, undefined, blob), 404);
    await expectStatus(bob.req("GET", `/v1/attachments/a_${crypto.randomBytes(16).toString("base64url")}`), 404);
    // Expire it
    h.ctx.db.prepare("UPDATE attachments SET expires_at = ? WHERE id = ?").run(Date.now() - 1, up.id);
    await expectStatus(bob.req("GET", `/v1/attachments/${up.id}`), 404);
    const res = await runCleanup(h.ctx);
    expect(res.attachments).toBe(1);
    expect(fs.existsSync(path.join(h.ctx.config.attachmentDir, up.id))).toBe(false);
  });

  it("enforces the size limit", async () => {
    const h2 = await startServer({ ATTACHMENT_MAX_BYTES: "1000" });
    try {
      const a = await h2.client("sizea");
      const b = await h2.client("sizeb");
      const dm = (await a.req("POST", "/v1/conversations/dm", { userId: b.userId })).conversation.id;
      await expectStatus(a.req("POST", `/v1/attachments?conversationId=${dm}`, undefined, crypto.randomBytes(5000)), 413);
    } finally {
      await h2.close();
    }
  });
});
