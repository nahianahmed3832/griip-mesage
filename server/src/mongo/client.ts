import { AsyncLocalStorage } from "node:async_hooks";
import { type ClientSession, type Db, MongoClient, MongoServerError } from "mongodb";
import type { DB } from "../db.js";
import { describeTables, indexesFor, type TableSpec, validatorFor } from "./catalog.js";
import { type MongoConfig, type MongoDomain, redactUri } from "./config.js";

/** Bumped whenever the collections' validators or indexes change; recorded in orbit_meta. */
export const MONGO_SCHEMA_VERSION = 2;
export const META_COLLECTION = "orbit_meta";

export interface Logger {
  info(obj: object, msg?: string): void;
  warn(obj: object, msg?: string): void;
  error(obj: object, msg?: string): void;
}

/**
 * The server's MongoDB connection (docs/MONGODB.md): one pooled client, the database, and whether the deployment can run
 * multi-document transactions (replica sets and sharded clusters can; a standalone server can't). Stores run their
 * multi-step writes through [transaction], which carries the session implicitly ([session]), so store methods don't
 * need a session parameter.
 */
export class MeldiqMongo {
  private readonly txContext = new AsyncLocalStorage<ClientSession>();

  private constructor(
    readonly client: MongoClient,
    readonly db: Db,
    readonly config: MongoConfig,
    readonly supportsTransactions: boolean,
  ) {}

  /** Connects, checks the deployment and prepares the collections of [tables] (validators and indexes). */
  static async connect(config: MongoConfig, tables: TableSpec[], log?: Logger): Promise<MeldiqMongo> {
    const client = new MongoClient(config.uri, {
      appName: "meldiq-api",
      maxPoolSize: config.maxPoolSize,
      minPoolSize: 0,
      serverSelectionTimeoutMS: config.timeoutMs,
      connectTimeoutMS: config.timeoutMs,
      socketTimeoutMS: config.timeoutMs * 6,
      retryWrites: true,
      retryReads: true,
      // Acknowledged by a majority of the replica set: a failover never rolls back a write the app was told succeeded.
      writeConcern: { w: "majority" },
      readConcern: { level: "majority" },
    });
    try {
      await client.connect();
      const db = client.db(config.dbName);
      const hello = (await db.admin().command({ hello: 1 })) as { setName?: string; msg?: string };
      const supportsTransactions = !!hello.setName || hello.msg === "isdbgrid";
      const mongo = new MeldiqMongo(client, db, config, supportsTransactions);
      await mongo.ensureSchema(tables);
      log?.info({ db: config.dbName, domains: [...config.domains], transactions: supportsTransactions }, "MongoDB connected");
      return mongo;
    } catch (e) {
      await client.close().catch(() => undefined);
      // The driver's message may echo the host; never the password.
      throw new Error(`MongoDB unavailable (${redactUri(config.uri)}): ${(e as Error).message.replaceAll(config.uri, redactUri(config.uri))}`);
    }
  }

  /** Collections for the tables, created with their validators (or updated to them), and their indexes. Idempotent. */
  async ensureSchema(tables: TableSpec[]): Promise<void> {
    const existing = new Set((await this.db.listCollections({}, { nameOnly: true }).toArray()).map((c) => c.name));
    // Collections are independent of each other: prepared side by side.
    await Promise.all(
      tables.map(async (t) => {
        const validator = validatorFor(t);
        if (existing.has(t.name)) await this.db.command({ collMod: t.name, validator, validationLevel: "strict", validationAction: "error" });
        else await this.db.createCollection(t.name, { validator, validationLevel: "strict", validationAction: "error" });
        const indexes = indexesFor(t);
        if (indexes.length) await this.db.collection(t.name).createIndexes(indexes);
      }),
    );
    await this.db
      .collection<{ _id: string }>(META_COLLECTION)
      .updateOne({ _id: "schema" }, { $set: { version: MONGO_SCHEMA_VERSION, tables: tables.map((t) => t.name), updatedAt: Date.now() } }, { upsert: true });
  }

  owns(domain: MongoDomain): boolean {
    return this.config.domains.has(domain);
  }

  /** The session of the transaction this call runs in, if any (pass it to every operation). */
  session(): ClientSession | undefined {
    return this.txContext.getStore();
  }

  /**
   * Runs [fn] in one multi-document transaction, retried by the driver on transient errors. Without transaction
   * support (a standalone server, development only) it runs without one; the stores then rely on single-document
   * atomic updates, which they use for every check-and-write anyway.
   */
  async transaction<T>(fn: () => Promise<T>): Promise<T> {
    if (this.txContext.getStore()) return fn(); // already inside one
    if (!this.supportsTransactions) return fn();
    const session = this.client.startSession();
    try {
      let result: T | undefined;
      await session.withTransaction(async () => {
        result = await this.txContext.run(session, fn);
      });
      return result as T;
    } finally {
      await session.endSession();
    }
  }

  async ping(): Promise<boolean> {
    try {
      await this.db.command({ ping: 1 });
      return true;
    } catch {
      return false;
    }
  }

  async close(): Promise<void> {
    await this.client.close();
  }
}

/** The tables whose collections the server needs: those of the domains it keeps in MongoDB. */
export function domainTables(db: DB, domains: Iterable<MongoDomain>): TableSpec[] {
  const wanted = new Set<string>(domains);
  return describeTables(db).filter((t) => wanted.has(t.domain));
}

/** A duplicate-key error (a unique index refused the write). */
export function isDuplicateKey(e: unknown): boolean {
  return e instanceof MongoServerError && e.code === 11000;
}
