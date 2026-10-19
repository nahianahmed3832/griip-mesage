import { ApiError } from "../util.js";
import type { ActionInput } from "./connector.js";
import { type Kernel, providerFailure } from "./kernel.js";
import type { AccountRow } from "./store.js";
import type { Page, Resource, ResourceDetails } from "./types.js";

/**
 * Using a connected account (docs/INTEGRATIONS.md › In-Meldiq actions): search, browse, metadata, details, link previews
 * and explicit actions. Every call runs the full authorisation chain (Kernel.requireUsable) and uses only the caller's own
 * account, so what comes back is exactly what the provider lets that person see. Results go to the caller only; nothing
 * is cached or stored on the server. Sharing a link into a conversation is done by the app inside the end-to-end
 * encrypted message, by the person's choice.
 */

/** Provider calls per person per connector per minute, and per workspace per connector (bounds cost and abuse). */
const PER_USER_PER_MINUTE = 60;
const PER_WORKSPACE_PER_MINUTE = 600;

async function use<T>(k: Kernel, orgId: string, userId: string, connectorId: string, capability: string, fn: (ctx: ReturnType<Kernel["callContext"]>) => Promise<T>): Promise<T> {
  const { connector, account } = await k.requireUsable(orgId, userId, connectorId, capability);
  k.rate(`use:${userId}:${connectorId}`, PER_USER_PER_MINUTE);
  k.rate(`use-ws:${orgId}:${connectorId}`, PER_WORKSPACE_PER_MINUTE);
  try {
    const out = await fn(k.callContext(connector, account));
    await touch(k, account);
    return out;
  } catch (e) {
    throw providerFailure(e, connector.def.name);
  }
}

async function touch(k: Kernel, account: AccountRow): Promise<void> {
  // At most once a minute, so busy searching doesn't write on every request.
  if (!account.last_used_at || account.last_used_at < k.now - 60_000) await k.deps.store.updateAccount(account.id, null, { last_used_at: k.now });
}

/** Thumbnails are short-lived provider links for the person's own picker; they never go into a message. */
const forPicker = (r: Resource): Resource => r;

export async function search(k: Kernel, orgId: string, userId: string, connectorId: string, q: string, cursor: string | null, filter: string | null): Promise<Page<Resource>> {
  const c = k.connector(connectorId);
  if (!c.search) throw new ApiError(409, "unsupported", `${c.def.name} doesn't support search`);
  const op = c.search;
  return use(k, orgId, userId, connectorId, op.capability, async (ctx) => {
    const page = await op.run(ctx, q.slice(0, 200), cursor, filter);
    return { items: page.items.map(forPicker), cursor: page.cursor };
  });
}

/** Results per app in a search across apps, and how long one app may take before it is left out. */
const SEARCH_ALL_PER_APP = 5;
const SEARCH_ALL_TIMEOUT_MS = 6000;

/**
 * One search across every app this person has connected here (docs/INTEGRATIONS.md › Search). Each app is searched with
 * the person's own account and the same checks as a single search; an app that fails or is slow is reported, not
 * fatal. Message content is never part of this: Meldiq can't search end-to-end encrypted messages on the server.
 */
export async function searchAll(k: Kernel, orgId: string, userId: string, q: string) {
  k.member(orgId, userId);
  k.rate(`search-all:${userId}`, 20);
  const mine = await k.deps.store.accounts({ workspace_id: orgId, user_id: userId, owner_type: "member" });
  const results = await Promise.all(
    mine
      .filter((a) => a.status === "active" && k.connectors.get(a.connector_id)?.search)
      .map(async (a) => {
        const c = k.connectors.get(a.connector_id)!;
        const timeout = new Promise<never>((_, reject) => setTimeout(() => reject(new ApiError(504, "timeout", `${c.def.name} took too long`)), SEARCH_ALL_TIMEOUT_MS).unref());
        try {
          const page = await Promise.race([search(k, orgId, userId, a.connector_id, q, null, null), timeout]);
          return { connectorId: c.def.id, appName: c.def.name, icon: c.def.icon, items: page.items.slice(0, SEARCH_ALL_PER_APP), error: null as string | null };
        } catch (e) {
          return { connectorId: c.def.id, appName: c.def.name, icon: c.def.icon, items: [], error: e instanceof ApiError ? e.message : "Search failed" };
        }
      }),
  );
  return { results };
}

