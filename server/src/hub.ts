import type { WebSocket } from "ws";
import type { DB } from "./db.js";
import { now } from "./util.js";

export interface QueueItemRow {
  id: number;
  recipient_device_id: string;
  kind: "envelope" | "receipt";
  conversation_id: string;
  message_id: string;
  sender_user_id: string;
  sender_device_id: string;
  payload: string;
  created_at: number;
  expires_at: number;
}

export function queueItemToWire(r: QueueItemRow) {
  return {
    id: r.id,
    kind: r.kind,
    conversationId: r.conversation_id,
    messageId: r.message_id,
    senderUserId: r.sender_user_id,
    senderDeviceId: r.sender_device_id,
    createdAt: r.created_at,
    expiresAt: r.expires_at,
    payload: JSON.parse(r.payload),
  };
}

export interface Conn {
  ws: WebSocket;
  userId: string;
  deviceId: string;
  /**
   * True until this socket's backlog has been sent completely. While flushing, live items aren't
   * pushed: they're already queued and go out in the next backlog batch, in queue order, so a new
   * message can't overtake (or duplicate) the backlog (P6).
   */
  flushing: boolean;
  /** When this socket opened: when it closes, items queued since then may still be unacknowledged (push wake-ups). */
  openedAt: number;
}

/**
 * Tracks live WebSocket connections (one per device session) and pushes realtime events.
 * Pure in-memory: a single API instance is assumed for the internal beta (docs/ARCHITECTURE.md §7).
 */
export class Hub {
  private byDevice = new Map<string, Set<Conn>>();
  private byUser = new Map<string, Set<string>>();
  private lastSeen = new Map<string, number>();
  onPresenceChange: (userId: string, online: boolean) => void = () => {};
  /** An envelope was queued for a device with no open socket (src/push sends it a wake-up). */
  onQueuedOffline: (deviceId: string) => void = () => {};
  /** A device's last socket closed; [openedAt] is when that socket opened. */
  onDeviceOffline: (deviceId: string, openedAt: number) => void = () => {};

  constructor(private db: DB) {}

  add(ws: WebSocket, userId: string, deviceId: string): Conn {
    const conn: Conn = { ws, userId, deviceId, flushing: true, openedAt: now() }; // its backlog flush starts right after "hello"
    let set = this.byDevice.get(deviceId);
    if (!set) this.byDevice.set(deviceId, (set = new Set()));
    set.add(conn);
    let devs = this.byUser.get(userId);
    const wasOnline = !!devs && devs.size > 0;
    if (!devs) this.byUser.set(userId, (devs = new Set()));
    devs.add(deviceId);
    if (!wasOnline) this.onPresenceChange(userId, true);
    return conn;
  }

  remove(conn: Conn): void {
    const set = this.byDevice.get(conn.deviceId);
    if (set) {
      set.delete(conn);
      if (set.size === 0) {
        this.byDevice.delete(conn.deviceId);
        const devs = this.byUser.get(conn.userId);
        devs?.delete(conn.deviceId);
        if (devs && devs.size === 0) {
          this.byUser.delete(conn.userId);
          this.lastSeen.set(conn.userId, now());
          this.onPresenceChange(conn.userId, false);
        }
        this.onDeviceOffline(conn.deviceId, conn.openedAt);
      }
    }
  }

  isUserOnline(userId: string): boolean {
    return (this.byUser.get(userId)?.size ?? 0) > 0;
  }

  isDeviceOnline(deviceId: string): boolean {
    return (this.byDevice.get(deviceId)?.size ?? 0) > 0;
  }

  lastSeenAt(userId: string): number | null {
    return this.lastSeen.get(userId) ?? null;
  }

  onlineDeviceCount(): number {
    return this.byDevice.size;
  }

  sendToDevice(deviceId: string, msg: unknown): boolean {
    const set = this.byDevice.get(deviceId);
    if (!set || set.size === 0) return false;
    const data = JSON.stringify(msg);
    for (const c of set) if (c.ws.readyState === 1) c.ws.send(data);
    return true;
  }

  sendToUser(userId: string, msg: unknown, exceptDeviceId?: string): void {
    const devs = this.byUser.get(userId);
    if (!devs) return;
    for (const d of devs) if (d !== exceptDeviceId) this.sendToDevice(d, msg);
  }

  sendToUsers(userIds: Iterable<string>, msg: unknown, exceptDeviceId?: string): void {
    for (const u of userIds) this.sendToUser(u, msg, exceptDeviceId);
  }

  /** Users with at least one open socket. */
  connectedUserIds(): string[] {
    return [...this.byUser.keys()];
  }

  disconnectUser(userId: string, reason: string): void {
    for (const d of [...(this.byUser.get(userId) ?? [])]) this.disconnectDevice(d, reason);
  }

  disconnectDevice(deviceId: string, reason: string): void {
    const set = this.byDevice.get(deviceId);
    if (!set) return;
    for (const c of [...set]) {
      try {
        c.ws.close(4001, reason);
      } catch {
        /* ignore */
      }
    }
  }

  /**
   * Push freshly queued items to a device's sockets whose backlog has drained (fire and forget). A device with no
   * socket is offered a wake-up instead, for envelopes only: delivery receipts wait until the app next connects.
   */
  pushQueued(deviceId: string, rows: QueueItemRow[]): void {
    if (rows.length === 0) return;
    if (!this.isDeviceOnline(deviceId)) {
      if (rows.some((r) => r.kind === "envelope")) this.onQueuedOffline(deviceId);
      return;
    }
    const data = JSON.stringify({ t: "queue", items: rows.map(queueItemToWire), more: false });
    for (const c of this.byDevice.get(deviceId) ?? []) if (!c.flushing && c.ws.readyState === 1) c.ws.send(data);
  }

  /** Send the next backlog batch for a device. Called on connect and after each ack. */
  flushBacklog(conn: Conn, batchSize = 100): void {
    const t = now();
    const rows = this.db
      .prepare(
        `SELECT * FROM delivery_queue WHERE recipient_device_id = ? AND expires_at > ? ORDER BY id LIMIT ?`,
      )
      .all(conn.deviceId, t, batchSize + 1) as QueueItemRow[];
    const more = rows.length > batchSize;
    const batch = rows.slice(0, batchSize);
    if (conn.ws.readyState !== 1) return;
    conn.ws.send(JSON.stringify({ t: "queue", items: batch.map(queueItemToWire), more, backlog: true }));
    // Query, send and this switch happen in one synchronous step, so an item queued afterwards is
    // pushed live and one queued before is in this batch: nothing is skipped or sent twice.
    conn.flushing = more;
    if (!more) conn.ws.send(JSON.stringify({ t: "queue.drained" }));
  }
}


