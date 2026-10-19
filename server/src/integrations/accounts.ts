import { ApiError, forbidden, newId, notFound, sha256Hex } from "../util.js";
import { scopesFor } from "./catalog.js";
import type { Connector } from "./connector.js";
import { ProviderError } from "./http.js";
import { type Kernel, providerFailure } from "./kernel.js";
import { accessAllows, listOf } from "./policy.js";
import type { AccountRow } from "./store.js";
import { open, pkceChallenge, randomSecret, safeEqual, seal } from "./tokens.js";
import type { TokenSet } from "./types.js";

/**
 * Connecting an external account (operation B in docs/INTEGRATIONS.md): always the member's own account, through the
 * provider's OAuth consent. Workspace (shared) connections are made only by linking (src/integrations/notifications.ts),
 * so a personal account never silently becomes a shared one.
 *
 * Flow and its defences:
 * 1. start (authenticated): checks every rule, records a single-use flow with only the hash of its `state`, a sealed
 *    PKCE verifier and a one-time begin token; returns the begin link.
 * 2. begin (the browser): spends the begin token and sets an HttpOnly cookie that binds the flow to that browser, then
 *    redirects to the provider.
 * 3. callback (the browser): the state must match a live flow, the cookie must match (the provider's redirect can't be
 *    finished in another browser), the flow is spent once whatever happens. Nothing is connected yet: the provider's
 *    code is sealed to the flow and the browser gets a one-time finish code for the app.
 * 4. finish (authenticated, [finish]): only the person who started the sign-in, from their own signed-in app, with the
 *    finish code. Then the code is exchanged with the verifier, the account identified, the rules checked again, tokens
 *    sealed and stored. A begin link forwarded to someone else therefore can't attach their provider account to the
 *    sender's membership: the finish code lands in the other person's browser and app, and their Meldiq session isn't
 *    the one that asked.
 */
const PKCE_PURPOSE = "oauth-flow";
export const BROWSER_COOKIE = "meldiq_oauth";
/** How long the app has to finish a sign-in the provider approved (providers' codes live about ten minutes). */
const FINISH_WINDOW_MS = 3 * 60_000;
const ONE_TIME_CODE = /^[A-Za-z0-9_-]{20,64}$/;

export function callbackUrl(k: Kernel): string {
  if (!k.deps.config.publicUrl) throw new ApiError(409, "needs_configuration", "This server has no public address for app sign-ins (INTEGRATIONS_PUBLIC_URL)");
  return `${k.deps.config.publicUrl}/v1/integrations/oauth/callback`;
}

/** The checks for a member connecting (or using) their own account, without the account itself. */
async function requireMayConnect(k: Kernel, orgId: string, userId: string, connector: Connector) {
  const def = connector.def;
  const { perms, roleIds } = k.member(orgId, userId);
  k.require(perms, "integrations.connect", "Your role doesn't include connecting apps");
  const policy = await k.requireAllowed(orgId, def);
  if (!policy.memberAccounts) throw new ApiError(403, "blocked", "This workspace doesn't allow personal app connections");
  const install = await k.requireInstalled(orgId, def);
  if (!accessAllows(k.installConfig(install), { perms, roleIds })) throw new ApiError(403, "blocked", `${def.name} is limited to certain roles in this workspace`);
  const grantedAll = listOf(install.granted_caps);
  const granted = grantedAll.filter((c) => def.capabilities.some((x) => x.key === c && x.accountType === "member"));
  // An admin linking a workspace connection (GitHub) first connects their own account, only to prove which
  // installations they can reach; that needs no member capability.
  const linking = !!connector.linking && perms.has("integrations.connect_workspace") && grantedAll.some((c) => def.capabilities.some((x) => x.key === c && x.accountType === "workspace"));
  if (!granted.length && !linking) throw new ApiError(403, "capability_not_granted", `${def.name} has nothing to connect a personal account for in this workspace`);
  if (!connector.oauth) throw new ApiError(409, "needs_configuration", `${def.name} isn't set up on this server yet`);
  return { install, granted, oauth: connector.oauth };
}

