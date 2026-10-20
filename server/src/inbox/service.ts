import type { AppContext } from "../context.js";
import type { FetchLike } from "../integrations/http.js";
import { open, seal, type TokenKeyring } from "../integrations/tokens.js";
import { ApiError, badRequest, conflict, forbidden, newId, notFound, now } from "../util.js";
import { InboxAssist } from "./assist.js";
import * as meta from "./meta.js";
import { InboxWorker } from "./worker.js";
import {
  CAPABILITIES,
  DEFAULT_SETTINGS,
  type ConversationStatus,
  type Handling,
  type InboxProvider,
  type InboxSettings,
  replyRule,
  textFits,
  zInboxSettings,
} from "./model.js";

/**
 * The Unified Inbox (docs/UNIFIED_INBOX.md, round 8). Customer conversations from connected business accounts, worked
 * by the workspace's agents. Everything is scoped by workspace: a row is only ever reached through its own workspace,
 * after the permission check. Bodies and tokens are sealed at rest; the views open them only for people allowed to read.
 *
 * Permissions: `inbox.view` (see conversations, notes, assign), `inbox.reply` (write to customers), `inbox.manage`
 * (accounts, queues, settings, AI policy, erasing a customer's data).
 */
export interface AccountRow {
  id: string;
  org_id: string;
  provider: InboxProvider;
  external_id: string;
  name: string;
  meta_json: string;
  token_sealed: string | null;
  token_expires_at: number | null;
  status: "active" | "needs_reauth" | "error" | "disconnected";
  last_error: string | null;
  last_event_at: number | null;
  connected_by: string;
  created_at: number;
  updated_at: number;
}

export interface ConversationRow {
  id: string;
  org_id: string;
  account_id: string;
  contact_id: string;
  provider: InboxProvider;
  status: ConversationStatus;
  priority: string;
  assignee_id: string | null;
  queue_id: string | null;
  handling: Handling;
  escalation_reason: string | null;
  tags_json: string;
  rev: number;
  unread: number;
  last_message_at: number;
  last_inbound_at: number | null;
  first_response_at: number | null;
  sla_notified_at: number | null;
  auto_replies_json: string;
  resolved_at: number | null;
  created_at: number;
  updated_at: number;
}

export interface MessageRow {
  id: string;
  org_id: string;
  conversation_id: string;
  direction: "in" | "out" | "note";
  external_id: string | null;
  author_id: string | null;
  origin: "human" | "ai_auto" | "ai_draft";
  kind: string;
  body_sealed: string | null;
  attachments_json: string;
  status: string;
  error_kind: string | null;
  attempts: number;
  next_attempt_at: number | null;
  provider_ts: number | null;
  created_at: number;
}

interface ContactRow {
  id: string;
  external_user_id: string;
  display_name: string | null;
}

/** "Template: order_update (en_US)" from a template reply's sealed spec. */
export function templateLabel(spec: string | null): string | null {
  try {
    const t = JSON.parse(spec ?? "") as { name?: string; language?: string };
    return t.name ? `Template: ${t.name} (${t.language ?? "?"})` : null;
  } catch {
    return null;
  }
}

export const INBOX_LIMITS = {
  accountsPerOrg: 20,
  queuesPerOrg: 30,
  tagsPerConversation: 10,
  noteChars: 4000,
  pageSize: 50,
  messagesShown: 200,
  sendAttempts: 5,
} as const;

const PURPOSE = { token: "inbox-token", body: "inbox-message", attachment: "inbox-attachment", event: "inbox-event" } as const;

export class InboxService {
  readonly metaCfg: meta.MetaConfig;
  readonly worker: InboxWorker;
  readonly assist: InboxAssist;

  constructor(readonly ctx: AppContext, fetchFn: FetchLike = (u, i) => fetch(u, i)) {
    this.metaCfg = { graphVersion: ctx.config.inbox.graphVersion, fetch: fetchFn };
    meta.useTestBase(ctx.config.inbox.testBaseUrl);
    if (ctx.config.inbox.testBaseUrl) ctx.log.warn("META_TEST_BASE_URL is set: Meta calls go to a local stand-in (development only)");
    this.worker = new InboxWorker(this);
    this.assist = new InboxAssist(this, this.worker);
  }

  get db() {
    return this.ctx.db;
  }

  /** The integrations key seals tokens and bodies; without it nothing can be connected. */
  ring(): TokenKeyring {
    const r = this.ctx.config.integrations.keyring;
    if (!r) throw new ApiError(409, "inbox_needs_key", "This server can't store connected accounts yet (INTEGRATIONS_TOKEN_KEY isn't set)");
    return r;
  }

