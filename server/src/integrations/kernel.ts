import type { FastifyBaseLogger } from "fastify";
import type { Authz } from "../authz.js";
import type { AppContext } from "../context.js";
import type { DB } from "../db.js";
import type { Entitlements } from "../entitlements.js";
import type { Hub } from "../hub.js";
import type { Permission } from "../permissions/catalog.js";
import { ApiError, forbidden, notFound } from "../util.js";
import { CONNECTORS, connectorDef } from "./catalog.js";
import type { IntegrationsConfig } from "./config.js";
import type { CallContext, Connector } from "./connector.js";
import { type FetchLike, ProviderError, type ProviderRequest, providerJson } from "./http.js";
import {
  accessAllows,
  DEFAULT_WORKSPACE_POLICY,
  type InstallConfig,
  listOf,
  parseJson,
  type Platform,
  type PlatformPolicy,
  policyAllows,
  type Viewer,
  type WorkspacePolicy,
  zInstallConfig,
  zPlatformPolicy,
  zWorkspacePolicy,
} from "./policy.js";
import { github } from "./providers/github.js";
import { googleCalendar, googleDrive } from "./providers/google.js";
import { jiraConnector } from "./providers/atlassian.js";
import { figmaConnector } from "./providers/figma.js";
import { gitlabConnector } from "./providers/gitlab.js";
import { linearConnector } from "./providers/linear.js";
import { microsoftOneDrive, microsoftOutlookCalendar, microsoftSharePoint } from "./providers/microsoft.js";
import { notionConnector } from "./providers/notion.js";
import { incomingConnector } from "./providers/incoming.js";
import { SANDBOX_HOST, SandboxProvider, sandboxConnector } from "./providers/sandbox.js";
import type { AccountRow, InstallRow, IntegrationStore } from "./store.js";
import { open, SealError, seal } from "./tokens.js";
import type { ConnectorDef, ProviderId, TokenSet } from "./types.js";

/**
 * The integrations kernel (docs/INTEGRATIONS.md › Framework): connectors, the stores, and the checks every connector
 * action goes through. Workspace authorisation is evaluated afresh on every call (deny by default): membership, the
 * permission, the install's state and granted capabilities, the app's access setting, the workspace and platform
 * policy, and the account's owner and health. A capability not granted at install is never used, even if a token
 * would allow it.
 */
export interface KernelDeps {
  db: DB;
  authz: Authz;
  hub: Hub;
  log: FastifyBaseLogger;
  audit: AppContext["audit"];
  config: IntegrationsConfig;
  /** Plan-based limits (src/entitlements.ts); none when absent. */
  entitlements?: Entitlements;
  store: IntegrationStore;
  fetch: FetchLike;
  now: () => number;
}

/** Seconds before expiry at which a token counts as expired (refreshed first). */
const EXPIRY_MARGIN_MS = 60_000;
const ACCESS_PURPOSE = "access-token";
const REFRESH_PURPOSE = "refresh-token";

export class Kernel {
  readonly connectors = new Map<string, Connector>();
  readonly sandbox: SandboxProvider | null;
  readonly fetch: FetchLike;
  private readonly buckets = new Map<string, { tokens: number; at: number }>();
  /** Token refreshes in progress, by account. */
  private readonly refreshing = new Map<string, Promise<string>>();

  constructor(readonly deps: KernelDeps) {
    const cfg = deps.config;
    this.sandbox = cfg.sandbox && cfg.publicUrl ? new SandboxProvider(cfg.sandbox.webhookSecret, cfg.publicUrl, deps.now) : null;
    const sandbox = this.sandbox;
    // The sandbox's "provider" is answered in-process; every other URL goes to the network as usual.
    this.fetch = (url, init) => (sandbox && url.startsWith(`${SANDBOX_HOST}/`) ? sandbox.handle(url, init) : deps.fetch(url, init));
    const cdeps = { config: cfg, fetch: this.fetch, now: deps.now };
    const list = [
      googleDrive(cdeps),
      googleCalendar(cdeps),
      microsoftOneDrive(cdeps),
      microsoftSharePoint(cdeps),
      microsoftOutlookCalendar(cdeps),
      github(cdeps),
      jiraConnector(cdeps),
      notionConnector(cdeps),
      linearConnector(cdeps),
      figmaConnector(cdeps),
      gitlabConnector(cdeps),
      incomingConnector(cdeps),
      ...(cfg.sandbox ? [sandboxConnector(cdeps, sandbox)] : []),
    ];
    for (const c of list) this.connectors.set(c.def.id, c);
  }

  get now(): number {
    return this.deps.now();
  }

  /** The directory's entries: every catalog entry, the sandbox only where it's enabled. */
  visibleDefs(): ConnectorDef[] {
    return CONNECTORS.filter((d) => d.availability !== "sandbox" || this.connectors.has(d.id));
  }

