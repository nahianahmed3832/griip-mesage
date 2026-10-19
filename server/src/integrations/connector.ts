import type { IntegrationsConfig } from "./config.js";
import type { FetchLike, ProviderRequest } from "./http.js";
import type { AccountIdentity, ConnectorDef, NormalizedEvent, Page, Resource, ResourceDetails, TokenSet } from "./types.js";

/**
 * What a connector implements (docs/INTEGRATIONS.md › Framework). Everything is optional except the definition and
 * `identify`: a connector provides exactly the operations its capabilities declare, and the framework refuses the rest.
 * Provider-specific code lives only behind this interface; installs, accounts, authorisation, token storage, refresh,
 * webhooks, jobs and audit are common.
 */

/** The authenticated call context the framework gives a connector for one account. */
export interface CallContext {
  /** Calls a provider URL with the account's token (refreshed and retried once if it expired). */
  api<T>(url: string, req?: ProviderRequest): Promise<T>;
  /** Provider ids recorded when the account was connected (tenant, cloud id, installation). */
  meta: Readonly<Record<string, string>>;
  externalId: string;
}

export interface AuthorizeParams {
  state: string;
  redirectUri: string;
  scopes: string[];
  /** PKCE S256 challenge, when the provider supports it. */
  codeChallenge: string | null;
}

export interface OAuthProvider {
  /** Providers that support PKCE (S256) get a challenge with every authorisation. */
  pkce: boolean;
  authorizeUrl(p: AuthorizeParams): string;
  exchange(p: { code: string; redirectUri: string; verifier: string | null; scopes: string[] }): Promise<TokenSet>;
  /** Absent: the provider's tokens don't expire (or can't be refreshed): the person reconnects. */
  refresh?(refreshToken: string, scopes: string[]): Promise<TokenSet>;
  /** Tells the provider to forget the grant, where it offers that. Best effort on disconnect. */
  revoke?(tokens: { accessToken: string; refreshToken: string | null }): Promise<void>;
}

export interface ActionInput {
  /** The resource the action is about (an issue key), when it has one. */
  resourceId?: string;
  fields: Record<string, string>;
}

export interface ActionDef {
  /** The capability that allows it. */
  capability: string;
  label: string;
  /** Validates and performs the action as the person; returns what was created or changed. */
  run(ctx: CallContext, input: ActionInput): Promise<Resource>;
}

/** Verified inbound events (webhooks) for a connector's workspace connections. */
export interface WebhookHandler {
  /**
   * Checks the delivery's signature over the raw body and its freshness. Returns the delivery's unique id and event name
   * (for duplicate detection), or null to refuse it. Must not throw on bad input.
   */
  verify(raw: Buffer, headers: Readonly<Record<string, string | undefined>>): { deliveryId: string; event: string } | null;
  /**
   * Which workspace connection the delivery is for (the provider's installation or workspace id), and the normalised
   * events it carries. Payload fields are validated here; anything unexpected yields no events.
   */
  parse(event: string, payload: unknown): { externalId: string; events: NormalizedEvent[] } | null;
  /** Event keys a subscription may choose, with labels. */
  eventTypes: { key: string; label: string }[];
}

export interface WorkspaceLinking {
  /**
   * The provider's workspace-level targets the admin's own account can reach (GitHub App installations). Linking checks
   * again with the same account, so a shared connection can never point at something the admin couldn't access.
   */
  targets(ctx: CallContext): Promise<{ id: string; label: string }[]>;
  /** Where to install the provider's app when there is nothing to link yet. */
  installUrl(): string | null;
  /**
   * Resources a subscription can watch in the linked target, as the admin's own account sees them (GitHub: the
   * installation's repositories that person can read). Never listed with the shared connection's own rights.
   */
  resources(ctx: CallContext, externalId: string, cursor: string | null): Promise<Page<{ id: string; label: string }>>;
}

export interface Connector {
  def: ConnectorDef;
  /** Null when the platform hasn't configured the provider (the connector shows "Needs configuration"). */
  oauth: OAuthProvider | null;
  /** For connectors without an OAuth provider (incoming webhooks): whether the server can run them. */
  configured?: () => boolean;
  /** Checks the connection works and says whose account it is. */
  identify(ctx: CallContext, tokens: TokenSet): Promise<AccountIdentity>;
  search?: { capability: string; run(ctx: CallContext, query: string, cursor: string | null, filter: string | null): Promise<Page<Resource>> };
  browse?: { capability: string; run(ctx: CallContext, containerId: string | null, cursor: string | null): Promise<Page<Resource>> };
  get?: { capability: string; run(ctx: CallContext, id: string): Promise<Resource> };
  /** Recognises the provider's own links and returns the resource id, without any network access. */
  matchUrl?(url: URL): string | null;
  unfurlCapability?: string;
  details?: { capability: string; run(ctx: CallContext, id: string): Promise<ResourceDetails> };
  actions?: Record<string, ActionDef>;
  webhook?: WebhookHandler;
  linking?: WorkspaceLinking;
}

export interface ConnectorDeps {
  config: IntegrationsConfig;
  fetch: FetchLike;
  now: () => number;
}
