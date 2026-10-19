import crypto from "node:crypto";
import { z } from "zod";
import { ApiError, conflict, forbidden, newId, notFound } from "../util.js";
import type { Kernel } from "./kernel.js";
import { publishToChannel } from "./notifications.js";
import { listOf } from "./policy.js";
import { HOOKS_CONNECTOR } from "./providers/incoming.js";
import type { HookRow } from "./store.js";
import { hmacSha256Hex, open, randomSecret, safeEqual, seal } from "./tokens.js";

/**
 * Incoming webhooks (docs/INTEGRATIONS.md › Incoming webhooks): a secret URL per channel that a workspace's own tools
 * post to. One connector, many sources (CI servers, monitoring, automation services), each honestly a generic webhook.
 *
 * - Authentication: `Authorization: Bearer <secret>`, or better an HMAC: `X-Meldiq-Timestamp: <ms>` and
 *   `X-Meldiq-Signature: sha256=<hex HMAC-SHA256 of "<timestamp>.<raw body>" with the secret>` (rejected after 5 minutes,
 *   so a captured request can't be replayed later).
 * - Duplicates: an `Idempotency-Key` (or `X-Meldiq-Delivery`) header posts once per key.
 * - Body: JSON `{ "title": "…", "text": "…", "url": "https://…", "actor": "…" }`; `text` alone works too (the first
 *   line becomes the title), which is what most tools' "send a webhook" steps produce.
 * Every post is checked again before it appears: the app is installed and allowed, the webhook is on, its channel still
 * exists in the workspace, and the person who made it can still manage apps.
 */
const SECRET_PURPOSE = "incoming-webhook-secret";
const SIGNATURE_SKEW_MS = 5 * 60_000;
const MAX_HOOKS_PER_WORKSPACE = 50;
const POSTS_PER_HOOK_PER_MINUTE = 60;

const zBody = z
  .object({
    title: z.string().trim().max(500).optional(),
    text: z.string().trim().max(5000).optional(),
    url: z.string().trim().max(2000).optional(),
    actor: z.string().trim().max(200).optional(),
  })
  .passthrough();

function hookUrl(k: Kernel, id: string): string {
  return `${k.deps.config.publicUrl}/v1/integrations/hooks/${id}`;
}

function view(k: Kernel, h: HookRow) {
  const channel = (k.deps.db.prepare("SELECT name FROM conversations WHERE id = ?").get(h.conversation_id) as { name: string | null } | undefined)?.name ?? null;
  return {
    id: h.id,
    name: h.name,
    conversationId: h.conversation_id,
    channelName: channel,
    url: hookUrl(k, h.id),
    active: h.active === 1,
    createdBy: h.created_by,
    createdAt: h.created_at,
    lastDeliveryAt: h.last_delivery_at,
    lastError: h.last_error,
  };
}

async function requireHooks(k: Kernel, orgId: string, userId: string) {
  const connector = k.connector(HOOKS_CONNECTOR);
  const { perms } = k.member(orgId, userId);
  k.require(perms, "integrations.configure", "Only people who can configure apps can manage webhooks");
  await k.requireAllowed(orgId, connector.def);
  const install = await k.requireInstalled(orgId, connector.def);
  if (!listOf(install.granted_caps).includes("notify.channels")) throw new ApiError(403, "capability_not_granted", "Posting to channels isn't allowed for incoming webhooks here");
  return { connector, install, perms };
}

export async function listHooks(k: Kernel, orgId: string, userId: string) {
  const { perms } = k.member(orgId, userId);
  if (!perms.has("integrations.configure") && !perms.has("integrations.activity")) throw forbidden("Your role doesn't include app settings");
  return (await k.deps.store.hooks(orgId)).map((h) => view(k, h));
}

