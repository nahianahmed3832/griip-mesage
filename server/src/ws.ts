import type { FastifyInstance } from "fastify";
import { type AppContext, authenticateToken } from "./context.js";
import { acknowledge } from "./routes/messages.js";
import { now } from "./util.js";

/**
 * Realtime gateway. Auth uses the same bearer session token as REST, sent in the
 * Authorization header of the upgrade request (never in the URL, so it can't leak into logs).
 *
 * Client → server: {t:"ack", ids}, {t:"typing", c, on}, {t:"ping"}
 * Server → client: hello, queue, queue.drained, typing, presence, call, conv.updated,
 *                  org.updated, devices.changed, pong
 */
export default async function wsRoutes(app: FastifyInstance, ctx: AppContext) {
  const { db, hub, authz } = ctx;
  const lastTyping = new Map<string, number>();

  hub.onPresenceChange = (userId, online) => {
    if (!db.open) return; // server shutting down
    // Only people who share a DM or group with this user learn about presence changes.
    const peers = db
      .prepare(
        `SELECT DISTINCT m2.user_id FROM conversation_members m1
         JOIN conversations c ON c.id = m1.conversation_id AND c.type IN ('DM','GROUP')
         JOIN conversation_members m2 ON m2.conversation_id = m1.conversation_id
         WHERE m1.user_id = ? AND m2.user_id != ?`,
      )
      .all(userId, userId) as { user_id: string }[];
    hub.sendToUsers(
      peers.map((p) => p.user_id),
      { t: "presence", userId, online, lastSeenAt: online ? null : now() },
    );
  };

  app.get("/v1/ws", { websocket: true }, (socket, req) => {
    const token = req.headers.authorization?.startsWith("Bearer ") ? req.headers.authorization.slice(7) : undefined;
    const auth = authenticateToken(ctx, token);
    if (!auth) {
      socket.close(4401, "unauthorized");
      return;
    }
    const conn = hub.add(socket, auth.userId, auth.deviceId);
    db.prepare("UPDATE devices SET last_seen_at = ? WHERE id = ?").run(now(), auth.deviceId);
    socket.send(JSON.stringify({ t: "hello", userId: auth.userId, deviceId: auth.deviceId, serverTime: now() }));
    hub.flushBacklog(conn);

    let alive = true;
    const heartbeat = setInterval(() => {
      if (!alive) {
        socket.terminate();
        return;
      }
      alive = false;
      try {
        socket.ping();
      } catch {
        /* closed */
      }
    }, 30_000);
    socket.on("pong", () => (alive = true));

    socket.on("message", (raw: Buffer) => {
      alive = true;
      if (raw.length > 64 * 1024) return;
      let msg: any;
      try {
        msg = JSON.parse(raw.toString("utf8"));
      } catch {
        return;
      }
      // Re-check the session on every frame so revoked devices are cut off immediately.
      if (!authenticateToken(ctx, token)) {
        socket.close(4401, "session expired");
        return;
      }
      switch (msg?.t) {
        case "ping":
          socket.send(JSON.stringify({ t: "pong", serverTime: now() }));
          break;
        case "ack": {
          const ids = Array.isArray(msg.ids) ? msg.ids.filter((x: unknown) => Number.isInteger(x)).slice(0, 500) : [];
          if (ids.length) acknowledge(ctx, auth.userId, auth.deviceId, ids);
          // Only while this socket's backlog is still being sent: clients also acknowledge the final (empty)
          // batch with backlog:true, and answering that with another flush looped forever and re-sent
          // unacknowledged live items (P6 duplicates).
          if (msg.backlog && conn.flushing) hub.flushBacklog(conn);
          break;
        }
        case "typing": {
          const convId = typeof msg.c === "string" ? msg.c : "";
          const key = `${auth.deviceId}:${convId}`;
          const last = lastTyping.get(key) ?? 0;
          if (msg.on && now() - last < 1500) break; // rate-limit
          lastTyping.set(key, now());
          const conv = authz.conversation(convId);
          if (!conv || !authz.isMember(conv.id, auth.userId)) break;
          const members = (db.prepare("SELECT user_id FROM conversation_members WHERE conversation_id = ?").all(conv.id) as { user_id: string }[])
            .map((r) => r.user_id)
            .filter((u) => u !== auth.userId);
          if (members.length > 200) break; // large channels: skip typing fan-out
          hub.sendToUsers(members, { t: "typing", c: conv.id, u: auth.userId, on: !!msg.on });
          break;
        }
        default:
          break;
      }
    });

    socket.on("close", () => {
      clearInterval(heartbeat);
      hub.remove(conn);
      if (db.open) db.prepare("UPDATE devices SET last_seen_at = ? WHERE id = ?").run(now(), auth.deviceId);
    });
    socket.on("error", () => {
      /* close handler cleans up */
    });
  });
}
