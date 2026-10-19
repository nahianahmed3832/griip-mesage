import { z } from "zod";
import type { Permission } from "../permissions/catalog.js";
import type { ConnectorDef, ConnectorState } from "./types.js";
import type { InstallRow } from "./store.js";

/**
 * Integration policy (docs/INTEGRATIONS.md › Policy): pure rules, so the routes, the tests and the app's mirror agree.
 * Who may do what comes from workspace permissions (src/permissions/catalog.ts); the policy adds workspace-wide switches
 * within the platform's bounds, and each installed app can be limited to people holding certain roles.
 */
export const zWorkspacePolicy = z.object({
  /** Only connectors in [allowedConnectors] may be requested or installed. */
  blockUnlisted: z.boolean().default(false),
  allowedConnectors: z.array(z.string().max(64)).max(100).default([]),
  /** Members may ask admins for apps (on top of the integrations.request permission). */
  requestsEnabled: z.boolean().default(true),
  /** Members may connect their own accounts (on top of the integrations.connect permission). */
  memberAccounts: z.boolean().default(true),
});
export type WorkspacePolicy = z.infer<typeof zWorkspacePolicy>;
export const DEFAULT_WORKSPACE_POLICY: WorkspacePolicy = zWorkspacePolicy.parse({});

export const zPlatformPolicy = z.object({
  /** Connectors platform admins turned off for every workspace (on top of INTEGRATIONS_DISABLED). */
  blocked: z.array(z.string().max(64)).max(100).default([]),
});
export type PlatformPolicy = z.infer<typeof zPlatformPolicy>;

/** Who may use an installed app: everyone in the workspace (default) or only holders of certain custom roles. */
export const zAccess = z.object({ mode: z.enum(["everyone", "roles"]).default("everyone"), roleIds: z.array(z.string().max(64)).max(50).default([]) });
export type Access = z.infer<typeof zAccess>;
export const zInstallConfig = z.object({ access: zAccess.default({ mode: "everyone", roleIds: [] }) });
export type InstallConfig = z.infer<typeof zInstallConfig>;

export function parseJson<S extends z.ZodTypeAny>(schema: S, text: string | null | undefined, fallback: z.output<S>): z.output<S> {
  if (!text) return fallback;
  try {
    const r = schema.safeParse(JSON.parse(text));
    return r.success ? (r.data as z.output<S>) : fallback;
  } catch {
    return fallback;
  }
}

export const listOf = (text: string | null | undefined): string[] => parseJson(z.array(z.string()), text, []);

export interface Viewer {
  perms: ReadonlySet<Permission>;
  /** The viewer's custom role ids in this workspace (for app access by role). */
  roleIds: ReadonlySet<string>;
  /** Has an active connected account of this connector (member). */
  connected: boolean;
  accountError: boolean;
}

export interface Platform {
  /** The provider is configured (credentials, public URL, token key). */
  configured: boolean;
  /** Turned off by the operator or a platform admin. */
  blocked: boolean;
}

/** Whether the workspace's policy allows this connector at all. */
export function policyAllows(policy: WorkspacePolicy, connectorId: string): boolean {
  return !policy.blockUnlisted || policy.allowedConnectors.includes(connectorId);
}

/** May this person use an installed app (connect their account, search)? Admins who configure it always may. */
export function accessAllows(config: InstallConfig, viewer: Pick<Viewer, "perms" | "roleIds">): boolean {
  if (config.access.mode === "everyone") return true;
  if (viewer.perms.has("integrations.configure")) return true;
  return config.access.roleIds.some((r) => viewer.roleIds.has(r));
}

/** The one state a connector is in for this person, most blocking first. */
export function connectorState(def: ConnectorDef, platform: Platform, policy: WorkspacePolicy, install: InstallRow | null, viewer: Viewer, config: InstallConfig): ConnectorState {
  if (def.availability === "coming_soon") return "coming_soon";
  if (!platform.configured) return "needs_configuration";
  if (platform.blocked || !policyAllows(policy, def.id)) return "blocked";
  switch (install?.state) {
    case "requested":
      return "approval_required";
    case "disabled":
      return "disabled";
    case "installed":
      if (!accessAllows(config, viewer)) return "blocked";
      if (viewer.accountError) return "error";
      if (viewer.connected) return "connected";
      return "installed";
    default:
      return "available";
  }
}

export type ConnectorAction = "install" | "request" | "approve" | "decline" | "connect" | "reconnect" | "configure" | "disconnect" | "uninstall" | "link_workspace";

/**
 * What this person may do with the connector right now (the server checks again when they do it). [linkable]: the
 * connector links the workspace to a provider target (GitHub, the sandbox). Incoming webhooks post to channels too, but
 * through their own webhooks, so they offer no linking.
 */
export function allowedActions(def: ConnectorDef, state: ConnectorState, policy: WorkspacePolicy, viewer: Viewer, linkable = false): ConnectorAction[] {
  const p = viewer.perms;
  const out: ConnectorAction[] = [];
  const hasMember = def.capabilities.some((c) => c.accountType === "member");
  const hasWorkspace = linkable && def.capabilities.some((c) => c.accountType === "workspace");
  if (state === "available") {
    if (p.has("integrations.install")) out.push("install");
    else if (p.has("integrations.request") && policy.requestsEnabled) out.push("request");
  }
  if (state === "approval_required" && p.has("integrations.install")) out.push("approve", "decline");
  if (state === "disabled" && p.has("integrations.install")) out.push("install");
  const memberCanConnect = hasMember && p.has("integrations.connect") && policy.memberAccounts;
  if (state === "installed" && memberCanConnect) out.push("connect");
  if (state === "error" && memberCanConnect) out.push("reconnect");
  if (state === "connected" || state === "error") out.push("disconnect");
  if (["installed", "connected", "error", "disabled"].includes(state)) {
    if (p.has("integrations.configure")) out.push("configure");
    if (hasWorkspace && p.has("integrations.connect_workspace") && state !== "disabled") out.push("link_workspace");
    if (p.has("integrations.uninstall")) out.push("uninstall");
  }
  return out;
}
