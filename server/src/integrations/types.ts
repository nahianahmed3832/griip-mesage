/**
 * Shared types of the integrations framework (docs/INTEGRATIONS.md). A *connector* is one app in the directory (Google
 * Drive, GitHub); a *provider* is whose identity platform it signs in with (Google, Microsoft, GitHub). Connectors declare
 * exactly what they do as *capabilities*; nothing a connector can't do is ever listed.
 */

export const CATEGORIES = [
  "files",
  "docs",
  "calendar",
  "communication",
  "projects",
  "engineering",
  "crm",
  "automation",
] as const;
export type Category = (typeof CATEGORIES)[number];

export type ProviderId = "google" | "microsoft" | "github" | "atlassian" | "notion" | "linear" | "figma" | "gitlab" | "asana" | "zoom" | "sandbox" | "none";

/** Whose account a capability uses: the member's own, or the workspace's shared connection. */
export type AccountType = "member" | "workspace";

/** What a capability does: reads for the person, writes on their explicit action, or notifications into channels. */
export type CapabilityKind = "read" | "write" | "notify";

export interface CapabilityDef {
  key: string;
  label: string;
  description: string;
  kind: CapabilityKind;
  accountType: AccountType;
  /** The provider scopes it needs. Only the granted capabilities' scopes are ever requested. */
  scopes: string[];
  /** Requested when an admin installs without choosing (the least-privilege set). */
  default: boolean;
}

export type Availability = "available" | "coming_soon" | "sandbox";

export interface ConnectorDef {
  id: string;
  provider: ProviderId;
  name: string;
  publisher: string;
  category: Category;
  /** Name of the icon the app draws (never a provider's logo file). */
  icon: string;
  summary: string;
  description: string;
  featured: boolean;
  availability: Availability;
  version: number;
  capabilities: CapabilityDef[];
  /** Plain-language privacy summary: what Meldiq's server sees and keeps. */
  dataAccess: string[];
  /** What the platform operator must configure before anyone can use it (shown to platform admins). */
  setup: string[];
  /** Data that needs extra care (mail): shown with a warning, never installed silently. */
  sensitivity: "standard" | "high";
  /** Provider review needed before a public launch (Google verification, Microsoft publisher verification…). */
  reviewNote: string | null;
  docsUrl: string;
}

/** The state of a connector for one person in one workspace, from most to least blocking. */
export type ConnectorState =
  | "coming_soon"
  | "needs_configuration"
  | "blocked"
  | "available"
  | "approval_required"
  | "disabled"
  | "installed"
  | "connected"
  | "error";

/** An external item, normalised across providers. Only metadata: never a file's content. */
export interface Resource {
  id: string;
  kind: "file" | "folder" | "document" | "spreadsheet" | "presentation" | "workbook" | "event" | "issue" | "pull_request" | "page" | "repository" | "site" | "library" | "other";
  title: string;
  url: string | null;
  mimeType?: string | null;
  modifiedAt?: number | null;
  owner?: string | null;
  size?: number | null;
  /** Where it lives: a folder, a calendar, a project, a repository. */
  container?: string | null;
  /** A short second line: an event's place, an issue's assignee. */
  detail?: string | null;
  status?: string | null;
  startAt?: number | null;
  endAt?: number | null;
  /** Can be opened as a folder or library (browse). */
  browsable?: boolean;
  /** Short-lived link to a thumbnail for the person's own picker; never forwarded into messages. */
  thumbnailUrl?: string | null;
}

export interface Page<T> {
  items: T[];
  cursor: string | null;
}

/** Facts about an item beyond its metadata, where a capability provides them (an Excel workbook's sheets). */
export interface ResourceDetails {
  resource: Resource;
  sections: { title: string; rows: { label: string; value: string }[] }[];
}

/** What a connected account turned out to be when it was checked. */
export interface AccountIdentity {
  externalId: string;
  label: string | null;
  meta?: Record<string, string>;
}

export interface TokenSet {
  accessToken: string;
  refreshToken: string | null;
  /** Epoch ms, or null for tokens that don't expire. */
  expiresAt: number | null;
  /** Scopes the provider says it granted; null when it doesn't say (then the requested ones are recorded). */
  scopes: string[] | null;
  /** Provider ids returned with the token (Notion's workspace and bot ids). */
  meta?: Record<string, string>;
}

/** A provider event turned into a channel notification (src/integrations/events.ts). */
export interface NormalizedEvent {
  /** Which subscription resource it belongs to ("owner/repo"). */
  resource: string;
  /** Event key matched against a subscription's events ("pull_request.opened"). */
  event: string;
  title: string;
  url: string | null;
  summary: string | null;
  actor: string | null;
  occurredAt: number;
  /** Identifies the provider object and change, so the same change is never posted twice. */
  dedupeKey: string;
}
