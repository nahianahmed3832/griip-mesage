import type { AppContext } from "../../app/context.js";
import { ApiError } from "../../shared/errors.js";
import { ADAPTER_SUPPORT, MODEL_ID, ROUTING_CATEGORIES, type RoutingCategory } from "../ai/index.js";
import type { JobMeta } from "../workforce/index.js";

/**
 * The AI Control Center's routes (docs/AI_WORKFORCE.md §4): each category's state as `auto` would see it, the one-step
 * set-up that only activates a model after a real verification passed, and what a failed job tells an operator. The
 * caller (AdminWorkforce) checks permissions and writes the audit records it is handed.
 */

/** One audit record of a set-up step: action, target, detail, outcome (the reason and actor are bound by the caller). */
export type SetupAudit = (action: string, targetType: string, targetId: string, detail: Record<string, unknown>, outcome?: "success" | "failed") => void;

/** Fallbacks a route keeps (the contract's limit). */
const MAX_FALLBACKS = 5;

/** The console's view of each route: its state, the default's last verification, and the last agent request it served. */
export function routeStates(ctx: AppContext) {
  const routing = ctx.ai.routing;
  const providers = ctx.ai.providers();
  return new Map(
    routing.policies().map((policy) => {
      const capability = ROUTING_CATEGORIES[policy.category].capability;
      const { status, detail } = routing.status(policy.category, ctx.ai.enabled);
      const kind = providers.find((p) => p.id === policy.providerId)?.kind;
      const cap = policy.providerId && policy.model && kind ? routing.capabilities(policy.providerId, kind, policy.model).find((c) => c.capability === capability) : undefined;
      return [
        policy.category,
        {
          status,
          statusDetail: detail,
          verification: cap?.enabled ? { at: cap.verifiedAt, ok: cap.verifyOk, detail: cap.verifyDetail } : null,
          lastExecution: routing.lastExecution(policy.category),
        },
      ] as const;
    }),
  );
}

/**
 * Sets a route up in one step: approves the model if it isn't yet (no prices: its cost shows as unpriced), turns the
 * category's capability on, verifies it with one real request, and only when that passed makes it the route's default
 * (the previous default becomes the first fallback). A failed verification leaves the route as it was.
 */
export async function setupRoute(ctx: AppContext, audit: SetupAudit, category: RoutingCategory, providerId: string, model: string, actorId: string) {
  const capability = ROUTING_CATEGORIES[category].capability;
  const label = ROUTING_CATEGORIES[category].label.toLowerCase();
  const prov = ctx.ai.providers().find((p) => p.id === providerId);
  if (!prov) throw new ApiError(404, "not_found", "No such provider.");
  if (!prov.enabled || !prov.credential.configured) throw new ApiError(409, "ai_not_configured", `${prov.name} needs a key and to be enabled first (Providers & keys).`);
  if (!ADAPTER_SUPPORT[prov.kind].includes(capability)) throw new ApiError(400, "invalid_policy", `Meldiq can't send ${label} requests to ${prov.kindLabel}. Pick another provider.`);
  if (!MODEL_ID.test(model)) throw new ApiError(400, "invalid_policy", "That isn't a model id.");

  const approved = prov.models.find((m) => m.model === model);
  if (approved && !approved.enabled) throw new ApiError(400, "invalid_policy", `${model} is switched off for ${prov.name}. Turn it on under Providers & keys first.`);
  if (!approved) {
    ctx.ai.setModel(providerId, model, { enabled: true, inputPriceMicro: null, outputPriceMicro: null, priceSource: "" });
    audit("ai.model_set", "ai_provider", providerId, { model, after: "enabled, unpriced", via: "route_setup" });
  }
  const on = ctx.ai.routing.capabilities(providerId, prov.kind, model).filter((c) => c.enabled).map((c) => c.capability);
  if (!on.includes(capability)) {
    ctx.ai.routing.setCapabilities(providerId, prov.kind, model, [...on, capability]);
    audit("ai.model_capabilities", "ai_provider", providerId, { model, before: on.join(","), after: [...on, capability].join(","), via: "route_setup" });
  }

  const r = await ctx.ai.gateway.verify(providerId, model, capability);
  audit("ai.capability_verify", "ai_provider", providerId, { model, capability, ok: r.ok, detail: r.detail, via: "route_setup" }, r.ok ? "success" : "failed");
  if (!r.ok) return { ...r, activated: false };

  const policy = ctx.ai.routing.policies().find((p) => p.category === category)!;
  const same = (x: { providerId: string | null; model: string | null }) => x.providerId === providerId && x.model === model;
  const previous = policy.providerId && policy.model && !same(policy) ? [{ providerId: policy.providerId, model: policy.model }] : [];
  const fallbacks = [...previous, ...policy.fallbacks.filter((f) => !same(f) && !previous.some((p) => p.providerId === f.providerId && p.model === f.model))].slice(0, MAX_FALLBACKS);
  ctx.ai.routing.setPolicy({ category, providerId, model, fallbacks, prefer: policy.prefer, maxPriceMicro: policy.maxPriceMicro }, actorId);
  audit("ai.routing_set", "ai_routing", category, {
    before: `${policy.providerId ?? "-"}/${policy.model ?? "-"}`,
    after: `${providerId}/${model}`,
    fallbacks: fallbacks.length,
    via: "route_setup",
  });
  return { ...r, activated: true };
}

const TRANSIENT = new Set(["provider_error", "timeout", "worker_lost", "rate_limited"]);

/**
 * What an operator needs about a failed job: where it stopped, why in plain words, and how it can be recovered. A
 * retry is the requester's (or a community admin's) to make from the request itself: it sends their brief to a model,
 * re-admits the job against today's routes and limits, and keeps this failure in the job's history.
 */
export function jobDiagnosis(ctx: AppContext, job: JobMeta & { modelRequests: number }) {
  if (job.state !== "failed" || !job.error) return null;
  const stage = job.modelRequests > 0 ? "after model requests" : "before any model request";
  const category = ctx.workforce.console.failedRoute(job.id);
  if (job.error.code === "ai_not_configured") {
    const label = category ? ROUTING_CATEGORIES[category].label.toLowerCase() : "the kind of work it needed";
    const ready = category ? ctx.ai.routing.route(category, null).ordered.length > 0 : false;
    return {
      stage,
      category,
      reason: `No verified model for ${label} when it ran, so it stopped before reaching a provider.`,
      retryReady: ready,
      recovery: ready
        ? "That route works now. The person who asked, or a community admin with Manage AI agents, can retry it from the request; the retry uses today's configuration."
        : `Set up the ${label} route under Models & routing first: a retry before that fails the same way.`,
    };
  }
  const transient = TRANSIENT.has(job.error.code);
  return {
    stage,
    category,
    reason: transient ? `The provider or the worker failed (${job.error.code}).` : `It failed with ${job.error.code}.`,
    retryReady: transient ? true : null,
    recovery: transient
      ? "Usually temporary: the person who asked can retry it from the request once the provider answers again."
      : "Check the job's events for the step that failed; the person who asked can retry it once the cause is fixed.",
  };
}
