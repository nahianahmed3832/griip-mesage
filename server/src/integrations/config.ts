import fs from "node:fs";
import { webReturnUrlsFrom } from "./returns.js";
import { type TokenKeyring, keyringFrom } from "./tokens.js";
import type { ProviderId } from "./types.js";

/**
 * Integration settings (docs/INTEGRATIONS.md › Configuration). Nothing is required: without a provider's credentials its
 * connectors show "Needs configuration" and nobody can install them. Secrets come from the environment or a file named
 * by `<NAME>_FILE` (for example a mounted secret), never from Git, the app or a request.
 */
export interface GoogleConfig {
  clientId: string;
  clientSecret: string;
}
export interface MicrosoftConfig {
  clientId: string;
  clientSecret: string;
  /** "organizations" (work and school accounts, the default: Excel and SharePoint need them), "common" or a tenant id. */
  tenant: string;
}
export interface GitHubConfig {
  appId: string;
  slug: string;
  clientId: string;
  clientSecret: string;
  webhookSecret: string;
}
export interface OAuthClientConfig {
  clientId: string;
  clientSecret: string;
}
export interface LinearConfig extends OAuthClientConfig {
  webhookSecret: string | null;
}
export interface GitLabConfig extends OAuthClientConfig {
  /** gitlab.com, or a self-managed instance (https only, no path). */
  baseUrl: string;
}

export interface IntegrationsConfig {
  /** The server's public HTTPS address: callbacks and webhooks are under it. Null: no OAuth (needs configuration). */
  publicUrl: string | null;
  /** Where the browser goes after a callback: the app's own link (no secret is ever put in it). */
  appReturnUrl: string;
  /** Web clients' return addresses by exact origin (INTEGRATIONS_WEB_RETURN_URLS, src/integrations/returns.ts). */
  webReturnUrls: ReadonlyMap<string, string>;
  /** Seals stored tokens (AES-256-GCM). Null: nothing can be connected (needs configuration). */
  keyring: TokenKeyring | null;
  /** Platform administrators (user ids): the global app registry. Never grants anything inside a workspace. */
  platformAdmins: ReadonlySet<string>;
  /** Connectors the operator turned off for the whole server. */
  disabled: ReadonlySet<string>;
  /** A local test provider (never in production): exercises every flow without a third party. */
  sandbox: { webhookSecret: string } | null;
  google: GoogleConfig | null;
  microsoft: MicrosoftConfig | null;
  github: GitHubConfig | null;
  atlassian: OAuthClientConfig | null;
  notion: OAuthClientConfig | null;
  linear: LinearConfig | null;
  figma: OAuthClientConfig | null;
  gitlab: GitLabConfig | null;
  /** Round 6 (2026-10-19). */
  asana: OAuthClientConfig | null;
  zoom: OAuthClientConfig | null;
  /** Environment names still missing per provider, for "needs configuration" (names only, never values). */
  missing: Readonly<Record<ProviderId, string[]>>;
  /** Sign-ins one person may start per minute. */
  connectPerMinute: number;
  /** Authorisations must finish within this time. */
  stateTtlMs: number;
  /** Channel notifications, accepted webhook ids and finished jobs are kept this long. */
  eventRetentionDays: number;
  deliveryRetentionDays: number;
  jobRetentionDays: number;
}

/** A secret from NAME, or from the file NAME_FILE names. */
export function secretFrom(env: NodeJS.ProcessEnv, name: string): string | null {
  const direct = env[name]?.trim();
  if (direct) return direct;
  const file = env[`${name}_FILE`]?.trim();
  if (!file) return null;
  try {
    return fs.readFileSync(file, "utf8").trim() || null;
  } catch (e) {
    throw new Error(`${name}_FILE can't be read: ${(e as Error).message}`);
  }
}

