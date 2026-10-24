import crypto from "node:crypto";
import { describe, expect, it } from "vitest";
import { AiService, type Fetch, SecretBox } from "../src/modules/ai/index.js";
import { QuotaEngine } from "../src/modules/quotas/index.js";
import { openDatabase } from "../src/infrastructure/database/db.js";
import { checkUrl, isPublicAddress } from "../src/modules/workforce/webFetch.js";

/**
 * The capability gateway and routing (docs/AI_WORKFORCE.md §4): what each adapter sends, that only verified
 * capabilities are routed, the order `auto` tries models in, fallback across models, the circuit breaker, cost
 * ceilings, entitlements, the kill switch and quotas before any call. Fake providers: nothing leaves this machine.
 */
interface Call {
  url: string;
  headers: Record<string, string>;
  body?: string | FormData;
}

function setup(script: (c: Call) => { status: number; body?: unknown; bytes?: Buffer }, opts: { enabled?: boolean } = {}) {
  const db = openDatabase(":memory:");
  const quotas = new QuotaEngine(db);
  const calls: Call[] = [];
  const fetchFn: Fetch = async (url, init) => {
    const c = { url, headers: init.headers, body: init.body };
    calls.push(c);
    const a = script(c);
    return { status: a.status, json: async () => a.body ?? {}, arrayBuffer: async () => new Uint8Array(a.bytes ?? Buffer.alloc(0)).buffer };
  };
  let enabled = opts.enabled ?? true;
  const ai = new AiService(db, quotas, new SecretBox(crypto.randomBytes(32)), { fetch: fetchFn, enabled: () => enabled });
  const provider = (kind: "openai" | "anthropic" | "google", name: string, models: { model: string; caps: string[]; out?: number | null }[]) => {
    const p = ai.createProvider(kind, name, "test");
    ai.setCredential(p.id, `sk-${name}-0123456789abcdef`);
    ai.updateProvider(p.id, { enabled: true, maxRetries: 0 });
    for (const m of models) {
      ai.setModel(p.id, m.model, { enabled: true, inputPriceMicro: m.out === undefined ? null : 1, outputPriceMicro: m.out ?? null, priceSource: m.out === undefined ? "" : "test" });
      ai.routing.setCapabilities(p.id, kind, m.model, m.caps as never);
      for (const c of m.caps) ai.routing.recordVerification(p.id, m.model, c as never, true, "ok");
    }
    return p.id;
  };
  return { db, quotas, ai, calls, provider, setEnabled: (v: boolean) => (enabled = v) };
}

const ctx = { feature: "workforce" as const, userId: "u_1", orgId: "o_1" };
const text = (t: string) => ({ capability: "text" as const, category: "reasoning" as const, chat: { system: "sys", messages: [{ role: "user" as const, text: t }], maxOutputTokens: 100 } });
const okChat = { status: 200, body: { choices: [{ message: { content: "hi" } }], usage: { prompt_tokens: 10, completion_tokens: 2 } } };

