import { ApiError, conflict, forbidden, newId } from "../util.js";
import { connectorTier, defaultCapabilities } from "./catalog.js";
import type { Kernel } from "./kernel.js";
import {
  allowedActions,
  connectorState,
  type InstallConfig,
  listOf,
  type PlatformPolicy,
  policyAllows,
  type WorkspacePolicy,
  zInstallConfig,
  zPlatformPolicy,
  zWorkspacePolicy,
} from "./policy.js";
import type { AccountRow, InstallRow } from "./store.js";
import type { ConnectorDef } from "./types.js";

/**
 * Installing apps for a workspace (operation A in docs/INTEGRATIONS.md): requests, approval, install, configuration,
 * disabling and uninstalling. One install row per workspace and connector, changed only by compare-and-set on its
 * revision, so two admins acting at once can't both win and a retried install is harmless.
 */

const nameOf = (k: Kernel, id: string | null): string | null => {
  if (!id) return null;
  return (k.deps.db.prepare("SELECT display_name FROM users WHERE id = ?").get(id) as { display_name: string } | undefined)?.display_name ?? "Someone";
};

function accountView(k: Kernel, a: AccountRow, showLabel: boolean) {
  return {
    id: a.id,
    ownerType: a.owner_type,
    status: a.status,
    // The provider account (an email or a GitHub login) is shown to its owner and to integration admins only.
    label: showLabel ? a.external_label : null,
    scopes: listOf(a.scopes),
    connectedBy: a.user_id,
    connectedByName: nameOf(k, a.user_id),
    connectedAt: a.created_at,
    lastValidatedAt: a.last_validated_at,
    lastError: a.status === "active" ? null : a.last_error,
  };
}

function capabilityViews(def: ConnectorDef, install: InstallRow | null) {
  const granted = new Set(listOf(install?.granted_caps));
  const requested = new Set(listOf(install?.requested_caps));
  return def.capabilities.map((c) => ({ ...c, granted: granted.has(c.key), requested: requested.has(c.key) }));
}

export async function directory(k: Kernel, orgId: string, userId: string, includePlanned = false) {
  const { perms, roleIds } = k.member(orgId, userId);
  k.require(perms, "integrations.view", "Your role doesn't include the app directory");
  const [{ policy }, { policy: platformPolicy }, installs, mine] = await Promise.all([
    k.workspacePolicy(orgId),
    k.platformPolicy(),
    k.deps.store.installs(orgId),
    k.deps.store.accounts({ workspace_id: orgId, user_id: userId, owner_type: "member" }),
  ]);
  const byConnector = new Map(installs.map((i) => [i.connector_id, i]));
  const myAccounts = new Map(mine.map((a) => [a.connector_id, a]));
  const apps = [];
  for (const def of k.visibleDefs()) {
    if (!includePlanned && def.availability === "coming_soon") continue;
    const install = byConnector.get(def.id) ?? null;
    const account = myAccounts.get(def.id) ?? null;
    const viewer = { perms, roleIds, connected: account?.status === "active", accountError: !!account && account.status !== "active" };
    const platform = await k.platform(def, platformPolicy);
    const state = connectorState(def, platform, policy, install, viewer, k.installConfig(install));
    apps.push({
      id: def.id,
      name: def.name,
      publisher: def.publisher,
      category: def.category,
      icon: def.icon,
      summary: def.summary,
      featured: def.featured,
      tier: connectorTier(def),
      availability: def.availability,
      sensitivity: def.sensitivity,
      state,
      actions: allowedActions(def, state, policy, viewer, !!k.connectors.get(def.id)?.linking),
      capabilities: capabilityViews(def, install),
      installState: install?.state ?? null,
      myAccount: account ? accountView(k, account, true) : null,
    });
  }
  return {
    apps,
    policy,
    permissions: {
      canInstall: perms.has("integrations.install"),
      canRequest: perms.has("integrations.request") && policy.requestsEnabled,
      canConfigure: perms.has("integrations.configure"),
      canConnect: perms.has("integrations.connect") && policy.memberAccounts,
      canConnectWorkspace: perms.has("integrations.connect_workspace"),
      canUninstall: perms.has("integrations.uninstall"),
      canViewActivity: perms.has("integrations.activity"),
      canManagePolicy: perms.has("policies.manage"),
    },
  };
}

