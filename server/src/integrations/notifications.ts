import crypto from "node:crypto";
import { ApiError, conflict, forbidden, newId, notFound } from "../util.js";
import type { Connector } from "./connector.js";
import { type Kernel, providerFailure } from "./kernel.js";
import { listOf } from "./policy.js";
import type { AccountRow, ChannelEventRow, JobRow, SubscriptionRow } from "./store.js";
import type { NormalizedEvent } from "./types.js";

/**
 * Events into channels (docs/INTEGRATIONS.md › Webhooks and events). An admin links the workspace to the provider
 * (a shared connection), then subscribes channels to resources and event types. Inbound webhooks are verified by
 * signature, de-duplicated by delivery id, reduced at once to titles and links (the rest of the payload is dropped),
 * queued as idempotent jobs with retries, and delivered only after the channel, the workspace's app, the connection and
 * the subscription's creator are checked again. Notifications are server-visible (not end-to-end encrypted) and say so.
 */
const MAX_SUBSCRIPTIONS_PER_WORKSPACE = 100;
const MAX_JOB_ATTEMPTS = 6;
const JOB_LEASE_MS = 60_000;
const JOBS_PER_TICK = 25;
const WORKSPACE_OWNER_KEY = "workspace";

async function workspaceConnection(k: Kernel, orgId: string, connectorId: string): Promise<AccountRow | null> {
  return k.deps.store.accountFor(orgId, connectorId, WORKSPACE_OWNER_KEY);
}

function notifyCapability(k: Kernel, connector: Connector): string {
  const c = connector.def.capabilities.find((x) => x.accountType === "workspace" && x.kind === "notify");
  if (!c || !connector.webhook || !connector.linking) throw new ApiError(409, "unsupported", `${connector.def.name} doesn't post into channels`);
  return c.key;
}

/** The checks for managing an app's workspace connection and its channel notifications. */
async function requireManage(k: Kernel, orgId: string, userId: string, connectorId: string, permission: "integrations.connect_workspace" | "integrations.configure") {
  const connector = k.connector(connectorId);
  const capability = notifyCapability(k, connector);
  const { perms } = k.member(orgId, userId);
  k.require(perms, permission, permission === "integrations.configure" ? "Only people who can configure apps can do that" : "Only people who can connect workspace accounts can do that");
  await k.requireAllowed(orgId, connector.def);
  const install = await k.requireInstalled(orgId, connector.def);
  if (!listOf(install.granted_caps).includes(capability)) throw new ApiError(403, "capability_not_granted", `Channel notifications aren't allowed for ${connector.def.name} in this workspace`);
  return { connector, install, perms };
}

/** The admin's own connection, which proves what they can reach at the provider. */
async function adminAccount(k: Kernel, orgId: string, userId: string, connector: Connector): Promise<AccountRow> {
  const own = await k.deps.store.accountFor(orgId, connector.def.id, `member:${userId}`);
  if (!own) throw new ApiError(409, "not_connected", `Connect your own ${connector.def.name} account first: it shows which organisations you can link`);
  if (own.status !== "active") throw new ApiError(409, "reconnect_required", `Your ${connector.def.name} connection needs to be renewed. Connect it again.`);
  return own;
}

export async function linkTargets(k: Kernel, orgId: string, userId: string, connectorId: string) {
  const { connector } = await requireManage(k, orgId, userId, connectorId, "integrations.connect_workspace");
  const own = await adminAccount(k, orgId, userId, connector);
  try {
    const targets = await connector.linking!.targets(k.callContext(connector, own));
    const linked = await workspaceConnection(k, orgId, connector.def.id);
    return { targets, linkedTo: linked ? { id: linked.external_id, label: linked.external_label } : null, installUrl: connector.linking!.installUrl() };
  } catch (e) {
    throw providerFailure(e, connector.def.name);
  }
}

