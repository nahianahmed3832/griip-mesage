import { seal } from "../integrations/tokens.js";
import { errorKind } from "../ops/metrics.js";
import { newId, now, sha256Hex } from "../util.js";
import * as meta from "./meta.js";
import type { InboundMessage, StatusUpdate } from "./model.js";
import type { AccountRow, ConversationRow, InboxService, MessageRow } from "./service.js";

/**
 * The inbox's background work (docs/UNIFIED_INBOX.md › Reliability):
 * - every verified webhook is stored sealed before Meta gets its 200, then processed here, so nothing is lost when
 *   processing fails; a redelivery has the same hash and is ignored;
 * - replies wait in an outbox and are retried with bounded backoff; only the provider's own reports move them to
 *   delivered or read;
 * - overdue conversations, expiring Instagram tokens and retention are looked after on the same tick.
 */
const EVENT_ATTEMPTS = 8;
const BACKOFF_MS = [2_000, 10_000, 30_000, 120_000, 600_000, 1_800_000, 3_600_000];
const STATE_RANK: Record<string, number> = { queued: 0, sent: 1, delivered: 2, read: 3 };
const TOKEN_REFRESH_BEFORE_MS = 7 * 86_400_000;
const RETENTION_EVERY_MS = 3_600_000;
const EVENTS_KEPT_MS = 7 * 86_400_000;

const backoff = (attempts: number) => BACKOFF_MS[Math.min(attempts, BACKOFF_MS.length) - 1] ?? BACKOFF_MS[BACKOFF_MS.length - 1];

export class InboxWorker {
  private timer: NodeJS.Timeout | null = null;
  private running = false;
  private lastRetention = 0;
  /** Conversations whose replies changed state during the current event (id → workspace), to refresh the apps. */
  private statusChanged = new Map<string, string>();
  /** Set by the AI layer: runs after a customer's message is stored (automatic replies, docs/UNIFIED_INBOX.md › AI). */
  onInbound: ((c: ConversationRow) => Promise<void>) | null = null;

  constructor(private readonly svc: InboxService) {}

  private get db() {
    return this.svc.db;
  }

  /** Stores a verified delivery; false when it was already received (Meta retries until it gets a 200). */
  accept(provider: string, raw: Buffer): boolean {
    const id = sha256Hex(Buffer.concat([Buffer.from(`${provider}\n`), raw]));
    const t = now();
    const res = this.db
      .prepare("INSERT OR IGNORE INTO inbox_events (id, provider, payload_sealed, next_attempt_at, received_at) VALUES (?, ?, ?, ?, ?)")
      .run(id, provider, this.svc.sealEvent(id, raw.toString("utf8")), t, t);
    return res.changes > 0;
  }

