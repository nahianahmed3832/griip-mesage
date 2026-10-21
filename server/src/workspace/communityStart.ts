import type { ConversationRow } from "../authz.js";
import type { AppContext } from "../context.js";
import { notifyConversation } from "../routes/orgs.js";
import { newId, now } from "../util.js";

/**
 * The channels a new community starts with, so the first person who joins finds somewhere to go: a welcome and an
 * announcements channel, general chat, introductions and a voice channel, in a "Start here" and a "Talk" category.
 * Added once, by POST /v1/orgs; nothing is added to an existing community.
 */
interface ChannelSpec {
  name: string;
  topic: string;
  kind: "PUBLIC" | "ANNOUNCEMENT";
  voice?: boolean;
}

export const COMMUNITY_START: readonly { category: string; channels: readonly ChannelSpec[] }[] = [
  {
    category: "Start here",
    channels: [
      { name: "welcome", topic: "What this community is about and how it works", kind: "ANNOUNCEMENT" },
      { name: "announcements", topic: "News from the people who run the community", kind: "ANNOUNCEMENT" },
    ],
  },
  {
    category: "Talk",
    channels: [
      { name: "general", topic: "Everyday conversation", kind: "PUBLIC" },
      { name: "introductions", topic: "Say hello and tell people what brings you here", kind: "PUBLIC" },
      { name: "lounge", topic: "Drop in to talk", kind: "PUBLIC", voice: true },
    ],
  },
];

/** Creates the starter categories and channels for a community [ownerId] just made. Returns the channel ids. */
export function createCommunityStart(ctx: AppContext, orgId: string, ownerId: string): string[] {
  const { db, authz } = ctx;
  const created: string[] = [];
  const t = now();
  db.transaction(() => {
    COMMUNITY_START.forEach((group, g) => {
      const categoryId = newId("cat");
      db.prepare("INSERT INTO channel_categories (id, org_id, name, position, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)").run(
        categoryId, orgId, group.category, g, ownerId, t, t,
      );
      group.channels.forEach((ch, i) => {
        const id = newId("c");
        db.prepare(
          `INSERT INTO conversations (id, type, org_id, scope, scope_id, kind, name, topic, auto_join, created_by, created_at, updated_at, voice, category_id, position)
           VALUES (?, 'CHANNEL', ?, 'ORG', NULL, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?)`,
        ).run(id, orgId, ch.kind, ch.name, ch.topic, ownerId, t, t, ch.voice ? 1 : 0, categoryId, i);
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
  ctx.audit({ actorId: ownerId, orgId, action: "community.start", detail: `${created.length} channels` });
  return created;
}
