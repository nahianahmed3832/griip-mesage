import type { AppContext } from "../context.js";
import { ApiError, badRequest, forbidden, now } from "../util.js";
import { structuralProblems, zWorkflowInput, type WorkflowInput } from "../workflows/model.js";
import { AI_PROVIDER_NAMES, AiProviderError, type AiProvider, type AiProviderId, type AiResult } from "./provider.js";
import {
  askPrompt,
  catchUpPrompt,
  documentSummaryPrompt,
  extractJson,
  questionTerms,
  workflowDraftPrompt,
  writePrompt,
  type AskSource,
  type CatchUpMessage,
  type Prompt,
  type WriteMode,
} from "./prompts.js";

/**
 * AI features (docs/AI.md, round 7 slice C8). Every request passes the same gates, in order:
 * a configured provider → the workspace's switch → a member who isn't a guest → the person's consent for this request →
 * their per-minute rate → the workspace's monthly allowance (server cap and plan limit) → the input size.
 * Then one provider call with a timeout and one retry for passing failures. Only counts are recorded (usage meter,
 * health metrics, audit action names): never the text sent or received.
 */
export type AiFeature = "catch_up" | "write" | "document_summary" | "ask" | "workflow_draft" | "inbox_draft" | "inbox_summary" | "inbox_triage" | "inbox_auto";

const ASK_MAX_SOURCES = 5;
const ASK_SOURCE_CHARS = 6000;
const RETRY_DELAY_MS = 800;

interface OrgAiRow {
  ai_enabled: number;
  ai_provider: string | null;
  ai_changed_by: string | null;
  ai_changed_at: number | null;
}

export interface AiAnswer {
  text: string;
  provider: { id: AiProviderId; name: string; model: string };
}

export class AiService {
  private rate = new Map<string, { minute: number; count: number }>();

  constructor(private readonly ctx: AppContext) {}

  private get config() {
    return this.ctx.config.ai;
  }

  private orgRow(orgId: string): OrgAiRow {
    return this.ctx.db.prepare("SELECT ai_enabled, ai_provider, ai_changed_by, ai_changed_at FROM orgs WHERE id = ?").get(orgId) as OrgAiRow;
  }

  /** The workspace's chosen provider when it's still configured, else the server's first. */
  private providerFor(row: OrgAiRow): AiProvider | null {
    const all = this.config.providers;
    return all.find((p) => p.id === row.ai_provider) ?? all[0] ?? null;
  }

  private usedThisMonth(orgId: string): number {
    const month = new Date(now()).toISOString().slice(0, 7);
    const r = this.ctx.db.prepare("SELECT SUM(value) n FROM usage_daily WHERE org_id = ? AND metric = 'ai.requests' AND day >= ?").get(orgId, `${month}-01`) as { n: number | null };
    return r.n ?? 0;
  }

  /** What the apps show: whether AI can be used here, why not, and which provider would receive the text. */
  status(orgId: string, userId: string) {
    const role = this.ctx.authz.requireOrgMember(orgId, userId);
    const row = this.orgRow(orgId);
    const provider = this.providerFor(row);
    const { limit: planLimit } = this.ctx.entitlements.limitFor("ai.requests_monthly", orgId);
    const limits = [this.config.monthlyRequestsPerOrg, planLimit].filter((n): n is number => n !== null);
    const reason = !provider ? "not_configured" : !row.ai_enabled ? "workspace_off" : role === "GUEST" ? "guest" : null;
    return {
      available: reason === null,
      reason,
      enabled: row.ai_enabled === 1,
      canManage: this.ctx.authz.can(orgId, userId, "workspace.edit"),
      provider: provider ? { id: provider.id, name: AI_PROVIDER_NAMES[provider.id], model: provider.model } : null,
      providers: this.config.providers.map((p) => ({ id: p.id, name: AI_PROVIDER_NAMES[p.id], model: p.model })),
      chosenProvider: row.ai_provider,
      monthly: { used: this.usedThisMonth(orgId), limit: limits.length ? Math.min(...limits) : null },
      changedAt: row.ai_changed_at,
    };
  }

  /** Workspace admins (workspace.edit): the switch, and which configured provider the workspace uses. */
  setPolicy(orgId: string, actorId: string, input: { enabled: boolean; provider?: AiProviderId | null }) {
    this.ctx.authz.requirePermission(orgId, actorId, "workspace.edit", "Only workspace admins can change AI settings");
    if (input.enabled && !this.config.providers.length) throw new ApiError(409, "ai_not_configured", "This server has no AI provider configured");
    if (input.provider && !this.config.providers.some((p) => p.id === input.provider)) throw badRequest(`${AI_PROVIDER_NAMES[input.provider]} isn't configured on this server`);
    const prior = this.orgRow(orgId);
    const provider = input.provider === undefined ? prior.ai_provider : input.provider;
    this.ctx.db
      .prepare("UPDATE orgs SET ai_enabled = ?, ai_provider = ?, ai_changed_by = ?, ai_changed_at = ? WHERE id = ?")
      .run(input.enabled ? 1 : 0, provider, actorId, now(), orgId);
    this.ctx.audit({ actorId, orgId, action: input.enabled ? "ai.enabled" : "ai.disabled", detail: provider ?? "default" });
    return this.status(orgId, actorId);
  }