  start(intervalMs: number): void {
    if (intervalMs <= 0 || this.timer) return;
    this.timer = setInterval(() => void this.tick(), intervalMs);
    this.timer.unref();
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  /** One pass of everything due. Never runs twice at once. */
  async tick(): Promise<{ events: number; sent: number }> {
    if (this.running || !this.db.open) return { events: 0, sent: 0 };
    this.running = true;
    try {
      const events = await this.processEvents();
      const sent = await this.sendDue();
      this.overdue();
      await this.refreshTokens();
      if (now() - this.lastRetention > RETENTION_EVERY_MS) this.retention();
      return { events, sent };
    } finally {
      this.running = false;
    }
  }

  // ───────── Inbound ─────────

  async processEvents(limit = 100): Promise<number> {
    const rows = this.db.prepare("SELECT id, payload_sealed, attempts FROM inbox_events WHERE state = 'pending' AND next_attempt_at <= ? ORDER BY received_at LIMIT ?").all(now(), limit) as { id: string; payload_sealed: string; attempts: number }[];
    let done = 0;
    for (const r of rows) {
      try {
        const parsed = meta.parseWebhook(JSON.parse(this.svc.openEvent(r.id, r.payload_sealed)));
        const touched = this.db.transaction(() => [...parsed.inbound.map((m) => this.ingest(m)), ...parsed.statuses.map((s) => this.status(s))])();
        this.db.prepare("UPDATE inbox_events SET state = 'done', error_kind = NULL WHERE id = ?").run(r.id);
        done++;
        this.svc.ctx.metrics.recordJob("inbox.events", 0);
        for (const c of new Set(touched.filter((x): x is ConversationRow => !!x))) await this.after(c);
        // Delivered and read reports refresh open screens too (ids only, no wake-ups).
        for (const [conversationId, orgId] of this.statusChanged) this.svc.notify(orgId, conversationId);
        this.statusChanged.clear();
      } catch (e) {
        const attempts = r.attempts + 1;
        const failed = attempts >= EVENT_ATTEMPTS;
        this.db.prepare("UPDATE inbox_events SET attempts = ?, next_attempt_at = ?, state = ?, error_kind = ? WHERE id = ?").run(attempts, now() + backoff(attempts), failed ? "failed" : "pending", errorKind(e), r.id);
        this.svc.ctx.metrics.recordJob("inbox.events", 0, e);
        this.svc.ctx.log.warn({ errorType: errorKind(e), attempts }, "inbox event processing failed");
      }
    }
    return done;
  }

  private account(provider: string, externalId: string): AccountRow | undefined {
    return this.db.prepare("SELECT * FROM inbox_accounts WHERE provider = ? AND external_id = ? AND status != 'disconnected'").get(provider, externalId) as AccountRow | undefined;
  }

  /** Stores one customer message (once, whatever the order or number of deliveries). Returns its conversation. */
  private ingest(m: InboundMessage): ConversationRow | null {
    const a = this.account(m.provider, m.accountExternalId);
    if (!a) return null; // not connected here (or disconnected): dropped, never routed to another workspace
    const t = now();
    this.db.prepare("UPDATE inbox_accounts SET last_event_at = ? WHERE id = ?").run(t, a.id);
    this.db
      .prepare("INSERT INTO inbox_contacts (id, org_id, account_id, external_user_id, display_name, created_at) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(account_id, external_user_id) DO UPDATE SET display_name = COALESCE(excluded.display_name, display_name)")
      .run(newId("ibk"), a.org_id, a.id, m.customerId, m.customerName, t);
    const contact = this.db.prepare("SELECT id FROM inbox_contacts WHERE account_id = ? AND external_user_id = ?").get(a.id, m.customerId) as { id: string };
    let c = this.db.prepare("SELECT * FROM inbox_conversations WHERE account_id = ? AND contact_id = ?").get(a.id, contact.id) as ConversationRow | undefined;
    const isNew = !c;
    if (!c) {
      const s = this.svc.settings(a.org_id);
      const id = newId("ibc");
      this.db
        .prepare(
          "INSERT INTO inbox_conversations (id, org_id, account_id, contact_id, provider, queue_id, handling, last_message_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        .run(id, a.org_id, a.id, contact.id, a.provider, s.defaultQueueId, s.aiMode === "auto" && !s.autoPaused ? "ai_auto" : s.aiMode === "draft" ? "ai_draft" : "human", m.at, t, t);
      c = this.db.prepare("SELECT * FROM inbox_conversations WHERE id = ?").get(id) as ConversationRow;
    }
    const mid = newId("ibm");
    const atts = m.attachments.map((x, n) => ({ type: x.type, mime: x.mime, ref: this.svc.sealAttachmentRef(mid, n, x.ref) }));
    const inserted = this.db
      .prepare(
        "INSERT OR IGNORE INTO inbox_messages (id, org_id, conversation_id, direction, external_id, kind, body_sealed, attachments_json, status, provider_ts, created_at) VALUES (?, ?, ?, 'in', ?, ?, ?, ?, 'received', ?, ?)",
      )
      .run(mid, a.org_id, c.id, m.externalId, m.kind, this.svc.sealBody(mid, m.text), JSON.stringify(atts), m.at, t);
    if (inserted.changes === 0) return null; // a duplicate of a message already stored
    // Out-of-order deliveries: times only move forward; a customer message reopens a resolved conversation.
    this.db
      .prepare(
        `UPDATE inbox_conversations SET unread = unread + 1, last_message_at = MAX(last_message_at, ?), last_inbound_at = MAX(COALESCE(last_inbound_at, 0), ?),
           status = 'waiting_agent', resolved_at = NULL, rev = rev + 1, updated_at = ? WHERE id = ?`,
      )
      .run(m.at, m.at, t, c.id);
    this.svc.ctx.usage.add(a.org_id, "inbox.messages_in");
    const fresh = this.db.prepare("SELECT * FROM inbox_conversations WHERE id = ?").get(c.id) as ConversationRow;
    if (isNew) {
      this.svc.ctx.workflows.emit({ type: "inbox.conversation_created", key: `inbox-new:${c.id}`, orgId: a.org_id, depth: 0, inbox: { provider: a.provider, account: a.name, queue: this.queueName(fresh.queue_id) } });
    }
    return fresh;
  }

  private queueName(id: string | null): string {
    return id ? ((this.db.prepare("SELECT name FROM inbox_queues WHERE id = ?").get(id) as { name: string } | undefined)?.name ?? "") : "";
  }

  /** A provider's delivery report: only ever moves a reply forward (sent → delivered → read), or to failed. */
  private status(s: StatusUpdate): ConversationRow | null {
    const a = this.account(s.provider, s.accountExternalId);
    if (!a) return null;
    if (s.upTo && s.customerId) {
      const conv = this.db
        .prepare("SELECT c.* FROM inbox_conversations c JOIN inbox_contacts k ON k.id = c.contact_id WHERE c.account_id = ? AND k.external_user_id = ?")
        .get(a.id, s.customerId) as ConversationRow | undefined;
      if (!conv) return null;
      const r = this.db.prepare("UPDATE inbox_messages SET status = 'read' WHERE conversation_id = ? AND direction = 'out' AND status IN ('sent', 'delivered') AND created_at <= ?").run(conv.id, s.at);
      if (r.changes) this.statusChanged.set(conv.id, conv.org_id);
      return null;
    }
    const m = this.db
      .prepare("SELECT m.* FROM inbox_messages m JOIN inbox_conversations c ON c.id = m.conversation_id WHERE c.account_id = ? AND m.external_id = ? AND m.direction = 'out'")
      .get(a.id, s.externalId) as MessageRow | undefined;
    if (!m) return null;
    const forward = s.state !== "failed" && (STATE_RANK[s.state] ?? 0) > (STATE_RANK[m.status] ?? 0);
    if (s.state === "failed") this.db.prepare("UPDATE inbox_messages SET status = 'failed', error_kind = 'provider_failed' WHERE id = ?").run(m.id);
    else if (forward) this.db.prepare("UPDATE inbox_messages SET status = ? WHERE id = ?").run(s.state, m.id);
    if (s.state === "failed" || forward) this.statusChanged.set(m.conversation_id, m.org_id);
    return null;
  }

  private async after(c: ConversationRow): Promise<void> {
    const watchers = c.assignee_id ? [c.assignee_id] : this.queueMembers(c) ?? [];
    this.svc.notify(c.org_id, c.id, "inbox.updated", watchers);
    if (this.onInbound) {
      try {
        await this.onInbound(c);
      } catch (e) {
        this.svc.ctx.log.warn({ errorType: errorKind(e) }, "inbox automation failed");
      }
    }
  }

  private queueMembers(c: ConversationRow): string[] | null {
    const q = c.queue_id ? this.svc.queue(c.org_id, c.queue_id) : undefined;
    return q ? (JSON.parse(q.members_json) as string[]) : null;
  }

  // ───────── Outbound ─────────

  /** Sends due replies. Returns how many went out. */
  async sendDue(onlyId?: string): Promise<number> {
    const rows = (onlyId
      ? this.db.prepare("SELECT * FROM inbox_messages WHERE id = ? AND status = 'queued'").all(onlyId)
      : this.db.prepare("SELECT * FROM inbox_messages WHERE status = 'queued' AND next_attempt_at <= ? ORDER BY created_at LIMIT 50").all(now())) as MessageRow[];
    let sent = 0;
    for (const m of rows) if (await this.sendOne(m)) sent++;
    return sent;
  }

  private async sendOne(m: MessageRow): Promise<boolean> {
    const c = this.db.prepare("SELECT * FROM inbox_conversations WHERE id = ?").get(m.conversation_id) as ConversationRow | undefined;
    const a = c ? (this.db.prepare("SELECT * FROM inbox_accounts WHERE id = ?").get(c.account_id) as AccountRow) : undefined;
    if (!c || !a || a.status === "disconnected" || a.status === "needs_reauth") {
      this.db.prepare("UPDATE inbox_messages SET status = 'failed', error_kind = 'account_unavailable' WHERE id = ?").run(m.id);
      return false;
    }
    const to = this.svc.contact(c.contact_id).external_user_id;
    const opened = this.svc.openBody(m) ?? "";
    let body: meta.SendBody;
    if (m.kind === "template") {
      const spec = JSON.parse(opened) as { name: string; language: string };
      body = { kind: "template", name: spec.name, language: spec.language };
    } else {
      // The window is checked again at send time: a retry never sends past it.
      const humanAgentTag = this.svc.ctx.config.inbox.humanAgentTag && m.origin !== "ai_auto" && c.last_inbound_at !== null && now() - c.last_inbound_at > 24 * 3_600_000;
      body = { kind: "text", text: opened, humanAgentTag };
    }
    try {
      const externalId = await meta.send(this.svc.metaCfg, a.provider as meta.MetaProvider, { externalId: a.external_id, token: this.svc.token(a) }, to, body);
      this.db.prepare("UPDATE inbox_messages SET status = 'sent', external_id = ?, attempts = attempts + 1, error_kind = NULL, provider_ts = ? WHERE id = ? AND status = 'queued'").run(externalId, now(), m.id);
      this.svc.ctx.usage.add(c.org_id, m.origin === "ai_auto" ? "inbox.ai_auto_replies" : "inbox.messages_out");
      this.svc.ctx.metrics.recordProvider(`inbox.${a.provider}`);
      this.svc.notify(c.org_id, c.id);
      return true;
    } catch (e) {
      const err = e instanceof meta.MetaError ? e : new meta.MetaError("unavailable", true);
      this.svc.ctx.metrics.recordProvider(`inbox.${a.provider}`, err);
      const attempts = m.attempts + 1;
      const giveUp = !err.retryable || attempts >= 5;
      this.db.prepare("UPDATE inbox_messages SET attempts = ?, status = ?, error_kind = ?, next_attempt_at = ? WHERE id = ?").run(attempts, giveUp ? "failed" : "queued", err.kind, now() + backoff(attempts), m.id);
      if (err.kind === "unauthorized" || err.kind === "permission") this.svc.markFailed(a, err);
      if (giveUp) {
        const watchers = [c.assignee_id, m.author_id].filter((x): x is string => !!x);
        this.svc.notify(c.org_id, c.id, "inbox.delivery_failed", [...new Set(watchers)]);
      }
      return false;
    }
  }

  // ───────── Upkeep ─────────

  /** Tells agents once when a customer has waited past the workspace's response target. */
  overdue(): number {
    const rows = this.db.prepare("SELECT * FROM inbox_conversations WHERE status = 'waiting_agent' AND sla_notified_at IS NULL AND last_inbound_at IS NOT NULL").all() as ConversationRow[];
    let n = 0;
    for (const c of rows) {
      const target = this.svc.settings(c.org_id).responseTargetMinutes;
      if (!target || now() - (c.last_inbound_at ?? 0) < target * 60_000) continue;
      this.db.prepare("UPDATE inbox_conversations SET sla_notified_at = ? WHERE id = ?").run(now(), c.id);
      this.svc.notify(c.org_id, c.id, "inbox.overdue", c.assignee_id ? [c.assignee_id] : (this.queueMembers(c) ?? []));
      this.svc.ctx.workflows.emit({ type: "inbox.conversation_overdue", key: `inbox-late:${c.id}:${c.last_inbound_at}`, orgId: c.org_id, depth: 0, inbox: { provider: c.provider, account: "", queue: this.queueName(c.queue_id) } });
      n++;
    }
    return n;
  }

  /** Instagram tokens are refreshed in their last week; a refused refresh asks the admins to reconnect. */
  async refreshTokens(): Promise<number> {
    const rows = this.db.prepare("SELECT * FROM inbox_accounts WHERE provider = 'instagram' AND status = 'active' AND token_expires_at IS NOT NULL AND token_expires_at < ?").all(now() + TOKEN_REFRESH_BEFORE_MS) as AccountRow[];
    let n = 0;
    for (const a of rows) {
      try {
        const r = await meta.refreshInstagramToken(this.svc.metaCfg, this.svc.token(a));
        this.db.prepare("UPDATE inbox_accounts SET token_sealed = ?, token_expires_at = ?, updated_at = ? WHERE id = ?").run(seal(this.svc.ring(), r.token, "inbox-token", a.id), r.expiresAt, now(), a.id);
        n++;
      } catch (e) {
        this.svc.markFailed(a, e instanceof meta.MetaError && e.kind !== "rate_limited" && e.kind !== "unavailable" ? new meta.MetaError("unauthorized", false) : e);
      }
    }
    return n;
  }

  /** Workspace retention (settings.retentionDays) and old webhook payloads. */
  retention(): number {
    this.lastRetention = now();
    let n = 0;
    const orgs = this.db.prepare("SELECT org_id, json FROM inbox_settings").all() as { org_id: string; json: string }[];
    for (const o of orgs) {
      const days = (JSON.parse(o.json) as { retentionDays?: number }).retentionDays ?? 0;
      if (!days) continue;
      n += this.db.prepare("DELETE FROM inbox_messages WHERE org_id = ? AND created_at < ?").run(o.org_id, now() - days * 86_400_000).changes;
    }
    this.db.prepare("DELETE FROM inbox_events WHERE state != 'pending' AND received_at < ?").run(now() - EVENTS_KEPT_MS);
    return n;
  }
}