export async function startConnect(k: Kernel, orgId: string, userId: string, connectorId: string) {
  const connector = k.connector(connectorId);
  const def = connector.def;
  k.rate(`connect:${userId}`, k.deps.config.connectPerMinute);
  const { granted, oauth } = await requireMayConnect(k, orgId, userId, connector);
  const ring = k.deps.config.keyring;
  if (!ring) throw new ApiError(409, "needs_configuration", "This server has no key for storing app connections (INTEGRATIONS_TOKEN_KEY)");
  const redirectUri = callbackUrl(k);
  const scopes = scopesFor(def, granted, "member");
  const id = newId("flow");
  const state = randomSecret(32);
  const begin = randomSecret(24);
  const verifier = oauth.pkce ? randomSecret(48) : null;
  const t = k.now;
  const expiresAt = t + k.deps.config.stateTtlMs;
  const inserted = await k.deps.store.insertState({
    id,
    state_hash: sha256Hex(state),
    begin_hash: sha256Hex(begin),
    browser_hash: null,
    finish_hash: null,
    workspace_id: orgId,
    connector_id: def.id,
    user_id: userId,
    owner_type: "member",
    // The state and verifier, sealed to this flow: needed again at begin and callback, never stored in the clear.
    verifier: seal(ring, JSON.stringify({ s: state, v: verifier }), PKCE_PURPOSE, id),
    redirect_uri: redirectUri,
    scopes: JSON.stringify(scopes),
    created_at: t,
    expires_at: expiresAt,
    used_at: null,
    result: null,
    error: null,
    account_id: null,
  });
  if (!inserted) throw new ApiError(500, "internal", "Couldn't start the sign-in; try again");
  return {
    flowId: id,
    beginUrl: `${k.deps.config.publicUrl}/v1/integrations/oauth/begin/${id}?t=${begin}`,
    expiresAt,
    scopes,
    capabilities: def.capabilities.filter((c) => granted.includes(c.key)).map((c) => ({ key: c.key, label: c.label, description: c.description, kind: c.kind })),
  };
}

export type BrowserOutcome =
  | { kind: "redirect"; location: string; cookie: string }
  | { kind: "page"; ok: boolean; title: string; message: string; flowId: string | null; result: string; finish: string | null };

const page = (ok: boolean, title: string, message: string, flowId: string | null, result: string, finish: string | null = null): BrowserOutcome => ({
  kind: "page",
  ok,
  title,
  message,
  flowId,
  result,
  finish,
});

export async function begin(k: Kernel, flowId: string, beginToken: string): Promise<BrowserOutcome> {
  const row = await k.deps.store.state(flowId);
  const ring = k.deps.config.keyring;
  if (!row || !ring || !ONE_TIME_CODE.test(beginToken)) return page(false, "Link not valid", "This sign-in link isn't valid. Start again from Meldiq.", null, "invalid");
  if (row.expires_at <= k.now) return page(false, "Link expired", "This sign-in link has expired. Start again from Meldiq.", row.id, "expired");
  const browser = randomSecret(32);
  if (!(await k.deps.store.claimBegin(row.id, sha256Hex(beginToken), sha256Hex(browser), k.now))) {
    return page(false, "Link already used", "This sign-in link was already opened. Start again from Meldiq.", row.id, "used");
  }
  const connector = k.connectors.get(row.connector_id);
  if (!connector?.oauth) return page(false, "Not available", "This app isn't available on this server any more.", row.id, "failed");
  const { s, v } = JSON.parse(open(ring, row.verifier!, PKCE_PURPOSE, row.id)) as { s: string; v: string | null };
  const location = connector.oauth.authorizeUrl({
    state: s,
    redirectUri: row.redirect_uri,
    scopes: listOf(row.scopes),
    codeChallenge: connector.oauth.pkce && v ? pkceChallenge(v) : null,
  });
  return { kind: "redirect", location, cookie: browser };
}

