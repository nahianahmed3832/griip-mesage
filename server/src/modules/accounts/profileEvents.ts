import type { AppContext } from "../../app/context.js";
import { conversationPeers } from "../messaging/index.js";

/**
 * Live profile and settings changes (the owner, 2026-10-23: a change made on one device showed elsewhere only after a
 * reload). The frame names whose profile changed; clients fetch it again, so privacy is applied by that fetch.
 */

/** [userId]'s profile changed: their own devices and the people who see them hear of it at once. */
export function announceProfileChange(ctx: AppContext, userId: string): void {
  ctx.hub.sendToUsers(new Set([userId, ...conversationPeers(ctx, userId)]), { t: "user.updated", userId });
}

/** One of [userId]'s private settings changed: only their own devices hear of it (a blocked person is never told). */
export function announceOwnSettingsChange(ctx: AppContext, userId: string): void {
  ctx.hub.sendToUser(userId, { t: "user.updated", userId });
}
