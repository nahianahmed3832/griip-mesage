import type { AppContext } from "../context.js";
import { ApiError, badRequest, conflict, forbidden, newId, notFound, now } from "../util.js";
import { atLeast, DOC_LIMITS, type DocRole, higher, type Sharing, snippet } from "./model.js";

/**
 * Workspace documents (docs/DOCUMENTS.md, round 7 slice C4). Every read and write goes through [roleOf]: someone without
 * a role gets 404, never a hint that the document exists. Archived documents are read-only until restored.
 */

export interface DocRow {
  id: string;
  org_id: string;
  title: string;
  content: string;
  access: "workspace" | "channel" | "restricted";
  default_role: "view" | "comment" | "edit";
  channel_id: string | null;
  created_by: string;
  updated_by: string;
  rev: number;
  archived_at: number | null;
  created_at: number;
  updated_at: number;
}

interface CommentRow {
  id: string;
  document_id: string;
  author_id: string;
  body: string;
  quote: string | null;
  resolved_at: number | null;
  resolved_by: string | null;
  created_at: number;
  updated_at: number;
}

export class DocumentService {
  constructor(private readonly ctx: AppContext) {}

  private get db() {
    return this.ctx.db;
  }

  /** The person's role on [d]: from being its creator, being listed, the workspace, or the linked channel; else null. */
  roleOf(d: DocRow, userId: string): DocRole | null {
    const { authz } = this.ctx;
    const orgRole = authz.orgRole(d.org_id, userId);
    if (!orgRole) return null; // only people in the workspace, whatever is listed
    let role: DocRole | null = d.created_by === userId ? "admin" : null;
    const listed = this.db.prepare("SELECT role FROM document_members WHERE document_id = ? AND user_id = ?").get(d.id, userId) as { role: DocRole } | undefined;
    role = higher(role, listed?.role ?? null);
    if (d.access === "workspace" && orgRole !== "GUEST") role = higher(role, d.default_role);
    if (d.access === "channel" && d.channel_id) {
      const c = authz.conversation(d.channel_id);
      if (c && authz.isMember(c.id, userId)) role = higher(role, authz.canManageConversation(c, userId) ? "admin" : d.default_role);
    }
    return role;
  }

  private load(id: string): DocRow | undefined {
    return this.db.prepare("SELECT * FROM documents WHERE id = ?").get(id) as DocRow | undefined;
  }

  /** The document and the person's role, or 404 (also when they have no role); [needed] or 403. */
  require(id: string, userId: string, needed: DocRole = "view"): { d: DocRow; role: DocRole } {
    const d = this.load(id);
    const role = d ? this.roleOf(d, userId) : null;
    if (!d || !role) throw notFound("Document not found");
    if (!atLeast(role, needed)) throw forbidden(needed === "comment" ? "You can only read this document" : needed === "edit" ? "You can't edit this document" : "Only the document's admins can do that");
    return { d, role };
  }

  private requireWritable(d: DocRow): void {
    if (d.archived_at) throw conflict("This document is archived. Restore it to change it.");
  }

  private userName(id: string): string | null {
    return (this.db.prepare("SELECT display_name FROM users WHERE id = ?").get(id) as { display_name: string } | undefined)?.display_name ?? null;
  }

  summary(d: DocRow, role: DocRole) {
    const channel = d.channel_id ? this.ctx.authz.conversation(d.channel_id) : undefined;
    return {
      id: d.id,
      orgId: d.org_id,
      title: d.title,
      access: d.access,
      defaultRole: d.default_role,
      channel: channel ? { id: channel.id, name: channel.name } : null,
      role,
      rev: d.rev,
      archived: d.archived_at !== null,
      createdBy: { id: d.created_by, name: this.userName(d.created_by) },
      updatedBy: { id: d.updated_by, name: this.userName(d.updated_by) },
      createdAt: d.created_at,
      updatedAt: d.updated_at,
      // Always shown with the document: what the server can read (docs/DOCUMENTS.md).
      encryption: "server-readable",
    };
  }

