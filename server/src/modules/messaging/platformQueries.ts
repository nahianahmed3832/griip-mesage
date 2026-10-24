import type { DB } from "../../infrastructure/database/db.js";

/** Channel counts of a community for the Platform Admin Console: how many, never their names, members or messages. */
export function channelCounts(db: DB, orgId: string): { total: number; private: number } {
  const r = db.prepare("SELECT COUNT(*) AS total, SUM(CASE WHEN kind = 'PRIVATE' THEN 1 ELSE 0 END) AS private FROM conversations WHERE org_id = ? AND type = 'CHANNEL'").get(orgId) as {
    total: number;
    private: number | null;
  };
  return { total: r.total, private: r.private ?? 0 };
}
