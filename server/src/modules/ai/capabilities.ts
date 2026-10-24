import { BASE, call, type Fetch, headers, MODEL_ID, ProviderError, type ProviderKind } from "./providers.js";

/**
 * What the gateway can ask a model to do (docs/AI_WORKFORCE.md §4), and the requests each provider kind's adapter can
 * send for it. ADAPTER_SUPPORT is a fact about this code, not about any model: whether a particular model can do it is
 * decided by the operator and proven by a verification request (AiService.verifyCapability). Images, audio and speech
 * come back inline (base64 or bytes); a URL from a provider is never fetched.
 */
export const CAPABILITIES = ["text", "vision", "transcription", "speech", "image_generation", "embeddings"] as const;
export type Capability = (typeof CAPABILITIES)[number];

export const CAPABILITY_LABELS: Record<Capability, string> = {
  text: "Text reasoning",
  vision: "Image understanding",
  transcription: "Speech to text",
  speech: "Text to speech",
  image_generation: "Image generation",
  embeddings: "Embeddings",
};

export const ADAPTER_SUPPORT: Record<ProviderKind, readonly Capability[]> = {
  anthropic: ["text", "vision"],
  openai: ["text", "vision", "transcription", "speech", "image_generation", "embeddings"],
  google: ["text", "vision", "transcription", "image_generation", "embeddings"],
};

export interface ChatImage {
  mime: "image/png" | "image/jpeg" | "image/webp" | "image/gif";
  base64: string;
}
export interface ChatMessage {
  role: "user" | "assistant";
  text: string;
  images?: ChatImage[];
}
export interface ChatRequest {
  system?: string;
  messages: ChatMessage[];
  maxOutputTokens: number;
  /** Ask for a JSON object (the caller still validates it). */
  json?: boolean;
}
export interface ChatResult {
  text: string;
  inputTokens: number | null;
  outputTokens: number | null;
}
export interface TranscriptSegment {
  start: number;
  end: number;
  text: string;
}
export interface TranscriptionResult {
  text: string;
  segments: TranscriptSegment[];
  /** "provider": timings measured by the provider's speech model; "model": a language model's own estimate. */
  timing: "provider" | "model" | "none";
}
export interface BinaryResult {
  bytes: Buffer;
  mime: string;
}
export interface EmbeddingResult {
  vectors: number[][];
  inputTokens: number | null;
}

type Opts = { timeoutMs: number; signal?: AbortSignal };

const IMAGE_MIMES = new Set(["image/png", "image/jpeg", "image/webp", "image/gif"]);

function checkModel(model: string): void {
  if (!MODEL_ID.test(model)) throw new ProviderError("bad_request", null);
}

