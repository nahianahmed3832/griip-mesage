import { MongoMemoryReplSet } from "mongodb-memory-server";
import type { TestProject } from "vitest/node";

/**
 * One local, throwaway MongoDB replica set for the whole test run (docs/MONGODB.md › Tests). Never a shared or
 * production database: it listens on 127.0.0.1 only and is deleted when the run ends. A replica set, so transactions
 * behave as on Atlas. Each test server uses its own database in it.
 */
let rs: MongoMemoryReplSet | undefined;

export async function setup(project: TestProject): Promise<void> {
  rs = await MongoMemoryReplSet.create({ replSet: { count: 1, storageEngine: "wiredTiger" }, instanceOpts: [{ ip: "127.0.0.1" }] });
  project.provide("mongoUri", rs.getUri());
}

export async function teardown(): Promise<void> {
  await rs?.stop({ doCleanup: true, force: true });
}

declare module "vitest" {
  export interface ProvidedContext {
    mongoUri: string;
  }
}
