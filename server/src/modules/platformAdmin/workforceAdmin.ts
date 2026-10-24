import type { AppContext } from "../../app/context.js";
import { networkKey } from "../../app/middleware/abuse.js";
import { ApiError } from "../../shared/errors.js";
import { now } from "../../shared/utilities/util.js";
import {
  type Capability,
  isRoutingCategory,
  type ProviderKind,
  ROUTING_CATEGORIES,
  type RoutingCategory,
} from "../ai/index.js";
import { orgNames } from "../organizations/index.js";
import {
  AGENTS,
  agentSummary,
  Entitlement,
  type JobMeta,
  type JobState,
  mediaEngine,
  TemplatePolicy,
  type TemplateStatus,
  TOOL_INFO,
  TOOL_KEYS,
  type ToolMode,
  type WorkforceConsoleView,
} from "../workforce/index.js";
import { jobDiagnosis, routeStates, setupRoute } from "./aiRoutesAdmin.js";
import type { PlatformAudit } from "./audit.js";
import type { AdminPrincipal } from "./auth.js";
import type { RequestMeta } from "./credentials.js";
import { flatDetail } from "./quotasAdmin.js";
import { writeSetting } from "./settings.js";

const DAY_MS = 86_400_000;

/**
 * The AI Control Center (docs/AI_WORKFORCE.md): what operators see and change of AI Workforce. Every change is
 * audited with its reason and takes effect in the runtime at once (the worker and the gateways read these records on
 * every job and every call). Job views are metadata only.
 */
export class AdminWorkforce {
  constructor(
    private readonly ctx: AppContext,
    private readonly audit: PlatformAudit,
  ) {}

  /** Metadata and operations only (ctx.workforce.console): the console never reaches content. */
  private get w(): WorkforceConsoleView {
    return this.ctx.workforce.console;
  }

  private record(
    p: AdminPrincipal,
    action: string,
    targetType: string,
    targetId: string,
    reason: string,
    meta: RequestMeta,
    detail: Record<string, unknown> = {},
    outcome: "success" | "failed" = "success",
  ): void {
    this.audit.record({
      action,
      outcome,
      actorAccountId: p.account.id,
      actorRole: p.grant.role,
      targetType,
      targetId,
      reason,
      requestId: meta.requestId,
      network: networkKey(meta.ip),
      detail: flatDetail(detail),
    });
  }

  private async person(id: string | null) {
    if (!id) return null;
    const a = await this.ctx.accounts.store.accountById(id);
    return { accountId: id, name: a?.name ?? null };
  }

  // ───────────── Overview ─────────────

  async overview(days: number) {
    const t = now();
    const from = t - days * DAY_MS;
    const ai = this.ctx.ai;
    const { queue, oldestQueuedAt, ...jobFigures } = this.w.jobStats(from);
    const usage = ai.workforceUsage(from);
    const names = orgNames(this.ctx.db, [
      ...new Set(jobFigures.byOrg.map((o) => o.orgId)),
    ]);
    const media = await mediaEngine();
    const controls = {
      aiEnabled: ai.enabled,
      ...this.w.controls(),
      secretsAvailable: ai.secretsAvailable,
    };
    const counts = this.w.installCounts();
    const worker = this.w.worker();
    return {
      generatedAt: t,
      days,
      controls,
      worker: {
        ...worker,
        ticking: worker.lastTickAt !== null && t - worker.lastTickAt < 60_000,
      },
      mediaEngine: {
        available: media.available,
        version: media.version,
        burnCaptions: media.burnCaptions,
        textOverlay: media.textOverlay,
        detail: media.detail,
      },
      queue,
      oldestQueuedAt,
      jobs: {
        ...jobFigures,
        byOrg: jobFigures.byOrg.map((o) => ({
          ...o,
          name: names.get(o.orgId) ?? null,
        })),
      },
      usage: {
        ...usage,
        mediaSeconds: Math.round(this.w.mediaSeconds(from)),
        verifications: ai.verificationUsage(from),
      },
      storage: this.w.storage(),
      installs: {
        active: counts.reduce((n, c) => n + c.active, 0),
        disabled: counts.reduce((n, c) => n + c.disabled, 0),
        communities: this.w.communitiesWithAgents(),
      },
      issues: [
        ...this.issues(controls, media.available),
        ...(jobFigures.failedBeforeModel > 0
          ? [
              {
                severity: "warning" as const,
                area: "jobs",
                message: `${jobFigures.failedBeforeModel === 1 ? "1 failed job" : `${jobFigures.failedBeforeModel} failed jobs`} never reached a model, so they cost nothing and sent no model requests. Open Jobs to see why and how to recover them.`,
              },
            ]
          : []),
      ],
    };
  }

