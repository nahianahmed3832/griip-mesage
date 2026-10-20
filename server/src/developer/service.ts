import crypto from "node:crypto";
import type { AppContext } from "../context.js";
import { hmacSha256Hex, open, randomSecret, seal } from "../integrations/tokens.js";
import { errorKind } from "../ops/metrics.js";
import { retryDelayMs } from "../reminders/schedule.js";
import { ApiError, badRequest, conflict, forbidden, newId, notFound, now, sha256Hex, unauthorized } from "../util.js";
import { sendWebhook, type WebhookSender, webhookUrlProblem } from "./transport.js";

/**
 * The developer platform (docs/DEVELOPER_API.md, round 7 slice C6): workspace API keys with explicit scopes, and outgoing
 * webhooks for workspace events. Neither reaches messages (end-to-end encrypted), private channels, restricted or channel
 * documents, private rooms' meetings, emails, phone numbers or presence. Both stop working when the person who made them
 * can no longer manage developer access.
 */
export const API_SCOPES = ["workspace:read", "members:read", "channels:read", "documents:read", "meetings:read", "updates:write"] as const;
export type ApiScope = (typeof API_SCOPES)[number];

export const WEBHOOK_EVENTS = [
  "member.joined", "member.left", "member.role_changed", "channel.created",
  "document.created", "document.updated", "meeting.created", "meeting.updated", "meeting.cancelled",
] as const;
export type WebhookEvent = (typeof WEBHOOK_EVENTS)[number] | "ping";

export const DEV_LIMITS = {
  keysPerOrg: 25,
  webhooksPerOrg: 10,
  keyMaxDays: 365,
  /** Requests per key per minute; more get 429. */
  keyRatePerMinute: 120,
  maxAttempts: 8,
  /** Consecutive failed deliveries after which a webhook is paused (an admin turns it back on). */
  pauseAfterFailures: 20,
  deliveryRetentionDays: 7,
  leaseMs: 60_000,
} as const;

interface KeyRow {
  id: string;
  org_id: string;
  name: string;
  hint: string;
  secret_hash: string;
  scopes: string;
  created_by: string;
  created_at: number;
  expires_at: number;
  last_used_at: number | null;
  revoked_at: number | null;
}

interface HookRow {
  id: string;
  org_id: string;
  name: string;
  url: string;
  events: string;
  secret_sealed: string;
  enabled: number;
  disabled_reason: string | null;
  consecutive_failures: number;
  verified_at: number | null;
  created_by: string;
  created_at: number;
  updated_at: number;
}

interface DeliveryRow {
  id: string;
  webhook_id: string;
  org_id: string;
  event_id: string;
  event_type: string;
  payload: string;
  state: string;
  attempts: number;
  next_at: number;
  last_status: number | null;
  last_error: string | null;
  created_at: number;
  delivered_at: number | null;
}

export interface ApiCaller {
  keyId: string;
  orgId: string;
  name: string;
  scopes: Set<ApiScope>;
}

const SEAL_PURPOSE = "webhook-secret";

export class DeveloperService {
  /** Replaceable in tests (no network there). */
  sender: WebhookSender = sendWebhook;
  private timer: NodeJS.Timeout | null = null;
  private running = false;
  private rate = new Map<string, { minute: number; count: number }>();

  constructor(private readonly ctx: AppContext) {}

  requireManager(orgId: string, userId: string): void {
    this.ctx.authz.requireOrgMember(orgId, userId);
    if (!this.ctx.authz.can(orgId, userId, "developers.manage")) throw forbidden("Only people who can manage developer access can do that");
  }

  private name(id: string): string | null {
    return (this.ctx.db.prepare("SELECT display_name FROM users WHERE id = ?").get(id) as { display_name: string } | undefined)?.display_name ?? null;
  }

  // ───────────── API keys ─────────────

  private keyView(k: KeyRow) {
    return {
      id: k.id, name: k.name, hint: k.hint, scopes: JSON.parse(k.scopes) as string[],
      createdBy: { id: k.created_by, name: this.name(k.created_by) }, createdAt: k.created_at, expiresAt: k.expires_at,
      lastUsedAt: k.last_used_at, revoked: k.revoked_at !== null, expired: k.expires_at <= now(),
      // A key stops working when its creator can no longer manage developer access.
      ownerAllowed: this.ctx.authz.can(k.org_id, k.created_by, "developers.manage"),
    };
  }

