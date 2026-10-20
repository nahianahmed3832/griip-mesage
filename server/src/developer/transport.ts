import dns from "node:dns/promises";
import https from "node:https";
import type { LookupFunction } from "node:net";
import net from "node:net";
import { isPublicAddress } from "../push/address.js";

/**
 * One webhook POST (docs/DEVELOPER_API.md): https only, to an address resolved once and required to be public, then
 * pinned (DNS can't move the request to an internal service), no redirects followed, a deadline on the whole exchange,
 * and the response body never read. Never rejects: it returns the status, or why there is none.
 */
export type WebhookResult = { status: number } | { error: "invalid_url" | "blocked_address" | "dns" | "timeout" | "tls" | "network" };

export type WebhookSender = (url: string, body: string, headers: Record<string, string>) => Promise<WebhookResult>;

export const WEBHOOK_TIMEOUT_MS = 10_000;

/** Why [url] can't be a webhook address, or null when it can (checked at save time; the address is checked again per send). */
export function webhookUrlProblem(url: string): string | null {
  let u: URL;
  try {
    u = new URL(url);
  } catch {
    return "That isn't a valid address";
  }
  if (u.protocol !== "https:") return "Webhook addresses must start with https://";
  if (u.username || u.password) return "Put credentials in the signing secret, not the address";
  if (url.length > 1000) return "That address is too long";
  const host = u.hostname.replace(/^\[(.*)\]$/, "$1");
  if (net.isIP(host) && !isPublicAddress(host)) return "Webhooks can't be sent to private or local addresses";
  if (/^(localhost|.*\.local|.*\.internal|.*\.localhost)$/i.test(host)) return "Webhooks can't be sent to private or local addresses";
  return null;
}

export const sendWebhook: WebhookSender = async (url, body, headers) => {
  if (webhookUrlProblem(url)) return { error: "invalid_url" };
  const u = new URL(url);
  const host = u.hostname.replace(/^\[(.*)\]$/, "$1");
  let address: string;
  let family: 4 | 6;
  try {
    const found = net.isIP(host) ? [{ address: host, family: net.isIP(host) }] : await dns.lookup(host, { all: true });
    if (!found.length || !found.every((a) => isPublicAddress(a.address))) return { error: "blocked_address" };
    address = found[0].address;
    family = found[0].family === 6 ? 6 : 4;
  } catch {
    return { error: "dns" };
  }
  const pinned: LookupFunction = (_h, options, callback) => {
    if (options.all) (callback as (e: null, a: { address: string; family: number }[]) => void)(null, [{ address, family }]);
    else callback(null, address, family);
  };
  return new Promise((resolve) => {
    let settled = false;
    const done = (r: WebhookResult) => {
      if (!settled) {
        settled = true;
        resolve(r);
      }
    };
    const req = https.request(
      {
        host, port: u.port ? Number(u.port) : 443, path: `${u.pathname}${u.search}`, method: "POST",
        headers: { ...headers, "content-type": "application/json", "content-length": String(Buffer.byteLength(body)), "user-agent": "meldiq-webhooks/1" },
        lookup: pinned, agent: false, signal: AbortSignal.timeout(WEBHOOK_TIMEOUT_MS),
      },
      (res) => {
        done({ status: res.statusCode ?? 0 });
        res.destroy();
      },
    );
    req.on("error", (e: NodeJS.ErrnoException) => {
      const code = e.code ?? e.name;
      done({ error: code === "ABORT_ERR" || code === "ETIMEDOUT" || e.name === "AbortError" || e.name === "TimeoutError" ? "timeout" : /CERT|TLS|SSL/.test(String(code)) ? "tls" : "network" });
    });
    req.end(body);
  });
};