  view(d: DocRow, role: DocRole) {
    const members = atLeast(role, "admin")
      ? (this.db.prepare("SELECT m.user_id, m.role, u.display_name, u.username FROM document_members m JOIN users u ON u.id = m.user_id WHERE m.document_id = ? ORDER BY m.added_at").all(d.id) as {
          user_id: string;
          role: DocRole;
          display_name: string;
          username: string;
        }[]).map((m) => ({ userId: m.user_id, role: m.role, name: m.display_name, username: m.username }))
      : undefined;
    return { ...this.summary(d, role), content: d.content, members };
  }

  list(orgId: string, userId: string, opts: { archived: boolean; channelId?: string }) {
    this.ctx.authz.requireOrgMember(orgId, userId);
    const rows = this.db
      .prepare(`SELECT * FROM documents WHERE org_id = ? AND (archived_at IS NOT NULL) = ? AND (? IS NULL OR channel_id = ?) ORDER BY updated_at DESC LIMIT 500`)
      .all(orgId, opts.archived ? 1 : 0, opts.channelId ?? null, opts.channelId ?? null) as DocRow[];
    return rows.flatMap((d) => {
      const role = this.roleOf(d, userId);
      return role ? [this.summary(d, role)] : [];
    });
  }

  /** Checks sharing settings against the workspace: the linked channel and everyone listed must belong to it. */
  private checkSharing(orgId: string, actorId: string, s: Sharing): void {
    const { authz } = this.ctx;
    if (s.access === "channel") {
      const c = s.channelId ? authz.conversation(s.channelId) : undefined;
      if (!c || c.type !== "CHANNEL" || c.org_id !== orgId || !authz.isMember(c.id, actorId)) throw badRequest("Pick a channel of this workspace that you're in");
    }
    for (const m of s.members) {
      if (!authz.orgRole(orgId, m.userId)) throw badRequest("Everyone you share with must be in this workspace", { userId: m.userId });
    }
  }

  private writeMembers(docId: string, actorId: string, members: Sharing["members"], t: number): void {
    this.db.prepare("DELETE FROM document_members WHERE document_id = ?").run(docId);
    const ins = this.db.prepare("INSERT OR REPLACE INTO document_members (document_id, user_id, role, added_by, added_at) VALUES (?, ?, ?, ?, ?)");
    for (const m of members) ins.run(docId, m.userId, m.role, actorId, t);
  }

  private keepVersion(d: { id: string; rev: number; title: string; content: string }, authorId: string, t: number): void {
    this.db.prepare("INSERT INTO document_versions (id, document_id, rev, title, content, author_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)").run(newId("dv"), d.id, d.rev, d.title, d.content, authorId, t);
    this.db
      .prepare("DELETE FROM document_versions WHERE document_id = ? AND rev NOT IN (SELECT rev FROM document_versions WHERE document_id = ? ORDER BY rev DESC LIMIT ?)")
      .run(d.id, d.id, DOC_LIMITS.versionsKept);
  }

  create(orgId: string, userId: string, input: Sharing & { title: string; content: string }) {
    const orgRole = this.ctx.authz.requireOrgMember(orgId, userId);
    if (orgRole === "GUEST") throw forbidden("Guests can't create documents");
    this.ctx.entitlements.check("documents", (this.db.prepare("SELECT COUNT(*) n FROM documents WHERE org_id = ?").get(orgId) as { n: number }).n, orgId);
    this.checkSharing(orgId, userId, input);
    const id = newId("doc");
    const t = now();
    this.db.transaction(() => {
      this.db
        .prepare(
          `INSERT INTO documents (id, org_id, title, content, access, default_role, channel_id, created_by, updated_by, rev, created_at, updated_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)`,
        )
        .run(id, orgId, input.title, input.content, input.access, input.defaultRole, input.access === "channel" ? input.channelId : null, userId, userId, t, t);
      this.writeMembers(id, userId, input.members.filter((m) => m.userId !== userId), t);
      this.keepVersion({ id, rev: 1, title: input.title, content: input.content }, userId, t);
    })();
    this.ctx.audit({ actorId: userId, orgId, action: "document.create", target: id, detail: input.access });
    // Webhooks hear only about documents shared with the whole workspace (docs/DEVELOPER_API.md).
    if (input.access === "workspace") this.ctx.developer?.emit(orgId, "document.created", `doc:${id}:1`, { document: { id, title: input.title } });
    const d = this.load(id) as DocRow;
    return this.view(d, "admin");
  }

