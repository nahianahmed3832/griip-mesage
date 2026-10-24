import type { DB } from "../../infrastructure/database/db.js";
import { ApiError } from "../../shared/errors.js";
import { type QuotaEngine, refusal, scopesFor } from "../quotas/index.js";
import {
  type BinaryResult,
  type Capability,
  chat,
  type ChatRequest,
  type ChatResult,
  embed,
  type EmbeddingResult,
  generateImage,
  speak,
  transcribe,
  type TranscriptionResult,
} from "./capabilities.js";
import { type Fetch, ProviderError, type ProviderKind } from "./providers.js";
import { type AiRouting, type Candidate, ROUTING_CATEGORIES, type RoutingCategory } from "./routing.js";
import { nextStepFor, type Proof, proofOf, VERIFY_REQUESTS } from "./verification.js";

/**
 * The capability gateway (docs/AI_WORKFORCE.md §4): what AI Workforce jobs call. Order, like AiService.complete:
 * kill switch → feature → route (only verified, enabled, entitled models; circuit breaker) → size limits → quota
 * reservations → the call (bounded retries, then the next model in the route) → settlement → one usage row per attempt
 * with the route taken. Never stores prompts, files or answers.
 */
export const GATEWAY_FEATURES = ["workforce", "console.verify"] as const;
export type GatewayFeature = (typeof GATEWAY_FEATURES)[number];

export const LIMITS = {
  /** Characters across the system prompt and messages (about 50,000 tokens). */
  chatChars: 200_000,
  chatOutputTokens: 8192,
  images: 8,
  imageBytes: 5 * 1024 * 1024,
  audioBytes: 25 * 1024 * 1024,
  speechChars: 4000,
  imagePromptChars: 4000,
  embedTexts: 64,
  embedChars: 8000,
  /** Models tried for one request: the first, then at most two more from the route. */
  candidates: 3,
} as const;

const CHARS_PER_TOKEN = 4;
const IMAGE_SIZES = ["1024x1024", "1536x1024", "1024x1536"] as const;

export interface CallContext {
  feature: GatewayFeature;
  userId: string | null;
  orgId: string | null;
  jobId?: string | null;
  installId?: string | null;
  agentId?: string | null;
  /** Models this community may use ("providerId/model"); null: all. */
  allowed?: ReadonlySet<string> | null;
  signal?: AbortSignal;
}

export type Invocation =
  | { capability: "text"; category: Extract<RoutingCategory, "reasoning" | "fast">; chat: ChatRequest }
  | { capability: "vision"; category: "vision"; chat: ChatRequest }
  | { capability: "transcription"; category: "transcription"; audio: { bytes: Buffer; mime: string; name: string; language?: string; seconds: number } }
  | { capability: "speech"; category: "speech"; text: string; voice?: string }
  | { capability: "image_generation"; category: "image_generation"; prompt: string; size?: (typeof IMAGE_SIZES)[number] }
  | { capability: "embeddings"; category: "embeddings"; texts: string[] };

export type InvocationResult =
  | { capability: "text" | "vision"; chat: ChatResult }
  | { capability: "transcription"; transcript: TranscriptionResult }
  | { capability: "speech" | "image_generation"; binary: BinaryResult }
  | { capability: "embeddings"; embeddings: EmbeddingResult };

export interface InvokeOutcome {
  result: InvocationResult;
  providerId: string;
  model: string;
  route: string;
  inputTokens: number | null;
  outputTokens: number | null;
  costMicro: number | null;
  latencyMs: number;
}

/** The parts of AiService the gateway needs: provider settings and their keys (module-internal). */
export interface ProviderAccess {
  provider(id: string): { id: string; kind: ProviderKind; timeoutMs: number; maxRetries: number } | null;
  openKey(id: string): string;
}

