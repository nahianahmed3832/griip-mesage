/**
 * Platform telemetry's public API (docs/ANALYTICS_DEFINITIONS.md, docs/RESOURCE_MONITORING.md): the service the server
 * wires in, and the read-side queries the Platform Admin Console uses.
 */
export { Telemetry, RETENTION } from "./service.js";
export { classifyClient, type ClientInfo, PLATFORMS, type Platform } from "./client.js";
export { ACTIVE_NOW_MS, requestKind, TRAFFIC_METRICS, type TrafficMetric, utcDay } from "./activity.js";
export { COLLECTOR_STALE_MS, SERVICE_GROUPS, type CollectorStatus } from "./resources.js";
export * as analytics from "./analytics.js";