  sealBody(messageId: string, text: string | null): string | null {
    return text === null ? null : seal(this.ring(), text, PURPOSE.body, messageId);
  }

  openBody(m: Pick<MessageRow, "id" | "body_sealed">): string | null {
    if (!m.body_sealed) return null;
    try {
      return open(this.ring(), m.body_sealed, PURPOSE.body, m.id);
    } catch {
      return null;
    }
  }

  sealAttachmentRef(messageId: string, n: number, ref: string): string {
    return seal(this.ring(), ref, PURPOSE.attachment, `${messageId}:${n}`);
  }

  openAttachmentRef(messageId: string, n: number, sealed: string): string {
    return open(this.ring(), sealed, PURPOSE.attachment, `${messageId}:${n}`);
  }

  sealEvent(id: string, raw: string): string {
    return seal(this.ring(), raw, PURPOSE.event, id);
  }

  openEvent(id: string, sealed: string): string {
    return open(this.ring(), sealed, PURPOSE.event, id);
  }

  token(a: AccountRow): string {
    if (!a.token_sealed) throw new ApiError(409, "inbox_reconnect", "This account needs to be connected again");
    return open(this.ring(), a.token_sealed, PURPOSE.token, a.id);
  }

  // ───────── Permissions ─────────

  requireView(orgId: string, userId: string): void {
    this.ctx.authz.requirePermission(orgId, userId, "inbox.view", "You don't have access to the inbox in this workspace");
  }

  requireReply(orgId: string, userId: string): void {
    this.ctx.authz.requirePermission(orgId, userId, "inbox.reply", "You can't reply to customers in this workspace");
  }

  requireManage(orgId: string, userId: string): void {
    this.ctx.authz.requirePermission(orgId, userId, "inbox.manage", "Only inbox admins can change this");
  }

  agents(orgId: string): string[] {
    return this.ctx.authz.workspaceMembersWith(orgId, "inbox.view");
  }

  /** Tells the workspace's agents something changed (ids only; the apps fetch what they may see). */
  notify(orgId: string, conversationId: string | null, kind = "inbox.updated", wakeUserIds: readonly string[] = []): void {
    this.ctx.hub.sendToUsers(new Set(this.agents(orgId)), { t: kind, orgId, conversationId });
    for (const u of wakeUserIds) {
      // Payload-free wake-ups: no customer text ever travels in a push.
      if (this.ctx.hub.isUserOnline(u)) continue;
      this.ctx.push?.remind(u);
      this.ctx.fcm?.wakeUser(u);
    }
  }

  // ───────── Settings ─────────

  settings(orgId: string): InboxSettings {
    const r = this.db.prepare("SELECT json FROM inbox_settings WHERE org_id = ?").get(orgId) as { json: string } | undefined;
    if (!r) return DEFAULT_SETTINGS;
    const parsed = zInboxSettings.safeParse(JSON.parse(r.json));
    return parsed.success ? parsed.data : DEFAULT_SETTINGS;
  }

  setSettings(orgId: string, actorId: string, input: InboxSettings): InboxSettings {
    this.requireManage(orgId, actorId);
    for (const id of input.knowledgeDocumentIds) {
      const { d } = this.ctx.documents.require(id, actorId, "view");
      // Customer answers may only draw on documents the whole workspace can read, never restricted ones.
      if (d.org_id !== orgId || d.access !== "workspace") throw badRequest(`"${d.title}" isn't shared with the whole workspace, so it can't be used for customer answers`);
    }
    if (input.defaultQueueId && !this.queue(orgId, input.defaultQueueId)) throw badRequest("That queue isn't in this workspace");
    if (input.aiMode !== "off" && !this.ctx.config.ai.providers.length) throw new ApiError(409, "ai_not_configured", "This server has no AI provider configured");
    const prior = this.settings(orgId);
    this.db
      .prepare("INSERT INTO inbox_settings (org_id, json, updated_by, updated_at) VALUES (?, ?, ?, ?) ON CONFLICT(org_id) DO UPDATE SET json = excluded.json, updated_by = excluded.updated_by, updated_at = excluded.updated_at")
      .run(orgId, JSON.stringify(input), actorId, now());
    const changed = (Object.keys(input) as (keyof InboxSettings)[]).filter((k) => JSON.stringify(input[k]) !== JSON.stringify(prior[k]));
    if (changed.length) this.ctx.audit({ actorId, orgId, action: "inbox.settings", detail: changed.join(",").slice(0, 200) });
    return input;
  }