  update(id: string, userId: string, input: { rev: number; title: string; content: string }) {
    const { d } = this.require(id, userId, "edit");
    this.requireWritable(d);
    if (d.rev !== input.rev) throw new ApiError(409, "stale_revision", "Someone saved this document meanwhile. Reload it to see their changes, then edit again.", { rev: d.rev });
    const t = now();
    this.db.transaction(() => {
      this.db.prepare("UPDATE documents SET title = ?, content = ?, rev = rev + 1, updated_by = ?, updated_at = ? WHERE id = ?").run(input.title, input.content, userId, t, id);
      this.keepVersion({ id, rev: d.rev + 1, title: input.title, content: input.content }, userId, t);
    })();
    const fresh = this.load(id) as DocRow;
    if (fresh.access === "workspace") this.ctx.developer?.emit(fresh.org_id, "document.updated", `doc:${id}:${fresh.rev}`, { document: { id, title: fresh.title, rev: fresh.rev } });
    return this.view(fresh, this.roleOf(fresh, userId) as DocRole);
  }

  share(id: string, userId: string, s: Sharing) {
    const { d } = this.require(id, userId, "admin");
    this.checkSharing(d.org_id, userId, s);
    const t = now();
    this.db.transaction(() => {
      this.db.prepare("UPDATE documents SET access = ?, default_role = ?, channel_id = ?, updated_at = ? WHERE id = ?").run(s.access, s.defaultRole, s.access === "channel" ? s.channelId : null, t, id);
      // The person changing it keeps admin even if they left themselves out.
      const members = s.members.some((m) => m.userId === userId) || d.created_by === userId ? s.members : [...s.members, { userId, role: "admin" as const }];
      this.writeMembers(id, userId, members, t);
    })();
    this.ctx.audit({ actorId: userId, orgId: d.org_id, action: "document.share", target: id, detail: s.access });
    const fresh = this.load(id) as DocRow;
    return this.view(fresh, this.roleOf(fresh, userId) as DocRole);
  }

  setArchived(id: string, userId: string, archived: boolean) {
    const { d } = this.require(id, userId, "admin");
    this.db.prepare("UPDATE documents SET archived_at = ?, updated_at = ? WHERE id = ?").run(archived ? now() : null, now(), id);
    this.ctx.audit({ actorId: userId, orgId: d.org_id, action: archived ? "document.archive" : "document.restore", target: id });
    const fresh = this.load(id) as DocRow;
    return this.view(fresh, this.roleOf(fresh, userId) as DocRole);
  }

  remove(id: string, userId: string): void {
    const { d } = this.require(id, userId, "admin");
    this.db.prepare("DELETE FROM documents WHERE id = ?").run(id);
    this.ctx.audit({ actorId: userId, orgId: d.org_id, action: "document.delete", target: id });
  }

  versions(id: string, userId: string) {
    this.require(id, userId);
    return (this.db.prepare("SELECT rev, title, author_id, created_at FROM document_versions WHERE document_id = ? ORDER BY rev DESC").all(id) as {
      rev: number;
      title: string;
      author_id: string;
      created_at: number;
    }[]).map((v) => ({ rev: v.rev, title: v.title, author: { id: v.author_id, name: this.userName(v.author_id) }, createdAt: v.created_at }));
  }

