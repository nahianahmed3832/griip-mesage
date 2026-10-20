import { secretFrom } from "../integrations/config.js";

/**
 * AI providers (docs/AI.md, round 7 slice C8): Claude (Anthropic), ChatGPT (OpenAI) and Gemini (Google). The server is
 * the only place that talks to a model provider, so apps never hold a provider key. AI is off unless the operator
 * configures at least one provider (AI_PROVIDERS plus that provider's key), and even then each workspace's admins must
 * turn it on and may pick which configured provider their workspace uses. Requests carry only what the person chose to
 * send, and nothing is kept.
 */
export const AI_PROVIDER_IDS = ["anthropic", "openai", "gemini"] as const;
export type AiProviderId = (typeof AI_PROVIDER_IDS)[number];

export const AI_PROVIDER_NAMES: Record<AiProviderId, string> = {
  anthropic: "Claude (Anthropic)",
  openai: "ChatGPT (OpenAI)",
  gemini: "Gemini (Google)",
};

export interface AiRequest {
  system: string;
  user: string;
  maxTokens: number;
}

export interface AiResult {
  text: string;
  inputTokens: number;
  outputTokens: number;
}

export interface AiProvider {
  readonly id: AiProviderId;
  readonly model: string;
  complete(req: AiRequest, signal: AbortSignal): Promise<AiResult>;
}

export type AiFailureKind = "rate_limited" | "unavailable" | "rejected" | "timeout" | "bad_response";

/** A provider failure's kind (never the provider's message, which can echo input). */
export class AiProviderError extends Error {
  constructor(readonly kind: AiFailureKind, readonly retryable: boolean) {
    super(kind);
  }
}

export interface AiConfig {
  /** Configured providers, in the operator's order; the first is the default for workspaces that haven't picked one. */
  providers: readonly AiProvider[];
  /** Requests per workspace per calendar month (UTC), on top of any plan limit. */
  monthlyRequestsPerOrg: number;
  /** Requests per person per minute. */
  perUserPerMinute: number;
  maxInputChars: number;
  timeoutMs: number;
}

export type FetchLike = (url: string, init: RequestInit) => Promise<Response>;
const defaultFetch: FetchLike = (u, i) => fetch(u, i);

/** Sends one JSON request and maps transport and HTTP failures to an [AiProviderError]. */
async function postJson(fetchFn: FetchLike, url: string, headers: Record<string, string>, body: unknown, signal: AbortSignal): Promise<unknown> {
  let res: Response;
  try {
    res = await fetchFn(url, { method: "POST", signal, headers: { "content-type": "application/json", ...headers }, body: JSON.stringify(body) });
  } catch (e) {
    const name = (e as Error).name;
    throw new AiProviderError(name === "AbortError" || name === "TimeoutError" ? "timeout" : "unavailable", true);
  }
  if (res.status === 429) throw new AiProviderError("rate_limited", true);
  if (res.status >= 500) throw new AiProviderError("unavailable", true);
  if (!res.ok) throw new AiProviderError("rejected", false);
  return res.json().catch(() => {
    throw new AiProviderError("bad_response", false);
  });
}

function result(text: string | undefined, inputTokens: number | undefined, outputTokens: number | undefined): AiResult {
  const trimmed = text?.trim();
  if (!trimmed) throw new AiProviderError("bad_response", false);
  return { text: trimmed, inputTokens: inputTokens ?? 0, outputTokens: outputTokens ?? 0 };
}

/** Claude, through Anthropic's Messages API. */
export class AnthropicProvider implements AiProvider {
  readonly id = "anthropic" as const;

  constructor(private readonly apiKey: string, readonly model: string, private readonly fetchFn: FetchLike = defaultFetch) {}

  async complete(req: AiRequest, signal: AbortSignal): Promise<AiResult> {
    const body = (await postJson(
      this.fetchFn,
      "https://api.anthropic.com/v1/messages",
      { "x-api-key": this.apiKey, "anthropic-version": "2023-06-01" },
      { model: this.model, max_tokens: req.maxTokens, system: req.system, messages: [{ role: "user", content: req.user }] },
      signal,
    )) as { content?: { type: string; text?: string }[]; usage?: { input_tokens?: number; output_tokens?: number } } | null;
    const text = body?.content?.filter((c) => c.type === "text").map((c) => c.text ?? "").join("");
    return result(text, body?.usage?.input_tokens, body?.usage?.output_tokens);
  }
}

/** ChatGPT models, through OpenAI's Chat Completions API. */
export class OpenAiProvider implements AiProvider {
  readonly id = "openai" as const;

  constructor(private readonly apiKey: string, readonly model: string, private readonly fetchFn: FetchLike = defaultFetch) {}