export async function detail(k: Kernel, orgId: string, userId: string, connectorId: string) {
  const def = k.def(connectorId);
  const { perms, roleIds } = k.member(orgId, userId);
  k.require(perms, "integrations.view", "Your role doesn't include the app directory");
  const [{ policy }, install, accounts] = await Promise.all([k.workspacePolicy(orgId), k.deps.store.install(orgId, def.id), k.deps.store.accounts({ workspace_id: orgId, connector_id: def.id })]);
  const mine = accounts.find((a) => a.owner_key === `member:${userId}`) ?? null;
  const viewer = { perms, roleIds, connected: mine?.status === "active", accountError: !!mine && mine.status !== "active" };
  const platform = await k.platform(def);
  const state = connectorState(def, platform, policy, install, viewer, k.installConfig(install));
  const admin = perms.has("integrations.install") || perms.has("integrations.configure");
  const connector = k.connectors.get(def.id);
  return {
    app: {
      id: def.id,
      name: def.name,
      publisher: def.publisher,
      category: def.category,
      icon: def.icon,
      summary: def.summary,
      description: def.description,
      availability: def.availability,
      sensitivity: def.sensitivity,
      version: def.version,
      dataAccess: def.dataAccess,
      reviewNote: def.reviewNote,
      docsUrl: def.docsUrl || null,
      // How to set it up is for the people who could act on it (the values themselves are never shown).
      setup: admin && !platform.configured ? def.setup : [],
      capabilities: capabilityViews(def, install),
      eventTypes: connector?.webhook?.eventTypes ?? [],
      actions: Object.entries(connector?.actions ?? {}).map(([key, a]) => ({ key, label: a.label, capability: a.capability })),
      supports: {
        search: !!connector?.search,
        browse: !!connector?.browse,
        details: !!connector?.details,
        unfurl: !!connector?.matchUrl,
        workspaceLink: !!connector?.linking,
      },
    },
    state,
    actions: allowedActions(def, state, policy, viewer, !!connector?.linking),
    install: install
      ? {
          id: install.id,
          state: install.state,
          version: install.connector_version,
          rev: install.rev,
          requestedCapabilities: listOf(install.requested_caps),
          grantedCapabilities: listOf(install.granted_caps),
          requestedBy: install.requested_by,
          requestedByName: nameOf(k, install.requested_by),
          requestNote: install.request_note,
          installedBy: install.installed_by,
          installedByName: nameOf(k, install.installed_by),
          decidedBy: install.decided_by,
          decidedByName: nameOf(k, install.decided_by),
          decidedAt: install.decided_at,
          config: k.installConfig(install),
          lastEventAt: install.last_event_at,
          lastError: perms.has("integrations.activity") ? install.last_error : null,
          createdAt: install.created_at,
          updatedAt: install.updated_at,
        }
      : null,
    myAccount: mine ? accountView(k, mine, true) : null,
    workspaceConnections: accounts.filter((a) => a.owner_type === "workspace").map((a) => accountView(k, a, true)),
    // How many members connected their own account (names only for integration admins).
    memberConnections: admin ? accounts.filter((a) => a.owner_type === "member").map((a) => accountView(k, a, true)) : [],
    memberConnectionCount: accounts.filter((a) => a.owner_type === "member" && a.status === "active").length,
  };
}

function capsChoice(def: ConnectorDef, requested: string[] | undefined): string[] {
  const all = new Set(def.capabilities.map((c) => c.key));
  const caps = requested ?? defaultCapabilities(def);
  for (const c of caps) if (!all.has(c)) throw new ApiError(400, "unknown_capability", `${def.name} has no capability "${c}"`);
  if (!caps.length) throw new ApiError(400, "bad_request", "Choose at least one capability");
  return [...new Set(caps)].sort();
}