  /** What each provider supports here, and what's still missing to use it (names only). */
  providers(orgId: string, userId: string) {
    this.requireView(orgId, userId);
    const c = this.ctx.config.inbox;
    const keyMissing = this.ctx.config.integrations.keyring ? [] : ["INTEGRATIONS_TOKEN_KEY"];
    return (Object.keys(CAPABILITIES) as InboxProvider[]).map((id) => {
      const cap = CAPABILITIES[id];
      const missing = id === "tiktok" ? [] : [...keyMissing, ...(id === "instagram" ? c.missing.instagram : c.missing.meta)];
      const state = cap.readiness === "awaiting_access" ? "awaiting_access" : missing.length ? "needs_configuration" : "ready";
      return {
        id,
        ...cap,
        state,
        missing,
        humanAgentTag: c.humanAgentTag && cap.humanAgentWindowMs !== null,
        webhookUrl: cap.readiness === "live" ? `${this.publicBase()}/v1/inbox/webhooks/meta` : null,
        tiktokCredentialsPresent: id === "tiktok" ? c.tiktokAppId !== null : undefined,
      };
    });
  }

  private publicBase(): string {
    return (this.ctx.config.integrations.publicUrl ?? "").replace(/\/+$/, "") || "(PUBLIC_API_URL)";
  }

  // ───────── Accounts ─────────

  accountView(a: AccountRow) {
    const m = JSON.parse(a.meta_json) as Record<string, string>;
    return {
      id: a.id,
      provider: a.provider,
      providerName: CAPABILITIES[a.provider].name,
      externalId: a.external_id,
      name: a.name,
      number: m.number || null,
      status: a.status,
      lastError: a.last_error,
      lastEventAt: a.last_event_at,
      tokenExpiresAt: a.token_expires_at,
      connectedBy: a.connected_by,
      createdAt: a.created_at,
    };
  }

  accounts(orgId: string, userId: string) {
    this.requireView(orgId, userId);
    const rows = this.db.prepare("SELECT * FROM inbox_accounts WHERE org_id = ? AND status != 'disconnected' ORDER BY created_at").all(orgId) as AccountRow[];
    return rows.map((a) => this.accountView(a));
  }

  account(orgId: string, id: string): AccountRow {
    const a = this.db.prepare("SELECT * FROM inbox_accounts WHERE id = ? AND org_id = ?").get(id, orgId) as AccountRow | undefined;
    if (!a || a.status === "disconnected") throw notFound("No such connected account");
    return a;
  }

  /** Connects a business account with a token the provider issued (never a password). The token is checked first. */
  async connect(orgId: string, actorId: string, input: { provider: InboxProvider; externalId: string; token: string; businessAccountId?: string; tokenExpiresInDays?: number }) {
    this.requireManage(orgId, actorId);
    const ring = this.ring();
    if (input.provider === "tiktok") throw new ApiError(409, "awaiting_access", "TikTok's Business Messaging API isn't available to this server yet");
    const prov = this.providers(orgId, actorId).find((p) => p.id === input.provider);
    if (prov?.state !== "ready") throw new ApiError(409, "needs_configuration", `${CAPABILITIES[input.provider].name} needs server setup first: ${prov?.missing.join(", ")}`);
    const live = (this.db.prepare("SELECT COUNT(*) n FROM inbox_accounts WHERE org_id = ? AND status != 'disconnected'").get(orgId) as { n: number }).n;
    if (live >= INBOX_LIMITS.accountsPerOrg) throw conflict(`At most ${INBOX_LIMITS.accountsPerOrg} connected accounts per workspace`);
    this.ctx.entitlements.check("inbox.accounts", live, orgId);
    let checked: meta.AccountCheck;
    try {
      checked = await meta.checkAccount(this.metaCfg, input.provider, input);
    } catch (e) {
      const kind = e instanceof meta.MetaError ? e.kind : "unavailable";
      throw badRequest(kind === "unauthorized" || kind === "permission" ? "Meta didn't accept that token for this account" : "Meta couldn't confirm that account. Check the ids and try again.", { kind });
    }
    const taken = this.db.prepare("SELECT org_id FROM inbox_accounts WHERE provider = ? AND external_id = ? AND status != 'disconnected'").get(input.provider, checked.externalId) as { org_id: string } | undefined;
    if (taken) throw conflict(taken.org_id === orgId ? "That account is already connected here" : "That account is connected to another workspace");
    const id = newId("iba");
    const t = now();
    const expires = input.provider === "instagram" ? t + (input.tokenExpiresInDays ?? 60) * 86_400_000 : input.tokenExpiresInDays ? t + input.tokenExpiresInDays * 86_400_000 : null;
    this.db
      .prepare("INSERT INTO inbox_accounts (id, org_id, provider, external_id, live_key, name, meta_json, token_sealed, token_expires_at, status, connected_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', ?, ?, ?)")
      .run(id, orgId, input.provider, checked.externalId, `${input.provider}:${checked.externalId}`, checked.name.slice(0, 120), JSON.stringify(checked.meta), seal(ring, input.token, PURPOSE.token, id), expires, actorId, t, t);
    try {
      await meta.subscribe(this.metaCfg, input.provider, { externalId: checked.externalId, token: input.token, meta: checked.meta });
    } catch (e) {
      // The account still works if the app's dashboard subscription covers it; the health card shows the kind.
      this.db.prepare("UPDATE inbox_accounts SET last_error = ? WHERE id = ?").run(`subscribe_${e instanceof meta.MetaError ? e.kind : "failed"}`, id);
    }
    this.ctx.audit({ actorId, orgId, action: "inbox.account_connected", target: id, detail: input.provider });
    return this.accountView(this.account(orgId, id));
  }