  async complete(req: AiRequest, signal: AbortSignal): Promise<AiResult> {
    const body = (await postJson(
      this.fetchFn,
      "https://api.openai.com/v1/chat/completions",
      { authorization: `Bearer ${this.apiKey}` },
      {
        model: this.model,
        max_completion_tokens: req.maxTokens,
        messages: [
          { role: "system", content: req.system },
          { role: "user", content: req.user },
        ],
      },
      signal,
    )) as { choices?: { message?: { content?: string | null } }[]; usage?: { prompt_tokens?: number; completion_tokens?: number } } | null;
    return result(body?.choices?.[0]?.message?.content ?? undefined, body?.usage?.prompt_tokens, body?.usage?.completion_tokens);
  }
}

/** Gemini models, through Google's Gemini API (generateContent). */
export class GeminiProvider implements AiProvider {
  readonly id = "gemini" as const;

  constructor(private readonly apiKey: string, readonly model: string, private readonly fetchFn: FetchLike = defaultFetch) {}

  async complete(req: AiRequest, signal: AbortSignal): Promise<AiResult> {
    const body = (await postJson(
      this.fetchFn,
      `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(this.model)}:generateContent`,
      { "x-goog-api-key": this.apiKey },
      {
        systemInstruction: { parts: [{ text: req.system }] },
        contents: [{ role: "user", parts: [{ text: req.user }] }],
        generationConfig: { maxOutputTokens: req.maxTokens },
      },
      signal,
    )) as { candidates?: { content?: { parts?: { text?: string }[] } }[]; usageMetadata?: { promptTokenCount?: number; candidatesTokenCount?: number } } | null;
    const text = body?.candidates?.[0]?.content?.parts?.map((p) => p.text ?? "").join("");
    return result(text, body?.usageMetadata?.promptTokenCount, body?.usageMetadata?.candidatesTokenCount);
  }
}

const int = (env: NodeJS.ProcessEnv, name: string, def: number, min: number, max: number): number => {
  const raw = env[name]?.trim();
  const n = raw ? Number(raw) : def;
  if (!Number.isInteger(n) || n < min || n > max) throw new Error(`${name} must be a whole number from ${min} to ${max}`);
  return n;
};

/** Environment names per provider. Only Claude has a default model; ChatGPT and Gemini need the operator to name one. */
export const AI_ENV: Record<AiProviderId, { key: string; model: string; defaultModel: string | null }> = {
  anthropic: { key: "AI_ANTHROPIC_API_KEY", model: "AI_ANTHROPIC_MODEL", defaultModel: "claude-haiku-5-5" },
  openai: { key: "AI_OPENAI_API_KEY", model: "AI_OPENAI_MODEL", defaultModel: null },
  gemini: { key: "AI_GEMINI_API_KEY", model: "AI_GEMINI_MODEL", defaultModel: null },
};

export function buildProvider(id: AiProviderId, apiKey: string, model: string, fetchFn: FetchLike = defaultFetch): AiProvider {
  if (id === "anthropic") return new AnthropicProvider(apiKey, model, fetchFn);
  if (id === "openai") return new OpenAiProvider(apiKey, model, fetchFn);
  return new GeminiProvider(apiKey, model, fetchFn);
}

function isProviderId(s: string): s is AiProviderId {
  return (AI_PROVIDER_IDS as readonly string[]).includes(s);
}

export function aiConfig(env: NodeJS.ProcessEnv): AiConfig {
  const ids = (env.AI_PROVIDERS ?? "").split(",").map((s) => s.trim().toLowerCase()).filter((s) => s && s !== "none");
  const providers = [...new Set(ids)].map((id) => {
    if (!isProviderId(id)) throw new Error(`AI_PROVIDERS: unknown provider "${id}" (known: ${AI_PROVIDER_IDS.join(", ")})`);
    const names = AI_ENV[id];
    const key = secretFrom(env, names.key);
    if (!key) throw new Error(`AI_PROVIDERS includes ${id}, which needs ${names.key} or ${names.key}_FILE`);
    const model = env[names.model]?.trim() || names.defaultModel;
    if (!model) throw new Error(`AI_PROVIDERS includes ${id}, which needs ${names.model} (the model to use)`);
    return buildProvider(id, key, model);
  });
  return {
    providers,
    monthlyRequestsPerOrg: int(env, "AI_MONTHLY_REQUESTS_PER_ORG", 500, 0, 1_000_000),
    perUserPerMinute: int(env, "AI_REQUESTS_PER_USER_PER_MINUTE", 6, 1, 600),
    maxInputChars: int(env, "AI_MAX_INPUT_CHARS", 40_000, 1000, 400_000),
    timeoutMs: int(env, "AI_TIMEOUT_MS", 30_000, 1000, 120_000),
  };
}
