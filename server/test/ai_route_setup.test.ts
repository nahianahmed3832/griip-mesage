import crypto from "node:crypto";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { isMp3, sniffImage } from "../src/modules/ai/index.js";
import { proofOf, VERIFY_REQUESTS } from "../src/modules/ai/verification.js";
import { MemoryMailer } from "./support/accounts.js";
import { ADMIN_ORIGIN, type ConsoleAdmin, consoleAdmin } from "./support/adminConsole.js";
import { type Answer, chatText, type FakeCall, fakeProviders, openAiChat } from "./support/fakeAi.js";
import { type Harness, startServer } from "./support/harness.js";
import { type Community, community, configurePlatform, failure, install, REASON } from "./support/workforce.js";

/**
 * Capability routes (docs/AI_WORKFORCE.md §4): each of the seven categories is set up on its own with a real request
 * whose answer must be the right kind of output, and only then activated; each route reports one of six states; a new
 * key clears every verification; a request no model could serve is refused instead of queued; a failed job says where
 * it stopped and how to recover; verifications are counted apart from agents' usage. The provider is a fake
 * (support/fakeAi.ts): these tests prove Meldiq's side of it, not a provider.
 */
const mailer = new MemoryMailer();
let h: Harness;
let admin: ConsoleAdmin;
let providerId: string;
let a: Community;
let general: string;

const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==", "base64");
const MP3 = Buffer.concat([Buffer.from("ID3"), Buffer.alloc(64)]);
/** Per model, what the fake provider does instead of answering properly. */
const misbehave = new Map<string, "blind" | "html" | "status401" | "status404" | "status429" | "empty">();

function modelOf(c: FakeCall): string {
  if (c.body instanceof FormData) return String(c.body.get("model"));
  return String(c.json?.model ?? "");
}

function answer(c: FakeCall): Answer | null {
  if (c.url.endsWith("/v1/models")) return { status: 200, body: { data: [{ id: "gpt-test" }] } };
  const model = modelOf(c);
  const bad = misbehave.get(model);
  if (bad === "status401") return { status: 401, body: { error: { message: "bad key sk-echo" } } };
  if (bad === "status404") return { status: 404, body: { error: { message: "model_not_found" } } };
  if (bad === "status429") return { status: 429, body: { error: { message: "insufficient_quota" } } };
  if (c.url.endsWith("/v1/chat/completions")) {
    const { user } = chatText(c);
    if (bad === "empty") return openAiChat("");
    if (user.includes("What colour is this image")) return openAiChat(bad === "blind" ? "Blue" : "Red");
    if (user.includes("Reply with the single word")) return openAiChat("pong");
    return openAiChat("Ready.");
  }
  if (c.url.endsWith("/v1/audio/transcriptions")) return { status: 200, body: { text: "" } };
  if (c.url.endsWith("/v1/audio/speech")) return { status: 200, bytes: bad === "html" ? Buffer.from("<html>error</html>") : MP3 };
  if (c.url.endsWith("/v1/images/generations")) return { status: 200, body: { data: [{ b64_json: (bad === "html" ? Buffer.from("<html>") : PNG).toString("base64") }] } };
  if (c.url.endsWith("/v1/embeddings")) return { status: 200, body: { data: bad === "empty" ? [] : [{ embedding: [0.1, -0.2, 0.3] }], usage: { prompt_tokens: 1 } } };
  return null;
}

beforeAll(async () => {
  fakeProviders(answer);
  h = await startServer({ ADMIN_CONSOLE_ORIGIN: ADMIN_ORIGIN, PLATFORM_SECRETS_KEY: crypto.randomBytes(32).toString("hex"), AI_WORKFORCE_TICK_MS: "0" }, { mailer });
  admin = await consoleAdmin(h, mailer, "super_admin", "rtsuper");
  providerId = await configurePlatform(admin, { models: [{ model: "gpt-test", capabilities: ["text"] }], agents: ["general_assistant"] });
  a = await community(h, "Routes");
  general = await install(a, "general_assistant");
}, 120_000);

afterAll(async () => h.close());