describe("adapters", () => {
  it("send each provider's own request shape, with the key in a header", async () => {
    const t = setup((c) => {
      if (c.url.includes("anthropic")) return { status: 200, body: { content: [{ type: "text", text: "a" }], usage: { input_tokens: 5, output_tokens: 1 } } };
      if (c.url.includes("generativelanguage")) return { status: 200, body: { candidates: [{ content: { parts: [{ text: "g" }] } }], usageMetadata: { promptTokenCount: 5, candidatesTokenCount: 1 } } };
      return okChat;
    });
    const an = t.provider("anthropic", "an", [{ model: "claude-x", caps: ["vision"] }]);
    const go = t.provider("google", "go", [{ model: "gemini-x", caps: ["vision"] }]);
    const img = [{ mime: "image/png" as const, base64: "AAAA" }];
    const vision = { capability: "vision" as const, category: "vision" as const, chat: { system: "s", messages: [{ role: "user" as const, text: "look", images: img }], maxOutputTokens: 50, json: true } };
    await t.ai.gateway.send({ providerId: an, kind: "anthropic", model: "claude-x" }, vision, 5000);
    await t.ai.gateway.send({ providerId: go, kind: "google", model: "gemini-x" }, vision, 5000);
    const [a, g] = t.calls;
    expect(a!.url).toBe("https://api.anthropic.com/v1/messages");
    expect(a!.headers["x-api-key"]).toMatch(/^sk-an-/);
    expect(JSON.parse(a!.body as string)).toMatchObject({ system: "s", messages: [{ role: "user", content: [{ type: "image", source: { type: "base64", media_type: "image/png", data: "AAAA" } }, { type: "text", text: "look" }] }] });
    expect(g!.url).toBe("https://generativelanguage.googleapis.com/v1beta/models/gemini-x:generateContent");
    expect(g!.headers["x-goog-api-key"]).toMatch(/^sk-go-/);
    expect(JSON.parse(g!.body as string)).toMatchObject({ systemInstruction: { parts: [{ text: "s" }] }, generationConfig: { responseMimeType: "application/json" } });
    expect(t.calls.every((c) => !c.url.includes("sk-"))).toBe(true);
  });

  it("transcription, speech, images and embeddings on OpenAI; images only inline", async () => {
    const t = setup((c) => {
      if (c.url.endsWith("/audio/transcriptions")) return { status: 200, body: { text: "hello", segments: [{ start: 0, end: 1, text: "hello" }] } };
      if (c.url.endsWith("/audio/speech")) return { status: 200, bytes: Buffer.from([0xff, 0xfb, 1, 2]) };
      if (c.url.endsWith("/images/generations")) return { status: 200, body: { data: [{ url: "https://example.test/evil.png" }] } };
      if (c.url.endsWith("/embeddings")) return { status: 200, body: { data: [{ embedding: [0.1, 0.2] }], usage: { prompt_tokens: 3 } } };
      return okChat;
    });
    const p = t.provider("openai", "oa", [{ model: "whisper-1", caps: ["transcription"] }, { model: "tts-1", caps: ["speech"] }, { model: "gpt-image-1", caps: ["image_generation"] }, { model: "emb", caps: ["embeddings"] }]);
    const tr = await t.ai.gateway.invoke(ctx, { capability: "transcription", category: "transcription", audio: { bytes: Buffer.from("RIFF"), mime: "audio/wav", name: "a.wav", seconds: 1 } });
    expect(tr.result).toMatchObject({ transcript: { text: "hello", timing: "provider" } });
    expect(t.calls[0]!.body).toBeInstanceOf(FormData);
    expect((t.calls[0]!.body as FormData).get("response_format")).toBe("verbose_json");
    expect(t.calls[0]!.headers["content-type"]).toBeUndefined();
    const sp = await t.ai.gateway.invoke(ctx, { capability: "speech", category: "speech", text: "hi" });
    expect(sp.result).toMatchObject({ binary: { mime: "audio/mpeg" } });
    const em = await t.ai.gateway.invoke(ctx, { capability: "embeddings", category: "embeddings", texts: ["a"] });
    expect(em.result).toMatchObject({ embeddings: { vectors: [[0.1, 0.2]] } });
    // A URL instead of inline bytes is refused, never fetched.
    await expect(t.ai.gateway.invoke(ctx, { capability: "image_generation", category: "image_generation", prompt: "x" })).rejects.toMatchObject({ code: "provider_error" });
    expect(t.calls.some((c) => c.url.includes("example.test"))).toBe(false);
    expect(p).toBeTruthy();
  });

  it("refuses capabilities an adapter can't send", () => {
    const t = setup(() => okChat);
    const p = t.ai.createProvider("anthropic", "an", "test");
    t.ai.setModel(p.id, "claude-x", { enabled: true, inputPriceMicro: null, outputPriceMicro: null, priceSource: "" });
    expect(() => t.ai.routing.setCapabilities(p.id, "anthropic", "claude-x", ["speech"])).toThrow(/can't send/);
  });
});

describe("routing", () => {
  it("routes only verified capabilities; a failed verification takes a model out", async () => {
    const t = setup(() => okChat);
    const p = t.provider("openai", "oa", [{ model: "m1", caps: ["text"] }]);
    expect(t.ai.routing.route("reasoning", null).ordered).toHaveLength(1);
    t.ai.routing.recordVerification(p, "m1", "text", false, "auth");
    const r = t.ai.routing.route("reasoning", null);
    expect(r.ordered).toEqual([]);
    expect(r.excluded).toEqual([{ providerId: p, model: "m1", why: "last verification failed" }]);
    await expect(t.ai.gateway.invoke(ctx, text("x"))).rejects.toMatchObject({ code: "ai_not_configured" });
    expect(t.calls).toHaveLength(0);
  });

  it("tries the default, then fallbacks, then the rest by preference; records the route", async () => {
    const t = setup((c) => (c.url.includes("openai") && JSON.parse(c.body as string).model === "a" ? { status: 503 } : okChat));
    const p = t.provider("openai", "oa", [
      { model: "a", caps: ["text"], out: 10 },
      { model: "b", caps: ["text"], out: 5 },
      { model: "c", caps: ["text"], out: 1 },
    ]);
    t.ai.routing.setPolicy({ category: "reasoning", providerId: p, model: "a", fallbacks: [{ providerId: p, model: "b" }], prefer: "cost", maxPriceMicro: null }, "test");
    expect(t.ai.routing.route("reasoning", null).ordered.map((o) => [o.candidate.model, o.reason])).toEqual([
      ["a", "auto:reasoning default"],
      ["b", "auto:reasoning fallback 1"],
      ["c", "auto:reasoning by cost"],
    ]);
    const out = await t.ai.gateway.invoke(ctx, text("x"));
    expect(out).toMatchObject({ model: "b", route: "auto:reasoning fallback 1" });
    const rows = t.db.prepare("SELECT model, outcome, route FROM ai_usage ORDER BY id").all();
    expect(rows).toEqual([
      { model: "a", outcome: "error", route: "auto:reasoning default" },
      { model: "b", outcome: "ok", route: "auto:reasoning fallback 1" },
    ]);
  });

  it("skips a failing provider (circuit) and models above a cost ceiling or outside the entitlement", async () => {
    const t = setup(() => ({ status: 500 }));
    const p = t.provider("openai", "oa", [
      { model: "cheap", caps: ["text"], out: 1 },
      { model: "pricey", caps: ["text"], out: 100 },
      { model: "unpriced", caps: ["text"] },
    ]);
    t.ai.routing.setPolicy({ category: "reasoning", providerId: null, model: null, fallbacks: [], prefer: "quality", maxPriceMicro: 10 }, "test");
    const r = t.ai.routing.route("reasoning", null);
    expect(r.ordered.map((o) => o.candidate.model)).toEqual(["cheap"]);
    expect(r.excluded.map((e) => e.why).sort()).toEqual(["above this category's cost ceiling", "no price entered; this category has a cost ceiling"]);
    expect(t.ai.routing.route("reasoning", new Set(["nobody/none"])).ordered).toEqual([]);
    for (let i = 0; i < 3; i++) await expect(t.ai.gateway.invoke(ctx, text("x"))).rejects.toMatchObject({ code: "provider_error" });
    expect(t.ai.routing.circuitOpen(p)).toBe(true);
    expect(t.ai.routing.route("reasoning", null).excluded.some((e) => e.why.includes("circuit open"))).toBe(true);
  });

  it("refuses before calling: kill switch, size, quota", async () => {
    const t = setup(() => okChat);
    t.provider("openai", "oa", [{ model: "m", caps: ["text"], out: 1 }]);
    t.setEnabled(false);
    await expect(t.ai.gateway.invoke(ctx, text("x"))).rejects.toMatchObject({ code: "ai_disabled" });
    t.setEnabled(true);
    await expect(t.ai.gateway.invoke(ctx, text("x".repeat(200_001)))).rejects.toMatchObject({ code: "invalid_policy" });
    t.quotas.setPolicy({ key: "ai.requests", scopeType: "org", scopeId: "o_1", limit: 0, mode: "block", reason: "test" }, "test");
    await expect(t.ai.gateway.invoke(ctx, text("x"))).rejects.toMatchObject({ statusCode: 429 });
    expect(t.calls).toHaveLength(0);
    expect(t.db.prepare("SELECT outcome, error_kind FROM ai_usage").all()).toEqual([{ outcome: "blocked", error_kind: "quota:ai.requests" }]);
  });

  it("verification makes one real minimal request and records only its outcome", async () => {
    const t = setup(() => okChat);
    const p = t.provider("openai", "oa", [{ model: "m", caps: ["text"] }]);
    const v = await t.ai.gateway.verify(p, "m", "text");
    expect(v).toMatchObject({ ok: true, detail: "answered (2 tokens)" });
    expect(t.ai.routing.capabilities(p, "openai", "m").find((c) => c.capability === "text")).toMatchObject({ verifyOk: true, verifyDetail: "answered (2 tokens)" });
  });
});

describe("web fetch guard", () => {
  it("allows only public https addresses on the standard port", async () => {
    for (const ip of ["127.0.0.1", "10.1.2.3", "172.16.0.9", "192.168.1.1", "169.254.169.254", "100.64.0.1", "::1", "fd00::1", "fe80::1", "::ffff:127.0.0.1", "::ffff:7f00:1", "::ffff:a9fe:a9fe", "::127.0.0.1", "64:ff9b:1::a", "2002:7f00:1::", "fec0::1", "0.0.0.0"]) expect(isPublicAddress(ip), ip).toBe(false);
    for (const ip of ["93.184.216.34", "1.1.1.1", "::ffff:93.184.216.34", "2606:4700::1111"]) expect(isPublicAddress(ip), ip).toBe(true);
    const resolve = async (host: string) => (host === "evil.test" ? [{ address: "93.184.216.34", family: 4 }, { address: "10.0.0.5", family: 4 }] : [{ address: "93.184.216.34", family: 4 }]);
    await expect(checkUrl("http://example.com/", resolve)).rejects.toMatchObject({ code: "unsafe_url" });
    await expect(checkUrl("https://user:pw@example.com/", resolve)).rejects.toMatchObject({ code: "unsafe_url" });
    await expect(checkUrl("https://example.com:8443/", resolve)).rejects.toMatchObject({ code: "unsafe_url" });
    await expect(checkUrl("https://localhost/", resolve)).rejects.toMatchObject({ code: "unsafe_url" });
    await expect(checkUrl("https://169.254.169.254/latest/meta-data", resolve)).rejects.toMatchObject({ code: "unsafe_url" });
    // One private address among the answers is enough to refuse (DNS rebinding).
    await expect(checkUrl("https://evil.test/", resolve)).rejects.toMatchObject({ code: "unsafe_url" });
    expect(await checkUrl("https://example.com/page", resolve)).toMatchObject({ address: "93.184.216.34" });
  });
});