/** Links the workspace to a provider target the admin's own account can reach (checked again right now). */
export async function linkWorkspace(k: Kernel, orgId: string, userId: string, connectorId: string, targetId: string) {
  const { connector } = await requireManage(k, orgId, userId, connectorId, "integrations.connect_workspace");
  const own = await adminAccount(k, orgId, userId, connector);
  let targets: { id: string; label: string }[];
  try {
    targets = await connector.linking!.targets(k.callContext(connector, own));
  } catch (e) {
    throw providerFailure(e, connector.def.name);
  }
  const target = targets.find((t) => t.id === targetId);
  if (!target) throw forbidden(`Your ${connector.def.name} account can't access that. Ask someone who can, or install the app there first.`);
  const t = k.now;
  const fields = {
    external_id: target.id,
    external_label: target.label.slice(0, 200),
    user_id: userId,
    status: "active" as const,
    scopes: "[]",
    meta: JSON.stringify({ linkedBy: own.id }),
    updated_at: t,
    last_validated_at: t,
    last_error: null,
    last_error_at: null,
  };
  const existing = await workspaceConnection(k, orgId, connector.def.id);
  if (existing) {
    if (existing.external_id !== target.id) {
      // Pointing at a different organisation: the old subscriptions were for its repositories.
      for (const s of await k.deps.store.subscriptions({ account_id: existing.id })) await k.deps.store.deleteSubscription(s.id);
    }
    await k.deps.store.updateAccount(existing.id, null, fields);
  } else {
    const row: AccountRow = {
      ...fields,
      id: newId("acct"),
      workspace_id: orgId,
      connector_id: connector.def.id,
      owner_type: "workspace",
      owner_key: WORKSPACE_OWNER_KEY,
      rev: 1,
      created_at: t,
      last_used_at: null,
    };
    if (!(await k.deps.store.insertAccount(row))) throw conflict("Someone else linked this app at the same moment. Reload and check.");
  }
  k.deps.audit({ actorId: userId, orgId, action: "integration.link", target: connector.def.id, detail: target.label.slice(0, 100) });
  return { linkedTo: { id: target.id, label: target.label } };
}

/** What the admin may watch: the linked target's resources as their own account sees them (never the shared link's). */
async function watchable(k: Kernel, orgId: string, userId: string, connector: Connector) {
  const link = await workspaceConnection(k, orgId, connector.def.id);
  if (!link || link.status !== "active") throw new ApiError(409, "not_linked", `Link this workspace to ${connector.def.name} first`);
  const own = await adminAccount(k, orgId, userId, connector);
  return { link, list: (cursor: string | null) => connector.linking!.resources(k.callContext(connector, own), link.external_id, cursor) };
}

export async function subscriptionResources(k: Kernel, orgId: string, userId: string, connectorId: string, cursor: string | null) {
  const { connector } = await requireManage(k, orgId, userId, connectorId, "integrations.configure");
  const { list } = await watchable(k, orgId, userId, connector);
  try {
    return await list(cursor);
  } catch (e) {
    throw providerFailure(e, connector.def.name);
  }
}

function channelName(k: Kernel, id: string): string | null {
  return (k.deps.db.prepare("SELECT name FROM conversations WHERE id = ?").get(id) as { name: string | null } | undefined)?.name ?? null;
}

const subscriptionView = (k: Kernel, s: SubscriptionRow) => ({
  id: s.id,
  connectorId: s.connector_id,
  conversationId: s.conversation_id,
  channelName: channelName(k, s.conversation_id),
  resource: s.resource,
  events: listOf(s.events),
  active: s.active === 1,
  createdBy: s.created_by,
  createdAt: s.created_at,
  lastDeliveryAt: s.last_delivery_at,
  lastError: s.last_error,
});

export async function listSubscriptions(k: Kernel, orgId: string, userId: string, connectorId: string) {
  const { perms } = k.member(orgId, userId);
  if (!perms.has("integrations.configure") && !perms.has("integrations.activity")) throw forbidden("Your role doesn't include app settings");
  return (await k.deps.store.subscriptions({ workspace_id: orgId, connector_id: k.def(connectorId).id })).map((s) => subscriptionView(k, s));
}

