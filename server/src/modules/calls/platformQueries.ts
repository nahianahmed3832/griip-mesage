import type { DB } from "../../infrastructure/database/db.js";

/**
 * Call figures for the Platform Admin Console (counts only: no rooms, names or conversation ids). Calls in progress are
 * those RINGING or ACTIVE; their participants are the people with a device in them that hasn't left.
 */
export function activeCallCounts(db: DB): { calls: number; participants: number; video: number } {
  const r = db
    .prepare(
      `SELECT COUNT(*) calls, COALESCE(SUM(video), 0) video,
              (SELECT COUNT(DISTINCT p.user_id) FROM call_participants p JOIN calls c2 ON c2.id = p.call_id
                WHERE c2.state IN ('RINGING','ACTIVE') AND p.left_at IS NULL) participants
         FROM calls WHERE state IN ('RINGING','ACTIVE')`,
    )
    .get() as { calls: number; video: number; participants: number };
  return { calls: r.calls, participants: r.participants, video: r.video };
}

/**
 * Calls started per UTC day since [from] (ms): answered (someone picked up), declined or busy, and lost (the server saw
 * the participants' connections drop). Other endings (hang-up, empty) are ordinary.
 */
export function callDays(db: DB, from: number): { day: string; started: number; answered: number; declined: number; lost: number }[] {
  return db
    .prepare(
      `SELECT strftime('%Y-%m-%d', created_at / 1000, 'unixepoch') day, COUNT(*) started,
              SUM(CASE WHEN answered_at IS NOT NULL THEN 1 ELSE 0 END) answered,
              SUM(CASE WHEN end_reason IN ('declined','busy') THEN 1 ELSE 0 END) declined,
              SUM(CASE WHEN end_reason = 'lost' THEN 1 ELSE 0 END) lost
         FROM calls WHERE created_at >= ? GROUP BY day ORDER BY day`,
    )
    .all(from) as { day: string; started: number; answered: number; declined: number; lost: number }[];
}
