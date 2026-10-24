import type { DB } from "../../infrastructure/database/db.js";
import { ApiError } from "../../shared/errors.js";
import { ADAPTER_SUPPORT, type Capability, CAPABILITIES } from "./capabilities.js";
import { MODEL_ID, type ProviderKind } from "./providers.js";

/**
 * Capabilities per model and `auto` routing (docs/AI_WORKFORCE.md §4). A model is a candidate for a capability only
 * when its provider is enabled with a credential and a closed circuit, the model is approved and routable, the adapter
 * can send that request, the operator turned the capability on, and its last verification passed. Nothing is assumed
 * from a model's name.
 */
export const ROUTING_CATEGORIES = {
  reasoning: { capability: "text", label: "Reasoning and long documents" },
  fast: { capability: "text", label: "Quick replies and classification" },
  vision: { capability: "vision", label: "Image understanding" },
  transcription: { capability: "transcription", label: "Speech to text" },
  speech: { capability: "speech", label: "Text to speech" },
  image_generation: { capability: "image_generation", label: "Image generation" },
  embeddings: { capability: "embeddings", label: "Embeddings" },
} as const satisfies Record<string, { capability: Capability; label: string }>;
export type RoutingCategory = keyof typeof ROUTING_CATEGORIES;

export const isRoutingCategory = (c: string): c is RoutingCategory => Object.hasOwn(ROUTING_CATEGORIES, c);

/** A circuit opens after this many failures in a row within the window, and stays open until the window passes. */
const CIRCUIT_FAILURES = 3;
const CIRCUIT_WINDOW_MS = 5 * 60_000;
const LATENCY_WINDOW_MS = 7 * 86_400_000;

export interface Candidate {
  providerId: string;
  kind: ProviderKind;
  providerName: string;
  model: string;
  inputPriceMicro: number | null;
  outputPriceMicro: number | null;
  requestPriceMicro: number | null;
  minutePriceMicro: number | null;
  qualityRank: number | null;
  /** Whether `auto` may pick it beyond the category's default and fallbacks. */
  autoRoutable: boolean;
  p50Ms: number | null;
}

export interface RoutePolicy {
  category: RoutingCategory;
  providerId: string | null;
  model: string | null;
  fallbacks: { providerId: string; model: string }[];
  prefer: "quality" | "cost" | "latency";
  maxPriceMicro: number | null;
  updatedAt: number | null;
}

export interface CapabilityState {
  capability: Capability;
  supportedByAdapter: boolean;
  enabled: boolean;
  verifiedAt: number | null;
  verifyOk: boolean | null;
  verifyDetail: string | null;
}

/** Why a model can't serve a capability now; the console words each one with EXCLUSION_REASONS. */
export const EXCLUSION_REASONS = {
  adapter: "the adapter can't send this request",
  provider_off: "provider disabled or without a credential",
  model_off: "model not allowed",
  capability_off: "capability not turned on",
  verify_failed: "last verification failed",
  unverified: "not verified yet",
  entitlement: "not in this community's entitlement",
  circuit: "provider failing (circuit open)",
  unpriced: "no price entered; this category has a cost ceiling",
  over_ceiling: "above this category's cost ceiling",
} as const;
export type Exclusion = keyof typeof EXCLUSION_REASONS;
export interface Excluded {
  providerId: string;
  model: string;
  why: string;
  code: Exclusion;
}

/**
 * A route's state for the console: ready (a verified model would serve it now), not_configured (no approved model has
 * the capability on), untested (on but never verified), failed (its verification failed), unavailable (verified, but
 * its provider is failing right now) or disabled (switched off, or kept out by policy such as a cost ceiling).
 */
export const ROUTE_STATUSES = ["ready", "not_configured", "untested", "failed", "unavailable", "disabled"] as const;
export type RouteStatus = (typeof ROUTE_STATUSES)[number];

const POLICY_EXCLUSIONS: ReadonlySet<Exclusion> = new Set(["unpriced", "over_ceiling"]);

export type ModelRef = { providerId: string; model: string };
export const refKey = (r: ModelRef) => `${r.providerId}/${r.model}`;