  /** Disconnects: the token is erased; conversations stay until the workspace deletes them (or retention does). */
  disconnect(orgId: string, actorId: string, id: string): void {
    this.requireManage(orgId, actorId);
    const a = this.account(orgId, id);
    this.db.prepare("UPDATE inbox_accounts SET status = 'disconnected', live_key = NULL, token_sealed = NULL, updated_at = ? WHERE id = ?").run(now(), a.id);
    this.ctx.audit({ actorId, orgId, action: "inbox.account_disconnected", target: a.id, detail: a.provider });
    this.notify(orgId, null);
  }

  /** Re-checks a connection with the provider and records its health. */
  async checkHealth(orgId: string, actorId: string, id: string) {
    this.requireManage(orgId, actorId);
    const a = this.account(orgId, id);
    try {
      await meta.checkAccount(this.metaCfg, a.provider as meta.MetaProvider, { externalId: a.external_id, token: this.token(a), businessAccountId: (JSON.parse(a.meta_json) as Record<string, string>).wabaId });
      this.db.prepare("UPDATE inbox_accounts SET status = 'active', last_error = NULL, updated_at = ? WHERE id = ?").run(now(), a.id);
    } catch (e) {
      this.markFailed(a, e);
    }
    return this.accountView(this.account(orgId, id));
  }

  /** Records a provider failure on the account: a refused token needs reconnecting, anything else is an error state. */
  markFailed(a: AccountRow, e: unknown): void {
    const kind = e instanceof meta.MetaError ? e.kind : "unavailable";
    const status = kind === "unauthorized" ? "needs_reauth" : kind === "rate_limited" || kind === "unavailable" ? a.status : "error";
    if (status === a.status && a.last_error === kind) return;
    this.db.prepare("UPDATE inbox_accounts SET status = ?, last_error = ?, updated_at = ? WHERE id = ?").run(status, kind, now(), a.id);
    if (status !== "active") {
      this.ctx.workflows.emit({ type: "inbox.connection_failed", key: `inbox-fail:${a.id}:${now()}`, orgId: a.org_id, depth: 0, inbox: { provider: a.provider, account: a.name, queue: "" } });
      this.notify(a.org_id, null, "inbox.account_failed", this.ctx.authz.workspaceMembersWith(a.org_id, "inbox.manage"));
    }
  }

  async templates(orgId: string, userId: string, id: string) {
    this.requireReply(orgId, userId);
    const a = this.account(orgId, id);
    if (a.provider !== "whatsapp") return [];
    try {
      return await meta.templates(this.metaCfg, { token: this.token(a), meta: JSON.parse(a.meta_json) as Record<string, string> });
    } catch (e) {
      this.markFailed(a, e);
      throw new ApiError(502, "provider_failed", "WhatsApp didn't return the templates. Try again later.", { kind: e instanceof meta.MetaError ? e.kind : "unavailable" });
    }
  }

  // ───────── Queues ─────────

  queue(orgId: string, id: string) {
    return this.db.prepare("SELECT * FROM inbox_queues WHERE id = ? AND org_id = ?").get(id, orgId) as { id: string; name: string; members_json: string } | undefined;
  }

  queues(orgId: string, userId: string) {
    this.requireView(orgId, userId);
    return (this.db.prepare("SELECT * FROM inbox_queues WHERE org_id = ? ORDER BY name").all(orgId) as { id: string; name: string; members_json: string }[]).map((q) => ({
      id: q.id,
      name: q.name,
      memberIds: JSON.parse(q.members_json) as string[],
    }));
  }

