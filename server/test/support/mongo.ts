import { MongoClient } from "mongodb";
import { inject } from "vitest";
import type { MongoConfig } from "../../src/mongo/config.js";

let n = 0;

/** A database name nobody else in this run uses. */
export function testDbName(prefix = "t"): string {
  return `${prefix}_${process.pid}_${++n}_${Date.now().toString(36)}`;
}

/**
 * The MongoDB settings a test server gets: none, unless the test asks for MongoDB domains itself or the run sets
 * ORBIT_TEST_MONGO_DOMAINS (then every suite runs with those domains in MongoDB). Always the run's local replica set.
 */
export function testMongoEnv(env: Record<string, string>): Record<string, string> {
  const domains = env.MONGODB_DOMAINS ?? process.env.ORBIT_TEST_MONGO_DOMAINS;
  if (!domains && !env.MONGODB_URI) return {};
  return { MONGODB_URI: env.MONGODB_URI ?? inject("mongoUri"), MONGODB_DB: env.MONGODB_DB ?? testDbName(), MONGODB_DOMAINS: domains ?? "" };
}

export async function dropTestDatabase(cfg: MongoConfig): Promise<void> {
  const c = await MongoClient.connect(cfg.uri);
  try {
    await c.db(cfg.dbName).dropDatabase();
  } finally {
    await c.close();
  }
}