export async function chat(fetchFn: Fetch, kind: ProviderKind, key: string, model: string, req: ChatRequest, o: Opts): Promise<ChatResult> {
  checkModel(model);
  const h = headers(kind, key);
  if (kind === "anthropic") {
    const messages = req.messages.map((m) => ({
      role: m.role,
      content: [
        ...(m.images ?? []).map((i) => ({ type: "image", source: { type: "base64", media_type: i.mime, data: i.base64 } })),
        { type: "text", text: m.text },
      ],
    }));
    const body = { model, max_tokens: req.maxOutputTokens, ...(req.system ? { system: req.system } : {}), messages };
    const r = (await call(fetchFn, `${BASE.anthropic}/v1/messages`, { method: "POST", headers: h, body: JSON.stringify(body) }, o.timeoutMs, o.signal)) as {
      content?: { type?: string; text?: string }[];
      usage?: { input_tokens?: number; output_tokens?: number };
    };
    return { text: (r.content ?? []).filter((c) => c.type === "text").map((c) => c.text ?? "").join(""), inputTokens: r.usage?.input_tokens ?? null, outputTokens: r.usage?.output_tokens ?? null };
  }
  if (kind === "openai") {
    const messages = [
      ...(req.system ? [{ role: "system", content: req.system }] : []),
      ...req.messages.map((m) =>
        m.images?.length
          ? { role: m.role, content: [{ type: "text", text: m.text }, ...m.images.map((i) => ({ type: "image_url", image_url: { url: `data:${i.mime};base64,${i.base64}` } }))] }
          : { role: m.role, content: m.text },
      ),
    ];
    const body = { model, max_completion_tokens: req.maxOutputTokens, messages, ...(req.json ? { response_format: { type: "json_object" } } : {}) };
    const r = (await call(fetchFn, `${BASE.openai}/v1/chat/completions`, { method: "POST", headers: h, body: JSON.stringify(body) }, o.timeoutMs, o.signal)) as {
      choices?: { message?: { content?: string } }[];
      usage?: { prompt_tokens?: number; completion_tokens?: number };
    };
    return { text: r.choices?.[0]?.message?.content ?? "", inputTokens: r.usage?.prompt_tokens ?? null, outputTokens: r.usage?.completion_tokens ?? null };
  }
  const contents = req.messages.map((m) => ({
    role: m.role === "assistant" ? "model" : "user",
    parts: [{ text: m.text }, ...(m.images ?? []).map((i) => ({ inline_data: { mime_type: i.mime, data: i.base64 } }))],
  }));
  const body = {
    contents,
    ...(req.system ? { systemInstruction: { parts: [{ text: req.system }] } } : {}),
    generationConfig: { maxOutputTokens: req.maxOutputTokens, ...(req.json ? { responseMimeType: "application/json" } : {}) },
  };
  const r = (await call(fetchFn, `${BASE.google}/v1beta/models/${encodeURIComponent(model)}:generateContent`, { method: "POST", headers: h, body: JSON.stringify(body) }, o.timeoutMs, o.signal)) as {
    candidates?: { content?: { parts?: { text?: string }[] } }[];
    usageMetadata?: { promptTokenCount?: number; candidatesTokenCount?: number };
  };
  return {
    text: (r.candidates?.[0]?.content?.parts ?? []).map((p) => p.text ?? "").join(""),
    inputTokens: r.usageMetadata?.promptTokenCount ?? null,
    outputTokens: r.usageMetadata?.candidatesTokenCount ?? null,
  };
}

/** Speech to text. OpenAI's speech models report segment timings ("whisper-*" with verbose_json); Gemini's are its own estimate. */
export async function transcribe(fetchFn: Fetch, kind: ProviderKind, key: string, model: string, audio: { bytes: Buffer; mime: string; name: string; language?: string }, o: Opts): Promise<TranscriptionResult> {
  checkModel(model);
  if (kind === "openai") {
    const form = new FormData();
    form.append("file", new Blob([new Uint8Array(audio.bytes)], { type: audio.mime }), audio.name);
    form.append("model", model);
    const verbose = model.startsWith("whisper");
    form.append("response_format", verbose ? "verbose_json" : "json");
    if (audio.language) form.append("language", audio.language);
    const h = headers(kind, key);
    delete h["content-type"]; // FormData sets its own boundary.
    const r = (await call(fetchFn, `${BASE.openai}/v1/audio/transcriptions`, { method: "POST", headers: h, body: form }, o.timeoutMs, o.signal)) as {
      text?: string;
      segments?: { start?: number; end?: number; text?: string }[];
    };
    const segments = (r.segments ?? []).filter((s) => typeof s.start === "number" && typeof s.end === "number").map((s) => ({ start: s.start!, end: s.end!, text: (s.text ?? "").trim() }));
    return { text: r.text ?? "", segments, timing: segments.length ? "provider" : "none" };
  }
  if (kind === "google") {
    const prompt =
      'Transcribe this audio. Answer with JSON only: {"text": string, "segments": [{"start": seconds, "end": seconds, "text": string}]}.' +
      (audio.language ? ` The language is ${audio.language}.` : "");
    const body = {
      contents: [{ role: "user", parts: [{ text: prompt }, { inline_data: { mime_type: audio.mime, data: audio.bytes.toString("base64") } }] }],
      generationConfig: { responseMimeType: "application/json", maxOutputTokens: 8192 },
    };
    const r = (await call(fetchFn, `${BASE.google}/v1beta/models/${encodeURIComponent(model)}:generateContent`, { method: "POST", headers: headers(kind, key), body: JSON.stringify(body) }, o.timeoutMs, o.signal)) as {
      candidates?: { content?: { parts?: { text?: string }[] } }[];
    };
    const raw = (r.candidates?.[0]?.content?.parts ?? []).map((p) => p.text ?? "").join("");
    try {
      const parsed = JSON.parse(raw) as { text?: unknown; segments?: { start?: unknown; end?: unknown; text?: unknown }[] };
      const segments = (Array.isArray(parsed.segments) ? parsed.segments : [])
        .filter((s) => typeof s.start === "number" && typeof s.end === "number" && s.end >= s.start)
        .map((s) => ({ start: s.start as number, end: s.end as number, text: String(s.text ?? "").trim() }));
      return { text: typeof parsed.text === "string" ? parsed.text : segments.map((s) => s.text).join(" "), segments, timing: segments.length ? "model" : "none" };
    } catch {
      return { text: raw, segments: [], timing: "none" };
    }
  }
  throw new ProviderError("bad_request", null);
}

