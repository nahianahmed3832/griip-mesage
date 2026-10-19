import type { AuthorizeParams, OAuthProvider } from "../connector.js";
import { type FetchLike, ProviderError, providerJson } from "../http.js";
import type { TokenSet } from "../types.js";

/**
 * The OAuth 2.0 authorisation-code flow most providers share (RFC 6749, PKCE RFC 7636). Provider files only fill in
 * endpoints and quirks. The client secret is only ever sent to the provider's own token endpoint.
 */
export interface OAuth2Spec {
  label: string;
  authorizeEndpoint: string;
  tokenEndpoint: string;
  /** Where refreshes go when it isn't the token endpoint (Figma). */
  refreshEndpoint?: string;
  clientId: string;
  clientSecret: string;
  pkce: boolean;
  /** How the client authenticates at the token endpoint. */
  clientAuth: "body" | "basic";
  /** Token requests as a form (most) or JSON (Atlassian, Notion). */
  tokenFormat: "form" | "json";
  scopeSeparator: " " | ",";
  /** Extra authorize parameters (access_type=offline, prompt=consent, audience…). */
  authorizeExtras?: Record<string, string>;
  /** Whether scopes go in the authorize URL at all (Notion has none). */
  sendScopes?: boolean;
  /** Token requests also carry the scopes (Microsoft). */
  scopesInTokenRequest?: boolean;
  revoke?: (tokens: { accessToken: string; refreshToken: string | null }) => Promise<void>;
  /** Pulls provider ids out of the token answer (Notion's workspace). */
  tokenMeta?: (raw: Record<string, unknown>) => Record<string, string>;
  /** Maps the provider's spelling of granted scopes back to the requested names (Google, Microsoft). */
  normalizeScopes?: (granted: string[], hasRefreshToken: boolean) => string[];
  now: () => number;
}

interface RawToken {
  access_token?: unknown;
  refresh_token?: unknown;
  expires_in?: unknown;
  scope?: unknown;
  token_type?: unknown;
  [k: string]: unknown;
}

export function tokenSetFrom(raw: RawToken | null, now: number, previousRefresh: string | null, label: string, meta?: Record<string, string>): TokenSet {
  // Some providers (GitHub) answer 200 with an OAuth error in the body.
  if (raw && typeof raw.error === "string") throw new ProviderError("invalid_grant", `${label} refused the sign-in; connect again`);
  if (!raw || typeof raw.access_token !== "string" || !raw.access_token) throw new ProviderError("bad_response", `${label} didn't return a token`);
  const expiresIn = typeof raw.expires_in === "number" ? raw.expires_in : typeof raw.expires_in === "string" ? Number(raw.expires_in) : NaN;
  const scope = typeof raw.scope === "string" ? raw.scope : null;
  return {
    accessToken: raw.access_token,
    // Providers that rotate refresh tokens return a new one; those that don't keep the old one valid.
    refreshToken: typeof raw.refresh_token === "string" && raw.refresh_token ? raw.refresh_token : previousRefresh,
    expiresAt: Number.isFinite(expiresIn) && expiresIn > 0 ? now + expiresIn * 1000 : null,
    scopes: scope === null ? null : scope.split(/[ ,]+/).filter(Boolean).sort(),
    meta,
  };
}

export function oauth2Provider(spec: OAuth2Spec, fetch: FetchLike): OAuthProvider {
  const tokenRequest = async (params: Record<string, string>, previousRefresh: string | null, endpoint: string = spec.tokenEndpoint): Promise<TokenSet> => {
    const headers: Record<string, string> = {};
    const body: Record<string, string> = { ...params };
    if (spec.clientAuth === "basic") headers.authorization = `Basic ${Buffer.from(`${spec.clientId}:${spec.clientSecret}`).toString("base64")}`;
    else {
      body.client_id = spec.clientId;
      body.client_secret = spec.clientSecret;
    }
    const raw = await providerJson<RawToken>(fetch, endpoint, spec.tokenFormat === "json" ? { method: "POST", headers, json: body } : { method: "POST", headers, form: body }, spec.label);
    const t = tokenSetFrom(raw, spec.now(), previousRefresh, spec.label, raw && spec.tokenMeta ? spec.tokenMeta(raw) : undefined);
    const scopes = t.scopes && spec.normalizeScopes ? [...new Set(spec.normalizeScopes(t.scopes, !!t.refreshToken))].sort() : t.scopes;
    return { ...t, scopes };
  };
  return {
    pkce: spec.pkce,
    authorizeUrl(p: AuthorizeParams): string {
      const u = new URL(spec.authorizeEndpoint);
      u.searchParams.set("client_id", spec.clientId);
      u.searchParams.set("redirect_uri", p.redirectUri);
      u.searchParams.set("response_type", "code");
      u.searchParams.set("state", p.state);
      if (spec.sendScopes !== false && p.scopes.length) u.searchParams.set("scope", p.scopes.join(spec.scopeSeparator));
      if (spec.pkce && p.codeChallenge) {
        u.searchParams.set("code_challenge", p.codeChallenge);
        u.searchParams.set("code_challenge_method", "S256");
      }
      for (const [k, v] of Object.entries(spec.authorizeExtras ?? {})) u.searchParams.set(k, v);
      return u.toString();
    },
    exchange: ({ code, redirectUri, verifier, scopes }) =>
      tokenRequest(
        {
          grant_type: "authorization_code",
          code,
          redirect_uri: redirectUri,
          ...(spec.pkce && verifier ? { code_verifier: verifier } : {}),
          ...(spec.scopesInTokenRequest ? { scope: scopes.join(spec.scopeSeparator) } : {}),
        },
        null,
      ),
    refresh: (refreshToken, scopes) =>
      tokenRequest(
        { grant_type: "refresh_token", refresh_token: refreshToken, ...(spec.scopesInTokenRequest ? { scope: scopes.join(spec.scopeSeparator) } : {}) },
        refreshToken,
        spec.refreshEndpoint,
      ),
    revoke: spec.revoke,
  };
}

/** A text query safe inside a provider's quoted string ('…' or "…"): backslashes and the quote escaped. */
export function quoteEscape(q: string, quote: "'" | '"'): string {
  return q.replace(/\\/g, "\\\\").replaceAll(quote, `\\${quote}`);
}

/** Opaque paging cursors that are provider URLs: only ever followed back to [allowedPrefix]. */
export function encodeCursor(next: string | null | undefined): string | null {
  return next ? Buffer.from(next, "utf8").toString("base64url") : null;
}
export function decodeCursor(cursor: string | null, allowedPrefix: string): string | null {
  if (!cursor) return null;
  const url = Buffer.from(cursor, "base64url").toString("utf8");
  if (!url.startsWith(allowedPrefix)) throw new ProviderError("bad_response", "That page link isn't valid any more");
  return url;
}

export const ts = (v: unknown): number | null => {
  if (typeof v !== "string") return null;
  const t = Date.parse(v);
  return Number.isFinite(t) ? t : null;
};
export const str = (v: unknown): string | null => (typeof v === "string" && v ? v : null);
export const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : typeof v === "string" && /^\d+$/.test(v) ? Number(v) : null);

/** Provider metadata kept with an account: only non-empty strings. */
export const metaOf = (o: Record<string, string | null | undefined>): Record<string, string> =>
  Object.fromEntries(Object.entries(o).filter((e): e is [string, string] => typeof e[1] === "string" && e[1].length > 0));