  saveQueue(orgId: string, actorId: string, input: { id?: string; name: string; memberIds: string[] }) {
    this.requireManage(orgId, actorId);
    const agents = new Set(this.agents(orgId));
    const bad = input.memberIds.filter((m) => !agents.has(m));
    if (bad.length) throw badRequest("Queue members must be people with inbox access in this workspace", { userIds: bad });
    const members = JSON.stringify([...new Set(input.memberIds)]);
    if (input.id) {
      if (!this.queue(orgId, input.id)) throw notFound("No such queue");
      this.db.prepare("UPDATE inbox_queues SET name = ?, members_json = ? WHERE id = ?").run(input.name, members, input.id);
    } else {
      const n = (this.db.prepare("SELECT COUNT(*) n FROM inbox_queues WHERE org_id = ?").get(orgId) as { n: number }).n;
      if (n >= INBOX_LIMITS.queuesPerOrg) throw conflict(`At most ${INBOX_LIMITS.queuesPerOrg} queues per workspace`);
      input = { ...input, id: newId("ibq") };
      this.db.prepare("INSERT INTO inbox_queues (id, org_id, name, members_json, created_at) VALUES (?, ?, ?, ?, ?)").run(input.id, orgId, input.name, members, now());
    }
    this.ctx.audit({ actorId, orgId, action: "inbox.queue_saved", target: input.id ?? null });
    return this.queues(orgId, actorId).find((q) => q.id === input.id);
  }

  deleteQueue(orgId: string, actorId: string, id: string): void {
    this.requireManage(orgId, actorId);
    if (!this.queue(orgId, id)) throw notFound("No such queue");
    this.db.transaction(() => {
      this.db.prepare("UPDATE inbox_conversations SET queue_id = NULL WHERE org_id = ? AND queue_id = ?").run(orgId, id);
      this.db.prepare("DELETE FROM inbox_queues WHERE id = ?").run(id);
    })();
    this.ctx.audit({ actorId, orgId, action: "inbox.queue_deleted", target: id });
  }

  // ───────── Conversations ─────────

  conversation(id: string, userId: string): ConversationRow {
    const c = this.db.prepare("SELECT * FROM inbox_conversations WHERE id = ?").get(id) as ConversationRow | undefined;
    // Outsiders and people without inbox access get the same answer as for a missing conversation.
    if (!c || !this.ctx.authz.can(c.org_id, userId, "inbox.view")) throw notFound("No such conversation");
    return c;
  }

  contact(id: string): ContactRow {
    return this.db.prepare("SELECT id, external_user_id, display_name FROM inbox_contacts WHERE id = ?").get(id) as ContactRow;
  }

  private overdue(c: ConversationRow, s: InboxSettings): boolean {
    return s.responseTargetMinutes > 0 && c.status === "waiting_agent" && c.last_inbound_at !== null && now() - c.last_inbound_at > s.responseTargetMinutes * 60_000;
  }

  summary(c: ConversationRow, s: InboxSettings = this.settings(c.org_id)) {
    const contact = this.contact(c.contact_id);
    const last = this.db.prepare("SELECT * FROM inbox_messages WHERE conversation_id = ? AND direction != 'note' ORDER BY created_at DESC LIMIT 1").get(c.id) as MessageRow | undefined;
    const account = this.db.prepare("SELECT name, status FROM inbox_accounts WHERE id = ?").get(c.account_id) as { name: string; status: string };
    return {
      id: c.id,
      orgId: c.org_id,
      provider: c.provider,
      account: { id: c.account_id, name: account.name, status: account.status },
      // Only what the provider supplied; never a guessed name.
      customer: { id: contact.id, name: contact.display_name, handle: c.provider === "whatsapp" ? `+${contact.external_user_id}` : null },
      status: c.status,
      priority: c.priority,
      assigneeId: c.assignee_id,
      queueId: c.queue_id,
      handling: c.handling,
      escalationReason: c.escalation_reason,
      tags: JSON.parse(c.tags_json) as string[],
      unread: c.unread,
      preview: last ? (last.kind === "text" ? (this.openBody(last) ?? "").slice(0, 140) : last.kind === "template" ? (templateLabel(this.openBody(last)) ?? "") : `[${last.kind}]`) : "",
      previewFrom: last?.direction ?? null,
      lastMessageAt: c.last_message_at,
      lastInboundAt: c.last_inbound_at,
      overdue: this.overdue(c, s),
      rev: c.rev,
    };
  }