type Route = {
  category: string;
  status: string;
  statusDetail: string;
  providerId: string | null;
  model: string | null;
  fallbacks: { model: string }[];
  verification: { ok: boolean | null; detail: string | null } | null;
  excluded: { model: string; code: string }[];
};
const routes = async () => new Map(((await admin.browser.expect(200, "GET", "/admin/v1/ai/models")).routing as Route[]).map((r) => [r.category, r]));
const setup = (category: string, model: string) => admin.browser.expect(200, "POST", `/admin/v1/ai/routing/${category}/setup`, { providerId, model, reason: REASON });
const overview = () => admin.browser.expect(200, "GET", "/admin/v1/ai/workforce?days=7");
const controls = (body: Record<string, unknown>) => admin.browser.expect(200, "PUT", "/admin/v1/ai/workforce/controls", { ...body, reason: REASON });
const setCaps = (model: string, caps: string[]) => admin.browser.expect(200, "PUT", `/admin/v1/ai/providers/${providerId}/models/${model}/capabilities`, { capabilities: caps, reason: REASON });
const ask = (brief = "Say hello.") => a.member.req("POST", `/v1/orgs/${a.orgId}/ai/jobs`, { installId: general, input: { brief } }).then((r) => r.job as { id: string });

const SETUPS = [
  ["reasoning", "gpt-reason"],
  ["fast", "gpt-test"],
  ["vision", "gpt-vision"],
  ["transcription", "whisper-test"],
  ["speech", "tts-test"],
  ["image_generation", "img-test"],
  ["embeddings", "emb-test"],
] as const;

describe("what a verification answer must prove", () => {
  it("recognises the formats Meldiq consumes, not just any bytes", () => {
    expect(sniffImage(PNG)).toBe("image/png");
    expect(sniffImage(Buffer.from([0xff, 0xd8, 0xff, 0xe0]))).toBe("image/jpeg");
    expect(sniffImage(Buffer.from("<html>"))).toBeNull();
    expect(isMp3(MP3)).toBe(true);
    expect(isMp3(Buffer.from([0xff, 0xfb, 0x90]))).toBe(true);
    expect(isMp3(Buffer.from("{\"error\":1}"))).toBe(false);
  });

  it("the vision test shows a real image, not a single pixel models misread", () => {
    const inv = VERIFY_REQUESTS.vision;
    if (!("chat" in inv)) throw new Error("vision verification is a chat request");
    const png = Buffer.from(inv.chat.messages[0]!.images![0]!.base64, "base64");
    expect(sniffImage(png)).toBe("image/png");
    expect([png.readUInt32BE(16), png.readUInt32BE(20)]).toEqual([64, 64]);
  });

  it("an answer of the wrong kind proves nothing, and says what to do", () => {
    const chat = (text: string) => ({ capability: "text" as const, chat: { text, inputTokens: 1, outputTokens: 1 } });
    expect(proofOf("text", chat("pong")).ok).toBe(true);
    expect(proofOf("text", chat("  ")).ok).toBe(false);
    expect(proofOf("vision", chat("Red.")).ok).toBe(true);
    const blind = proofOf("vision", chat("I can't see images."));
    expect(blind).toMatchObject({ ok: false, nextStep: expect.stringMatching(/Pick another model/) });
    const bin = (bytes: Buffer, cap: "speech" | "image_generation") => ({ capability: cap, binary: { bytes, mime: "image/png" } });
    expect(proofOf("speech", bin(PNG, "speech")).ok).toBe(false);
    expect(proofOf("image_generation", bin(MP3, "image_generation")).ok).toBe(false);
    expect(proofOf("image_generation", bin(PNG, "image_generation")).ok).toBe(true);
    const vec = (vectors: number[][]) => ({ capability: "embeddings" as const, embeddings: { vectors, inputTokens: null } });
    expect(proofOf("embeddings", vec([[0.1, 0.2]]))).toEqual({ ok: true, detail: "2 dimensions" });
    expect(proofOf("embeddings", vec([])).ok).toBe(false);
    expect(proofOf("embeddings", vec([[0.1, Number.NaN]])).ok).toBe(false);
  });
});

