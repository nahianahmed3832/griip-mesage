import type { AppContext } from "../../app/context.js";
import { networkKey } from "../../app/middleware/abuse.js";
import { notFound } from "../../shared/errors.js";
import { now } from "../../shared/utilities/util.js";
import { ENTITLEMENT_KEYS, ENTITLEMENT_LABELS, type EntitlementKey, orgLimitOverrides } from "../administration/index.js";
import { orgNames, orgRecord } from "../organizations/index.js";
import type { PlatformAudit } from "./audit.js";
import type { AdminPrincipal } from "./auth.js";
import type { RequestMeta } from "./credentials.js";

export const NO_BILLING = "No billing provider is configured: Meldiq has no plans, prices, subscriptions or payments yet. Limits below are set by operators.";

const limitsOf = (ctx: AppContext, orgId: string) => ENTITLEMENT_KEYS.map((key) => ({ key, ...ctx.entitlements.limitFor(key, orgId) }));

/**
 * Entitlements and billing status (§5, phase 4). There is no billing provider, so nothing here is a plan or a payment:
 * only the limits operators set, server-wide (ENTITLEMENT_LIMITS) or per organization, enforced where things are added.
 */
export class AdminBilling {
  constructor(
    private readonly ctx: AppContext,
    private readonly audit: PlatformAudit,
  ) {}

  overview() {
    const overrides = orgLimitOverrides(this.ctx.db);
    const names = orgNames(this.ctx.db, [...new Set(overrides.map((o) => o.org_id))]);
    const server = this.ctx.config.entitlements.limits;
    return {
      billing: { available: false as const, reason: NO_BILLING },
      keys: ENTITLEMENT_KEYS.map((key) => ({ key, label: ENTITLEMENT_LABELS[key] })),
      serverLimits: ENTITLEMENT_KEYS.filter((k) => server[k] !== undefined).map((key) => ({ key, limit: server[key]! })),
      overrides: overrides.map((o) => ({ orgId: o.org_id, orgName: names.get(o.org_id) ?? null, key: o.key, limit: o.limit_value, setAt: o.set_at })),
    };
  }

  /** Sets or clears ([null]) an organization's own limits; the change and its before/after are audited together. */
  setLimits(p: AdminPrincipal, orgId: string, limits: Partial<Record<EntitlementKey, number | null>>, reason: string, meta: RequestMeta) {
    if (!orgRecord(this.ctx.db, orgId)) throw notFound("No such workspace or community");
    const before = Object.fromEntries(limitsOf(this.ctx, orgId).filter((l) => l.source === "workspace").map((l) => [l.key, l.limit]));
    this.ctx.db.transaction(() => {
      this.ctx.entitlements.setLimits(orgId, `platform:${p.account.id}`, limits, now());
      this.audit.record({
        action: "org.limits",
        outcome: "success",
        actorAccountId: p.account.id,
        actorRole: p.grant.role,
        targetType: "org",
        targetId: orgId,
        reason,
        requestId: meta.requestId,
        network: networkKey(meta.ip),
        detail: Object.fromEntries(Object.entries(limits).map(([k, v]) => [k, `${before[k] ?? "default"} -> ${v ?? "default"}`])),
      });
    })();
    return { limits: limitsOf(this.ctx, orgId) };
  }
}