export function integrationsConfig(env: NodeJS.ProcessEnv, appEnv: string, int: (n: string, d: number) => number): IntegrationsConfig {
  const production = appEnv === "production";
  // The API's public address (PUBLIC_API_URL, docs/ENVIRONMENTS.md) unless sign-ins need a different one.
  const publicUrl = (env.INTEGRATIONS_PUBLIC_URL || env.PUBLIC_API_URL || "").trim().replace(/\/+$/, "") || null;
  if (publicUrl) {
    let u: URL;
    try {
      u = new URL(publicUrl);
    } catch {
      throw new Error("INTEGRATIONS_PUBLIC_URL must be a URL such as https://msg.example.com");
    }
    if (u.search || u.hash) throw new Error("INTEGRATIONS_PUBLIC_URL must not contain a query or fragment");
    if (production && u.protocol !== "https:") throw new Error("INTEGRATIONS_PUBLIC_URL must use https in production");
  }
  const missing: Record<ProviderId, string[]> = { google: [], microsoft: [], github: [], atlassian: [], notion: [], linear: [], figma: [], gitlab: [], asana: [], zoom: [], sandbox: [], none: [] };
  const need = (provider: ProviderId, names: string[]): Record<string, string> | null => {
    const values: Record<string, string> = {};
    for (const n of names) {
      const v = secretFrom(env, n);
      if (v) values[n] = v;
      else missing[provider].push(n);
    }
    return missing[provider].length ? null : values;
  };
  const g = need("google", ["GOOGLE_OAUTH_CLIENT_ID", "GOOGLE_OAUTH_CLIENT_SECRET"]);
  const m = need("microsoft", ["MICROSOFT_OAUTH_CLIENT_ID", "MICROSOFT_OAUTH_CLIENT_SECRET"]);
  const gh = need("github", ["GITHUB_APP_ID", "GITHUB_APP_SLUG", "GITHUB_APP_CLIENT_ID", "GITHUB_APP_CLIENT_SECRET", "GITHUB_APP_WEBHOOK_SECRET"]);
  const at = need("atlassian", ["ATLASSIAN_OAUTH_CLIENT_ID", "ATLASSIAN_OAUTH_CLIENT_SECRET"]);
  const no = need("notion", ["NOTION_OAUTH_CLIENT_ID", "NOTION_OAUTH_CLIENT_SECRET"]);
  const li = need("linear", ["LINEAR_OAUTH_CLIENT_ID", "LINEAR_OAUTH_CLIENT_SECRET"]);
  const fi = need("figma", ["FIGMA_OAUTH_CLIENT_ID", "FIGMA_OAUTH_CLIENT_SECRET"]);
  const gl = need("gitlab", ["GITLAB_OAUTH_CLIENT_ID", "GITLAB_OAUTH_CLIENT_SECRET"]);
  const as = need("asana", ["ASANA_OAUTH_CLIENT_ID", "ASANA_OAUTH_CLIENT_SECRET"]);
  const zo = need("zoom", ["ZOOM_OAUTH_CLIENT_ID", "ZOOM_OAUTH_CLIENT_SECRET"]);
  const gitlabBase = (env.GITLAB_BASE_URL ?? "https://gitlab.com").trim().replace(/\/+$/, "");
  {
    let u: URL;
    try {
      u = new URL(gitlabBase);
    } catch {
      throw new Error("GITLAB_BASE_URL must be a URL such as https://gitlab.com");
    }
    if (u.protocol !== "https:" || u.pathname !== "/" || u.search || u.hash || u.username) {
      throw new Error("GITLAB_BASE_URL must be an https address with no path, such as https://gitlab.example.com");
    }
  }
  // Shared requirements: without them no provider can be used.
  const keyring = keyringFrom(secretFrom(env, "INTEGRATIONS_TOKEN_KEY"), secretFrom(env, "INTEGRATIONS_TOKEN_KEY_PREVIOUS"));
  for (const p of Object.keys(missing) as ProviderId[]) {
    if (p === "none") continue;
    if (!publicUrl) missing[p].unshift("INTEGRATIONS_PUBLIC_URL");
    if (!keyring) missing[p].unshift("INTEGRATIONS_TOKEN_KEY");
  }
  const sandboxOn = (env.INTEGRATIONS_SANDBOX ?? "").trim() === "on";
  if (sandboxOn && production) throw new Error("INTEGRATIONS_SANDBOX is for development and tests only, never production");
  const tenant = (env.MICROSOFT_OAUTH_TENANT ?? "organizations").trim();
  if (!/^(organizations|common|consumers|[0-9a-f-]{36}|[a-z0-9.-]+\.onmicrosoft\.com)$/i.test(tenant)) {
    throw new Error("MICROSOFT_OAUTH_TENANT must be organizations, common, consumers, a tenant id or a tenant domain");
  }
  const ready = (p: ProviderId) => missing[p].length === 0;
  return {
    publicUrl,
    appReturnUrl: (env.INTEGRATIONS_APP_RETURN_URL ?? "meldiq://integrations").trim(),
    webReturnUrls: webReturnUrlsFrom(env.INTEGRATIONS_WEB_RETURN_URLS, production),
    keyring,
    platformAdmins: new Set((env.PLATFORM_ADMIN_USER_IDS ?? "").split(",").map((s) => s.trim()).filter(Boolean)),
    disabled: new Set((env.INTEGRATIONS_DISABLED ?? "").split(",").map((s) => s.trim()).filter(Boolean)),
    sandbox: sandboxOn ? { webhookSecret: secretFrom(env, "INTEGRATIONS_SANDBOX_WEBHOOK_SECRET") ?? "meldiq-sandbox-local-secret" } : null,
    google: g && ready("google") ? { clientId: g.GOOGLE_OAUTH_CLIENT_ID, clientSecret: g.GOOGLE_OAUTH_CLIENT_SECRET } : null,
    microsoft: m && ready("microsoft") ? { clientId: m.MICROSOFT_OAUTH_CLIENT_ID, clientSecret: m.MICROSOFT_OAUTH_CLIENT_SECRET, tenant } : null,
    github:
      gh && ready("github")
        ? {
            appId: gh.GITHUB_APP_ID,
            slug: gh.GITHUB_APP_SLUG,
            clientId: gh.GITHUB_APP_CLIENT_ID,
            clientSecret: gh.GITHUB_APP_CLIENT_SECRET,
            webhookSecret: gh.GITHUB_APP_WEBHOOK_SECRET,
          }
        : null,
    atlassian: at && ready("atlassian") ? { clientId: at.ATLASSIAN_OAUTH_CLIENT_ID, clientSecret: at.ATLASSIAN_OAUTH_CLIENT_SECRET } : null,
    notion: no && ready("notion") ? { clientId: no.NOTION_OAUTH_CLIENT_ID, clientSecret: no.NOTION_OAUTH_CLIENT_SECRET } : null,
    linear:
      li && ready("linear")
        ? { clientId: li.LINEAR_OAUTH_CLIENT_ID, clientSecret: li.LINEAR_OAUTH_CLIENT_SECRET, webhookSecret: secretFrom(env, "LINEAR_WEBHOOK_SECRET") }
        : null,
    figma: fi && ready("figma") ? { clientId: fi.FIGMA_OAUTH_CLIENT_ID, clientSecret: fi.FIGMA_OAUTH_CLIENT_SECRET } : null,
    gitlab: gl && ready("gitlab") ? { clientId: gl.GITLAB_OAUTH_CLIENT_ID, clientSecret: gl.GITLAB_OAUTH_CLIENT_SECRET, baseUrl: gitlabBase } : null,
    asana: as && ready("asana") ? { clientId: as.ASANA_OAUTH_CLIENT_ID, clientSecret: as.ASANA_OAUTH_CLIENT_SECRET } : null,
    zoom: zo && ready("zoom") ? { clientId: zo.ZOOM_OAUTH_CLIENT_ID, clientSecret: zo.ZOOM_OAUTH_CLIENT_SECRET } : null,
    missing,
    connectPerMinute: Math.min(Math.max(int("INTEGRATIONS_CONNECTS_PER_MINUTE", 10), 1), 1000),
    stateTtlMs: Math.min(Math.max(int("INTEGRATIONS_STATE_TTL_SECONDS", 600), 60), 1800) * 1000,
    eventRetentionDays: Math.min(Math.max(int("INTEGRATIONS_EVENT_RETENTION_DAYS", 30), 1), 365),
    deliveryRetentionDays: Math.min(Math.max(int("INTEGRATIONS_DELIVERY_RETENTION_DAYS", 7), 1), 30),
    jobRetentionDays: Math.min(Math.max(int("INTEGRATIONS_JOB_RETENTION_DAYS", 14), 1), 90),
  };
}
