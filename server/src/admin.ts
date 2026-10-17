/**
 * Operator CLI. Runs inside the api container; there is no HTTP admin surface.
 *
 *   docker compose -p griip-internal-messaging exec api node dist/admin.js <command>
 *
 *   stats                          counts of users, devices, orgs, queued items (no content)
 *   users                          list usernames and device counts
 *   invite <orgSlug> [ROLE] [uses] create an org invite (ROLE: ADMIN|MEMBER|GUEST)
 *   reg-invite [uses] [days] [note] create a registration invite (default 1 use, 7 days)
 *   reg-invites                    list registration invites (hint, uses, expiry; never the code)
 *   reg-invite-revoke <hint|id>    revoke a registration invite
 *   disable <username>             disable an account and drop its sessions
 *   enable <username>
 *   limits <username>              new-account limits, active devices and file use of one account (no content)
 *   limits-end <username>          end an account's new-account limits now (docs/ABUSE_CONTROLS.md)
 *   cleanup                        run the retention job now
 *   backup <file>                  consistent snapshot of the live database (never overwrites)
 *   verify-backup <file>           read-only check that a snapshot is intact and this server can use it
 *   fed-status                     federation identity, domain, known peers and queued deliveries
 *   fed-rotate-key                 add a new server key signed by the current one (stop the server first; the id stays)
 *   fed-decommission <reason> --yes  permanently retire this server id on the core network (can't be undone)
 *   fed-block <serverId> [reason]  refuse federation with a server; fed-unblock <serverId> lifts it
 *   org-duplicates                 list workspaces one person owns under the same name, with what each holds (counts only)
 *   org-delete-empty <orgId> [--yes]  remove one provably empty duplicate (dry run without --yes; back up first)
 *   storage-check                  which file store is configured and whether it answers (no secrets printed)
 *   storage-orphans [--delete]     stored files no record refers to, older than a day (dry run; --delete queues them
 *                                  for the retention job)
 */
import { Authz } from "./authz.js";
import { backupDatabase, verifyBackup } from "./backup.js";
import { loadConfig } from "./config.js";
import { openDatabase } from "./db.js";
import { ServerIdentity } from "./federation/identity.js";
import { Hub } from "./hub.js";
import { endNewAccountLimits } from "./abuse.js";
import { createRegistrationInvite } from "./invites.js";
import { runCleanup } from "./jobs.js";
import { deleteEmptyOrg, findDuplicateOrgs, keepReason, orgFootprint } from "./orgRepair.js";
import { humanCode, newId, now, sha256Hex } from "./util.js";
import { createBlobStore } from "./storage/index.js";

