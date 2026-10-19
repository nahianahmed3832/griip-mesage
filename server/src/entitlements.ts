import { ApiError } from "./util.js";

/**
 * Entitlements (docs/PLANS.md): the one place where plan-based limits will live, so the app and the routes never
 * hard-code what a plan allows. No plans, prices or limits have been decided, so by default every capability is
 * available and nothing is limited. An operator can set server-wide limits (ENTITLEMENT_LIMITS, for example
 * "integrations.installed=10,integrations.webhooks=20"); they apply to every workspace and are shown honestly.
 * There is no upgrade flow, so no upgrade is ever offered.
 */
export const ENTITLEMENT_KEYS = ["integrations.installed", "integrations.webhooks", "integrations.notifications", "roles.custom"] as const;
export type EntitlementKey = (typeof ENTITLEMENT_KEYS)[number];

const LABELS: Record<EntitlementKey, string> = {
  "integrations.installed": "Installed apps",
  "integrations.webhooks": "Incoming webhooks",
  "integrations.notifications": "App notifications in channels",
  "roles.custom": "Custom roles",
};

export interface EntitlementConfig {
  limits: Readonly<Partial<Record<EntitlementKey, number>>>;
}

export function entitlementConfig(env: NodeJS.ProcessEnv): EntitlementConfig {
  const limits: Partial<Record<EntitlementKey, number>> = {};
  for (const part of (env.ENTITLEMENT_LIMITS ?? "").split(",").map((s) => s.trim()).filter(Boolean)) {
    const [key, value] = part.split("=").map((s) => s.trim());
    if (!(ENTITLEMENT_KEYS as readonly string[]).includes(key)) throw new Error(`ENTITLEMENT_LIMITS: unknown capability "${key}" (known: ${ENTITLEMENT_KEYS.join(", ")})`);
    const n = Number(value);
    if (!Number.isInteger(n) || n < 0) throw new Error(`ENTITLEMENT_LIMITS: ${key} needs a whole number`);
    limits[key as EntitlementKey] = n;
  }
  return { limits };
}

export interface EntitlementView {
  key: EntitlementKey;
  label: string;
  /** null: no limit on this server. */
  limit: number | null;
  used: number;
  available: boolean;
}

export class Entitlements {
  constructor(private readonly config: EntitlementConfig) {}

  /** What a workspace has: the same for every workspace until plans exist. */
  plan(usage: Readonly<Partial<Record<EntitlementKey, number>>>) {
    const capabilities: EntitlementView[] = ENTITLEMENT_KEYS.map((key) => {
      const limit = this.config.limits[key] ?? null;
      const used = usage[key] ?? 0;
      return { key, label: LABELS[key], limit, used, available: limit === null || used < limit };
    });
    // No plan has been assigned or priced: say so instead of inventing one.
    return { plan: null, source: Object.keys(this.config.limits).length ? "server" : "none", capabilities, upgrade: null };
  }

  /** Refuses adding one more of [key] once a server-wide limit is reached (409, with the limit in the message). */
  check(key: EntitlementKey, used: number): void {
    const limit = this.config.limits[key];
    if (limit !== undefined && used >= limit) {
      throw new ApiError(409, "limit_reached", `This server allows ${limit} ${LABELS[key].toLowerCase()} per workspace, and this workspace has reached it.`, { key, limit });
    }
  }
}
