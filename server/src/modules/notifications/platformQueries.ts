import type { DB } from "../../infrastructure/database/db.js";

export type PushState = "none" | "ok" | "failing";

/**
 * Wake-up registrations of [deviceIds] for the Platform Admin Console: whether each device has an FCM token or a
 * UnifiedPush endpoint and whether its last wake-up failed. Never the token or endpoint itself (they're capabilities).
 */
export function pushStatus(db: DB, deviceIds: string[]): Map<string, { fcm: PushState; unifiedPush: PushState; lastWakeAt: number | null }> {
  const out = new Map<string, { fcm: PushState; unifiedPush: PushState; lastWakeAt: number | null }>();
  if (deviceIds.length === 0) return out;
  const json = JSON.stringify(deviceIds);
  const fcm = db.prepare("SELECT device_id, last_wake_at, failing_since FROM fcm_tokens WHERE device_id IN (SELECT value FROM json_each(?))").all(json) as {
    device_id: string;
    last_wake_at: number | null;
    failing_since: number | null;
  }[];
  const up = db.prepare("SELECT device_id, last_wake_at, failing_since FROM push_endpoints WHERE device_id IN (SELECT value FROM json_each(?))").all(json) as {
    device_id: string;
    last_wake_at: number | null;
    failing_since: number | null;
  }[];
  for (const id of deviceIds) out.set(id, { fcm: "none", unifiedPush: "none", lastWakeAt: null });
  for (const r of fcm) {
    const cur = out.get(r.device_id)!;
    out.set(r.device_id, { ...cur, fcm: r.failing_since ? "failing" : "ok", lastWakeAt: Math.max(cur.lastWakeAt ?? 0, r.last_wake_at ?? 0) || null });
  }
  for (const r of up) {
    const cur = out.get(r.device_id)!;
    out.set(r.device_id, { ...cur, unifiedPush: r.failing_since ? "failing" : "ok", lastWakeAt: Math.max(cur.lastWakeAt ?? 0, r.last_wake_at ?? 0) || null });
  }
  return out;
}
