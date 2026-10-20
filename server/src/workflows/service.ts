import { meteredMailer } from "../accounts/mailer.js";
import type { ConversationRow } from "../authz.js";
import type { AppContext } from "../context.js";
import { errorKind } from "../ops/metrics.js";
import { nextOccurrence, retryDelayMs } from "../reminders/schedule.js";
import { notifyConversation } from "../routes/orgs.js";
import { ApiError, badRequest, conflict, forbidden, newId, notFound, now } from "../util.js";
import {
  type Action,
  conditionsHold,
  LIMITS,
  render,
  structuralProblems,
  type TriggerType,
  type WorkflowEvent,
  type WorkflowInput,
} from "./model.js";

/**
 * The workflow engine (docs/WORKFLOWS.md, round 7 slice C3). Events from real server actions become runs, one per
 * workflow and event; a timer claims due runs, performs their actions with the owner's current rights, records each
 * action's outcome, and retries transient failures with backoff. A workspace's runs per hour, a workflow's actions and
 * the depth of workflow-caused events are bounded.
 */

export interface WorkflowRow {
  id: string;
  org_id: string;
  name: string;
  trigger_type: TriggerType;
  trigger_json: string;
  conditions_json: string;
  actions_json: string;
  enabled: number;
  owner_id: string;
  next_run_at: number | null;
  rev: number;
  created_at: number;
  updated_at: number;
}

interface RunRow {
  id: string;
  workflow_id: string;
  org_id: string;
  event_key: string;
  event_json: string;
  depth: number;
  state: string;
  attempts: number;
  next_at: number;
  outcome_json: string | null;
  error_kind: string | null;
  created_at: number;
  finished_at: number | null;
}

/** One action's result in a run (or a dry run): what happened, never content. */
export interface ActionOutcome {
  type: Action["type"];
  status: "done" | "skipped" | "failed" | "would_run";
  detail: string;
}

/** A problem retrying can't fix (a channel gone, a right lost): the action fails, the others still run. */
class Permanent extends Error {}

const parseJson = <T>(s: string | null, fallback: T): T => {
  if (!s) return fallback;
  try {
    return JSON.parse(s) as T;
  } catch {
    return fallback;
  }
};

export class WorkflowService {
  private timer: NodeJS.Timeout | null = null;
  private running = false;

  constructor(private readonly ctx: AppContext) {}

  // ───────────── Definitions ─────────────

  view(w: WorkflowRow) {
    const last = this.ctx.db.prepare("SELECT state, created_at, finished_at, error_kind FROM workflow_runs WHERE workflow_id = ? AND dry_run = 0 ORDER BY created_at DESC LIMIT 1").get(w.id) as
      | { state: string; created_at: number; finished_at: number | null; error_kind: string | null }
      | undefined;
    const owner = this.ctx.db.prepare("SELECT display_name FROM users WHERE id = ?").get(w.owner_id) as { display_name: string } | undefined;
    return {
      id: w.id,
      orgId: w.org_id,
      name: w.name,
      trigger: { type: w.trigger_type, ...parseJson<object>(w.trigger_json, {}) },
      conditions: parseJson<unknown[]>(w.conditions_json, []),
      actions: parseJson<unknown[]>(w.actions_json, []),
      enabled: !!w.enabled,
      owner: { id: w.owner_id, name: owner?.display_name ?? null, allowed: this.ctx.authz.can(w.org_id, w.owner_id, "workflows.manage") },
      nextRunAt: w.next_run_at,
      rev: w.rev,
      createdAt: w.created_at,
      updatedAt: w.updated_at,
      lastRun: last ? { state: last.state, at: last.created_at, finishedAt: last.finished_at, errorKind: last.error_kind } : null,
    };
  }

  list(orgId: string): WorkflowRow[] {
    return this.ctx.db.prepare("SELECT * FROM workflows WHERE org_id = ? ORDER BY created_at").all(orgId) as WorkflowRow[];
  }