/** Creates a webhook for a channel the admin is in. The secret is shown once, here, and stored sealed. */
export async function createHook(k: Kernel, orgId: string, userId: string, body: { conversationId: string; name: string }) {
  await requireHooks(k, orgId, userId);
  const conv = k.deps.authz.conversation(body.conversationId);
  if (!conv || conv.type !== "CHANNEL" || conv.org_id !== orgId || !k.deps.authz.isMember(conv.id, userId)) throw notFound("Channel not found");
  if (conv.archived_at || k.deps.authz.conversationScopeArchived(conv)) throw conflict("That channel is archived");
  const existingHooks = await k.deps.store.hooks(orgId);
  if (existingHooks.length >= MAX_HOOKS_PER_WORKSPACE) throw conflict(`A workspace can have at most ${MAX_HOOKS_PER_WORKSPACE} webhooks`);
  k.deps.entitlements?.check("integrations.webhooks", existingHooks.filter((h) => h.active === 1).length);
  const id = newId("hook");
  const secret = randomSecret(32);
  const t = k.now;
  const row: HookRow = {
    id,
    workspace_id: orgId,
    conversation_id: conv.id,
    name: body.name.trim().slice(0, 80),
    secret: seal(k.deps.config.keyring!, secret, SECRET_PURPOSE, id),
    created_by: userId,
    created_at: t,
    updated_at: t,
    active: 1,
    last_delivery_at: null,
    last_error: null,
  };
  await k.deps.store.insertHook(row);
  k.deps.audit({ actorId: userId, orgId, action: "integration.hook.create", target: id, detail: row.name });
  return { hook: view(k, row), secret };
}

async function ownedHook(k: Kernel, orgId: string, hookId: string): Promise<HookRow> {
  const h = await k.deps.store.hook(hookId);
  if (!h || h.workspace_id !== orgId) throw notFound("Webhook not found");
  return h;
}

/** A new secret (the old one stops working at once), shown once. */
export async function rotateHook(k: Kernel, orgId: string, userId: string, hookId: string) {
  await requireHooks(k, orgId, userId);
  const h = await ownedHook(k, orgId, hookId);
  const secret = randomSecret(32);
  await k.deps.store.updateHook(h.id, { secret: seal(k.deps.config.keyring!, secret, SECRET_PURPOSE, h.id), updated_at: k.now, active: 1, last_error: null });
  k.deps.audit({ actorId: userId, orgId, action: "integration.hook.rotate", target: h.id, detail: h.name });
  return { hook: view(k, (await k.deps.store.hook(h.id))!), secret };
}

export async function deleteHook(k: Kernel, orgId: string, userId: string, hookId: string) {
  const { perms } = k.member(orgId, userId);
  k.require(perms, "integrations.configure", "Only people who can configure apps can manage webhooks");
  const h = await ownedHook(k, orgId, hookId);
  await k.deps.store.deleteHook(h.id);
  k.deps.audit({ actorId: userId, orgId, action: "integration.hook.delete", target: h.id, detail: h.name });
  return { ok: true };
}

export type HookOutcome = { status: 202 | 200 | 400 | 401 | 404 | 409 | 429; body: Record<string, unknown> };