/** The provider's redirect back. Every outcome spends the flow; nothing in the answer is secret. */
export async function callback(k: Kernel, q: { state?: string; code?: string; error?: string }, cookie: string | undefined): Promise<BrowserOutcome> {
  if (!q.state || !/^[A-Za-z0-9_-]{20,100}$/.test(q.state)) return page(false, "Sign-in not recognised", "Meldiq didn't start this sign-in. Start again from Meldiq.", null, "invalid");
  const row = await k.deps.store.stateByHash(sha256Hex(q.state));
  if (!row) return page(false, "Sign-in not recognised", "Meldiq didn't start this sign-in, or it was already used. Start again from Meldiq.", null, "invalid");
  const sameBrowser = !!cookie && !!row.browser_hash && sha256Hex(cookie) === row.browser_hash;
  if (!(await k.deps.store.claimCallback(row.id, k.now))) {
    return page(false, "Sign-in already finished", "This sign-in was already used or has expired. Start again from Meldiq.", row.id, "used");
  }
  const fail = async (result: "denied" | "failed", message: string, title = "Couldn't connect") => {
    await k.deps.store.finishState(row.id, result, message, null);
    return page(false, title, message, row.id, result);
  };
  if (!sameBrowser) return fail("failed", "This sign-in was finished in a different browser than the one Meldiq opened. Start again from Meldiq on this device.");
  if (q.error) return fail("denied", q.error === "access_denied" ? "You didn't allow access. Nothing was connected." : "The provider didn't complete the sign-in. Nothing was connected.", "Not connected");
  if (!q.code || q.code.length > 2048) return fail("failed", "The provider didn't send a sign-in code.");
  const connector = k.connectors.get(row.connector_id);
  const ring = k.deps.config.keyring;
  if (!connector?.oauth || !ring) return fail("failed", "This app isn't available on this server any more.");
  try {
    // The person may have lost the right to connect while they were at the provider: checked again (and at finish).
    await requireMayConnect(k, row.workspace_id, row.user_id, connector);
  } catch (e) {
    return fail("failed", providerFailure(e, connector.def.name).message);
  }
  // Nothing is connected from the browser: the code waits, sealed, for the person who started this sign-in.
  const { s, v } = JSON.parse(open(ring, row.verifier!, PKCE_PURPOSE, row.id)) as { s: string; v: string | null };
  const finishCode = randomSecret(24);
  const sealed = seal(ring, JSON.stringify({ s, v, c: q.code }), PKCE_PURPOSE, row.id);
  if (!(await k.deps.store.approveState(row.id, sealed, sha256Hex(finishCode), k.now + FINISH_WINDOW_MS))) {
    return fail("failed", "This sign-in was already used or has expired. Start again from Meldiq.");
  }
  return page(true, "Almost connected", `Return to Meldiq to finish connecting ${connector.def.name}.`, row.id, "approved", finishCode);
}

/**
 * The app finishes a sign-in the provider approved (POST /v1/integrations/flows/:id/finish): signed in as the person
 * who started it, with the one-time code the browser handed back. Only now is the provider's code exchanged and the
 * account stored. Answers with the flow's status, also when it had already ended.
 */