  list(orgId: string, userId: string, f: { view: string; provider?: InboxProvider; queueId?: string; q?: string; before?: number }) {
    this.requireView(orgId, userId);
    const where = ["c.org_id = ?"];
    const args: unknown[] = [orgId];
    const views: Record<string, string> = {
      all: "c.status != 'resolved'",
      unassigned: "c.status != 'resolved' AND c.assignee_id IS NULL",
      mine: "c.status != 'resolved' AND c.assignee_id = ?",
      waiting_customer: "c.status = 'waiting_customer'",
      waiting_agent: "c.status = 'waiting_agent'",
      ai: "c.status != 'resolved' AND c.handling IN ('ai_auto', 'ai_draft', 'awaiting_review', 'escalated')",
      resolved: "c.status = 'resolved'",
    };
    const v = views[f.view];
    if (!v) throw badRequest("Unknown view");
    where.push(v);
    if (f.view === "mine") args.push(userId);
    if (f.provider) (where.push("c.provider = ?"), args.push(f.provider));
    if (f.queueId) (where.push("c.queue_id = ?"), args.push(f.queueId));
    if (f.before) (where.push("c.last_message_at < ?"), args.push(f.before));
    if (f.q) {
      // Customer names and tags (message bodies are sealed, so they aren't searched on the server).
      const like = `%${f.q.toLowerCase().replace(/[%_\\]/g, (ch) => "\\" + ch)}%`;
      where.push("(lower(COALESCE(k.display_name, '')) LIKE ? ESCAPE '\\' OR lower(c.tags_json) LIKE ? ESCAPE '\\' OR k.external_user_id LIKE ? ESCAPE '\\')");
      args.push(like, like, like);
    }
    const rows = this.db
      .prepare(`SELECT c.* FROM inbox_conversations c JOIN inbox_contacts k ON k.id = c.contact_id WHERE ${where.join(" AND ")} ORDER BY c.last_message_at DESC LIMIT ${INBOX_LIMITS.pageSize + 1}`)
      .all(...args) as ConversationRow[];
    const s = this.settings(orgId);
    const page = rows.slice(0, INBOX_LIMITS.pageSize);
    const counts = this.db
      .prepare(
        `SELECT SUM(status != 'resolved') open, SUM(status != 'resolved' AND assignee_id IS NULL) unassigned, SUM(status != 'resolved' AND assignee_id = ?) mine,
                SUM(handling = 'escalated' AND status != 'resolved') escalated FROM inbox_conversations WHERE org_id = ?`,
      )
      .get(userId, orgId) as Record<string, number | null>;
    return {
      conversations: page.map((c) => this.summary(c, s)),
      next: rows.length > INBOX_LIMITS.pageSize ? page[page.length - 1].last_message_at : null,
      counts: Object.fromEntries(Object.entries(counts).map(([k, n]) => [k, n ?? 0])),
    };
  }

  messageView(m: MessageRow) {
    const atts = JSON.parse(m.attachments_json) as { type: string; mime: string | null }[];
    return {
      id: m.id,
      direction: m.direction,
      kind: m.kind,
      text: m.kind === "template" ? templateLabel(this.openBody(m)) : this.openBody(m),
      attachments: atts.map((a, n) => ({ n, type: a.type, mime: a.mime })),
      authorId: m.author_id,
      origin: m.origin,
      status: m.status,
      errorKind: m.error_kind,
      at: m.provider_ts ?? m.created_at,
    };
  }

  /** One conversation with its history, what may be sent now, and why. */
  detail(id: string, userId: string) {
    const c = this.conversation(id, userId);
    const msgs = (this.db.prepare(`SELECT * FROM inbox_messages WHERE conversation_id = ? ORDER BY COALESCE(provider_ts, created_at) DESC, created_at DESC, rowid DESC LIMIT ${INBOX_LIMITS.messagesShown}`).all(c.id) as MessageRow[]).reverse();
    const rule = replyRule(c.provider, c.last_inbound_at, now(), { humanAgentTag: this.ctx.config.inbox.humanAgentTag, byAi: false });
    return {
      conversation: this.summary(c),
      messages: msgs.map((m) => this.messageView(m)),
      reply: {
        rule,
        canReply: this.ctx.authz.can(c.org_id, userId, "inbox.reply"),
        templates: CAPABILITIES[c.provider].templates,
        textLimit: CAPABILITIES[c.provider].textLimit,
        windowEndsAt: c.last_inbound_at === null ? null : c.last_inbound_at + CAPABILITIES[c.provider].freeReplyWindowMs,
      },
      capabilities: CAPABILITIES[c.provider],
    };
  }