export async function speak(fetchFn: Fetch, kind: ProviderKind, key: string, model: string, text: string, voice: string, o: Opts): Promise<BinaryResult> {
  checkModel(model);
  if (kind !== "openai") throw new ProviderError("bad_request", null);
  if (!/^[a-z]{2,20}$/.test(voice)) throw new ProviderError("bad_request", null);
  const bytes = await call(
    fetchFn,
    `${BASE.openai}/v1/audio/speech`,
    { method: "POST", headers: headers(kind, key), body: JSON.stringify({ model, input: text, voice, response_format: "mp3" }) },
    o.timeoutMs,
    o.signal,
    "binary",
  );
  return { bytes: bytes as Buffer, mime: "audio/mpeg" };
}

export async function generateImage(fetchFn: Fetch, kind: ProviderKind, key: string, model: string, prompt: string, size: string, o: Opts): Promise<BinaryResult> {
  checkModel(model);
  if (kind === "openai") {
    const body = { model, prompt, size, n: 1, ...(model.startsWith("dall-e") ? { response_format: "b64_json" } : {}) };
    const r = (await call(fetchFn, `${BASE.openai}/v1/images/generations`, { method: "POST", headers: headers(kind, key), body: JSON.stringify(body) }, o.timeoutMs, o.signal)) as {
      data?: { b64_json?: string }[];
    };
    const b64 = r.data?.[0]?.b64_json;
    if (!b64) throw new ProviderError("unexpected", null);
    return { bytes: Buffer.from(b64, "base64"), mime: "image/png" };
  }
  if (kind === "google") {
    const body = { contents: [{ role: "user", parts: [{ text: prompt }] }], generationConfig: { responseModalities: ["TEXT", "IMAGE"] } };
    const r = (await call(fetchFn, `${BASE.google}/v1beta/models/${encodeURIComponent(model)}:generateContent`, { method: "POST", headers: headers(kind, key), body: JSON.stringify(body) }, o.timeoutMs, o.signal)) as {
      candidates?: { content?: { parts?: { inlineData?: { mimeType?: string; data?: string }; inline_data?: { mime_type?: string; data?: string } }[] } }[];
    };
    for (const p of r.candidates?.[0]?.content?.parts ?? []) {
      const d = p.inlineData ?? (p.inline_data ? { mimeType: p.inline_data.mime_type, data: p.inline_data.data } : undefined);
      if (d?.data && d.mimeType && IMAGE_MIMES.has(d.mimeType)) return { bytes: Buffer.from(d.data, "base64"), mime: d.mimeType };
    }
    throw new ProviderError("unexpected", null);
  }
  throw new ProviderError("bad_request", null);
}

export async function embed(fetchFn: Fetch, kind: ProviderKind, key: string, model: string, texts: string[], o: Opts): Promise<EmbeddingResult> {
  checkModel(model);
  if (kind === "openai") {
    const r = (await call(fetchFn, `${BASE.openai}/v1/embeddings`, { method: "POST", headers: headers(kind, key), body: JSON.stringify({ model, input: texts }) }, o.timeoutMs, o.signal)) as {
      data?: { embedding?: number[] }[];
      usage?: { prompt_tokens?: number };
    };
    return { vectors: (r.data ?? []).map((d) => d.embedding ?? []), inputTokens: r.usage?.prompt_tokens ?? null };
  }
  if (kind === "google") {
    const body = { requests: texts.map((t) => ({ model: `models/${model}`, content: { parts: [{ text: t }] } })) };
    const r = (await call(fetchFn, `${BASE.google}/v1beta/models/${encodeURIComponent(model)}:batchEmbedContents`, { method: "POST", headers: headers(kind, key), body: JSON.stringify(body) }, o.timeoutMs, o.signal)) as {
      embeddings?: { values?: number[] }[];
    };
    return { vectors: (r.embeddings ?? []).map((e) => e.values ?? []), inputTokens: null };
  }
  throw new ProviderError("bad_request", null);
}
