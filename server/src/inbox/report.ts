import { now } from "../util.js";
import { INBOX_PROVIDERS } from "./model.js";
import type { InboxService } from "./service.js";

/**
 * Inbox reporting (docs/UNIFIED_INBOX.md › Reporting): counts and times from recorded events only, for inbox admins.
 * Per-person numbers are activity counts, shown with context, never as a ranking.
 */
const DAY = 86_400_000;

function median(xs: number[]): number | null {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  const mid = Math.floor(s.length / 2);
  return s.length % 2 ? s[mid] : Math.round((s[mid - 1] + s[mid]) / 2);
}

export function inboxReport(svc: InboxService, orgId: string, userId: string, days: number) {
  svc.requireManage(orgId, userId);
  const since = now() - days * DAY;
  const db = svc.db;
  const one = (sql: string, ...p: unknown[]) => (db.prepare(sql).get(...p) as { n: number | null }).n ?? 0;
  const firstResponses = (db.prepare("SELECT created_at, first_response_at FROM inbox_conversations WHERE org_id = ? AND created_at >= ? AND first_response_at IS NOT NULL").all(orgId, since) as { created_at: number; first_response_at: number }[])
    .map((r) => Math.max(0, r.first_response_at - r.created_at));
  const resolutions = (db.prepare("SELECT created_at, resolved_at FROM inbox_conversations WHERE org_id = ? AND resolved_at >= ?").all(orgId, since) as { created_at: number; resolved_at: number }[])
    .map((r) => Math.max(0, r.resolved_at - r.created_at));
  const byProvider = INBOX_PROVIDERS.map((p) => ({
    provider: p,
    received: one("SELECT COUNT(*) n FROM inbox_messages m JOIN inbox_conversations c ON c.id = m.conversation_id WHERE m.org_id = ? AND c.provider = ? AND m.direction = 'in' AND m.created_at >= ?", orgId, p, since),
    failed: one("SELECT COUNT(*) n FROM inbox_messages m JOIN inbox_conversations c ON c.id = m.conversation_id WHERE m.org_id = ? AND c.provider = ? AND m.status = 'failed' AND m.created_at >= ?", orgId, p, since),
  }));
  const queues = (db.prepare("SELECT q.id, q.name, COUNT(c.id) open FROM inbox_queues q LEFT JOIN inbox_conversations c ON c.queue_id = q.id AND c.status != 'resolved' WHERE q.org_id = ? GROUP BY q.id ORDER BY q.name").all(orgId) as { id: string; name: string; open: number }[]);
  const agents = (db.prepare("SELECT author_id id, COUNT(*) replies FROM inbox_messages WHERE org_id = ? AND direction = 'out' AND origin != 'ai_auto' AND author_id IS NOT NULL AND created_at >= ? GROUP BY author_id").all(orgId, since) as { id: string; replies: number }[]);
  return {
    days,
    conversations: {
      started: one("SELECT COUNT(*) n FROM inbox_conversations WHERE org_id = ? AND created_at >= ?", orgId, since),
      resolved: resolutions.length,
      open: one("SELECT COUNT(*) n FROM inbox_conversations WHERE org_id = ? AND status != 'resolved'", orgId),
      unassigned: one("SELECT COUNT(*) n FROM inbox_conversations WHERE org_id = ? AND status != 'resolved' AND assignee_id IS NULL", orgId),
    },
    messages: {
      received: one("SELECT COUNT(*) n FROM inbox_messages WHERE org_id = ? AND direction = 'in' AND created_at >= ?", orgId, since),
      sentByPeople: one("SELECT COUNT(*) n FROM inbox_messages WHERE org_id = ? AND direction = 'out' AND origin != 'ai_auto' AND created_at >= ?", orgId, since),
      sentAutomatically: one("SELECT COUNT(*) n FROM inbox_messages WHERE org_id = ? AND direction = 'out' AND origin = 'ai_auto' AND created_at >= ?", orgId, since),
      sentFromAiDrafts: one("SELECT COUNT(*) n FROM inbox_messages WHERE org_id = ? AND direction = 'out' AND origin = 'ai_draft' AND created_at >= ?", orgId, since),
      failed: one("SELECT COUNT(*) n FROM inbox_messages WHERE org_id = ? AND status = 'failed' AND created_at >= ?", orgId, since),
    },
    times: { medianFirstResponseMs: median(firstResponses), medianResolutionMs: median(resolutions) },
    escalations: one("SELECT COUNT(*) n FROM audit_log WHERE org_id = ? AND action = 'inbox.ai_escalated' AND at >= ?", orgId, since),
    byProvider,
    queues,
    agents,
    accounts: svc.accounts(orgId, userId),
  };
}

/** Platform admins: health across workspaces, counts only (never conversations, names or content). */
export function inboxPlatformHealth(svc: InboxService) {
  const db = svc.db;
  return {
    accounts: db.prepare("SELECT provider, status, COUNT(*) n FROM inbox_accounts GROUP BY provider, status").all(),
    events: db.prepare("SELECT state, COUNT(*) n FROM inbox_events GROUP BY state").all(),
    outbox: db.prepare("SELECT status, COUNT(*) n FROM inbox_messages WHERE direction = 'out' AND status IN ('queued', 'failed') GROUP BY status").all(),
  };
}