  markRead(id: string, userId: string): void {
    const c = this.conversation(id, userId);
    // Meldiq's own unread count; the customer is never told (provider read receipts aren't sent).
    this.db.prepare("UPDATE inbox_conversations SET unread = 0 WHERE id = ?").run(c.id);
  }

  /** Assignment, queue, status, priority, tags, ownership. Each change is audited; a stale [rev] is refused. */
  update(id: string, userId: string, input: { rev: number; assigneeId?: string | null; queueId?: string | null; status?: ConversationStatus; priority?: string; tags?: string[]; handling?: Handling }) {
    const c = this.conversation(id, userId);
    if (input.assigneeId && !this.agents(c.org_id).includes(input.assigneeId)) throw badRequest("Assign to someone with inbox access in this workspace");
    if (input.queueId && !this.queue(c.org_id, input.queueId)) throw badRequest("That queue isn't in this workspace");
    if (input.handling === "ai_auto" && this.settings(c.org_id).aiMode !== "auto") throw badRequest("Automatic replies are off in this workspace");
    const next = {
      assignee_id: input.assigneeId === undefined ? c.assignee_id : input.assigneeId,
      queue_id: input.queueId === undefined ? c.queue_id : input.queueId,
      status: input.status ?? c.status,
      priority: input.priority ?? c.priority,
      tags_json: input.tags ? JSON.stringify([...new Set(input.tags)].slice(0, INBOX_LIMITS.tagsPerConversation)) : c.tags_json,
      handling: input.handling ?? c.handling,
    };
    const t = now();
    const res = this.db
      .prepare(
        `UPDATE inbox_conversations SET assignee_id = ?, queue_id = ?, status = ?, priority = ?, tags_json = ?, handling = ?,
           escalation_reason = CASE WHEN ? = 'escalated' THEN escalation_reason ELSE NULL END,
           resolved_at = CASE WHEN ? = 'resolved' THEN COALESCE(resolved_at, ?) ELSE NULL END, rev = rev + 1, updated_at = ?
         WHERE id = ? AND rev = ?`,
      )
      .run(next.assignee_id, next.queue_id, next.status, next.priority, next.tags_json, next.handling, next.handling, next.status, t, t, c.id, input.rev);
    if (res.changes === 0) throw conflict("Someone else changed this conversation. Refresh and try again.");
    const changes = [
      next.assignee_id !== c.assignee_id && `assignee:${next.assignee_id ?? "none"}`,
      next.queue_id !== c.queue_id && `queue:${next.queue_id ?? "none"}`,
      next.status !== c.status && `status:${next.status}`,
      next.priority !== c.priority && `priority:${next.priority}`,
      next.handling !== c.handling && `handling:${next.handling}`,
      next.tags_json !== c.tags_json && "tags",
    ].filter(Boolean) as string[];
    if (changes.length) this.ctx.audit({ actorId: userId, orgId: c.org_id, action: "inbox.conversation_updated", target: c.id, detail: changes.join(",").slice(0, 200) });
    const woken = next.assignee_id && next.assignee_id !== c.assignee_id && next.assignee_id !== userId ? [next.assignee_id] : [];
    this.notify(c.org_id, c.id, "inbox.updated", woken);
    return this.summary(this.conversation(id, userId));
  }

  claim(id: string, userId: string) {
    const c = this.conversation(id, userId);
    if (c.assignee_id && c.assignee_id !== userId) throw conflict("Someone else already has this conversation");
    return this.update(id, userId, { rev: c.rev, assigneeId: userId, handling: c.handling === "ai_auto" || c.handling === "escalated" ? "human" : c.handling });
  }

  /** An internal note: stored with the conversation, never sent to the customer. */
  note(id: string, userId: string, text: string) {
    const c = this.conversation(id, userId);
    const mid = newId("ibm");
    this.db
      .prepare("INSERT INTO inbox_messages (id, org_id, conversation_id, direction, author_id, kind, body_sealed, status, created_at) VALUES (?, ?, ?, 'note', ?, 'text', ?, 'received', ?)")
      .run(mid, c.org_id, c.id, userId, this.sealBody(mid, text.slice(0, INBOX_LIMITS.noteChars)), now());
    this.notify(c.org_id, c.id);
    return this.messageView(this.db.prepare("SELECT * FROM inbox_messages WHERE id = ?").get(mid) as MessageRow);
  }

