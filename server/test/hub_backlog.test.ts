import { describe, expect, it } from "vitest";
import type { WebSocket } from "ws";
import type { QueueItemRow, RelayStore } from "../src/modules/messaging/index.js";
import { Hub } from "../src/modules/realtime/hub.js";

/**
 * The backlog flush with a store that answers asynchronously (MongoDB): items queued while a backlog is being sent
 * reach the device once, after the backlog, in id order (P6), however the store's answers interleave with live pushes.
 */

const row = (id: number): QueueItemRow => ({
  id,
  recipient_device_id: "d1",
  kind: "envelope",
  conversation_id: "c1",
  message_id: `m${id}`,
  sender_user_id: "u2",
  sender_device_id: "d2",
  payload: "{}",
  created_at: 1,
  expires_at: Number.MAX_SAFE_INTEGER,
});

/** A queue whose backlog query resolves only when the test releases it. */
function slowRelay(queue: QueueItemRow[]) {
  const waiting: (() => void)[] = [];
  const relay = {
    backend: "mongodb",
    backlog: (_d: string, _t: number, limit: number) =>
      new Promise<QueueItemRow[]>((resolve) => waiting.push(() => resolve(queue.slice(0, limit)))),
  } as unknown as RelayStore;
  return { relay, release: () => waiting.shift()?.(), pending: () => waiting.length };
}

function socket() {
  const frames: Record<string, unknown>[] = [];
  const ws = { readyState: 1, send: (d: string) => frames.push(JSON.parse(d)), close: () => {} } as unknown as WebSocket;
  const ids = () => frames.filter((f) => f.t === "queue").flatMap((f) => (f.items as { id: number }[]).map((i) => i.id));
  return { ws, frames, ids };
}

const tick = () => new Promise((r) => setImmediate(r));

describe("hub backlog with an asynchronous store", () => {
  it("sends items that arrive during the flush after the backlog, once and in order", async () => {
    const queue = [row(1), row(2)];
    const { relay, release } = slowRelay(queue);
    const hub = new Hub(relay);
    const s = socket();
    const conn = hub.add(s.ws, "u1", "d1");
    const flushing = hub.flushBacklog(conn);
    // Two live items: one already in the queue the query will read (2), one stored after it (3).
    hub.pushQueued("d1", [row(2)]);
    queue.push(row(3));
    hub.pushQueued("d1", [row(3)]);
    expect(s.frames).toEqual([]); // nothing overtakes the backlog
    release();
    await flushing;
    expect(s.ids()).toEqual([1, 2, 3]);
    expect(s.frames.at(-1)).toEqual({ t: "queue.drained" });
    expect(conn.flushing).toBe(false);
    hub.pushQueued("d1", [row(4)]);
    expect(s.ids()).toEqual([1, 2, 3, 4]); // live again
  });

  it("asks for another batch when more arrived than it holds", async () => {
    const { relay, release } = slowRelay([row(1)]);
    const hub = new Hub(relay);
    const s = socket();
    const conn = hub.add(s.ws, "u1", "d1");
    const flushing = hub.flushBacklog(conn);
    hub.pushQueued("d1", Array.from({ length: 1001 }, (_, i) => row(100 + i)));
    release();
    await flushing;
    expect(s.frames[0]).toMatchObject({ t: "queue", more: true, backlog: true });
    expect(conn.flushing).toBe(true);
  });

  it("runs one backlog query at a time when acknowledgements overlap", async () => {
    const { relay, release, pending } = slowRelay([row(1)]);
    const hub = new Hub(relay);
    const s = socket();
    const conn = hub.add(s.ws, "u1", "d1");
    const first = hub.flushBacklog(conn);
    const second = hub.flushBacklog(conn); // waits for the first instead of querying in parallel
    await tick();
    expect(pending()).toBe(1);
    release();
    await Promise.all([first, second]);
    expect(s.frames.filter((f) => f.t === "queue.drained")).toHaveLength(1);
  });
});