/** Installs right away (integrations.install). Installing again, or approving a request, reuses the same row. */
export async function install(k: Kernel, orgId: string, userId: string, connectorId: string, caps?: string[]) {
  const def = k.connector(connectorId).def;
  const { perms } = k.member(orgId, userId);
  k.require(perms, "integrations.install", "Only people who can install apps can do that");
  await k.requireAllowed(orgId, def);
  const granted = capsChoice(def, caps);
  const t = k.now;
  for (let attempt = 0; attempt < 3; attempt++) {
    const current = await k.deps.store.install(orgId, def.id);
    if (current?.state === "installed") return current; // already installed: the same answer
    // A server-wide limit for future plans, if one is set (src/entitlements.ts; none by default).
    k.deps.entitlements?.check("integrations.installed", (await k.deps.store.installs(orgId)).filter((i) => i.state === "installed").length);
    if (!current) {
      const row: InstallRow = {
        id: newId("ins"),
        workspace_id: orgId,
        connector_id: def.id,
        connector_version: def.version,
        state: "installed",
        requested_caps: JSON.stringify(granted),
        granted_caps: JSON.stringify(granted),
        config: JSON.stringify({ access: { mode: "everyone", roleIds: [] } }),
        requested_by: null,
        request_note: null,
        installed_by: userId,
        decided_by: userId,
        decided_at: t,
        rev: 1,
        created_at: t,
        updated_at: t,
        last_event_at: null,
        last_error: null,
        last_error_at: null,
      };
      if (await k.deps.store.insertInstall(row)) {
        k.deps.audit({ actorId: userId, orgId, action: "integration.install", target: def.id, detail: granted.join(",") });
        return row;
      }
      continue; // someone else installed it at the same moment: read theirs
    }
    const ok = await k.deps.store.updateInstall(current.id, current.rev, {
      state: "installed",
      connector_version: def.version,
      granted_caps: JSON.stringify(granted),
      requested_caps: current.state === "requested" ? current.requested_caps : JSON.stringify(granted),
      installed_by: userId,
      decided_by: userId,
      decided_at: t,
      updated_at: t,
      last_error: null,
      last_error_at: null,
    });
    if (ok) {
      k.deps.audit({ actorId: userId, orgId, action: current.state === "requested" ? "integration.approve" : "integration.install", target: def.id, detail: granted.join(",") });
      return (await k.deps.store.install(orgId, def.id))!;
    }
  }
  throw conflict("Someone else changed this app at the same time. Try again.");
}

/** Asks an admin to install (integrations.request, if the policy allows requests). */
export async function request(k: Kernel, orgId: string, userId: string, connectorId: string, note: string | null, caps?: string[]) {
  const def = k.connector(connectorId).def;
  const { perms } = k.member(orgId, userId);
  const policy = await k.requireAllowed(orgId, def);
  if (!perms.has("integrations.request") || !policy.requestsEnabled) throw forbidden("You can't request apps in this workspace");
  const requested = capsChoice(def, caps);
  const t = k.now;
  const current = await k.deps.store.install(orgId, def.id);
  if (current && (current.state === "installed" || current.state === "requested")) return current;
  // An admin turned it off on purpose (keeping its settings): a request mustn't reset what they had granted.
  if (current?.state === "disabled") throw new ApiError(409, "disabled", `${def.name} is turned off in this workspace. Ask an admin to turn it back on.`);
  const fields = {
    state: "requested" as const,
    requested_caps: JSON.stringify(requested),
    granted_caps: "[]",
    requested_by: userId,
    request_note: note,
    decided_by: null,
    decided_at: null,
    updated_at: t,
  };
  if (current) {
    if (!(await k.deps.store.updateInstall(current.id, current.rev, fields))) throw conflict("Someone else changed this app at the same time. Try again.");
  } else {
    const row: InstallRow = {
      ...fields,
      id: newId("ins"),
      workspace_id: orgId,
      connector_id: def.id,
      connector_version: def.version,
      config: JSON.stringify({ access: { mode: "everyone", roleIds: [] } }),
      installed_by: null,
      rev: 1,
      created_at: t,
      last_event_at: null,
      last_error: null,
      last_error_at: null,
    };
    if (!(await k.deps.store.insertInstall(row))) return (await k.deps.store.install(orgId, def.id))!;
  }
  k.deps.audit({ actorId: userId, orgId, action: "integration.request", target: def.id, detail: requested.join(",") });
  return (await k.deps.store.install(orgId, def.id))!;
}