/** A tool posting to a webhook. Unknown or switched-off webhooks answer 404; a wrong secret or signature 401. */
export async function receiveHook(k: Kernel, hookId: string, raw: Buffer, headers: Record<string, string | undefined>): Promise<HookOutcome> {
  if (!/^hook_[A-Za-z0-9_-]{10,40}$/.test(hookId)) return { status: 404, body: { error: "unknown" } };
  const h = await k.deps.store.hook(hookId);
  const ring = k.deps.config.keyring;
  if (!h || h.active !== 1 || !ring) return { status: 404, body: { error: "unknown" } };
  let secret: string;
  try {
    secret = open(ring, h.secret, SECRET_PURPOSE, h.id);
  } catch {
    return { status: 404, body: { error: "unknown" } };
  }
  const auth = headers.authorization ?? "";
  const sig = headers["x-meldiq-signature"] ?? "";
  const ts = headers["x-meldiq-timestamp"] ?? "";
  let ok = false;
  if (sig) {
    ok = /^sha256=[0-9a-f]{64}$/.test(sig) && /^\d{10,16}$/.test(ts) && Math.abs(k.now - Number(ts)) <= SIGNATURE_SKEW_MS &&
      safeEqual(sig, `sha256=${hmacSha256Hex(secret, Buffer.concat([Buffer.from(`${ts}.`), raw]))}`);
  } else if (auth.startsWith("Bearer ")) {
    ok = safeEqual(auth.slice(7).trim(), secret);
  }
  if (!ok) return { status: 401, body: { error: "unverified" } };
  try {
    k.rate(`hook:${h.id}`, POSTS_PER_HOOK_PER_MINUTE);
  } catch {
    return { status: 429, body: { error: "rate_limited" } };
  }
  let parsed: z.infer<typeof zBody>;
  try {
    const r = zBody.safeParse(JSON.parse(raw.toString("utf8")));
    if (!r.success) return { status: 400, body: { error: "invalid" } };
    parsed = r.data;
  } catch {
    return { status: 400, body: { error: "invalid" } };
  }
  const lines = (parsed.text ?? "").split("\n");
  const title = (parsed.title || lines[0] || "").trim();
  if (!title) return { status: 400, body: { error: "title or text required" } };
  // With a title, the text is the summary; with text alone, its first line is the title and the rest the summary.
  const summary = (parsed.title ? parsed.text : lines.slice(1).join(" "))?.trim() || null;
  const url = parsed.url && /^https:\/\//.test(parsed.url) ? parsed.url : null;
  // Checked again now: the app, the channel and the person who made the webhook.
  const connector = k.connectors.get(HOOKS_CONNECTOR)!;
  const install = await k.deps.store.install(h.workspace_id, HOOKS_CONNECTOR);
  const allowed = await k.requireAllowed(h.workspace_id, connector.def).then(() => true, () => false);
  if (!allowed || !install || install.state !== "installed" || !listOf(install.granted_caps).includes("notify.channels")) {
    return { status: 409, body: { error: "app_off" } };
  }
  const conv = k.deps.authz.conversation(h.conversation_id);
  const pause = async (reason: string) => {
    await k.deps.store.updateHook(h.id, { active: 0, last_error: reason, updated_at: k.now });
    k.deps.audit({ actorId: null, orgId: h.workspace_id, action: "integration.hook.pause", target: h.id, detail: reason });
  };
  if (!conv || conv.type !== "CHANNEL" || conv.org_id !== h.workspace_id) {
    await pause("The channel no longer exists");
    return { status: 409, body: { error: "paused" } };
  }
  if (!k.deps.authz.can(h.workspace_id, h.created_by, "integrations.configure")) {
    await pause("The person who set this up can no longer manage apps");
    return { status: 409, body: { error: "paused" } };
  }
  if (conv.archived_at || k.deps.authz.conversationScopeArchived(conv)) return { status: 409, body: { error: "channel_archived" } };
  // A signed request posts once per signature (its timestamp and body): a captured one can't be replayed within the
  // window, whatever key it carries; to retry, a tool resends the same signed request. With the bearer secret alone,
  // Idempotency-Key makes retries safe.
  const key = sig ? `sig:${sig.slice(7)}` : `key:${(headers["idempotency-key"] ?? headers["x-meldiq-delivery"] ?? "").slice(0, 100)}`;
  const posted = await publishToChannel(k, {
    workspaceId: h.workspace_id,
    conversationId: conv.id,
    connectorId: HOOKS_CONNECTOR,
    sourceId: h.id,
    event: {
      resource: h.id,
      event: "webhook.post",
      title: title.slice(0, 200),
      url,
      summary: summary?.slice(0, 300) ?? null,
      actor: parsed.actor?.slice(0, 60) || h.name,
      occurredAt: k.now,
      dedupeKey: key === "key:" ? `post:${crypto.randomUUID()}` : key,
    },
  });
  if (posted) await k.deps.store.updateHook(h.id, { last_delivery_at: k.now, last_error: null });
  return posted ? { status: 202, body: { accepted: true } } : { status: 200, body: { duplicate: true } };
}
