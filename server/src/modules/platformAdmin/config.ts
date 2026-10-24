import { BlockList, isIP } from "node:net";

/**
 * The Platform Admin Console's settings (meldiq_central_admin/docs/ARCHITECTURE_PROPOSAL.md §3). The console's routes
 * exist only when ADMIN_CONSOLE_ORIGIN is set; without it the server is exactly what it was.
 */
export interface AdminConsoleConfig {
  /** The console's origin, e.g. https://admin.meldiq.app (http only for localhost). */
  origin: string;
  /** Its host (and port, for localhost): requests for admin routes must name it. */
  host: string;
  /** WebAuthn relying party of console passkeys: the console's host name. */
  rpId: string;
  idleMs: number;
  maxMs: number;
  /** How recent a passkey check must be for a sensitive action. */
  reauthMs: number;
  /** When set, admin routes answer only these networks (ADMIN_ALLOWED_NETWORKS, CIDR list). */
  allowedNetworks: BlockList | null;
}

const MINUTE_MS = 60_000;
const HOUR_MS = 60 * MINUTE_MS;
const HOST = /^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/;

const clamp = (v: number, lo: number, hi: number) => Math.min(Math.max(v, lo), hi);

function consoleOrigin(raw: string): URL {
  const bad = () => new Error("ADMIN_CONSOLE_ORIGIN must be an origin such as https://admin.example.com (http only for localhost)");
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw bad();
  }
  const secure = url.protocol === "https:" && HOST.test(url.hostname);
  const local = url.protocol === "http:" && url.hostname === "localhost";
  if ((!secure && !local) || url.origin !== raw.replace(/\/$/, "").toLowerCase()) throw bad();
  return url;
}

function networks(raw: string): BlockList | null {
  const entries = raw.split(",").map((s) => s.trim()).filter(Boolean);
  if (entries.length === 0) return null;
  const list = new BlockList();
  for (const e of entries) {
    const [address, bits] = e.split("/");
    const family = isIP(address);
    const prefix = bits === undefined ? (family === 6 ? 128 : 32) : Number(bits);
    if (!family || !Number.isInteger(prefix) || prefix < 0 || prefix > (family === 6 ? 128 : 32)) {
      throw new Error("ADMIN_ALLOWED_NETWORKS entries must be addresses or CIDR ranges such as 203.0.113.7 or 2001:db8::/48");
    }
    list.addSubnet(address, prefix, family === 6 ? "ipv6" : "ipv4");
  }
  return list;
}

export function adminConsoleConfig(env: NodeJS.ProcessEnv, int: (name: string, fallback: number) => number): AdminConsoleConfig | null {
  const raw = (env.ADMIN_CONSOLE_ORIGIN ?? "").trim();
  if (!raw) return null;
  const url = consoleOrigin(raw);
  return {
    origin: url.origin,
    host: url.host,
    rpId: url.hostname,
    idleMs: clamp(int("ADMIN_SESSION_IDLE_MINUTES", 15), 5, 60) * MINUTE_MS,
    maxMs: clamp(int("ADMIN_SESSION_MAX_HOURS", 8), 1, 12) * HOUR_MS,
    reauthMs: clamp(int("ADMIN_REAUTH_MINUTES", 5), 1, 15) * MINUTE_MS,
    allowedNetworks: networks(env.ADMIN_ALLOWED_NETWORKS ?? ""),
  };
}

/** Whether [ip] may reach the console under [cfg] (always, without an allowlist). */
export function networkAllowed(cfg: AdminConsoleConfig, ip: string): boolean {
  if (!cfg.allowedNetworks) return true;
  const mapped = /^::ffff:(\d+\.\d+\.\d+\.\d+)$/i.exec(ip)?.[1] ?? ip;
  const family = isIP(mapped);
  return family !== 0 && cfg.allowedNetworks.check(mapped, family === 6 ? "ipv6" : "ipv4");
}