/** Declines a pending request (approving is [install] with the capabilities the admin allows). */
export async function decline(k: Kernel, orgId: string, userId: string, connectorId: string) {
  const def = k.def(connectorId);
  const { perms } = k.member(orgId, userId);
  k.require(perms, "integrations.install", "Only people who can install apps can decline requests");
  const current = await k.deps.store.install(orgId, def.id);
  if (!current || current.state !== "requested") throw conflict("There's no pending request for this app");
  if (!(await k.deps.store.updateInstall(current.id, current.rev, { state: "rejected", decided_by: userId, decided_at: k.now, updated_at: k.now }))) {
    throw conflict("Someone else changed this app at the same time. Try again.");
  }
  k.deps.audit({ actorId: userId, orgId, action: "integration.decline", target: def.id });
  return (await k.deps.store.install(orgId, def.id))!;
}

/**
 * Changes an installed app: its granted capabilities, who may use it, or whether it is turned on. Capabilities taken
 * away stop working at once (every call checks them); accounts connected with broader scopes keep only what is granted.
 */
export async function configure(
  k: Kernel,
  orgId: string,
  userId: string,
  connectorId: string,
  body: { baseRev: number; grantedCapabilities?: string[]; access?: InstallConfig["access"]; enabled?: boolean },
) {
  const def = k.connector(connectorId).def;
  const { perms } = k.member(orgId, userId);
  k.require(perms, "integrations.configure", "Only people who can configure apps can do that");
  const current = await k.deps.store.install(orgId, def.id);
  if (!current || !["installed", "disabled"].includes(current.state)) throw new ApiError(409, "not_installed", `${def.name} isn't installed in this workspace`);
  if (current.rev !== body.baseRev) throw new ApiError(409, "stale", "Someone else changed this app. Review the latest settings and try again.");
  const set: Partial<InstallRow> = { updated_at: k.now };
  if (body.grantedCapabilities) set.granted_caps = JSON.stringify(capsChoice(def, body.grantedCapabilities));
  if (body.access) {
    const known = new Set(k.deps.authz.workspaceRoleIds(orgId));
    for (const r of body.access.roleIds) if (!known.has(r)) throw new ApiError(400, "unknown_role", "One of those roles isn't in this workspace");
    set.config = JSON.stringify(zInstallConfig.parse({ ...k.installConfig(current), access: body.access }));
  }
  if (body.enabled !== undefined) {
    if (body.enabled) await k.requireAllowed(orgId, def);
    set.state = body.enabled ? "installed" : "disabled";
  }
  if (!(await k.deps.store.updateInstall(current.id, current.rev, set))) throw new ApiError(409, "stale", "Someone else changed this app. Review the latest settings and try again.");
  k.deps.audit({
    actorId: userId,
    orgId,
    action: body.enabled === false ? "integration.disable" : body.enabled === true && current.state === "disabled" ? "integration.enable" : "integration.configure",
    target: def.id,
    detail: [set.granted_caps ? `caps=${listOf(set.granted_caps).join(",")}` : null, body.access ? `access=${body.access.mode}` : null].filter(Boolean).join(" ") || null,
  });
  return (await k.deps.store.install(orgId, def.id))!;
}