export async function finish(k: Kernel, flowId: string, userId: string, finishCode: string) {
  const row = await k.deps.store.state(flowId);
  if (!row || row.user_id !== userId) throw notFound("Sign-in not found");
  if (row.result !== "approved") return flowStatus(k, flowId, userId);
  if (!ONE_TIME_CODE.test(finishCode) || !row.finish_hash || !safeEqual(sha256Hex(finishCode), row.finish_hash)) {
    throw forbidden("This sign-in can't be finished with that link");
  }
  if (!(await k.deps.store.claimFinish(row.id, row.finish_hash, k.now))) {
    if (row.expires_at <= k.now) await k.deps.store.finishState(row.id, "failed", "The sign-in wasn't finished in time. Start again from Meldiq.", null);
    return flowStatus(k, flowId, userId);
  }
  const connector = k.connectors.get(row.connector_id);
  const fail = async (message: string) => {
    await k.deps.store.finishState(row.id, "failed", message, null);
    return flowStatus(k, flowId, userId);
  };
  if (!connector?.oauth || !k.deps.config.keyring) return fail("This app isn't available on this server any more.");
  try {
    await requireMayConnect(k, row.workspace_id, row.user_id, connector);
    const { v, c } = JSON.parse(open(k.deps.config.keyring, row.verifier!, PKCE_PURPOSE, row.id)) as { v: string | null; c: string };
    const requested = listOf(row.scopes);
    const tokens = await connector.oauth.exchange({ code: c, redirectUri: row.redirect_uri, verifier: v, scopes: requested });
    const account = await saveAccount(k, connector, row.workspace_id, row.user_id, requested, tokens);
    const grantedLower = new Set(listOf(account.scopes).map((x) => x.toLowerCase()));
    const partly = requested.some((x) => !grantedLower.has(x.toLowerCase()));
    const note = partly ? `You didn't allow everything ${connector.def.name} asked for. Some features won't work until you connect again.` : null;
    await k.deps.store.finishState(row.id, "connected", note, account.id);
    k.deps.audit({ actorId: row.user_id, orgId: row.workspace_id, action: "integration.connect", target: connector.def.id, detail: `scopes=${listOf(account.scopes).length}` });
    k.deps.hub.sendToUser(row.user_id, { t: "integration.updated", orgId: row.workspace_id, connectorId: connector.def.id });
    return flowStatus(k, flowId, userId);
  } catch (e) {
    const err = providerFailure(e, connector.def.name);
    k.deps.log.warn({ connector: connector.def.id, code: err.code, kind: e instanceof ProviderError ? e.kind : undefined }, "integration connect failed");
    return fail(err.message);
  }
}

/** Identifies the account and stores it with its sealed tokens (replacing the member's earlier connection, if any). */
async function saveAccount(k: Kernel, connector: Connector, orgId: string, userId: string, requested: string[], tokens: TokenSet): Promise<AccountRow> {
  const t = k.now;
  const ownerKey = `member:${userId}`;
  const existing = await k.deps.store.accountFor(orgId, connector.def.id, ownerKey);
  const id = existing?.id ?? newId("acct");
  // Identify with the new token before storing anything.
  const probe: AccountRow = {
    id,
    workspace_id: orgId,
    connector_id: connector.def.id,
    owner_type: "member",
    owner_key: ownerKey,
    user_id: userId,
    external_id: existing?.external_id ?? "",
    external_label: null,
    scopes: "[]",
    meta: "{}",
    status: "active",
    rev: existing?.rev ?? 1,
    created_at: existing?.created_at ?? t,
    updated_at: t,
    last_validated_at: null,
    last_used_at: null,
    last_error: null,
    last_error_at: null,
  };
  const who = await connector.identify(k.callContext(connector, probe, tokens), tokens);
  // Scopes the provider actually granted; when it doesn't say, the ones requested.
  const scopes = tokens.scopes ?? requested;
  const fields = {
    external_id: who.externalId,
    external_label: who.label?.slice(0, 200) ?? null,
    scopes: JSON.stringify([...new Set(scopes)].sort()),
    meta: JSON.stringify({ ...(tokens.meta ?? {}), ...(who.meta ?? {}) }),
    status: "active" as const,
    updated_at: t,
    last_validated_at: t,
    last_error: null,
    last_error_at: null,
  };
  if (existing && existing.external_id && existing.external_id !== who.externalId) {
    // A different provider account replaces the old one: the old grant is revoked first (best effort).
    await revokeQuietly(k, connector, existing);
  }
  await k.deps.store.putCredentials({ account_id: id, ...k.sealTokens(id, tokens), updated_at: t });
  if (existing) {
    await k.deps.store.updateAccount(id, null, fields);
  } else if (!(await k.deps.store.insertAccount({ ...probe, ...fields }))) {
    // A second flow for the same person finished at the same moment: theirs is replaced by this one.
    const raced = (await k.deps.store.accountFor(orgId, connector.def.id, ownerKey))!;
    await k.deps.store.putCredentials({ account_id: raced.id, ...k.sealTokens(raced.id, tokens), updated_at: t });
    await k.deps.store.deleteCredentials(id);
    await k.deps.store.updateAccount(raced.id, null, fields);
    return (await k.deps.store.account(raced.id))!;
  }
  return (await k.deps.store.account(id))!;
}

