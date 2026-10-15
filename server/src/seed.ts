/**
 * DEVELOPMENT SEED — creates the GriipBuddy sample hierarchy owned by an existing user.
 *
 *   node dist/seed.js --owner <username>
 *
 * Refuses to run when NODE_ENV=production unless --force is passed. Seeded rows are tagged in
 * the audit log ("seed.dev") so they can be identified; no sample users or messages are created.
 */
import { Authz } from "./authz.js";
import { loadConfig } from "./config.js";
import { openDatabase } from "./db.js";
import { humanCode, newId, now, sha256Hex } from "./util.js";

const args = process.argv.slice(2);
const arg = (n: string) => {
  const i = args.indexOf(n);
  return i >= 0 ? args[i + 1] : undefined;
};
if (process.env.NODE_ENV === "production" && !args.includes("--force")) {
  console.error("Refusing to seed development data with NODE_ENV=production (pass --force to override).");
  process.exit(2);
}
const ownerName = arg("--owner");
if (!ownerName) {
  console.error("Usage: seed --owner <username>   (register the owner in the app first)");
  process.exit(2);
}
const cfg = loadConfig();
const db = openDatabase(cfg.databasePath);
const authz = new Authz(db);
const owner = db.prepare("SELECT id FROM users WHERE username = ?").get(ownerName) as { id: string } | undefined;
if (!owner) {
  console.error(`No user named ${ownerName}. Register in the app first.`);
  process.exit(1);
}
if (db.prepare("SELECT 1 FROM orgs WHERE slug = 'griipbuddy'").get()) {
  console.error("GriipBuddy org already exists — nothing to do.");
  process.exit(0);
}
const t = now();
const audit = (orgId: string, action: string, target: string, detail: string) =>
  db.prepare("INSERT INTO audit_log (at, actor_id, org_id, action, target, detail) VALUES (?, ?, ?, ?, ?, ?)").run(t, owner.id, orgId, action, target, detail);

const orgId = newId("o");
const codes: string[] = [];
db.transaction(() => {
  db.prepare("INSERT INTO orgs (id, name, slug, description, created_by, created_at, updated_at) VALUES (?, 'GriipBuddy', 'griipbuddy', ?, ?, ?, ?)").run(
    orgId,
    "GriipBuddy internal workspace (development seed)",
    owner.id,
    t,
    t,
  );
  db.prepare("INSERT INTO org_members (org_id, user_id, role, joined_at) VALUES (?, ?, 'OWNER', ?)").run(orgId, owner.id, t);
  audit(orgId, "seed.dev", orgId, "org");

  const branch = (name: string, location: string) => {
    const id = newId("b");
    db.prepare("INSERT INTO branches (id, org_id, name, location, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)").run(id, orgId, name, location, t, t);
    db.prepare("INSERT INTO branch_members (branch_id, user_id, role, joined_at) VALUES (?, ?, 'MANAGER', ?)").run(id, owner.id, t);
    audit(orgId, "branch.create", id, name);
    return id;
  };
  const dept = (branchId: string, name: string) => {
    const id = newId("dp");
    db.prepare("INSERT INTO departments (id, org_id, branch_id, name, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)").run(id, orgId, branchId, name, t, t);
    return id;
  };
  const team = (name: string, branchId: string | null, deptId: string | null, description: string) => {
    const id = newId("t");
    db.prepare("INSERT INTO teams (id, org_id, branch_id, department_id, name, description, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)").run(
      id,
      orgId,
      branchId,
      deptId,
      name,
      description,
      t,
      t,
    );
    db.prepare("INSERT INTO team_members (team_id, user_id, role, joined_at) VALUES (?, ?, 'LEAD', ?)").run(id, owner.id, t);
    audit(orgId, "team.create", id, name);
    return id;
  };
  const channel = (name: string, topic: string, scope: string, scopeId: string | null, kind: string, autoJoin: boolean) => {
    const id = newId("c");
    db.prepare(
      `INSERT INTO conversations (id, type, org_id, scope, scope_id, kind, name, topic, auto_join, created_by, created_at, updated_at)
       VALUES (?, 'CHANNEL', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).run(id, orgId, scope, scopeId, kind, name, topic, autoJoin ? 1 : 0, owner.id, t, t);
    db.prepare("INSERT INTO conversation_members (conversation_id, user_id, role, joined_at) VALUES (?, ?, 'ADMIN', ?)").run(id, owner.id, t);
    audit(orgId, "channel.create", id, `${kind} ${name}`);
    return id;
  };

  const bd = branch("Bangladesh", "Dhaka");
  const intl = branch("International", "Remote");
  const bdOps = dept(bd, "Operations");
  const bdCx = dept(bd, "Customer Experience");
  const intlGrowth = dept(intl, "Growth");
  const ops = team("Operations", bd, bdOps, "Daily operations, logistics and vendor coordination");
  const support = team("Customer Support", bd, bdCx, "Tickets, escalations and customer follow-ups");
  const marketing = team("Marketing", intl, intlGrowth, "Campaigns, content and partnerships");
  const product = team("Product & Development", null, null, "Cross-branch product, engineering and design");
  const community = team("Community & Gaming", null, null, "Community test group for voice rooms and screen sharing");

  channel("announcements", "Company-wide announcements", "ORG", null, "ANNOUNCEMENT", true);
  channel("general", "Company-wide chat", "ORG", null, "PUBLIC", true);
  channel("management", "Private: owners, admins and branch managers", "ORG", null, "PRIVATE", false);
  channel("bd-announcements", "Bangladesh branch announcements", "BRANCH", bd, "ANNOUNCEMENT", true);
  channel("intl-announcements", "International branch announcements", "BRANCH", intl, "ANNOUNCEMENT", true);
  channel("ops-floor", "Operations team discussion", "TEAM", ops, "PUBLIC", true);
  channel("support-desk", "Customer Support team discussion", "TEAM", support, "PUBLIC", true);
  channel("marketing", "Marketing team discussion", "TEAM", marketing, "PUBLIC", true);
  channel("product-dev", "Product & Development discussion", "TEAM", product, "PUBLIC", true);
  channel("voice-lounge", "Community voice room & screen sharing tests", "TEAM", community, "PUBLIC", true);

  const invite = (label: string, branchId: string | null, teamId: string | null) => {
    const code = humanCode(12);
    db.prepare(
      `INSERT INTO invitations (id, org_id, code_hash, code_hint, role, branch_id, team_id, max_uses, created_by, created_at, expires_at)
       VALUES (?, ?, ?, ?, 'MEMBER', ?, ?, 50, ?, ?, ?)`,
    ).run(newId("inv"), orgId, sha256Hex(code), code.slice(-4), branchId, teamId, owner.id, t, t + 14 * 24 * 3600_000);
    codes.push(`${label.padEnd(34)} ${code.slice(0, 4)}-${code.slice(4, 8)}-${code.slice(8)}`);
  };
  invite("Bangladesh · Operations", bd, ops);
  invite("Bangladesh · Customer Support", bd, support);
  invite("International · Marketing", intl, marketing);
  invite("Org-wide member (no branch)", null, null);
})();
authz.autoJoinScope(orgId, owner.id, t);
console.log(`Seeded GriipBuddy (${orgId}) owned by @${ownerName}.`);
console.log("Invite codes (50 uses, 14 days):");
for (const c of codes) console.log("  " + c);
db.close();
