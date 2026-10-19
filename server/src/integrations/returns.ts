import { ApiError } from "../util.js";

/**
 * Where the browser goes after a sign-in (docs/INTEGRATIONS.md › Connecting an account). By default the phone app's
 * own link (INTEGRATIONS_APP_RETURN_URL). A web client names its origin when it starts the sign-in; only origins the
 * operator listed in INTEGRATIONS_WEB_RETURN_URLS are accepted, compared as exact strings, and the page then links to
 * the operator's address for that origin, never to anything the request spelled. So no value can turn the server into
 * an open redirect.
 */
const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]"]);

function webReturnUrl(entry: string, production: boolean): URL {
  const refuse = (why: string) => new Error(`INTEGRATIONS_WEB_RETURN_URLS entry ${JSON.stringify(entry)} ${why}`);
  let u: URL;
  try {
    u = new URL(entry);
  } catch {
    throw refuse("isn't a URL such as https://web.example.com/#/workspace/apps");
  }
  const localDev = u.protocol === "http:" && !production && LOOPBACK_HOSTS.has(u.hostname);
  if (u.protocol !== "https:" && !localDev) throw refuse("must use https (plain http only for localhost outside production)");
  if (u.username || u.password) throw refuse("must not contain a user name or password");
  if (u.search) throw refuse("must not contain a query");
  // The flow, result and one-time finish code are appended to the hash route, so they stay in the browser: a fragment
  // never reaches the web host's logs or a Referer header.
  if (!u.hash.startsWith("#/") || u.hash.includes("?")) throw refuse("must end in a hash route without a query, such as #/workspace/apps");
  return u;
}

/** INTEGRATIONS_WEB_RETURN_URLS (comma-separated) keyed by origin. Refuses the whole list on any bad entry. */
export function webReturnUrlsFrom(raw: string | undefined, production: boolean): ReadonlyMap<string, string> {
  const entries = (raw ?? "").split(",").map((s) => s.trim()).filter(Boolean);
  const urls = new Map<string, string>();
  for (const u of entries.map((e) => webReturnUrl(e, production))) {
    if (urls.has(u.origin)) throw new Error(`INTEGRATIONS_WEB_RETURN_URLS lists ${u.origin} twice`);
    urls.set(u.origin, u.href);
  }
  return urls;
}

/**
 * The web origin a sign-in returns to, from the connect request's `return` (absent: the phone app). The value must be
 * exactly a listed origin, as a browser's `location.origin` spells it: nothing is parsed or normalised, so encodings,
 * user-info, other schemes, ports and trailing characters are simply not equal.
 */
export function webReturnOrigin(allowed: ReadonlyMap<string, string>, requested: string | undefined): string | null {
  if (requested === undefined) return null;
  if (!allowed.has(requested)) throw new ApiError(400, "return_not_allowed", "This server doesn't send sign-ins back to that address");
  return requested;
}