interface CandidateRow {
  provider_id: string;
  kind: ProviderKind;
  name: string;
  model: string;
  input_price_micro: number | null;
  output_price_micro: number | null;
  request_price_micro: number | null;
  minute_price_micro: number | null;
  quality_rank: number | null;
}

export class AiRouting {
  constructor(
    private readonly db: DB,
    private readonly clock: () => number,
  ) {}

  /** Each capability of one model: adapter support, the operator's switch, and the last verification. */
  capabilities(providerId: string, kind: ProviderKind, model: string): CapabilityState[] {
    const rows = this.db.prepare("SELECT * FROM ai_model_capabilities WHERE provider_id = ? AND model = ?").all(providerId, model) as {
      capability: Capability;
      verified_at: number | null;
      verify_ok: number | null;
      verify_detail: string | null;
    }[];
    const by = new Map(rows.map((r) => [r.capability, r]));
    return CAPABILITIES.map((c) => {
      const r = by.get(c);
      return {
        capability: c,
        supportedByAdapter: ADAPTER_SUPPORT[kind].includes(c),
        enabled: !!r,
        verifiedAt: r?.verified_at ?? null,
        verifyOk: r?.verify_ok === null || r?.verify_ok === undefined ? null : !!r.verify_ok,
        verifyDetail: r?.verify_detail ?? null,
      };
    });
  }

  /** Turns capabilities on or off for a model. Turning one on (again) clears its verification: it must pass anew. */
  setCapabilities(providerId: string, kind: ProviderKind, model: string, enabled: Capability[]): void {
    if (!MODEL_ID.test(model)) throw new ApiError(400, "invalid_policy", "That isn't a model id.");
    const unsupported = enabled.filter((c) => !ADAPTER_SUPPORT[kind].includes(c));
    if (unsupported.length) throw new ApiError(400, "invalid_policy", `This provider's adapter can't send ${unsupported.join(", ")} requests.`);
    if (!this.db.prepare("SELECT 1 FROM ai_models WHERE provider_id = ? AND model = ?").get(providerId, model)) throw new ApiError(404, "not_found", "Approve the model first.");
    const current = new Set((this.db.prepare("SELECT capability FROM ai_model_capabilities WHERE provider_id = ? AND model = ?").all(providerId, model) as { capability: Capability }[]).map((r) => r.capability));
    const want = new Set(enabled);
    this.db.transaction(() => {
      for (const c of current) if (!want.has(c)) this.db.prepare("DELETE FROM ai_model_capabilities WHERE provider_id = ? AND model = ? AND capability = ?").run(providerId, model, c);
      for (const c of want) if (!current.has(c)) this.db.prepare("INSERT INTO ai_model_capabilities (provider_id, model, capability) VALUES (?, ?, ?)").run(providerId, model, c);
    })();
  }

  /**
   * Clears every verification of a provider's models (its key changed or was removed): a new key may not reach the
   * same models, so each route it served stops until verified again.
   */
  invalidateProvider(providerId: string, why: string): void {
    this.db.prepare("UPDATE ai_model_capabilities SET verified_at = NULL, verify_ok = NULL, verify_detail = ? WHERE provider_id = ?").run(why, providerId);
  }

  recordVerification(providerId: string, model: string, capability: Capability, ok: boolean, detail: string): void {
    this.db
      .prepare("UPDATE ai_model_capabilities SET verified_at = ?, verify_ok = ?, verify_detail = ? WHERE provider_id = ? AND model = ? AND capability = ?")
      .run(this.clock(), ok ? 1 : 0, detail, providerId, model, capability);
  }

  setRanking(providerId: string, model: string, patch: { qualityRank?: number | null; autoRoutable?: boolean; requestPriceMicro?: number | null; minutePriceMicro?: number | null }): void {
    const r = this.db.prepare("SELECT 1 FROM ai_models WHERE provider_id = ? AND model = ?").get(providerId, model);
    if (!r) throw new ApiError(404, "not_found", "No such model.");
    this.db
      .prepare(
        `UPDATE ai_models SET quality_rank = CASE WHEN ? THEN ? ELSE quality_rank END, auto_routable = COALESCE(?, auto_routable),
           request_price_micro = CASE WHEN ? THEN ? ELSE request_price_micro END, minute_price_micro = CASE WHEN ? THEN ? ELSE minute_price_micro END
         WHERE provider_id = ? AND model = ?`,
      )
      .run(
        patch.qualityRank !== undefined ? 1 : 0,
        patch.qualityRank ?? null,
        patch.autoRoutable === undefined ? null : patch.autoRoutable ? 1 : 0,
        patch.requestPriceMicro !== undefined ? 1 : 0,
        patch.requestPriceMicro ?? null,
        patch.minutePriceMicro !== undefined ? 1 : 0,
        patch.minutePriceMicro ?? null,
        providerId,
        model,
      );
  }

