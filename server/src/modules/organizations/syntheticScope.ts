/** Load-test communities (docs/LOADTEST_PROVISIONING.md): synthetic accounts may only join communities synthetic accounts made. */
import type { DB } from "../../infrastructure/database/db.js";
import { ApiError } from "../../shared/errors.js";
import { isSynthetic } from "../accounts/index.js";

/** Marks a community its synthetic creator just made as a load-test community. */
export function markSyntheticOrg(db: DB, orgId: string, creatorId: string): void {
  if (isSynthetic(db, creatorId)) db.prepare("UPDATE orgs SET synthetic = 1 WHERE id = ?").run(orgId);
}

/** A synthetic account may only join load-test communities. */
export function requireSyntheticScopeForOrg(db: DB, userId: string, orgId: string): void {
  if (!isSynthetic(db, userId)) return;
  const org = db.prepare("SELECT synthetic FROM orgs WHERE id = ?").get(orgId) as { synthetic: number } | undefined;
  if (org?.synthetic !== 1) throw new ApiError(403, "synthetic_scope", "Load-test accounts can only join load-test communities");
}
