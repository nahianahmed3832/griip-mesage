import { z } from "zod/v4";
import type { DB } from "../../infrastructure/database/db.js";
import { ApiError } from "../../shared/errors.js";
import { newId } from "../../shared/utilities/util.js";
import { TOOL_INFO } from "./toolInfo.js";
import { AGENT_CATEGORIES, type AgentDef, TOOL_KEYS, type ToolKey, type ToolMode } from "./types.js";

/**
 * AI Workforce's records (migration 049): publication state of the catalog, the platform's tool ceilings, each
 * community's entitlement, and each community's installed agents and their channels. Global definitions (code) and
 * a community's installation (rows) never share a row, so a new template version can't change an installation.
 */

export type TemplateStatus = "draft" | "published" | "disabled" | "retired";

export const TemplatePolicy = z.strictObject({
  /** A ceiling on one run of this agent (seconds); never above the skill's own limit. */
  maxRuntimeSec: z.number().int().min(10).max(3600).optional(),
  /** Jobs per community per day for this agent, before the community's own limit. */
  maxJobsPerDay: z.number().int().min(0).max(10_000).optional(),
  /** Every installation works in review mode. */
  forceReview: z.boolean().optional(),
});
export type TemplatePolicy = z.infer<typeof TemplatePolicy>;

export interface TemplateState {
  agentId: string;
  version: number;
  status: TemplateStatus;
  availability: "all" | "entitled";
  policy: TemplatePolicy;
  updatedAt: number | null;
  updatedBy: string | null;
}

export const Entitlement = z.strictObject({
  /** Off: this community can't install or ask agents. */
  enabled: z.boolean(),
  /** Agent ids it may install (null: every published agent available to all). */
  agents: z.array(z.string().regex(/^[a-z][a-z0-9_]{1,40}$/)).max(100).nullable(),
  categories: z.array(z.enum(AGENT_CATEGORIES)).max(AGENT_CATEGORIES.length).nullable(),
  /** "providerId/model" it may use (null: every routable model). */
  models: z.array(z.string().max(200)).max(100).nullable(),
  /** Tools it may grant (null: whatever the platform allows). Never wider than the platform. */
  tools: z.array(z.enum(TOOL_KEYS)).max(TOOL_KEYS.length).nullable(),
  /** Jobs of this community that may be active (queued, running or waiting) at once (null: no extra limit). */
  maxActiveJobs: z.number().int().min(0).max(1000).nullable(),
  /** Jobs it may run at the same moment (null: the platform's concurrency only). */
  maxConcurrentJobs: z.number().int().min(0).max(16).nullable(),
});
export type Entitlement = z.infer<typeof Entitlement>;
export const DEFAULT_ENTITLEMENT: Entitlement = { enabled: true, agents: null, categories: null, models: null, tools: null, maxActiveJobs: null, maxConcurrentJobs: null };

export const Invokers = z.strictObject({ everyone: z.boolean(), roles: z.array(z.string().regex(/^[A-Za-z0-9_-]{1,64}$/)).max(50) });
export type Invokers = z.infer<typeof Invokers>;

export interface InstallRow {
  id: string;
  org_id: string;
  agent_id: string;
  live_agent: string | null;
  version: number;
  state: "active" | "disabled" | "uninstalled";
  display_name: string;
  avatar_blob: string | null;
  avatar_sha256: string | null;
  avatar_color: string | null;
  description: string;
  role_title: string;
  language: string | null;
  style: string;
  instructions: string;
  tools: string;
  execution_mode: "review" | "auto";
  max_jobs_per_day: number | null;
  max_concurrent: number | null;
  invokers: string;
  rev: number;
  installed_by: string;
  created_at: number;
  updated_at: number;
}

export interface Install {
  id: string;
  orgId: string;
  agentId: string;
  version: number;
  state: InstallRow["state"];
  displayName: string;
  avatar: { sha256: string } | null;
  avatarColor: string | null;
  description: string;
  roleTitle: string;
  language: string | null;
  style: string;
  instructions: string;
  tools: ToolKey[];
  executionMode: "review" | "auto";
  maxJobsPerDay: number | null;
  maxConcurrent: number | null;
  invokers: Invokers;
  rev: number;
  installedBy: string;
  createdAt: number;
  updatedAt: number;
}