/**
 * Revokes a connection at the provider before Meldiq's copy is deleted, unless the same provider account still backs
 * another Meldiq connection (another workspace, or another app of the same provider such as Drive and Calendar): Google
 * ends the person's whole grant of Meldiq's client, so revoking would quietly break those too.
 */
async function revokeQuietly(k: Kernel, connector: Connector | undefined, account: AccountRow): Promise<void> {
  if (!connector?.oauth?.revoke || account.owner_type !== "member" || !account.external_id) return;
  const sameProvider = [...k.connectors.values()].filter((c) => c.def.provider === connector.def.provider).map((c) => c.def.id);
  const others = (await k.deps.store.accountsWithExternalId(sameProvider, account.external_id)).filter((a) => a.id !== account.id && a.owner_type === "member");
  if (others.length) return;
  const t = await k.tokens(account).catch(() => null);
  if (!t) return;
  try {
    await connector.oauth.revoke({ accessToken: t.accessToken, refreshToken: t.refreshToken });
  } catch (e) {
    // Revocation is best effort: Meldiq's copy is deleted regardless, and the person can remove the grant at the provider.
    k.deps.log.warn({ connector: account.connector_id, kind: e instanceof ProviderError ? e.kind : "error" }, "provider revocation failed");
  }
}

export async function flowStatus(k: Kernel, flowId: string, userId: string) {
  const row = await k.deps.store.state(flowId);
  if (!row || row.user_id !== userId) throw notFound("Sign-in not found");
  const live = row.expires_at > k.now;
  // "approved": the provider said yes and the app still has to finish (its return link carries the finish code).
  const status =
    row.result === "approved" ? (live ? "approved" : "expired")
    : row.result === "finishing" ? (live ? "pending" : "expired")
    : (row.result ?? (row.used_at || live ? "pending" : "expired"));
  const connected = row.result === "connected";
  return { flowId: row.id, connectorId: row.connector_id, orgId: row.workspace_id, status, error: connected ? null : row.error, note: connected ? row.error : null, accountId: row.account_id };
}

/** Removes a connection: the member's own (theirs to remove), or anyone's / the workspace's with integrations.uninstall. */
export async function disconnect(k: Kernel, orgId: string, userId: string, accountId: string) {
  const account = await k.deps.store.account(accountId);
  if (!account || account.workspace_id !== orgId) throw notFound("Connection not found");
  const { perms } = k.member(orgId, userId);
  const own = account.owner_type === "member" && account.user_id === userId;
  if (!own && !perms.has("integrations.uninstall")) throw forbidden("Only the person who connected it, or someone who can uninstall apps, can disconnect this");
  await revokeQuietly(k, k.connectors.get(account.connector_id), account);
  await k.deps.store.deleteAccount(account.id);
  k.deps.audit({ actorId: userId, orgId, action: "integration.disconnect", target: account.connector_id, detail: own ? "own" : account.owner_type });
  k.deps.hub.sendToUser(account.user_id, { t: "integration.updated", orgId, connectorId: account.connector_id });
  return { ok: true };
}

