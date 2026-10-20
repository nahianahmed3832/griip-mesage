import { z } from "zod";

/**
 * Workspace documents (docs/DOCUMENTS.md, round 7 slice C4): roles, limits, validation and templates. Pure.
 * Documents are readable by the server (the owner's decision) and the apps say so; messages stay end-to-end encrypted.
 */

export const DOC_ROLES = ["view", "comment", "edit", "admin"] as const;
export type DocRole = (typeof DOC_ROLES)[number];
export const ACCESS = ["workspace", "channel", "restricted"] as const;
export type DocAccess = (typeof ACCESS)[number];

export const DOC_LIMITS = {
  titleChars: 120,
  /** About 50 pages of text. */
  contentChars: 200_000,
  commentChars: 2_000,
  quoteChars: 300,
  membersPerDoc: 200,
  versionsKept: 50,
} as const;

const RANK: Record<DocRole, number> = { view: 0, comment: 1, edit: 2, admin: 3 };

/** Whether [role] is at least [needed]. */
export function atLeast(role: DocRole | null, needed: DocRole): boolean {
  return role !== null && RANK[role] >= RANK[needed];
}

/** The higher of two roles (null for none). */
export function higher(a: DocRole | null, b: DocRole | null): DocRole | null {
  if (a === null) return b;
  if (b === null) return a;
  return RANK[a] >= RANK[b] ? a : b;
}

const zMember = z.object({ userId: z.string().min(1).max(64), role: z.enum(DOC_ROLES) });

export const zSharing = z.object({
  access: z.enum(ACCESS),
  defaultRole: z.enum(["view", "comment", "edit"]).default("comment"),
  channelId: z.string().max(64).nullish(),
  members: z.array(zMember).max(DOC_LIMITS.membersPerDoc).default([]),
});
export type Sharing = z.infer<typeof zSharing>;

export const zCreate = zSharing.extend({
  title: z.string().trim().min(1).max(DOC_LIMITS.titleChars),
  content: z.string().max(DOC_LIMITS.contentChars).default(""),
});

export const zUpdate = z.object({
  rev: z.number().int().min(1),
  title: z.string().trim().min(1).max(DOC_LIMITS.titleChars),
  content: z.string().max(DOC_LIMITS.contentChars),
});

export const zComment = z.object({
  body: z.string().trim().min(1).max(DOC_LIMITS.commentChars),
  quote: z.string().trim().max(DOC_LIMITS.quoteChars).nullish(),
});

/**
 * A short excerpt around the first match of [q] in [text] for search results: plain text, at most ~160 characters,
 * from content the searcher may already read.
 */
export function snippet(text: string, q: string, width = 160): string {
  const flat = text.replace(/[#>*_`[\]]/g, "").replace(/\s+/g, " ").trim();
  const i = flat.toLowerCase().indexOf(q.toLowerCase());
  if (i < 0) return flat.slice(0, width);
  const start = Math.max(0, i - Math.floor((width - q.length) / 2));
  const out = flat.slice(start, start + width);
  return `${start > 0 ? "…" : ""}${out}${start + width < flat.length ? "…" : ""}`;
}

/** Starting points; the person edits them freely. */
export const DOC_TEMPLATES = [
  { id: "blank", title: "Untitled", content: "" },
  {
    id: "meeting",
    title: "Meeting notes",
    content: "# Meeting notes\n\n**Date:** \n**Attendees:** \n\n## Agenda\n- \n\n## Notes\n- \n\n## Decisions\n- \n\n## Action items\n- [ ] Who · what · by when\n",
  },
  {
    id: "brief",
    title: "Project brief",
    content: "# Project brief\n\n## Goal\nWhat this project achieves and why it matters.\n\n## Scope\n- In: \n- Out: \n\n## Milestones\n- \n\n## Team\n- Owner: \n\n## Risks\n- \n",
  },
  {
    id: "onboarding",
    title: "Onboarding guide",
    content: "# Welcome\n\n## Your first week\n- [ ] Meet your team\n- [ ] Set up your tools\n- [ ] Read the key documents\n\n## Who to ask\n- \n\n## Useful channels\n- \n",
  },
  {
    id: "sop",
    title: "Standard operating procedure",
    content: "# Procedure name\n\n**Purpose:** \n**Owner:** \n**Last reviewed:** \n\n## When to use it\n\n## Steps\n1. \n2. \n3. \n\n## If something goes wrong\n",
  },
  { id: "faq", title: "Team FAQ", content: "# Frequently asked questions\n\n## Question?\nAnswer.\n\n## Question?\nAnswer.\n" },
  {
    id: "policy",
    title: "Policy",
    content: "# Policy name\n\n**Applies to:** \n**Effective from:** \n\n## Purpose\n\n## Policy\n\n## Exceptions\n\n## Questions\nAsk in \n",
  },
] as const;
