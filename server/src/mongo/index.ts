import type { DB } from "../db.js";
import type { TableSpec } from "./catalog.js";
import { domainTables, type Logger, OrbitMongo } from "./client.js";
import { MONGO_DOMAINS, type MongoConfig, type MongoDomain } from "./config.js";
import { authorityOf, setAuthority } from "./migrate.js";

export { OrbitMongo } from "./client.js";
export type { MongoConfig, MongoDomain } from "./config.js";

/** Rows the SQLite tables of [tables] hold. */
function sqliteRows(db: DB, tables: TableSpec[]): number {
  return tables.reduce((n, t) => n + (db.prepare(`SELECT COUNT(*) n FROM "${t.name}"`).get() as { n: number }).n, 0);
}

/**
 * Connects to MongoDB for the server and refuses to start in a state that could split a domain's data between the two
 * stores (docs/MONGODB.md › Authority):
 * - a domain in MONGODB_DOMAINS must have been migrated (`admin mongo-migrate`), unless SQLite has nothing for it yet;
 * - a domain migrated to MongoDB can't silently go back to SQLite's stale copy (`admin mongo-rollback` moves it back).
 */
export async function openMongo(config: MongoConfig, db: DB, log?: Logger): Promise<OrbitMongo> {
  const mongo = await OrbitMongo.connect(config, domainTables(db, config.domains), log);
  try {
    for (const domain of MONGO_DOMAINS) await checkAuthority(mongo, db, domain);
  } catch (e) {
    await mongo.close();
    throw e;
  }
  return mongo;
}

async function checkAuthority(mongo: OrbitMongo, db: DB, domain: MongoDomain): Promise<void> {
  const authority = await authorityOf(mongo, domain);
  if (mongo.owns(domain)) {
    if (authority === "mongodb") return;
    const rows = sqliteRows(db, domainTables(db, [domain]));
    if (rows > 0) {
      throw new Error(
        `MONGODB_DOMAINS includes "${domain}", but its ${rows} row(s) are still only in SQLite. Stop writes and run: node dist/admin.js mongo-migrate ${domain}`,
      );
    }
    await setAuthority(mongo, domain, "mongodb", "empty in SQLite when first used");
  } else if (authority === "mongodb") {
    throw new Error(
      `"${domain}" lives in MongoDB since its migration. Keep it in MONGODB_DOMAINS, or move it back first: node dist/admin.js mongo-rollback ${domain}`,
    );
  }
}
