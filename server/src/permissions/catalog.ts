import type { OrgRole } from "../authz.js";

/**
 * The workspace permission catalog (docs/WORKSPACE_ROLES.md): every power a workspace role can carry. Each key is
 * enforced somewhere on the server; nothing here is decorative. Platform powers (service configuration, the global app
 * registry) are deliberately absent: no workspace role can ever hold them (src/permissions/platform.ts).
 *
 * The app shows the same catalog, read from GET /v1/orgs/:id/roles, so labels live here once.
 */
export const PERMISSION_KEYS = [
  // Workspace
  "workspace.edit",
  "workspace.usage.view",
  "workspace.delete",
  "workspace.transfer",
  // Members
  "members.view",
  "members.invite",
  "members.invites.manage",
  "members.roles",
  "members.titles",
  "members.remove",
  // Roles
  "roles.view",
  "roles.manage",
  "roles.assign",
  "roles.delegate",
  // Teams and branches
  "teams.create",
  "teams.manage",
  // Channels
  "channels.create",
  "channels.manage",
  "channels.visibility",
  // Messaging
  "messages.post",
  "messages.announce",
  // Integrations
  "integrations.view",
  "integrations.request",
  "integrations.install",
  "integrations.configure",
  "integrations.connect",
  "integrations.connect_workspace",
  "integrations.uninstall",
  "integrations.activity",
  // Automation (round 7, docs/WORKFLOWS.md)
  "workflows.manage",
  // User groups (round 7, docs/WORKSPACE_C5.md)
  "groups.manage",
  // Developer access (round 7, docs/DEVELOPER_API.md)
  "developers.manage",
  // Unified Inbox (round 8, docs/UNIFIED_INBOX.md)
  "inbox.view",
  "inbox.reply",
  "inbox.manage",
  // Security and administration
  "audit.view",
  "policies.manage",
] as const;

export type Permission = (typeof PERMISSION_KEYS)[number];

export type PermissionCategory = "workspace" | "members" | "roles" | "teams" | "channels" | "messaging" | "integrations" | "security";

export interface PermissionDef {
  key: Permission;
  category: PermissionCategory;
  label: string;
  description: string;
  /**
   * An administrative power (docs/WORKSPACE_ROLES.md §3): only someone holding `roles.delegate` may put it into a role
   * or hand out a role that carries it. Owners hold every power.
   */
  admin: boolean;
  /** Held by owners only; no custom role may carry it. */
  ownerOnly: boolean;
  /** May a guest receive it through a custom role? Guests only ever get low-risk powers. */
  guestAllowed: boolean;
  /** May a custom role restrict (deny) it? Restrictions win over grants and never apply to owners. */
  restrictable: boolean;
}

type Def = Omit<PermissionDef, "key" | "admin" | "ownerOnly" | "guestAllowed" | "restrictable"> & Partial<PermissionDef>;