  def(id: string): ConnectorDef {
    const d = connectorDef(id);
    if (!d || (d.availability === "sandbox" && !this.connectors.has(id))) throw notFound("Unknown app");
    return d;
  }

  /** An implemented connector; "coming soon" entries have none and every operation on them is refused. */
  connector(id: string): Connector {
    this.def(id);
    const c = this.connectors.get(id);
    if (!c) throw new ApiError(409, "coming_soon", "This app isn't available in Meldiq yet");
    return c;
  }

  // ───── Platform and policy ─────
  async platformPolicy(): Promise<{ policy: PlatformPolicy; rev: number }> {
    const row = await this.deps.store.policy("platform");
    return { policy: parseJson(zPlatformPolicy, row?.doc, { blocked: [] }), rev: row?.rev ?? 0 };
  }

  providerConfigured(provider: ProviderId): boolean {
    return this.deps.config.missing[provider]?.length === 0;
  }

  async platform(def: ConnectorDef, platformPolicy?: PlatformPolicy): Promise<Platform> {
    const pp = platformPolicy ?? (await this.platformPolicy()).policy;
    const c = this.connectors.get(def.id);
    return {
      configured: c?.configured ? c.configured() : !!c?.oauth && this.providerConfigured(def.provider),
      blocked: this.deps.config.disabled.has(def.id) || pp.blocked.includes(def.id),
    };
  }

  async workspacePolicy(orgId: string): Promise<{ policy: WorkspacePolicy; rev: number }> {
    const row = await this.deps.store.policy(orgId);
    return { policy: parseJson(zWorkspacePolicy, row?.doc, DEFAULT_WORKSPACE_POLICY), rev: row?.rev ?? 0 };
  }

  installConfig(install: InstallRow | null): InstallConfig {
    return parseJson(zInstallConfig, install?.config, { access: { mode: "everyone", roleIds: [] } });
  }

  // ───── Who is asking ─────
  /** The caller's permissions in the workspace; outsiders get 404 (the workspace's existence stays hidden). */
  member(orgId: string, userId: string): { perms: Set<Permission>; roleIds: Set<string> } {
    const actor = this.deps.authz.actor(orgId, userId);
    if (!actor) throw notFound("Organization not found");
    return { perms: actor.perms, roleIds: new Set(this.deps.authz.assignedRoles(orgId, userId).map((r) => r.id)) };
  }

  require(perms: ReadonlySet<Permission>, p: Permission, message: string): void {
    if (!perms.has(p)) throw forbidden(message);
  }

  async viewer(orgId: string, userId: string, connectorId: string): Promise<Viewer & { account: AccountRow | null }> {
    const m = this.member(orgId, userId);
    const account = await this.deps.store.accountFor(orgId, connectorId, `member:${userId}`);
    return { ...m, account, connected: account?.status === "active", accountError: !!account && account.status !== "active" };
  }

  /** The workspace may use the connector: implemented, configured, not blocked by the platform or the policy. */
  async requireAllowed(orgId: string, def: ConnectorDef): Promise<WorkspacePolicy> {
    if (def.availability === "coming_soon") throw new ApiError(409, "coming_soon", "This app isn't available in Meldiq yet");
    const platform = await this.platform(def);
    if (!platform.configured) {
      throw new ApiError(409, "needs_configuration", `${def.name} isn't set up on this server yet. A platform administrator needs to add its credentials.`);
    }
    if (platform.blocked) throw new ApiError(403, "blocked", `${def.name} has been turned off on this server`);
    const { policy } = await this.workspacePolicy(orgId);
    if (!policyAllows(policy, def.id)) throw new ApiError(403, "blocked", `${def.name} isn't on this workspace's list of allowed apps`);
    return policy;
  }

  async requireInstalled(orgId: string, def: ConnectorDef): Promise<InstallRow> {
    const install = await this.deps.store.install(orgId, def.id);
    if (!install || install.state === "uninstalled" || install.state === "rejected") throw new ApiError(409, "not_installed", `${def.name} isn't installed in this workspace`);
    if (install.state === "requested") throw new ApiError(409, "approval_pending", `${def.name} is waiting for an admin's approval`);
    if (install.state === "disabled") throw new ApiError(409, "disabled", `${def.name} is turned off in this workspace`);
    return install;
  }

