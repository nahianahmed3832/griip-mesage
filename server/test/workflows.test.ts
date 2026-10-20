import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { Mailer, MailMessage } from "../src/accounts/mailer.js";
import { conditionsHold, render, type WorkflowEvent } from "../src/workflows/model.js";
import type { GriipClient } from "./support/griipClient.js";
import { expectStatus, type Harness, startServer } from "./support/harness.js";

/** Round 7 slice C3: workspace workflows (docs/WORKFLOWS.md). */
class MemoryMailer implements Mailer {
  readonly kind = "memory" as const;
  readonly available = true;
  sent: MailMessage[] = [];
  async send(m: MailMessage) {
    this.sent.push(m);
  }
}

let h: Harness;
const mail = new MemoryMailer();
let owner: GriipClient, member: GriipClient, newcomer: GriipClient, leaver: GriipClient;
let orgId: string, general: string, onboarding: string, secret: string, announcements: string;

const appEvents = async (c: GriipClient, conv: string) =>
  ((await c.req("GET", `/v1/conversations/${conv}/integration-events`)).events as { appName: string; title: string; summary: string }[]);
const invite = async (c: GriipClient, role = "MEMBER") => {
  const code = (await owner.req("POST", `/v1/orgs/${orgId}/invites`, { role })).invite.code;
  await c.req("POST", "/v1/invites/accept", { code });
};
const runAll = async () => {
  for (let i = 0; i < 5; i++) await h.ctx.workflows.tick();
};

beforeAll(async () => {
  h = await startServer({ WORKFLOW_TICK_MS: "0" }, { mailer: mail });
  [owner, member, newcomer, leaver] = await Promise.all(["wfowner", "wfmember", "wfnewcomer", "wfleaver"].map((n) => h.client(n)));
  orgId = (await owner.req("POST", "/v1/orgs", { name: "Flow Co" })).org.id;
  general = (await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "general", scope: "ORG", kind: "PUBLIC" })).channel.id;
  onboarding = (await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "onboarding", scope: "ORG", kind: "PUBLIC", autoJoin: false })).channel.id;
  secret = (await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "secret", scope: "ORG", kind: "PRIVATE" })).channel.id;
  announcements = (await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "news", scope: "ORG", kind: "ANNOUNCEMENT" })).channel.id;
  await invite(member);
});
afterAll(async () => h.close());

