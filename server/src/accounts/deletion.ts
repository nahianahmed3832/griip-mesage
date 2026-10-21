import crypto from "node:crypto";
import type { AppContext } from "../context.js";
import type { DB } from "../db.js";
import { photoBlobId } from "../photos.js";
import { processBlobDeletes, queueBlobDelete } from "../routes/attachments.js";
import { revokeDeviceRows } from "../routes/auth.js";
import { evictUserDevicesFromCalls } from "../routes/calls.js";
import { deleteHistoryAfterCommit } from "../routes/history.js";
import { notifyConversation, notifyOrg, removeFromWorkspace } from "../routes/orgs.js";
import { ApiError, now } from "../util.js";
import type { Accounts } from "./index.js";
import type { AccountRow } from "./store.js";

/**
 * Deleting an account (docs/ACCOUNT_DELETION.md). Ported from the round-4 design (ba13c28, 2026-10-18) to the Orbit 2.0
 * account model: an email-and-password account (accounts) on top of an end-to-end encryption identity (users + devices).
 *
 * What goes: the account row (email freed, sessions, codes, recovery codes), the identity's profile (names, username,
 * phone, birthday, bio, photo, privacy), every device's sessions, queued deliveries and wake-up addresses, the encrypted
 * server history, blocks, unfinished uploads, reminders, workspace arrangements, personal app connections, and every
 * membership except direct conversations. What stays: an anonymous users row with the identity's id and public identity
 * key, so a direct conversation stays one conversation for the other person and shows "Deleted account"; files already
 * sent into conversations, which belong to them and expire under the normal rules; workspace content that belongs to the
 * community (audit entries), now attributed to "Deleted account". Messages already delivered are
 * on the recipients' devices.
 *
 * Retry-safe: the steps can be repeated after a failure part-way (the account row goes last).
 */

/** The name contacts see for a deleted account. */
export const DELETED_DISPLAY_NAME = "Deleted account";

/** Deleting an account can't be undone: a few attempts a minute is plenty. */
export const DELETE_ACCOUNT_RATE_LIMIT = { max: 10, timeWindow: "1 minute" };

export interface OwnedWorkspace {
  id: string;
  name: string;
  members: number;
}

export interface AccountDeletionResult {
  deletedAt: number;
  /** Whether an encryption identity was anonymised (an account that never set up a device has none). */
  identity: boolean;
  devices: number;
  conversations: number;
  workspaces: number;
}

/** Workspaces this person is the only owner of. The account can't be deleted until each is handed over or deleted. */
export function soleOwnedWorkspaces(db: DB, userId: string): OwnedWorkspace[] {
  return db
    .prepare(
      `SELECT o.id, o.name, (SELECT COUNT(*) FROM org_members x WHERE x.org_id = o.id) AS members
       FROM org_members m JOIN orgs o ON o.id = m.org_id
       WHERE m.user_id = ? AND m.role = 'OWNER'
         AND (SELECT COUNT(*) FROM org_members y WHERE y.org_id = o.id AND y.role = 'OWNER') = 1
       ORDER BY o.name`,
    )
    .all(userId) as OwnedWorkspace[];
}

const ids = (db: DB, sql: string, ...args: unknown[]): string[] => (db.prepare(sql).all(...args) as { id: string }[]).map((r) => r.id);

/** The person's password-checked request: `DELETE /v2/account`. */
export async function deleteOwnAccount(
  ctx: AppContext,
  accounts: Accounts,
  account: AccountRow,
  body: { password: string; confirmEmail: string },
  ip: string,
  reply: { header: (k: string, v: string) => unknown },
): Promise<AccountDeletionResult> {
  await accounts.service.checkPassword(account, body.password, ip, reply);
  if (body.confirmEmail !== account.email.toLowerCase()) {
    throw new ApiError(400, "confirm_mismatch", "Type your account's email address exactly to delete your account. Nothing was deleted.");
  }
  return eraseAccount(ctx, accounts, account, account.user_id);
}

/**
 * Erases [account] and anonymises its encryption identity. Refused (409 owns_workspaces, nothing changed) while the
 * identity is the only owner of a workspace. The caller has checked who is asking.
 */
