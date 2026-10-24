import crypto from "node:crypto";
import type { DB } from "../../infrastructure/database/db.js";
import { ApiError } from "../../shared/errors.js";
import { newId } from "../../shared/utilities/util.js";

/**
 * Approvals (docs/AI_WORKFORCE.md §6): one action of one job, bound to one exact file (id and SHA-256) or one payload
 * (its hash). A revision, a cancellation or a newer request for the same action invalidates it. Only people decide
 * (routes check `ai.approve`); agents can ask, never answer.
 */
export type ApprovalStatus = "pending" | "approved" | "rejected" | "invalidated";

export interface ApprovalRow {
  id: string;
  org_id: string;
  job_id: string;
  action: string;
  artifact_id: string | null;
  artifact_sha: string | null;
  payload_hash: string | null;
  summary: string;
  state: ApprovalStatus;
  requested_at: number;
  decided_by: string | null;
  decided_at: number | null;
  note: string | null;
}

/** A stable hash of a JSON value (keys sorted), so the same action always hashes the same. */
export function payloadHash(value: unknown): string {
  const canon = (v: unknown): unknown =>
    Array.isArray(v) ? v.map(canon) : v && typeof v === "object" ? Object.fromEntries(Object.keys(v as object).sort().map((k) => [k, canon((v as Record<string, unknown>)[k])])) : v;
  return crypto.createHash("sha256").update(JSON.stringify(canon(value) ?? null)).digest("hex");
}

export class Approvals {
  constructor(
    private readonly db: DB,
    private readonly clock: () => number,
  ) {}

  get(id: string): ApprovalRow | null {
    return (this.db.prepare("SELECT * FROM ai_approvals WHERE id = ?").get(id) as ApprovalRow | undefined) ?? null;
  }

  request(a: { orgId: string; jobId: string; action: string; artifactId?: string | null; artifactSha?: string | null; payloadHash?: string | null; summary: string }): ApprovalRow {
    const id = newId("apr");
    const t = this.clock();
    this.db.transaction(() => {
      this.db.prepare("UPDATE ai_approvals SET state = 'invalidated', decided_at = ? WHERE job_id = ? AND action = ? AND state = 'pending'").run(t, a.jobId, a.action);
      this.db
        .prepare("INSERT INTO ai_approvals (id, org_id, job_id, action, artifact_id, artifact_sha, payload_hash, summary, state, requested_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?)")
        .run(id, a.orgId, a.jobId, a.action, a.artifactId ?? null, a.artifactSha ?? null, a.payloadHash ?? null, a.summary.slice(0, 500), t);
    })();
    return this.get(id)!;
  }

  decide(id: string, decision: "approved" | "rejected", userId: string, note: string | null): ApprovalRow {
    const ok = this.db.prepare("UPDATE ai_approvals SET state = ?, decided_by = ?, decided_at = ?, note = ? WHERE id = ? AND state = 'pending'").run(decision, userId, this.clock(), note, id).changes;
    if (!ok) throw new ApiError(409, "approval_stale", "This request was already decided, or replaced by a newer one.");
    return this.get(id)!;
  }

  invalidateJob(jobId: string): void {
    this.db.prepare("UPDATE ai_approvals SET state = 'invalidated', decided_at = ? WHERE job_id = ? AND state IN ('pending', 'approved')").run(this.clock(), jobId);
  }

  ofJob(jobId: string): ApprovalRow[] {
    return this.db.prepare("SELECT * FROM ai_approvals WHERE job_id = ? ORDER BY requested_at").all(jobId) as ApprovalRow[];
  }

  pending(orgId: string): ApprovalRow[] {
    return this.db.prepare("SELECT * FROM ai_approvals WHERE org_id = ? AND state = 'pending' ORDER BY requested_at DESC LIMIT 200").all(orgId) as ApprovalRow[];
  }

  /** Throws unless [id] is an approved approval of exactly this job, action, file version or payload. */
  require(id: string | null | undefined, want: { jobId: string; action: string; artifactId?: string | null; artifactSha?: string | null; payloadHash?: string | null }): ApprovalRow {
    if (!id) throw new ApiError(409, "approval_required", "This action needs a person's approval first.");
    const a = this.get(id);
    const matches =
      a !== null &&
      a.state === "approved" &&
      a.job_id === want.jobId &&
      a.action === want.action &&
      (want.artifactId === undefined || a.artifact_id === want.artifactId) &&
      (want.artifactSha === undefined || a.artifact_sha === want.artifactSha) &&
      (want.payloadHash === undefined || a.payload_hash === want.payloadHash);
    if (!matches) throw new ApiError(409, "approval_stale", "The approval doesn't match this exact action or file version.");
    return a;
  }
}
