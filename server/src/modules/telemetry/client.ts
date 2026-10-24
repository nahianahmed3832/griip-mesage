/**
 * Which kind of client made a request (docs/ANALYTICS_DEFINITIONS.md › Clients). Derived from what the client already
 * sends: an optional `X-Meldiq-Client: <platform>/<version>` header (for app releases that choose to send it), else the
 * User-Agent. The raw header is never stored, only the derived kind; no fingerprinting.
 *
 * Today: Android sends OkHttp's default User-Agent (platform known, app version not), browsers send their own (web,
 * browser name and major version), iOS sends CFNetwork's default with the app's build.
 */
export const PLATFORMS = ["android", "ios", "web", "other", "unknown"] as const;
export type Platform = (typeof PLATFORMS)[number];

export interface ClientInfo {
  platform: Platform;
  /** "Chrome 141", "OkHttp 4.12", "Meldiq iOS 57"… or "" when nothing is recognised. */
  client: string;
  /** The app's own version, only when the client says it; "" otherwise. */
  version: string;
  /** "Android", "iOS 18", "macOS", "Windows"… or "". */
  os: string;
}

const UNKNOWN: ClientInfo = { platform: "unknown", client: "", version: "", os: "" };
const CLIENT_HEADER = /^(android|ios|web)\/([0-9][0-9A-Za-z.+-]{0,30})$/;

const major = (v: string | undefined) => (v ?? "").split(".").slice(0, 2).join(".");

function browserOs(ua: string): string {
  if (/Android/.test(ua)) return "Android";
  if (/iPhone|iPad|iPod/.test(ua)) return "iOS";
  if (/Mac OS X|Macintosh/.test(ua)) return "macOS";
  if (/Windows/.test(ua)) return "Windows";
  if (/CrOS/.test(ua)) return "ChromeOS";
  if (/Linux/.test(ua)) return "Linux";
  return "";
}

function browser(ua: string): string {
  const pick = (re: RegExp, name: string) => {
    const m = ua.match(re);
    return m ? `${name} ${m[1]!.split(".")[0]}` : null;
  };
  return (
    pick(/Edg\/([\d.]+)/, "Edge") ??
    pick(/OPR\/([\d.]+)/, "Opera") ??
    pick(/Firefox\/([\d.]+)/, "Firefox") ??
    pick(/(?:Chrome|CriOS)\/([\d.]+)/, "Chrome") ??
    pick(/Version\/([\d.]+).*Safari/, "Safari") ??
    "Browser"
  );
}

/** Classifies a request's client from its headers (pure; unit-tested). */
export function classifyClient(userAgent: string | undefined, clientHeader?: string | undefined): ClientInfo {
  const ua = (userAgent ?? "").slice(0, 512);
  const declared = (clientHeader ?? "").trim().match(CLIENT_HEADER);
  if (declared) {
    const platform = declared[1] as Platform;
    const fallback = ua ? classifyClient(ua) : UNKNOWN;
    return { platform, client: fallback.client || `Meldiq ${platform}`, version: declared[2]!, os: fallback.os };
  }
  if (!ua) return UNKNOWN;
  const okhttp = ua.match(/^okhttp\/([\d.]+)/i);
  if (okhttp) return { platform: "android", client: `OkHttp ${major(okhttp[1])}`, version: "", os: "Android" };
  // iOS apps using URLSession: "<App>/<build> CFNetwork/<v> Darwin/<v>".
  const cfnetwork = ua.match(/^([A-Za-z][\w .-]{0,40})\/([\w.]{1,20}) CFNetwork\/[\d.]+ Darwin\//);
  if (cfnetwork) return { platform: "ios", client: `${cfnetwork[1]} iOS`, version: cfnetwork[2]!, os: "iOS" };
  if (/^Mozilla\//.test(ua)) return { platform: "web", client: browser(ua), version: "", os: browserOs(ua) };
  return { platform: "other", client: ua.split(/[\s/]/)[0]!.slice(0, 40), version: "", os: "" };
}
