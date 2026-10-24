import crypto from "node:crypto";
import { describe, expect, it } from "vitest";
import { AiService, costMicro, type Fetch, SecretBox, secretsKey } from "../src/modules/ai/index.js";
import { QuotaEngine } from "../src/modules/quotas/index.js";
import { openDatabase } from "../src/infrastructure/database/db.js";

/**
 * AI operations (docs/AI_OPERATIONS.md): credentials sealed and never returned, connection tests that really talk to
 * the provider, the gateway's kill switch, allowlist, quota reservations settled with the provider's own counts,
 * bounded retries and an explicit fallback, and usage rows that hold counts only. The provider is a fake: no request
 * leaves this machine.
 */

const KEY = crypto.randomBytes(32);
const API_KEY = "sk-test-0123456789abcdefghijklmnop";

interface Call {
  url: string;
  headers: Record<string, string>;
  body?: string;
}

function fakeProvider(script: ((c: Call) => { status: number; body: unknown }) | { status: number; body: unknown }[]) {
  const calls: Call[] = [];
  const fetchFn: Fetch = async (url, init) => {
    const c = { url, headers: init.headers, body: init.body };
    calls.push(c);
    const answer = Array.isArray(script) ? (script.shift() ?? { status: 500, body: {} }) : script(c);
    return { status: answer.status, json: async () => answer.body };
  };
  return { calls, fetchFn };
}

function setup(fetchFn: Fetch, opts: { enabled?: boolean; key?: Buffer | null } = {}) {
  const db = openDatabase(":memory:");
  const quotas = new QuotaEngine(db);
  let enabled = opts.enabled ?? true;
  const ai = new AiService(db, quotas, new SecretBox(opts.key === undefined ? KEY : opts.key), { fetch: fetchFn, enabled: () => enabled });
  return { db, quotas, ai, setEnabled: (v: boolean) => (enabled = v) };
}

const anthropicOk = (input = 12, output = 5) => ({ status: 200, body: { content: [{ type: "text", text: "pong" }], usage: { input_tokens: input, output_tokens: output } } });

describe("credentials", () => {
  it("are sealed to their provider, never returned, and can't be stored without the server's key", () => {
    const box = new SecretBox(KEY);
    const sealed = box.seal(API_KEY, "ai_provider:a");
    expect(sealed).not.toContain(API_KEY);
    expect(box.open(sealed, "ai_provider:a")).toBe(API_KEY);
    expect(() => box.open(sealed, "ai_provider:b")).toThrow();
    expect(() => new SecretBox(null).seal(API_KEY, "x")).toThrow(/PLATFORM_SECRETS_KEY/);
    expect(secretsKey({ PLATFORM_SECRETS_KEY: KEY.toString("hex") })?.equals(KEY)).toBe(true);
    expect(() => secretsKey({ PLATFORM_SECRETS_KEY: "short" })).toThrow(/32 bytes/);

    const { db, ai } = setup(fakeProvider([]).fetchFn);
    const p = ai.createProvider("anthropic", "Primary", "admin");
    ai.setCredential(p.id, API_KEY);
    const view = JSON.stringify(ai.providers());
    expect(view).not.toContain(API_KEY);
    expect(ai.providers()[0]!.credential.configured).toBe(true);
    expect(JSON.stringify(db.prepare("SELECT * FROM ai_providers").all())).not.toContain(API_KEY);
    const { ai: keyless } = setup(fakeProvider([]).fetchFn, { key: null });
    const q = keyless.createProvider("openai", "No key", "admin");
    expect(() => keyless.setCredential(q.id, API_KEY)).toThrow(/PLATFORM_SECRETS_KEY/);
  });
});

describe("connection tests", () => {
  it("make a real authenticated request to the fixed endpoint and record what happened", async () => {
    const { calls, fetchFn } = fakeProvider([
      { status: 200, body: { data: [{ id: "claude-test-1" }, { id: "../etc" }] } },
      { status: 401, body: { error: { message: `bad key ${API_KEY}` } } },
    ]);
    const { ai } = setup(fetchFn);
    const p = ai.createProvider("anthropic", "Primary", "admin");
    ai.setCredential(p.id, API_KEY);
    const ok = await ai.testConnection(p.id);
    expect(ok).toMatchObject({ ok: true, models: ["claude-test-1"] });
    expect(calls[0]!.url).toBe("https://api.anthropic.com/v1/models?limit=100");
    expect(calls[0]!.headers["x-api-key"]).toBe(API_KEY);
    const bad = await ai.testConnection(p.id);
    expect(bad).toMatchObject({ ok: false, detail: "auth (HTTP 401)" });
    expect(JSON.stringify(ai.providers())).not.toContain("bad key");
    expect(ai.providers()[0]!.lastTest).toMatchObject({ ok: false });
  });
});

