/**
 * The administration module's public API for other modules (docs/architecture/ARCHITECTURE_1_0_PLAN.md §3.2). Started
 * 2026-10-23 for the Platform Admin Console.
 */
export { ENTITLEMENT_KEYS, type EntitlementKey, isEntitlementKey, LABELS as ENTITLEMENT_LABELS, orgLimitOverrides } from "./entitlements.js";
export { type HealthReport, healthReport } from "./ops/health.js";
export { CHECKS, checkConnector, connectorReport } from "./ops/connectors.js";
export { experimentsReport } from "./experiments/assign.js";
