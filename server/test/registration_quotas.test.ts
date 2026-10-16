import crypto from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { createRegistrationInvite } from "../src/invites.js";
import { removeStalePartials } from "../src/routes/attachments.js";
import { GriipClient } from "./support/griipClient.js";
import { expectStatus, type Harness, startServer } from "./support/harness.js";

const MiB = 1024 * 1024;

describe("invite-only registration (T1.6)", () => {
  let h: Harness;
  beforeAll(async () => {
    h = await startServer({ REGISTRATION_MODE: "invite" });
  });
  afterAll(async () => h.close());

  const fresh = (name: string) => new GriipClient(h.baseUrl, `${name}${crypto.randomBytes(3).toString("hex")}`, name);
  const users = () => (h.ctx.db.prepare("SELECT COUNT(*) n FROM users").get() as { n: number }).n;
  const invite = (maxUses = 1, days = 1) => createRegistrationInvite(h.ctx.db, { maxUses, days, note: "test" });

  it("refuses registration without a code or with an unknown one, creating nothing", async () => {
    const before = users();
    expect((await expectStatus(fresh("nocode").register(), 403)).error.code).toBe("invite_required");
    expect((await expectStatus(fresh("badcode").register("ABCD-EFGH-JKMN"), 403)).error.code).toBe("invalid_invite");
    expect(users()).toBe(before);
  });

  it("a registration invite admits up to its use limit and stores only a hash of the code", async () => {
    const { id, code } = invite(2);
    await fresh("first").register(code);
    await fresh("second").register(code.toLowerCase().replace(/-/g, " ")); // typed loosely
    expect((await expectStatus(fresh("third").register(code), 403)).error.code).toBe("invalid_invite");
    const row = h.ctx.db.prepare("SELECT * FROM registration_invites WHERE id = ?").get(id) as Record<string, unknown>;
    expect(row.uses).toBe(2);
    expect(JSON.stringify(row)).not.toContain(code.replace(/-/g, ""));
  });

  it("expired and revoked registration invites are refused", async () => {
    const expired = invite();
    h.ctx.db.prepare("UPDATE registration_invites SET expires_at = ? WHERE id = ?").run(Date.now() - 1, expired.id);
    await expectStatus(fresh("late").register(expired.code), 403);
    const revoked = invite();
    h.ctx.db.prepare("UPDATE registration_invites SET revoked_at = ? WHERE id = ?").run(Date.now(), revoked.id);
    await expectStatus(fresh("revoked").register(revoked.code), 403);
  });

  it("an org invite registers the person and joins them to its org and team in one step", async () => {
    const owner = await fresh("owner").register(invite().code);
    const org = (await owner.req("POST", "/v1/orgs", { name: "Gate Co" })).org.id;
    const branch = (await owner.req("POST", `/v1/orgs/${org}/branches`, { name: "Dhaka" })).branch.id;
    const team = (await owner.req("POST", `/v1/orgs/${org}/teams`, { name: "Sales", branchId: branch })).team.id;
    const code = (await owner.req("POST", `/v1/orgs/${org}/invites`, { role: "MEMBER", teamId: team, maxUses: 1 })).invite.code;

    const newbie = await fresh("newbie").register(code);
    expect((await newbie.req("GET", "/v1/orgs")).orgs.map((o: any) => o.id)).toContain(org);
    expect((await owner.req("GET", `/v1/teams/${team}/members`)).members.map((m: any) => m.id)).toContain(newbie.userId);
    // The single use was consumed by the registration.
    await expectStatus(fresh("second").register(code), 403);
  });

  it("existing accounts keep signing in normally", async () => {
    const c = await fresh("returning").register(invite().code);
    c.token = "";
    await c.login();
    expect(c.token.length).toBeGreaterThan(20);
    expect((await c.req("GET", "/v1/conversations")).conversations).toEqual([]);
  });

  it("no HTTP route mints registration invites", () => {
    expect(h.app.printRoutes()).not.toMatch(/registration/i);
  });
});

