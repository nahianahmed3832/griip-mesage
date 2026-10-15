import type { AppContext } from "./context.js";
import { purgeExpiredAttachments } from "./routes/attachments.js";
import { broadcastCall, type CallRow, endCallRow, loadCall } from "./routes/calls.js";
import { now } from "./util.js";

export interface CleanupResult {
  queue: number;
  submitted: number;
  challenges: number;
  sessions: number;
  pairing: number;
  attachments: number;
  callsTimedOut: number;
}

/**
 * Enforces retention. Runs on a timer and can be invoked directly (admin CLI / tests).
 * Reads also filter on expires_at, so an item is never served after expiry even between runs.
 */
export async function runCleanup(ctx: AppContext): Promise<CleanupResult> {
  const t = now();
  const { db } = ctx;
  const r: CleanupResult = {
    queue: db.prepare("DELETE FROM delivery_queue WHERE expires_at <= ?").run(t).changes,
    submitted: db.prepare("DELETE FROM submitted_messages WHERE expires_at <= ?").run(t).changes,
    challenges: db.prepare("DELETE FROM auth_challenges WHERE expires_at <= ?").run(t).changes,
    sessions: db.prepare("DELETE FROM sessions WHERE expires_at <= ?").run(t).changes,
    pairing: db.prepare("DELETE FROM pairing_requests WHERE expires_at <= ?").run(t).changes,
    attachments: purgeExpiredAttachments(ctx),
    callsTimedOut: 0,
  };
  // Reconcile live calls with LiveKit (authoritative participant list) once they are past the
  // ring window: unanswered 1:1 calls become "missed", empty rooms are closed.
  const live = db
    .prepare("SELECT * FROM calls WHERE state != 'ENDED' AND created_at <= ?")
    .all(t - ctx.config.ringTimeoutSeconds * 1000) as CallRow[];
  for (const c of live) {
    const fromLiveKit = ctx.livekit ? await ctx.livekit.participantUsers(c.room_name) : null;
    const users =
      fromLiveKit ??
      new Set(
        (db.prepare("SELECT DISTINCT user_id FROM call_participants WHERE call_id = ? AND left_at IS NULL").all(c.id) as { user_id: string }[]).map(
          (p) => p.user_id,
        ),
      );
    const type = (db.prepare("SELECT type FROM conversations WHERE id = ?").get(c.conversation_id) as { type: string } | undefined)?.type;
    const needed = type === "DM" ? 2 : 1;
    if (c.state === "RINGING") {
      if (users.size < needed) {
        await endCallRow(ctx, c, "missed");
        r.callsTimedOut++;
      } else {
        db.prepare("UPDATE calls SET state = 'ACTIVE', answered_at = COALESCE(answered_at, ?) WHERE id = ?").run(t, c.id);
        broadcastCall(ctx, loadCall(ctx, c.id)!, "updated");
      }
    } else if (users.size === 0) {
      await endCallRow(ctx, c, "empty");
    }
  }
  return r;
}

export function startJobs(ctx: AppContext): () => void {
  const timer = setInterval(() => {
    runCleanup(ctx)
      .then((r) => {
        const total = Object.values(r).reduce((a, b) => a + b, 0);
        if (total > 0) ctx.log.info({ cleanup: r }, "retention cleanup");
      })
      .catch((e) => ctx.log.error({ err: e }, "cleanup failed"));
  }, ctx.config.cleanupIntervalSeconds * 1000);
  timer.unref();
  return () => clearInterval(timer);
}
