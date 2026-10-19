import type { Collection, Filter } from "mongodb";
import type { DB } from "../db.js";
import { docId, type TableSpec } from "../mongo/catalog.js";
import { isDuplicateKey, type MeldiqMongo } from "../mongo/client.js";

/**
 * A small row API that behaves the same on SQLite and MongoDB, for domains whose code is async from the start (the
 * integrations domain, docs/INTEGRATIONS.md). Documents in MongoDB are the SQLite rows themselves plus `_id` (the
 * primary key), exactly as `admin mongo-migrate` copies them, so a domain can move between the stores with the existing
 * migrate, verify and rollback tools.
 *
 * Every check-and-write is a single conditional statement (`update` with the expected values in `where`), atomic on
 * both stores, so no multi-document transaction is needed.
 */
export type Value = string | number | null;
export type Cond = Value | { lt?: number; lte?: number; gt?: number; gte?: number; in?: readonly Value[]; ne?: Value };
export type Where = Readonly<Record<string, Cond>>;
export type Row = Record<string, Value>;

export interface FindOptions {
  orderBy?: readonly (readonly [string, "asc" | "desc"])[];
  limit?: number;
}

export interface RowStore {
  readonly backend: "sqlite" | "mongodb";
  find<T extends Row>(table: string, where: Where, opts?: FindOptions): Promise<T[]>;
  first<T extends Row>(table: string, where: Where, opts?: FindOptions): Promise<T | null>;
  count(table: string, where: Where): Promise<number>;
  /** False when a unique key already exists (nothing is written). */
  insert(table: string, row: Row): Promise<boolean>;
  /** Rows changed. With the expected current values in [where], it is a compare-and-set. */
  update(table: string, where: Where, set: Row): Promise<number>;
  delete(table: string, where: Where): Promise<number>;
}

const IDENT = /^[a-z_][a-z0-9_]*$/;
function ident(name: string): string {
  // Table and column names only ever come from code; this keeps it that way.
  if (!IDENT.test(name)) throw new Error(`Bad identifier ${name}`);
  return `"${name}"`;
}

function isOps(c: Cond): c is Exclude<Cond, Value> {
  return c !== null && typeof c === "object";
}

export class SqliteRows implements RowStore {
  readonly backend = "sqlite" as const;
  constructor(private readonly db: DB) {}

  private clause(where: Where): { sql: string; args: Value[] } {
    const parts: string[] = [];
    const args: Value[] = [];
    for (const [col, c] of Object.entries(where)) {
      const k = ident(col);
      if (!isOps(c)) {
        if (c === null) parts.push(`${k} IS NULL`);
        else {
          parts.push(`${k} = ?`);
          args.push(c);
        }
        continue;
      }
      const ops: [keyof typeof c, string][] = [["lt", "<"], ["lte", "<="], ["gt", ">"], ["gte", ">="]];
      for (const [op, sym] of ops) {
        const v = c[op] as number | undefined;
        if (v !== undefined) {
          parts.push(`${k} ${sym} ?`);
          args.push(v);
        }
      }
      if (c.in !== undefined) {
        if (c.in.length === 0) parts.push("0");
        else {
          parts.push(`${k} IN (${c.in.map(() => "?").join(",")})`);
          args.push(...c.in);
        }
      }
      if (c.ne !== undefined) {
        if (c.ne === null) parts.push(`${k} IS NOT NULL`);
        else {
          parts.push(`(${k} IS NULL OR ${k} != ?)`);
          args.push(c.ne);
        }
      }
    }
    return { sql: parts.length ? `WHERE ${parts.join(" AND ")}` : "", args };
  }

  private tail(opts?: FindOptions): string {
    const order = opts?.orderBy?.length ? ` ORDER BY ${opts.orderBy.map(([c, d]) => `${ident(c)} ${d === "desc" ? "DESC" : "ASC"}`).join(", ")}` : "";
    const limit = opts?.limit !== undefined ? ` LIMIT ${Math.max(0, Math.floor(opts.limit))}` : "";
    return order + limit;
  }

  async find<T extends Row>(table: string, where: Where, opts?: FindOptions): Promise<T[]> {
    const w = this.clause(where);
    return this.db.prepare(`SELECT * FROM ${ident(table)} ${w.sql}${this.tail(opts)}`).all(...w.args) as T[];
  }

  async first<T extends Row>(table: string, where: Where, opts?: FindOptions): Promise<T | null> {
    return (await this.find<T>(table, where, { ...opts, limit: 1 }))[0] ?? null;
  }

  async count(table: string, where: Where): Promise<number> {
    const w = this.clause(where);
    return (this.db.prepare(`SELECT COUNT(*) n FROM ${ident(table)} ${w.sql}`).get(...w.args) as { n: number }).n;
  }