  /** A model's routing settings and unit prices. */
  ranking(providerId: string, model: string): { qualityRank: number | null; autoRoutable: boolean; requestPriceMicro: number | null; minutePriceMicro: number | null } {
    const r = this.db.prepare("SELECT quality_rank, auto_routable, request_price_micro, minute_price_micro FROM ai_models WHERE provider_id = ? AND model = ?").get(providerId, model) as
      | { quality_rank: number | null; auto_routable: number; request_price_micro: number | null; minute_price_micro: number | null }
      | undefined;
    return { qualityRank: r?.quality_rank ?? null, autoRoutable: r ? !!r.auto_routable : true, requestPriceMicro: r?.request_price_micro ?? null, minutePriceMicro: r?.minute_price_micro ?? null };
  }

  policies(): RoutePolicy[] {
    const rows = new Map(
      (this.db.prepare("SELECT * FROM ai_routing").all() as { category: string; provider_id: string | null; model: string | null; fallbacks: string; prefer: RoutePolicy["prefer"]; max_price_micro: number | null; updated_at: number }[]).map(
        (r) => [r.category, r],
      ),
    );
    return (Object.keys(ROUTING_CATEGORIES) as RoutingCategory[]).map((category) => {
      const r = rows.get(category);
      return {
        category,
        providerId: r?.provider_id ?? null,
        model: r?.model ?? null,
        fallbacks: r ? (JSON.parse(r.fallbacks) as ModelRef[]) : [],
        prefer: r?.prefer ?? "quality",
        maxPriceMicro: r?.max_price_micro ?? null,
        updatedAt: r?.updated_at ?? null,
      };
    });
  }

  setPolicy(p: Omit<RoutePolicy, "updatedAt">, actor: string): RoutePolicy {
    const refs = [...(p.providerId && p.model ? [{ providerId: p.providerId, model: p.model }] : []), ...p.fallbacks];
    for (const r of refs) {
      if (!this.db.prepare("SELECT 1 FROM ai_models WHERE provider_id = ? AND model = ?").get(r.providerId, r.model)) throw new ApiError(400, "invalid_policy", `${r.model} isn't an approved model of that provider.`);
    }
    if (new Set(refs.map(refKey)).size !== refs.length) throw new ApiError(400, "invalid_policy", "A model appears twice in this route.");
    this.db
      .prepare(
        `INSERT INTO ai_routing (category, provider_id, model, fallbacks, prefer, max_price_micro, updated_by, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT (category) DO UPDATE SET provider_id = excluded.provider_id, model = excluded.model, fallbacks = excluded.fallbacks,
           prefer = excluded.prefer, max_price_micro = excluded.max_price_micro, updated_by = excluded.updated_by, updated_at = excluded.updated_at`,
      )
      .run(p.category, p.providerId, p.model, JSON.stringify(p.fallbacks), p.prefer, p.maxPriceMicro, actor, this.clock());
    return this.policies().find((x) => x.category === p.category)!;
  }

  /** Whether a provider's recent requests all failed (CIRCUIT_FAILURES in a row within the window). */
  circuitOpen(providerId: string): boolean {
    const rows = this.db
      .prepare("SELECT outcome, error_kind FROM ai_usage WHERE provider_id = ? AND at >= ? AND outcome <> 'blocked' ORDER BY id DESC LIMIT ?")
      .all(providerId, this.clock() - CIRCUIT_WINDOW_MS, CIRCUIT_FAILURES) as { outcome: string; error_kind: string | null }[];
    return rows.length === CIRCUIT_FAILURES && rows.every((r) => (r.outcome === "error" || r.outcome === "timeout") && r.error_kind !== "cancelled" && r.error_kind !== "bad_request");
  }

