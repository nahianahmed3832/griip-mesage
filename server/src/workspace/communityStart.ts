import type { ConversationRow } from "../authz.js";
import type { AppContext } from "../context.js";
import { notifyConversation } from "../routes/orgs.js";
import { newId, now } from "../util.js";

/**
 * The channels a new community starts with, so the first person who joins finds somewhere to go. Each starter
 * template (batch B2, Discord reference CREATE-CTRL-001) is a set of categories and channels; "starter" is the set every
 * community got before templates: a welcome and an announcements channel, general chat, introductions and a voice
 * channel. Added once, by POST /v1/orgs; nothing is added to an existing community.
 */
interface ChannelSpec {
  name: string;
  topic: string;
  kind: "PUBLIC" | "ANNOUNCEMENT";
  voice?: boolean;
  format?: "text" | "forum";
}

interface CategorySpec {
  category: string;
  channels: readonly ChannelSpec[];
}

export const TEMPLATE_IDS = ["starter", "friends", "gaming", "study", "club"] as const;
export type TemplateId = (typeof TEMPLATE_IDS)[number];

const welcome: ChannelSpec = { name: "welcome", topic: "What this community is about and how it works", kind: "ANNOUNCEMENT" };
const announcements: ChannelSpec = { name: "announcements", topic: "News from the people who run the community", kind: "ANNOUNCEMENT" };
const general: ChannelSpec = { name: "general", topic: "Everyday conversation", kind: "PUBLIC" };

export const COMMUNITY_TEMPLATES: Readonly<Record<TemplateId, readonly CategorySpec[]>> = {
  starter: [
    { category: "Start here", channels: [welcome, announcements] },
    {
      category: "Talk",
      channels: [
        general,
        { name: "introductions", topic: "Say hello and tell people what brings you here", kind: "PUBLIC" },
        { name: "lounge", topic: "Drop in to talk", kind: "PUBLIC", voice: true },
      ],
    },
  ],
  friends: [
    {
      category: "Hang out",
      channels: [
        general,
        { name: "photos", topic: "Pictures and videos to share", kind: "PUBLIC" },
        { name: "plans", topic: "What we're doing and when", kind: "PUBLIC" },
      ],
    },
    { category: "Voice", channels: [{ name: "hangout", topic: "Drop in to talk", kind: "PUBLIC", voice: true }] },
  ],
  gaming: [
    { category: "Start here", channels: [welcome, announcements] },
    {
      category: "Play",
      channels: [
        general,
        { name: "looking-for-group", topic: "Find people to play with", kind: "PUBLIC" },
        { name: "clips", topic: "Highlights, screenshots and clips", kind: "PUBLIC", format: "forum" },
      ],
    },
    {
      category: "Voice",
      channels: [
        { name: "lobby", topic: "Hang out between games", kind: "PUBLIC", voice: true },
        { name: "squad", topic: "Play together", kind: "PUBLIC", voice: true },
      ],
    },
  ],
  study: [
    { category: "Start here", channels: [welcome, announcements] },
    {
      category: "Study",
      channels: [
        general,
        { name: "homework-help", topic: "Ask a question, help someone out", kind: "PUBLIC" },
        { name: "resources", topic: "Notes, links and guides worth keeping", kind: "PUBLIC", format: "forum" },
        { name: "study-room", topic: "Study together", kind: "PUBLIC", voice: true },
      ],
    },
  ],
  club: [
    { category: "Start here", channels: [welcome, announcements] },
    {
      category: "Club",
      channels: [
        general,
        { name: "introductions", topic: "Say hello and tell people what brings you here", kind: "PUBLIC" },
        { name: "ideas", topic: "Suggestions and plans for the club", kind: "PUBLIC", format: "forum" },
        { name: "meeting-room", topic: "Meetings and calls", kind: "PUBLIC", voice: true },
      ],
    },
  ],
};

/** Kept for code that reads the original set directly. */
export const COMMUNITY_START = COMMUNITY_TEMPLATES.starter;

/** The templates as GET /v1/community-templates lists them. */
export function templateList(): { id: TemplateId; categories: { name: string; channels: { name: string; voice: boolean; format: string; kind: string }[] }[] }[] {
  return TEMPLATE_IDS.map((id) => ({
    id,
    categories: COMMUNITY_TEMPLATES[id].map((g) => ({
      name: g.category,
      channels: g.channels.map((ch) => ({ name: ch.name, voice: !!ch.voice, format: ch.format ?? "text", kind: ch.kind })),
    })),
  }));
}

/** Creates [template]'s categories and channels for a community [ownerId] just made. Returns the channel ids. */
export function createCommunityStart(ctx: AppContext, orgId: string, ownerId: string, template: TemplateId = "starter"): string[] {
  const { db, authz } = ctx;
  const created: string[] = [];
  const t = now();
  db.transaction(() => {
    COMMUNITY_TEMPLATES[template].forEach((group, g) => {
      const categoryId = newId("cat");
      db.prepare("INSERT INTO channel_categories (id, org_id, name, position, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)").run(
        categoryId, orgId, group.category, g, ownerId, t, t,
      );
      group.channels.forEach((ch, i) => {
        const id = newId("c");
        db.prepare(
          `INSERT INTO conversations (id, type, org_id, scope, scope_id, kind, name, topic, auto_join, created_by, created_at, updated_at, voice, category_id, position, format)
           VALUES (?, 'CHANNEL', ?, 'ORG', NULL, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?)`,
        ).run(id, orgId, ch.kind, ch.name, ch.topic, ownerId, t, t, ch.voice ? 1 : 0, categoryId, i, ch.format ?? "text");
        db.prepare("INSERT INTO conversation_members (conversation_id, user_id, role, joined_at) VALUES (?, ?, 'ADMIN', ?)").run(id, ownerId, t);
        const conv = authz.conversation(id) as ConversationRow;
        for (const r of db.prepare("SELECT user_id FROM org_members WHERE org_id = ? AND user_id != ?").all(orgId, ownerId) as { user_id: string }[]) {
          if (authz.scopeEligible(conv, r.user_id)) db.prepare("INSERT OR IGNORE INTO conversation_members (conversation_id, user_id, role, joined_at) VALUES (?, ?, 'MEMBER', ?)").run(id, r.user_id, t);
        }
        created.push(id);
      });
    });
  })();
  for (const id of created) notifyConversation(ctx, id);
  ctx.audit({ actorId: ownerId, orgId, action: "community.start", detail: `${template}: ${created.length} channels` });
  return created;
}