  get(orgId: string, id: string): WorkflowRow {
    const w = this.ctx.db.prepare("SELECT * FROM workflows WHERE id = ? AND org_id = ?").get(id, orgId) as WorkflowRow | undefined;
    if (!w) throw notFound("Workflow not found");
    return w;
  }

  /** Throws a 400 listing what's wrong: structure, and channels the actor couldn't reach in this workspace. */
  validate(orgId: string, actorId: string, input: WorkflowInput): void {
    const problems = [...structuralProblems(input)];
    for (const a of input.actions) {
      const ids = a.type === "post_channel" ? [a.channelId] : a.type === "add_to_channels" ? a.channelIds : [];
      for (const id of ids) {
        const c = this.ctx.authz.conversation(id);
        if (!c || c.type !== "CHANNEL" || c.org_id !== orgId || !this.ctx.authz.canViewChannel(c, actorId)) problems.push(`Channel ${id} isn't one you can use in this workspace`);
        else if (a.type === "add_to_channels" && c.kind === "PRIVATE" && !this.ctx.authz.canManageConversation(c, actorId)) problems.push(`You don't manage the private channel #${c.name}`);
      }
    }
    if (problems.length) throw badRequest(problems.join(". "), { problems });
  }

  private triggerJson(input: WorkflowInput): string {
    return JSON.stringify(input.trigger.type === "schedule" ? { schedule: input.trigger.schedule } : {});
  }

  private nextRunAt(input: WorkflowInput, t: number): number | null {
    return input.trigger.type === "schedule" && input.enabled ? nextOccurrence(input.trigger.schedule, t) : null;
  }

  create(orgId: string, actorId: string, input: WorkflowInput): WorkflowRow {
    this.validate(orgId, actorId, input);
    const count = (this.ctx.db.prepare("SELECT COUNT(*) n FROM workflows WHERE org_id = ?").get(orgId) as { n: number }).n;
    if (count >= LIMITS.workflowsPerOrg) throw conflict(`A workspace can have at most ${LIMITS.workflowsPerOrg} workflows`);
    this.ctx.entitlements.check("workflows", count, orgId);
    const id = newId("wf");
    const t = now();
    this.ctx.db
      .prepare(
        `INSERT INTO workflows (id, org_id, name, trigger_type, trigger_json, conditions_json, actions_json, enabled, owner_id, next_run_at, rev, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)`,
      )
      .run(id, orgId, input.name, input.trigger.type, this.triggerJson(input), JSON.stringify(input.conditions), JSON.stringify(input.actions), input.enabled ? 1 : 0, actorId, this.nextRunAt(input, t), t, t);
    this.ctx.audit({ actorId, orgId, action: "workflow.create", target: id, detail: input.trigger.type });
    return this.get(orgId, id);
  }

  /** Whoever saves a workflow becomes its owner: it then runs with their rights. */
  update(orgId: string, actorId: string, id: string, rev: number, input: WorkflowInput): WorkflowRow {
    const w = this.get(orgId, id);
    if (w.rev !== rev) throw new ApiError(409, "stale_revision", "Someone changed this workflow meanwhile. Reload it and try again.");
    this.validate(orgId, actorId, input);
    const t = now();
    this.ctx.db
      .prepare(
        `UPDATE workflows SET name = ?, trigger_type = ?, trigger_json = ?, conditions_json = ?, actions_json = ?, enabled = ?, owner_id = ?,
           next_run_at = ?, rev = rev + 1, updated_at = ? WHERE id = ?`,
      )
      .run(input.name, input.trigger.type, this.triggerJson(input), JSON.stringify(input.conditions), JSON.stringify(input.actions), input.enabled ? 1 : 0, actorId, this.nextRunAt(input, t), t, id);
    this.ctx.audit({ actorId, orgId, action: "workflow.update", target: id, detail: input.enabled ? "enabled" : "disabled" });
    return this.get(orgId, id);
  }

  remove(orgId: string, actorId: string, id: string): void {
    this.get(orgId, id);
    this.ctx.db.prepare("DELETE FROM workflows WHERE id = ?").run(id);
    this.ctx.audit({ actorId, orgId, action: "workflow.delete", target: id });
  }