  listKeys(orgId: string, userId: string) {
    this.requireManager(orgId, userId);
    return (this.ctx.db.prepare("SELECT * FROM api_keys WHERE org_id = ? ORDER BY created_at DESC").all(orgId) as KeyRow[]).map((k) => this.keyView(k));
  }

  /** Makes a key; the full token is returned this once and never again. */
  createKey(orgId: string, userId: string, input: { name: string; scopes: ApiScope[]; expiresInDays: number }) {
    this.requireManager(orgId, userId);
    const active = (this.ctx.db.prepare("SELECT COUNT(*) n FROM api_keys WHERE org_id = ? AND revoked_at IS NULL AND expires_at > ?").get(orgId, now()) as { n: number }).n;
    if (active >= DEV_LIMITS.keysPerOrg) throw conflict(`A workspace can have at most ${DEV_LIMITS.keysPerOrg} active API keys`);
    this.ctx.entitlements.check("api_keys", active, orgId);
    const id = newId("ak");
    const secret = randomSecret(32);
    const t = now();
    this.ctx.db
      .prepare("INSERT INTO api_keys (id, org_id, name, hint, secret_hash, scopes, created_by, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
      .run(id, orgId, input.name, secret.slice(-4), sha256Hex(secret), JSON.stringify([...new Set(input.scopes)]), userId, t, t + input.expiresInDays * 86_400_000);
    this.ctx.audit({ actorId: userId, orgId, action: "api_key.create", target: id, detail: input.scopes.join(",") });
    return { key: this.keyView(this.ctx.db.prepare("SELECT * FROM api_keys WHERE id = ?").get(id) as KeyRow), token: `mdq.${id}.${secret}` };
  }

  revokeKey(orgId: string, userId: string, id: string): void {
    this.requireManager(orgId, userId);
    const changed = this.ctx.db.prepare("UPDATE api_keys SET revoked_at = ? WHERE id = ? AND org_id = ? AND revoked_at IS NULL").run(now(), id, orgId).changes;
    if (!changed) throw notFound("API key not found");
    this.ctx.audit({ actorId: userId, orgId, action: "api_key.revoke", target: id });
  }

  /** A new key with the same name and scopes; the old one is revoked at once. */
  rotateKey(orgId: string, userId: string, id: string) {
    this.requireManager(orgId, userId);
    const k = this.ctx.db.prepare("SELECT * FROM api_keys WHERE id = ? AND org_id = ? AND revoked_at IS NULL").get(id, orgId) as KeyRow | undefined;
    if (!k) throw notFound("API key not found");
    const days = Math.max(1, Math.round((k.expires_at - k.created_at) / 86_400_000));
    this.revokeKey(orgId, userId, id);
    return this.createKey(orgId, userId, { name: k.name, scopes: JSON.parse(k.scopes), expiresInDays: Math.min(days, DEV_LIMITS.keyMaxDays) });
  }

  /** Checks a bearer token for the external API, its scope and its rate; 401 / 403 / 429 otherwise. */
  authenticate(token: string | undefined, scope: ApiScope): ApiCaller {
    const m = /^mdq\.([A-Za-z0-9_-]{4,64})\.([A-Za-z0-9_-]{20,128})$/.exec(token ?? "");
    if (!m) throw unauthorized("Use an API key: Authorization: Bearer mdq.…");
    const k = this.ctx.db.prepare("SELECT * FROM api_keys WHERE id = ?").get(m[1]) as KeyRow | undefined;
    const t = now();
    const good = k && crypto.timingSafeEqual(Buffer.from(k.secret_hash, "hex"), Buffer.from(sha256Hex(m[2]), "hex"));
    if (!k || !good || k.revoked_at || k.expires_at <= t || !this.ctx.authz.can(k.org_id, k.created_by, "developers.manage")) {
      throw unauthorized("This API key isn't valid (revoked, expired, or its creator no longer manages developer access)");
    }
    const scopes = new Set(JSON.parse(k.scopes) as ApiScope[]);
    if (!scopes.has(scope)) throw new ApiError(403, "missing_scope", `This API key doesn't have the ${scope} scope`);
    const minute = Math.floor(t / 60_000);
    const r = this.rate.get(k.id);
    const count = r?.minute === minute ? r.count + 1 : 1;
    this.rate.set(k.id, { minute, count });
    if (count > DEV_LIMITS.keyRatePerMinute) throw new ApiError(429, "rate_limited", `At most ${DEV_LIMITS.keyRatePerMinute} requests a minute per key`);
    if (!k.last_used_at || t - k.last_used_at > 60_000) this.ctx.db.prepare("UPDATE api_keys SET last_used_at = ? WHERE id = ?").run(t, k.id);
    this.ctx.metrics.recordProvider("api.external");
    this.ctx.usage?.add(k.org_id, "api.requests");
    return { keyId: k.id, orgId: k.org_id, name: k.name, scopes };
  }

  // ───────────── Webhooks ─────────────

  private ring() {
    const ring = this.ctx.config.integrations.keyring;
    if (!ring) throw new ApiError(503, "webhooks_unavailable", "Webhooks need INTEGRATIONS_TOKEN_KEY on the server to keep their secrets sealed");
    return ring;
  }

  private hookView(h: HookRow) {
    const stats = this.ctx.db
      .prepare("SELECT SUM(state = 'delivered') ok, SUM(state = 'failed') failed, SUM(state IN ('queued','sending')) waiting FROM webhook_deliveries WHERE webhook_id = ?")
      .get(h.id) as { ok: number | null; failed: number | null; waiting: number | null };
    return {
      id: h.id, name: h.name, url: h.url, events: JSON.parse(h.events) as string[], enabled: !!h.enabled, disabledReason: h.disabled_reason,
      verified: h.verified_at !== null, consecutiveFailures: h.consecutive_failures,
      deliveries: { delivered: stats.ok ?? 0, failed: stats.failed ?? 0, waiting: stats.waiting ?? 0 },
      createdBy: { id: h.created_by, name: this.name(h.created_by) }, createdAt: h.created_at,
      ownerAllowed: this.ctx.authz.can(h.org_id, h.created_by, "developers.manage"),
    };
  }

  private hook(orgId: string, id: string): HookRow {
    const h = this.ctx.db.prepare("SELECT * FROM webhooks WHERE id = ? AND org_id = ?").get(id, orgId) as HookRow | undefined;
    if (!h) throw notFound("Webhook not found");
    return h;
  }

  listHooks(orgId: string, userId: string) {
    this.requireManager(orgId, userId);
    return (this.ctx.db.prepare("SELECT * FROM webhooks WHERE org_id = ? ORDER BY created_at").all(orgId) as HookRow[]).map((h) => this.hookView(h));
  }

  /** Creates a webhook and sends it a signed "ping"; the signing secret is returned this once. */
  createHook(orgId: string, userId: string, input: { name: string; url: string; events: string[] }) {
    this.requireManager(orgId, userId);
    const problem = webhookUrlProblem(input.url);
    if (problem) throw badRequest(problem);
    const count = (this.ctx.db.prepare("SELECT COUNT(*) n FROM webhooks WHERE org_id = ?").get(orgId) as { n: number }).n;
    if (count >= DEV_LIMITS.webhooksPerOrg) throw conflict(`A workspace can have at most ${DEV_LIMITS.webhooksPerOrg} webhooks`);
    this.ctx.entitlements.check("webhooks.outgoing", count, orgId);
    const id = newId("wh");
    const secret = `whsec_${randomSecret(32)}`;
    const t = now();
    this.ctx.db
      .prepare("INSERT INTO webhooks (id, org_id, name, url, events, secret_sealed, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
      .run(id, orgId, input.name, input.url, JSON.stringify([...new Set(input.events)]), seal(this.ring(), secret, SEAL_PURPOSE, id), userId, t, t);
    this.ctx.audit({ actorId: userId, orgId, action: "webhook.create", target: id });
    this.enqueue(this.hook(orgId, id), "ping", `ping:${id}:${t}`, { message: "Meldiq is sending this workspace's events here" });
    return { webhook: this.hookView(this.hook(orgId, id)), secret };
  }

  updateHook(orgId: string, userId: string, id: string, input: { name: string; url: string; events: string[]; enabled: boolean }) {
    this.requireManager(orgId, userId);
    this.hook(orgId, id);
    const problem = webhookUrlProblem(input.url);
    if (problem) throw badRequest(problem);
    this.ctx.db
      .prepare("UPDATE webhooks SET name = ?, url = ?, events = ?, enabled = ?, disabled_reason = CASE WHEN ? THEN NULL ELSE disabled_reason END, consecutive_failures = CASE WHEN ? THEN 0 ELSE consecutive_failures END, updated_at = ? WHERE id = ?")
      .run(input.name, input.url, JSON.stringify([...new Set(input.events)]), input.enabled ? 1 : 0, input.enabled ? 1 : 0, input.enabled ? 1 : 0, now(), id);
    this.ctx.audit({ actorId: userId, orgId, action: "webhook.update", target: id, detail: input.enabled ? "enabled" : "disabled" });
    return this.hookView(this.hook(orgId, id));
  }

  rotateHookSecret(orgId: string, userId: string, id: string) {
    this.requireManager(orgId, userId);
    this.hook(orgId, id);
    const secret = `whsec_${randomSecret(32)}`;
    this.ctx.db.prepare("UPDATE webhooks SET secret_sealed = ?, updated_at = ? WHERE id = ?").run(seal(this.ring(), secret, SEAL_PURPOSE, id), now(), id);
    this.ctx.audit({ actorId: userId, orgId, action: "webhook.rotate_secret", target: id });
    return { secret };
  }

  deleteHook(orgId: string, userId: string, id: string): void {
    this.requireManager(orgId, userId);
    this.hook(orgId, id);
    this.ctx.db.prepare("DELETE FROM webhooks WHERE id = ?").run(id);
    this.ctx.audit({ actorId: userId, orgId, action: "webhook.delete", target: id });
  }

  testHook(orgId: string, userId: string, id: string) {
    this.requireManager(orgId, userId);
    const h = this.hook(orgId, id);
    this.enqueue(h, "ping", `ping:${id}:${now()}`, { message: "A test from Meldiq" });
    return this.hookView(h);
  }

  deliveries(orgId: string, userId: string, id: string) {
    this.requireManager(orgId, userId);
    this.hook(orgId, id);
    return (this.ctx.db.prepare("SELECT * FROM webhook_deliveries WHERE webhook_id = ? ORDER BY created_at DESC LIMIT 50").all(id) as DeliveryRow[]).map((d) => ({
      id: d.id, eventId: d.event_id, event: d.event_type, state: d.state, attempts: d.attempts,
      lastStatus: d.last_status, lastError: d.last_error, createdAt: d.created_at, deliveredAt: d.delivered_at,
    }));
  }

  private enqueue(h: HookRow, type: WebhookEvent, eventId: string, data: object): void {
    const t = now();
    const payload = JSON.stringify({ id: eventId, type, workspaceId: h.org_id, createdAt: t, data });
    this.ctx.db
      .prepare("INSERT OR IGNORE INTO webhook_deliveries (id, webhook_id, org_id, event_id, event_type, payload, state, attempts, next_at, created_at) VALUES (?, ?, ?, ?, ?, ?, 'queued', 0, ?, ?)")
      .run(newId("whd"), h.id, h.org_id, eventId, type, payload, t, t);
  }

  /**
   * A workspace event for its webhooks. [data] holds ids and names only; callers leave out anything private (private
   * channels, restricted documents, private rooms' meetings). Never throws into the caller.
   */
  emit(orgId: string, type: Exclude<WebhookEvent, "ping">, eventId: string, data: object): void {
    try {
      const hooks = this.ctx.db.prepare("SELECT * FROM webhooks WHERE org_id = ? AND enabled = 1").all(orgId) as HookRow[];
      for (const h of hooks) if ((JSON.parse(h.events) as string[]).includes(type)) this.enqueue(h, type, eventId, data);
    } catch (e) {
      this.ctx.log.error({ errorType: errorKind(e) }, "webhook event not recorded");
    }
  }

  /** Signs and sends due deliveries; retries with backoff; pauses a webhook that keeps failing. Exposed for tests. */
  async tick(batch = 20): Promise<number> {
    const t = now();
    this.ctx.db.prepare("DELETE FROM webhook_deliveries WHERE created_at < ?").run(t - DEV_LIMITS.deliveryRetentionDays * 86_400_000);
    const due = this.ctx.db
      .prepare("SELECT * FROM webhook_deliveries WHERE (state = 'queued' AND next_at <= ?) OR (state = 'sending' AND lease_until < ?) ORDER BY next_at LIMIT ?")
      .all(t, t, batch) as DeliveryRow[];
    let sent = 0;
    for (const d of due) {
      if (!this.ctx.db.prepare("UPDATE webhook_deliveries SET state = 'sending', lease_until = ?, attempts = attempts + 1 WHERE id = ? AND state IN ('queued','sending')").run(t + DEV_LIMITS.leaseMs, d.id).changes) continue;
      const h = this.ctx.db.prepare("SELECT * FROM webhooks WHERE id = ?").get(d.webhook_id) as HookRow | undefined;
      const attempts = d.attempts + 1;
      if (!h || !h.enabled || !this.ctx.authz.can(h.org_id, h.created_by, "developers.manage")) {
        this.ctx.db.prepare("UPDATE webhook_deliveries SET state = 'failed', last_error = ?, lease_until = NULL WHERE id = ?").run(!h ? "webhook_deleted" : !h.enabled ? "webhook_paused" : "owner_not_allowed", d.id);
        continue;
      }
      const ts = String(Math.floor(now() / 1000));
      const secret = open(this.ring(), h.secret_sealed, SEAL_PURPOSE, h.id);
      const headers = {
        "meldiq-webhook": h.id, "meldiq-event": d.event_type, "meldiq-delivery": d.event_id, "meldiq-timestamp": ts,
        "meldiq-signature": `v1=${hmacSha256Hex(secret, `${ts}.${d.payload}`)}`,
      };
      const r = await this.sender(h.url, d.payload, headers);
      sent++;
      this.ctx.usage?.add(h.org_id, "webhooks.deliveries");
      const ok = "status" in r && r.status >= 200 && r.status < 300;
      this.ctx.metrics.recordProvider("webhooks", ok ? undefined : { code: "status" in r ? `http_${r.status}` : r.error });
      if (ok) {
        this.ctx.db.prepare("UPDATE webhook_deliveries SET state = 'delivered', last_status = ?, last_error = NULL, delivered_at = ?, lease_until = NULL WHERE id = ?").run((r as { status: number }).status, now(), d.id);
        this.ctx.db.prepare("UPDATE webhooks SET consecutive_failures = 0, verified_at = COALESCE(verified_at, ?) WHERE id = ?").run(now(), h.id);
        continue;
      }
      const status = "status" in r ? r.status : null;
      const error = "error" in r ? r.error : `http_${status}`;
      const giveUp = attempts >= DEV_LIMITS.maxAttempts || error === "invalid_url";
      this.ctx.db
        .prepare("UPDATE webhook_deliveries SET state = ?, last_status = ?, last_error = ?, next_at = ?, lease_until = NULL WHERE id = ?")
        .run(giveUp ? "failed" : "queued", status, error, now() + retryDelayMs(attempts), d.id);
      const failures = h.consecutive_failures + 1;
      this.ctx.db
        .prepare("UPDATE webhooks SET consecutive_failures = ?, enabled = CASE WHEN ? THEN 0 ELSE enabled END, disabled_reason = CASE WHEN ? THEN ? ELSE disabled_reason END WHERE id = ?")
        .run(failures, failures >= DEV_LIMITS.pauseAfterFailures ? 1 : 0, failures >= DEV_LIMITS.pauseAfterFailures ? 1 : 0, `Paused after ${failures} failed deliveries in a row`, h.id);
    }
    return sent;
  }

  start(intervalMs: number): void {
    if (this.timer || intervalMs <= 0) return;
    this.timer = setInterval(() => {
      if (this.running || !this.ctx.db.open) return;
      this.running = true;
      this.ctx.metrics
        .timeJob("webhooks.deliver", () => this.tick())
        .catch((e) => this.ctx.log.error({ errorType: errorKind(e) }, "webhook delivery failed"))
        .finally(() => {
          this.running = false;
        });
    }, intervalMs);
    this.timer.unref();
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }
}