export const toInstall = (r: InstallRow): Install => ({
  id: r.id,
  orgId: r.org_id,
  agentId: r.agent_id,
  version: r.version,
  state: r.state,
  displayName: r.display_name,
  avatar: r.avatar_blob && r.avatar_sha256 ? { sha256: r.avatar_sha256 } : null,
  avatarColor: r.avatar_color,
  description: r.description,
  roleTitle: r.role_title,
  language: r.language,
  style: r.style,
  instructions: r.instructions,
  tools: (JSON.parse(r.tools) as string[]).filter((t): t is ToolKey => (TOOL_KEYS as readonly string[]).includes(t)),
  executionMode: r.execution_mode,
  maxJobsPerDay: r.max_jobs_per_day,
  maxConcurrent: r.max_concurrent,
  invokers: Invokers.parse(JSON.parse(r.invokers)),
  rev: r.rev,
  installedBy: r.installed_by,
  createdAt: r.created_at,
  updatedAt: r.updated_at,
});

export type Activation = "mention" | "command" | "explicit";

export interface ChannelAssignment {
  installId: string;
  conversationId: string;
  activation: Activation;
  addedBy: string;
  addedAt: number;
}

export class WorkforceStore {
  constructor(
    private readonly db: DB,
    private readonly clock: () => number,
  ) {}

  // ───────────── Catalog publication ─────────────

  templateStates(): Map<string, TemplateState> {
    const rows = this.db.prepare("SELECT * FROM ai_agent_templates").all() as {
      agent_id: string;
      version: number;
      status: TemplateStatus;
      availability: "all" | "entitled";
      policy: string;
      updated_at: number;
      updated_by: string | null;
    }[];
    return new Map(
      rows.map((r) => [
        `${r.agent_id}@${r.version}`,
        { agentId: r.agent_id, version: r.version, status: r.status, availability: r.availability, policy: TemplatePolicy.parse(JSON.parse(r.policy)), updatedAt: r.updated_at, updatedBy: r.updated_by },
      ]),
    );
  }

  /** A version without a row is a draft: definitions ship in code unpublished until an operator publishes them. */
  templateState(agentId: string, version: number): TemplateState {
    return this.templateStates().get(`${agentId}@${version}`) ?? { agentId, version, status: "draft", availability: "all", policy: {}, updatedAt: null, updatedBy: null };
  }

  setTemplateState(agentId: string, version: number, patch: { status?: TemplateStatus; availability?: "all" | "entitled"; policy?: TemplatePolicy }, actor: string): TemplateState {
    const cur = this.templateState(agentId, version);
    const next = { status: patch.status ?? cur.status, availability: patch.availability ?? cur.availability, policy: patch.policy ?? cur.policy };
    const t = this.clock();
    this.db
      .prepare(
        `INSERT INTO ai_agent_templates (agent_id, version, status, availability, policy, updated_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT (agent_id, version) DO UPDATE SET status = excluded.status, availability = excluded.availability, policy = excluded.policy,
           updated_by = excluded.updated_by, updated_at = excluded.updated_at`,
      )
      .run(agentId, version, next.status, next.availability, JSON.stringify(next.policy), actor, t, t);
    return this.templateState(agentId, version);
  }

  // ───────────── Platform tool ceilings ─────────────

  toolPolicies(): Record<ToolKey, ToolMode> {
    const rows = new Map((this.db.prepare("SELECT tool, mode FROM ai_tool_policies").all() as { tool: string; mode: ToolMode }[]).map((r) => [r.tool, r.mode]));
    return Object.fromEntries(TOOL_KEYS.map((k) => [k, TOOL_INFO[k].hard ? "disabled" : (rows.get(k) ?? TOOL_INFO[k].defaultMode)])) as Record<ToolKey, ToolMode>;
  }

  setToolPolicy(tool: ToolKey, mode: ToolMode, actor: string): void {
    if (TOOL_INFO[tool].hard && mode !== "disabled") throw new ApiError(400, "invalid_policy", `${TOOL_INFO[tool].label} is never allowed for agents.`);
    if (TOOL_INFO[tool].risk === "external" && mode === "allowed") throw new ApiError(400, "invalid_policy", "Actions in outside services always need a person's approval.");
    this.db
      .prepare("INSERT INTO ai_tool_policies (tool, mode, updated_by, updated_at) VALUES (?, ?, ?, ?) ON CONFLICT (tool) DO UPDATE SET mode = excluded.mode, updated_by = excluded.updated_by, updated_at = excluded.updated_at")
      .run(tool, mode, actor, this.clock());
  }

  // ───────────── Entitlements ─────────────

  entitlement(orgId: string): Entitlement & { rev: number; explicit: boolean } {
    const r = this.db.prepare("SELECT doc, rev FROM ai_org_entitlements WHERE org_id = ?").get(orgId) as { doc: string; rev: number } | undefined;
    return r ? { ...Entitlement.parse(JSON.parse(r.doc)), rev: r.rev, explicit: true } : { ...DEFAULT_ENTITLEMENT, rev: 0, explicit: false };
  }