function validate(inv: Invocation): void {
  const bad = (msg: string) => new ApiError(400, "invalid_policy", msg);
  if (ROUTING_CATEGORIES[inv.category].capability !== inv.capability) throw bad("That category doesn't serve this capability.");
  if (inv.capability === "text" || inv.capability === "vision") {
    const chars = (inv.chat.system?.length ?? 0) + inv.chat.messages.reduce((n, m) => n + m.text.length, 0);
    if (inv.chat.messages.length === 0 || chars === 0) throw bad("A request needs a message.");
    if (!(inv.chat.maxOutputTokens >= 1 && inv.chat.maxOutputTokens <= LIMITS.chatOutputTokens)) throw bad(`A request asks for 1–${LIMITS.chatOutputTokens} output tokens.`);
    if (chars > LIMITS.chatChars) throw bad(`A request is at most ${LIMITS.chatChars} characters.`);
    const images = inv.chat.messages.flatMap((m) => m.images ?? []);
    if (inv.capability === "text" && images.length) throw bad("Images need the vision capability.");
    if (images.length > LIMITS.images) throw bad(`At most ${LIMITS.images} images per request.`);
    if (images.some((i) => (i.base64.length * 3) / 4 > LIMITS.imageBytes)) throw bad("An image is at most 5 MB.");
  } else if (inv.capability === "transcription") {
    if (inv.audio.bytes.length === 0 || inv.audio.bytes.length > LIMITS.audioBytes) throw bad("Audio for one transcription is 1 byte to 25 MB.");
  } else if (inv.capability === "speech") {
    if (!inv.text.trim() || inv.text.length > LIMITS.speechChars) throw bad(`Speech is 1–${LIMITS.speechChars} characters.`);
  } else if (inv.capability === "image_generation") {
    if (!inv.prompt.trim() || inv.prompt.length > LIMITS.imagePromptChars) throw bad(`An image prompt is 1–${LIMITS.imagePromptChars} characters.`);
  } else if (inv.texts.length === 0 || inv.texts.length > LIMITS.embedTexts || inv.texts.some((t) => !t || t.length > LIMITS.embedChars)) {
    throw bad(`Embeddings take 1–${LIMITS.embedTexts} texts of up to ${LIMITS.embedChars} characters.`);
  }
}

/** Estimated tokens and cost before the call (for reservations), from the candidate's prices. */
function estimate(inv: Invocation, c: Candidate): { tokens: number; costMicro: number | null } {
  if (inv.capability === "text" || inv.capability === "vision") {
    const chars = (inv.chat.system?.length ?? 0) + inv.chat.messages.reduce((n, m) => n + m.text.length, 0);
    const input = Math.ceil(chars / CHARS_PER_TOKEN) + 800 * inv.chat.messages.flatMap((m) => m.images ?? []).length;
    const cost = c.inputPriceMicro === null || c.outputPriceMicro === null ? null : Math.round((input * c.inputPriceMicro + inv.chat.maxOutputTokens * c.outputPriceMicro) / 1_000_000);
    return { tokens: input + inv.chat.maxOutputTokens, costMicro: cost };
  }
  if (inv.capability === "transcription") return { tokens: 0, costMicro: c.minutePriceMicro === null ? null : Math.ceil((inv.audio.seconds / 60) * c.minutePriceMicro) };
  if (inv.capability === "embeddings") {
    const t = Math.ceil(inv.texts.reduce((n, x) => n + x.length, 0) / CHARS_PER_TOKEN);
    return { tokens: t, costMicro: c.inputPriceMicro === null ? null : Math.round((t * c.inputPriceMicro) / 1_000_000) };
  }
  return { tokens: 0, costMicro: c.requestPriceMicro };
}

/** Actual cost after the call: provider token counts where there are any, the operator's unit prices otherwise. */
function settle(inv: Invocation, c: Candidate, r: InvocationResult): { input: number | null; output: number | null; source: "provider" | "estimate" | "none"; cost: number | null } {
  if (r.capability === "text" || r.capability === "vision") {
    const reported = r.chat.inputTokens !== null && r.chat.outputTokens !== null;
    const est = estimate(inv, c);
    const input = r.chat.inputTokens ?? est.tokens - (inv as { chat: ChatRequest }).chat.maxOutputTokens;
    const output = r.chat.outputTokens ?? Math.ceil(r.chat.text.length / CHARS_PER_TOKEN);
    const cost = c.inputPriceMicro === null || c.outputPriceMicro === null ? null : Math.round((input * c.inputPriceMicro + output * c.outputPriceMicro) / 1_000_000);
    return { input, output, source: reported ? "provider" : "estimate", cost };
  }
  if (r.capability === "embeddings") {
    const input = r.embeddings.inputTokens ?? estimate(inv, c).tokens;
    return { input, output: 0, source: r.embeddings.inputTokens === null ? "estimate" : "provider", cost: c.inputPriceMicro === null ? null : Math.round((input * c.inputPriceMicro) / 1_000_000) };
  }
  return { input: null, output: null, source: "none", cost: estimate(inv, c).costMicro };
}

