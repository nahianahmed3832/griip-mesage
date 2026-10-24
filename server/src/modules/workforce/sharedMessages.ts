import type { z } from "zod/v4";
import type { DB } from "../../infrastructure/database/db.js";
import type { AiSharedMessage } from "../../shared/contracts/http/aiWorkforce.js";
import type { AgentDef } from "./types.js";

/**
 * Channel messages a person shared with a request (docs/AI_WORKFORCE.md §2). Their app decrypted and picked them,
 * under a notice that they leave end-to-end encryption; the server can't read a channel. Only the job's skill reads
 * them (and the steps it delegates, through the root job): no route returns them, and they're deleted after 7 days.
 */
export const SHARED_RETENTION_MS = 7 * 86_400_000;

export type SharedMessage = z.infer<typeof AiSharedMessage>;

export class SharedMessageStore {
  constructor(
    private readonly db: DB,
    private readonly clock: () => number,
  ) {}

  add(s: { jobId: string; orgId: string; conversationId: string; sharedBy: string; messages: SharedMessage[] }): void {
    const t = this.clock();
    const sorted = [...s.messages].sort((a, b) => a.at - b.at);
    this.db
      .prepare("INSERT INTO ai_shared_messages (job_id, org_id, conversation_id, shared_by, messages, count, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
      .run(s.jobId, s.orgId, s.conversationId, s.sharedBy, JSON.stringify(sorted), sorted.length, t, t + SHARED_RETENTION_MS);
  }

  /** The messages shared with the first of [jobIds] that has some that haven't expired. */
  of(jobIds: readonly string[]): SharedMessage[] | null {
    for (const id of jobIds) {
      const row = this.db.prepare("SELECT messages FROM ai_shared_messages WHERE job_id = ? AND expires_at > ?").get(id, this.clock()) as { messages: string } | undefined;
      if (row) return JSON.parse(row.messages) as SharedMessage[];
    }
    return null;
  }

  /** How many messages were shared with [jobId] (0 once they've expired). */
  count(jobId: string): number {
    const row = this.db.prepare("SELECT count FROM ai_shared_messages WHERE job_id = ? AND expires_at > ?").get(jobId, this.clock()) as { count: number } | undefined;
    return row?.count ?? 0;
  }

  purgeExpired(): number {
    return this.db.prepare("DELETE FROM ai_shared_messages WHERE expires_at <= ?").run(this.clock()).changes;
  }
}

/** The shared messages as one block of text for a prompt: "[2026-10-24 14:02 UTC] Alice: …" per message. */
export function transcript(messages: readonly SharedMessage[]): string {
  return messages.map((m) => `[${new Date(m.at).toISOString().slice(0, 16).replace("T", " ")} UTC] ${m.author}: ${m.text}`).join("\n");
}

/** Agents that read text can be sent channel messages; the rest (CSV-only, video) can't use them. */
export function readsMessages(def: AgentDef): boolean {
  return def.skill.files.mimes.includes("text/plain");
}

/** What the channel's request post adds, so every member sees their messages went to the agent. */
export function sharedNote(count: number): string {
  return `Shared the last ${count === 1 ? "message" : `${count} messages`} here with the agent (not end-to-end encrypted).`;
}
