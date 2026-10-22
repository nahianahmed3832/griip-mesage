/**
 * Consistent database snapshots for self-hosted operators (docs/SELF_HOSTING.md). SQLite's online backup
 * API copies a live WAL database page by page, so the server can keep running while it's taken.
 */
import Database from "better-sqlite3";
import fs from "node:fs";
import path from "node:path";
import { type DB, latestMigration } from "../../infrastructure/database/db.js";

export async function backupDatabase(db: DB, dest: string): Promise<{ file: string; bytes: number }> {
  if (fs.existsSync(dest)) throw new Error(`refusing to overwrite ${dest}`);
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  await db.backup(dest);
  fs.chmodSync(dest, 0o600); // holds account metadata and queued ciphertext: owner-only
  return { file: dest, bytes: fs.statSync(dest).size };
}

export interface BackupReport {
  ok: boolean;
  integrity: string;
  schemaVersion: number;
  serverSchemaVersion: number;
  users: number;
  problems: string[];
}

/** Opens a snapshot read-only and says whether this server can safely start on it. Changes nothing. */
export function verifyBackup(file: string): BackupReport {
  const problems: string[] = [];
  const serverSchemaVersion = latestMigration();
  let b: Database.Database;
  try {
    b = new Database(file, { readonly: true, fileMustExist: true });
  } catch (e) {
    return { ok: false, integrity: "unreadable", schemaVersion: 0, serverSchemaVersion, users: 0, problems: [`can't open: ${(e as Error).message}`] };
  }
  try {
    let integrity = "unknown";
    try {
      integrity = String(b.pragma("integrity_check", { simple: true }));
    } catch (e) {
      integrity = `failed: ${(e as Error).message}`;
    }
    if (integrity !== "ok") problems.push(`integrity check: ${integrity}`);
    let schemaVersion = 0;
    let users = 0;
    try {
      schemaVersion = (b.prepare("SELECT COALESCE(MAX(version), 0) v FROM schema_migrations").get() as { v: number }).v;
      users = (b.prepare("SELECT COUNT(*) n FROM users").get() as { n: number }).n;
    } catch {
      problems.push("not a Griip Message database (no schema_migrations or users table)");
    }
    // An older snapshot is fine (migrations run at start); a newer one would meet code that doesn't know its schema.
    if (schemaVersion > serverSchemaVersion) problems.push(`snapshot schema ${schemaVersion} is newer than this server's ${serverSchemaVersion}; upgrade the server first`);
    return { ok: problems.length === 0, integrity, schemaVersion, serverSchemaVersion, users, problems };
  } finally {
    b.close();
  }
}