export async function eraseAccount(ctx: AppContext, accounts: Accounts, account: AccountRow, actorId: string | null): Promise<AccountDeletionResult> {
  const userId = account.user_id;
  if (userId) {
    const owned = soleOwnedWorkspaces(ctx.db, userId);
    if (owned.length) {
      throw new ApiError(
        409,
        "owns_workspaces",
        "You're the only owner of a workspace. Make someone else an owner, or delete the workspace, first. Nothing was deleted.",
        { workspaces: owned },
      );
    }
  }
  const t = now();
  // Signed out everywhere first, so nothing acts for the account while it is taken apart.
  await accounts.service.revokeSessions(account.id, "account_deleted");
  let result: AccountDeletionResult = { deletedAt: t, identity: false, devices: 0, conversations: 0, workspaces: 0 };
  if (userId) result = await eraseIdentity(ctx, userId, actorId, t);
  await accounts.recovery.store.deleteForAccount(account.id);
  await accounts.keyBackups.store.deleteForAccount(account.id);
  await accounts.store.deleteAccount(account.id);
  // Counts only: the email, username and profile are gone, and the audit log mustn't keep them.
  ctx.audit({
    actorId: actorId ?? userId,
    action: "account.delete",
    target: userId ?? account.id,
    detail: JSON.stringify({ devices: result.devices, conversations: result.conversations, workspaces: result.workspaces, by: actorId ? "self" : "operator" }),
  });
  return result;
}

/** Leaves every workspace, then anonymises the identity in one transaction, then tells everyone concerned. */
async function eraseIdentity(ctx: AppContext, userId: string, actorId: string | null, t: number): Promise<AccountDeletionResult> {
  const { db } = ctx;
  const orgs = ids(db, "SELECT org_id AS id FROM org_members WHERE user_id = ?", userId);
  // Each community exactly as if the person left it: roles, meetings, channels, arrangement.
  for (const orgId of orgs) await removeFromWorkspace(ctx, orgId, userId, actorId ?? userId, "left");

  const peers = ids(
    db,
    `SELECT DISTINCT m2.user_id AS id FROM conversation_members m1 JOIN conversation_members m2 ON m2.conversation_id = m1.conversation_id
     WHERE m1.user_id = ? AND m2.user_id != ?`,
    userId,
    userId,
  );
  const memberships = db.prepare("SELECT c.id, c.type FROM conversation_members m JOIN conversations c ON c.id = m.conversation_id WHERE m.user_id = ?").all(userId) as {
    id: string;
    type: string;
  }[];
  const devices = ids(db, "SELECT id FROM devices WHERE user_id = ?", userId);
  const photoRev = (db.prepare("SELECT photo_rev FROM users WHERE id = ?").get(userId) as { photo_rev: number | null } | undefined)?.photo_rev ?? null;

  let removed: string[] = [];
  let rooms: string[] = [];
  db.transaction(() => {
    for (const d of devices) revokeDeviceRows(ctx, d, t);
    deletePersonalRecords(db, userId);
    leaveConversations(db, userId, memberships);
    anonymize(ctx, userId, photoRev, t);
    removed = memberships.filter((m) => m.type !== "CHANNEL" && !hasLiveMember(db, m.id)).map((m) => m.id);
    rooms = removed.flatMap((c) => removeConversation(ctx, c));
  }).immediate();

  // Stores that may live outside SQLite (MongoDB domains).
  await ctx.prefs.deleteForUser(userId);
  await deleteHistoryAfterCommit(ctx, userId, "account deleted");

  ctx.hub.disconnectUser(userId, "account deleted");
  ctx.hub.sendToUsers(peers, { t: "devices.changed", userId });
  const kept = memberships.map((m) => m.id).filter((c) => !removed.includes(c));
  for (const c of kept) notifyConversation(ctx, c);
  for (const o of orgs) notifyOrg(ctx, o);
  await evictUserDevicesFromCalls(ctx, userId);
  for (const room of rooms) await ctx.livekit?.deleteRoom(room).catch(() => {});
  await processBlobDeletes(ctx).catch((e) => ctx.log.warn({ err: e }, "account deletion: stored files left for the retention job"));
  return { deletedAt: t, identity: true, devices: devices.length, conversations: kept.length, workspaces: orgs.length };
}

/** Everything held only for this identity: queues both ways, history, blocks, approvals, uploads, reminders. */
function deletePersonalRecords(db: DB, userId: string): void {
  for (const sql of [
    "DELETE FROM sessions WHERE user_id = ?",
    // Messages and receipts the account sent that haven't been delivered: nobody can verify them once it's gone.
    "DELETE FROM delivery_queue WHERE sender_user_id = ?",
    "DELETE FROM submitted_messages WHERE sender_user_id = ?",
    "DELETE FROM mention_all_events WHERE user_id = ?",
    // A link-a-device approval still holds the sealed identity key for the new device.
    "DELETE FROM pairing_requests WHERE approved_user_id = ?",
    "DELETE FROM history_items WHERE user_id = ?",
    "DELETE FROM history_accounts WHERE user_id = ?",
    // Unfinished direct uploads: their objects or parts are removed by the retention job.
    "INSERT OR IGNORE INTO abandoned_uploads (id, multipart_id, attempts, next_at) SELECT id, multipart_id, 0, 0 FROM attachment_uploads WHERE user_id = ?",
    "DELETE FROM attachment_uploads WHERE user_id = ?",
    "DELETE FROM reminders WHERE user_id = ?",
    "DELETE FROM call_hands WHERE user_id = ?",
  ]) {
    db.prepare(sql).run(userId);
  }
  db.prepare("DELETE FROM user_blocks WHERE user_id = ? OR blocked_user_id = ?").run(userId, userId);
}