export async function createSubscription(k: Kernel, orgId: string, userId: string, connectorId: string, body: { conversationId: string; resource: string; events: string[] }) {
  const { connector } = await requireManage(k, orgId, userId, connectorId, "integrations.configure");
  const { link, list } = await watchable(k, orgId, userId, connector);
  // The destination: a live channel of this workspace that the admin is in (never a DM, group or another workspace).
  const conv = k.deps.authz.conversation(body.conversationId);
  if (!conv || conv.type !== "CHANNEL" || conv.org_id !== orgId || !k.deps.authz.isMember(conv.id, userId)) throw notFound("Channel not found");
  if (conv.archived_at || k.deps.authz.conversationScopeArchived(conv)) throw conflict("That channel is archived");
  const known = new Set(connector.webhook!.eventTypes.map((e) => e.key));
  const events = [...new Set(body.events)].sort();
  if (!events.length || events.some((e) => !known.has(e))) throw new ApiError(400, "bad_request", "Choose events from the app's list");
  // The resource must be one the admin's own account can see within the linked target (checked again right now).
  let found = false;
  let cursor: string | null = null;
  try {
    for (let page = 0; page < 10 && !found; page++) {
      const r = await list(cursor);
      found = r.items.some((x) => x.id === body.resource);
      cursor = r.cursor;
      if (!cursor) break;
    }
  } catch (e) {
    throw providerFailure(e, connector.def.name);
  }
  if (!found) throw forbidden(`Your ${connector.def.name} account can't see "${body.resource}" in the linked organisation`);
  const existing = await k.deps.store.subscriptions({ workspace_id: orgId });
  if (existing.length >= MAX_SUBSCRIPTIONS_PER_WORKSPACE) throw conflict(`A workspace can have at most ${MAX_SUBSCRIPTIONS_PER_WORKSPACE} app notifications`);
  const isSame = (s: SubscriptionRow) => s.connector_id === connector.def.id && s.conversation_id === conv.id && s.resource === body.resource;
  const t = k.now;
  const update = async (same: SubscriptionRow) => {
    await k.deps.store.updateSubscription(same.id, { events: JSON.stringify(events), active: 1, updated_at: t, last_error: null, account_id: link.id });
    k.deps.audit({ actorId: userId, orgId, action: "integration.subscription.update", target: same.id, detail: `${connector.def.id} ${body.resource}` });
    return subscriptionView(k, (await k.deps.store.subscription(same.id))!);
  };
  const same = existing.find(isSame);
  if (same) return update(same);
  k.deps.entitlements?.check("integrations.notifications", existing.filter((s) => s.active === 1).length);
  const row: SubscriptionRow = {
    id: newId("sub"),
    workspace_id: orgId,
    connector_id: connector.def.id,
    account_id: link.id,
    conversation_id: conv.id,
    resource: body.resource,
    events: JSON.stringify(events),
    created_by: userId,
    created_at: t,
    updated_at: t,
    active: 1,
    last_delivery_at: null,
    last_error: null,
  };
  if (!(await k.deps.store.insertSubscription(row))) {
    // Someone saved the same channel and resource at the same moment (unique index): change theirs instead.
    const raced = (await k.deps.store.subscriptions({ workspace_id: orgId, connector_id: connector.def.id, conversation_id: conv.id })).find(isSame);
    if (!raced) throw conflict("Someone changed this notification at the same moment. Reload and check.");
    return update(raced);
  }
  k.deps.audit({ actorId: userId, orgId, action: "integration.subscription.create", target: row.id, detail: `${connector.def.id} ${body.resource}` });
  return subscriptionView(k, row);
}

