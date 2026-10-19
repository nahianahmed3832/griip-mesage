import type { AppContext } from "./context.js";
import { expireDirectUploads, processAbandonedUploads, processBlobDeletes, purgeExpiredAttachments } from "./routes/attachments.js";
import { purgeRevokedPushEndpoints } from "./push/service.js";
import { purgeHistoryTombstones } from "./routes/history.js";
import { broadcastCall, type CallRow, endCallRow, evictFromCall, loadCall, scheduleLost } from "./routes/calls.js";
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
  /** Push endpoints of revoked devices. */
  pushEndpoints: number;
  /** Orbit 2.0 account sessions, email codes and legacy tickets past their time. */
  accountRows: number;
  callsTimedOut: number;
  callEvictions: number;
  /** Call participant records of devices LiveKit no longer has (a missed "left" webhook). */
  callStaleParticipants: number;
  disabledDisconnected: number;
  callRecordsPruned: number;
  auditPruned: number;
  /** Expired sign-in flows, webhook delivery ids, channel notifications from apps and old finished jobs. */
  integrations: number;
  /** Old reminder occurrence records and reminders finished more than 30 days ago. */
  reminders: number;
}

/**
 * Enforces retention. Runs on a timer and can be invoked directly (admin CLI / tests).
 * Reads also filter on expires_at, so an item is never served after expiry even between runs.
 */
export async function runCleanup(ctx: AppContext): Promise<CleanupResult> {
  const t = now();
  const { db } = ctx;
  // First, before anything waits: accounts disabled with the admin CLI (a separate process) keep receive-only sockets
  // open, because sessions are only re-checked on client frames. Close them here (T3.3).
  let disabledDisconnected = 0;
  const online = ctx.hub.connectedUserIds();
  for (let i = 0; i < online.length; i += 500) {
    const chunk = online.slice(i, i + 500);
    const disabled = db
      .prepare(`SELECT id FROM users WHERE disabled_at IS NOT NULL AND id IN (${chunk.map(() => "?").join(",")})`)
      .all(...chunk) as { id: string }[];
    for (const u of disabled) {
      ctx.hub.disconnectUser(u.id, "account disabled");
      disabledDisconnected++;
    }
  }
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
    pushEndpoints: purgeRevokedPushEndpoints(db),
    // An unreachable account store (MongoDB) mustn't stop the rest of retention.
    accountRows: await ctx.accounts.purgeExpired().catch((e) => {
      ctx.log.error({ err: e }, "account retention failed");
      return 0;
    }),
    reminders: (() => {
      try {
        return ctx.reminders.purge();
      } catch (e) {
        ctx.log.error({ err: e }, "reminder retention failed");
        return 0;
      }
    })(),
    callsTimedOut: 0,
    callEvictions: 0,
    callStaleParticipants: 0,
    disabledDisconnected,
    callRecordsPruned: 0,
    auditPruned: 0,
    integrations: 0,
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
  r.integrations = await ctx.integrations.purge(t);
  r.blobsDeleted = await processBlobDeletes(ctx);
  r.uploadsCleaned = await processAbandonedUploads(ctx);
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
    } else if (fromLiveKit) {
      r.callStaleParticipants += await reconcileParticipants(ctx, c, type === "DM", t);
    }
  }
  return r;
}

/**
 * A live call against LiveKit's participant list (UI-003): a device the server still counts as connected but LiveKit no
 * longer has (its "left" webhook was lost, or the server restarted meanwhile) is marked as left once it's been gone
 * longer than the reconnect grace; a 1:1 call left with one person gets the same grace timer a "left" webhook starts,
 * so it ends as "lost" instead of staying open. Returns the records corrected.
 */
async function reconcileParticipants(ctx: AppContext, c: CallRow, isDm: boolean, t: number): Promise<number> {
  const identities = new Set((ctx.livekit && (await ctx.livekit.participantIdentities(c.room_name))) ?? []);
  const rows = ctx.db
    .prepare("SELECT user_id, device_id, joined_at FROM call_participants WHERE call_id = ? AND left_at IS NULL")
    .all(c.id) as { user_id: string; device_id: string; joined_at: number }[];
  let stale = 0;
  for (const p of rows) {
    if (identities.has(`${p.user_id}.${p.device_id}`) || t - p.joined_at < ctx.config.callPeerGraceMs) continue;
    ctx.db.prepare("UPDATE call_participants SET left_at = ? WHERE call_id = ? AND device_id = ? AND left_at IS NULL").run(t, c.id, p.device_id);
    stale++;
  }
  if (stale > 0) broadcastCall(ctx, loadCall(ctx, c.id)!, "updated");
  const users = new Set([...identities].map((i) => i.split(".")[0]));
  if (isDm && c.state === "ACTIVE" && users.size < 2) scheduleLost(ctx, c);
  return stale;
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