  async insert(table: string, row: Row): Promise<boolean> {
    const cols = Object.keys(row);
    try {
      this.db.prepare(`INSERT INTO ${ident(table)} (${cols.map(ident).join(", ")}) VALUES (${cols.map(() => "?").join(", ")})`).run(...cols.map((c) => row[c]));
      return true;
    } catch (e) {
      if ((e as { code?: string }).code === "SQLITE_CONSTRAINT_UNIQUE" || (e as { code?: string }).code === "SQLITE_CONSTRAINT_PRIMARYKEY") return false;
      throw e;
    }
  }

  async update(table: string, where: Where, set: Row): Promise<number> {
    const cols = Object.keys(set);
    if (!cols.length) return 0;
    const w = this.clause(where);
    return this.db.prepare(`UPDATE ${ident(table)} SET ${cols.map((c) => `${ident(c)} = ?`).join(", ")} ${w.sql}`).run(...cols.map((c) => set[c]), ...w.args).changes;
  }

  async delete(table: string, where: Where): Promise<number> {
    const w = this.clause(where);
    return this.db.prepare(`DELETE FROM ${ident(table)} ${w.sql}`).run(...w.args).changes;
  }
}

export class MongoRows implements RowStore {
  readonly backend = "mongodb" as const;
  private readonly specs: Map<string, TableSpec>;

  constructor(private readonly mongo: MeldiqMongo, tables: readonly TableSpec[]) {
    this.specs = new Map(tables.map((t) => [t.name, t]));
  }

  private col(table: string): Collection<Record<string, unknown>> {
    if (!this.specs.has(table)) throw new Error(`Table ${table} isn't one of this store's collections`);
    return this.mongo.db.collection(table);
  }

  private filter(where: Where): Filter<Record<string, unknown>> {
    const f: Record<string, unknown> = {};
    for (const [col, c] of Object.entries(where)) {
      ident(col);
      if (!isOps(c)) {
        f[col] = c;
        continue;
      }
      const ops: Record<string, unknown> = {};
      if (c.lt !== undefined) ops.$lt = c.lt;
      if (c.lte !== undefined) ops.$lte = c.lte;
      if (c.gt !== undefined) ops.$gt = c.gt;
      if (c.gte !== undefined) ops.$gte = c.gte;
      if (c.in !== undefined) ops.$in = c.in;
      if (c.ne !== undefined) ops.$ne = c.ne;
      // A comparison never matches null in SQLite; MongoDB's $lt/$gt don't match null either, so they agree.
      f[col] = ops;
    }
    return f;
  }

  /** Documents keep only the row's columns (and `_id`), like the migration's copies. */
  private strip<T extends Row>(table: string, d: Record<string, unknown>): T {
    const out: Row = {};
    for (const c of this.specs.get(table)!.columns) out[c.name] = (d[c.name] ?? null) as Value;
    return out as T;
  }

  async find<T extends Row>(table: string, where: Where, opts?: FindOptions): Promise<T[]> {
    const limit = opts?.limit === undefined ? undefined : Math.max(0, Math.floor(opts.limit));
    if (limit === 0) return []; // MongoDB reads limit(0) as "no limit"
    let cur = this.col(table).find(this.filter(where), { session: this.mongo.session() });
    if (opts?.orderBy?.length) cur = cur.sort(Object.fromEntries(opts.orderBy.map(([c, d]) => [c, d === "desc" ? -1 : 1])));
    if (limit !== undefined) cur = cur.limit(limit);
    return (await cur.toArray()).map((d) => this.strip<T>(table, d));
  }

  async first<T extends Row>(table: string, where: Where, opts?: FindOptions): Promise<T | null> {
    return (await this.find<T>(table, where, { ...opts, limit: 1 }))[0] ?? null;
  }

  async count(table: string, where: Where): Promise<number> {
    return this.col(table).countDocuments(this.filter(where), { session: this.mongo.session() });
  }

  async insert(table: string, row: Row): Promise<boolean> {
    const spec = this.specs.get(table)!;
    const doc: Record<string, unknown> = {};
    for (const c of spec.columns) doc[c.name] = row[c.name] ?? null;
    doc._id = docId(spec, doc);
    try {
      await this.col(table).insertOne(doc, { session: this.mongo.session() });
      return true;
    } catch (e) {
      if (isDuplicateKey(e)) return false;
      throw e;
    }
  }

  async update(table: string, where: Where, set: Row): Promise<number> {
    if (!Object.keys(set).length) return 0;
    for (const k of Object.keys(set)) ident(k);
    // Rows matched, like SQLite's `changes` (a write of identical values still counts).
    return (await this.col(table).updateMany(this.filter(where), { $set: set }, { session: this.mongo.session() })).matchedCount;
  }

  async delete(table: string, where: Where): Promise<number> {
    return (await this.col(table).deleteMany(this.filter(where), { session: this.mongo.session() })).deletedCount;
  }
}