  runs(orgId: string, id: string, limit = 50) {
    this.get(orgId, id);
    return (this.ctx.db.prepare("SELECT * FROM workflow_runs WHERE workflow_id = ? AND dry_run = 0 ORDER BY created_at DESC LIMIT ?").all(id, limit) as RunRow[]).map((r) => ({
      id: r.id,
      state: r.state,
      attempts: r.attempts,
      createdAt: r.created_at,
      finishedAt: r.finished_at,
      errorKind: r.error_kind,
      outcomes: parseJson<ActionOutcome[]>(r.outcome_json, []),
      event: (({ type, member, channel, occurrenceAt }) => ({ type, member: member ? { name: member.name } : undefined, channel: channel ? { name: channel.name } : undefined, occurrenceAt }))(
        parseJson<WorkflowEvent>(r.event_json, { type: "schedule", key: "", orgId, depth: 0 }),
      ),
    }));
  }

  cancel(orgId: string, actorId: string, runId: string): void {
    const r = this.ctx.db.prepare("SELECT * FROM workflow_runs WHERE id = ? AND org_id = ?").get(runId, orgId) as RunRow | undefined;
    if (!r) throw notFound("Run not found");
    if (r.state !== "queued") throw conflict("Only a run that hasn't started can be cancelled");
    this.ctx.db.prepare("UPDATE workflow_runs SET state = 'cancelled', finished_at = ? WHERE id = ? AND state = 'queued'").run(now(), runId);
    this.ctx.audit({ actorId, orgId, action: "workflow.run.cancel", target: runId });
  }

  // ───────────── Events ─────────────

  /** A server event happened: one queued run per enabled workflow it matches. Never throws into the caller. */
  emit(e: WorkflowEvent): void {
    this.toWebhooks(e);
    try {
      const t = now();
      const flows = this.ctx.db.prepare("SELECT * FROM workflows WHERE org_id = ? AND trigger_type = ? AND enabled = 1").all(e.orgId, e.type) as WorkflowRow[];
      for (const w of flows) {
        if (!conditionsHold(parseJson(w.conditions_json, []), e)) continue;
        const startedThisHour = (this.ctx.db.prepare("SELECT COUNT(*) n FROM workflow_runs WHERE org_id = ? AND created_at > ? AND dry_run = 0").get(e.orgId, t - 3_600_000) as { n: number }).n;
        const blocked = e.depth > LIMITS.maxDepth ? "loop_guard" : startedThisHour >= LIMITS.runsPerOrgPerHour ? "rate_limited" : null;
        this.ctx.db
          .prepare(
            `INSERT OR IGNORE INTO workflow_runs (id, workflow_id, org_id, event_key, event_json, depth, state, attempts, next_at, error_kind, created_at, finished_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?)`,
          )
          .run(newId("wfr"), w.id, e.orgId, e.key, JSON.stringify(e), e.depth, blocked ? "skipped" : "queued", t, blocked, t, blocked ? t : null);
      }
    } catch (err) {
      this.ctx.log.error({ errorType: errorKind(err) }, "workflow event not recorded");
    }
  }

  /** The same server events, for the workspace's webhooks (docs/DEVELOPER_API.md); private channels are left out. */
  private toWebhooks(e: WorkflowEvent): void {
    if (e.depth > 0 || !this.ctx.developer) return;
    const member = e.member && { id: e.member.id, displayName: e.member.name, role: e.member.newRole ?? e.member.role };
    if (e.type === "member.joined") this.ctx.developer.emit(e.orgId, "member.joined", e.key, { member });
    if (e.type === "member.left") this.ctx.developer.emit(e.orgId, "member.left", e.key, { member, reason: e.member?.leaveReason });
    if (e.type === "member.role_changed") this.ctx.developer.emit(e.orgId, "member.role_changed", e.key, { member, previousRole: e.member?.role });
    if (e.type === "channel.created" && e.channel && e.channel.kind !== "PRIVATE") {
      this.ctx.developer.emit(e.orgId, "channel.created", e.key, { channel: { id: e.channel.id, name: e.channel.name, kind: e.channel.kind, scope: e.channel.scope } });
    }
  }

