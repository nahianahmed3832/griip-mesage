import crypto from "node:crypto";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { MemoryMailer } from "./support/accounts.js";
import { ADMIN_ORIGIN, consoleAdmin } from "./support/adminConsole.js";
import { type Answer, chatText, type FakeCall, fakeProviders, openAiChat } from "./support/fakeAi.js";
import { type Harness, startServer } from "./support/harness.js";
import { type Community, community, configurePlatform, failure, install } from "./support/workforce.js";

/**
 * Channel messages shared with an agent request (docs/AI_WORKFORCE.md §2): the person's app decrypts and picks them,
 * the server keeps them with the job only, the skill gets them as untrusted material, the channel is told, no route
 * returns them, delegated steps get them, and they're gone after 7 days. The provider is a fake (support/fakeAi.ts).
 */
const mailer = new MemoryMailer();
let h: Harness;
let c: Community;
let general: string, manager: string, writer: string, data: string;
const prompts: { system: string; user: string }[] = [];

function answer(call: FakeCall): Answer | null {
  if (call.url.endsWith("/v1/models")) return { status: 200, body: { data: [{ id: "gpt-test" }] } };
  if (!call.url.endsWith("/v1/chat/completions")) return null;
  const { system, user } = chatText(call);
  prompts.push({ system, user });
  if (user.includes("Reply with the single word")) return openAiChat("pong");
  if (system.includes("You coordinate a team of AI agents")) {
    const w = [...user.matchAll(/installId (agi_[A-Za-z0-9_-]+): Content Writer/g)][0]?.[1];
    return openAiChat(JSON.stringify({ mode: "delegate", steps: [{ key: "draft", installId: w, task: "Write a short announcement from the discussion.", dependsOn: [] }] }));
  }
  if (/Write an? [a-z ]+ in a [a-z ]+ tone/.test(system)) return openAiChat(JSON.stringify({ title: "Launch moved", body: Array.from({ length: 250 }, (_, i) => `word${i}`).join(" "), headlines: [] }));
  if (system.includes("Summarise for the person")) return openAiChat("The step finished.");
  return openAiChat("Here is the answer.");
}

beforeAll(async () => {
  fakeProviders(answer);
  h = await startServer({ ADMIN_CONSOLE_ORIGIN: ADMIN_ORIGIN, PLATFORM_SECRETS_KEY: crypto.randomBytes(32).toString("hex"), AI_WORKFORCE_TICK_MS: "0" }, { mailer });
  const admin = await consoleAdmin(h, mailer, "super_admin", "shsuper");
  await configurePlatform(admin, { models: [{ model: "gpt-test", capabilities: ["text"] }], agents: ["general_assistant", "agent_manager", "content_writer", "data_analytics"] });
  c = await community(h, "Shared");
  general = await install(c, "general_assistant");
  manager = await install(c, "agent_manager");
  writer = await install(c, "content_writer");
  data = await install(c, "data_analytics");
}, 120_000);

afterAll(async () => h.close());

beforeEach(() => {
  prompts.length = 0;
});

const T = Date.UTC(2026, 9, 24, 14, 2);
const messages = [
  { author: "Alice", text: "The launch moves to Thursday because the build failed.", at: T },
  { author: "Bob", text: "Then marketing needs the new date by Monday.", at: T + 60_000 },
];
const ask = (installId: string, body: Record<string, unknown> = {}) =>
  c.member.req("POST", `/v1/orgs/${c.orgId}/ai/jobs`, { installId, input: { brief: "Summarise the discussion." }, conversationId: c.channel, requestText: "Summarise the discussion.", channelMessages: messages, ...body });
const job = (id: string) => c.member.req("GET", `/v1/orgs/${c.orgId}/ai/jobs/${id}`).then((r) => r.job);
const run = () => h.ctx.workforce.runtime.runUntilIdle();