  /**
   * Everything a member's use of their own account needs, checked on every call: membership and `integrations.connect`
   * (a role can restrict it), the workspace and platform allow the app, it's installed with this capability granted,
   * the app's access setting includes them, and their account is connected, healthy and has the capability's scopes.
   */
  async requireUsable(orgId: string, userId: string, connectorId: string, capability: string) {
    const connector = this.connector(connectorId);
    const def = connector.def;
    const { perms, roleIds } = this.member(orgId, userId);
    this.require(perms, "integrations.connect", "Your role doesn't include using connected apps");
    const policy = await this.requireAllowed(orgId, def);
    if (!policy.memberAccounts) throw new ApiError(403, "blocked", "This workspace doesn't allow personal app connections");
    const install = await this.requireInstalled(orgId, def);
    if (!listOf(install.granted_caps).includes(capability)) {
      throw new ApiError(403, "capability_not_granted", `An admin hasn't allowed "${def.capabilities.find((c) => c.key === capability)?.label ?? capability}" for ${def.name}`);
    }
    if (!accessAllows(this.installConfig(install), { perms, roleIds })) throw new ApiError(403, "blocked", `${def.name} is limited to certain roles in this workspace`);
    const account = await this.deps.store.accountFor(orgId, connectorId, `member:${userId}`);
    if (!account) throw new ApiError(409, "not_connected", `Connect your ${def.name} account first`);
    if (account.status !== "active") throw new ApiError(409, "reconnect_required", `Your ${def.name} connection needs to be renewed. Connect it again.`);
    const capDef = def.capabilities.find((c) => c.key === capability);
    // Scope names compare case-insensitively (Microsoft's permissions aren't case-sensitive).
    const granted = new Set(listOf(account.scopes).map((s) => s.toLowerCase()));
    const lacking = (capDef?.scopes ?? []).filter((s) => !granted.has(s.toLowerCase()));
    if (lacking.length) throw new ApiError(403, "insufficient_scope", `Your ${def.name} connection doesn't include this. Connect again and allow it.`);
    return { connector, def, install, account, perms };
  }

  /** Simple per-key rate limit for expensive provider calls (a token bucket refilled per minute). */
  rate(key: string, perMinute: number): void {
    const t = this.now;
    const b = this.buckets.get(key) ?? { tokens: perMinute, at: t };
    const tokens = Math.min(perMinute, b.tokens + ((t - b.at) / 60_000) * perMinute);
    if (tokens < 1) {
      this.buckets.set(key, { tokens, at: t });
      throw new ApiError(429, "rate_limited", "Too many requests to this app; wait a moment", { retryAfterMs: Math.ceil(((1 - tokens) / perMinute) * 60_000) });
    }
    this.buckets.set(key, { tokens: tokens - 1, at: t });
    if (this.buckets.size > 10_000) this.buckets.clear();
  }

  // ───── Tokens ─────
  sealTokens(accountId: string, t: TokenSet) {
    const ring = this.deps.config.keyring;
    if (!ring) throw new ApiError(409, "needs_configuration", "This server has no key for storing app connections (INTEGRATIONS_TOKEN_KEY)");
    return {
      key_id: ring.active.id,
      access_token: seal(ring, t.accessToken, ACCESS_PURPOSE, accountId),
      refresh_token: t.refreshToken ? seal(ring, t.refreshToken, REFRESH_PURPOSE, accountId) : null,
      expires_at: t.expiresAt,
    };
  }

  /** The account's tokens, opened. Null when none are stored or they can't be opened (a removed key). */
  async tokens(account: AccountRow): Promise<{ accessToken: string; refreshToken: string | null; expiresAt: number | null; sealedAccess: string } | null> {
    const ring = this.deps.config.keyring;
    const row = await this.deps.store.credentials(account.id);
    if (!ring || !row) return null;
    try {
      return {
        accessToken: open(ring, row.access_token, ACCESS_PURPOSE, account.id),
        refreshToken: row.refresh_token ? open(ring, row.refresh_token, REFRESH_PURPOSE, account.id) : null,
        expiresAt: row.expires_at,
        sealedAccess: row.access_token,
      };
    } catch (e) {
      if (e instanceof SealError) {
        this.deps.log.warn({ accountId: account.id, connector: account.connector_id }, "stored integration token can't be opened");
        return null;
      }
      throw e;
    }
  }

  /** Marks an account as needing reconnection (the reason is a fixed message, never a provider body). */
  async markAccount(account: AccountRow, status: "expired" | "revoked" | "error", reason: string): Promise<void> {
    await this.deps.store.updateAccount(account.id, null, { status, last_error: reason.slice(0, 200), last_error_at: this.now, updated_at: this.now });
  }

  /**
   * Refreshes an account's token, once at a time per account on this server (later callers share the refresh in
   * progress). Rotating refresh tokens (GitHub, Atlassian, the sandbox) are single use, so a refresh elsewhere (another
   * server, or a caller that read the old token just before) can still spend ours first: a refusal is then checked
   * against the stored token, and a newer one is used instead of marking the account expired. The compare-and-swap on
   * the stored value keeps the first renewal.
   */
  private refresh(connector: Connector, account: AccountRow, current: { refreshToken: string | null; sealedAccess: string }): Promise<string> {
    const running = this.refreshing.get(account.id);
    if (running) return running;
    const run = this.refreshOnce(connector, account, current).finally(() => this.refreshing.delete(account.id));
    this.refreshing.set(account.id, run);
    return run;
  }