  // ───────────── Running ─────────────

  start(intervalMs: number): void {
    if (this.timer || intervalMs <= 0) return;
    this.timer = setInterval(() => {
      if (this.running || !this.ctx.db.open) return;
      this.running = true;
      this.ctx.metrics
        .timeJob("workflows.tick", () => this.tick())
        .catch((err) => this.ctx.log.error({ errorType: errorKind(err) }, "workflow tick failed"))
        .finally(() => {
          this.running = false;
        });
    }, intervalMs);
    this.timer.unref();
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  /** Schedules due, then up to [batch] due runs. Exposed for tests. */
  async tick(batch = 20): Promise<{ scheduled: number; ran: number }> {
    const t = now();
    let scheduled = 0;
    for (const w of this.ctx.db.prepare("SELECT * FROM workflows WHERE enabled = 1 AND next_run_at IS NOT NULL AND next_run_at <= ?").all(t) as WorkflowRow[]) {
      const occurrence = w.next_run_at as number;
      this.emit({ type: "schedule", key: `schedule:${occurrence}`, orgId: w.org_id, depth: 0, occurrenceAt: occurrence });
      const rule = parseJson<{ schedule?: Parameters<typeof nextOccurrence>[0] }>(w.trigger_json, {}).schedule;
      this.ctx.db.prepare("UPDATE workflows SET next_run_at = ? WHERE id = ?").run(rule ? nextOccurrence(rule, Math.max(occurrence, t)) : null, w.id);
      scheduled++;
    }
    const due = this.ctx.db
      .prepare("SELECT * FROM workflow_runs WHERE dry_run = 0 AND ((state = 'queued' AND next_at <= ?) OR (state = 'running' AND lease_until < ?)) ORDER BY next_at LIMIT ?")
      .all(t, t, batch) as RunRow[];
    let ran = 0;
    for (const r of due) {
      const claimed = this.ctx.db
        .prepare("UPDATE workflow_runs SET state = 'running', lease_until = ?, attempts = attempts + 1 WHERE id = ? AND (state = 'queued' OR (state = 'running' AND lease_until < ?))")
        .run(t + LIMITS.leaseMs, r.id, t).changes;
      if (!claimed) continue;
      await this.execute({ ...r, attempts: r.attempts + 1 });
      ran++;
    }
    return { scheduled, ran };
  }

  private finish(runId: string, state: string, errorKindValue: string | null): void {
    this.ctx.db.prepare("UPDATE workflow_runs SET state = ?, error_kind = ?, finished_at = ?, lease_until = NULL WHERE id = ?").run(state, errorKindValue, now(), runId);
  }

  private async execute(run: RunRow): Promise<void> {
    const w = this.ctx.db.prepare("SELECT * FROM workflows WHERE id = ?").get(run.workflow_id) as WorkflowRow | undefined;
    if (!w) return this.finish(run.id, "cancelled", "workflow_deleted");
    if (!w.enabled) return this.finish(run.id, "skipped", "disabled");
    if (!this.ctx.authz.can(w.org_id, w.owner_id, "workflows.manage")) return this.finish(run.id, "failed", "owner_not_allowed");
    if (run.attempts === 1) this.ctx.usage?.add(w.org_id, "workflows.runs");
    const event = parseJson<WorkflowEvent>(run.event_json, { type: w.trigger_type, key: run.event_key, orgId: w.org_id, depth: run.depth });
    const actions = parseJson<Action[]>(w.actions_json, []);
    const outcomes = parseJson<ActionOutcome[]>(run.outcome_json, []);
    try {
      await withTimeout(
        (async () => {
          for (let i = 0; i < actions.length; i++) {
            if (outcomes[i] && outcomes[i].status !== "failed") continue; // done before a retry: never twice
            outcomes[i] = await this.perform(w, run, event, actions[i], i, false).catch((err: unknown) => {
              if (err instanceof Permanent) return { type: actions[i].type, status: "failed" as const, detail: err.message };
              throw err;
            });
            this.ctx.db.prepare("UPDATE workflow_runs SET outcome_json = ? WHERE id = ?").run(JSON.stringify(outcomes), run.id);
          }
        })(),
        LIMITS.runTimeoutMs,
      );
      const failed = outcomes.find((o) => o.status === "failed");
      this.finish(run.id, failed ? "failed" : "done", failed ? "action_failed" : null);
    } catch (err) {
      const kind = errorKind(err);
      if (run.attempts >= LIMITS.maxAttempts) return this.finish(run.id, "failed", kind);
      this.ctx.db.prepare("UPDATE workflow_runs SET state = 'queued', next_at = ?, error_kind = ?, lease_until = NULL WHERE id = ?").run(now() + retryDelayMs(run.attempts), kind, run.id);
    }
  }

  /**
   * One action, with the owner's current rights. With [dry] nothing changes: the outcome says what would happen.
   * Throws [Permanent] for problems retrying can't fix; anything else is retried.
   */
  private async perform(w: WorkflowRow, run: { id: string }, e: WorkflowEvent, a: Action, index: number, dry: boolean): Promise<ActionOutcome> {
    const { authz, db } = this.ctx;
    const org = db.prepare("SELECT name FROM orgs WHERE id = ?").get(w.org_id) as { name: string } | undefined;
    const workspace = org?.name ?? "";
    const channel = (id: string): ConversationRow => {
      const c = authz.conversation(id);
      if (!c || c.type !== "CHANNEL" || c.org_id !== w.org_id) throw new Permanent("The channel no longer exists");
      if (c.archived_at || authz.conversationScopeArchived(c)) throw new Permanent(`#${c.name} is archived`);
      if (!authz.canViewChannel(c, w.owner_id)) throw new Permanent(`The workflow's owner can't see #${c.name} any more`);
      return c;
    };
    switch (a.type) {
      case "post_channel": {
        const c = channel(a.channelId);
        if (c.kind === "ANNOUNCEMENT" && !authz.canManageConversation(c, w.owner_id)) throw new Permanent(`Only managers post in #${c.name}`);
        if (dry) return { type: a.type, status: "would_run", detail: `Would post in #${c.name}: "${render(a.text, e, workspace)}"` };
        await this.ctx.integrations.publishWorkflowUpdate({
          orgId: w.org_id, conversationId: c.id, workflowId: w.id, workflowName: w.name, dedupeKey: `${run.id}:${index}`, text: render(a.text, e, workspace), at: now(),
        });
        return { type: a.type, status: "done", detail: `Posted in #${c.name}` };
      }
      case "add_to_channels": {
        const member = e.member;
        if (!member) throw new Permanent("This event has no member");
        if (!authz.orgRole(w.org_id, member.id)) return { type: a.type, status: "skipped", detail: "They're no longer in the workspace" };
        const names: string[] = [];
        for (const id of a.channelIds) {
          const c = channel(id);
          const allowed = c.kind === "PRIVATE" ? authz.canManageConversation(c, w.owner_id) : authz.scopeEligible(c, member.id);
          if (!allowed) continue;
          names.push(`#${c.name}`);
          if (dry) continue;
          if (db.prepare("INSERT OR IGNORE INTO conversation_members (conversation_id, user_id, role, joined_at) VALUES (?, ?, 'MEMBER', ?)").run(c.id, member.id, now()).changes) {
            notifyConversation(this.ctx, c.id, [member.id]);
          }
        }
        if (!names.length) return { type: a.type, status: "skipped", detail: "None of the channels is open to them" };
        return { type: a.type, status: dry ? "would_run" : "done", detail: `${dry ? "Would add" : "Added"} them to ${names.join(", ")}` };
      }
      case "email_admins": {
        const mailer = meteredMailer(this.ctx.accounts.mailer, this.ctx.metrics);
        if (!mailer.available) return { type: a.type, status: "skipped", detail: "This server can't send email" };
        const admins = (db.prepare("SELECT user_id FROM org_members WHERE org_id = ? AND role IN ('OWNER','ADMIN') ORDER BY joined_at").all(w.org_id) as { user_id: string }[]).map((r) => r.user_id);
        const to: string[] = [];
        for (const uid of admins) {
          const acct = await this.ctx.accounts.store.accountByUserId(uid);
          if (acct?.email_verified_at && !acct.disabled_at) to.push(acct.email);
          if (to.length >= LIMITS.emailRecipients) break;
        }
        if (!to.length) return { type: a.type, status: "skipped", detail: "No admin has a verified email address" };
        if (dry) return { type: a.type, status: "would_run", detail: `Would email ${to.length} admin${to.length === 1 ? "" : "s"}` };
        const subject = render(a.subject, e, workspace);
        const text = `${render(a.text, e, workspace)}\n\nSent by the workflow "${w.name}" in ${workspace}. Workspace admins can change or switch it off in Meldiq.`;
        for (const addr of to) await mailer.send({ to: addr, subject, text });
        this.ctx.usage?.add(w.org_id, "workflows.emails", to.length);
        return { type: a.type, status: "done", detail: `Emailed ${to.length} admin${to.length === 1 ? "" : "s"}` };
      }
    }
  }

  /** What a workflow would do for a sample event, changing nothing (docs/WORKFLOWS.md › Testing). */
  async dryRun(orgId: string, actorId: string, input: WorkflowInput, name: string) {
    this.validate(orgId, actorId, input);
    const actor = this.ctx.db.prepare("SELECT display_name FROM users WHERE id = ?").get(actorId) as { display_name: string } | undefined;
    const sample: WorkflowEvent = {
      type: input.trigger.type,
      key: "dry-run",
      orgId,
      depth: 0,
      member: { id: actorId, name: actor?.display_name ?? "A member", role: this.ctx.authz.orgRole(orgId, actorId) ?? "MEMBER", newRole: "ADMIN", leaveReason: "left" },
      channel: { id: "sample", name: "new-channel", kind: "PUBLIC", scope: "ORG" },
      occurrenceAt: input.trigger.type === "schedule" ? nextOccurrence(input.trigger.schedule, now()) : undefined,
    };
    const fake: WorkflowRow = {
      id: "dry-run", org_id: orgId, name, trigger_type: input.trigger.type, trigger_json: "{}", conditions_json: "[]", actions_json: "[]",
      enabled: 1, owner_id: actorId, next_run_at: null, rev: 0, created_at: 0, updated_at: 0,
    };
    const outcomes: ActionOutcome[] = [];
    for (const [i, a] of input.actions.entries()) {
      outcomes.push(
        await this.perform(fake, { id: "dry-run" }, sample, a, i, true).catch((err: unknown) =>
          err instanceof Permanent ? { type: a.type, status: "failed" as const, detail: err.message } : { type: a.type, status: "failed" as const, detail: "Couldn't check this action" },
        ),
      );
    }
    return { conditionsHoldForSample: conditionsHold(input.conditions, sample), nextRunAt: sample.occurrenceAt ?? null, outcomes };
  }

  /** Throws unless [userId] is in the workspace and holds `workflows.manage` (admins and owners by default). */
  requireManager(orgId: string, userId: string): void {
    this.ctx.authz.requireOrgMember(orgId, userId);
    if (!this.ctx.authz.can(orgId, userId, "workflows.manage")) throw forbidden("Only people who can manage workflows can do that");
  }
}

async function withTimeout<T>(p: Promise<T>, ms: number): Promise<T> {
  let timer: NodeJS.Timeout | undefined;
  try {
    return await Promise.race([p, new Promise<never>((_, reject) => (timer = setTimeout(() => reject(Object.assign(new Error("timeout"), { code: "timeout" })), ms)))]);
  } finally {
    clearTimeout(timer);
  }
}
