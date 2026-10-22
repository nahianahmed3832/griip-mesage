import { Binary, type Collection } from "mongodb";
import type { HistoryAccount, HistoryItem, HistoryLimits, HistoryPut, HistoryStore, HistoryWrite } from "./store.js";
import type { MeldiqMongo } from "../../../infrastructure/database/mongo/client.js";

/** history_items: the SQLite row (same field names, docs/MONGODB.md §3) keyed by `{ user_id, tag }`. */
interface ItemDoc {
  _id: { user_id: string; tag: string };
  user_id: string;
  tag: string;
  conv_tag: string;
  seq: number;
  ts: number;
  body: Binary | null;
  size: number;
  updated_at: number;
}

/** history_accounts: keyed by user id. */
interface AccountDoc extends HistoryAccount {
  _id: string;
  user_id: string;
}

/** How many deletions or orphan checks go in one request. */
const BATCH = 500;

/**
 * Encrypted message history in MongoDB (MONGODB_DOMAINS includes "history"). A write reads the account's totals and
 * the items it replaces, checks the limits and writes everything in one transaction (Atlas and any replica set). Writes
 * for one account are also queued in this process, so they never interleave even on a standalone development server
 * without transactions.
 */
export class MongoHistoryStore implements HistoryStore {
  readonly backend = "mongodb" as const;
  private readonly itemsCol: Collection<ItemDoc>;
  private readonly accountsCol: Collection<AccountDoc>;
  /** The tail of each account's write queue. */
  private readonly queues = new Map<string, Promise<unknown>>();

  constructor(private readonly mongo: MeldiqMongo) {
    this.itemsCol = mongo.db.collection<ItemDoc>("history_items");
    this.accountsCol = mongo.db.collection<AccountDoc>("history_accounts");
  }

  /** Runs [fn] after every earlier queued call for [userId] has finished. */
  private queued<T>(userId: string, fn: () => Promise<T>): Promise<T> {
    const prev = this.queues.get(userId) ?? Promise.resolve();
    const run = prev.then(fn, fn);
    const tail = run.catch(() => undefined);
    this.queues.set(userId, tail);
    void tail.then(() => {
      if (this.queues.get(userId) === tail) this.queues.delete(userId);
    });
    return run;
  }

  async account(userId: string): Promise<HistoryAccount | null> {
    const a = await this.accountsCol.findOne({ _id: userId }, { session: this.mongo.session() });
    return a ? { next_seq: a.next_seq, items: a.items, bytes: a.bytes } : null;
  }

  put(userId: string, plan: HistoryWrite[], limits: HistoryLimits, at: number): Promise<HistoryPut> {
    return this.queued(userId, () =>
      this.mongo.transaction(async (): Promise<HistoryPut> => {
        const session = this.mongo.session();
        const a = (await this.account(userId)) ?? { next_seq: 1, items: 0, bytes: 0 };
        let items = a.items;
        let bytes = a.bytes;
        let seq = a.next_seq;
        const old = await this.itemsCol
          .find(
            { _id: { $in: plan.map((p) => ({ user_id: userId, tag: p.tag })) } },
            // Only whether each still has a body, and its size: never the ciphertext itself.
            { projection: { tag: 1, size: 1, live: { $ne: ["$body", null] } }, session },
          )
          .toArray();
        const live = new Map(old.map((d) => [d.tag, d as unknown as { size: number; live: boolean }]));
        for (const i of plan) {
          const o = live.get(i.tag);
          if (o?.live) {
            items--;
            bytes -= o.size;
          }
          if (i.body) {
            items++;
            bytes += i.body.length;
          }
        }
        if (items > limits.maxItems || bytes > limits.maxBytes) return "account";
        if (limits.maxTotalBytes > 0 && bytes > a.bytes) {
          const [sum] = await this.accountsCol.aggregate<{ n: number }>([{ $group: { _id: null, n: { $sum: "$bytes" } } }], { session }).toArray();
          if ((sum?.n ?? 0) - a.bytes + bytes > limits.maxTotalBytes) return "server";
        }
        await this.itemsCol.bulkWrite(
          plan.map((p) => {
            const doc: ItemDoc = {
              _id: { user_id: userId, tag: p.tag },
              user_id: userId,
              tag: p.tag,
              conv_tag: p.conv,
              seq: seq++,
              ts: p.ts,
              body: p.body ? new Binary(p.body) : null,
              size: p.body?.length ?? 0,
              updated_at: at,
            };
            return { replaceOne: { filter: { _id: doc._id }, replacement: doc, upsert: true } };
          }),
          { ordered: true, session },
        );
        await this.accountsCol.replaceOne({ _id: userId }, { user_id: userId, next_seq: seq, items, bytes }, { upsert: true, session });
        return { seq: seq - 1, items, bytes };
      }),
    );
  }

  async items(userId: string, after: number, limit: number): Promise<HistoryItem[]> {
    const docs = await this.itemsCol
      .find({ user_id: userId, seq: { $gt: after } }, { session: this.mongo.session() })
      .sort({ seq: 1 })
      .limit(limit)
      .toArray();
    return docs.map((d) => ({ tag: d.tag, conv_tag: d.conv_tag, seq: d.seq, ts: d.ts, body: d.body ? Buffer.from(d.body.buffer) : null }));
  }

  deleteForUser(userId: string): Promise<number> {
    return this.queued(userId, () =>
      this.mongo.transaction(async () => {
        const session = this.mongo.session();
        const n = (await this.itemsCol.deleteMany({ user_id: userId }, { session })).deletedCount;
        await this.accountsCol.deleteOne({ _id: userId }, { session });
        return n;
      }),
    );
  }

  async purgeTombstones(before: number): Promise<number> {
    return (await this.itemsCol.deleteMany({ body: null, updated_at: { $lte: before } })).deletedCount;
  }

  async orphanUserIds(exists: (userIds: string[]) => Set<string>): Promise<string[]> {
    const orphans: string[] = [];
    let batch: string[] = [];
    const check = () => {
      const known = exists(batch);
      orphans.push(...batch.filter((id) => !known.has(id)));
      batch = [];
    };
    for await (const a of this.accountsCol.find({}, { projection: { _id: 1 } })) {
      batch.push(a._id);
      if (batch.length >= BATCH) check();
    }
    if (batch.length) check();
    return orphans;
  }
}