  /** The gates, in order (see the class comment). Returns the provider to use. */
  private admit(orgId: string, userId: string, consent: boolean, inputChars: number): AiProvider {
    const role = this.ctx.authz.requireOrgMember(orgId, userId);
    const row = this.orgRow(orgId);
    const provider = this.providerFor(row);
    if (!provider) throw new ApiError(409, "ai_not_configured", "AI isn't set up on this server");
    if (!row.ai_enabled) throw new ApiError(409, "ai_off", "AI is turned off in this workspace");
    if (role === "GUEST") throw forbidden("Guests can't use AI in this workspace");
    if (!consent) throw badRequest("Confirm that this text may be sent to the AI provider", { consentRequired: true, provider: AI_PROVIDER_NAMES[provider.id] });
    this.takeRate(userId);
    const used = this.usedThisMonth(orgId);
    if (used >= this.config.monthlyRequestsPerOrg) {
      throw new ApiError(409, "limit_reached", `This server allows ${this.config.monthlyRequestsPerOrg} AI requests per workspace a month, and this workspace has reached it.`, { key: "ai.requests_monthly", limit: this.config.monthlyRequestsPerOrg });
    }
    this.ctx.entitlements.check("ai.requests_monthly", used, orgId);
    if (inputChars > this.config.maxInputChars) throw badRequest(`That's too much text for AI (at most ${this.config.maxInputChars} characters)`);
    return provider;
  }

  private takeRate(userId: string): void {
    const minute = Math.floor(now() / 60_000);
    const r = this.rate.get(userId);
    const count = r?.minute === minute ? r.count + 1 : 1;
    this.rate.set(userId, { minute, count });
    if (count > this.config.perUserPerMinute) throw new ApiError(429, "rate_limited", `At most ${this.config.perUserPerMinute} AI requests a minute`);
  }

  /** One provider call: timeout, one retry for passing failures, metering. Never logs the prompt or the reply. */
  /** A request a person makes (every gate, consent included). Used by other modules, e.g. the inbox. */
  async forPerson(orgId: string, userId: string, consent: boolean, feature: AiFeature, prompt: Prompt): Promise<AiAnswer> {
    const provider = this.admit(orgId, userId, consent, prompt.user.length);
    return this.call(orgId, userId, feature, provider, prompt);
  }

  /**
   * A request the workspace itself makes (inbox automatic replies an admin switched on): the provider, the workspace
   * switch, the monthly allowance and the size are checked; there is no person to ask, so no per-person consent.
   */
  async forWorkspace(orgId: string, feature: AiFeature, prompt: Prompt): Promise<AiAnswer> {
    const row = this.orgRow(orgId);
    const provider = this.providerFor(row);
    if (!provider) throw new ApiError(409, "ai_not_configured", "AI isn't set up on this server");
    if (!row.ai_enabled) throw new ApiError(409, "ai_off", "AI is turned off in this workspace");
    const used = this.usedThisMonth(orgId);
    if (used >= this.config.monthlyRequestsPerOrg) throw new ApiError(409, "limit_reached", "This workspace has used its AI requests for the month", { key: "ai.requests_monthly" });
    this.ctx.entitlements.check("ai.requests_monthly", used, orgId);
    if (prompt.user.length > this.config.maxInputChars) throw badRequest("Too much text for AI");
    return this.call(orgId, null, feature, provider, prompt);
  }

  private async call(orgId: string, userId: string | null, feature: AiFeature, provider: AiProvider, prompt: Prompt): Promise<AiAnswer> {
    let result: AiResult | null = null;
    let failure: AiProviderError | null = null;
    for (let attempt = 0; attempt < 2 && !result; attempt++) {
      if (attempt) await new Promise((r) => setTimeout(r, RETRY_DELAY_MS));
      try {
        result = await provider.complete(prompt, AbortSignal.timeout(this.config.timeoutMs));
        failure = null;
      } catch (e) {
        failure = e instanceof AiProviderError ? e : new AiProviderError("unavailable", false);
        if (!failure.retryable) break;
      }
    }
    const metricName = `ai.${provider.id}`;
    this.ctx.usage.add(orgId, "ai.requests");
    if (!result) {
      this.ctx.metrics.recordProvider(metricName, failure ?? new Error("unavailable"));
      this.ctx.log.warn({ provider: provider.id, feature, kind: failure?.kind }, "AI request failed");
      throw new ApiError(502, "ai_failed", failure?.kind === "rate_limited" ? "The AI provider is busy. Try again in a minute." : "The AI provider couldn't answer. Try again later.", { kind: failure?.kind });
    }
    this.ctx.metrics.recordProvider(metricName);
    this.ctx.usage.add(orgId, "ai.input_tokens", result.inputTokens);
    this.ctx.usage.add(orgId, "ai.output_tokens", result.outputTokens);
    this.ctx.audit({ actorId: userId, orgId, action: `ai.${feature}`, detail: provider.id });
    return { text: result.text, provider: { id: provider.id, name: AI_PROVIDER_NAMES[provider.id], model: provider.model } };
  }

