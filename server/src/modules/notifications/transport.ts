import https from "node:https";
import type { LookupFunction } from "node:net";

/** Where one wake-up goes. */
export interface WakeTarget {
  url: URL;
  /** Resolved once and checked to be public before the request, then pinned: DNS can't move the request elsewhere. */
  address: string;
  family: 4 | 6;
}

export type WakeError = "timeout" | "tls" | "refused" | "network" | "dns" | "blocked_address";

/** The push server's HTTP status, or why no status came back. */
export type WakeResult = { status: number } | { error: WakeError };

export interface PostOptions {
  timeoutMs: number;
  /** RFC 8030 TTL: how long the push server may hold the wake-up for an unreachable phone. */
  ttlSeconds: number;
  /** Tests only: a CA to trust besides the system's. */
  ca?: string;
}

/**
 * Sends one wake-up: an HTTPS POST with an empty body to the pinned address. Redirects are never followed (a 3xx is
 * just a failed status), the whole exchange has a deadline, and the response body is never read. Never rejects.
 */
export function postWake(target: WakeTarget, opts: PostOptions): Promise<WakeResult> {
  const pinned: LookupFunction = (_hostname, options, callback) => {
    if (options.all) (callback as (e: null, a: { address: string; family: number }[]) => void)(null, [{ address: target.address, family: target.family }]);
    else callback(null, target.address, target.family);
  };
  return new Promise((resolve) => {
    let settled = false;
    const done = (r: WakeResult) => {
      if (settled) return;
      settled = true;
      resolve(r);
    };
    const req = https.request(
      {
        host: target.url.hostname.replace(/^\[(.*)\]$/, "$1"),
        port: target.url.port ? Number(target.url.port) : 443,
        path: `${target.url.pathname}${target.url.search}`,
        method: "POST",
        headers: { "content-length": "0", ttl: String(opts.ttlSeconds), urgency: "high", "user-agent": "meldiq-push/1" },
        lookup: pinned,
        agent: false,
        ca: opts.ca,
        signal: AbortSignal.timeout(opts.timeoutMs),
      },
      (res) => {
        done({ status: res.statusCode ?? 0 });
        res.destroy();
      },
    );
    req.on("error", (e) => done({ error: classify(e) }));
    req.end();
  });
}

function classify(e: Error & { code?: string }): WakeError {
  const code = e.code ?? "";
  if (e.name === "AbortError" || e.name === "TimeoutError" || code === "ABORT_ERR" || code === "ETIMEDOUT") return "timeout";
  if (code.startsWith("ERR_TLS") || code.startsWith("ERR_SSL") || /CERT|SELF_SIGNED|UNABLE_TO_VERIFY/.test(code)) return "tls";
  if (code === "ECONNREFUSED") return "refused";
  return "network";
}