  private p50(providerId: string, model: string): number | null {
    const rows = this.db
      .prepare("SELECT latency_ms l FROM ai_usage WHERE provider_id = ? AND model = ? AND outcome = 'ok' AND latency_ms IS NOT NULL AND at >= ? ORDER BY latency_ms")
      .all(providerId, model, this.clock() - LATENCY_WINDOW_MS) as { l: number }[];
    return rows.length ? rows[Math.floor((rows.length - 1) / 2)]!.l : null;
  }

  /** Every model that can serve [capability] right now, with why the others can't (for the console). */
  eligible(capability: Capability, allowed: ReadonlySet<string> | null, opts: { ignoreCircuit?: boolean } = {}): { ok: Candidate[]; excluded: Excluded[] } {
    const rows = this.db
      .prepare(
        `SELECT p.id provider_id, p.kind, p.name, p.enabled p_enabled, p.secret_ciphertext IS NOT NULL has_key, m.model, m.enabled m_enabled, m.auto_routable,
                m.input_price_micro, m.output_price_micro, m.request_price_micro, m.minute_price_micro, m.quality_rank,
                c.capability IS NOT NULL cap_on, c.verify_ok
           FROM ai_providers p JOIN ai_models m ON m.provider_id = p.id
           LEFT JOIN ai_model_capabilities c ON c.provider_id = m.provider_id AND c.model = m.model AND c.capability = ?
          ORDER BY p.created_at, m.model`,
      )
      .all(capability) as (CandidateRow & { p_enabled: number; has_key: number; m_enabled: number; auto_routable: number; cap_on: number; verify_ok: number | null })[];
    const ok: Candidate[] = [];
    const excluded: Excluded[] = [];
    const open = new Map<string, boolean>();
    const failing = (providerId: string) => !opts.ignoreCircuit && (open.get(providerId) ?? open.set(providerId, this.circuitOpen(providerId)).get(providerId)!);
    for (const r of rows) {
      const code: Exclusion | null = !ADAPTER_SUPPORT[r.kind].includes(capability)
        ? "adapter"
        : !r.p_enabled || !r.has_key
          ? "provider_off"
          : !r.m_enabled
            ? "model_off"
            : !r.cap_on
              ? "capability_off"
              : r.verify_ok !== 1
                ? r.verify_ok === 0
                  ? "verify_failed"
                  : "unverified"
                : allowed && !allowed.has(refKey({ providerId: r.provider_id, model: r.model }))
                  ? "entitlement"
                  : failing(r.provider_id)
                    ? "circuit"
                    : null;
      if (code) {
        excluded.push({ providerId: r.provider_id, model: r.model, why: EXCLUSION_REASONS[code], code });
        continue;
      }
      ok.push({
        providerId: r.provider_id,
        kind: r.kind,
        providerName: r.name,
        model: r.model,
        inputPriceMicro: r.input_price_micro,
        outputPriceMicro: r.output_price_micro,
        requestPriceMicro: r.request_price_micro,
        minutePriceMicro: r.minute_price_micro,
        qualityRank: r.quality_rank,
        autoRoutable: !!r.auto_routable,
        p50Ms: this.p50(r.provider_id, r.model),
      });
    }
    return { ok, excluded };
  }

