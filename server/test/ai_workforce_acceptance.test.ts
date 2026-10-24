import crypto from "node:crypto";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { MemoryMailer } from "./support/accounts.js";
import { type ConsoleAdmin, consoleAdmin } from "./support/adminConsole.js";
import { chatText, fakeProviders, hasFfmpeg, openAiChat, openAiModels, PNG, probeSeconds, testVideo } from "./support/fakeAi.js";
import type { GriipClient } from "./support/griipClient.js";
import { type Harness, startServer } from "./support/harness.js";
import { ADMIN_ORIGIN } from "./support/adminConsole.js";
import { AdminAiEntitlements, AdminAiModels, AdminCatalog, AdminJobDetail, AdminJobs, AdminOrgAi, AdminTools, AdminWorkforceOverview } from "../src/shared/contracts/http/platformAdminWorkforce.js";

/**
 * The AI Workforce acceptance scenario (docs/AI_WORKFORCE.md, brief Part 14), end to end over the real HTTP API: the
 * console configures a provider and publishes Product Video Editor; a community installs and customises it and
 * assigns it to a channel; a member asks for a video with real footage; ffmpeg renders a real preview; a revision, an
 * approval of exactly the new preview, the final export; the console sees the work; and the refusals hold.
 *
 * The AI provider is a fake (support/fakeAi.ts): this proves the runtime, not a real provider.
 */
const REASON = "acceptance test of AI Workforce";
const mailer = new MemoryMailer();
const ffmpeg = hasFfmpeg();
let h: Harness;
let admin: ConsoleAdmin;
let ops: ConsoleAdmin;
let support: ConsoleAdmin;
let owner: GriipClient, member: GriipClient, outsider: GriipClient;
let orgId: string, marketing: string, general: string, outsiderOrg: string;
let fake: ReturnType<typeof fakeProviders>;
let planRequests = 0;

const SEGMENTS = [
  { start: 0.6, end: 2.0, text: "Meet the Acme bottle" },
  { start: 2.2, end: 3.8, text: "Cold for 24 hours" },
];

beforeAll(async () => {
  fake = fakeProviders((c) => {
    if (c.url.endsWith("/v1/models")) return openAiModels(["gpt-test", "whisper-1"]);
    if (c.url.endsWith("/v1/audio/transcriptions")) return { status: 200, body: { text: SEGMENTS.map((s) => s.text).join(" "), segments: SEGMENTS } };
    if (c.url.endsWith("/v1/chat/completions")) {
      const { system, user } = chatText(c);
      if (user.includes("Reply with the single word")) return openAiChat("pong", { in: 12, out: 1 });
      if (system.includes("You plan product video edits")) {
        planRequests++;
        const ids = [...user.matchAll(/File (aif_[A-Za-z0-9_-]+)/g)].map((m) => m[1]!);
        // A revision ("asked for changes") gets a shorter cut that starts with the second clip.
        const clips = user.includes("asked for changes") ? [{ fileId: ids[1], startSec: 0, endSec: 3, reason: "shorter" }] : [{ fileId: ids[0], startSec: 0.5, endSec: 4.5, reason: "opening" }, { fileId: ids[1], startSec: 1, endSec: 5, reason: "close-up" }];
        return openAiChat(JSON.stringify({ clips, title: "Acme Bottle" }));
      }
      return openAiChat("ok");
    }
    return null;
  });
  h = await startServer({ ADMIN_CONSOLE_ORIGIN: ADMIN_ORIGIN, PLATFORM_SECRETS_KEY: crypto.randomBytes(32).toString("hex"), AI_WORKFORCE_TICK_MS: "0" }, { mailer });
  admin = await consoleAdmin(h, mailer, "super_admin", "aisuper");
  ops = await consoleAdmin(h, mailer, "operations_admin", "aiops");
  support = await consoleAdmin(h, mailer, "support_admin", "aisupport");
  [owner, member, outsider] = await Promise.all(["owner", "member", "outsider"].map((n) => h.client(n)));
  orgId = (await owner.req("POST", "/v1/orgs", { name: "Acme Community", visibility: "private", joinPolicy: "invite" })).org.id;
  marketing = (await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "marketing", scope: "ORG" })).channel.id;
  general = (await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "ideas", scope: "ORG" })).channel.id;
  const code = (await owner.req("POST", `/v1/orgs/${orgId}/invites`, { role: "MEMBER", maxUses: 5 })).invite.code;
  await member.req("POST", "/v1/invites/accept", { code });
  for (const ch of [marketing, general]) await member.req("POST", `/v1/conversations/${ch}/join`).catch(() => undefined);
  outsiderOrg = (await outsider.req("POST", "/v1/orgs", { name: "Other Place", visibility: "private", joinPolicy: "invite" })).org.id;
}, 120_000);

