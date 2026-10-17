import type { AppContext } from "./context.js";
import { expireDirectUploads, processAbandonedUploads, processBlobDeletes, purgeExpiredAttachments } from "./routes/attachments.js";
import { purgeHistoryTombstones } from "./routes/history.js";
import { broadcastCall, type CallRow, endCallRow, evictFromCall, loadCall } from "./routes/calls.js";
import { now } from "./util.js";

export interface CleanupResult {
  queue: number;
  submitted: number;
  challenges: number;
  sessions: number;
  pairing: number;
  /** @everyone counters older than a day (only the last hour is ever consulted). */
  mentionAll: number;
  attachments: number;
  /** Stored files (disk or S3) removed after their rows were deleted. */
  blobsDeleted: number;
  /** Direct uploads that ran out of time, and abandoned uploads whose object or parts were removed from the store. */
  uploadsExpired: number;
  uploadsCleaned: number;
  /** History deletion markers older than HISTORY_TOMBSTONE_DAYS. */
  historyTombstones: number;
  /** Expired federation nonces, inbox dedupe keys and outbox items. */
  federation: number;
  callsTimedOut: number;
  callEvictions: number;
  disabledDisconnected: number;
  callRecordsPruned: number;
  auditPruned: number;
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
    mentionAll: db.prepare("DELETE FROM mention_all_events WHERE at <= ?").run(t - 86_400_000).changes,
    attachments: purgeExpiredAttachments(ctx),
    blobsDeleted: 0,
    uploadsExpired: expireDirectUploads(ctx),
    uploadsCleaned: 0,
    historyTombstones: purgeHistoryTombstones(ctx, t),
    federation: ctx.federation ? Object.values(ctx.federation.cleanup(t)).reduce((a, b) => a + b, 0) : 0,
    callsTimedOut: 0,
    callEvictions: 0,
    disabledDisconnected: 0,
    callRecordsPruned: 0,
    auditPruned: 0,
  };
  // Operational history (RETENTION.md, D16): ended calls and old audit rows don't accumulate forever.
  const callCutoff = t - ctx.config.callRecordRetentionDays * 86_400_000;
  const oldCalls = db.prepare("SELECT id FROM calls WHERE state = 'ENDED' AND COALESCE(ended_at, created_at) <= ?").all(callCutoff) as { id: string }[];
  if (oldCalls.length) {
    db.transaction(() => {
      for (const c of oldCalls) {
        db.prepare("DELETE FROM call_participants WHERE call_id = ?").run(c.id);
        db.prepare("DELETE FROM calls WHERE id = ?").run(c.id);
      }
    })();
    r.callRecordsPruned = oldCalls.length;
  }
  r.auditPruned = db.prepare("DELETE FROM audit_log WHERE at <= ?").run(t - ctx.config.auditRetentionDays * 86_400_000).changes;
  r.blobsDeleted = await processBlobDeletes(ctx);
  r.uploadsCleaned = await processAbandonedUploads(ctx);
  // Accounts disabled with the admin CLI (a separate process) keep receive-only sockets open, because
  // sessions are only re-checked on client frames. Close them here (T3.3).
  const online = ctx.hub.connectedUserIds();
  for (let i = 0; i < online.length; i += 500) {
    const chunk = online.slice(i, i + 500);
    const disabled = db
      .prepare(`SELECT id FROM users WHERE disabled_at IS NOT NULL AND id IN (${chunk.map(() => "?").join(",")})`)
      .all(...chunk) as { id: string }[];
    for (const u of disabled) {
      ctx.hub.disconnectUser(u.id, "account disabled");
      r.disabledDisconnected++;
    }
  }
  // Call membership: anyone no longer admitted (removed from the conversation, revoked device,
  // disabled account) is removed from the room. This repairs evictions missed while LiveKit was
  // unreachable, rejoins with a token issued before removal, and `admin disable` from the CLI process.
  for (const c of db.prepare("SELECT * FROM calls WHERE state != 'ENDED' AND host_server_id IS NULL").all() as CallRow[]) {
    r.callEvictions += await evictFromCall(ctx, c);
  }
  // Calls hosted by another server are that server's to time out. If it vanished mid-call, close the local mirror
  // instead of showing a call forever (no relay: the host is gone or will send its own end).
  for (const c of db
    .prepare("SELECT * FROM calls WHERE state != 'ENDED' AND host_server_id IS NOT NULL AND ((state = 'RINGING' AND created_at <= ?) OR created_at <= ?)")
    .all(t - 2 * ctx.config.ringTimeoutSeconds * 1000, t - 12 * 3_600_000) as CallRow[]) {
    db.prepare("UPDATE calls SET state = 'ENDED', ended_at = ?, end_reason = ? WHERE id = ? AND state != 'ENDED'").run(t, c.state === "RINGING" ? "missed" : "lost", c.id);
    r.callsTimedOut++;
  }
  // Reconcile live calls with LiveKit (authoritative participant list) once they are past the
  // ring window: unanswered 1:1 calls become "missed", empty rooms are closed.
  const live = db
    .prepare("SELECT * FROM calls WHERE state != 'ENDED' AND host_server_id IS NULL AND created_at <= ?")
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
  let running = false; // a slow LiveKit can make one run outlast the interval; never overlap runs
  const timer = setInterval(() => {
    if (running) return;
    running = true;
    runCleanup(ctx)
      .then((r) => {
        const total = Object.values(r).reduce((a, b) => a + b, 0);
        if (total > 0) ctx.log.info({ cleanup: r }, "retention cleanup");
      })
      .catch((e) => ctx.log.error({ err: e }, "cleanup failed"))
      .finally(() => {
        running = false;
      });
  }, ctx.config.cleanupIntervalSeconds * 1000);
  timer.unref();
  return () => clearInterval(timer);
}