describe("routes", () => {
  it("each route has its own state: a verified text model doesn't make the other six ready", async () => {
    const r = await routes();
    expect(r.get("reasoning")!.status).toBe("ready");
    expect(r.get("fast")!.status).toBe("ready");
    for (const c of ["vision", "transcription", "speech", "image_generation", "embeddings"]) {
      expect(r.get(c)!.status).toBe("not_configured");
      expect(r.get(c)!.statusDetail).toMatch(/Use Set up/);
    }
    const o = await overview();
    expect(o.issues.filter((i: { area: string }) => i.area === "models").map((i: { message: string }) => i.message)).toEqual([
      "No verified model for image understanding.",
      "No verified model for speech to text.",
      "No verified model for text to speech.",
      "No verified model for image generation.",
      "No verified model for embeddings.",
    ]);
  });

  it("a failed set-up leaves the route as it was and names the next step", async () => {
    const cases = [
      ["blind", /didn't name the test image's colour/, /Pick another model/],
      ["status401", /^auth \(HTTP 401\)$/, /refused the key/],
      ["status404", /^bad_request \(HTTP 404\)$/, /model id may be wrong/],
      ["status429", /^rate_limited \(HTTP 429\)$/, /out of credit/],
    ] as const;
    for (const [how, detail, next] of cases) {
      const model = `gpt-${how}`;
      misbehave.set(model, how);
      const v = await setup("vision", model);
      expect(v).toMatchObject({ ok: false, activated: false });
      expect(v.detail).toMatch(detail);
      expect(v.nextStep).toMatch(next);
      expect(JSON.stringify(v)).not.toContain("sk-");
    }
    const vision = (await routes()).get("vision")!;
    expect(vision.model).toBeNull();
    expect(vision.status).toBe("failed");
    expect(vision.statusDetail).toMatch(/Verification failed for gpt-blind/);

    misbehave.set("tts-bad", "html");
    expect(await setup("speech", "tts-bad")).toMatchObject({ ok: false, activated: false, detail: expect.stringMatching(/aren't MP3 audio/) });
    misbehave.set("img-bad", "html");
    expect(await setup("image_generation", "img-bad")).toMatchObject({ ok: false, detail: expect.stringMatching(/aren't an image/) });
    misbehave.set("emb-bad", "empty");
    expect(await setup("embeddings", "emb-bad")).toMatchObject({ ok: false, detail: "no vector for the input" });
  });

  it("set-up approves, turns on, verifies with a real request and activates each of the seven routes", async () => {
    for (const [category, model] of SETUPS) {
      const v = await setup(category, model);
      expect(v, category).toMatchObject({ ok: true, activated: true, nextStep: null });
      const r = v.models.routing.find((x: Route) => x.category === category) as Route;
      expect(r.status).toBe("ready");
      expect([r.providerId, r.model]).toEqual([providerId, model]);
      expect(r.verification).toMatchObject({ ok: true });
    }
    const r = await routes();
    expect([...r.values()].every((x) => x.status === "ready")).toBe(true);
    expect(r.get("speech")!.verification!.detail).toMatch(/^audio\/mpeg, \d+ bytes$/);
    expect(r.get("image_generation")!.verification!.detail).toMatch(/^image\/png/);
    expect(r.get("embeddings")!.verification!.detail).toBe("3 dimensions");
    // gpt-test served `fast` by `auto` before; making gpt-reason reasoning's default kept nothing else from it.
    expect(r.get("reasoning")!.fallbacks).toEqual([]);
    const o = await overview();
    expect(o.issues.some((i: { area: string }) => i.area === "models")).toBe(false);
    // Verifications are real provider calls, counted apart from what agents used.
    expect(o.usage.verifications.requests).toBeGreaterThanOrEqual(15);
    expect(o.usage.requests).toBe(0);
    const audit = h.ctx.db.prepare("SELECT action, COUNT(*) n FROM platform_audit WHERE reason = ? GROUP BY action").all(REASON) as { action: string; n: number }[];
    expect(audit.map((x) => x.action)).toEqual(expect.arrayContaining(["ai.model_set", "ai.capability_verify", "ai.routing_set"]));
  });

  it("a newer default keeps the previous one as its first fallback", async () => {
    await setup("reasoning", "gpt-test");
    const r = (await routes()).get("reasoning")!;
    expect(r.model).toBe("gpt-test");
    expect(r.fallbacks.map((f) => f.model)).toEqual(["gpt-reason"]);
  });

  it("a cost ceiling the model can't show it meets keeps it out: disabled by policy", async () => {
    const body = (max: number | null) => ({ providerId, model: "img-test", fallbacks: [], prefer: "quality", maxPriceMicro: max, reason: REASON });
    await admin.browser.expect(200, "PUT", "/admin/v1/ai/routing/image_generation", body(1));
    expect((await routes()).get("image_generation")).toMatchObject({ status: "disabled", statusDetail: expect.stringMatching(/cost ceiling/) });
    await admin.browser.expect(200, "PUT", "/admin/v1/ai/routing/image_generation", body(null));
    expect((await routes()).get("image_generation")!.status).toBe("ready");
  });

  it("a failing provider makes its routes unavailable for now, without refusing requests", async () => {
    const at = Date.now();
    const ins = h.ctx.db.prepare("INSERT INTO ai_usage (at, provider_id, model, feature, outcome, status, tokens_source, error_kind) VALUES (?, ?, 'gpt-test', 'workforce', 'error', 500, 'none', 'server')");
    for (let i = 0; i < 3; i++) ins.run(at + i, providerId);
    expect((await routes()).get("fast")).toMatchObject({ status: "unavailable", statusDetail: expect.stringMatching(/recent requests all failed/) });
    await controls({ maxConcurrency: 0 });
    const j = await ask();
    expect(h.ctx.workforce.jobs.get(j.id)!.state).toBe("queued");
    h.ctx.db.prepare("DELETE FROM ai_usage WHERE feature = 'workforce' AND error_kind = 'server' AND at >= ?").run(at);
    h.ctx.workforce.jobs.requestCancel(j.id, "cancelled_by_operator");
    await controls({ maxConcurrency: 2 });
    await h.ctx.workforce.runtime.runUntilIdle();
  });

  it("only operators with ai.manage set routes up", async () => {
    const billing = await consoleAdmin(h, mailer, "billing_admin", "rtbilling");
    await billing.browser.expect(403, "POST", "/admin/v1/ai/routing/vision/setup", { providerId, model: "gpt-vision", reason: REASON });
    expect((await failure(a.owner.req("POST", "/admin/v1/ai/routing/vision/setup", { providerId, model: "gpt-vision", reason: REASON }))).status).toBeGreaterThanOrEqual(400);
  });

  it("a new key clears every verification: requests are refused until the routes are verified again", async () => {
    await admin.browser.expect(200, "PUT", `/admin/v1/ai/providers/${providerId}/credential`, { apiKey: "sk-rotated-0123456789abcdef", reason: REASON });
    const r = await routes();
    expect([...r.values()].every((x) => x.status === "untested")).toBe(true);
    expect(r.get("fast")!.verification).toMatchObject({ ok: null, detail: "The provider's key changed: verify again." });
    const refused = await failure(ask());
    expect(refused.body.error.code).toBe("ai_not_configured");
    for (const [category, model] of SETUPS) expect((await setup(category, model)).activated).toBe(true);
    expect([...(await routes()).values()].every((x) => x.status === "ready")).toBe(true);
  });
});

describe("failed jobs", () => {
  it("a job that lost its model says where it stopped; a retry is refused until the route works, then runs", async () => {
    await controls({ maxConcurrency: 0 });
    const j = await ask("Plan the launch.");
    for (const m of ["gpt-test", "gpt-reason", "gpt-vision"]) await setCaps(m, []);
    await controls({ maxConcurrency: 2 });
    await h.ctx.workforce.runtime.runUntilIdle();
    expect(h.ctx.workforce.jobs.get(j.id)).toMatchObject({ state: "failed", error_code: "ai_not_configured" });

    const detail = await admin.browser.expect(200, "GET", `/admin/v1/ai/jobs/${j.id}`);
    expect(detail.job.modelRequests).toBe(0);
    expect(detail.diagnosis).toMatchObject({ stage: "before any model request", category: "fast", retryReady: false, recovery: expect.stringMatching(/Set up the quick replies/) });
    const o = await overview();
    expect(o.jobs.failedBeforeModel).toBeGreaterThanOrEqual(1);
    expect(o.issues.some((i: { area: string; message: string }) => i.area === "jobs" && /never reached a model/.test(i.message))).toBe(true);

    const early = await failure(a.member.req("POST", `/v1/orgs/${a.orgId}/ai/jobs/${j.id}/retry`));
    expect(early.body.error.code).toBe("ai_not_configured");
    expect(h.ctx.workforce.jobs.get(j.id)!.state).toBe("failed");

    expect((await setup("fast", "gpt-test")).activated).toBe(true);
    expect((await admin.browser.expect(200, "GET", `/admin/v1/ai/jobs/${j.id}`)).diagnosis).toMatchObject({ retryReady: true, recovery: expect.stringMatching(/works now/) });
    await a.member.req("POST", `/v1/orgs/${a.orgId}/ai/jobs/${j.id}/retry`);
    await h.ctx.workforce.runtime.runUntilIdle();
    expect(h.ctx.workforce.jobs.get(j.id)!.state).toBe("completed");
    // The failure stays in the job's history; the retry's model requests are real usage.
    const kinds = h.ctx.workforce.jobs.events(j.id).map((e) => e.kind);
    expect(kinds).toEqual(expect.arrayContaining(["failed", "retried"]));
    expect(kinds.indexOf("failed")).toBeLessThan(kinds.lastIndexOf("retried"));
    expect((await overview()).usage.requests).toBeGreaterThan(0);
    expect((await admin.browser.expect(200, "GET", `/admin/v1/ai/jobs/${j.id}`)).diagnosis).toBeNull();
  });
});
