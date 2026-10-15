/**
 * Operator CLI. Runs inside the api container; there is no HTTP admin surface.
 *
 *   docker compose -p griip-internal-messaging exec api node dist/admin.js <command>
 *
 *   stats                          counts of users, devices, orgs, queued items (no content)
 *   users                          list usernames and device counts
 *   invite <orgSlug> [ROLE] [uses] create an org invite (ROLE: ADMIN|MEMBER|GUEST)
 *   disable <username>             disable an account and drop its sessions
 *   enable <username>
 *   cleanup                        run the retention job now
 */
import { Authz } from "./authz.js";
import { loadConfig } from "./config.js";
import { openDatabase } from "./db.js";
import { Hub } from "./hub.js";
import { runCleanup } from "./jobs.js";
import { humanCode, newId, now, sha256Hex } from "./util.js";

const [cmd, ...rest] = process.argv.slice(2);
const cfg = loadConfig();
const db = openDatabase(cfg.databasePath);
const one = (sql: string, ...p: unknown[]) => (db.prepare(sql).get(...p) as { n: number }).n;

switch (cmd) {
  case "stats":
    console.table({
      users: one("SELECT COUNT(*) n FROM users"),
      activeDevices: one("SELECT COUNT(*) n FROM devices WHERE revoked_at IS NULL"),
      orgs: one("SELECT COUNT(*) n FROM orgs"),
      conversations: one("SELECT COUNT(*) n FROM conversations"),
      queuedEnvelopes: one("SELECT COUNT(*) n FROM delivery_queue WHERE kind = 'envelope'"),
      queuedReceipts: one("SELECT COUNT(*) n FROM delivery_queue WHERE kind = 'receipt'"),
      oldestQueuedHours: Math.round((now() - (one("SELECT COALESCE(MIN(created_at), 0) n FROM delivery_queue") || now())) / 3600_000),
      attachments: one("SELECT COUNT(*) n FROM attachments"),
      liveCalls: one("SELECT COUNT(*) n FROM calls WHERE state != 'ENDED'"),
    });
    break;
  case "users":
    console.table(
      db
        .prepare(
          "SELECT u.username, u.display_name, (SELECT COUNT(*) FROM devices d WHERE d.user_id = u.id AND d.revoked_at IS NULL) devices, u.disabled_at IS NOT NULL disabled FROM users u ORDER BY u.created_at",
        )
        .all(),
    );
    break;
  case "invite": {
    const [slug, role = "MEMBER", uses = "1"] = rest;
    const org = db.prepare("SELECT id, created_by FROM orgs WHERE slug = ?").get(slug) as { id: string; created_by: string } | undefined;
    if (!org || !["ADMIN", "MEMBER", "GUEST"].includes(role)) {
      console.error("usage: invite <orgSlug> [ADMIN|MEMBER|GUEST] [uses]");
      process.exit(2);
    }
    const code = humanCode(12);
    db.prepare(
      `INSERT INTO invitations (id, org_id, code_hash, code_hint, role, max_uses, created_by, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).run(newId("inv"), org.id, sha256Hex(code), code.slice(-4), role, Number(uses), org.created_by, now(), now() + 72 * 3600_000);
    console.log(`${code.slice(0, 4)}-${code.slice(4, 8)}-${code.slice(8)}  (${role}, ${uses} use(s), 72h)`);
    break;
  }
  case "disable":
  case "enable": {
    const u = db.prepare("SELECT id FROM users WHERE username = ?").get(rest[0]) as { id: string } | undefined;
    if (!u) {
      console.error("no such user");
      process.exit(1);
    }
    db.prepare("UPDATE users SET disabled_at = ? WHERE id = ?").run(cmd === "disable" ? now() : null, u.id);
    if (cmd === "disable") db.prepare("DELETE FROM sessions WHERE user_id = ?").run(u.id);
    console.log(`${cmd}d ${rest[0]}`);
    break;
  }
  case "cleanup": {
    const r = await runCleanup({ db, config: cfg, authz: new Authz(db), hub: new Hub(db), livekit: null, log: console as any, audit: () => {} });
    console.table(r);
    break;
  }
  default:
    console.log("commands: stats | users | invite <orgSlug> [role] [uses] | disable <username> | enable <username> | cleanup");
}
db.close();