const DEFS: Record<Permission, Def> = {
  "workspace.edit": { category: "workspace", label: "Edit workspace profile", description: "Change the workspace's name, description and avatar.", admin: true },
  "workspace.usage.view": { category: "workspace", label: "View usage and storage", description: "See member, channel, file storage and app usage figures.", admin: true },
  "workspace.delete": { category: "workspace", label: "Delete the workspace", description: "Permanently delete the workspace and its channels.", admin: true, ownerOnly: true },
  "workspace.transfer": { category: "workspace", label: "Transfer ownership", description: "Hand ownership of the workspace to another member.", admin: true, ownerOnly: true },
  "members.view": { category: "members", label: "View the member directory", description: "Browse and search everyone in the workspace.", restrictable: true },
  "members.invite": { category: "members", label: "Invite people", description: "Create invitations for members and guests anywhere in the workspace.", admin: true },
  "members.invites.manage": { category: "members", label: "Manage all invitations", description: "See and revoke invitations created by anyone.", admin: true },
  "members.roles": { category: "members", label: "Change member access", description: "Switch people between Member and Guest.", admin: true },
  "members.titles": { category: "members", label: "Edit titles", description: "Change members' and guests' job titles.", admin: true },
  "members.remove": { category: "members", label: "Remove people", description: "Remove members and guests from the workspace.", admin: true },
  "roles.view": { category: "roles", label: "View roles", description: "See the workspace's roles and what each one allows." },
  "roles.manage": { category: "roles", label: "Create and edit roles", description: "Create, edit, clone and archive custom roles (only with powers you hold).", admin: true },
  "roles.assign": { category: "roles", label: "Assign roles", description: "Give custom roles to people and take them away (only roles within your own powers).", admin: true },
  "roles.delegate": { category: "roles", label: "Delegate admin powers", description: "Put administrative powers into roles and assign such roles.", admin: true },
  "teams.create": { category: "teams", label: "Create branches and teams", description: "Create branches, departments and teams anywhere in the workspace.", admin: true },
  "teams.manage": { category: "teams", label: "Manage branches and teams", description: "Edit, archive and restore any branch or team, and manage who is in it.", admin: true },
  "channels.create": { category: "channels", label: "Create workspace channels", description: "Create channels for the whole workspace.", admin: true },
  "channels.manage": { category: "channels", label: "Manage channels", description: "Rename, archive and manage the members of any channel in the workspace.", admin: true },
  "channels.visibility": { category: "channels", label: "Change channel access", description: "Make any channel public, private or announcement-only.", admin: true },
  "messages.post": { category: "messaging", label: "Post in channels", description: "Send messages and files in the workspace's channels.", restrictable: true, guestAllowed: true },
  "messages.announce": { category: "messaging", label: "Post announcements", description: "Post in announcement channels, where only managers can post." },
  "integrations.view": { category: "integrations", label: "View apps", description: "See the app directory and the apps installed in this workspace.", guestAllowed: true },
  "integrations.request": { category: "integrations", label: "Request apps", description: "Ask an admin to install an app.", restrictable: true },
  "integrations.install": { category: "integrations", label: "Install and approve apps", description: "Install apps, and approve or decline install requests.", admin: true },
  "integrations.configure": { category: "integrations", label: "Configure apps", description: "Change an installed app's settings, access and channel notifications.", admin: true },
  "integrations.connect": { category: "integrations", label: "Connect personal accounts", description: "Connect your own account of an installed app (for example your Google Drive).", restrictable: true, guestAllowed: true },
  "integrations.connect_workspace": { category: "integrations", label: "Connect workspace accounts", description: "Connect a shared account the whole workspace's app uses (for example a GitHub organisation).", admin: true },
  "integrations.uninstall": { category: "integrations", label: "Disconnect and uninstall apps", description: "Disconnect shared accounts and uninstall apps.", admin: true },
  "integrations.activity": { category: "integrations", label: "View app activity", description: "See apps' recent events, errors and health.", admin: true },
  "developers.manage": { category: "integrations", label: "Manage API keys and webhooks", description: "Create and revoke the workspace's API keys and outgoing webhooks (they never reach messages or private channels).", admin: true },
  "groups.manage": { category: "members", label: "Manage user groups", description: "Create groups such as Marketing or Engineering, choose their members, and remove groups.", admin: true },
  "workflows.manage": { category: "integrations", label: "Manage workflows", description: "Create, change, test and switch off the workspace's workflows (they run with their owner's rights).", admin: true },
  "inbox.view": { category: "messaging", label: "Work in the customer inbox", description: "See customer conversations from connected business accounts, write internal notes, and assign conversations.", restrictable: true },
  "inbox.reply": { category: "messaging", label: "Reply to customers", description: "Send replies to customers on Messenger, Instagram and WhatsApp from the inbox.", restrictable: true },
  "inbox.manage": { category: "messaging", label: "Manage the customer inbox", description: "Connect and disconnect business accounts, set up queues, AI and automation, see reports, and erase a customer's data.", admin: true },
  "audit.view": { category: "security", label: "View the audit log", description: "See every administrative change in the workspace.", admin: true },
  "policies.manage": { category: "security", label: "Manage policies", description: "Change the workspace's app policy (approval, blocked apps).", admin: true },
};