  /** What's in the way of agents working, plainly. */
  private issues(
    c: {
      aiEnabled: boolean;
      workforceEnabled: boolean;
      paused: boolean;
      secretsAvailable: boolean;
    },
    mediaOk: boolean,
  ) {
    const out: {
      severity: "blocking" | "warning";
      area: string;
      message: string;
    }[] = [];
    if (!c.secretsAvailable)
      out.push({
        severity: "blocking",
        area: "credentials",
        message:
          "PLATFORM_SECRETS_KEY isn't set on the server, so no provider key can be stored.",
      });
    if (!c.aiEnabled)
      out.push({
        severity: "blocking",
        area: "settings",
        message: "AI requests are switched off (the ai.enabled kill switch).",
      });
    if (!c.workforceEnabled)
      out.push({
        severity: "blocking",
        area: "settings",
        message:
          "AI Workforce is switched off: communities can't install or ask agents.",
      });
    if (c.paused)
      out.push({
        severity: "warning",
        area: "operations",
        message: "New jobs are paused.",
      });
    const providers = this.ctx.ai.providers();
    if (!providers.some((p) => p.enabled && p.credential.configured))
      out.push({
        severity: "blocking",
        area: "providers",
        message: "No provider is enabled with a credential.",
      });
    for (const p of providers)
      if (p.enabled && p.lastTest && !p.lastTest.ok)
        out.push({
          severity: "warning",
          area: "providers",
          message: `${p.name}: the last connection test failed (${p.lastTest.detail}).`,
        });
    for (const p of providers)
      if (p.enabled && this.ctx.ai.routing.circuitOpen(p.id))
        out.push({
          severity: "warning",
          area: "providers",
          message: `${p.name}: its recent requests all failed, so routing skips it for now.`,
        });
    for (const cat of Object.keys(ROUTING_CATEGORIES) as RoutingCategory[]) {
      if (this.ctx.ai.routing.route(cat, null).ordered.length === 0) {
        out.push({
          severity:
            cat === "reasoning" || cat === "fast" ? "blocking" : "warning",
          area: "models",
          message: `No verified model for ${ROUTING_CATEGORIES[cat].label.toLowerCase()}.`,
        });
      }
    }
    if (!mediaOk)
      out.push({
        severity: "warning",
        area: "media",
        message:
          "The media engine (ffmpeg) isn't available: video and audio agents can't work.",
      });
    const states = this.w.templateStates();
    if (![...states.values()].some((s) => s.status === "published"))
      out.push({
        severity: "warning",
        area: "catalog",
        message: "No agent is published yet.",
      });
    return out;
  }

  setControls(
    p: AdminPrincipal,
    b: {
      workforceEnabled?: boolean;
      paused?: boolean;
      maxConcurrency?: number;
      approvalFloor?: "standard" | "strict";
      reason: string;
    },
    meta: RequestMeta,
  ) {
    const t = now();
    const changes: Record<string, unknown> = {};
    const write = (
      key:
        | "ai.workforce.enabled"
        | "ai.workforce.paused"
        | "ai.workforce.max_concurrency"
        | "ai.workforce.approval_floor",
      value: unknown,
    ) => {
      const r = writeSetting(this.ctx.db, key, value, p.account.id, t);
      changes[key] =
        `${JSON.stringify(r.before)} -> ${JSON.stringify(r.after)}`;
    };
    if (b.workforceEnabled !== undefined)
      write("ai.workforce.enabled", b.workforceEnabled);
    if (b.paused !== undefined) write("ai.workforce.paused", b.paused);
    if (b.maxConcurrency !== undefined)
      write("ai.workforce.max_concurrency", b.maxConcurrency);
    if (b.approvalFloor !== undefined)
      write("ai.workforce.approval_floor", b.approvalFloor);
    if (!Object.keys(changes).length)
      throw new ApiError(400, "invalid_policy", "Nothing to change.");
    this.record(
      p,
      "ai.workforce_controls",
      "ai_workforce",
      "platform",
      b.reason,
      meta,
      changes,
    );
    return this.overview(7);
  }