export async function getPolicy(k: Kernel, orgId: string, userId: string) {
  const { perms } = k.member(orgId, userId);
  k.require(perms, "integrations.view", "Your role doesn't include the app directory");
  return k.workspacePolicy(orgId);
}

export async function putPolicy(k: Kernel, orgId: string, userId: string, baseRev: number, input: unknown): Promise<{ policy: WorkspacePolicy; rev: number }> {
  const { perms } = k.member(orgId, userId);
  k.require(perms, "policies.manage", "Only people who manage policies can change the app policy");
  const parsed = zWorkspacePolicy.safeParse(input);
  if (!parsed.success) throw new ApiError(400, "bad_request", "Invalid policy");
  const policy = { ...parsed.data, allowedConnectors: [...new Set(parsed.data.allowedConnectors.filter((id) => k.visibleDefs().some((d) => d.id === id)))].sort() };
  if (!(await k.deps.store.putPolicy(orgId, JSON.stringify(policy), baseRev, userId, k.now))) {
    throw new ApiError(409, "stale", "Someone else changed the policy. Review it and try again.");
  }
  k.deps.audit({ actorId: userId, orgId, action: "integration.policy", detail: `blockUnlisted=${policy.blockUnlisted} requests=${policy.requestsEnabled} memberAccounts=${policy.memberAccounts}` });
  return k.workspacePolicy(orgId);
}

/** Platform admins (PLATFORM_ADMIN_USER_IDS) only: never a workspace role. */
export function requirePlatformAdmin(k: Kernel, userId: string): void {
  if (!k.deps.config.platformAdmins.has(userId)) throw forbidden("Only platform administrators can do that");
}

export async function platformRegistry(k: Kernel, userId: string) {
  requirePlatformAdmin(k, userId);
  const { policy, rev } = await k.platformPolicy();
  const cfg = k.deps.config;
  return {
    rev,
    policy,
    publicUrl: cfg.publicUrl,
    callbackUrl: cfg.publicUrl ? `${cfg.publicUrl}/v1/integrations/oauth/callback` : null,
    tokenKey: cfg.keyring ? { configured: true, keyId: cfg.keyring.active.id, previousKeys: cfg.keyring.keys.size - 1 } : { configured: false },
    connectors: await Promise.all(
      k.visibleDefs().map(async (d) => {
        const p = await k.platform(d, policy);
        return {
          id: d.id,
          name: d.name,
          provider: d.provider,
          availability: d.availability,
          configured: p.configured,
          blocked: p.blocked,
          blockedByEnvironment: cfg.disabled.has(d.id),
          // Names of what's missing (never values).
          missing: d.availability === "coming_soon" ? [] : (cfg.missing[d.provider] ?? []),
          webhookUrl: cfg.publicUrl && k.connectors.get(d.id)?.webhook ? `${cfg.publicUrl}/v1/integrations/webhooks/${d.id}` : null,
          setup: d.setup,
          reviewNote: d.reviewNote,
        };
      }),
    ),
  };
}

export async function putPlatformPolicy(k: Kernel, userId: string, baseRev: number, input: unknown): Promise<PlatformPolicy> {
  requirePlatformAdmin(k, userId);
  const parsed = zPlatformPolicy.safeParse(input);
  if (!parsed.success) throw new ApiError(400, "bad_request", "Invalid platform policy");
  const policy = { blocked: [...new Set(parsed.data.blocked.filter((id) => k.visibleDefs().some((d) => d.id === id)))].sort() };
  if (!(await k.deps.store.putPolicy("platform", JSON.stringify(policy), baseRev, userId, k.now))) throw new ApiError(409, "stale", "The platform policy changed. Reload and try again.");
  k.deps.audit({ actorId: userId, orgId: null, action: "platform.integrations.policy", detail: `blocked=${policy.blocked.join(",")}` });
  return policy;
}

export { policyAllows };