  setEntitlement(orgId: string, doc: Entitlement, rev: number, actor: string): Entitlement & { rev: number; explicit: boolean } {
    const cur = this.entitlement(orgId);
    if (cur.rev !== rev) throw new ApiError(409, "conflict", "Someone changed this community's AI settings meanwhile. Reload and try again.");
    this.db
      .prepare(
        `INSERT INTO ai_org_entitlements (org_id, doc, rev, updated_by, updated_at) VALUES (?, ?, ?, ?, ?)
         ON CONFLICT (org_id) DO UPDATE SET doc = excluded.doc, rev = excluded.rev, updated_by = excluded.updated_by, updated_at = excluded.updated_at`,
      )
      .run(orgId, JSON.stringify(doc), rev + 1, actor, this.clock());
    return this.entitlement(orgId);
  }

  // ───────────── Installations ─────────────

  install(id: string): Install | null {
    const r = this.db.prepare("SELECT * FROM ai_agent_installs WHERE id = ?").get(id) as InstallRow | undefined;
    return r ? toInstall(r) : null;
  }

  installRow(id: string): InstallRow | null {
    return (this.db.prepare("SELECT * FROM ai_agent_installs WHERE id = ?").get(id) as InstallRow | undefined) ?? null;
  }

  installsOf(orgId: string, includeUninstalled = false): Install[] {
    const rows = this.db
      .prepare(`SELECT * FROM ai_agent_installs WHERE org_id = ? ${includeUninstalled ? "" : "AND state <> 'uninstalled'"} ORDER BY created_at`)
      .all(orgId) as InstallRow[];
    return rows.map(toInstall);
  }

  liveInstallOf(orgId: string, agentId: string): Install | null {
    const r = this.db.prepare("SELECT * FROM ai_agent_installs WHERE org_id = ? AND agent_id = ? AND state <> 'uninstalled'").get(orgId, agentId) as InstallRow | undefined;
    return r ? toInstall(r) : null;
  }

  createInstall(orgId: string, def: AgentDef, tools: ToolKey[], identity: { displayName: string; description: string; roleTitle: string; avatarColor: string | null }, actor: string): Install {
    if (this.liveInstallOf(orgId, def.id)) throw new ApiError(409, "conflict", "This agent is already installed in this community.");
    const t = this.clock();
    const id = newId("agi");
    this.db
      .prepare(
        `INSERT INTO ai_agent_installs (id, org_id, agent_id, live_agent, version, state, display_name, avatar_color, description, role_title, style, instructions, tools,
                                        execution_mode, max_jobs_per_day, invokers, rev, installed_by, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, 'active', ?, ?, ?, ?, 'balanced', '', ?, ?, ?, ?, 1, ?, ?, ?)`,
      )
      .run(id, orgId, def.id, def.id, def.version, identity.displayName, identity.avatarColor, identity.description, identity.roleTitle, JSON.stringify(tools), def.defaults.executionMode, def.defaults.maxJobsPerDay, JSON.stringify({ everyone: true, roles: [] }), actor, t, t);
    return this.install(id)!;
  }

  /** Optimistic: [rev] must be the revision the edit was based on. */
  updateInstall(
    id: string,
    rev: number,
    patch: Partial<{
      state: "active" | "disabled";
      version: number;
      displayName: string;
      description: string;
      roleTitle: string;
      avatarColor: string | null;
      language: string | null;
      style: string;
      instructions: string;
      tools: ToolKey[];
      executionMode: "review" | "auto";
      maxJobsPerDay: number | null;
      maxConcurrent: number | null;
      invokers: Invokers;
    }>,
  ): Install {
    const cur = this.installRow(id);
    if (!cur || cur.state === "uninstalled") throw new ApiError(404, "not_found", "No such agent in this community.");
    if (cur.rev !== rev) throw new ApiError(409, "conflict", "Someone changed this agent meanwhile. Reload and try again.");
    const v = <K extends keyof typeof patch>(k: K) => patch[k] !== undefined;
    this.db
      .prepare(
        `UPDATE ai_agent_installs SET
           state = COALESCE(?, state), version = COALESCE(?, version), display_name = COALESCE(?, display_name), description = COALESCE(?, description),
           role_title = COALESCE(?, role_title), avatar_color = CASE WHEN ? THEN ? ELSE avatar_color END, language = CASE WHEN ? THEN ? ELSE language END,
           style = COALESCE(?, style), instructions = COALESCE(?, instructions), tools = COALESCE(?, tools), execution_mode = COALESCE(?, execution_mode),
           max_jobs_per_day = CASE WHEN ? THEN ? ELSE max_jobs_per_day END, max_concurrent = CASE WHEN ? THEN ? ELSE max_concurrent END,
           invokers = COALESCE(?, invokers), rev = rev + 1, updated_at = ?
         WHERE id = ?`,
      )
      .run(
        patch.state ?? null,
        patch.version ?? null,
        patch.displayName ?? null,
        patch.description ?? null,
        patch.roleTitle ?? null,
        v("avatarColor") ? 1 : 0,
        patch.avatarColor ?? null,
        v("language") ? 1 : 0,
        patch.language ?? null,
        patch.style ?? null,
        patch.instructions ?? null,
        patch.tools ? JSON.stringify(patch.tools) : null,
        patch.executionMode ?? null,
        v("maxJobsPerDay") ? 1 : 0,
        patch.maxJobsPerDay ?? null,
        v("maxConcurrent") ? 1 : 0,
        patch.maxConcurrent ?? null,
        patch.invokers ? JSON.stringify(patch.invokers) : null,
        this.clock(),
        id,
      );
    return this.install(id)!;
  }