describe("the gateway", () => {
  const ready = (fetchFn: Fetch, enabled = true) => {
    const s = setup(fetchFn, { enabled });
    const p = s.ai.createProvider("anthropic", "Primary", "admin");
    s.ai.setCredential(p.id, API_KEY);
    s.ai.updateProvider(p.id, { enabled: true, maxRetries: 1 });
    s.ai.setModel(p.id, "claude-test-1", { enabled: true, inputPriceMicro: 3_000_000, outputPriceMicro: 15_000_000, priceSource: "provider price page, 2026-10-24" });
    return { ...s, providerId: p.id };
  };
  const req = (providerId: string, extra = {}) => ({ feature: "console.test" as const, providerId, model: "claude-test-1", prompt: "Reply with pong.", maxOutputTokens: 16, ...extra });

  it("refuses everything while the kill switch is off, before any provider is called", async () => {
    const { calls, fetchFn } = fakeProvider([anthropicOk()]);
    const { ai, providerId, db } = ready(fetchFn, false);
    await expect(ai.complete(req(providerId))).rejects.toMatchObject({ code: "ai_disabled" });
    expect(calls).toHaveLength(0);
    expect(db.prepare("SELECT outcome, error_kind FROM ai_usage").all()).toEqual([{ outcome: "blocked", error_kind: "kill_switch" }]);
  });

  it("only calls models on the allowlist", async () => {
    const { calls, fetchFn } = fakeProvider([anthropicOk()]);
    const { ai, providerId } = ready(fetchFn);
    await expect(ai.complete(req(providerId, { model: "claude-unlisted" }))).rejects.toMatchObject({ code: "ai_not_configured" });
    expect(calls).toHaveLength(0);
  });

  it("settles quotas with the provider's own token counts and records counts, never the prompt or answer", async () => {
    const { fetchFn } = fakeProvider([anthropicOk(120, 30)]);
    const { ai, providerId, db, quotas } = ready(fetchFn);
    quotas.setPolicy({ scopeType: "global", scopeId: "", key: "ai.tokens", limit: 1_000_000, mode: "block", reason: "test" }, "admin");
    const out = await ai.complete(req(providerId));
    expect(out).toMatchObject({ text: "pong", inputTokens: 120, outputTokens: 30, costMicro: costMicro({ input_price_micro: 3_000_000, output_price_micro: 15_000_000 }, 120, 30) });
    expect(quotas.usage("ai.tokens", { type: "global", id: "" })).toMatchObject({ used: 150, reserved: 0 });
    const row = db.prepare("SELECT * FROM ai_usage").get() as Record<string, unknown>;
    expect(row).toMatchObject({ outcome: "ok", input_tokens: 120, output_tokens: 30, tokens_source: "provider" });
    expect(JSON.stringify(row)).not.toContain("pong");
    expect(JSON.stringify(row)).not.toContain("Reply with");
  });

  it("blocks at a spending budget before calling, and parallel requests can't overspend it", async () => {
    const { calls, fetchFn } = fakeProvider(() => anthropicOk(10, 10));
    const { ai, providerId, quotas } = ready(fetchFn);
    // Each request reserves ~ (4 + 16) tokens' worth: input 4 × 3 + output 16 × 15 = 252 micro-USD estimated.
    quotas.setPolicy({ scopeType: "global", scopeId: "", key: "ai.cost_micro", limit: 400, mode: "block", reason: "test" }, "admin");
    const results = await Promise.allSettled([ai.complete(req(providerId)), ai.complete(req(providerId))]);
    expect(results.map((r) => r.status).sort()).toEqual(["fulfilled", "rejected"]);
    expect(calls).toHaveLength(1);
  });

  it("retries a provider failure a bounded number of times, then uses an explicit fallback, and releases on failure", async () => {
    const { calls, fetchFn } = fakeProvider((c) => (c.url.includes("anthropic") ? { status: 503, body: {} } : { status: 200, body: { choices: [{ message: { content: "ok" } }], usage: { prompt_tokens: 5, completion_tokens: 2 } } }));
    const { ai, providerId, db, quotas } = ready(fetchFn);
    quotas.setPolicy({ scopeType: "global", scopeId: "", key: "ai.requests", limit: 100, mode: "block", reason: "test" }, "admin");
    await expect(ai.complete(req(providerId))).rejects.toMatchObject({ code: "provider_error" });
    expect(calls.filter((c) => c.url.includes("anthropic"))).toHaveLength(2); // the try and one retry
    expect(quotas.usage("ai.requests", { type: "global", id: "" })).toMatchObject({ used: 0, reserved: 0 });
    const fb = ai.createProvider("openai", "Fallback", "admin");
    ai.setCredential(fb.id, API_KEY);
    ai.updateProvider(fb.id, { enabled: true, maxRetries: 0 });
    ai.setModel(fb.id, "gpt-test", { enabled: true, inputPriceMicro: null, outputPriceMicro: null, priceSource: "" });
    ai.updateProvider(providerId, { fallbackId: fb.id });
    const out = await ai.complete(req(providerId));
    expect(out).toMatchObject({ text: "ok", providerId: fb.id, model: "gpt-test", costMicro: null });
    const rows = db.prepare("SELECT provider_id, outcome, error_kind FROM ai_usage ORDER BY id DESC LIMIT 2").all() as { provider_id: string; outcome: string; error_kind: string | null }[];
    expect(rows).toEqual([
      { provider_id: fb.id, outcome: "ok", error_kind: null },
      { provider_id: providerId, outcome: "error", error_kind: "server;fallback" },
    ]);
  });

  it("can't be enabled without a credential, and losing the credential disables it", () => {
    const { ai } = setup(fakeProvider([]).fetchFn);
    const p = ai.createProvider("google", "Gemini", "admin");
    expect(() => ai.updateProvider(p.id, { enabled: true })).toThrow(/credential/);
    ai.setCredential(p.id, API_KEY);
    ai.updateProvider(p.id, { enabled: true });
    ai.clearCredential(p.id);
    expect(ai.providers()[0]).toMatchObject({ enabled: false, credential: { configured: false } });
  });

  it("summarizes usage with estimates labelled as such", async () => {
    const { fetchFn } = fakeProvider([{ status: 200, body: { content: [{ type: "text", text: "pong" }] } }]);
    const { ai, providerId } = ready(fetchFn);
    await ai.complete(req(providerId));
    const u = ai.usage(0);
    expect(u.totals).toMatchObject({ requests: 1, ok: 1, estimatedTokenRows: 1 });
  });
});
