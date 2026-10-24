import type { DB } from "../../infrastructure/database/db.js";
import { newId } from "../../shared/utilities/util.js";

/**
 * What agents post in channels (docs/AI_WORKFORCE.md §2): requests people sent them, progress, results, approval
 * requests and errors. NOT end-to-end encrypted: agents never hold message keys, so these are stored on the server and
 * every client labels them. Read access is checked by the route (channel membership and view permission).
 */
export type PostKind = "request" | "ack" | "progress" | "result" | "approval" | "error";

export interface PostRow {
  id: string;
  org_id: string;
  conversation_id: string;
  thread_root: string | null;
  install_id: string;
  job_id: string | null;
  kind: PostKind;
  body: string;
  file_ids: string;
  approval_id: string | null;
  author_id: string | null;
  created_at: number;
  expires_at: number;
}

export const POST_RETENTION_MS = 30 * 86_400_000;
export const MAX_POST_CHARS = 8000;

export class AgentPosts {
  constructor(
    private readonly db: DB,
    private readonly clock: () => number,
  ) {}

  add(p: { orgId: string; conversationId: string; threadRoot: string | null; installId: string; jobId: string | null; kind: PostKind; body: string; fileIds?: string[]; approvalId?: string | null; authorId?: string | null }): PostRow {
    const id = newId("aipost");
    const t = this.clock();
    this.db
      .prepare(
        `INSERT INTO ai_channel_posts (id, org_id, conversation_id, thread_root, install_id, job_id, kind, body, file_ids, approval_id, author_id, created_at, expires_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(id, p.orgId, p.conversationId, p.threadRoot, p.installId, p.jobId, p.kind, p.body.slice(0, MAX_POST_CHARS), JSON.stringify(p.fileIds ?? []), p.approvalId ?? null, p.authorId ?? null, t, t + POST_RETENTION_MS);
    return this.db.prepare("SELECT * FROM ai_channel_posts WHERE id = ?").get(id) as PostRow;
  }

  list(conversationId: string, opts: { since?: number; before?: number; jobId?: string }, limit: number): PostRow[] {
    const where = ["conversation_id = ?", "expires_at > ?"];
    const args: (string | number)[] = [conversationId, this.clock()];
    if (opts.since !== undefined) (where.push("created_at > ?"), args.push(opts.since));
    if (opts.before !== undefined) (where.push("created_at < ?"), args.push(opts.before));
    if (opts.jobId) (where.push("job_id = ?"), args.push(opts.jobId));
    const order = opts.since !== undefined ? "ASC" : "DESC";
    const rows = this.db.prepare(`SELECT * FROM ai_channel_posts WHERE ${where.join(" AND ")} ORDER BY created_at ${order} LIMIT ?`).all(...args, limit) as PostRow[];
    return order === "DESC" ? rows.reverse() : rows;
  }

  ofJob(jobId: string): PostRow[] {
    return this.db.prepare("SELECT * FROM ai_channel_posts WHERE job_id = ? ORDER BY created_at").all(jobId) as PostRow[];
  }

  purgeExpired(): number {
    return this.db.prepare("DELETE FROM ai_channel_posts WHERE expires_at < ?").run(this.clock()).changes;
  }
}