  setAvatar(id: string, blobKey: string | null, sha256: string | null): void {
    this.db.prepare("UPDATE ai_agent_installs SET avatar_blob = ?, avatar_sha256 = ?, rev = rev + 1, updated_at = ? WHERE id = ?").run(blobKey, sha256, this.clock(), id);
  }

  /** Uninstalling keeps the row (jobs and deliverables still name it) and removes its channels. */
  uninstall(id: string): void {
    this.db.transaction(() => {
      this.db.prepare("UPDATE ai_agent_installs SET state = 'uninstalled', live_agent = NULL, rev = rev + 1, updated_at = ? WHERE id = ?").run(this.clock(), id);
      this.db.prepare("DELETE FROM ai_agent_channels WHERE install_id = ?").run(id);
    })();
  }

  installsOnVersion(agentId: string, version: number): number {
    return (this.db.prepare("SELECT COUNT(*) n FROM ai_agent_installs WHERE agent_id = ? AND version = ? AND state <> 'uninstalled'").get(agentId, version) as { n: number }).n;
  }

  installCounts(): { agentId: string; version: number; active: number; disabled: number; orgs: number }[] {
    return this.db
      .prepare(
        `SELECT agent_id agentId, version, SUM(state = 'active') active, SUM(state = 'disabled') disabled, COUNT(DISTINCT org_id) orgs
           FROM ai_agent_installs WHERE state <> 'uninstalled' GROUP BY agent_id, version`,
      )
      .all() as { agentId: string; version: number; active: number; disabled: number; orgs: number }[];
  }

  communitiesWithAgents(): number {
    return (this.db.prepare("SELECT COUNT(DISTINCT org_id) n FROM ai_agent_installs WHERE state <> 'uninstalled'").get() as { n: number }).n;
  }

  // ───────────── Channels ─────────────

  channelsOf(installId: string): ChannelAssignment[] {
    return (
      this.db.prepare("SELECT * FROM ai_agent_channels WHERE install_id = ? ORDER BY added_at").all(installId) as {
        install_id: string;
        conversation_id: string;
        activation: Activation;
        added_by: string;
        added_at: number;
      }[]
    ).map((r) => ({ installId: r.install_id, conversationId: r.conversation_id, activation: r.activation, addedBy: r.added_by, addedAt: r.added_at }));
  }

  assignment(installId: string, conversationId: string): ChannelAssignment | null {
    return this.channelsOf(installId).find((c) => c.conversationId === conversationId) ?? null;
  }

  agentsInChannel(conversationId: string): { installId: string; activation: Activation }[] {
    return (
      this.db
        .prepare(
          `SELECT c.install_id, c.activation FROM ai_agent_channels c JOIN ai_agent_installs i ON i.id = c.install_id
            WHERE c.conversation_id = ? AND i.state = 'active' ORDER BY i.created_at`,
        )
        .all(conversationId) as { install_id: string; activation: Activation }[]
    ).map((r) => ({ installId: r.install_id, activation: r.activation }));
  }

  assign(installId: string, orgId: string, conversationId: string, activation: Activation, actor: string): void {
    this.db
      .prepare(
        `INSERT INTO ai_agent_channels (install_id, conversation_id, org_id, activation, added_by, added_at) VALUES (?, ?, ?, ?, ?, ?)
         ON CONFLICT (install_id, conversation_id) DO UPDATE SET activation = excluded.activation`,
      )
      .run(installId, conversationId, orgId, activation, actor, this.clock());
  }

  unassign(installId: string, conversationId: string): boolean {
    return this.db.prepare("DELETE FROM ai_agent_channels WHERE install_id = ? AND conversation_id = ?").run(installId, conversationId).changes > 0;
  }
}