  /** A catch-up of messages the person's app decrypted and chose to send (the server can't read encrypted ones). */
  async catchUp(orgId: string, userId: string, input: { consent: boolean; channelId: string; messages: readonly CatchUpMessage[] }): Promise<AiAnswer> {
    const conv = this.ctx.authz.conversation(input.channelId);
    if (!conv || conv.org_id !== orgId || !this.ctx.authz.canViewChannel(conv, userId)) throw forbidden("You can't read that channel");
    const prompt = catchUpPrompt(conv.name ?? "channel", input.messages);
    const provider = this.admit(orgId, userId, input.consent, prompt.user.length);
    return this.call(orgId, userId, "catch_up", provider, prompt);
  }

  async write(orgId: string, userId: string, input: { consent: boolean; mode: WriteMode; text: string }): Promise<AiAnswer> {
    const prompt = writePrompt(input.mode, input.text);
    const provider = this.admit(orgId, userId, input.consent, prompt.user.length);
    return this.call(orgId, userId, "write", provider, prompt);
  }

  /** A document the person may read (DocumentService.roleOf is the one access rule). */
  async documentSummary(documentId: string, userId: string, consent: boolean): Promise<AiAnswer> {
    const { d } = this.ctx.documents.require(documentId, userId, "view");
    const prompt = documentSummaryPrompt(d.title, d.content);
    const provider = this.admit(d.org_id, userId, consent, prompt.user.length);
    return this.call(d.org_id, userId, "document_summary", provider, prompt);
  }

  /** Finds the documents the person may read that match the question; no provider call when none do. */
  async ask(orgId: string, userId: string, input: { consent: boolean; question: string }) {
    const hits = new Map<string, { score: number; id: string }>();
    for (const term of questionTerms(input.question)) {
      for (const h of this.ctx.documents.search(userId, term, orgId)) {
        const prior = hits.get(h.item.id);
        hits.set(h.item.id, { id: h.item.id, score: (prior?.score ?? 0) + (h.rank === 0 ? 2 : 1) });
      }
    }
    const top = [...hits.values()].sort((a, b) => b.score - a.score).slice(0, ASK_MAX_SOURCES);
    const sources: AskSource[] = top.map(({ id }) => {
      const { d } = this.ctx.documents.require(id, userId, "view");
      return { id: d.id, title: d.title, content: d.content.slice(0, ASK_SOURCE_CHARS) };
    });
    const cited = sources.map((s, i) => ({ n: i + 1, id: s.id, title: s.title }));
    if (!sources.length) {
      this.ctx.authz.requireOrgMember(orgId, userId);
      return { text: null, sources: cited, found: false };
    }
    const prompt = askPrompt(input.question, sources);
    const provider = this.admit(orgId, userId, input.consent, prompt.user.length);
    const answer = await this.call(orgId, userId, "ask", provider, prompt);
    return { ...answer, sources: cited, found: true };
  }

  /** A workflow draft for a manager to review: validated like a saved one, returned with its problems, never saved. */
  async workflowDraft(orgId: string, userId: string, input: { consent: boolean; description: string; timeZone: string }) {
    this.ctx.authz.requirePermission(orgId, userId, "workflows.manage", "Only people who manage workflows can draft them");
    const channels = (this.ctx.db.prepare("SELECT id FROM conversations WHERE org_id = ? AND type = 'CHANNEL' AND archived_at IS NULL ORDER BY name LIMIT 200").all(orgId) as { id: string }[])
      .map((r) => this.ctx.authz.conversation(r.id))
      .filter((c): c is NonNullable<typeof c> => !!c && this.ctx.authz.canViewChannel(c, userId))
      .map((c) => ({ id: c.id, name: c.name ?? c.id }));
    const prompt = workflowDraftPrompt(input.description, channels, input.timeZone, new Date(now()).toISOString().slice(0, 10));
    const provider = this.admit(orgId, userId, input.consent, prompt.user.length);
    const answer = await this.call(orgId, userId, "workflow_draft", provider, prompt);
    const parsed = zWorkflowInput.safeParse(extractJson(answer.text));
    if (!parsed.success) return { draft: null, problems: ["The AI's draft wasn't a workflow Meldiq can use. Try describing it differently."], provider: answer.provider };
    const draft: WorkflowInput = { ...parsed.data, enabled: false };
    return { draft, problems: this.draftProblems(orgId, userId, draft), provider: answer.provider };
  }

  private draftProblems(orgId: string, userId: string, draft: WorkflowInput): string[] {
    try {
      this.ctx.workflows.validate(orgId, userId, draft);
      return [];
    } catch (e) {
      const details = (e as ApiError).details as { problems?: string[] } | undefined;
      return details?.problems ?? structuralProblems(draft);
    }
  }
}
