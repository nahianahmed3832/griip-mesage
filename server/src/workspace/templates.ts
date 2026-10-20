import type { ConversationRow } from "../authz.js";
import type { AppContext } from "../context.js";
import { notifyConversation } from "../routes/orgs.js";
import { forbidden, newId, notFound, now } from "../util.js";

/**
 * Workspace templates (docs/WORKSPACE_C5.md, round 7 slice C5): channels (and voice rooms) a kind of team usually
 * wants, plus a welcome document. Applying one only adds what's missing (by channel name), never members, apps, paid
 * services or private channels: running it twice changes nothing.
 */
interface ChannelSpec {
  name: string;
  topic: string;
  kind: "PUBLIC" | "ANNOUNCEMENT";
  voice?: boolean;
}

export const WORKSPACE_TEMPLATES: readonly { id: string; name: string; description: string; channels: readonly ChannelSpec[] }[] = [
  {
    id: "business",
    name: "General business",
    description: "Announcements, general chat, random, and a meeting room.",
    channels: [
      { name: "announcements", topic: "Company news from the people who run the workspace", kind: "ANNOUNCEMENT" },
      { name: "general", topic: "Everyday conversation", kind: "PUBLIC" },
      { name: "random", topic: "Off-topic and fun", kind: "PUBLIC" },
      { name: "meeting-room", topic: "Drop in to talk", kind: "PUBLIC", voice: true },
    ],
  },
  {
    id: "software",
    name: "Software development",
    description: "Engineering, code review, releases, incidents and a stand-up room.",
    channels: [
      { name: "announcements", topic: "Team news", kind: "ANNOUNCEMENT" },
      { name: "engineering", topic: "Day-to-day engineering", kind: "PUBLIC" },
      { name: "code-review", topic: "Ask for reviews, discuss changes", kind: "PUBLIC" },
      { name: "releases", topic: "What shipped and when", kind: "PUBLIC" },
      { name: "incidents", topic: "Outages and how they were fixed", kind: "PUBLIC" },
      { name: "standup", topic: "Daily stand-up", kind: "PUBLIC", voice: true },
    ],
  },
  {
    id: "marketing",
    name: "Marketing",
    description: "Campaigns, content, social and a briefing room.",
    channels: [
      { name: "announcements", topic: "Team news", kind: "ANNOUNCEMENT" },
      { name: "campaigns", topic: "Plans, launches and results", kind: "PUBLIC" },
      { name: "content", topic: "Drafts, reviews and the calendar", kind: "PUBLIC" },
      { name: "social", topic: "Posts and community", kind: "PUBLIC" },
      { name: "briefing-room", topic: "Briefings and reviews", kind: "PUBLIC", voice: true },
    ],
  },
  {
    id: "ecommerce",
    name: "Ecommerce operations",
    description: "Orders, inventory, shipping, customer issues and an ops room.",
    channels: [
      { name: "announcements", topic: "Team news", kind: "ANNOUNCEMENT" },
      { name: "orders", topic: "Order questions and exceptions", kind: "PUBLIC" },
      { name: "inventory", topic: "Stock levels and suppliers", kind: "PUBLIC" },
      { name: "shipping", topic: "Couriers, delays and returns", kind: "PUBLIC" },
      { name: "customer-issues", topic: "Escalations from customers", kind: "PUBLIC" },
      { name: "ops-room", topic: "Daily operations call", kind: "PUBLIC", voice: true },
    ],
  },
  {
    id: "support",
    name: "Customer support",
    description: "Escalations, knowledge, shift handover and a huddle room.",
    channels: [
      { name: "announcements", topic: "Team news", kind: "ANNOUNCEMENT" },
      { name: "escalations", topic: "Tickets that need help", kind: "PUBLIC" },
      { name: "knowledge", topic: "Answers worth keeping", kind: "PUBLIC" },
      { name: "shift-handover", topic: "What the next shift needs to know", kind: "PUBLIC" },
      { name: "huddle", topic: "Quick team calls", kind: "PUBLIC", voice: true },
    ],
  },
  {
    id: "agency",
    name: "Agency or client work",
    description: "Projects, internal discussion, client updates and a review room. Clients join as guests, added to channels one by one.",
    channels: [
      { name: "announcements", topic: "Team news", kind: "ANNOUNCEMENT" },
      { name: "projects", topic: "Across all projects", kind: "PUBLIC" },
      { name: "internal", topic: "Team-only discussion", kind: "PUBLIC" },
      { name: "client-updates", topic: "What we share with clients", kind: "PUBLIC" },
      { name: "review-room", topic: "Reviews with clients", kind: "PUBLIC", voice: true },
    ],
  },
];