  private async refreshOnce(connector: Connector, account: AccountRow, current: { refreshToken: string | null; sealedAccess: string }): Promise<string> {
    if (!connector.oauth?.refresh || !current.refreshToken) {
      await this.markAccount(account, "expired", "The connection expired; connect again");
      throw new ApiError(409, "reconnect_required", `Your ${connector.def.name} connection expired. Connect it again.`);
    }
    let next: TokenSet;
    try {
      next = await connector.oauth.refresh(current.refreshToken, listOf(account.scopes));
    } catch (e) {
      if (e instanceof ProviderError && (e.kind === "invalid_grant" || e.kind === "unauthorized")) {
        const latest = await this.tokens(account);
        if (latest && latest.sealedAccess !== current.sealedAccess) return latest.accessToken; // renewed meanwhile
        await this.markAccount(account, "expired", "The provider refused to renew the connection");
        throw new ApiError(409, "reconnect_required", `Your ${connector.def.name} connection was revoked or expired. Connect it again.`);
      }
      throw e;
    }
    const sealed = this.sealTokens(account.id, next);
    const swapped = await this.deps.store.swapCredentials(account.id, current.sealedAccess, { ...sealed, updated_at: this.now });
    if (!swapped) {
      const latest = await this.tokens(account);
      if (latest) return latest.accessToken;
    }
    return next.accessToken;
  }

  /** The call context for [account]: provider calls with its token, refreshed before expiry and once after a 401. */
  callContext(connector: Connector, account: AccountRow, initial?: TokenSet): CallContext {
    let access: string | null = initial?.accessToken ?? null;
    const meta = stringRecord(account.meta);
    const ensure = async (force: boolean): Promise<string> => {
      if (initial && !force) return initial.accessToken;
      const t = await this.tokens(account);
      if (!t) {
        await this.markAccount(account, "error", "The stored connection can't be used; connect again");
        throw new ApiError(409, "reconnect_required", `Your ${connector.def.name} connection needs to be renewed. Connect it again.`);
      }
      if (force || (t.expiresAt !== null && t.expiresAt - EXPIRY_MARGIN_MS <= this.now)) return this.refresh(connector, account, t);
      return t.accessToken;
    };
    const fetch = this.fetch;
    return {
      meta: { ...meta, ...(initial?.meta ?? {}) },
      externalId: account.external_id,
      api: async <T>(url: string, req: ProviderRequest = {}): Promise<T> => {
        access ??= await ensure(false);
        const call = (token: string) => providerJson<T>(fetch, url, { ...req, headers: { ...req.headers, authorization: `Bearer ${token}` } }, connector.def.name);
        try {
          return await call(access);
        } catch (e) {
          if (!(e instanceof ProviderError) || e.kind !== "unauthorized" || initial) throw e;
          access = await ensure(true);
          try {
            return await call(access);
          } catch (e2) {
            if (e2 instanceof ProviderError && e2.kind === "unauthorized") {
              await this.markAccount(account, "expired", "The provider no longer accepts the connection");
              throw new ApiError(409, "reconnect_required", `Your ${connector.def.name} connection was revoked. Connect it again.`);
            }
            throw e2;
          }
        }
      },
    };
  }
}

/** Stored provider metadata (JSON): only string values are kept. */
export function stringRecord(text: string | null | undefined): Record<string, string> {
  try {
    const v: unknown = JSON.parse(text ?? "{}");
    if (!v || typeof v !== "object" || Array.isArray(v)) return {};
    return Object.fromEntries(Object.entries(v).filter((e): e is [string, string] => typeof e[1] === "string"));
  } catch {
    return {};
  }
}

/** A provider failure as the API answer the app shows (never the provider's own text or body). */
export function providerFailure(e: unknown, name: string): ApiError {
  if (e instanceof ApiError) return e;
  if (e instanceof ProviderError) {
    switch (e.kind) {
      case "forbidden":
        return new ApiError(403, "provider_forbidden", `${name} doesn't allow this for your account`);
      case "not_found":
        return new ApiError(404, "not_found", e.message);
      case "rate_limited":
        return new ApiError(429, "rate_limited", `${name} is limiting requests; try again shortly`, { retryAfterMs: e.retryAfterMs });
      case "invalid_grant":
      case "unauthorized":
        return new ApiError(409, "reconnect_required", `Your ${name} connection needs to be renewed. Connect it again.`);
      case "bad_response":
        return new ApiError(502, "provider_error", e.message);
      default:
        return new ApiError(503, "provider_unavailable", `${name} can't be reached right now. Try again in a moment.`);
    }
  }
  return new ApiError(500, "internal", "Something went wrong on the server");
}