afterAll(async () => {
  fake.restore();
  await h.close();
});

const failure = async (p: Promise<unknown>) => {
  try {
    await p;
  } catch (e) {
    return e as { status: number; body: { error: { code: string } } };
  }
  throw new Error("expected a failure");
};

describe("the AI Control Center on a fresh platform", () => {
  it("answers every view in the shape the console validates, with nothing invented", async () => {
    const b = ops.browser;
    const check = (schema: { safeParse(v: unknown): { success: boolean; error?: unknown } }, v: unknown, what: string) => {
      const r = schema.safeParse(v);
      expect(r.success, `${what}: ${JSON.stringify((r.error as { issues?: unknown })?.issues)?.slice(0, 600)}`).toBe(true);
    };
    const o = await b.expect(200, "GET", "/admin/v1/ai/workforce");
    check(AdminWorkforceOverview, o, "overview");
    expect(o.jobs.created).toBe(0);
    expect(o.issues.map((i: { area: string }) => i.area)).toEqual(expect.arrayContaining(["settings", "providers", "models", "catalog"]));
    check(AdminAiModels, await b.expect(200, "GET", "/admin/v1/ai/models"), "models");
    check(AdminCatalog, await b.expect(200, "GET", "/admin/v1/ai/catalog"), "catalog");
    check(AdminTools, await b.expect(200, "GET", "/admin/v1/ai/tools"), "tools");
    check(AdminAiEntitlements, await b.expect(200, "GET", "/admin/v1/ai/entitlements"), "entitlements");
    check(AdminOrgAi, await b.expect(200, "GET", `/admin/v1/orgs/${orgId}/ai`), "org");
    check(AdminJobs, await b.expect(200, "GET", "/admin/v1/ai/jobs"), "jobs");
  });
});

