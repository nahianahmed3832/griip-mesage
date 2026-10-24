/**
 * AI provider adapters (docs/AI_OPERATIONS.md). Each kind has a fixed base URL in code: nobody can point the server at
 * another host (no SSRF, no billing proxy). Model ids are checked against a strict pattern before they go into a URL.
 * Requests carry the key in a header, never in a URL; responses are reduced to text and token counts, and errors to a
 * status and kind (provider error bodies can echo input, so they're never kept).
 */
export const PROVIDER_KINDS = ["anthropic", "openai", "google"] as const;
export type ProviderKind = (typeof PROVIDER_KINDS)[number];

export const PROVIDER_LABELS: Record<ProviderKind, string> = { anthropic: "Anthropic", openai: "OpenAI", google: "Google AI (Gemini API)" };

export const BASE: Record<ProviderKind, string> = {
  anthropic: "https://api.anthropic.com",
  openai: "https://api.openai.com",
  google: "https://generativelanguage.googleapis.com",
};

export const MODEL_ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$/;

export type Fetch = (url: string, init: { method: string; headers: Record<string, string>; body?: string | FormData; signal: AbortSignal }) => Promise<{
  status: number;
  json(): Promise<unknown>;
  /** Binary answers (speech). Fakes in tests may leave it out. */
  arrayBuffer?(): Promise<ArrayBuffer>;
}>;

export interface Completion {
  text: string;
  inputTokens: number | null;
  outputTokens: number | null;
}

export class ProviderError extends Error {
  constructor(
    readonly kind: "timeout" | "network" | "auth" | "rate_limited" | "bad_request" | "server" | "unexpected" | "cancelled",
    readonly status: number | null,
  ) {
    super(`provider ${kind}${status ? ` (${status})` : ""}`);
  }

  /** Worth one more try (nothing was produced, so nothing was charged by the provider). */
  get retryable(): boolean {
    return this.kind === "network" || this.kind === "rate_limited" || this.kind === "server" || this.kind === "timeout";
  }
}

export function headers(kind: ProviderKind, key: string): Record<string, string> {
  if (kind === "anthropic") return { "x-api-key": key, "anthropic-version": "2023-06-01", "content-type": "application/json" };
  if (kind === "openai") return { authorization: `Bearer ${key}`, "content-type": "application/json" };
  return { "x-goog-api-key": key, "content-type": "application/json" };
}

function classify(status: number): ProviderError["kind"] {
  if (status === 401 || status === 403) return "auth";
  if (status === 429) return "rate_limited";
  if (status >= 500) return "server";
  return "bad_request";
}

/**
 * One request with a timeout; [signal] also aborts it (a cancelled job). Answers are JSON, or bytes with "binary".
 * Only the status and an error kind leave this function on failure: provider error bodies can echo the input.
 */
export async function call(
  fetchFn: Fetch,
  url: string,
  init: { method: string; headers: Record<string, string>; body?: string | FormData },
  timeoutMs: number,
  signal?: AbortSignal,
  as: "json" | "binary" = "json",
): Promise<unknown> {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  const onAbort = () => ctl.abort();
  if (signal?.aborted) ctl.abort();
  signal?.addEventListener("abort", onAbort, { once: true });
  try {
    const res = await fetchFn(url, { ...init, signal: ctl.signal });
    if (res.status < 200 || res.status >= 300) throw new ProviderError(classify(res.status), res.status);
    if (as === "binary") {
      if (!res.arrayBuffer) throw new ProviderError("unexpected", res.status);
      return Buffer.from(await res.arrayBuffer());
    }
    return await res.json();
  } catch (e) {
    if (e instanceof ProviderError) throw e;
    if (signal?.aborted) throw new ProviderError("cancelled", null);
    throw new ProviderError(ctl.signal.aborted ? "timeout" : "network", null);
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener("abort", onAbort);
  }
}

/**
 * Lists the models the key can use: an authenticated request that costs nothing (the connection test). Returns their
 * ids, so the console can tell a working key from a configured one.
 */
export async function listModels(fetchFn: Fetch, kind: ProviderKind, key: string, timeoutMs: number): Promise<string[]> {
  const url = kind === "google" ? `${BASE.google}/v1beta/models?pageSize=200` : `${BASE[kind]}/v1/models${kind === "anthropic" ? "?limit=100" : ""}`;
  const body = (await call(fetchFn, url, { method: "GET", headers: headers(kind, key) }, timeoutMs)) as { data?: { id?: unknown }[]; models?: { name?: unknown }[] };
  const ids = kind === "google" ? (body.models ?? []).map((m) => String(m.name ?? "").replace(/^models\//, "")) : (body.data ?? []).map((m) => String(m.id ?? ""));
  return ids.filter((id) => MODEL_ID.test(id)).slice(0, 300);
}

/** One completion: a single user prompt, a cap on output tokens. Token counts as the provider reports them. */
export async function complete(fetchFn: Fetch, kind: ProviderKind, key: string, model: string, prompt: string, maxOutputTokens: number, timeoutMs: number): Promise<Completion> {
  if (!MODEL_ID.test(model)) throw new ProviderError("bad_request", null);
  const h = headers(kind, key);
  if (kind === "anthropic") {
    const r = (await call(fetchFn, `${BASE.anthropic}/v1/messages`, { method: "POST", headers: h, body: JSON.stringify({ model, max_tokens: maxOutputTokens, messages: [{ role: "user", content: prompt }] }) }, timeoutMs)) as {
      content?: { type?: string; text?: string }[];
      usage?: { input_tokens?: number; output_tokens?: number };
    };
    return { text: (r.content ?? []).filter((c) => c.type === "text").map((c) => c.text ?? "").join(""), inputTokens: r.usage?.input_tokens ?? null, outputTokens: r.usage?.output_tokens ?? null };
  }
  if (kind === "openai") {
    const r = (await call(fetchFn, `${BASE.openai}/v1/chat/completions`, { method: "POST", headers: h, body: JSON.stringify({ model, max_completion_tokens: maxOutputTokens, messages: [{ role: "user", content: prompt }] }) }, timeoutMs)) as {
      choices?: { message?: { content?: string } }[];
      usage?: { prompt_tokens?: number; completion_tokens?: number };
    };
    return { text: r.choices?.[0]?.message?.content ?? "", inputTokens: r.usage?.prompt_tokens ?? null, outputTokens: r.usage?.completion_tokens ?? null };
  }
  const r = (await call(
    fetchFn,
    `${BASE.google}/v1beta/models/${encodeURIComponent(model)}:generateContent`,
    { method: "POST", headers: h, body: JSON.stringify({ contents: [{ role: "user", parts: [{ text: prompt }] }], generationConfig: { maxOutputTokens } }) },
    timeoutMs,
  )) as { candidates?: { content?: { parts?: { text?: string }[] } }[]; usageMetadata?: { promptTokenCount?: number; candidatesTokenCount?: number } };
  return {
    text: (r.candidates?.[0]?.content?.parts ?? []).map((p) => p.text ?? "").join(""),
    inputTokens: r.usageMetadata?.promptTokenCount ?? null,
    outputTokens: r.usageMetadata?.candidatesTokenCount ?? null,
  };
}