  /**
   * The ordered models for one request in [category]: the category's default, its fallbacks, then the other
   * routable candidates by the category's preference. Each comes with the reason it's in the list.
   */
  route(
    category: RoutingCategory,
    allowed: ReadonlySet<string> | null,
    opts: { ignoreCircuit?: boolean } = {},
  ): { ordered: { candidate: Candidate; reason: string }[]; excluded: Excluded[] } {
    const capability = ROUTING_CATEGORIES[category].capability;
    const policy = this.policies().find((p) => p.category === category)!;
    const { ok, excluded } = this.eligible(capability, allowed, opts);
    const priceOf = (c: Candidate) => (capability === "text" || capability === "vision" ? c.outputPriceMicro : capability === "transcription" ? c.minutePriceMicro : c.requestPriceMicro);
    const within = ok.filter((c) => {
      if (policy.maxPriceMicro === null) return true;
      const price = priceOf(c);
      const fits = price !== null && price <= policy.maxPriceMicro;
      if (!fits) {
        const code = price === null ? "unpriced" : "over_ceiling";
        excluded.push({ providerId: c.providerId, model: c.model, why: EXCLUSION_REASONS[code], code });
      }
      return fits;
    });
    const byKey = new Map(within.map((c) => [refKey(c), c]));
    const ordered: { candidate: Candidate; reason: string }[] = [];
    const take = (ref: ModelRef, reason: string) => {
      const c = byKey.get(refKey(ref));
      if (c) {
        ordered.push({ candidate: c, reason });
        byKey.delete(refKey(ref));
      }
    };
    if (policy.providerId && policy.model) take({ providerId: policy.providerId, model: policy.model }, `auto:${category} default`);
    policy.fallbacks.forEach((f, i) => take(f, `auto:${category} fallback ${i + 1}`));
    const nullsLast = (a: number | null, b: number | null) => (a === null ? (b === null ? 0 : 1) : b === null ? -1 : a - b);
    const rest = [...byKey.values()]
      .filter((c) => c.autoRoutable)
      .sort((a, b) => (policy.prefer === "cost" ? nullsLast(priceOf(a), priceOf(b)) : policy.prefer === "latency" ? nullsLast(a.p50Ms, b.p50Ms) : nullsLast(a.qualityRank, b.qualityRank)) || refKey(a).localeCompare(refKey(b)));
    for (const c of rest) ordered.push({ candidate: c, reason: `auto:${category} by ${policy.prefer}` });
    return { ordered, excluded };
  }

  /** A route's state (see ROUTE_STATUSES) and one plain sentence saying why. */
  status(category: RoutingCategory, aiEnabled: boolean): { status: RouteStatus; detail: string } {
    if (!aiEnabled) return { status: "disabled", detail: "AI requests are switched off (the ai.enabled kill switch)." };
    const { ordered, excluded } = this.route(category, null);
    const first = ordered[0]?.candidate;
    if (first) return { status: "ready", detail: `${first.model} (${first.providerName}) serves it, verified.` };
    const models = (codes: Exclusion[]) => [...new Set(excluded.filter((e) => codes.includes(e.code)).map((e) => e.model))].slice(0, 3).join(", ");
    if (models(["circuit"])) return { status: "unavailable", detail: `${models(["circuit"])} is verified, but its provider's recent requests all failed: routing skips it for a few minutes.` };
    if (models([...POLICY_EXCLUSIONS])) return { status: "disabled", detail: `Verified models are kept out by this route's cost ceiling (${models([...POLICY_EXCLUSIONS])}).` };
    if (models(["verify_failed"])) return { status: "failed", detail: `Verification failed for ${models(["verify_failed"])}. Open it to see why, then verify again or pick another model.` };
    if (models(["unverified"])) return { status: "untested", detail: `${models(["unverified"])} has this capability on but isn't verified yet.` };
    const policy = this.policies().find((p) => p.category === category)!;
    const fallen = excluded.find((e) => e.providerId === policy.providerId && e.model === policy.model && (e.code === "provider_off" || e.code === "model_off"));
    if (fallen) return { status: "disabled", detail: `Its default, ${fallen.model}, is switched off (${fallen.why}).` };
    return { status: "not_configured", detail: "No approved model has this capability turned on. Use Set up to pick and verify one." };
  }

  /** The latest agent request this category's routing served (feature "workforce"), or null. */
  lastExecution(category: RoutingCategory): { at: number; providerId: string; model: string; outcome: string; errorKind: string | null } | null {
    const prefix = `auto:${category} `;
    const r = this.db
      .prepare("SELECT at, provider_id, model, outcome, error_kind FROM ai_usage WHERE feature = 'workforce' AND substr(route, 1, ?) = ? ORDER BY id DESC LIMIT 1")
      .get(prefix.length, prefix) as { at: number; provider_id: string; model: string; outcome: string; error_kind: string | null } | undefined;
    return r ? { at: r.at, providerId: r.provider_id, model: r.model, outcome: r.outcome, errorKind: r.error_kind } : null;
  }
}