export async function browse(k: Kernel, orgId: string, userId: string, connectorId: string, containerId: string | null, cursor: string | null): Promise<Page<Resource>> {
  const c = k.connector(connectorId);
  if (!c.browse) throw new ApiError(409, "unsupported", `${c.def.name} doesn't support browsing`);
  const op = c.browse;
  return use(k, orgId, userId, connectorId, op.capability, (ctx) => op.run(ctx, containerId, cursor));
}

export async function getResource(k: Kernel, orgId: string, userId: string, connectorId: string, id: string): Promise<Resource> {
  const c = k.connector(connectorId);
  if (!c.get) throw new ApiError(409, "unsupported", `${c.def.name} can't look items up`);
  const op = c.get;
  return use(k, orgId, userId, connectorId, op.capability, (ctx) => op.run(ctx, id));
}

export async function details(k: Kernel, orgId: string, userId: string, connectorId: string, id: string): Promise<ResourceDetails> {
  const c = k.connector(connectorId);
  if (!c.details) throw new ApiError(409, "unsupported", `${c.def.name} has no details to show`);
  const op = c.details;
  return use(k, orgId, userId, connectorId, op.capability, (ctx) => op.run(ctx, id));
}

/**
 * A preview of a link someone shared, using the viewer's own account: the server never fetches the pasted URL, it only
 * recognises the provider's link format and asks the provider, as the viewer, for that item. People without access (or
 * without a connection) get nothing, so a title is never revealed to someone the provider wouldn't show it to.
 */
export async function unfurl(k: Kernel, orgId: string, userId: string, rawUrl: string): Promise<{ connectorId: string; resource: Resource } | { connectorId: string | null; resource: null; reason: string }> {
  let url: URL;
  try {
    url = new URL(rawUrl);
  } catch {
    throw new ApiError(400, "bad_request", "That isn't a link");
  }
  for (const c of k.connectors.values()) {
    const id = c.matchUrl?.(new URL(url.toString()));
    if (!id || !c.get || !c.unfurlCapability) continue;
    const account = await k.deps.store.accountFor(orgId, c.def.id, `member:${userId}`);
    if (!account) return { connectorId: c.def.id, resource: null, reason: "not_connected" };
    try {
      const op = c.get;
      const resource = await use(k, orgId, userId, c.def.id, c.unfurlCapability, (ctx) => op.run(ctx, id));
      return { connectorId: c.def.id, resource };
    } catch (e) {
      const code = e instanceof ApiError ? e.code : "unavailable";
      // No access looks the same as no such item: nothing about it is revealed.
      return { connectorId: c.def.id, resource: null, reason: code === "provider_forbidden" ? "not_found" : code };
    }
  }
  return { connectorId: null, resource: null, reason: "unsupported" };
}

/** An explicit action the person confirmed in the app (create a Jira issue from a message, comment on one). */
export async function runAction(k: Kernel, orgId: string, userId: string, connectorId: string, actionKey: string, input: ActionInput): Promise<Resource> {
  const c = k.connector(connectorId);
  const action = c.actions?.[actionKey];
  if (!action) throw new ApiError(404, "not_found", `${c.def.name} has no action "${actionKey}"`);
  k.rate(`action:${userId}`, 20);
  const result = await use(k, orgId, userId, connectorId, action.capability, (ctx) => action.run(ctx, input));
  // What was done, never what was written.
  k.deps.audit({ actorId: userId, orgId, action: "integration.action", target: connectorId, detail: `${actionKey} ${result.id}` });
  return result;
}
