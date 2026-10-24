import dns from "node:dns";
import https from "node:https";
import net from "node:net";
import { ApiError } from "../../shared/errors.js";

/**
 * The only way an agent reaches the web (tool web.fetch, off by default): one public https page. Every address the
 * name resolves to must be public; the connection is made to the address that was checked (no second lookup, so a name
 * can't be re-pointed at a private address in between); redirects are followed by hand, at most 3, each checked the
 * same way; 2 MB and 15 seconds at most; text types only.
 */
export const FETCH_LIMITS = { bytes: 2 * 1024 * 1024, timeoutMs: 15_000, redirects: 3 } as const;
const TEXT_TYPES = ["text/html", "text/plain", "application/xhtml+xml", "application/json", "text/xml", "application/xml"];

const blocked = new net.BlockList();
for (const [a, p] of [
  ["0.0.0.0", 8],
  ["10.0.0.0", 8],
  ["100.64.0.0", 10],
  ["127.0.0.0", 8],
  ["169.254.0.0", 16],
  ["172.16.0.0", 12],
  ["192.0.0.0", 24],
  ["192.0.2.0", 24],
  ["192.88.99.0", 24],
  ["192.168.0.0", 16],
  ["198.18.0.0", 15],
  ["198.51.100.0", 24],
  ["203.0.113.0", 24],
  ["224.0.0.0", 4],
  ["240.0.0.0", 4],
] as const)
  blocked.addSubnet(a, p, "ipv4");
for (const [a, p] of [
  ["::", 128],
  ["::1", 128],
  ["64:ff9b::", 96],
  ["64:ff9b:1::", 48],
  ["2002::", 16],
  ["fec0::", 10],
  ["100::", 64],
  ["2001::", 32],
  ["2001:db8::", 32],
  ["fc00::", 7],
  ["fe80::", 10],
  ["ff00::", 8],
] as const)
  blocked.addSubnet(a, p, "ipv6");

/** The IPv4 address inside an IPv4-mapped or IPv4-compatible IPv6 address (::ffff:1.2.3.4, ::ffff:102:304, ::1.2.3.4), or null. */
function mappedV4(ip: string): string | null {
  const dotted = /^::(?:ffff:)?(\d{1,3}(?:\.\d{1,3}){3})$/i.exec(ip)?.[1];
  if (dotted) return dotted;
  const hex = /^::(?:ffff:)?([0-9a-f]{1,4}):([0-9a-f]{1,4})$/i.exec(ip);
  if (!hex) return null;
  const [hi, lo] = [parseInt(hex[1]!, 16), parseInt(hex[2]!, 16)];
  return `${hi >> 8}.${hi & 255}.${lo >> 8}.${lo & 255}`;
}

export function isPublicAddress(ip: string): boolean {
  const v = net.isIP(ip);
  if (v === 0) return false;
  // Checked as the IPv4 address it is: a mapped-address rule in BlockList would match every IPv4 address.
  const v4 = v === 6 ? mappedV4(ip) : null;
  if (v4) return isPublicAddress(v4);
  return !blocked.check(ip, v === 4 ? "ipv4" : "ipv6");
}

export type Resolver = (host: string) => Promise<{ address: string; family: number }[]>;
const systemResolver: Resolver = (host) => dns.promises.lookup(host, { all: true, verbatim: true });