/** Checks a connection still works (the provider's identity call), and records the result. */
export async function validate(k: Kernel, orgId: string, userId: string, accountId: string) {
  const account = await k.deps.store.account(accountId);
  if (!account || account.workspace_id !== orgId) throw notFound("Connection not found");
  const { perms } = k.member(orgId, userId);
  if (!(account.owner_type === "member" && account.user_id === userId) && !perms.has("integrations.activity")) throw forbidden();
  const connector = k.connector(account.connector_id);
  k.rate(`validate:${userId}`, 10);
  if (account.owner_type === "workspace") return { status: account.status, checkedAt: account.last_validated_at };
  try {
    const tokens = await k.tokens(account);
    const who = await connector.identify(k.callContext(connector, account), {
      accessToken: tokens?.accessToken ?? "",
      refreshToken: null,
      expiresAt: null,
      scopes: null,
      meta: {},
    });
    if (who.externalId !== account.external_id) {
      await k.markAccount(account, "error", "The provider account changed; connect again");
      return { status: "error", checkedAt: k.now };
    }
    await k.deps.store.updateAccount(account.id, null, { status: "active", last_validated_at: k.now, last_error: null, last_error_at: null });
    return { status: "active", checkedAt: k.now };
  } catch (e) {
    const err = providerFailure(e, connector.def.name);
    if (err.code === "reconnect_required") return { status: "expired", checkedAt: k.now, error: err.message };
    throw err;
  }
}

/** Uninstalls: every connection of the app in the workspace is revoked and deleted, notifications stop. */
export async function uninstall(k: Kernel, orgId: string, userId: string, connectorId: string) {
  const def = k.def(connectorId);
  const { perms } = k.member(orgId, userId);
  k.require(perms, "integrations.uninstall", "Only people who can uninstall apps can do that");
  const install = await k.deps.store.install(orgId, def.id);
  if (!install || install.state === "uninstalled") return { ok: true };
  const accounts = await k.deps.store.accounts({ workspace_id: orgId, connector_id: def.id });
  for (const a of accounts) {
    await revokeQuietly(k, k.connectors.get(def.id), a);
    await k.deps.store.deleteAccount(a.id);
    k.deps.hub.sendToUser(a.user_id, { t: "integration.updated", orgId, connectorId: def.id });
  }
  for (let i = 0; i < 3; i++) {
    const cur = (await k.deps.store.install(orgId, def.id))!;
    if (await k.deps.store.updateInstall(cur.id, cur.rev, { state: "uninstalled", granted_caps: "[]", updated_at: k.now, decided_by: userId, decided_at: k.now })) break;
  }
  k.deps.audit({ actorId: userId, orgId, action: "integration.uninstall", target: def.id, detail: `${accounts.length} connection(s) removed` });
  return { ok: true, removedConnections: accounts.length };
}

/** Someone left or was removed from a workspace: their personal connections there are revoked and deleted. */
export async function forgetMember(k: Kernel, orgId: string, userId: string): Promise<void> {
  for (const a of await k.deps.store.accounts({ workspace_id: orgId, user_id: userId, owner_type: "member" })) {
    await revokeQuietly(k, k.connectors.get(a.connector_id), a);
    await k.deps.store.deleteAccount(a.id);
  }
}

/** A person's account was deleted: every personal connection of theirs, in every workspace. */
export async function forgetUser(k: Kernel, userId: string): Promise<void> {
  for (const a of await k.deps.store.accountsOfUser(userId)) {
    if (a.owner_type !== "member") continue;
    await revokeQuietly(k, k.connectors.get(a.connector_id), a);
    await k.deps.store.deleteAccount(a.id);
  }
}

/** A workspace was deleted: every connection revoked, every record of it removed. */
export async function forgetWorkspace(k: Kernel, orgId: string): Promise<void> {
  // One at a time, each deleted after its revocation: the last of a person's connections to a provider still revokes.
  for (const a of await k.deps.store.accounts({ workspace_id: orgId })) {
    await revokeQuietly(k, k.connectors.get(a.connector_id), a);
    await k.deps.store.deleteAccount(a.id);
  }
  await k.deps.store.forgetWorkspace(orgId);
}
