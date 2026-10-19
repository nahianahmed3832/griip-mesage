import { z } from "zod";

/**
 * Personal navigation preferences (docs/WORKSPACE_ADMINISTRATION.md › Personal sections): one document per person and
 * workspace. They only arrange what the person can already see; they never grant access to anything.
 */

/** Conversation ids: "c_…" (local) or "cf_…" (federated DMs). */
export const zConversationId = z.string().min(3).max(64).regex(/^[A-Za-z0-9_-]+$/);
/** Home's built-in groups ("dms", "channels", "branch:<id>", "team:<id>", …): a short opaque key chosen by the app. */
const zGroupKey = z.string().min(1).max(80).regex(/^[A-Za-z0-9_:.-]+$/);
const zSectionId = z.string().min(3).max(40).regex(/^[A-Za-z0-9_-]+$/);
/** Message ids as the server assigns them. */
const zMessageId = z.string().min(3).max(64).regex(/^[A-Za-z0-9_-]+$/);

export const NOTIFY_LEVELS = ["all", "mentions", "none"] as const;
export type NotifyLevel = (typeof NOTIFY_LEVELS)[number];

export const LIMITS = { starred: 200, sections: 30, itemsPerSection: 200, collapsed: 200, notify: 1000, saved: 500 } as const;

export const zSection = z.object({
  id: zSectionId,
  name: z.string().trim().min(1).max(60),
  items: z.array(zConversationId).max(LIMITS.itemsPerSection),
  collapsed: z.boolean().default(false),
});

/**
 * A message saved for later (Home › Later, 2026-10-19): only the ids and times, never the message content (which stays
 * end-to-end encrypted). An optional reminder time; [done] keeps it listed as finished until removed.
 */
export const zSavedItem = z
  .object({
    conversationId: zConversationId,
    messageId: zMessageId,
    savedAt: z.number().int().min(0),
    remindAt: z.number().int().min(0).nullable().default(null),
    done: z.boolean().default(false),
  })
  .strict();

export const zNavPrefs = z
  .object({
    starred: z.array(zConversationId).max(LIMITS.starred).default([]),
    sections: z.array(zSection).max(LIMITS.sections).default([]),
    collapsed: z.array(zGroupKey).max(LIMITS.collapsed).default([]),
    notify: z
      .record(zConversationId, z.enum(NOTIFY_LEVELS))
      .default({})
      .refine((m) => Object.keys(m).length <= LIMITS.notify, { message: `At most ${LIMITS.notify} notification settings` }),
    /**
     * Saved messages. Optional on the wire: a client that doesn't know the field (an older app, the web app) leaves it
     * out, and the stored list is kept (routes.ts) instead of being emptied.
     */
    saved: z.array(zSavedItem).max(LIMITS.saved).optional(),
  })
  .strict();

export type NavSection = z.infer<typeof zSection>;
export type SavedItem = z.infer<typeof zSavedItem>;
export type NavPrefs = z.infer<typeof zNavPrefs>;

export const EMPTY_PREFS: NavPrefs = { starred: [], sections: [], collapsed: [], notify: {}, saved: [] };

/** A stored document: the preferences plus the revision every write must name (optimistic concurrency). */
export interface StoredPrefs {
  userId: string;
  workspaceId: string;
  prefs: NavPrefs;
  rev: number;
  updatedAt: number;
}

/**
 * The canonical form of a document: each conversation listed once across Starred and every section (the first place
 * wins), section ids unique, duplicate group keys dropped, and "all" (the default level) left out of [notify]. [keep]
 * says which conversation ids may stay (the person's own conversations); the rest are dropped rather than refused, so a
 * conversation left on another device never makes a write fail.
 */
export function normalizePrefs(p: NavPrefs, keep: (conversationId: string) => boolean): NavPrefs {
  const seen = new Set<string>();
  const take = (ids: readonly string[]): string[] =>
    ids.filter((id) => {
      if (seen.has(id) || !keep(id)) return false;
      seen.add(id);
      return true;
    });
  const starred = take(p.starred);
  const sectionIds = new Set<string>();
  const sections = p.sections
    .filter((s) => !sectionIds.has(s.id) && (sectionIds.add(s.id), true))
    .map((s) => ({ ...s, items: take(s.items) }));
  const notify = Object.fromEntries(
    Object.entries(p.notify)
      .filter(([id, level]) => level !== "all" && keep(id))
      .sort(([a], [b]) => a.localeCompare(b)),
  ) as Record<string, NotifyLevel>;
  const savedIds = new Set<string>();
  const saved = (p.saved ?? []).filter((item) => keep(item.conversationId) && !savedIds.has(item.messageId) && (savedIds.add(item.messageId), true));
  return { starred, sections, collapsed: [...new Set(p.collapsed)], notify, saved };
}

/** Every conversation id a document mentions. */
export function referencedConversations(p: NavPrefs): string[] {
  return [...new Set([...p.starred, ...p.sections.flatMap((s) => s.items), ...Object.keys(p.notify), ...(p.saved ?? []).map((s) => s.conversationId)])];
}