export async function deleteSubscription(k: Kernel, orgId: string, userId: string, subscriptionId: string) {
  const sub = await k.deps.store.subscription(subscriptionId);
  if (!sub || sub.workspace_id !== orgId) throw notFound("Notification setting not found");
  const { perms } = k.member(orgId, userId);
  k.require(perms, "integrations.configure", "Only people who can configure apps can do that");
  await k.deps.store.deleteSubscription(sub.id);
  k.deps.audit({ actorId: userId, orgId, action: "integration.subscription.delete", target: sub.id, detail: `${sub.connector_id} ${sub.resource}` });
  return { ok: true };
}

// ───────────── Inbound webhooks ─────────────
export type WebhookOutcome = { status: 202 | 200 | 401 | 400 | 404; body: Record<string, unknown> };

/**
 * A provider's webhook delivery. Refused unless its signature verifies; a delivery id seen before is acknowledged and
 * ignored (duplicates and replays); only normalised titles and links are kept, in an idempotent job.
 */
export async function receiveWebhook(k: Kernel, connectorId: string, raw: Buffer, headers: Record<string, string | undefined>): Promise<WebhookOutcome> {
  const connector = k.connectors.get(connectorId);
  if (!connector?.webhook) return { status: 404, body: { error: "unknown" } };
  const verified = connector.webhook.verify(raw, headers);
  if (!verified) {
    k.deps.log.warn({ connector: connectorId }, "webhook refused: signature or freshness check failed");
    return { status: 401, body: { error: "unverified" } };
  }
  let payload: unknown;
  try {
    payload = JSON.parse(raw.toString("utf8"));
  } catch {
    return { status: 400, body: { error: "invalid" } };
  }
  const t = k.now;
  const deliveryKey = `${connectorId}:${verified.deliveryId}`;
  if (!(await k.deps.store.acceptDelivery(deliveryKey, connectorId, verified.event, t, t + k.deps.config.deliveryRetentionDays * 86_400_000))) {
    return { status: 200, body: { duplicate: true } };
  }
  let parsed: ReturnType<typeof connector.webhook.parse>;
  try {
    parsed = connector.webhook.parse(verified.event, payload);
    if (parsed?.events.length) {
      await k.deps.store.enqueue({
        id: newId("job"),
        kind: "webhook.deliver",
        workspace_id: null,
        idempotency_key: `deliver:${deliveryKey}`,
        payload: JSON.stringify({ connectorId, deliveryKey, externalId: parsed.externalId, events: parsed.events }),
        state: "queued",
        attempts: 0,
        next_at: t,
        lease_until: null,
        last_error: null,
        created_at: t,
        updated_at: t,
      });
    }
  } catch (e) {
    // Not queued: forget the delivery id so the provider's retry of it isn't taken for a duplicate.
    await k.deps.store.releaseDelivery(deliveryKey);
    throw e;
  }
  if (!parsed || !parsed.events.length) {
    await k.deps.store.markDelivery(deliveryKey, "ignored");
    return { status: 202, body: { accepted: true, events: 0 } };
  }
  setImmediate(() => void runJobs(k).catch((e) => k.deps.log.error({ err: e }, "integration jobs failed")));
  return { status: 202, body: { accepted: true, events: parsed.events.length } };
}

/** The sandbox's "Send a test event": a signed delivery through the same path a real provider's takes. */
export async function sendTestEvent(k: Kernel, orgId: string, userId: string, resource: string | null) {
  if (!k.sandbox) throw notFound("The sandbox isn't enabled on this server");
  const { connector } = await requireManage(k, orgId, userId, "meldiq-sandbox", "integrations.configure");
  const link = await workspaceConnection(k, orgId, connector.def.id);
  if (!link) throw new ApiError(409, "not_linked", "Link this workspace to the sandbox first");
  const delivery = k.sandbox.signDelivery({
    connection: link.external_id,
    resource: resource ?? "sample/one",
    event: "sample.created",
    title: "Sample item created in the sandbox",
    summary: "A made-up event, to test channel notifications",
    actor: "Sam Sample",
    url: k.sandbox.fileUrl("sbxf-plan"),
    id: crypto.randomUUID(),
  });
  const out = await receiveWebhook(k, connector.def.id, delivery.raw, delivery.headers);
  await runJobs(k);
  return out.body;
}