describe.skipIf(!ffmpeg)("AI Workforce acceptance scenario", () => {
  let providerId: string;
  let installId: string;
  let jobId: string;

  it("step 1: an operator configures a provider, verifies models and publishes Product Video Editor", async () => {
    const b = admin.browser;
    await b.expect(200, "PUT", "/admin/v1/settings/ai.enabled", { value: true, reason: REASON });
    providerId = (await b.expect(200, "POST", "/admin/v1/ai/providers", { kind: "openai", name: "OpenAI test", reason: REASON })).provider.id;
    await b.expect(200, "PUT", `/admin/v1/ai/providers/${providerId}/credential`, { apiKey: "sk-test-0123456789abcdef", reason: REASON });
    const tested = await b.expect(200, "POST", `/admin/v1/ai/providers/${providerId}/test`, {});
    expect(tested).toMatchObject({ ok: true, models: ["gpt-test", "whisper-1"] });
    await b.expect(200, "PATCH", `/admin/v1/ai/providers/${providerId}`, { enabled: true, reason: REASON });
    for (const model of ["gpt-test", "whisper-1"]) {
      await b.expect(200, "PUT", `/admin/v1/ai/providers/${providerId}/models`, { model, enabled: true, inputPriceMicro: 1_000_000, outputPriceMicro: 4_000_000, priceSource: "test prices 2026-10-24", reason: REASON });
    }
    await b.expect(200, "PUT", `/admin/v1/ai/providers/${providerId}/models/gpt-test/capabilities`, { capabilities: ["text"], qualityRank: 1, reason: REASON });
    await b.expect(200, "PUT", `/admin/v1/ai/providers/${providerId}/models/whisper-1/capabilities`, { capabilities: ["transcription"], minutePriceMicro: 6000, reason: REASON });
    // Not routable until a real request proves each capability.
    let models = await b.expect(200, "GET", "/admin/v1/ai/models");
    expect(models.routing.find((r: { category: string }) => r.category === "reasoning").route).toEqual([]);
    expect(await b.expect(200, "POST", `/admin/v1/ai/providers/${providerId}/models/gpt-test/verify`, { capability: "text", reason: REASON })).toMatchObject({ ok: true });
    expect(await b.expect(200, "POST", `/admin/v1/ai/providers/${providerId}/models/whisper-1/verify`, { capability: "transcription", reason: REASON })).toMatchObject({ ok: true });
    models = await b.expect(200, "GET", "/admin/v1/ai/models");
    expect(models.routing.find((r: { category: string }) => r.category === "reasoning").route[0]).toMatchObject({ providerId, model: "gpt-test" });
    expect(models.routing.find((r: { category: string }) => r.category === "transcription").route[0]).toMatchObject({ model: "whisper-1" });

    await b.expect(200, "PUT", "/admin/v1/ai/workforce/controls", { workforceEnabled: true, reason: REASON });
    const catalog = await b.expect(200, "PUT", "/admin/v1/ai/catalog/product_video_editor/1", { status: "published", reason: REASON });
    expect(catalog.agents.find((a: { id: string }) => a.id === "product_video_editor")).toMatchObject({ status: "published", ready: true });
  });

  it("step 2: a community admin installs it, renames it, gives it an avatar and a channel", async () => {
    const catalog = (await owner.req("GET", `/v1/orgs/${orgId}/ai/catalog`)).agents;
    expect(catalog.map((a: { agent: { id: string } }) => a.agent.id)).toEqual(["product_video_editor"]);
    expect(catalog[0]).toMatchObject({ available: true, setup: { ready: true } });
    const installed = (await owner.req("POST", `/v1/orgs/${orgId}/ai/agents`, { agentId: "product_video_editor", displayName: "Reel Maker" })).agent;
    installId = installed.id;
    expect(installed).toMatchObject({ displayName: "Reel Maker", effectiveMode: "review", badge: "AI", skill: { id: "video.edit", version: 1 } });
    const withAvatar = (await owner.req("PUT", `/v1/orgs/${orgId}/ai/agents/${installId}/avatar`, undefined, PNG)).agent;
    expect(withAvatar.avatar.sha256).toBe(crypto.createHash("sha256").update(PNG).digest("hex"));
    await owner.req("PUT", `/v1/orgs/${orgId}/ai/agents/${installId}/channels/${marketing}`, { activation: "mention" });
    expect((await member.req("GET", `/v1/conversations/${marketing}/ai/agents`)).agents).toMatchObject([{ id: installId, displayName: "Reel Maker", canInvoke: true }]);
    // Not in the other channel: never all channels by default.
    expect((await member.req("GET", `/v1/conversations/${general}/ai/agents`)).agents).toEqual([]);
    // A member can't manage agents, and a name can't pass for a member.
    expect((await failure(member.req("POST", `/v1/orgs/${orgId}/ai/agents`, { agentId: "product_video_editor" }))).status).toBe(403);
    const named = await failure(owner.req("PATCH", `/v1/orgs/${orgId}/ai/agents/${installId}`, { rev: withAvatar.rev, displayName: member.displayName }));
    expect(named.body.error.code).toBe("name_not_allowed");
  });

  it("step 3–4: a member asks in the channel; a real job runs and renders a real preview", async () => {
    const files = [];
    for (const [i, v] of [testVideo(6, "testsrc", 440), testVideo(6, "smptebars", 660)].entries()) {
      files.push((await member.req("POST", `/v1/orgs/${orgId}/ai/files?name=clip${i + 1}.mp4&type=video/mp4`, undefined, v)).file.id);
    }
    const job = (await member.req("POST", `/v1/orgs/${orgId}/ai/jobs`, {
      installId,
      input: { brief: "An energetic 8-second teaser for the Acme bottle.", targetSeconds: 8, captions: true, brandText: "Acme" },
      conversationId: marketing,
      fileIds: files,
      requestText: "@Reel Maker an energetic 8-second teaser for the Acme bottle",
      idempotencyKey: "acceptance-job-1",
    })).job;
    jobId = job.id;
    expect(job.state).toBe("queued");
    // The same request again is the same job (idempotency).
    expect((await member.req("POST", `/v1/orgs/${orgId}/ai/jobs`, { installId, input: { brief: "again" }, conversationId: marketing, idempotencyKey: "acceptance-job-1" })).job.id).toBe(jobId);

    await h.ctx.workforce.runtime.runUntilIdle();
    const detail = (await member.req("GET", `/v1/orgs/${orgId}/ai/jobs/${jobId}`)).job;
    expect(detail.state).toBe("awaiting_approval");
    const preview = detail.files.find((f: { kind: string }) => f.kind === "preview");
    expect(preview).toMatchObject({ mime: "video/mp4", approvalState: "pending", version: 1 });
    const bytes: Buffer = await member.req("GET", `/v1/orgs/${orgId}/ai/files/${preview.id}`);
    expect(crypto.createHash("sha256").update(bytes).digest("hex")).toBe(preview.sha256);
    expect(probeSeconds(bytes)).toBeGreaterThan(7.5);
    expect(probeSeconds(bytes)).toBeLessThan(8.6);
    expect(detail.files.some((f: { mime: string }) => f.mime === "application/x-subrip")).toBe(true);
    expect(detail.approvals).toMatchObject([{ action: "media.render", fileId: preview.id, fileSha256: preview.sha256, state: "pending" }]);
    // The channel shows the request and the preview, labelled as not end-to-end encrypted.
    const posts = (await member.req("GET", `/v1/conversations/${marketing}/ai/posts`)).posts;
    expect(posts.map((p: { kind: string }) => p.kind)).toEqual(["request", "approval"]);
    expect(posts[1]).toMatchObject({ encrypted: false, agent: { displayName: "Reel Maker", skill: "Product Video Editor", badge: "AI" } });
    expect(posts[1].files[0].id).toBe(preview.id);
  });

  it("step 5: a revision voids the old approval; approving the new preview exports the final video", async () => {
    const before = (await member.req("GET", `/v1/orgs/${orgId}/ai/jobs/${jobId}`)).job;
    const oldApproval = before.approvals[0].id;
    await member.req("POST", `/v1/orgs/${orgId}/ai/jobs/${jobId}/revise`, { note: "Shorter, and start with the second clip." });
    await h.ctx.workforce.runtime.runUntilIdle();
    const revised = (await member.req("GET", `/v1/orgs/${orgId}/ai/jobs/${jobId}`)).job;
    expect(revised.state).toBe("awaiting_approval");
    const v1 = revised.files.find((f: { kind: string; version: number }) => f.kind === "preview" && f.version === 1);
    const v2 = revised.files.find((f: { kind: string; version: number }) => f.kind === "preview" && f.version === 2);
    expect(v1.approvalState).toBe("superseded");
    expect(v2).toMatchObject({ supersedes: v1.id, approvalState: "pending" });
    expect(probeSeconds(await member.req("GET", `/v1/orgs/${orgId}/ai/files/${v2.id}`))).toBeLessThan(3.6);
    // The old approval can't be used for the new version.
    expect((await failure(member.req("POST", `/v1/orgs/${orgId}/ai/approvals/${oldApproval}`, { decision: "approved" }))).body.error.code).toBe("approval_stale");
    const current = revised.approvals.find((a: { state: string }) => a.state === "pending");
    expect(current.fileId).toBe(v2.id);
    // Under the platform's strict policy the requester can't approve their own export: an approver must.
    await admin.browser.expect(200, "PUT", "/admin/v1/ai/workforce/controls", { approvalFloor: "strict", reason: REASON });
    expect((await failure(member.req("POST", `/v1/orgs/${orgId}/ai/approvals/${current.id}`, { decision: "approved" }))).status).toBe(403);
    await admin.browser.expect(200, "PUT", "/admin/v1/ai/workforce/controls", { approvalFloor: "standard", reason: REASON });
    await member.req("POST", `/v1/orgs/${orgId}/ai/approvals/${current.id}`, { decision: "approved" });
    await h.ctx.workforce.runtime.runUntilIdle();
    const done = (await member.req("GET", `/v1/orgs/${orgId}/ai/jobs/${jobId}`)).job;
    expect(done.state).toBe("completed");
    const final = done.files.find((f: { kind: string }) => f.kind === "final");
    expect(done.output).toMatchObject({ finalFileId: final.id, previewFileId: v2.id, transcription: "provider" });
    const finalBytes: Buffer = await member.req("GET", `/v1/orgs/${orgId}/ai/files/${final.id}`);
    expect(crypto.createHash("sha256").update(finalBytes).digest("hex")).toBe(final.sha256);
    expect(probeSeconds(finalBytes)).toBeGreaterThan(2.5);
    const posts = (await member.req("GET", `/v1/conversations/${marketing}/ai/posts`)).posts;
    expect(posts.at(-1).kind).toBe("result");
    expect(posts.at(-1).files.map((f: { id: string }) => f.id)).toContain(final.id);
    expect(posts.at(-1).body).toContain("isn't part of this approval");
    // Approving the video didn't publish anything anywhere.
    expect(done.events.some((e: { kind: string; detail: { tool?: string } }) => e.kind === "tool" && e.detail.tool === "social.publish")).toBe(false);
    expect(planRequests).toBe(2);
  });

  it("step 6: the console sees the real requests, the job, usage and worker state, without content", async () => {
    const o = await ops.browser.expect(200, "GET", "/admin/v1/ai/workforce?days=1");
    expect(o.jobs.completed).toBeGreaterThanOrEqual(1);
    expect(o.usage.byCapability.map((c: { capability: string }) => c.capability).sort()).toEqual(["text", "transcription"]);
    expect(o.usage.requests).toBeGreaterThanOrEqual(4);
    expect(o.usage.mediaSeconds).toBeGreaterThan(0);
    expect(o.worker.claimed).toBeGreaterThanOrEqual(3);
    expect(o.storage.artifacts).toBeGreaterThan(0);
    expect(o.installs).toMatchObject({ active: 1, communities: 1 });
    const jobs = await ops.browser.expect(200, "GET", `/admin/v1/ai/jobs?orgId=${orgId}`);
    expect(jobs.jobs[0]).toMatchObject({ id: jobId, state: "completed", orgName: "Acme Community", agentId: "product_video_editor" });
    expect(jobs.jobs[0].modelRequests).toBeGreaterThanOrEqual(4);
    const detail = await ops.browser.expect(200, "GET", `/admin/v1/ai/jobs/${jobId}`);
    // Every Control Center answer matches the contract the console validates with.
    const check = (schema: { safeParse(v: unknown): { success: boolean; error?: unknown } }, v: unknown, what: string) => {
      const r = schema.safeParse(v);
      expect(r.success, `${what}: ${JSON.stringify((r.error as { issues?: unknown })?.issues)?.slice(0, 600)}`).toBe(true);
    };
    check(AdminWorkforceOverview, o, "overview");
    check(AdminJobs, jobs, "jobs");
    check(AdminJobDetail, detail, "job");
    check(AdminAiModels, await ops.browser.expect(200, "GET", "/admin/v1/ai/models"), "models");
    check(AdminCatalog, await ops.browser.expect(200, "GET", "/admin/v1/ai/catalog"), "catalog");
    check(AdminTools, await ops.browser.expect(200, "GET", "/admin/v1/ai/tools"), "tools");
    check(AdminAiEntitlements, await ops.browser.expect(200, "GET", "/admin/v1/ai/entitlements"), "entitlements");
    check(AdminOrgAi, await ops.browser.expect(200, "GET", `/admin/v1/orgs/${orgId}/ai`), "org");
    const text = JSON.stringify(detail);
    expect(text).not.toContain("energetic");
    expect(text).not.toContain("second clip");
    expect(text).not.toContain("clip1.mp4");
    expect(text).not.toContain("Acme bottle");
  });

  it("step 7: other communities, unauthorised operators, uninstalled agents, unassigned channels and prohibited actions are refused", async () => {
    // Another community's member can't see the job or its files.
    expect((await failure(outsider.req("GET", `/v1/orgs/${orgId}/ai/jobs/${jobId}`))).status).toBe(404);
    const someFile = (await member.req("GET", `/v1/orgs/${orgId}/ai/jobs/${jobId}`)).job.files[0].id;
    expect((await failure(outsider.req("GET", `/v1/orgs/${orgId}/ai/files/${someFile}`))).status).toBe(404);
    expect((await failure(outsider.req("GET", `/v1/orgs/${outsiderOrg}/ai/files/${someFile}`))).status).toBe(404);
    // An operator without ai.manage can't change global provider settings; an app session can't reach the console.
    await support.browser.expect(403, "PATCH", `/admin/v1/ai/providers/${providerId}`, { enabled: false, reason: REASON });
    await support.browser.expect(403, "PUT", "/admin/v1/ai/routing/reasoning", { providerId: null, model: null, fallbacks: [], prefer: "cost", maxPriceMicro: null, reason: REASON });
    expect((await failure(member.req("GET", "/admin/v1/ai/workforce"))).status).toBeGreaterThanOrEqual(400);
    // The agent isn't assigned to #ideas.
    expect((await failure(member.req("POST", `/v1/orgs/${orgId}/ai/jobs`, { installId, input: { brief: "x" }, conversationId: general }))).body.error.code).toBe("agent_not_assigned");
    // An agent that isn't installed here.
    expect((await failure(member.req("POST", `/v1/orgs/${orgId}/ai/jobs`, { installId: "agi_not_here_1234", input: { brief: "x" } }))).status).toBe(404);
    // Payments can't be allowed for agents, by anyone.
    await admin.browser.expect(400, "PUT", "/admin/v1/ai/tools/payments.execute", { mode: "approval", reason: REASON });
    await admin.browser.expect(400, "PUT", "/admin/v1/ai/tools/social.publish", { mode: "allowed", reason: REASON });
    // Uninstalled: gone for new jobs.
    await owner.req("DELETE", `/v1/orgs/${orgId}/ai/agents/${installId}`);
    expect((await failure(member.req("POST", `/v1/orgs/${orgId}/ai/jobs`, { installId, input: { brief: "x" } }))).status).toBe(404);
  });
});

describe.skipIf(ffmpeg)("AI Workforce acceptance scenario (skipped)", () => {
  it("needs ffmpeg and ffprobe on this machine", () => {
    expect(ffmpeg).toBe(false);
  });
});