  // ───────────── Models and routing ─────────────

  models() {
    const routing = this.ctx.ai.routing;
    const rows = this.ctx.ai.providers().flatMap((p) =>
      p.models.map((m) => {
        const extra = routing.ranking(p.id, m.model);
        return {
          providerId: p.id,
          providerName: p.name,
          kind: p.kindLabel,
          providerEnabled: p.enabled,
          model: m.model,
          enabled: m.enabled,
          qualityRank: extra.qualityRank,
          autoRoutable: extra.autoRoutable,
          inputPriceMicro: m.inputPriceMicro,
          outputPriceMicro: m.outputPriceMicro,
          requestPriceMicro: extra.requestPriceMicro,
          minutePriceMicro: extra.minutePriceMicro,
          circuitOpen: routing.circuitOpen(p.id),
          capabilities: routing.capabilities(p.id, p.kind, m.model),
        };
      }),
    );
    const states = routeStates(this.ctx);
    return {
      models: rows,
      routing: routing.policies().map((r) => {
        const route = routing.route(r.category, null);
        return {
          ...r,
          ...states.get(r.category)!,
          label: ROUTING_CATEGORIES[r.category].label,
          capability: ROUTING_CATEGORIES[r.category].capability,
          route: route.ordered.map((o) => ({
            providerId: o.candidate.providerId,
            model: o.candidate.model,
            reason: o.reason,
          })),
          excluded: route.excluded,
        };
      }),
      recentDecisions: this.ctx.ai.recentRoutes(50),
    };
  }

  private provider(id: string): { id: string; kind: ProviderKind } {
    const p = this.ctx.ai.providers().find((x) => x.id === id);
    if (!p) throw new ApiError(404, "not_found", "No such provider.");
    return p;
  }

  setCapabilities(
    p: AdminPrincipal,
    providerId: string,
    model: string,
    b: {
      capabilities: Capability[];
      qualityRank?: number | null;
      autoRoutable?: boolean;
      requestPriceMicro?: number | null;
      minutePriceMicro?: number | null;
      reason: string;
    },
    meta: RequestMeta,
  ) {
    const prov = this.provider(providerId);
    const before = this.ctx.ai.routing
      .capabilities(providerId, prov.kind, model)
      .filter((c) => c.enabled)
      .map((c) => c.capability);
    this.ctx.ai.routing.setCapabilities(
      providerId,
      prov.kind,
      model,
      b.capabilities,
    );
    this.ctx.ai.routing.setRanking(providerId, model, b);
    this.record(
      p,
      "ai.model_capabilities",
      "ai_provider",
      providerId,
      b.reason,
      meta,
      {
        model,
        before: before.join(","),
        after: b.capabilities.join(","),
        qualityRank: b.qualityRank,
        autoRoutable: b.autoRoutable,
      },
    );
    return this.models();
  }

  async verify(
    p: AdminPrincipal,
    providerId: string,
    model: string,
    capability: Capability,
    reason: string,
    meta: RequestMeta,
  ) {
    const prov = this.provider(providerId);
    const state = this.ctx.ai.routing
      .capabilities(providerId, prov.kind, model)
      .find((c) => c.capability === capability);
    if (!state?.enabled)
      throw new ApiError(
        400,
        "invalid_policy",
        "Turn the capability on for this model first.",
      );
    const r = await this.ctx.ai.gateway.verify(providerId, model, capability);
    this.record(
      p,
      "ai.capability_verify",
      "ai_provider",
      providerId,
      reason,
      meta,
      { model, capability, ok: r.ok, detail: r.detail },
      r.ok ? "success" : "failed",
    );
    return r;
  }