// ───────────── Jobs ─────────────
/** One run at a time per server: a caller waits for the run in progress, so "run, then read" is never stale. */
const runs = new WeakMap<Kernel, Promise<number>>();

/** Runs due jobs once (the server's timer calls this; tests call it directly). Returns how many ran. */
export function runJobs(k: Kernel): Promise<number> {
  const next = (runs.get(k) ?? Promise.resolve(0)).catch(() => 0).then(() => runJobsOnce(k));
  runs.set(k, next);
  return next;
}

async function runJobsOnce(k: Kernel): Promise<number> {
  const jobs = await k.deps.store.claimJobs(k.now, JOB_LEASE_MS, JOBS_PER_TICK);
  for (const job of jobs) {
    try {
      if (job.kind === "webhook.deliver") await deliver(k, job);
      await k.deps.store.finishJob(job.id, k.now);
    } catch (e) {
      const message = e instanceof ApiError ? e.message : "Delivery failed";
      const next = job.attempts >= MAX_JOB_ATTEMPTS ? null : k.now + Math.min(2 ** job.attempts * 5_000, 30 * 60_000);
      await k.deps.store.retryJob(job.id, next, message.slice(0, 200), k.now);
      k.deps.log.warn({ job: job.id, kind: job.kind, attempts: job.attempts, dead: next === null }, "integration job failed");
    }
  }
  return jobs.length;
}

interface DeliverPayload {
  connectorId: string;
  deliveryKey: string;
  externalId: string;
  events: NormalizedEvent[];
}

async function deliver(k: Kernel, job: JobRow): Promise<void> {
  const p = JSON.parse(job.payload) as DeliverPayload;
  const connector = k.connectors.get(p.connectorId);
  if (!connector) return;
  for (const link of await k.deps.store.workspaceConnections(p.connectorId, p.externalId)) {
    if (link.status !== "active") continue;
    const install = await k.deps.store.install(link.workspace_id, p.connectorId);
    const capability = connector.def.capabilities.find((c) => c.kind === "notify")?.key;
    if (!install || install.state !== "installed" || !capability || !listOf(install.granted_caps).includes(capability)) continue;
    try {
      await k.requireAllowed(link.workspace_id, connector.def);
    } catch {
      continue; // blocked by the platform or the workspace's policy since the delivery arrived
    }
    for (const sub of await k.deps.store.subscriptions({ account_id: link.id })) {
      if (sub.active !== 1) continue;
      const wanted = new Set(listOf(sub.events));
      for (const ev of p.events) {
        if (ev.resource !== sub.resource || !wanted.has(ev.event)) continue;
        await postToChannel(k, connector, sub, ev);
      }
    }
    await k.deps.store.touchInstall(install.id, { last_event_at: k.now });
  }
  await k.deps.store.markDelivery(p.deliveryKey, "done");
}

/** Checks everything again at the moment of posting, then posts once (dedupe key) and tells the channel's members. */
async function postToChannel(k: Kernel, connector: Connector, sub: SubscriptionRow, ev: NormalizedEvent): Promise<void> {
  const { authz } = k.deps;
  const conv = authz.conversation(sub.conversation_id);
  const pause = async (reason: string) => {
    await k.deps.store.updateSubscription(sub.id, { active: 0, last_error: reason, updated_at: k.now });
    k.deps.audit({ actorId: null, orgId: sub.workspace_id, action: "integration.subscription.pause", target: sub.id, detail: reason });
  };
  if (!conv || conv.type !== "CHANNEL" || conv.org_id !== sub.workspace_id) return pause("The channel no longer exists");
  if (conv.archived_at || authz.conversationScopeArchived(conv)) return; // archived: quietly skipped, resumes when restored
  if (!authz.can(sub.workspace_id, sub.created_by, "integrations.configure")) return pause("The person who set this up can no longer manage apps");
  if (await publishToChannel(k, { workspaceId: sub.workspace_id, conversationId: conv.id, connectorId: connector.def.id, sourceId: sub.id, event: ev })) {
    await k.deps.store.updateSubscription(sub.id, { last_delivery_at: k.now, last_error: null });
  }
}