describe("workflows (round 7, C3)", () => {
  it("only people who can manage workflows see or change them", async () => {
    await expectStatus(member.req("GET", `/v1/orgs/${orgId}/workflows`), 403);
    await expectStatus(member.req("GET", `/v1/orgs/${orgId}/workflows/catalog`), 403);
    const cat = (await owner.req("GET", `/v1/orgs/${orgId}/workflows/catalog`)).catalog;
    expect(cat.triggers.map((t: { type: string }) => t.type)).toContain("member.joined");
    expect((await owner.req("GET", "/v1/server-info")).features).toContain("workflows");
  });

  it("refuses channels the owner can't reach and actions that don't fit the trigger", async () => {
    const other = (await member.req("POST", "/v1/orgs", { name: "Elsewhere" })).org.id;
    const foreign = (await member.req("POST", `/v1/orgs/${other}/channels`, { name: "x", scope: "ORG", kind: "PUBLIC" })).channel.id;
    await expectStatus(owner.req("POST", `/v1/orgs/${orgId}/workflows`, {
      name: "bad", trigger: { type: "member.joined" }, actions: [{ type: "post_channel", channelId: foreign, text: "hi" }],
    }), 400);
    await expectStatus(owner.req("POST", `/v1/orgs/${orgId}/workflows`, {
      name: "bad", trigger: { type: "channel.created" }, actions: [{ type: "add_to_channels", channelIds: [general] }],
    }), 400);
  });

  it("welcomes a new member: adds them to channels and posts once, as a Workflows update", async () => {
    const w = (await owner.req("POST", `/v1/orgs/${orgId}/workflows`, {
      name: "Welcome",
      trigger: { type: "member.joined" },
      conditions: [{ field: "member.role", op: "is_not", value: "GUEST" }],
      actions: [
        { type: "add_to_channels", channelIds: [onboarding, secret] },
        { type: "post_channel", channelId: general, text: "Welcome to {workspace}, {member}!" },
      ],
    })).workflow;
    expect(w.owner.allowed).toBe(true);
    await invite(newcomer);
    await runAll();
    await runAll(); // a second pass changes nothing: the run is done
    const mine = ((await newcomer.req("GET", "/v1/conversations")).conversations as { id: string }[]).map((c) => c.id);
    expect(mine).toContain(onboarding);
    expect(mine).toContain(secret); // the owner manages the private channel, so the workflow may add people there
    const posts = (await appEvents(owner, general)).filter((e) => e.appName === "Workflows");
    expect(posts).toHaveLength(1);
    expect(posts[0].summary).toMatch(/^Welcome to Flow Co, /);
    const runs = (await owner.req("GET", `/v1/orgs/${orgId}/workflows/${w.id}/runs`)).runs;
    expect(runs[0]).toMatchObject({ state: "done" });
    expect(runs[0].outcomes.map((o: { status: string }) => o.status)).toEqual(["done", "done"]);
  });

  it("conditions decide: a guest isn't welcomed", async () => {
    const guest = await h.client("wfguest");
    const before = (await appEvents(owner, general)).length;
    await invite(guest, "GUEST");
    await runAll();
    expect((await appEvents(owner, general)).length).toBe(before);
  });

  it("emails the admins when someone leaves, and a dry run changes nothing", async () => {
    const input = { name: "Leavers", trigger: { type: "member.left" }, actions: [{ type: "email_admins", subject: "{member} left", text: "{member} left {workspace}." }] };
    // No admin has a verified account email in this harness: the action says so instead of pretending.
    const test = (await owner.req("POST", `/v1/orgs/${orgId}/workflows/test`, input)).test;
    expect(test.outcomes[0]).toMatchObject({ status: "skipped" });
    expect(mail.sent).toHaveLength(0);
    await owner.req("POST", `/v1/orgs/${orgId}/workflows`, input);
    await invite(leaver);
    await leaver.req("DELETE", `/v1/orgs/${orgId}/members/${leaver.userId}`);
    await runAll();
    expect(mail.sent).toHaveLength(0);
    // Once the owner has a verified account email, the next leaver is reported to them, once.
    if (!h.ctx.mongo?.owns("accounts")) {
      const t = Date.now();
      h.ctx.db
        .prepare("INSERT INTO accounts (id, email, name, password_hash, password_changed_at, email_verified_at, user_id, created_at, updated_at) VALUES ('acct_wf', 'owner@example.test', 'Owner', 'x', ?, ?, ?, ?, ?)")
        .run(t, t, owner.userId, t, t);
      const second = await h.client("wfleaver2");
      await invite(second);
      await owner.req("DELETE", `/v1/orgs/${orgId}/members/${second.userId}`);
      await runAll();
      await runAll();
      expect(mail.sent.map((m) => m.to)).toEqual(["owner@example.test"]);
      expect(mail.sent[0].subject).toMatch(/ left$/);
      expect(mail.sent[0].text).toContain('Sent by the workflow "Leavers" in Flow Co');
    }
  });

  it("an owner who loses the right stops their workflows from running", async () => {
    await owner.req("PATCH", `/v1/orgs/${orgId}/members/${member.userId}`, { role: "ADMIN" });
    const wf = (await member.req("POST", `/v1/orgs/${orgId}/workflows`, {
      name: "Announce channels", trigger: { type: "channel.created" }, actions: [{ type: "post_channel", channelId: general, text: "New: {channel}" }],
    })).workflow;
    await owner.req("PATCH", `/v1/orgs/${orgId}/members/${member.userId}`, { role: "MEMBER" });
    await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "design", scope: "ORG", kind: "PUBLIC" });
    await runAll();
    const runs = (await owner.req("GET", `/v1/orgs/${orgId}/workflows/${wf.id}/runs`)).runs;
    expect(runs[0]).toMatchObject({ state: "failed", errorKind: "owner_not_allowed" });
    // Saving it again makes the saver the owner, with their rights.
    const saved = (await owner.req("PUT", `/v1/orgs/${orgId}/workflows/${wf.id}`, {
      rev: wf.rev, name: wf.name, trigger: { type: "channel.created" }, actions: [{ type: "post_channel", channelId: general, text: "New: {channel}" }],
    })).workflow;
    expect(saved.owner.id).toBe(owner.userId);
    await expectStatus(owner.req("PUT", `/v1/orgs/${orgId}/workflows/${wf.id}`, {
      rev: wf.rev, name: "stale", trigger: { type: "channel.created" }, actions: [{ type: "post_channel", channelId: general, text: "x" }],
    }), 409);
  });

  it("posting in an announcement channel needs a manager as owner; a schedule runs once per occurrence", async () => {
    const wf = (await owner.req("POST", `/v1/orgs/${orgId}/workflows`, {
      name: "Weekly",
      trigger: { type: "schedule", schedule: { recurrence: "daily", localTime: "09:00", anchorDate: "2026-01-01", timeZone: "Asia/Dhaka" } },
      actions: [{ type: "post_channel", channelId: announcements, text: "Status update, please" }],
    })).workflow;
    expect(wf.nextRunAt).toBeGreaterThan(Date.now());
    h.ctx.db.prepare("UPDATE workflows SET next_run_at = ? WHERE id = ?").run(Date.now() - 1000, wf.id);
    await runAll();
    await runAll();
    const runs = (await owner.req("GET", `/v1/orgs/${orgId}/workflows/${wf.id}/runs`)).runs;
    expect(runs).toHaveLength(1);
    expect(runs[0].state).toBe("done");
    const after = (h.ctx.db.prepare("SELECT next_run_at FROM workflows WHERE id = ?").get(wf.id) as { next_run_at: number }).next_run_at;
    expect(after).toBeGreaterThan(Date.now());
  });

  it("guards against loops and floods", async () => {
    const wf = (await owner.req("GET", `/v1/orgs/${orgId}/workflows`)).workflows.find((w: { name: string }) => w.name === "Welcome");
    h.ctx.workflows.emit({ type: "member.joined", key: "deep", orgId, depth: 5, member: { id: newcomer.userId, name: "N", role: "MEMBER" } });
    const deep = h.ctx.db.prepare("SELECT state, error_kind FROM workflow_runs WHERE workflow_id = ? AND event_key = 'deep'").get(wf.id);
    expect(deep).toEqual({ state: "skipped", error_kind: "loop_guard" });
    // The same event twice runs once.
    h.ctx.workflows.emit({ type: "member.joined", key: "twice", orgId, depth: 0, member: { id: newcomer.userId, name: "N", role: "MEMBER" } });
    h.ctx.workflows.emit({ type: "member.joined", key: "twice", orgId, depth: 0, member: { id: newcomer.userId, name: "N", role: "MEMBER" } });
    expect((h.ctx.db.prepare("SELECT COUNT(*) n FROM workflow_runs WHERE event_key = 'twice'").get() as { n: number }).n).toBe(1);
  });

  it("a queued run can be cancelled", async () => {
    const wf = (await owner.req("GET", `/v1/orgs/${orgId}/workflows`)).workflows.find((w: { name: string }) => w.name === "Welcome");
    h.ctx.workflows.emit({ type: "member.joined", key: "cancel-me", orgId, depth: 0, member: { id: newcomer.userId, name: "N", role: "MEMBER" } });
    const run = h.ctx.db.prepare("SELECT id FROM workflow_runs WHERE workflow_id = ? AND event_key = 'cancel-me'").get(wf.id) as { id: string };
    await owner.req("POST", `/v1/orgs/${orgId}/workflow-runs/${run.id}/cancel`);
    await runAll();
    expect((h.ctx.db.prepare("SELECT state FROM workflow_runs WHERE id = ?").get(run.id) as { state: string }).state).toBe("cancelled");
  });

  it("conditions and templates (pure)", () => {
    const e: WorkflowEvent = { type: "channel.created", key: "k", orgId: "o", depth: 0, channel: { id: "c", name: "Design-Review", kind: "PUBLIC", scope: "ORG" } };
    expect(conditionsHold([{ field: "channel.name", op: "contains", value: "design" }], e)).toBe(true);
    expect(conditionsHold([{ field: "channel.kind", op: "is_not", value: "public" }], e)).toBe(false);
    expect(render("New {channel} in {workspace} {unknown}", e, "Flow Co")).toBe("New #Design-Review in Flow Co {unknown}");
  });
});