export class AiGateway {
  constructor(
    private readonly db: DB,
    private readonly quotas: QuotaEngine,
    private readonly routing: AiRouting,
    private readonly access: ProviderAccess,
    private readonly opts: { fetch: () => Fetch; enabled: () => boolean; clock: () => number },
  ) {}

  private record(
    ctx: CallContext,
    u: {
      providerId: string;
      model: string;
      capability: Capability;
      route: string;
      outcome: "ok" | "error" | "blocked" | "timeout";
      status?: number | null;
      input?: number | null;
      output?: number | null;
      tokensSource: "provider" | "estimate" | "none";
      cost?: number | null;
      latencyMs?: number | null;
      errorKind?: string | null;
    },
  ): void {
    this.db
      .prepare(
        `INSERT INTO ai_usage (at, provider_id, model, feature, user_id, org_id, outcome, status, input_tokens, output_tokens, tokens_source, cost_micro, latency_ms, error_kind,
                               job_id, install_id, agent_id, capability, route)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(
        this.opts.clock(),
        u.providerId,
        u.model,
        ctx.feature,
        ctx.userId,
        ctx.orgId,
        u.outcome,
        u.status ?? null,
        u.input ?? null,
        u.output ?? null,
        u.tokensSource,
        u.cost ?? null,
        u.latencyMs ?? null,
        u.errorKind ?? null,
        ctx.jobId ?? null,
        ctx.installId ?? null,
        ctx.agentId ?? null,
        u.capability,
        u.route,
      );
  }

  /** One request to one model, without routing, quotas or usage (the caller does those). */
  async send(c: Pick<Candidate, "providerId" | "kind" | "model">, inv: Invocation, timeoutMs: number, signal?: AbortSignal): Promise<InvocationResult> {
    const key = this.access.openKey(c.providerId);
    const f = this.opts.fetch();
    const o = { timeoutMs, signal };
    switch (inv.capability) {
      case "text":
      case "vision":
        return { capability: inv.capability, chat: await chat(f, c.kind, key, c.model, inv.chat, o) };
      case "transcription":
        return { capability: "transcription", transcript: await transcribe(f, c.kind, key, c.model, inv.audio, o) };
      case "speech":
        return { capability: "speech", binary: await speak(f, c.kind, key, c.model, inv.text, inv.voice ?? "alloy", o) };
      case "image_generation":
        return { capability: "image_generation", binary: await generateImage(f, c.kind, key, c.model, inv.prompt, inv.size ?? "1024x1024", o) };
      case "embeddings":
        return { capability: "embeddings", embeddings: await embed(f, c.kind, key, c.model, inv.texts, o) };
    }
  }

  /** The gateway (see the file comment). Throws an ApiError the caller can return or record as it is. */
  async invoke(ctx: CallContext, inv: Invocation): Promise<InvokeOutcome> {
    if (!GATEWAY_FEATURES.includes(ctx.feature)) throw new ApiError(400, "invalid_policy", "Unknown AI feature.");
    if (!this.opts.enabled()) throw new ApiError(503, "ai_disabled", "AI is switched off on this server.");
    validate(inv);
    const { ordered, excluded } = this.routing.route(inv.category, ctx.allowed ?? null);
    if (ordered.length === 0) {
      throw new ApiError(503, "ai_not_configured", `No verified model offers ${ROUTING_CATEGORIES[inv.category].label.toLowerCase()} right now.`, {
        category: inv.category,
        excluded: excluded.slice(0, 20),
      });
    }

    const scopes = scopesFor(ctx.userId ?? "platform", ctx.orgId).filter((s) => ctx.userId || s.type !== "user");
    const first = ordered[0]!.candidate;
    const est = estimate(inv, first);
    const reservations: { key: "ai.requests" | "ai.tokens" | "ai.cost_micro"; id: string | null }[] = [];
    const releaseAll = () => {
      for (const x of reservations) if (x.id) this.quotas.release(x.id);
    };
    const reserve = (key: "ai.requests" | "ai.tokens" | "ai.cost_micro", amount: number) => {
      const d = this.quotas.check(key, scopes, amount, { reserve: true, detail: ctx.feature });
      if (!d.allowed) {
        releaseAll();
        this.record(ctx, { providerId: first.providerId, model: first.model, capability: inv.capability, route: ordered[0]!.reason, outcome: "blocked", tokensSource: "none", errorKind: `quota:${key}` });
        throw refusal(d);
      }
      reservations.push({ key, id: d.reservationId });
    };
    reserve("ai.requests", 1);
    if (est.tokens > 0) reserve("ai.tokens", est.tokens);
    if (est.costMicro !== null && est.costMicro > 0) reserve("ai.cost_micro", est.costMicro);

    let last: ProviderError | null = null;
    for (const { candidate: c, reason } of ordered.slice(0, LIMITS.candidates)) {
      const p = this.access.provider(c.providerId);
      if (!p) continue;
      for (let i = 0; i <= Math.min(p.maxRetries, 3); i++) {
        const t0 = this.opts.clock();
        try {
          const result = await this.send(c, inv, p.timeoutMs, ctx.signal);
          const s = settle(inv, c, result);
          for (const x of reservations) {
            if (!x.id) continue;
            this.quotas.commit(x.id, x.key === "ai.requests" ? 1 : x.key === "ai.tokens" ? (s.input ?? 0) + (s.output ?? 0) : (s.cost ?? 0));
          }
          const latencyMs = this.opts.clock() - t0;
          this.record(ctx, { providerId: c.providerId, model: c.model, capability: inv.capability, route: reason, outcome: "ok", status: 200, input: s.input, output: s.output, tokensSource: s.source, cost: s.cost, latencyMs });
          return { result, providerId: c.providerId, model: c.model, route: reason, inputTokens: s.input, outputTokens: s.output, costMicro: s.cost, latencyMs };
        } catch (e) {
          last = e instanceof ProviderError ? e : new ProviderError("unexpected", null);
          this.record(ctx, {
            providerId: c.providerId,
            model: c.model,
            capability: inv.capability,
            route: reason,
            outcome: last.kind === "timeout" ? "timeout" : "error",
            status: last.status,
            tokensSource: "none",
            latencyMs: this.opts.clock() - t0,
            errorKind: last.kind,
          });
          if (last.kind === "cancelled" || last.kind === "bad_request") break;
          if (!last.retryable) break;
        }
      }
      // A cancelled job or a request the provider called malformed won't do better elsewhere.
      if (last?.kind === "cancelled" || last?.kind === "bad_request") break;
    }
    releaseAll();
    const kind = last?.kind ?? "unexpected";
    if (kind === "cancelled") throw new ApiError(409, "job_cancelled", "The job was cancelled.");
    throw new ApiError(502, "provider_error", `The provider didn't answer (${kind}).`, { kind });
  }

  /**
   * A capability's verification: one real, minimal request straight to the model (no routing; the capability needn't
   * be verified yet), whose answer must be the right kind of output (verification.ts). Counted as a usage row with
   * feature "console.verify". Returns what can be shown, never content, and on failure what to do next.
   */
  async verify(providerId: string, model: string, capability: Capability): Promise<{ ok: boolean; detail: string; latencyMs: number; nextStep: string | null }> {
    const p = this.access.provider(providerId);
    if (!p) throw new ApiError(404, "not_found", "No such provider.");
    const inv = VERIFY_REQUESTS[capability];
    const ctx: CallContext = { feature: "console.verify", userId: null, orgId: null };
    const t0 = this.opts.clock();
    let proof: Proof;
    try {
      const r = await this.send({ providerId, kind: p.kind, model }, inv, p.timeoutMs);
      proof = proofOf(capability, r);
      this.record(ctx, { providerId, model, capability, route: "verify", outcome: proof.ok ? "ok" : "error", status: 200, tokensSource: "none", latencyMs: this.opts.clock() - t0, errorKind: proof.ok ? null : "wrong_output" });
    } catch (e) {
      const pe = e instanceof ProviderError ? e : new ProviderError("unexpected", null);
      proof = { ok: false, detail: `${pe.kind}${pe.status ? ` (HTTP ${pe.status})` : ""}`, nextStep: nextStepFor(pe) };
      this.record(ctx, { providerId, model, capability, route: "verify", outcome: pe.kind === "timeout" ? "timeout" : "error", status: pe.status, tokensSource: "none", latencyMs: this.opts.clock() - t0, errorKind: pe.kind });
    }
    this.routing.recordVerification(providerId, model, capability, proof.ok, proof.detail);
    return { ok: proof.ok, detail: proof.detail, latencyMs: this.opts.clock() - t0, nextStep: proof.ok ? null : proof.nextStep };
  }
}
