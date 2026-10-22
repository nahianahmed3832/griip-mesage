/**
 * DEVELOPMENT SEED — creates the "Meldiq Lab" sample community owned by an existing user: categories, text, forum,
 * announcement and voice channels, a role-gated category and invitations.
 *
 *   node dist/seed.js --owner <username>
 *
 * Refuses to run when NODE_ENV=production unless --force is passed. Seeded rows are tagged in
 * the audit log ("seed.dev") so they can be identified; no sample users or messages are created.
 */
import { Authz } from "./modules/organizations/authz.js";
import { loadConfig } from "./app/config.js";
import { openDatabase } from "./infrastructure/database/db.js";
import { humanCode, newId, now, sha256Hex } from "./shared/utilities/util.js";

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
if (db.prepare("SELECT 1 FROM orgs WHERE slug = 'meldiq-lab'").get()) {
  console.error("Meldiq Lab already exists — nothing to do.");
  process.exit(0);
}
const t = now();
const audit = (orgId: string, action: string, target: string, detail: string) =>
  db.prepare("INSERT INTO audit_log (at, actor_id, org_id, action, target, detail) VALUES (?, ?, ?, ?, ?, ?)").run(t, owner.id, orgId, action, target, detail);

const orgId = newId("o");
const codes: string[] = [];
db.transaction(() => {
  db.prepare(
    `INSERT INTO orgs (id, name, slug, description, created_by, created_at, updated_at, kind, visibility, join_policy, rules)
     VALUES (?, 'Meldiq Lab', 'meldiq-lab', ?, ?, ?, ?, 'community', 'public', 'open', ?)`,
  ).run(orgId, "A sample community for trying Meldiq (development seed)", owner.id, t, t, "1. Be kind.\n2. Stay on topic.\n3. No spam or self-promotion.");
  db.prepare("INSERT INTO org_members (org_id, user_id, role, joined_at) VALUES (?, ?, 'OWNER', ?)").run(orgId, owner.id, t);
  audit(orgId, "seed.dev", orgId, "community");

  let categoryPosition = 0;
  const category = (name: string) => {
    const id = newId("cat");
    db.prepare("INSERT INTO channel_categories (id, org_id, name, position, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)").run(id, orgId, name, categoryPosition++, owner.id, t, t);
    audit(orgId, "category.create", id, name);
    return id;
  };
  const positions = new Map<string, number>();
  const channel = (categoryId: string, name: string, topic: string, kind: string, opts: { voice?: boolean; format?: "text" | "forum" } = {}) => {
    const id = newId("c");
    const position = positions.get(categoryId) ?? 0;
    positions.set(categoryId, position + 1);
    db.prepare(
      `INSERT INTO conversations (id, type, org_id, scope, scope_id, kind, name, topic, auto_join, created_by, created_at, updated_at, voice, category_id, position, format)
       VALUES (?, 'CHANNEL', ?, 'ORG', NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).run(id, orgId, kind, name, topic, kind === "PRIVATE" ? 0 : 1, owner.id, t, t, opts.voice ? 1 : 0, categoryId, position, opts.format ?? "text");
    db.prepare("INSERT INTO conversation_members (conversation_id, user_id, role, joined_at) VALUES (?, ?, 'ADMIN', ?)").run(id, owner.id, t);
    audit(orgId, opts.voice ? "voice_room.create" : "channel.create", id, `${kind} ${name}`);
    return id;
  };

  const start = category("Start here");
  channel(start, "welcome", "What this community is about", "ANNOUNCEMENT");
  channel(start, "rules", "Read these before posting", "ANNOUNCEMENT");
  channel(start, "announcements", "News from the moderators", "ANNOUNCEMENT");
  const talk = category("Talk");
  channel(talk, "general", "Everyday conversation", "PUBLIC");
  channel(talk, "introductions", "Say hello", "PUBLIC");
  channel(talk, "help", "Ask a question: one post per question", "PUBLIC", { format: "forum" });
  channel(talk, "media", "Photos, videos and files", "PUBLIC");
  const voice = category("Voice");
  channel(voice, "lounge", "Drop in to talk", "PUBLIC", { voice: true });
  channel(voice, "screen-share", "Voice room for screen sharing tests", "PUBLIC", { voice: true });

  // A category only moderators see, gated with a role and two overwrites.
  const modRole = newId("role");
  db.prepare(
    `INSERT INTO org_roles (id, org_id, name, description, color, icon, permissions, denies, template, position, rev, created_by, created_at, updated_at)
     VALUES (?, ?, 'Moderator', 'Keeps conversations healthy', '#E39B7B', 'shield', ?, '[]', 'moderator', 0, 1, ?, ?, ?)`,
  ).run(modRole, orgId, JSON.stringify(["channels.manage", "channels.visibility", "messages.announce", "members.remove", "members.ban"]), owner.id, t, t);
  const mods = category("Moderators");
  const overwrite = (subject: string, allow: string[], deny: string[]) =>
    db.prepare(
      "INSERT INTO channel_overwrites (target_type, target_id, org_id, subject, allow, deny, updated_by, updated_at) VALUES ('category', ?, ?, ?, ?, ?, ?, ?)",
    ).run(mods, orgId, subject, JSON.stringify(allow), JSON.stringify(deny), owner.id, t);
  overwrite("everyone", [], ["channels.view"]);
  overwrite(modRole, ["channels.view"], []);
  channel(mods, "mod-chat", "Moderators only", "PUBLIC");
  channel(mods, "reports", "Things to look at", "PUBLIC");

  const invite = (label: string, grantRoleId: string | null) => {
    const code = humanCode(12);
    db.prepare(
      `INSERT INTO invitations (id, org_id, code_hash, code_hint, role, max_uses, created_by, created_at, expires_at, grant_role_id)
       VALUES (?, ?, ?, ?, 'MEMBER', 50, ?, ?, ?, ?)`,
    ).run(newId("inv"), orgId, sha256Hex(code), code.slice(-4), owner.id, t, t + 14 * 24 * 3600_000, grantRoleId);
    codes.push(`${label.padEnd(24)} ${code.slice(0, 4)}-${code.slice(4, 8)}-${code.slice(8)}`);
  };
  invite("Member", null);
  invite("Moderator", modRole);
})();
authz.autoJoinScope(orgId, owner.id, t);
console.log(`Seeded Meldiq Lab (${orgId}) owned by @${ownerName}: public, open to join.`);
console.log("Invite codes (50 uses, 14 days):");
for (const c of codes) console.log("  " + c);
db.close();