/** Leaves every group and channel still held (workspace channels went with the workspaces). Direct conversations stay. */
function leaveConversations(db: DB, userId: string, memberships: { id: string; type: string }[]): void {
  db.prepare("DELETE FROM conversation_members WHERE user_id = ? AND conversation_id IN (SELECT id FROM conversations WHERE type != 'DM')").run(userId);
  for (const m of memberships) if (m.type === "GROUP") keepGroupAdmin(db, m.id);
}

/** After someone leaves a group: if no admin is left, its longest-standing member becomes one. */
function keepGroupAdmin(db: DB, convId: string): void {
  const admins = (db.prepare("SELECT COUNT(*) n FROM conversation_members WHERE conversation_id = ? AND role = 'ADMIN'").get(convId) as { n: number }).n;
  if (admins > 0) return;
  db.prepare(
    "UPDATE conversation_members SET role = 'ADMIN' WHERE conversation_id = ? AND user_id = (SELECT user_id FROM conversation_members WHERE conversation_id = ? ORDER BY joined_at LIMIT 1)",
  ).run(convId, convId);
}

/** Clears every personal field, frees the username, unnames the devices, and queues the photo's removal. */
function anonymize(ctx: AppContext, userId: string, photoRev: number | null, t: number): void {
  // "-" is never allowed in a username, so the placeholder can't collide with a real one; the old name is free again.
  const placeholder = `deleted-${crypto.randomBytes(8).toString("hex")}`;
  ctx.db
    .prepare(
      `UPDATE users SET username = ?, display_name = ?, status_text = NULL, status_emoji = NULL, status_expires_at = NULL, notifications_paused_until = NULL, avatar_color = 0, first_name = NULL, last_name = NULL,
         birthday = NULL, phone = NULL, photo_rev = NULL, privacy = NULL, username_changed_at = NULL, limited_until = NULL,
         disabled_at = COALESCE(disabled_at, ?), deleted_at = COALESCE(deleted_at, ?), updated_at = ? WHERE id = ?`,
    )
    .run(placeholder, DELETED_DISPLAY_NAME, t, t, t, userId);
  ctx.db.prepare("UPDATE devices SET name = '' WHERE user_id = ?").run(userId);
  if (photoRev) queueBlobDelete(ctx, "profile-photos", photoBlobId(userId, photoRev));
  queueBlobDelete(ctx, "profile-photos", userId); // a photo saved before revisions existed
}

/** Whether anyone who still has an account is in the conversation. */
function hasLiveMember(db: DB, convId: string): boolean {
  return !!db
    .prepare("SELECT 1 FROM conversation_members m JOIN users u ON u.id = m.user_id WHERE m.conversation_id = ? AND u.deleted_at IS NULL LIMIT 1")
    .get(convId);
}

/** Removes a group or direct conversation nobody is left in, with its files and queued items. Returns its live rooms. */
function removeConversation(ctx: AppContext, convId: string): string[] {
  const { db } = ctx;
  const rooms = ids(db, "SELECT room_name AS id FROM calls WHERE conversation_id = ? AND state != 'ENDED'", convId);
  for (const f of ids(db, "SELECT id FROM attachments WHERE conversation_id = ?", convId)) queueBlobDelete(ctx, "attachments", f);
  db.prepare(
    "INSERT OR IGNORE INTO abandoned_uploads (id, multipart_id, attempts, next_at) SELECT id, multipart_id, 0, 0 FROM attachment_uploads WHERE conversation_id = ?",
  ).run(convId);
  // Rows without a foreign key to the conversation; the rest (members, attachment rows, calls) cascade.
  for (const table of ["delivery_queue", "submitted_messages", "mention_all_events"]) {
    db.prepare(`DELETE FROM ${table} WHERE conversation_id = ?`).run(convId);
  }
  db.prepare("DELETE FROM conversations WHERE id = ?").run(convId);
  return rooms;
}