  /**
   * Queues a reply to the customer, after the provider's rules: the window, the text limit, templates on WhatsApp.
   * A person replying takes the conversation over from automatic replies.
   */
  queueReply(c: ConversationRow, authorId: string | null, body: meta.SendBody, origin: MessageRow["origin"]): MessageRow {
    const byAi = origin === "ai_auto";
    if (body.kind === "text") {
      const rule = replyRule(c.provider, c.last_inbound_at, now(), { humanAgentTag: this.ctx.config.inbox.humanAgentTag, byAi });
      if (!rule.allowed) {
        const msg = rule.reason === "template_required" ? "More than 24 hours have passed: WhatsApp only allows an approved template now" : rule.reason === "window_closed" ? "The reply window for this conversation has closed" : "Replies aren't supported for this conversation";
        throw new ApiError(409, rule.reason, msg);
      }
      if (!textFits(c.provider, body.text)) throw badRequest(`That's too long for ${CAPABILITIES[c.provider].name} (at most ${CAPABILITIES[c.provider].textLimit} characters)`);
      body = { ...body, humanAgentTag: rule.mode === "human_agent_tag" };
    } else if (!CAPABILITIES[c.provider].templates) {
      throw badRequest("Templates are only for WhatsApp");
    }
    const a = this.db.prepare("SELECT * FROM inbox_accounts WHERE id = ?").get(c.account_id) as AccountRow;
    if (a.status === "disconnected" || a.status === "needs_reauth") throw new ApiError(409, "inbox_reconnect", "This account needs to be connected again before replying");
    const mid = newId("ibm");
    const t = now();
    // A template's sealed body is its spec ({name, language}); the sender reads it back, even after a restart.
    const text = body.kind === "text" ? body.text : JSON.stringify({ name: body.name, language: body.language });
    this.db.transaction(() => {
      this.db
        .prepare(
          "INSERT INTO inbox_messages (id, org_id, conversation_id, direction, author_id, origin, kind, body_sealed, attachments_json, status, next_attempt_at, created_at) VALUES (?, ?, ?, 'out', ?, ?, ?, ?, ?, 'queued', ?, ?)",
        )
        .run(mid, c.org_id, c.id, authorId, origin, body.kind, this.sealBody(mid, text), "[]", t, t);
      this.db
        .prepare(
          `UPDATE inbox_conversations SET status = CASE WHEN status = 'resolved' THEN status ELSE 'waiting_customer' END, last_message_at = ?,
             first_response_at = COALESCE(first_response_at, ?), sla_notified_at = NULL, unread = 0, rev = rev + 1, updated_at = ?,
             handling = CASE WHEN ? = 'ai_auto' THEN handling ELSE 'human' END,
             assignee_id = CASE WHEN ? = 'ai_auto' THEN assignee_id ELSE COALESCE(assignee_id, ?) END
           WHERE id = ?`,
        )
        .run(t, t, t, origin, origin, authorId, c.id);
    })();
    return this.db.prepare("SELECT * FROM inbox_messages WHERE id = ?").get(mid) as MessageRow;
  }

  /** Erases a customer's data from this workspace: the contact, the conversation and every message. Audited. */
  erase(id: string, userId: string): void {
    const c = this.conversation(id, userId);
    this.requireManage(c.org_id, userId);
    this.db.prepare("DELETE FROM inbox_contacts WHERE id = ?").run(c.contact_id);
    this.ctx.audit({ actorId: userId, orgId: c.org_id, action: "inbox.customer_erased", target: c.id, detail: c.provider });
    this.notify(c.org_id, c.id);
  }

  /** A conversation's history for a data request (manage only): what Meldiq holds, opened. */
  export(id: string, userId: string) {
    const c = this.conversation(id, userId);
    this.requireManage(c.org_id, userId);
    this.ctx.audit({ actorId: userId, orgId: c.org_id, action: "inbox.conversation_exported", target: c.id });
    const d = this.detail(id, userId);
    return { exportedAt: now(), conversation: d.conversation, messages: d.messages };
  }

  requireAttachment(messageId: string, n: number, userId: string): { m: MessageRow; a: AccountRow; ref: string } {
    const m = this.db.prepare("SELECT * FROM inbox_messages WHERE id = ?").get(messageId) as MessageRow | undefined;
    if (!m) throw notFound("No such attachment");
    const c = this.conversation(m.conversation_id, userId);
    const atts = JSON.parse(m.attachments_json) as { ref: string }[];
    if (!atts[n]) throw notFound("No such attachment");
    const a = this.db.prepare("SELECT * FROM inbox_accounts WHERE id = ?").get(c.account_id) as AccountRow;
    if (a.status === "disconnected") throw forbidden("The account was disconnected, so its files can't be fetched");
    return { m, a, ref: this.openAttachmentRef(m.id, n, atts[n].ref) };
  }
}
