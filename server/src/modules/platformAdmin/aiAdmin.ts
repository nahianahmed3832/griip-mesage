import type { AppContext } from "../../app/context.js";
import { networkKey } from "../../app/middleware/abuse.js";
import { badRequest } from "../../shared/errors.js";
import { now } from "../../shared/utilities/util.js";
import { AI_FEATURES, type ProviderKind } from "../ai/index.js";
import { quotaAdmin } from "../quotas/index.js";
import type { PlatformAudit } from "./audit.js";
import type { AdminPrincipal } from "./auth.js";
import type { RequestMeta } from "./credentials.js";
import { flatDetail } from "./quotasAdmin.js";

const DAY_MS = 86_400_000;
/** The console's generation test: one word back, a handful of tokens. */
const TEST_PROMPT = "Reply with the single word: pong";
const TEST_MAX_TOKENS = 16;

/**
 * AI operations in the console (docs/AI_OPERATIONS.md): providers, credentials (write-only), models and prices,
 * connection and generation tests, usage and budgets. Credentials are audited as "set" or "removed", never their value.
 */
export class AdminAi {
  constructor(
    private readonly ctx: AppContext,
    private readonly audit: PlatformAudit,
    private readonly policyViews: (rows: ReturnType<typeof quotaAdmin.policiesFor>) => Promise<unknown[]>,
  ) {}

  private record(p: AdminPrincipal, action: string, providerId: string, reason: string, meta: RequestMeta, detail: Record<string, unknown> = {}, outcome: "success" | "failed" = "success"): void {
    this.audit.record({ action, outcome, actorAccountId: p.account.id, actorRole: p.grant.role, targetType: "ai_provider", targetId: providerId, reason, requestId: meta.requestId, network: networkKey(meta.ip), detail: flatDetail(detail) });
  }

  async overview() {
    const ai = this.ctx.ai;
    return {
      enabled: ai.enabled,
      secretsAvailable: ai.secretsAvailable,
      providers: ai.providers(),
      features: Object.entries(AI_FEATURES).map(([id, f]) => ({ id, label: f.label, description: f.description })),
      usage: ai.usage(now() - 30 * DAY_MS) as unknown as Record<string, unknown>,
      budgets: (await this.policyViews(quotaAdmin.policiesFor(this.ctx.db, "ai."))) as never[],
      notes: [
        "No Meldiq feature uses AI yet: the gateway serves the console's tests and is ready for approved features. Conversation content can't reach it: messages are end-to-end encrypted.",
        "Costs are estimates from the prices entered per model, never the provider's invoice. Token counts are the provider's own unless marked as estimates.",
        ai.secretsAvailable ? "Credentials are encrypted with the server's PLATFORM_SECRETS_KEY and never shown again." : "PLATFORM_SECRETS_KEY isn't set on the server: credentials can't be stored until it is.",
      ],
    };
  }

  create(p: AdminPrincipal, kind: ProviderKind, name: string, reason: string, meta: RequestMeta) {
    const provider = this.ctx.ai.createProvider(kind, name, p.account.id);
    this.record(p, "ai.provider_create", provider.id, reason, meta, { kind, name });
    return { provider };
  }

  update(p: AdminPrincipal, id: string, patch: { name?: string; enabled?: boolean; timeoutMs?: number; maxRetries?: number; fallbackId?: string | null }, reason: string, meta: RequestMeta) {
    const before = this.ctx.ai.providers().find((x) => x.id === id);
    const provider = this.ctx.ai.updateProvider(id, patch);
    this.record(p, "ai.provider_update", id, reason, meta, {
      before: before ? { enabled: before.enabled, timeoutMs: before.timeoutMs, maxRetries: before.maxRetries, fallbackId: before.fallbackId, name: before.name } : null,
      after: patch,
    });
    return { provider };
  }

  setCredential(p: AdminPrincipal, id: string, apiKey: string, reason: string, meta: RequestMeta) {
    const had = this.ctx.ai.providers().find((x) => x.id === id)?.credential.configured ?? false;
    this.ctx.ai.setCredential(id, apiKey);
    this.record(p, had ? "ai.credential_rotate" : "ai.credential_set", id, reason, meta);
    return { provider: this.ctx.ai.providers().find((x) => x.id === id)! };
  }

  clearCredential(p: AdminPrincipal, id: string, reason: string, meta: RequestMeta) {
    this.ctx.ai.clearCredential(id);
    this.record(p, "ai.credential_remove", id, reason, meta);
    return { provider: this.ctx.ai.providers().find((x) => x.id === id)! };
  }

  async test(p: AdminPrincipal, id: string, meta: RequestMeta) {
    const r = await this.ctx.ai.testConnection(id);
    this.record(p, "ai.provider_test", id, "connection test", meta, { ok: r.ok, detail: r.detail, latencyMs: r.latencyMs }, r.ok ? "success" : "failed");
    return r;
  }

  setModel(p: AdminPrincipal, id: string, b: { model: string; enabled: boolean; inputPriceMicro: number | null; outputPriceMicro: number | null; priceSource: string; reason: string }, meta: RequestMeta) {
    const before = this.ctx.ai.providers().find((x) => x.id === id)?.models.find((m) => m.model === b.model) ?? null;
    this.ctx.ai.setModel(id, b.model, b);
    this.record(p, "ai.model_set", id, b.reason, meta, { model: b.model, before, after: { enabled: b.enabled, inputPriceMicro: b.inputPriceMicro, outputPriceMicro: b.outputPriceMicro, priceSource: b.priceSource } });
    return { provider: this.ctx.ai.providers().find((x) => x.id === id)! };
  }

  removeModel(p: AdminPrincipal, id: string, model: string, reason: string, meta: RequestMeta) {
    this.ctx.ai.removeModel(id, model);
    this.record(p, "ai.model_remove", id, reason, meta, { model });
    return { provider: this.ctx.ai.providers().find((x) => x.id === id)! };
  }

  /** A real generation through the gateway (kill switch, allowlist, budgets, usage): costs a few tokens. */
  async generateTest(p: AdminPrincipal, id: string, model: string, reason: string, meta: RequestMeta) {
    if (!this.ctx.ai.providers().some((x) => x.id === id)) throw badRequest("No such provider.");
    try {
      const out = await this.ctx.ai.complete({ feature: "console.test", providerId: id, model, prompt: TEST_PROMPT, maxOutputTokens: TEST_MAX_TOKENS });
      this.record(p, "ai.generate_test", id, reason, meta, { model, inputTokens: out.inputTokens, outputTokens: out.outputTokens, costMicro: out.costMicro });
      return { ...out, text: out.text.slice(0, 200) };
    } catch (e) {
      this.record(p, "ai.generate_test", id, reason, meta, { model, error: (e as { code?: string }).code ?? "error" }, "failed");
      throw e;
    }
  }
}