/**
 * Stores one app notification for a channel (once per source and dedupe key) and tells the channel's members. The
 * caller has already checked that it may post there. Returns false when this change was already posted.
 */
export async function publishToChannel(
  k: Kernel,
  p: { workspaceId: string; conversationId: string; connectorId: string; sourceId: string; event: NormalizedEvent },
): Promise<boolean> {
  const ev = p.event;
  const t = k.now;
  const row: ChannelEventRow = {
    id: newId("iev"),
    workspace_id: p.workspaceId,
    conversation_id: p.conversationId,
    connector_id: p.connectorId,
    subscription_id: p.sourceId,
    kind: ev.event,
    title: ev.title.slice(0, 200),
    url: ev.url && /^https:\/\//.test(ev.url) ? ev.url.slice(0, 1000) : null,
    summary: ev.summary?.slice(0, 300) ?? null,
    actor: ev.actor?.slice(0, 60) ?? null,
    occurred_at: ev.occurredAt,
    created_at: t,
    expires_at: t + k.deps.config.eventRetentionDays * 86_400_000,
    dedupe_key: `${p.sourceId}:${ev.dedupeKey}`.slice(0, 300),
  };
  if (!(await k.deps.store.insertEvent(row))) return false;
  const members = (k.deps.db.prepare("SELECT user_id FROM conversation_members WHERE conversation_id = ?").all(p.conversationId) as { user_id: string }[]).map((r) => r.user_id);
  k.deps.hub.sendToUsers(new Set(members), { t: "integration.event", orgId: p.workspaceId, conversationId: p.conversationId });
  return true;
}

/** A channel's app notifications, for its current members only. */
export async function channelEvents(k: Kernel, conversationId: string, userId: string, after: number, limit: number) {
  k.deps.authz.requireMember(conversationId, userId);
  const rows = await k.deps.store.events(conversationId, after, limit);
  return rows.map((r) => {
    const def = k.visibleDefs().find((d) => d.id === r.connector_id);
    // Workspace workflows (docs/WORKFLOWS.md) post under their own label, not an installed app's.
    const workflow = r.connector_id === "workflows";
    // Updates posted with an API key (docs/DEVELOPER_API.md) name the key.
    const api = r.connector_id === "api";
    return {
      id: r.id,
      connectorId: r.connector_id,
      appName: workflow ? "Workflows" : api ? `API · ${r.actor ?? "key"}` : (def?.name ?? r.connector_id),
      icon: workflow ? "bolt" : api ? "code" : (def?.icon ?? "apps"),
      kind: r.kind,
      title: r.title,
      url: r.url,
      summary: r.summary,
      actor: r.actor,
      occurredAt: r.occurred_at,
      createdAt: r.created_at,
    };
  });
}

