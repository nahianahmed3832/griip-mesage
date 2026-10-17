import net from "node:net";

/** Longest endpoint URL a device may register (ntfy's are about 60 characters). */
export const MAX_ENDPOINT_LENGTH = 1024;

export type EndpointRefusal = "push_endpoint_invalid" | "push_host_not_allowed";

export const ENDPOINT_REFUSALS: Record<EndpointRefusal, string> = {
  push_endpoint_invalid: "The push endpoint must be an https:// address without a user name, password or #fragment",
  push_host_not_allowed: "This server only sends wake-ups to its own push server; set your distributor app to use it",
};

/**
 * Checks a device's endpoint before it is stored, and again before every request (the allow-list may have changed):
 * https, no credentials or fragment, and a host (with its port, when not 443) on [allowedHosts]. Returns the
 * normalized URL.
 */
export function checkEndpoint(raw: string, allowedHosts: readonly string[]): { url: URL } | { refusal: EndpointRefusal } {
  if (raw.length > MAX_ENDPOINT_LENGTH) return { refusal: "push_endpoint_invalid" };
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return { refusal: "push_endpoint_invalid" };
  }
  if (url.protocol !== "https:" || url.username || url.password || url.hash) {
    return { refusal: "push_endpoint_invalid" };
  }
  // WHATWG URLs lower-case the host and drop the default port, so "host" entries match port 443 only.
  if (!allowedHosts.includes(url.host)) return { refusal: "push_host_not_allowed" };
  return { url };
}

// Addresses a wake-up must never reach: this machine, private and shared networks, link-local (cloud metadata
// services live there), multicast and reserved ranges. A push host that resolves to any of them is refused.
const BLOCKED_V4: [string, number][] = [
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
];
// IPv6 must be global unicast (2000::/3), minus the special ranges inside it (Teredo and other protocol assignments,
// documentation, 6to4). Unique-local, link-local, loopback, multicast and NAT64 are all outside 2000::/3.
const BLOCKED_V6: [string, number][] = [
  ["2001::", 23],
  ["2001:db8::", 32],
  ["2002::", 16],
];

const v4Blocked = new net.BlockList();
for (const [a, p] of BLOCKED_V4) v4Blocked.addSubnet(a, p, "ipv4");
const v6Global = new net.BlockList();
v6Global.addSubnet("2000::", 3, "ipv6");
const v6Blocked = new net.BlockList();
for (const [a, p] of BLOCKED_V6) v6Blocked.addSubnet(a, p, "ipv6");

/** The IPv4 address inside an IPv4-mapped IPv6 address (::ffff:a.b.c.d or ::ffff:xxxx:xxxx), else null. */
function mappedV4(ip: string): string | null {
  const dotted = /^::ffff:(\d{1,3}(?:\.\d{1,3}){3})$/i.exec(ip);
  if (dotted) return dotted[1];
  const hex = /^::ffff:([0-9a-f]{1,4}):([0-9a-f]{1,4})$/i.exec(ip);
  if (!hex) return null;
  const hi = Number.parseInt(hex[1], 16);
  const lo = Number.parseInt(hex[2], 16);
  return `${hi >> 8}.${hi & 255}.${lo >> 8}.${lo & 255}`;
}

/** True only for a public unicast address; anything unparsable counts as not public. */
export function isPublicAddress(ip: string): boolean {
  try {
    const family = net.isIP(ip);
    if (family === 4) return !v4Blocked.check(ip, "ipv4");
    if (family !== 6) return false;
    const v4 = mappedV4(ip);
    if (v4) return net.isIP(v4) === 4 && !v4Blocked.check(v4, "ipv4");
    return v6Global.check(ip, "ipv6") && !v6Blocked.check(ip, "ipv6");
  } catch {
    return false;
  }
}