const [cmd, ...rest] = process.argv.slice(2);
// verify-backup only reads the snapshot: it must not open (or migrate) the live database.
if (cmd === "verify-backup") {
  if (!rest[0]) {
    console.error("usage: verify-backup <file>");
    process.exit(2);
  }
  const r = verifyBackup(rest[0]);
  console.log(JSON.stringify(r, null, 2));
  process.exit(r.ok ? 0 : 1);
}
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
  case "reg-invite": {
    const [uses = "1", days = "7", ...note] = rest;
    try {
      const { code } = createRegistrationInvite(db, { maxUses: Number(uses), days: Number(days), note: note.join(" ") || null });
      console.log(`${code}  (registration, ${uses} use(s), ${days} day(s))`);
    } catch (e) {
      console.error(`usage: reg-invite [uses 1-1000] [days ≤30] [note]  (${(e as Error).message})`);
      process.exit(2);
    }
    break;
  }
  case "reg-invites":
    console.table(
      db
        .prepare(
          "SELECT id, code_hint hint, note, uses, max_uses, datetime(expires_at / 1000, 'unixepoch') expires, revoked_at IS NOT NULL revoked FROM registration_invites ORDER BY created_at DESC",
        )
        .all(),
    );
    break;
  case "reg-invite-revoke": {
    const key = (rest[0] ?? "").toUpperCase();
    const res = db.prepare("UPDATE registration_invites SET revoked_at = ? WHERE (id = ? OR code_hint = ?) AND revoked_at IS NULL").run(now(), rest[0], key);
    console.log(res.changes ? `revoked ${res.changes} invite(s)` : "no matching active invite");
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
  case "limits":
  case "limits-end": {
    const u = db.prepare("SELECT id, limited_until FROM users WHERE username = ?").get(rest[0]) as { id: string; limited_until: number | null } | undefined;
    if (!u) {
      console.error(`usage: ${cmd} <username>  (no such user)`);
      process.exit(1);
    }
    if (cmd === "limits-end") {
      console.log(endNewAccountLimits(db, u.id) ? `new-account limits ended for ${rest[0]}` : `${rest[0]} had no new-account limits`);
      break;
    }
    const t = now();
    const limited = !!u.limited_until && u.limited_until > t;
    console.log(
      JSON.stringify(
        {
          newAccountLimits: limited ? { until: new Date(u.limited_until!).toISOString() } : null,
          activeDevices: one("SELECT COUNT(*) n FROM devices WHERE user_id = ? AND revoked_at IS NULL", u.id),
          maxDevices: cfg.abuse.maxDevicesPerUser || null,
          filesUploadedLast24h: one("SELECT COALESCE(SUM(size), 0) n FROM attachments WHERE uploader_user_id = ? AND created_at > ?", u.id, t - 86_400_000),
          filesStored: one("SELECT COALESCE(SUM(size), 0) n FROM attachments WHERE uploader_user_id = ? AND expires_at > ?", u.id, t),
          historyBytes: one("SELECT COALESCE(SUM(bytes), 0) n FROM history_accounts WHERE user_id = ?", u.id),
        },
        null,
        2,
      ),
    );
    break;
  }
  case "cleanup": {
    const r = await runCleanup({ db, config: cfg, authz: new Authz(db), hub: new Hub(db), livekit: null, blobs: createBlobStore(cfg), log: console as any, audit: () => {} });
    console.table(r);
    break;
  }
  case "backup": {
    if (!rest[0]) {
      console.error("usage: backup <file>");
      process.exit(2);
    }
    try {
      const r = await backupDatabase(db, rest[0]);
      console.log(`backup written: ${r.file} (${r.bytes} bytes)`);
    } catch (e) {
      console.error(`backup failed: ${(e as Error).message}`);
      process.exit(1);
    }
    break;
  }
  case "fed-status": {
    if (!cfg.federation) {
      console.log("federation: off (FEDERATION_DOMAIN not set)");
      break;
    }
    const id = ServerIdentity.loadOrCreate(cfg.federation.keyDir);
    console.log(JSON.stringify({ serverId: id.serverId, domain: cfg.federation.domain, publicUrl: cfg.federation.publicUrl, keys: id.publicChain().length + 1, coreNodes: cfg.federation.coreNodes.length, quorum: cfg.federation.coreQuorum }, null, 2));
    console.table(db.prepare("SELECT server_id, domain, state, agreeing, datetime(verified_at / 1000, 'unixepoch') verified FROM federation_peers ORDER BY domain").all());
    console.table(db.prepare("SELECT peer_server_id, kind, COUNT(*) n, MAX(attempts) attempts FROM federation_outbox GROUP BY peer_server_id, kind").all());
    break;
  }
  case "fed-rotate-key": {
    if (!cfg.federation) throw new Error("federation is off");
    const id = ServerIdentity.loadOrCreate(cfg.federation.keyDir);
    id.rotateKey();
    console.log(`rotated: server ${id.serverId} now signs with key #${id.publicChain().length}. Start the server: it reissues its descriptor and re-announces.`);
    break;
  }
  case "fed-decommission": {
    if (!cfg.federation) throw new Error("federation is off");
    if (!rest[0] || rest[1] !== "--yes") {
      console.error('usage: fed-decommission "<reason>" --yes   (permanent: this server id can never federate again)');
      process.exit(2);
    }
    const id = ServerIdentity.loadOrCreate(cfg.federation.keyDir);
    const rec = id.selfSigned({ t: "griip.server.decommission" as const, reason: rest[0], at: Date.now() });
    for (const c of cfg.federation.coreNodes) {
      const r = await fetch(`${c.url.replace(/\/$/, "")}/core/v1/decommission`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(rec) }).catch((e) => e as Error);
      console.log(c.url, r instanceof Error ? r.message : r.status);
    }
    break;
  }
  case "org-duplicates": {
    const groups = findDuplicateOrgs(db);
    if (groups.length === 0) console.log("No duplicate workspaces.");
    for (const g of groups) {
      console.log(`\n"${g.name}" owned by ${g.ownerUsername ?? g.ownerId}: ${g.orgs.length} workspaces (oldest first)`);
      console.table(
        g.orgs.map((o) => ({
          id: o.id,
          slug: o.slug,
          created: new Date(o.createdAt).toISOString(),
          members: o.members,
          teams: o.teams,
          channels: o.channels,
          removable: o.removable ? "yes (empty)" : `no: ${o.keepReason}`,
        })),
      );
    }
    break;
  }
  case "org-delete-empty": {
    const orgId = rest[0];
    if (!orgId) {
      console.error("usage: org-delete-empty <orgId> [--yes]   (refuses anything that isn't provably empty; back up first)");
      process.exit(2);
    }
    const f = orgFootprint(db, orgId);
    const reason = keepReason(db, orgId);
    console.log(JSON.stringify({ org: f ?? null, removable: reason === null, keepReason: reason }, null, 2));
    if (reason) process.exit(1);
    if (!rest.includes("--yes")) {
      console.log("Dry run: nothing deleted. Run again with --yes to remove it.");
      break;
    }
    deleteEmptyOrg(db, orgId);
    console.log(`Deleted empty workspace ${orgId}.`);
    break;
  }
  case "fed-block":
  case "fed-unblock": {
    if (!/^gs1[a-z2-7]{26}$/.test(rest[0] ?? "")) {
      console.error(`usage: ${cmd} <serverId> [reason]`);
      process.exit(2);
    }
    if (cmd === "fed-block") db.prepare("INSERT OR REPLACE INTO federation_blocks (server_id, reason, created_at) VALUES (?, ?, ?)").run(rest[0], rest.slice(1).join(" ") || null, now());
    else db.prepare("DELETE FROM federation_blocks WHERE server_id = ?").run(rest[0]);
    console.log(`${cmd === "fed-block" ? "blocked" : "unblocked"} ${rest[0]}`);
    break;
  }
  case "storage-check": {
    const blobs = createBlobStore(cfg);
    const s3 = cfg.storage.s3;
    console.log(`backend: ${blobs.backend}${cfg.storage.readFallback ? ` · also reading: ${cfg.storage.readFallback}` : ""}${s3 ? ` · bucket ${s3.bucket} · region ${s3.region} · prefix "${s3.prefix}" · credentials: ${s3.credentials ? "S3_ACCESS_KEY_ID" : "AWS default chain"} · direct uploads: ${blobs.direct ? "on" : "off"} · presigned downloads: ${s3.presignedDownloads ? "on" : "off"}` : ""}`);
    try {
      let n = 0;
      for await (const _ of blobs.listIds("attachments")) if (++n >= 1) break;
      console.log("store reachable: yes (listing works)");
    } catch (e) {
      console.error(`store reachable: NO (${(e as Error).message})`);
      process.exitCode = 1;
    }
    break;
  }
  case "storage-orphans": {
    const blobs = createBlobStore(cfg);
    const cutoff = now() - 86_400_000;
    const orphans: { kind: "attachments" | "profile-photos"; id: string; size: number }[] = [];
    const attachment = db.prepare("SELECT 1 FROM attachments WHERE id = ? UNION SELECT 1 FROM attachment_uploads WHERE id = ?");
    for await (const o of blobs.listIds("attachments")) {
      if (o.modifiedAt < cutoff && !attachment.get(o.id, o.id)) orphans.push({ kind: "attachments", id: o.id, size: o.size });
    }
    const photo = db.prepare("SELECT 1 FROM users WHERE id = ? AND photo_rev IS NOT NULL AND (? = id || '-' || photo_rev OR ? = id)");
    for await (const o of blobs.listIds("profile-photos")) {
      const user = o.id.replace(/-\d+$/, "");
      if (o.modifiedAt < cutoff && !photo.get(user, o.id, o.id)) orphans.push({ kind: "profile-photos", id: o.id, size: o.size });
    }
    const bytes = orphans.reduce((a, o) => a + o.size, 0);
    console.log(`${orphans.length} orphaned file(s), ${bytes} bytes, older than a day (${blobs.backend})`);
    if (rest.includes("--delete")) {
      const q = db.prepare("INSERT OR IGNORE INTO pending_blob_deletes (kind, blob_id, attempts, next_at) VALUES (?, ?, 0, 0)");
      for (const o of orphans) q.run(o.kind, o.id);
      console.log("queued for deletion; they're removed by the next retention run (or run: cleanup)");
    } else if (orphans.length) console.log("dry run: nothing deleted. Re-run with --delete to remove them.");
    break;
  }
  default:
    console.log(
      "commands: stats | users | invite <orgSlug> [role] [uses] | reg-invite [uses] [days] [note] | reg-invites | reg-invite-revoke <hint|id> | disable <username> | enable <username> | cleanup | backup <file> | verify-backup <file> | fed-status | fed-rotate-key | fed-decommission <reason> --yes | fed-block <serverId> [reason] | fed-unblock <serverId>",
    );
}
db.close();