describe("upload limits (T1.6)", () => {
  let h: Harness;
  let alice: GriipClient, bob: GriipClient, dave: GriipClient;
  let dm: string, dm2: string;

  beforeAll(async () => {
    h = await startServer({
      ATTACHMENT_MAX_BYTES: String(1 * MiB),
      ATTACHMENT_USER_DAILY_BYTES: String(3 * MiB),
      ATTACHMENT_USER_STORED_BYTES: String(10 * MiB),
      ATTACHMENT_TOTAL_BYTES: String(5 * MiB),
      ATTACHMENT_MAX_CONCURRENT_PER_USER: "1",
      ATTACHMENT_MAX_CONCURRENT: "4",
      ATTACHMENT_MAX_PENDING_BYTES: String(8 * MiB),
    });
    alice = await h.client("alice");
    bob = await h.client("bob");
    dave = await h.client("dave");
    dm = (await alice.req("POST", "/v1/conversations/dm", { userId: bob.userId })).conversation.id;
    dm2 = (await dave.req("POST", "/v1/conversations/dm", { userId: bob.userId })).conversation.id;
  });
  afterAll(async () => h.close());

  /** A raw streaming upload with chunked transfer encoding (no content-length), for size and concurrency tests. */
  const stream = (who: GriipClient, convId: string) => {
    const req = http.request(new URL(`/v1/attachments?conversationId=${convId}`, h.baseUrl), {
      method: "POST",
      agent: false, // a fresh connection: a refused upload can leave its kept-alive socket closed
      headers: { authorization: `Bearer ${who.token}`, "content-type": "application/octet-stream", "transfer-encoding": "chunked" },
    });
    const done = new Promise<{ status: number; body: any }>((resolve) => {
      req.on("response", (res) => {
        let d = "";
        res.on("data", (c) => (d += c));
        res.on("end", () => resolve({ status: res.statusCode!, body: d ? JSON.parse(d) : null }));
      });
      req.on("error", () => resolve({ status: 0, body: null })); // server closed after refusing
    });
    return { req, done };
  };
  const partials = () => fs.readdirSync(h.ctx.config.attachmentDir).filter((f) => f.endsWith(".part"));
  const blob = (n: number) => crypto.randomBytes(n);

  it("streams an upload of unknown length to disk and serves it back intact", async () => {
    const up = stream(alice, dm);
    const parts = [blob(200_000), blob(200_000), blob(200_000)];
    for (const p of parts) up.req.write(p);
    up.req.end();
    const r = await up.done;
    expect(r.status).toBe(200);
    expect(r.body.size).toBe(600_000);
    const down: Buffer = await bob.req("GET", `/v1/attachments/${r.body.id}`);
    expect(crypto.createHash("sha256").update(down).digest("hex")).toBe(crypto.createHash("sha256").update(Buffer.concat(parts)).digest("hex"));
  });

  it("refuses a body over the size limit, declared or streamed, and leaves no partial file", async () => {
    expect((await expectStatus(alice.req("POST", `/v1/attachments?conversationId=${dm}`, undefined, blob(MiB + 1)), 413)).error.code).toBe("too_large");
    const up = stream(alice, dm);
    for (let i = 0; i < 6; i++) up.req.write(blob(200_000)); // 1.2 MB, past the 1 MiB limit mid-stream
    up.req.end();
    const r = await up.done;
    expect([413, 0]).toContain(r.status);
    await new Promise((res) => setTimeout(res, 50));
    expect(partials()).toEqual([]);
  });

  it("limits concurrent uploads per user", async () => {
    const slow = stream(dave, dm2);
    slow.req.write(blob(1000));
    await dave.waitFor(() => partials().length > 0); // its .part file exists once the server has counted it in flight
    const second = await expectStatus(dave.req("POST", `/v1/attachments?conversationId=${dm2}`, undefined, blob(1000)), 429);
    expect(second.error.code).toBe("too_many_uploads");
    slow.req.end(blob(1000));
    expect((await slow.done).status).toBe(200);
    // With the first finished, a new upload goes through.
    await dave.req("POST", `/v1/attachments?conversationId=${dm2}`, undefined, blob(1000));
  });

  it("enforces the per-user daily quota", async () => {
    // alice has 600 kB today; two 1 MiB uploads fit in 3 MiB, a third doesn't.
    await alice.req("POST", `/v1/attachments?conversationId=${dm}`, undefined, blob(MiB));
    await alice.req("POST", `/v1/attachments?conversationId=${dm}`, undefined, blob(MiB));
    const e = await expectStatus(alice.req("POST", `/v1/attachments?conversationId=${dm}`, undefined, blob(MiB)), 429);
    expect(e.error.code).toBe("quota_exceeded");
  });

  it("enforces the server-wide storage cap", async () => {
    // About 2.6 MiB is stored. Bob's first two 1 MiB uploads fit under 5 MiB, the third doesn't.
    await bob.req("POST", `/v1/attachments?conversationId=${dm}`, undefined, blob(MiB));
    await bob.req("POST", `/v1/attachments?conversationId=${dm}`, undefined, blob(MiB));
    const e = await expectStatus(bob.req("POST", `/v1/attachments?conversationId=${dm}`, undefined, blob(MiB)), 507);
    expect(e.error.code).toBe("storage_full");
  });

  it("removes partial files left by uploads interrupted by a restart", () => {
    const dir = h.ctx.config.attachmentDir;
    fs.writeFileSync(path.join(dir, "a_interrupted.part"), "x");
    const kept = fs.readdirSync(dir).filter((f) => !f.endsWith(".part"));
    expect(removeStalePartials(dir)).toBe(1);
    expect(fs.readdirSync(dir).sort()).toEqual(kept.sort());
  });
});
