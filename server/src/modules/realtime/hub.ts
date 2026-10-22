import type { WebSocket } from "ws";
import type { QueueItemRow, RelayStore } from "../messaging/index.js";
import type { QueueItemWire, ServerEvent } from "../../shared/contracts/realtime.js";
import { now } from "../../shared/utilities/util.js";

export type { QueueItemRow };

export function queueItemToWire(r: QueueItemRow): QueueItemWire {
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
   * True until this socket's backlog has been sent completely. While flushing, live items aren't sent: they wait in
   * [held] and go out after the last backlog batch, minus any a batch already carried, so a new message can't overtake
   * (or duplicate) the backlog (P6), whether the store answers synchronously (SQLite) or not (MongoDB).
   */
  flushing: boolean;
  /** Live items queued while flushing, in arrival order (bounded: see HELD_LIMIT). */
  held: QueueItemRow[];
  /** More than HELD_LIMIT arrived while flushing: the last batch is followed by one more instead. */
  heldOverflow: boolean;
  /** Ids sent in backlog batches since this socket opened (to drop held items a batch already carried). */
  sentIds: Set<number>;
  /** A backlog query is running; a second request waits for it (an ack can arrive while a batch is in flight). */
  flushRunning: boolean;
  flushAgain: boolean;
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
  /** Batch B6b: false for someone who appears offline, so leaving doesn't move their "last seen" time. */
  recordsLastSeen: (userId: string) => boolean = () => true;
  /** A device's last socket closed; [openedAt] is when that socket opened. */
  onDeviceOffline: (deviceId: string, openedAt: number) => void = () => {};
  /** Sees every frame before it is sent; the test harness checks them against the contract (test/support/contracts.ts). */
  observe: ((e: ServerEvent) => void) | null = null;

  /** The text of one WebSocket frame: only contract events can be sent (src/shared/contracts/realtime.ts). */
  encode(e: ServerEvent): string {
    this.observe?.(e);
    return JSON.stringify(e);
  }

  /** [maxSocketsPerDevice]: a device opening more connections than this closes its oldest (docs/ABUSE_CONTROLS.md). */
  constructor(
    private relay: RelayStore,
    private maxSocketsPerDevice = 4,
  ) {}

  add(ws: WebSocket, userId: string, deviceId: string): Conn {
    // Its backlog flush starts right after "hello".
    const conn: Conn = { ws, userId, deviceId, flushing: true, openedAt: now(), held: [], heldOverflow: false, sentIds: new Set(), flushRunning: false, flushAgain: false };
    let set = this.byDevice.get(deviceId);
    if (!set) this.byDevice.set(deviceId, (set = new Set()));
    // A reconnecting phone often still has a half-open old connection: the newest one wins, nothing is refused.
    for (const old of set) {
      if (set.size < this.maxSocketsPerDevice) break;
      set.delete(old);
      try {
        old.ws.close(4409, "replaced by a newer connection");
      } catch {
        /* already closed */
      }
    }
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
          if (this.recordsLastSeen(conn.userId)) this.lastSeen.set(conn.userId, now());
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

  sendToDevice(deviceId: string, msg: ServerEvent): boolean {
    const set = this.byDevice.get(deviceId);
    if (!set || set.size === 0) return false;
    const data = this.encode(msg);
    for (const c of set) if (c.ws.readyState === 1) c.ws.send(data);
    return true;
  }

  sendToUser(userId: string, msg: ServerEvent, exceptDeviceId?: string): void {
    const devs = this.byUser.get(userId);
    if (!devs) return;
    for (const d of devs) if (d !== exceptDeviceId) this.sendToDevice(d, msg);
  }

  sendToUsers(userIds: Iterable<string>, msg: ServerEvent, exceptDeviceId?: string): void {
    for (const u of userIds) this.sendToUser(u, msg, exceptDeviceId);
  }

  /** The tail of each device's work queue (see [onDevice]). */
  private readonly deviceWork = new Map<string, Promise<void>>();

  /**
   * Runs [fn] after everything queued earlier for [deviceId]: its sockets' frames, its acknowledgements (also over
   * REST) and a new connection's first backlog. So acknowledgements a phone sent just before reconnecting are applied
   * before its next backlog is read, whether the store answers at once (SQLite) or not (MongoDB).
   */
  onDevice(deviceId: string, fn: () => Promise<void>): Promise<void> {
    const prev = this.deviceWork.get(deviceId) ?? Promise.resolve();
    const run = prev.then(fn);
    const tail = run.catch(() => undefined);
    this.deviceWork.set(deviceId, tail);
    void tail.then(() => {
      if (this.deviceWork.get(deviceId) === tail) this.deviceWork.delete(deviceId);
    });
    return run;
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
  /** [wake]: false keeps an offline device asleep (batch B6: a message request sends no push). */
  pushQueued(deviceId: string, rows: QueueItemRow[], opts: { wake?: boolean } = {}): void {
    if (rows.length === 0) return;
    if (!this.isDeviceOnline(deviceId)) {
      if (opts.wake !== false && rows.some((r) => r.kind === "envelope")) this.onQueuedOffline(deviceId);
      return;
    }
    const data = this.encode({ t: "queue", items: rows.map(queueItemToWire), more: false });
    for (const c of this.byDevice.get(deviceId) ?? []) {
      if (c.ws.readyState !== 1) continue;
      if (!c.flushing) c.ws.send(data);
      else if (c.held.length + rows.length <= HELD_LIMIT) c.held.push(...rows);
      else c.heldOverflow = true;
    }
  }

  /**
   * Send the next backlog batch for a device. Called on connect and after each acknowledgement of a batch. After the
   * last batch, the items held back while flushing go out (those a batch didn't already carry), then queue.drained.
   */
  async flushBacklog(conn: Conn, batchSize = 100): Promise<void> {
    if (conn.flushRunning) {
      conn.flushAgain = true;
      return;
    }
    conn.flushRunning = true;
    try {
      do {
        conn.flushAgain = false;
        await this.sendBatch(conn, batchSize);
      } while (conn.flushAgain && conn.flushing);
    } finally {
      conn.flushRunning = false;
    }
  }

  private async sendBatch(conn: Conn, batchSize: number): Promise<void> {
    const rows = await this.relay.backlog(conn.deviceId, now(), batchSize + 1);
    if (conn.ws.readyState !== 1) return;
    const more = rows.length > batchSize || conn.heldOverflow;
    const batch = rows.slice(0, batchSize);
    for (const r of batch) conn.sentIds.add(r.id);
    if (conn.heldOverflow && rows.length <= batchSize) {
      // Too much arrived while flushing to hold: it's all in the queue, so one more batch picks it up.
      conn.heldOverflow = false;
      conn.held = [];
    }
    conn.ws.send(this.encode({ t: "queue", items: batch.map(queueItemToWire), more, backlog: true }));
    if (more) return;
    // Everything from here to the switch below is synchronous, so a live item can't slip between the held ones and
    // the next live push.
    const late = conn.held.filter((r) => !conn.sentIds.has(r.id)).sort((a, b) => a.id - b.id);
    conn.held = [];
    conn.sentIds.clear();
    if (late.length) conn.ws.send(this.encode({ t: "queue", items: late.map(queueItemToWire), more: false }));
    conn.flushing = false;
    conn.ws.send(this.encode({ t: "queue.drained" }));
  }
}

/** Live items held per socket while its backlog is sent; beyond this, they're fetched as one more batch instead. */
const HELD_LIMIT = 1000;
