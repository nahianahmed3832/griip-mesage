import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { runCleanup } from "../src/modules/administration/jobs.js";
import { type Harness, startServer } from "./support/harness.js";

let h: Harness;
beforeAll(async () => {
  h = await startServer();
});
afterAll(async () => h.close());

describe("operations hygiene (Tier 7)", () => {
  it("the public health check reveals no usage numbers (D28)", async () => {
    const r = await (await fetch(`${h.baseUrl}/healthz`)).json();
    expect(r.ok).toBe(true);
    expect(r).not.toHaveProperty("onlineDevices");
  });

  it("the database overwrites deleted rows (D17)", () => {
    expect(h.ctx.db.pragma("secure_delete", { simple: true })).toBe(1);
  });

  it("prunes ended calls past retention and old audit rows, keeping recent and live ones (D16)", async () => {
    const a = await h.client("hygalice");
    const b = await h.client("hygbob");
    const dm = (await a.req("POST", "/v1/conversations/dm", { userId: b.userId })).conversation.id;
    const day = 86_400_000;
    const ins = h.ctx.db.prepare(
      "INSERT INTO calls (id, conversation_id, room_name, created_by, video, state, created_at, ended_at) VALUES (?, ?, ?, ?, 0, ?, ?, ?)",
    );
    ins.run("call_old", dm, "room-old", a.userId, "ENDED", Date.now() - 120 * day, Date.now() - 120 * day);
    ins.run("call_recent", dm, "room-recent", a.userId, "ENDED", Date.now() - 5 * day, Date.now() - 5 * day);
    ins.run("call_live_old", dm, "room-live", a.userId, "ACTIVE", Date.now() - 120 * day, null);
    h.ctx.db.prepare("INSERT INTO call_participants (call_id, user_id, device_id, joined_at) VALUES ('call_old', ?, ?, ?)").run(a.userId, a.deviceId, Date.now() - 120 * day);
    const audit = h.ctx.db.prepare("INSERT INTO audit_log (at, action) VALUES (?, ?)");
    audit.run(Date.now() - 400 * day, "old.event");
    audit.run(Date.now() - 10 * day, "recent.event");

    const r = await runCleanup(h.ctx);
    const calls = (h.ctx.db.prepare("SELECT id FROM calls").all() as { id: string }[]).map((c) => c.id);
    expect(calls).not.toContain("call_old");
    expect(calls).toEqual(expect.arrayContaining(["call_recent"]));
    expect(h.ctx.db.prepare("SELECT COUNT(*) n FROM call_participants WHERE call_id = 'call_old'").get()).toEqual({ n: 0 });
    const actions = (h.ctx.db.prepare("SELECT action FROM audit_log").all() as { action: string }[]).map((x) => x.action);
    expect(actions).not.toContain("old.event");
    expect(actions).toContain("recent.event");
    expect(r.callRecordsPruned).toBe(1);
    expect(r.auditPruned).toBeGreaterThanOrEqual(1);
  });
});