export const PERMISSIONS: readonly PermissionDef[] = PERMISSION_KEYS.map((key) => {
  const d = DEFS[key];
  return {
    key,
    category: d.category,
    label: d.label,
    description: d.description,
    admin: d.admin ?? false,
    ownerOnly: d.ownerOnly ?? false,
    guestAllowed: d.guestAllowed ?? false,
    restrictable: d.restrictable ?? false,
  };
});

const BY_KEY = new Map(PERMISSIONS.map((p) => [p.key, p]));

export function permissionDef(key: Permission): PermissionDef {
  const d = BY_KEY.get(key);
  if (!d) throw new Error(`Unknown permission ${key}`);
  return d;
}

export function isPermission(key: string): key is Permission {
  return BY_KEY.has(key as Permission);
}

/** What the protected base roles hold. Owners hold everything; admins everything but owner-only powers and delegation. */
const MEMBER_BASE: readonly Permission[] = ["members.view", "roles.view", "messages.post", "integrations.view", "integrations.request", "integrations.connect"];
const GUEST_BASE: readonly Permission[] = ["messages.post"];

export const BASE_ROLE_PERMISSIONS: Readonly<Record<OrgRole, ReadonlySet<Permission>>> = {
  OWNER: new Set(PERMISSION_KEYS),
  ADMIN: new Set(PERMISSION_KEYS.filter((k) => !permissionDef(k).ownerOnly && k !== "roles.delegate")),
  MEMBER: new Set(MEMBER_BASE),
  GUEST: new Set(GUEST_BASE),
};

/** Base roles, highest first: used to decide who may act on whom. */
export const BASE_ROLE_RANK: Readonly<Record<OrgRole, number>> = { OWNER: 3, ADMIN: 2, MEMBER: 1, GUEST: 0 };

/** Icons a custom role may show (names the app maps to its own icon set). */
export const ROLE_ICONS = ["shield", "star", "people", "briefcase", "support", "code", "finance", "eye", "megaphone", "badge"] as const;

export interface RoleTemplate {
  key: string;
  name: string;
  description: string;
  color: string;
  icon: (typeof ROLE_ICONS)[number];
  permissions: Permission[];
  denies: Permission[];
}

/**
 * Starter templates: a quick way to begin, copied into the workspace and freely editable afterwards. They are examples,
 * not a fixed hierarchy; a workspace defines its own roles.
 */
export const ROLE_TEMPLATES: readonly RoleTemplate[] = [
  {
    key: "moderator",
    name: "Moderator",
    description: "Keeps channels tidy: manages channels, their access and announcements.",
    color: "#E39B7B",
    icon: "shield",
    permissions: ["channels.manage", "channels.visibility", "messages.announce"],
    denies: [],
  },
  {
    key: "hr_manager",
    name: "HR Manager",
    description: "Brings people in and keeps the directory right.",
    color: "#D98BA7",
    icon: "people",
    permissions: ["members.invite", "members.invites.manage", "members.titles", "members.roles", "members.remove"],
    denies: [],
  },
  {
    key: "project_manager",
    name: "Project Manager",
    description: "Sets up teams and their channels.",
    color: "#C9A27A",
    icon: "briefcase",
    permissions: ["teams.create", "teams.manage", "channels.create", "members.invite"],
    denies: [],
  },
  {
    key: "app_manager",
    name: "App Manager",
    description: "Looks after the workspace's apps and their access.",
    color: "#B58FD1",
    icon: "code",
    permissions: ["integrations.install", "integrations.configure", "integrations.connect_workspace", "integrations.activity"],
    denies: [],
  },
  {
    key: "finance",
    name: "Finance",
    description: "Sees usage and storage figures.",
    color: "#8FB59A",
    icon: "finance",
    permissions: ["workspace.usage.view"],
    denies: [],
  },
  {
    key: "support_agent",
    name: "Support Agent",
    description: "Answers customers in the Unified Inbox.",
    color: "#7FA7D9",
    icon: "support",
    permissions: ["inbox.view", "inbox.reply"],
    denies: [],
  },
  {
    key: "read_only",
    name: "Read-Only Member",
    description: "Reads channels but can't post in them, and can't connect or request apps.",
    color: "#9A9A9A",
    icon: "eye",
    permissions: [],
    denies: ["messages.post", "integrations.connect", "integrations.request"],
  },
];
