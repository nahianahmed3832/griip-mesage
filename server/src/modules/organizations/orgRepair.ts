import type { DB } from "../../infrastructure/database/db.js";

/**
 * Operator repair for duplicate organisations (admin CLI `org-duplicates`, `org-delete-empty`).
 *
 * Before org creation was idempotent (migration 006), a retried or repeated "Create workspace" made a new organisation
 * each time, so one person could own several workspaces with the same name. Nothing here runs automatically: the
 * operator lists the duplicates, reads what each one holds, and removes only one that is provably empty.
 */

export interface OrgFootprint {
  id: string;
  name: string;
  slug: string;
  createdAt: number;
  members: number;
  branches: number;
  teams: number;
  channels: number;
  /** Channels with someone other than the owner in them. */
  sharedChannels: number;
  usedInvites: number;
  attachments: number;
  calls: number;
  queued: number;
}

export interface DuplicateGroup {
  ownerId: string;
  ownerUsername: string | null;
  name: string;
  orgs: (OrgFootprint & { removable: boolean; keepReason: string | null })[];
}

const n = (db: DB, sql: string, ...p: unknown[]) => (db.prepare(sql).get(...p) as { n: number }).n;

/** What an organisation holds, in counts only (no names of people, no content). */
export function orgFootprint(db: DB, orgId: string): OrgFootprint | undefined {
  const o = db.prepare("SELECT id, name, slug, created_at, created_by FROM orgs WHERE id = ?").get(orgId) as
    | { id: string; name: string; slug: string; created_at: number; created_by: string }
    | undefined;
  if (!o) return undefined;
  const conv = "SELECT id FROM conversations WHERE org_id = ?";
  return {
    id: o.id,
    name: o.name,
    slug: o.slug,
    createdAt: o.created_at,
    members: n(db, "SELECT COUNT(*) n FROM org_members WHERE org_id = ?", orgId),
    branches: n(db, "SELECT COUNT(*) n FROM branches WHERE org_id = ?", orgId),
    teams: n(db, "SELECT COUNT(*) n FROM teams WHERE org_id = ?", orgId),
    channels: n(db, "SELECT COUNT(*) n FROM conversations WHERE org_id = ?", orgId),
    sharedChannels: n(
      db,
      `SELECT COUNT(DISTINCT cm.conversation_id) n FROM conversation_members cm WHERE cm.conversation_id IN (${conv}) AND cm.user_id != ?`,
      orgId,
      o.created_by,
    ),
    usedInvites: n(db, "SELECT COUNT(*) n FROM invitations WHERE org_id = ? AND uses > 0", orgId),
    attachments: n(db, `SELECT COUNT(*) n FROM attachments WHERE conversation_id IN (${conv})`, orgId),
    calls: n(db, `SELECT COUNT(*) n FROM calls WHERE conversation_id IN (${conv})`, orgId),
    queued: n(db, `SELECT COUNT(*) n FROM delivery_queue WHERE conversation_id IN (${conv})`, orgId),
  };
}

/**
 * Why [orgId] must be kept, or null if it is provably empty: only its creator is a member (as owner), no branches or
 * teams, nobody has ever joined through one of its invites, its channels (if any) contain only the owner, and no
 * attachment, call or undelivered message references it.
 */
export function keepReason(db: DB, orgId: string): string | null {
  const f = orgFootprint(db, orgId);
  if (!f) return "no such organisation";
  const owner = db.prepare("SELECT created_by FROM orgs WHERE id = ?").get(orgId) as { created_by: string };
  const role = db.prepare("SELECT role FROM org_members WHERE org_id = ? AND user_id = ?").get(orgId, owner.created_by) as { role: string } | undefined;
  if (f.members !== 1 || role?.role !== "OWNER") return `${f.members} members`;
  if (f.branches > 0 || f.teams > 0) return "has branches or teams";
  if (f.usedInvites > 0) return "someone joined through one of its invites";
  if (f.sharedChannels > 0) return "has channels shared with other people";
  if (f.attachments > 0 || f.calls > 0 || f.queued > 0) return "has files, calls or undelivered messages";
  return null;
}

/** Groups of organisations owned by the same person under the same name, oldest first in each group. */
export function findDuplicateOrgs(db: DB): DuplicateGroup[] {
  const groups = db
    .prepare(
      `SELECT o.created_by owner, lower(trim(o.name)) k, COUNT(*) c FROM orgs o
        JOIN org_members m ON m.org_id = o.id AND m.user_id = o.created_by AND m.role = 'OWNER'
       GROUP BY o.created_by, lower(trim(o.name)) HAVING c > 1`,
    )
    .all() as { owner: string; k: string; c: number }[];
  return groups.map((g) => {
    const ids = (
      db.prepare("SELECT id FROM orgs WHERE created_by = ? AND lower(trim(name)) = ? ORDER BY created_at, id").all(g.owner, g.k) as { id: string }[]
    ).map((r) => r.id);
    const orgs = ids.map((id) => {
      const f = orgFootprint(db, id)!;
      const reason = keepReason(db, id);
      return { ...f, removable: reason === null, keepReason: reason };
    });
    const user = db.prepare("SELECT username FROM users WHERE id = ?").get(g.owner) as { username: string } | undefined;
    return { ownerId: g.owner, ownerUsername: user?.username ?? null, name: orgs[0]?.name ?? g.k, orgs };
  });
}

/**
 * Deletes an organisation only if [keepReason] finds nothing to keep (checked inside the same transaction).
 * Its owner-only channels go with it (foreign keys cascade). Throws, deleting nothing, otherwise.
 */
export function deleteEmptyOrg(db: DB, orgId: string): OrgFootprint {
  return db.transaction(() => {
    const reason = keepReason(db, orgId);
    if (reason) throw new Error(`refusing to delete ${orgId}: ${reason}`);
    const f = orgFootprint(db, orgId)!;
    db.prepare("DELETE FROM orgs WHERE id = ?").run(orgId);
    return f;
  })();
}
