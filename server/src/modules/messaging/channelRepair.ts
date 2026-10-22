import { Authz, type ConversationRow } from "../organizations/authz.js";
import type { DB } from "../../infrastructure/database/db.js";

/**
 * Repair for round 7 bug 6 (docs/MELDIQ_ROUND7_PLAN_2026-10-19.md): public channels created before the fix were
 * stored as opt-in (auto_join = 0) because neither app sent `autoJoin`, so workspace members weren't in them. The
 * repair marks those channels auto-joined and adds the members who may see them, as the fixed server does for new
 * channels. Someone who left a channel themselves (an audit `channel.leave`) isn't put back. Private, announcement,
 * archived and already auto-joined channels are untouched. Admin CLI: `channel-access-repair [--yes]`.
 */
export interface ChannelRepair {
  channelId: string;
  orgId: string;
  name: string;
  scope: string;
  add: string[];
  skippedLeft: number;
}

export function planChannelAccessRepair(db: DB): ChannelRepair[] {
  const authz = new Authz(db);
  const channels = db
    .prepare("SELECT * FROM conversations WHERE type = 'CHANNEL' AND kind = 'PUBLIC' AND auto_join = 0 AND archived_at IS NULL ORDER BY created_at")
    .all() as ConversationRow[];
  return channels.map((c) => {
    const left = new Set(
      (db.prepare("SELECT target FROM audit_log WHERE action = 'channel.leave' AND target LIKE ?").all(`${c.id}:%`) as { target: string }[]).map(
        (r) => r.target.slice(c.id.length + 1),
      ),
    );
    const candidates = (
      db
        .prepare(
          `SELECT user_id FROM org_members WHERE org_id = ?
           AND user_id NOT IN (SELECT user_id FROM conversation_members WHERE conversation_id = ?)`,
        )
        .all(c.org_id, c.id) as { user_id: string }[]
    )
      .map((r) => r.user_id)
      .filter((u) => authz.scopeEligible(c, u));
    return {
      channelId: c.id,
      orgId: c.org_id ?? "",
      name: c.name ?? "",
      scope: c.scope ?? "",
      add: candidates.filter((u) => !left.has(u)),
      skippedLeft: candidates.filter((u) => left.has(u)).length,
    };
  });
}

/** Applies [plan] in one transaction; returns how many memberships were added. */
export function applyChannelAccessRepair(db: DB, plan: ChannelRepair[], now: number): number {
  let added = 0;
  db.transaction(() => {
    for (const r of plan) {
      db.prepare("UPDATE conversations SET auto_join = 1, updated_at = ? WHERE id = ?").run(now, r.channelId);
      for (const u of r.add) {
        added += db.prepare("INSERT OR IGNORE INTO conversation_members (conversation_id, user_id, role, joined_at) VALUES (?, ?, 'MEMBER', ?)").run(r.channelId, u, now).changes;
      }
    }
  })();
  return added;
}