  version(id: string, userId: string, rev: number) {
    this.require(id, userId);
    const v = this.db.prepare("SELECT rev, title, content, author_id, created_at FROM document_versions WHERE document_id = ? AND rev = ?").get(id, rev) as
      | { rev: number; title: string; content: string; author_id: string; created_at: number }
      | undefined;
    if (!v) throw notFound("Version not found");
    return { rev: v.rev, title: v.title, content: v.content, author: { id: v.author_id, name: this.userName(v.author_id) }, createdAt: v.created_at };
  }

  /** Brings an earlier version back as a new revision (nothing is lost: the current one stays in the history). */
  restoreVersion(id: string, userId: string, rev: number) {
    const { d } = this.require(id, userId, "edit");
    const v = this.version(id, userId, rev);
    return this.update(id, userId, { rev: d.rev, title: v.title, content: v.content });
  }

  comments(id: string, userId: string) {
    const { role } = this.require(id, userId);
    return (this.db.prepare("SELECT * FROM document_comments WHERE document_id = ? ORDER BY created_at").all(id) as CommentRow[]).map((c) => ({
      id: c.id,
      author: { id: c.author_id, name: this.userName(c.author_id) },
      body: c.body,
      quote: c.quote,
      resolved: c.resolved_at !== null,
      createdAt: c.created_at,
      canDelete: c.author_id === userId || atLeast(role, "admin"),
    }));
  }

  comment(id: string, userId: string, input: { body: string; quote?: string | null }) {
    const { d } = this.require(id, userId, "comment");
    this.requireWritable(d);
    const t = now();
    const cid = newId("dc");
    this.db.prepare("INSERT INTO document_comments (id, document_id, author_id, body, quote, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)").run(cid, id, userId, input.body, input.quote ?? null, t, t);
    return this.comments(id, userId).find((c) => c.id === cid);
  }

  private commentRow(cid: string): CommentRow {
    const c = this.db.prepare("SELECT * FROM document_comments WHERE id = ?").get(cid) as CommentRow | undefined;
    if (!c) throw notFound("Comment not found");
    return c;
  }

  resolveComment(cid: string, userId: string, resolved: boolean): void {
    const c = this.commentRow(cid);
    this.require(c.document_id, userId, "comment");
    this.db.prepare("UPDATE document_comments SET resolved_at = ?, resolved_by = ?, updated_at = ? WHERE id = ?").run(resolved ? now() : null, resolved ? userId : null, now(), cid);
  }

  deleteComment(cid: string, userId: string): void {
    const c = this.commentRow(cid);
    const { role } = this.require(c.document_id, userId);
    if (c.author_id !== userId && !atLeast(role, "admin")) throw forbidden("Only its author or the document's admins can delete a comment");
    this.db.prepare("DELETE FROM document_comments WHERE id = ?").run(cid);
  }

  /** Documents whose title or text contains [q], with an excerpt, for the people who may read them (search). */
  search(userId: string, q: string, orgId?: string) {
    const like = `%${q.toLowerCase().replace(/[%_\\]/g, (ch) => "\\" + ch)}%`;
    const rows = this.db
      .prepare(
        `SELECT * FROM documents WHERE archived_at IS NULL
           AND org_id IN (SELECT org_id FROM org_members WHERE user_id = ?) AND (? IS NULL OR org_id = ?)
           AND (lower(title) LIKE ? ESCAPE '\\' OR lower(content) LIKE ? ESCAPE '\\')
         ORDER BY updated_at DESC LIMIT 200`,
      )
      .all(userId, orgId ?? null, orgId ?? null, like, like) as DocRow[];
    return rows.flatMap((d) => {
      const role = this.roleOf(d, userId);
      if (!role) return [];
      const titleHit = d.title.toLowerCase().includes(q.toLowerCase());
      return [{ rank: titleHit ? 0 : 1, item: { ...this.summary(d, role), snippet: snippet(d.content, q) } }];
    });
  }
}
