import crypto from "node:crypto";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { MemoryMailer } from "./support/accounts.js";
import { ADMIN_ORIGIN, type ConsoleAdmin, consoleAdmin } from "./support/adminConsole.js";
import { type Answer, chatText, type FakeCall, fakeProviders, openAiChat, PNG } from "./support/fakeAi.js";
import { type Harness, startServer } from "./support/harness.js";
import { type Community, community, configurePlatform, failure, install, REASON } from "./support/workforce.js";
import { runPlatformAdminCommand } from "../src/modules/platformAdmin/index.js";

/**
 * AI Workforce's runtime and its boundaries (docs/AI_WORKFORCE.md §5–6): console controls reaching running jobs,
 * quotas under parallel requests, recovery after a worker dies, cancellation and retries, provider failures, tenant
 * isolation, role and channel checks at run time, upload checks, and the Agent Manager's delegation. The provider is a
 * fake (support/fakeAi.ts).
 */
const mailer = new MemoryMailer();
let h: Harness;
let admin: ConsoleAdmin;
let a: Community, b: Community;
let general: string, writer: string, manager: string;
let chatFailures = 0;
const prompts: FakeCall[] = [];

function answer(c: FakeCall): Answer | null {
  if (c.url.endsWith("/v1/models")) return { status: 200, body: { data: [{ id: "gpt-test" }] } };
  if (!c.url.endsWith("/v1/chat/completions")) return null;
  prompts.push(c);
  if (chatFailures > 0) {
    chatFailures--;
    return { status: 500, body: {} };
  }
  const { system, user } = chatText(c);
  if (user.includes("Reply with the single word")) return openAiChat("pong");
  if (system.includes("You coordinate a team of AI agents")) {
    const ids = [...user.matchAll(/installId (agi_[A-Za-z0-9_-]+): ([^(]+)\(/g)].map((m) => ({ id: m[1]!, name: m[2]!.trim() }));
    const w = ids.find((x) => x.name === "Content Writer");
    const g = ids.find((x) => x.name === "General Assistant");
    if (user.includes("just answer")) return openAiChat(JSON.stringify({ mode: "answer", answer: "Forty-two." }));
    return openAiChat(JSON.stringify({ mode: "delegate", steps: [{ key: "draft", installId: w?.id, task: "Write a short announcement about voice channels.", dependsOn: [] }, { key: "check", installId: g?.id, task: "List three risks of the announcement.", dependsOn: ["draft"] }] }));
  }
  if (system.includes("Write a announcement") || system.includes("Write a article")) {
    return openAiChat(JSON.stringify({ title: "Voice channels are here", body: Array.from({ length: 120 }, (_, i) => `word${i}`).join(" "), headlines: [] }));
  }
  if (system.includes("Summarise for the person")) return openAiChat("Both steps finished.");
  return openAiChat("Here is the answer.");
}

beforeAll(async () => {
  fakeProviders(answer);
  h = await startServer({ ADMIN_CONSOLE_ORIGIN: ADMIN_ORIGIN, PLATFORM_SECRETS_KEY: crypto.randomBytes(32).toString("hex"), AI_WORKFORCE_TICK_MS: "0" }, { mailer });
  admin = await consoleAdmin(h, mailer, "super_admin", "rtsuper");
  await configurePlatform(admin, { models: [{ model: "gpt-test", capabilities: ["text"] }], agents: ["general_assistant", "content_writer", "agent_manager"] });
  a = await community(h, "Alpha");
  b = await community(h, "Beta");
  general = await install(a, "general_assistant");
  writer = await install(a, "content_writer");
  manager = await install(a, "agent_manager");
}, 120_000);

afterAll(async () => h.close());

beforeEach(() => {
  chatFailures = 0;
});

const ask = (c: Community, installId: string, brief = "What's the plan?", extra: Record<string, unknown> = {}) =>
  c.member.req("POST", `/v1/orgs/${c.orgId}/ai/jobs`, { installId, input: { brief }, conversationId: c.channel, requestText: brief, ...extra }).then((r) => r.job as { id: string; state: string });
const job = (c: Community, id: string) => c.member.req("GET", `/v1/orgs/${c.orgId}/ai/jobs/${id}`).then((r) => r.job);
const run = () => h.ctx.workforce.runtime.runUntilIdle();
const controls = (body: Record<string, unknown>) => admin.browser.expect(200, "PUT", "/admin/v1/ai/workforce/controls", { ...body, reason: REASON });

describe("console controls reach the runtime", () => {
  it("pausing refuses new jobs and leaves queued ones waiting; resuming runs them", async () => {
    const queued = await ask(a, general);
    await controls({ paused: true });
    expect((await failure(ask(a, general))).body.error.code).toBe("ai_paused");
    await run();
    expect((await job(a, queued.id)).state).toBe("queued");
    await controls({ paused: false });
    await run();
    expect((await job(a, queued.id)).state).toBe("completed");
  });

  it("the operator CLI can pause and resume new jobs without the console, audited", async () => {
    const cli = (cmd: string, ...args: string[]) => runPlatformAdminCommand(cmd, args, h.ctx.config, h.ctx.db);
    expect(await cli("ai-workforce-pause", "short")).toBe(2);
    expect(await cli("ai-workforce-pause", "provider", "is", "leaking", "data")).toBe(0);
    expect((await failure(ask(a, general))).body.error.code).toBe("ai_paused");
    expect(await cli("ai-workforce-resume", "provider", "fixed", "again")).toBe(0);
    const j = await ask(a, general);
    await run();
    expect((await job(a, j.id)).state).toBe("completed");
    const audit = h.ctx.db.prepare("SELECT action, reason FROM platform_audit WHERE action = 'ai.workforce_controls' ORDER BY rowid DESC LIMIT 2").all();
    expect(audit).toEqual([
      { action: "ai.workforce_controls", reason: "provider fixed again" },
      { action: "ai.workforce_controls", reason: "provider is leaking data" },
    ]);
  });

  it("the kill switch refuses jobs before any provider is called", async () => {
    await admin.browser.expect(200, "PUT", "/admin/v1/settings/ai.enabled", { value: false, reason: REASON });
    const before = prompts.length;
    expect((await failure(ask(a, general))).body.error.code).toBe("ai_disabled");
    await run();
    expect(prompts.length).toBe(before);
    await admin.browser.expect(200, "PUT", "/admin/v1/settings/ai.enabled", { value: true, reason: REASON });
  });

  it("disabling an agent version on the platform fails its queued jobs and refuses new ones", async () => {
    const queued = await ask(a, general);
    await admin.browser.expect(200, "PUT", "/admin/v1/ai/catalog/general_assistant/1", { status: "disabled", reason: REASON });
    await run();
    expect((await job(a, queued.id)).error.code).toBe("agent_unavailable");
    expect((await failure(ask(a, general))).body.error.code).toBe("agent_unavailable");
    await admin.browser.expect(200, "PUT", "/admin/v1/ai/catalog/general_assistant/1", { status: "published", reason: REASON });
  });

  it("turning a tool off stops agents using it at run time, and the refusal is recorded", async () => {
    await admin.browser.expect(200, "PUT", "/admin/v1/ai/tools/channel.post", { mode: "disabled", reason: REASON });
    const j = await ask(a, general, "Quiet please");
    await run();
    const d = await job(a, j.id);
    expect(d.state).toBe("completed");
    expect(d.events.some((e: { kind: string; detail: { tool?: string; outcome?: string } }) => e.kind === "tool" && e.detail.tool === "channel.post" && e.detail.outcome === "denied")).toBe(true);
    const posts = (await a.member.req("GET", `/v1/conversations/${a.channel}/ai/posts`)).posts.filter((p: { jobId: string }) => p.jobId === j.id);
    expect(posts.map((p: { kind: string }) => p.kind)).toEqual(["request"]);
    await admin.browser.expect(200, "PUT", "/admin/v1/ai/tools/channel.post", { mode: "allowed", reason: REASON });
  });

  it("the strict approval floor puts every agent in review mode", async () => {
    const cur = (await a.owner.req("GET", `/v1/orgs/${a.orgId}/ai/agents/${general}`)).agent;
    const auto = (await a.owner.req("PATCH", `/v1/orgs/${a.orgId}/ai/agents/${general}`, { rev: cur.rev, executionMode: "auto" })).agent;
    expect(auto.effectiveMode).toBe("auto");
    await controls({ approvalFloor: "strict" });
    expect((await a.owner.req("GET", `/v1/orgs/${a.orgId}/ai/agents/${general}`)).agent.effectiveMode).toBe("review");
    await controls({ approvalFloor: "standard" });
  });

  it("concurrency 0 claims nothing", async () => {
    await controls({ maxConcurrency: 0 });
    const j = await ask(a, general);
    await run();
    expect((await job(a, j.id)).state).toBe("queued");
    await controls({ maxConcurrency: 2 });
    await run();
    expect((await job(a, j.id)).state).toBe("completed");
  });
});

describe("quotas", () => {
  it("a community at its active-job limit is refused, also under parallel requests", async () => {
    await controls({ maxConcurrency: 0 });
    const ent = await admin.browser.expect(200, "GET", `/admin/v1/orgs/${b.orgId}/ai`);
    await admin.browser.expect(200, "PUT", `/admin/v1/orgs/${b.orgId}/ai`, { entitlement: { ...ent.entitlement, maxActiveJobs: 2 }, rev: ent.rev, reason: REASON });
    const bGeneral = await install(b, "general_assistant", "Helper");
    const results = await Promise.allSettled(Array.from({ length: 6 }, (_, i) => ask(b, bGeneral, `parallel ${i}`)));
    expect(results.filter((r) => r.status === "fulfilled")).toHaveLength(2);
    expect(results.filter((r) => r.status === "rejected").every((r) => (r as PromiseRejectedResult).reason.status === 429)).toBe(true);
    await controls({ maxConcurrency: 2 });
    await run();
  });

  it("an operator's daily job limit for a community blocks the next job", async () => {
    await admin.browser.expect(200, "POST", "/admin/v1/quotas/policies", { scopeType: "org", scopeId: a.orgId, key: "ai.jobs", limit: 0, mode: "block", reason: REASON });
    const r = await failure(ask(a, general));
    expect(r.status).toBe(429);
    const policies = (await admin.browser.expect(200, "GET", "/admin/v1/quotas")).policies as { id: string; key: string }[];
    for (const p of policies.filter((x) => x.key === "ai.jobs")) await admin.browser.expect(200, "POST", `/admin/v1/quotas/policies/${p.id}/remove`, { reason: REASON });
  });
});

describe("jobs", () => {
  it("come back after a worker dies holding them, and complete", async () => {
    await controls({ maxConcurrency: 0 });
    const j = await ask(a, general);
    // A worker claimed it and vanished: its lease ran out.
    h.ctx.db.prepare("UPDATE ai_jobs SET state = 'running', worker = 'w_dead', lease_until = ?, attempts = 1 WHERE id = ?").run(Date.now() - 1000, j.id);
    await controls({ maxConcurrency: 2 });
    await h.ctx.workforce.runtime.tick();
    expect((await job(a, j.id)).events.some((e: { kind: string }) => e.kind === "recovered")).toBe(true);
    h.ctx.db.prepare("UPDATE ai_jobs SET next_at = 0 WHERE id = ?").run(j.id);
    await run();
    expect((await job(a, j.id)).state).toBe("completed");
  });

  it("can be cancelled while queued and retried", async () => {
    await controls({ maxConcurrency: 0 });
    const j = await ask(a, general);
    expect((await a.member.req("POST", `/v1/orgs/${a.orgId}/ai/jobs/${j.id}/cancel`)).job.state).toBe("cancelled");
    expect((await failure(a.member.req("POST", `/v1/orgs/${a.orgId}/ai/jobs/${j.id}/cancel`))).body.error.code).toBe("job_not_retryable");
    await a.member.req("POST", `/v1/orgs/${a.orgId}/ai/jobs/${j.id}/retry`);
    await controls({ maxConcurrency: 2 });
    await run();
    expect((await job(a, j.id)).state).toBe("completed");
    // Someone else can't steer it.
    expect((await failure(a.owner.req("POST", `/v1/orgs/${a.orgId}/ai/jobs/${j.id}/retry`))).body.error.code).toBe("job_not_retryable");
  });

  it("an operator's cancel is final; a retry is admitted like a new job", async () => {
    await controls({ maxConcurrency: 0 });
    const j = await ask(a, general);
    await admin.browser.expect(200, "POST", `/admin/v1/ai/jobs/${j.id}/cancel`, { reason: REASON });
    expect((await job(a, j.id)).error.code).toBe("cancelled_by_operator");
    expect((await failure(a.member.req("POST", `/v1/orgs/${a.orgId}/ai/jobs/${j.id}/retry`))).body.error.code).toBe("job_not_retryable");
    // A retry passes the same limits a new job does.
    const mine = await ask(a, general);
    await a.member.req("POST", `/v1/orgs/${a.orgId}/ai/jobs/${mine.id}/cancel`);
    await admin.browser.expect(200, "POST", "/admin/v1/quotas/policies", { scopeType: "org", scopeId: a.orgId, key: "ai.jobs", limit: 0, mode: "block", reason: REASON });
    expect((await failure(a.member.req("POST", `/v1/orgs/${a.orgId}/ai/jobs/${mine.id}/retry`))).status).toBe(429);
    const policies = (await admin.browser.expect(200, "GET", "/admin/v1/quotas")).policies as { id: string; key: string }[];
    for (const p of policies.filter((x) => x.key === "ai.jobs")) await admin.browser.expect(200, "POST", `/admin/v1/quotas/policies/${p.id}/remove`, { reason: REASON });
    await a.member.req("POST", `/v1/orgs/${a.orgId}/ai/jobs/${mine.id}/retry`);
    await controls({ maxConcurrency: 2 });
    await run();
    expect((await job(a, mine.id)).state).toBe("completed");
  });

  it("a cancel that arrives while the agent finishes wins over the result", async () => {
    const jobs = h.ctx.workforce.jobs;
    await controls({ maxConcurrency: 0 });
    const j = await ask(a, general);
    const [claimed] = jobs.claim("w_test", 1, 60_000, () => true);
    expect(claimed!.id).toBe(j.id);
    jobs.requestCancel(j.id, "cancelled_by_requester");
    expect(jobs.complete(j.id, "w_test", { answer: "late" }, "late")).toBe(false);
    expect(jobs.cancelled(j.id, "w_test")).toBe(true);
    expect(jobs.get(j.id)).toMatchObject({ state: "cancelled", output: null, error_code: "cancelled_by_requester" });
    await controls({ maxConcurrency: 2 });
  });

  it("retry after a transient provider failure, with the attempt recorded", async () => {
    chatFailures = 2; // the gateway's own retry and its first attempt both fail
    const j = await ask(a, general);
    await run();
    let d = await job(a, j.id);
    expect(d.state).toBe("queued");
    expect(d.events.some((e: { kind: string }) => e.kind === "retry_scheduled")).toBe(true);
    h.ctx.db.prepare("UPDATE ai_jobs SET next_at = 0 WHERE id = ?").run(j.id);
    await run();
    d = await job(a, j.id);
    expect(d.state).toBe("completed");
    expect(d.attempts).toBe(2);
  });

  it("refuse plainly, without queueing a job, when no verified model offers what the agent needs", async () => {
    const providers = (await admin.browser.expect(200, "GET", "/admin/v1/ai")).providers as { id: string }[];
    const pid = providers[0]!.id;
    await admin.browser.expect(200, "PUT", `/admin/v1/ai/providers/${pid}/models/gpt-test/capabilities`, { capabilities: [], reason: REASON });
    const before = h.ctx.db.prepare("SELECT COUNT(*) n FROM ai_jobs").get() as { n: number };
    const r = await failure(ask(a, general));
    expect(r.status).toBe(503);
    expect(r.body.error.code).toBe("ai_not_configured");
    expect(r.body.error.message).toMatch(/no verified model for quick replies/);
    expect(r.body.error.details.categories).toEqual(["fast"]);
    expect(h.ctx.db.prepare("SELECT COUNT(*) n FROM ai_jobs").get()).toEqual(before);
    await admin.browser.expect(200, "PUT", `/admin/v1/ai/providers/${pid}/models/gpt-test/capabilities`, { capabilities: ["text"], reason: REASON });
    await admin.browser.expect(200, "POST", `/admin/v1/ai/providers/${pid}/models/gpt-test/verify`, { capability: "text", reason: REASON });
  });
});

describe("isolation and permissions", () => {
  it("another community sees nothing of this one's agents, jobs or files", async () => {
    const j = await ask(a, general);
    await run();
    expect((await failure(b.member.req("GET", `/v1/orgs/${a.orgId}/ai`))).status).toBe(404);
    expect((await failure(b.member.req("GET", `/v1/orgs/${a.orgId}/ai/jobs/${j.id}`))).status).toBe(404);
    expect((await failure(b.member.req("GET", `/v1/orgs/${b.orgId}/ai/jobs/${j.id}`))).status).toBe(404);
    // Using community A's agent from community B.
    expect((await failure(b.member.req("POST", `/v1/orgs/${b.orgId}/ai/jobs`, { installId: general, input: { brief: "x" } }))).status).toBe(404);
    expect((await failure(b.owner.req("PUT", `/v1/orgs/${b.orgId}/ai/agents/${general}/channels/${b.channel}`, { activation: "mention" }))).status).toBe(404);
    // A channel of another community can't be assigned.
    expect((await failure(a.owner.req("PUT", `/v1/orgs/${a.orgId}/ai/agents/${general}/channels/${b.channel}`, { activation: "mention" }))).body.error.code).toBe("agent_not_assigned");
  });

  it("an agent limited to some roles refuses people without them", async () => {
    const role = (await a.owner.req("POST", `/v1/orgs/${a.orgId}/roles`, { name: "Video team", permissions: [] })).role;
    const cur = (await a.owner.req("GET", `/v1/orgs/${a.orgId}/ai/agents/${writer}`)).agent;
    await a.owner.req("PATCH", `/v1/orgs/${a.orgId}/ai/agents/${writer}`, { rev: cur.rev, invokers: { everyone: false, roles: [role.id] } });
    expect((await failure(ask(a, writer))).body.error.code).toBe("agent_not_assigned");
    const now = (await a.owner.req("GET", `/v1/orgs/${a.orgId}/ai/agents/${writer}`)).agent;
    await a.owner.req("PATCH", `/v1/orgs/${a.orgId}/ai/agents/${writer}`, { rev: now.rev, invokers: { everyone: true, roles: [] } });
  });

  it("asking from a channel needs the right to post there (announcement channels refuse members)", async () => {
    const news = (await a.owner.req("POST", `/v1/orgs/${a.orgId}/channels`, { name: "news", scope: "ORG", kind: "ANNOUNCEMENT" })).channel.id;
    await a.member.req("POST", `/v1/conversations/${news}/join`).catch(() => undefined);
    await a.owner.req("PUT", `/v1/orgs/${a.orgId}/ai/agents/${general}/channels/${news}`, { activation: "mention" });
    const r = await failure(a.member.req("POST", `/v1/orgs/${a.orgId}/ai/jobs`, { installId: general, input: { brief: "spam" }, conversationId: news, requestText: "spam" }));
    expect(r.body.error.code).toBe("agent_not_assigned");
    expect((await a.member.req("GET", `/v1/conversations/${news}/ai/posts`)).posts).toEqual([]);
  });

  it("a person removed from the community before their job runs: the job fails at run time", async () => {
    const c = await community(h, "Gamma");
    const g = await install(c, "general_assistant", "Gamma helper");
    await controls({ maxConcurrency: 0 });
    const j = await ask(c, g);
    await c.owner.req("DELETE", `/v1/orgs/${c.orgId}/members/${c.member.userId}`);
    await controls({ maxConcurrency: 2 });
    await run();
    const row = h.ctx.workforce.jobs.get(j.id)!;
    expect(row.state).toBe("failed");
    expect(row.error_code).toBe("agent_not_assigned");
  });

  it("uploads must be what they say, and files can't be borrowed", async () => {
    expect((await failure(a.member.req("POST", `/v1/orgs/${a.orgId}/ai/files?name=x.png&type=image/png`, undefined, Buffer.from("not a png at all")))).body.error.code).toBe("bad_request");
    expect((await failure(a.member.req("POST", `/v1/orgs/${a.orgId}/ai/files?name=x.exe&type=application/x-msdownload`, undefined, Buffer.from("MZ")))).status).toBe(400);
    const mine = (await a.owner.req("POST", `/v1/orgs/${a.orgId}/ai/files?name=notes.txt&type=text/plain`, undefined, Buffer.from("owner's notes"))).file.id;
    expect((await failure(ask(a, general, "use the owner's notes", { fileIds: [mine] }))).body.error.code).toBe("bad_request");
    expect((await failure(a.owner.req("PUT", `/v1/orgs/${a.orgId}/ai/agents/${general}/avatar`, undefined, Buffer.from("<svg onload=alert(1)>")))).body.error.code).toBe("bad_request");
    expect((await a.owner.req("PUT", `/v1/orgs/${a.orgId}/ai/agents/${general}/avatar`, undefined, PNG)).agent.avatar).not.toBeNull();
  });
});

describe("Agent Manager", () => {
  it("answers directly when no specialist is needed", async () => {
    const j = await ask(a, manager, "just answer: what is six times seven?");
    await run();
    const d = await job(a, j.id);
    expect(d.state).toBe("completed");
    expect(d.output).toMatchObject({ mode: "answer", answer: "Forty-two.", steps: [] });
    expect(d.steps).toEqual([]);
  });

  it("delegates to the community's agents, waits for their real jobs and reports them", async () => {
    const j = await ask(a, manager, "Announce voice channels and check the announcement");
    await run();
    const d = await job(a, j.id);
    expect(d.state).toBe("completed");
    expect(d.steps).toHaveLength(2);
    expect(d.steps.map((s: { agent: { agentId: string }; state: string; depth: number }) => [s.agent.agentId, s.state, s.depth])).toEqual([
      ["content_writer", "completed", 1],
      ["general_assistant", "completed", 1],
    ]);
    expect(d.output.steps.map((s: { state: string }) => s.state)).toEqual(["completed", "completed"]);
    expect(d.output.reportFileId).toBeTruthy();
    // The second step waited for the first.
    const [draft, check] = d.steps as { id: string; startedAt: number; finishedAt: number }[];
    expect(check!.startedAt).toBeGreaterThanOrEqual(draft!.finishedAt);
    // The manager only offered agents the person can use: never itself.
    const plan = prompts.filter((c) => chatText(c).system.includes("You coordinate a team")).at(-1)!;
    expect(chatText(plan).user).not.toContain(manager);
  });

  it("can't delegate to a coordinator, beyond its depth, or for someone who can't use the target", async () => {
    const svc = h.ctx.workforce.service;
    const parent = h.ctx.workforce.jobs.list({ installId: manager }, 1)[0]!;
    expect(() => svc.createChild(parent, manager, { brief: "loop" }, [], "self")).toThrow(/coordinator/);
    expect(() => svc.createChild({ ...parent, depth: 2 }, general, { brief: "deep" }, [], "deep")).toThrow(/deeper/);
    expect(() => svc.createChild({ ...parent, requested_by: b.member.userId }, general, { brief: "x" }, [], "x")).toThrow();
  });
});