describe("sharing channel messages with a request", () => {
  it("reach the agent as untrusted material, are counted on the job, and the channel is told", async () => {
    const { job: j } = await ask(general);
    await run();
    const detail = await job(j.id);
    expect(detail.state).toBe("completed");
    expect(detail.sharedMessages).toBe(2);

    const prompt = prompts.find((p) => p.user.includes("Summarise the discussion."))!.user;
    expect(prompt).toContain('<untrusted source="channel (channel-messages.txt)">');
    expect(prompt).toContain("[2026-10-24 14:02 UTC] Alice: The launch moves to Thursday because the build failed.");
    expect(prompt.indexOf("Alice:")).toBeLessThan(prompt.indexOf("Bob:"));

    const { posts } = await c.owner.req("GET", `/v1/conversations/${c.channel}/ai/posts`);
    const request = posts.find((p: { jobId: string; kind: string }) => p.jobId === j.id && p.kind === "request");
    expect(request.body).toBe("Summarise the discussion.\n\nShared the last 2 messages here with the agent (not end-to-end encrypted).");

    const audit = h.ctx.db.prepare("SELECT detail FROM audit_log WHERE action = 'ai.job_create' AND target = ?").get(j.id) as { detail: string };
    expect(audit.detail).toMatch(/:shared=2$/);
  });

  it("are never returned by a route: the job shows only how many were shared", async () => {
    const { job: j } = await ask(general);
    await run();
    expect(JSON.stringify(await job(j.id))).not.toContain("build failed");
    const { jobs } = await c.member.req("GET", `/v1/orgs/${c.orgId}/ai/jobs`);
    expect(JSON.stringify(jobs)).not.toContain("build failed");
  });

  it("reach the Agent Manager's plan and the steps it delegates", async () => {
    const { job: j } = await ask(manager, { input: { brief: "Turn the discussion into an announcement." }, requestText: "Turn the discussion into an announcement." });
    await run();
    expect((await job(j.id)).state).toBe("completed");
    const plan = prompts.find((p) => p.system.includes("You coordinate a team of AI agents"))!;
    expect(plan.user).toContain("Channel messages shared with the request:");
    expect(plan.user).toContain("Bob: Then marketing needs the new date by Monday.");
    const step = prompts.find((p) => /Write an? [a-z ]+ in a [a-z ]+ tone/.test(p.system))!;
    expect(step.user).toContain("Alice: The launch moves to Thursday");
    // Only the request they were shared with counts them; the step reads them through it.
    const stepJob = (await job(j.id)).steps[0];
    expect((await job(stepJob.id)).sharedMessages).toBe(0);
  });

  it("are refused outside a channel request, for agents that don't read text, and beyond the limits", async () => {
    expect((await failure(ask(general, { conversationId: null, requestText: null }))).body.error.code).toBe("bad_request");
    const csvOnly = await failure(ask(data));
    expect(csvOnly.status).toBe(400);
    expect(csvOnly.body.error.message).toMatch(/doesn't read messages/);
    const many = Array.from({ length: 151 }, (_, i) => ({ author: "A", text: `m${i}`, at: T + i }));
    expect((await failure(ask(general, { channelMessages: many }))).status).toBe(400);
    const long = Array.from({ length: 20 }, (_, i) => ({ author: "A", text: "x".repeat(2000), at: T + i }));
    expect((await failure(ask(general, { channelMessages: long }))).status).toBe(400);
    expect((await failure(ask(general, { channelMessages: [{ author: "A", text: "x".repeat(2001), at: T }] }))).status).toBe(400);
    expect((await failure(ask(general, { channelMessages: [{ author: "A", text: "hi", at: T, extra: 1 }] }))).status).toBe(400);
    // Nothing was stored for the refused requests.
    expect((h.ctx.db.prepare("SELECT COUNT(*) n FROM ai_shared_messages WHERE conversation_id = ? AND job_id NOT IN (SELECT id FROM ai_jobs)").get(c.channel) as { n: number }).n).toBe(0);
  });

  it("someone in another community can't share into this channel", async () => {
    const other = await community(h, "Elsewhere");
    const r = await failure(other.member.req("POST", `/v1/orgs/${c.orgId}/ai/jobs`, { installId: general, input: { brief: "x" }, conversationId: c.channel, channelMessages: messages }));
    expect([403, 404]).toContain(r.status);
  });

  it("are gone after 7 days: the agent no longer gets them and cleanup deletes them", async () => {
    const { job: j } = await ask(general);
    h.ctx.db.prepare("UPDATE ai_shared_messages SET expires_at = ? WHERE job_id = ?").run(Date.now() - 1, j.id);
    await run();
    expect((await job(j.id)).sharedMessages).toBe(0);
    expect(prompts.some((p) => p.user.includes("build failed"))).toBe(false);
    await h.ctx.workforce.cleanup();
    expect(h.ctx.db.prepare("SELECT 1 FROM ai_shared_messages WHERE job_id = ?").get(j.id)).toBeUndefined();
  });

  it("the catalog says which agents can be sent messages", async () => {
    const detail = async (id: string) => (await c.member.req("GET", `/v1/orgs/${c.orgId}/ai/agents/${id}`)).definition;
    expect((await detail(general)).skill.readsMessages).toBe(true);
    expect((await detail(writer)).skill.readsMessages).toBe(true);
    expect((await detail(data)).skill.readsMessages).toBe(false);
  });
});
