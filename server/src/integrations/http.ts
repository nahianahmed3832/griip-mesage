/**
 * Calls to providers (docs/INTEGRATIONS.md › Errors). Every request has a deadline, every failure becomes a
 * [ProviderError] with a kind the framework acts on (refresh, back off, mark the account), and provider response bodies
 * never reach logs or API answers: they can contain the person's data.
 *
 * Only fixed provider hosts written in the connectors are ever called; a URL someone pastes is parsed for an id and
 * never fetched (no server-side request forgery).
 */
export type FetchLike = (url: string, init?: RequestInit) => Promise<Response>;

export type ProviderErrorKind =
  | "unauthorized" // the token is no longer accepted (refresh, or reconnect)
  | "invalid_grant" // the refresh token or code was refused: reconnect
  | "forbidden" // the provider refuses this person or app the resource (or a scope is missing)
  | "not_found"
  | "rate_limited"
  | "unavailable" // network, timeout or the provider's own error
  | "bad_response"; // an answer the connector doesn't understand

export class ProviderError extends Error {
  constructor(
    readonly kind: ProviderErrorKind,
    message: string,
    readonly status: number | null = null,
    readonly retryAfterMs: number | null = null,
  ) {
    super(message);
  }
}

const DEFAULT_TIMEOUT_MS = 10_000;
/** Largest provider answer read (search pages are small; this bounds memory if a provider misbehaves). */
const MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

export interface ProviderRequest {
  method?: string;
  headers?: Record<string, string>;
  /** JSON body (sent as application/json), or a form for token endpoints. */
  json?: unknown;
  form?: Record<string, string>;
  query?: Record<string, string | number | undefined | null>;
  timeoutMs?: number;
}

export function withQuery(url: string, query?: ProviderRequest["query"]): string {
  if (!query) return url;
  const u = new URL(url);
  for (const [k, v] of Object.entries(query)) if (v !== undefined && v !== null) u.searchParams.set(k, String(v));
  return u.toString();
}

function retryAfter(h: string | null): number | null {
  if (!h) return null;
  const s = Number(h);
  if (Number.isFinite(s)) return Math.min(Math.max(s, 0), 3600) * 1000;
  const at = Date.parse(h);
  return Number.isFinite(at) ? Math.min(Math.max(at - Date.now(), 0), 3_600_000) : null;
}

/** Performs a provider call and returns its JSON (or null for an empty answer). */
export async function providerJson<T>(fetch: FetchLike, url: string, req: ProviderRequest = {}, label = "provider"): Promise<T> {
  const headers: Record<string, string> = { accept: "application/json", "user-agent": "Meldiq-Integrations/1", ...req.headers };
  let body: string | undefined;
  if (req.json !== undefined) {
    headers["content-type"] = "application/json";
    body = JSON.stringify(req.json);
  } else if (req.form) {
    headers["content-type"] = "application/x-www-form-urlencoded";
    body = new URLSearchParams(req.form).toString();
  }
  let res: Response;
  try {
    res = await fetch(withQuery(url, req.query), {
      method: req.method ?? (body ? "POST" : "GET"),
      headers,
      body,
      redirect: "error",
      signal: AbortSignal.timeout(req.timeoutMs ?? DEFAULT_TIMEOUT_MS),
    });
  } catch {
    throw new ProviderError("unavailable", `${label} can't be reached right now`);
  }
  const text = await readCapped(res);
  if (!res.ok) throw errorFor(res.status, text, res.headers.get("retry-after"), label);
  if (!text) return null as T;
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new ProviderError("bad_response", `${label} sent an answer Meldiq doesn't understand`, res.status);
  }
}

async function readCapped(res: Response): Promise<string> {
  const len = Number(res.headers.get("content-length") ?? "0");
  if (len > MAX_RESPONSE_BYTES) {
    await res.body?.cancel().catch(() => undefined);
    throw new ProviderError("bad_response", "The provider's answer was too large", res.status);
  }
  const text = await res.text();
  if (text.length > MAX_RESPONSE_BYTES) throw new ProviderError("bad_response", "The provider's answer was too large", res.status);
  return text;
}

/** OAuth error codes are safe to read (they're from the spec); nothing else from the body is used. */
function oauthErrorCode(text: string): string | null {
  try {
    const v = JSON.parse(text) as { error?: unknown };
    return typeof v.error === "string" ? v.error : null;
  } catch {
    const m = /(?:^|&)error=([a-z_]+)/.exec(text);
    return m ? m[1] : null;
  }
}

function errorFor(status: number, text: string, retry: string | null, label: string): ProviderError {
  const oauth = oauthErrorCode(text);
  if (oauth === "invalid_grant" || oauth === "bad_refresh_token" || oauth === "bad_verification_code") {
    return new ProviderError("invalid_grant", `${label} refused the sign-in; connect again`, status);
  }
  if (status === 401) return new ProviderError("unauthorized", `${label} no longer accepts this connection`, status);
  if (status === 403) return new ProviderError("forbidden", `${label} doesn't allow this for your account`, status, retryAfter(retry));
  if (status === 404) return new ProviderError("not_found", `${label} couldn't find that, or you can't access it`, status);
  if (status === 429) return new ProviderError("rate_limited", `${label} is limiting requests; try again shortly`, status, retryAfter(retry) ?? 30_000);
  if (status >= 500) return new ProviderError("unavailable", `${label} is having trouble right now`, status, retryAfter(retry));
  return new ProviderError("bad_response", `${label} refused the request (${status})`, status);
}