  /**
   * One step for an approved model to serve agents: turns Text on (keeping what else is on) and verifies it with a real
   * request, the same as Capabilities then Verify. Both kinds of agent work (quick replies, reasoning) use Text.
   */
  async agentSetup(
    p: AdminPrincipal,
    providerId: string,
    model: string,
    reason: string,
    meta: RequestMeta,
  ) {
    const prov = this.provider(providerId);
    const approved = this.ctx.ai
      .providers()
      .find((x) => x.id === providerId)
      ?.models.find((m) => m.model === model);
    if (!approved?.enabled)
      throw new ApiError(
        400,
        "invalid_policy",
        "Approve this model for the provider first.",
      );
    const caps = this.ctx.ai.routing.capabilities(providerId, prov.kind, model);
    const on = caps.filter((c) => c.enabled).map((c) => c.capability);
    if (!on.includes("text")) {
      this.ctx.ai.routing.setCapabilities(providerId, prov.kind, model, [
        ...on,
        "text",
      ]);
      this.record(
        p,
        "ai.model_capabilities",
        "ai_provider",
        providerId,
        reason,
        meta,
        {
          model,
          before: on.join(","),
          after: [...on, "text"].join(","),
          via: "agent_setup",
        },
      );
    }
    const r = await this.ctx.ai.gateway.verify(providerId, model, "text");
    this.record(
      p,
      "ai.capability_verify",
      "ai_provider",
      providerId,
      reason,
      meta,
      {
        model,
        capability: "text",
        ok: r.ok,
        detail: r.detail,
        via: "agent_setup",
      },
      r.ok ? "success" : "failed",
    );
    return r;
  }

  /** Set up: approve, turn on, verify with a real request, and activate only if that passed (aiRoutesAdmin.ts). */
  async setupRoute(
    p: AdminPrincipal,
    category: string,
    b: { providerId: string; model: string; reason: string },
    meta: RequestMeta,
  ) {
    if (!isRoutingCategory(category))
      throw new ApiError(404, "not_found", "No such category.");
    const r = await setupRoute(
      this.ctx,
      (action, targetType, targetId, detail, outcome) =>
        this.record(
          p,
          action,
          targetType,
          targetId,
          b.reason,
          meta,
          detail,
          outcome,
        ),
      category,
      b.providerId,
      b.model,
      p.account.id,
    );
    return { ...r, models: this.models() };
  }

  setRouting(
    p: AdminPrincipal,
    category: string,
    b: {
      providerId: string | null;
      model: string | null;
      fallbacks: { providerId: string; model: string }[];
      prefer: "quality" | "cost" | "latency";
      maxPriceMicro: number | null;
      reason: string;
    },
    meta: RequestMeta,
  ) {
    if (!isRoutingCategory(category))
      throw new ApiError(404, "not_found", "No such category.");
    if ((b.providerId === null) !== (b.model === null))
      throw new ApiError(
        400,
        "invalid_policy",
        "Name both the provider and the model, or neither.",
      );
    const before = this.ctx.ai.routing
      .policies()
      .find((x) => x.category === category);
    this.ctx.ai.routing.setPolicy(
      {
        category,
        providerId: b.providerId,
        model: b.model,
        fallbacks: b.fallbacks,
        prefer: b.prefer,
        maxPriceMicro: b.maxPriceMicro,
      },
      p.account.id,
    );
    this.record(p, "ai.routing_set", "ai_routing", category, b.reason, meta, {
      before: before
        ? `${before.providerId ?? "-"}/${before.model ?? "-"} ${before.prefer}`
        : null,
      after: `${b.providerId ?? "-"}/${b.model ?? "-"} ${b.prefer}`,
      fallbacks: b.fallbacks.length,
      maxPriceMicro: b.maxPriceMicro,
    });
    return this.models();
  }

  // ───────────── Catalog ─────────────

