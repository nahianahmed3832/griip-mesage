import type { DB } from "./db.js";

/**
 * Tables of the modules the community rebuild removed (docs/COMMUNITY_REBUILD_2026-10-21.md §3): apps and their
 * connections (with sealed provider tokens), the Unified Inbox, workflows, documents, developer keys and webhooks, and
 * user groups. Nothing reads or writes them any more. Their migrations stay, so a rollback to an older server finds its
 * tables, and nothing is deleted unless an operator asks: `admin retired-data` reports, `--purge --yes` deletes.
 * Children come before their parents, so a purge never trips a foreign key.
 */
export const RETIRED_TABLES = [
  "integration_credentials",
  "integration_oauth_states",
  "integration_channel_events",
  "integration_subscriptions",
  "integration_deliveries",
  "integration_jobs",
  "integration_hooks",
  "integration_accounts",
  "integration_installs",
  "integration_policies",
  "inbox_events",
  "inbox_messages",
  "inbox_conversations",
  "inbox_contacts",
  "inbox_queues",
  "inbox_settings",
  "inbox_accounts",
  "workflow_runs",
  "workflows",
  "document_comments",
  "document_versions",
  "document_members",
  "documents",
  "webhook_deliveries",
  "webhooks",
  "api_keys",
  "user_group_members",
  "user_groups",
] as const;

export const RETIRED_COMMANDS = ["retired-data"];

const exists = (db: DB, table: string): boolean => !!db.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").get(table);

/** Rows left in each retired table that exists (counts only, never content). */
export function retiredDataReport(db: DB): Record<string, number> {
  const out: Record<string, number> = {};
  for (const t of RETIRED_TABLES) {
    if (exists(db, t)) out[t] = (db.prepare(`SELECT COUNT(*) AS n FROM ${t}`).get() as { n: number }).n;
  }
  return out;
}

/** Deletes every row of the retired tables in one transaction; the tables themselves stay. Returns the rows removed. */
export function purgeRetiredData(db: DB): Record<string, number> {
  const removed: Record<string, number> = {};
  db.transaction(() => {
    for (const t of RETIRED_TABLES) if (exists(db, t)) removed[t] = db.prepare(`DELETE FROM ${t}`).run().changes;
  })();
  return removed;
}

export function runRetiredCommand(_cmd: string, args: string[], db: DB): number {
  if (!args.includes("--purge")) {
    console.log(JSON.stringify({ retired: retiredDataReport(db) }, null, 2));
    return 0;
  }
  if (!args.includes("--yes")) {
    console.error("retired-data --purge deletes these rows for good. Back up first, then add --yes.");
    console.log(JSON.stringify({ wouldDelete: retiredDataReport(db) }, null, 2));
    return 2;
  }
  console.log(JSON.stringify({ deleted: purgeRetiredData(db) }, null, 2));
  return 0;
}