const WELCOME = (workspace: string) =>
  `# Welcome to ${workspace}\n\n## Where things are\n- **#announcements**: news from the people who run the workspace\n- Voice rooms: drop in to talk; meetings are scheduled there\n\n## Getting started\n- [ ] Add a photo and your title\n- [ ] Join the channels you need\n- [ ] Read the documents shared with you\n\n## Questions\nAsk in the general channel.\n`;

/** Adds the template's missing channels and a welcome document; returns what it created. */
export function applyWorkspaceTemplate(ctx: AppContext, orgId: string, actorId: string, templateId: string): { channels: string[]; document: string | null } {
  const { db, authz } = ctx;
  if (!authz.can(orgId, actorId, "channels.create") || !authz.can(orgId, actorId, "workspace.edit")) throw forbidden("Only people who can edit the workspace and create channels can apply a template");
  const tpl = WORKSPACE_TEMPLATES.find((x) => x.id === templateId);
  if (!tpl) throw notFound("Template not found");
  const existing = new Set((db.prepare("SELECT lower(name) n FROM conversations WHERE org_id = ? AND type = 'CHANNEL'").all(orgId) as { n: string }[]).map((r) => r.n));
  const created: string[] = [];
  const t = now();
  db.transaction(() => {
    for (const ch of tpl.channels) {
      if (existing.has(ch.name)) continue;
      const id = newId("c");
      db.prepare(
        `INSERT INTO conversations (id, type, org_id, scope, scope_id, kind, name, topic, auto_join, created_by, created_at, updated_at, voice)
         VALUES (?, 'CHANNEL', ?, 'ORG', NULL, ?, ?, ?, 1, ?, ?, ?, ?)`,
      ).run(id, orgId, ch.kind, ch.name, ch.topic, actorId, t, t, ch.voice ? 1 : 0);
      db.prepare("INSERT INTO conversation_members (conversation_id, user_id, role, joined_at) VALUES (?, ?, 'ADMIN', ?)").run(id, actorId, t);
      const conv = authz.conversation(id) as ConversationRow;
      for (const r of db.prepare("SELECT user_id FROM org_members WHERE org_id = ? AND user_id != ?").all(orgId, actorId) as { user_id: string }[]) {
        if (authz.scopeEligible(conv, r.user_id)) db.prepare("INSERT OR IGNORE INTO conversation_members (conversation_id, user_id, role, joined_at) VALUES (?, ?, 'MEMBER', ?)").run(id, r.user_id, t);
      }
      created.push(id);
    }
  })();
  for (const id of created) {
    notifyConversation(ctx, id);
    const c = authz.conversation(id) as ConversationRow;
    ctx.workflows?.emit({ type: "channel.created", key: `channel:${id}`, orgId, depth: 0, channel: { id, name: c.name ?? "", kind: c.kind ?? "PUBLIC", scope: "ORG" } });
  }
  const workspace = (db.prepare("SELECT name FROM orgs WHERE id = ?").get(orgId) as { name: string }).name;
  const hasWelcome = db.prepare("SELECT 1 FROM documents WHERE org_id = ? AND title = ?").get(orgId, `Welcome to ${workspace}`);
  const doc = hasWelcome ? null : ctx.documents.create(orgId, actorId, { title: `Welcome to ${workspace}`, content: WELCOME(workspace), access: "workspace", defaultRole: "comment", members: [] });
  ctx.audit({ actorId, orgId, action: "workspace.template", target: templateId, detail: `${created.length} channels` });
  return { channels: created, document: doc?.id ?? null };
}