  async catalog() {
    const states = this.w.templateStates();
    const counts = this.w.installCounts();
    const media = await mediaEngine();
    const out = [];
    for (const def of AGENTS) {
      const st = states.get(`${def.id}@${def.version}`) ?? {
        status: "draft" as TemplateStatus,
        availability: "all" as const,
        policy: {},
        updatedAt: null,
        updatedBy: null,
      };
      const c = counts.find(
        (x) => x.agentId === def.id && x.version === def.version,
      );
      const missing = def.skill.requiredCategories
        .filter(
          (cat) => this.ctx.ai.routing.route(cat, null).ordered.length === 0,
        )
        .map(
          (cat) =>
            `No verified model for ${ROUTING_CATEGORIES[cat].label.toLowerCase()}.`,
        );
      if (
        def.skill.requiredTools.some((t) => t.startsWith("media.")) &&
        !media.available
      )
        missing.push(`The media engine isn't available (${media.detail}).`);
      const s = agentSummary(def);
      out.push({
        id: def.id,
        version: def.version,
        name: def.name,
        description: def.description,
        category: def.category,
        skill: {
          id: s.skill.id,
          version: s.skill.version,
          tools: s.skill.tools,
          requiredTools: s.skill.requiredTools,
          modelCategories: s.skill.modelCategories,
          requiredModelCategories: s.skill.requiredModelCategories,
          maxRuntimeSec: def.skill.limits.maxRuntimeSec,
          maxModelCalls: def.skill.limits.maxModelCalls,
        },
        capabilities: s.capabilities,
        integrations: s.integrations,
        approvals: s.skill.approvals,
        errors: s.skill.errors,
        status: st.status,
        availability: st.availability,
        policy: st.policy,
        updatedAt: st.updatedAt,
        updatedBy: await this.person(st.updatedBy),
        installs: {
          active: c?.active ?? 0,
          disabled: c?.disabled ?? 0,
          communities: c?.orgs ?? 0,
        },
        jobs: this.w.agentJobs(def.id, def.version),
        ready: missing.length === 0,
        missing,
      });
    }
    return { agents: out };
  }

  async setTemplate(
    p: AdminPrincipal,
    agentId: string,
    version: number,
    b: {
      status?: TemplateStatus;
      availability?: "all" | "entitled";
      policy?: unknown;
      reason: string;
    },
    meta: RequestMeta,
  ) {
    if (!AGENTS.some((a) => a.id === agentId && a.version === version))
      throw new ApiError(404, "not_found", "No such agent version.");
    if (b.status === "draft")
      throw new ApiError(
        400,
        "invalid_policy",
        "A version can't go back to draft: disable or retire it.",
      );
    const policy =
      b.policy === undefined ? undefined : TemplatePolicy.parse(b.policy);
    const before = this.w.templateState(agentId, version);
    const after = this.w.setTemplateState(
      agentId,
      version,
      { status: b.status, availability: b.availability, policy },
      p.account.id,
    );
    this.record(
      p,
      "ai.catalog_set",
      "ai_agent",
      `${agentId}@${version}`,
      b.reason,
      meta,
      {
        status: `${before.status} -> ${after.status}`,
        availability: `${before.availability} -> ${after.availability}`,
        policy: JSON.stringify(after.policy),
      },
    );
    return this.catalog();
  }

  // ───────────── Tools ─────────────

  tools() {
    const modes = this.w.toolPolicies();
    const used = this.w.toolUse(now() - 30 * DAY_MS);
    return {
      tools: TOOL_KEYS.map((k) => ({
        key: k,
        label: TOOL_INFO[k].label,
        description: TOOL_INFO[k].description,
        risk: TOOL_INFO[k].risk,
        mode: modes[k],
        defaultMode: TOOL_INFO[k].defaultMode,
        hard: !!TOOL_INFO[k].hard,
        connected: TOOL_INFO[k].risk !== "external",
        integration: TOOL_INFO[k].integration ?? null,
        denied30d: used.get(k)?.denied ?? 0,
        used30d: used.get(k)?.used ?? 0,
      })),
    };
  }

  setTool(
    p: AdminPrincipal,
    tool: string,
    mode: ToolMode,
    reason: string,
    meta: RequestMeta,
  ) {
    const key = TOOL_KEYS.find((k) => k === tool);
    if (!key) throw new ApiError(404, "not_found", "No such tool.");
    const before = this.w.toolPolicies()[key];
    this.w.setToolPolicy(key, mode, p.account.id);
    this.record(p, "ai.tool_policy", "ai_tool", key, reason, meta, {
      before,
      after: mode,
    });
    return this.tools();
  }

  // ───────────── Entitlements ─────────────