/** Checks a URL and resolves its host; throws unsafe_url for anything but a public https address. */
export async function checkUrl(raw: string, resolve: Resolver = systemResolver): Promise<{ url: URL; address: string; family: number }> {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new ApiError(400, "unsafe_url", "That isn't a web address.");
  }
  if (url.protocol !== "https:") throw new ApiError(400, "unsafe_url", "Only https addresses can be fetched.");
  if (url.username || url.password) throw new ApiError(400, "unsafe_url", "Addresses with a user name or password can't be fetched.");
  if (url.port && url.port !== "443") throw new ApiError(400, "unsafe_url", "Only the standard https port can be used.");
  const host = url.hostname.replace(/^\[|\]$/g, "");
  if (!host || host === "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) throw new ApiError(400, "unsafe_url", "That host is private.");
  const addrs = net.isIP(host) ? [{ address: host, family: net.isIP(host) }] : await resolve(host).catch(() => []);
  if (addrs.length === 0) throw new ApiError(400, "unsafe_url", "That host doesn't resolve.");
  if (!addrs.every((a) => isPublicAddress(a.address))) throw new ApiError(400, "unsafe_url", "That host points to a private or reserved address.");
  return { url, address: addrs[0]!.address, family: addrs[0]!.family };
}

export async function fetchPage(raw: string, opts: { signal?: AbortSignal; resolve?: Resolver } = {}): Promise<{ url: string; status: number; contentType: string; text: string; truncated: boolean }> {
  let current = raw;
  for (let hop = 0; hop <= FETCH_LIMITS.redirects; hop++) {
    const { url, address, family } = await checkUrl(current, opts.resolve);
    const r = await new Promise<{ status: number; location: string | null; contentType: string; body: Buffer; truncated: boolean }>((resolve, reject) => {
      const req = https.request(
        {
          host: url.hostname,
          servername: net.isIP(url.hostname) ? undefined : url.hostname,
          path: `${url.pathname}${url.search}`,
          method: "GET",
          headers: { "user-agent": "MeldiqAgent/1.0 (+https://meldiq.app)", accept: TEXT_TYPES.join(", ") },
          // Connect to the address that was checked, never to a fresh lookup.
          lookup: (_h, o, cb) => (o.all ? cb(null, [{ address, family }]) : cb(null, address, family)),
          timeout: FETCH_LIMITS.timeoutMs,
          signal: opts.signal,
        },
        (res) => {
          const status = res.statusCode ?? 0;
          const contentType = String(res.headers["content-type"] ?? "").split(";")[0]!.trim().toLowerCase();
          if (status >= 300 && status < 400) {
            res.resume();
            return resolve({ status, location: res.headers.location ?? null, contentType, body: Buffer.alloc(0), truncated: false });
          }
          const parts: Buffer[] = [];
          let n = 0;
          let truncated = false;
          res.on("data", (c: Buffer) => {
            if (n >= FETCH_LIMITS.bytes) {
              truncated = true;
              res.destroy();
              return;
            }
            parts.push(c.subarray(0, FETCH_LIMITS.bytes - n));
            n += c.length;
          });
          res.on("close", () => resolve({ status, location: null, contentType, body: Buffer.concat(parts), truncated: truncated || n > FETCH_LIMITS.bytes }));
          res.on("error", reject);
        },
      );
      // A total deadline as well as the idle one: a server dripping bytes can't hold the fetch open.
      const deadline = setTimeout(() => req.destroy(new Error("timeout")), FETCH_LIMITS.timeoutMs);
      req.on("close", () => clearTimeout(deadline));
      req.on("timeout", () => req.destroy(new Error("timeout")));
      req.on("error", reject);
      req.end();
    }).catch((e: unknown) => {
      if (e instanceof ApiError) throw e;
      throw new ApiError(502, "provider_error", "The page couldn't be fetched (network error or timeout).");
    });
    if (r.status >= 300 && r.status < 400) {
      if (!r.location) throw new ApiError(502, "provider_error", "The page redirected nowhere.");
      current = new URL(r.location, url).toString();
      continue;
    }
    if (!TEXT_TYPES.includes(r.contentType)) throw new ApiError(400, "unsafe_url", `That page isn't text (${r.contentType || "no type"}).`);
    return { url: url.toString(), status: r.status, contentType: r.contentType, text: r.body.toString("utf8"), truncated: r.truncated };
  }
  throw new ApiError(400, "unsafe_url", "Too many redirects.");
}
