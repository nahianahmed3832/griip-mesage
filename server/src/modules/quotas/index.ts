/** The unified quota engine's public API (docs/QUOTAS.md). */
export { type Decision, type EffectivePolicy, type Measured, type PolicyInput, type PolicyRow, QuotaEngine, refusal, RESERVATION_TTL_MS, type Scope, type ScopeDecision } from "./engine.js";
export { isQuotaKey, MODES, periodEnd, periodOf, QUOTA_KEYS, type QuotaKey, type QuotaKeyDef, type QuotaMode, type ScopeType } from "./keys.js";

import type { Scope } from "./engine.js";

/** The scopes an operation by [userId] (in [orgId]'s space, if any) is charged to: the person, the organization, the platform. */
export function scopesFor(userId: string, orgId: string | null | undefined): Scope[] {
  return [{ type: "user", id: userId }, ...(orgId ? [{ type: "org" as const, id: orgId }] : []), { type: "global", id: "" }];
}

export * as quotaAdmin from "./admin.js";