  entitlements() {
    const rows = this.w.entitlementRows();
    const names = orgNames(
      this.ctx.db,
      rows.map((r) => r.orgId),
    );
    return {
      orgs: rows.map((r) => {
        const doc = Entitlement.parse(JSON.parse(r.doc));
        const restricted = (Object.entries(doc) as [string, unknown][])
          .filter(([k, v]) => k !== "enabled" && v !== null)
          .map(([k]) => k);
        return {
          orgId: r.orgId,
          name: names.get(r.orgId) ?? null,
          enabled: doc.enabled,
          restricted,
          updatedAt: r.updatedAt,
        };
      }),
    };
  }

  orgAi(orgId: string) {
    const name = orgNames(this.ctx.db, [orgId]).get(orgId) ?? null;
    if (name === null)
      throw new ApiError(404, "not_found", "No such community.");
    const ent = this.w.entitlement(orgId);
    const from = now() - 30 * DAY_MS;
    const jobs = this.w.orgJobs(orgId, from);
    const usage = this.ctx.ai.orgUsage(orgId, from);
    const { rev, explicit, ...doc } = ent;
    return {
      orgId,
      name,
      entitlement: doc,
      rev,
      explicit,
      installs: this.w.installs(orgId),
      usage: {
        jobs30d: jobs.jobs,
        failed30d: jobs.failed,
        active: jobs.active,
        requests30d: usage.requests,
        costMicro30d: usage.costMicro,
        fileBytes: this.w.fileBytes(orgId),
      },
    };
  }

  setOrgAi(
    p: AdminPrincipal,
    orgId: string,
    doc: unknown,
    rev: number,
    reason: string,
    meta: RequestMeta,
  ) {
    this.orgAi(orgId);
    const parsed = Entitlement.safeParse(doc);
    if (!parsed.success)
      throw new ApiError(
        400,
        "invalid_policy",
        `That entitlement isn't valid: ${parsed.error.issues[0]?.message ?? ""}`,
      );
    const unknownAgents = (parsed.data.agents ?? []).filter(
      (a) => !AGENTS.some((d) => d.id === a),
    );
    if (unknownAgents.length)
      throw new ApiError(
        400,
        "invalid_policy",
        `Unknown agents: ${unknownAgents.join(", ")}.`,
      );
    const before = this.w.entitlement(orgId);
    this.w.setEntitlement(orgId, parsed.data, rev, p.account.id);
    this.record(p, "ai.org_entitlement", "org", orgId, reason, meta, {
      before: JSON.stringify({
        ...before,
        rev: undefined,
        explicit: undefined,
      }),
      after: JSON.stringify(parsed.data),
    });
    return this.orgAi(orgId);
  }

  // ───────────── Jobs ─────────────

  private withUsage(jobs: JobMeta[]) {
    const usage = this.ctx.ai.jobUsage(jobs.map((j) => j.id));
    const names = orgNames(this.ctx.db, [...new Set(jobs.map((j) => j.orgId))]);
    return jobs.map((j) => ({
      ...j,
      orgName: names.get(j.orgId) ?? null,
      modelRequests: usage.get(j.id)?.requests ?? 0,
      costMicro: usage.get(j.id)?.costMicro ?? 0,
    }));
  }

  jobs(f: {
    state?: JobState;
    orgId?: string;
    agentId?: string;
    before?: number;
    limit: number;
  }) {
    const rows = this.w.jobs(f, f.limit);
    return {
      jobs: this.withUsage(rows),
      next: rows.length === f.limit ? rows[rows.length - 1]!.createdAt : null,
    };
  }

  job(jobId: string) {
    const j = this.w.job(jobId);
    if (!j) throw new ApiError(404, "not_found", "No such job.");
    const job = this.withUsage([j])[0]!;
    return {
      job,
      diagnosis: jobDiagnosis(this.ctx, job),
      events: this.w.events(j.id),
      steps: this.withUsage(this.w.steps(j.id)),
    };
  }

  cancelJob(
    p: AdminPrincipal,
    jobId: string,
    reason: string,
    meta: RequestMeta,
  ) {
    const j = this.w.job(jobId);
    if (!j) throw new ApiError(404, "not_found", "No such job.");
    this.w.cancel(jobId);
    this.record(p, "ai.job_cancel", "ai_job", jobId, reason, meta, {
      orgId: j.orgId,
      agentId: j.agentId,
      state: j.state,
    });
    return this.job(jobId);
  }
}