/** Recent activity for people with integrations.activity: audit entries, notifications posted, failed jobs. */
export async function activity(k: Kernel, orgId: string, userId: string) {
  const { perms } = k.member(orgId, userId);
  k.require(perms, "integrations.activity", "Your role doesn't include app activity");
  const audit = k.deps.db
    .prepare(
      `SELECT a.id, a.at, a.action, a.actor_id, u.display_name AS actor_name, a.target, a.detail FROM audit_log a LEFT JOIN users u ON u.id = a.actor_id
       WHERE a.org_id = ? AND a.action LIKE 'integration.%' ORDER BY a.id DESC LIMIT 50`,
    )
    .all(orgId) as { id: number; at: number; action: string; actor_id: string | null; actor_name: string | null; target: string | null; detail: string | null }[];
  const events = await k.deps.store.recentEvents(orgId, 20);
  const installs = await k.deps.store.installs(orgId);
  return {
    audit: audit.map((r) => ({ id: r.id, at: r.at, action: r.action, actorId: r.actor_id, actorName: r.actor_name, target: r.target, detail: r.detail })),
    notifications: events.map((e) => ({ id: e.id, connectorId: e.connector_id, conversationId: e.conversation_id, channelName: channelName(k, e.conversation_id), title: e.title, createdAt: e.created_at })),
    health: installs
      .filter((i) => i.state === "installed" || i.state === "disabled")
      .map((i) => ({ connectorId: i.connector_id, state: i.state, lastEventAt: i.last_event_at, lastError: i.last_error, lastErrorAt: i.last_error_at })),
  };
}

export async function usage(k: Kernel, orgId: string) {
  const installs = await k.deps.store.installs(orgId);
  const accounts = await k.deps.store.accounts({ workspace_id: orgId });
  return {
    installed: installs.filter((i) => i.state === "installed").length,
    pendingRequests: installs.filter((i) => i.state === "requested").length,
    connectedAccounts: accounts.filter((a) => a.owner_type === "member" && a.status === "active").length,
    workspaceConnections: accounts.filter((a) => a.owner_type === "workspace").length,
    notifications: (await k.deps.store.subscriptions({ workspace_id: orgId })).filter((s) => s.active === 1).length,
    webhooks: (await k.deps.store.hooks(orgId)).filter((h) => h.active === 1).length,
  };
}

/**
 * The workspace's workflows (docs/INTEGRATIONS.md › Workflows): every "when this happens, post there" set up through
 * apps, one list with its trigger, action, owner, last run and problem. Two kinds exist today: an app's events into a
 * channel (subscriptions) and incoming webhooks. The action set is fixed and typed; nobody can run their own code.
 */
export async function workflows(k: Kernel, orgId: string, userId: string) {
  const { perms } = k.member(orgId, userId);
  if (!perms.has("integrations.configure") && !perms.has("integrations.activity")) throw forbidden("Your role doesn't include app settings");
  const nameOf = (id: string) => (k.deps.db.prepare("SELECT display_name FROM users WHERE id = ?").get(id) as { display_name: string } | undefined)?.display_name ?? "Someone";
  const subs = await k.deps.store.subscriptions({ workspace_id: orgId });
  const hooks = await k.deps.store.hooks(orgId);
  return [
    ...subs.map((s) => {
      const c = k.connectors.get(s.connector_id);
      const labels = listOf(s.events).map((e) => c?.webhook?.eventTypes.find((x) => x.key === e)?.label ?? e);
      return {
        id: s.id,
        kind: "app_events" as const,
        connectorId: s.connector_id,
        appName: c?.def.name ?? s.connector_id,
        trigger: `${labels.join(", ")} · ${s.resource}`,
        action: `post_to_channel`,
        conversationId: s.conversation_id,
        channelName: channelName(k, s.conversation_id),
        ownerId: s.created_by,
        ownerName: nameOf(s.created_by),
        active: s.active === 1,
        lastRunAt: s.last_delivery_at,
        lastError: s.last_error,
        createdAt: s.created_at,
      };
    }),
    ...hooks.map((h) => ({
      id: h.id,
      kind: "incoming_webhook" as const,
      connectorId: "incoming-webhooks",
      appName: "Incoming webhooks",
      trigger: h.name,
      action: "post_to_channel",
      conversationId: h.conversation_id,
      channelName: channelName(k, h.conversation_id),
      ownerId: h.created_by,
      ownerName: nameOf(h.created_by),
      active: h.active === 1,
      lastRunAt: h.last_delivery_at,
      lastError: h.last_error,
      createdAt: h.created_at,
    })),
  ].sort((a, b) => b.createdAt - a.createdAt);
}
