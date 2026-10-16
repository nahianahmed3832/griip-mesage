import type { Authz } from "./authz.js";
import type { AppContext } from "./context.js";
import type { DB } from "./db.js";
import { humanCode, newId, normalizeCode, now, sha256Hex } from "./util.js";

export interface OrgInviteRow {
  id: string;
  org_id: string;
  role: "ADMIN" | "MEMBER" | "GUEST";
  branch_id: string | null;
  team_id: string | null;
  max_uses: number;
  uses: number;
  expires_at: number;
  revoked_at: number | null;
  created_by: string;
}

export interface RegistrationInviteRow {
  id: string;
  code_hint: string;
  note: string | null;
  max_uses: number;
  uses: number;
  created_at: number;
  expires_at: number;
  revoked_at: number | null;
}

const usable = (r: { revoked_at: number | null; expires_at: number; uses: number; max_uses: number } | undefined) =>
  !!r && !r.revoked_at && r.expires_at > now() && r.uses < r.max_uses;

/** Why an org invitation code can't be used, as the API reports it (status 404 for all of them, code tells which). */
export type InviteProblem = "invite_not_found" | "invite_revoked" | "invite_expired" | "invite_used_up" | "invite_unavailable";

export const INVITE_PROBLEMS: Record<InviteProblem, string> = {
  invite_not_found: "This invite code doesn't exist. Check the code with the person who sent it.",
  invite_revoked: "This invite was cancelled by the person who created it. Ask them for a new one.",
  invite_expired: "This invite has expired. Ask for a new one.",
  invite_used_up: "This invite has already been used as many times as it allows. Ask for a new one.",
  invite_unavailable: "This invite can no longer be used: whoever created it can't invite people there any more. Ask for a new one.",
};

/** The org invitation stored for this code (usable or not), or undefined if no invitation has that code. */
export function lookupOrgInvite(db: DB, code: string): OrgInviteRow | undefined {
  return db.prepare("SELECT * FROM invitations WHERE code_hash = ?").get(sha256Hex(normalizeCode(code))) as OrgInviteRow | undefined;
}

/**
 * Checks an org invitation: usable means not revoked, expired or used up, its branch/team not archived (D-14), and its
 * creator may still issue it (T3.1): an invite made by someone who has since been demoted or removed stops working.
 */
export function orgInviteProblem(authz: Authz, inv: OrgInviteRow | undefined): InviteProblem | null {
  if (!inv) return "invite_not_found";
  if (inv.revoked_at) return "invite_revoked";
  if (inv.expires_at <= now()) return "invite_expired";
  if (inv.uses >= inv.max_uses) return "invite_used_up";
  if (authz.scopeArchived(inv.branch_id, inv.team_id)) return "invite_unavailable";
  if (!authz.canIssueInvite(inv.org_id, inv.created_by, inv.role, inv.branch_id, inv.team_id)) return "invite_unavailable";
  return null;
}

/** A usable org invitation for this code, or undefined (see [orgInviteProblem] for why not). */
export function findOrgInvite(db: DB, authz: Authz, code: string): OrgInviteRow | undefined {
  const inv = lookupOrgInvite(db, code);
  return orgInviteProblem(authz, inv) ? undefined : inv;
}

/**
 * Whether accepting [inv] would change nothing for [userId]: they're already in its org with at least its role and
 * already in the branch/team it names. Accepting it again then succeeds without using up the invite, so a retry or
 * a double tap never consumes a second use or fails with "used up".
 */
export function inviteAlreadySatisfied(authz: Authz, db: DB, inv: OrgInviteRow, userId: string): boolean {
  const existing = authz.orgRole(inv.org_id, userId);
  if (!existing) return false;
  const rank: Record<string, number> = { GUEST: 0, MEMBER: 1, ADMIN: 2, OWNER: 3 };
  if (rank[inv.role] > rank[existing]) return false;
  if (existing === "GUEST") return true; // guests aren't added to branches or teams
  if (inv.branch_id && !db.prepare("SELECT 1 FROM branch_members WHERE branch_id = ? AND user_id = ?").get(inv.branch_id, userId)) return false;
  if (inv.team_id && !db.prepare("SELECT 1 FROM team_members WHERE team_id = ? AND user_id = ?").get(inv.team_id, userId)) return false;
  return true;
}

/** A usable registration invite for this code. */
export function findRegistrationInvite(db: DB, code: string): RegistrationInviteRow | undefined {
  const inv = db.prepare("SELECT * FROM registration_invites WHERE code_hash = ?").get(sha256Hex(normalizeCode(code))) as
    | RegistrationInviteRow
    | undefined;
  return usable(inv) ? inv : undefined;
}

/**
 * Operator-only: creates a registration invite and returns its code, which is never stored.
 * Called from the admin CLI and tests; no HTTP route exposes it.
 */
export function createRegistrationInvite(db: DB, opts: { maxUses: number; days: number; note?: string | null }): { id: string; code: string } {
  if (!Number.isInteger(opts.maxUses) || opts.maxUses < 1 || opts.maxUses > 1000) throw new Error("uses must be 1–1000");
  if (!(opts.days > 0 && opts.days <= 30)) throw new Error("days must be more than 0 and at most 30");
  const code = humanCode(12);
  const id = newId("rinv");
  const t = now();
  db.prepare(
    "INSERT INTO registration_invites (id, code_hash, code_hint, note, max_uses, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
  ).run(id, sha256Hex(code), code.slice(-4), opts.note ?? null, opts.maxUses, t, t + Math.round(opts.days * 86_400_000));
  return { id, code: `${code.slice(0, 4)}-${code.slice(4, 8)}-${code.slice(8)}` };
}

/**
 * Joins `userId` to the invite's org (upgrade only: an invite never downgrades a member), adds the
 * branch or team the invite names, consumes one use and auto-joins the scope's public channels.
 * Must run inside the caller's transaction.
 */
export function joinOrgByInvite(ctx: AppContext, inv: OrgInviteRow, userId: string, t: number): { orgId: string; inviteId: string; role: string } {
  const { db, authz } = ctx;
  const existing = authz.orgRole(inv.org_id, userId);
  const rank: Record<string, number> = { GUEST: 0, MEMBER: 1, ADMIN: 2, OWNER: 3 };
  if (!existing) {
    db.prepare("INSERT INTO org_members (org_id, user_id, role, joined_at) VALUES (?, ?, ?, ?)").run(inv.org_id, userId, inv.role, t);
  } else if (rank[inv.role] > rank[existing]) {
    db.prepare("UPDATE org_members SET role = ? WHERE org_id = ? AND user_id = ?").run(inv.role, inv.org_id, userId);
  }
  const role = authz.orgRole(inv.org_id, userId)!;
  if (role !== "GUEST") {
    if (inv.branch_id) db.prepare("INSERT OR IGNORE INTO branch_members (branch_id, user_id, role, joined_at) VALUES (?, ?, 'MEMBER', ?)").run(inv.branch_id, userId, t);
    if (inv.team_id) db.prepare("INSERT OR IGNORE INTO team_members (team_id, user_id, role, joined_at) VALUES (?, ?, 'MEMBER', ?)").run(inv.team_id, userId, t);
  }
  db.prepare("UPDATE invitations SET uses = uses + 1 WHERE id = ?").run(inv.id);
  authz.autoJoinScope(inv.org_id, userId, t);
  return { orgId: inv.org_id, inviteId: inv.id, role };
}
