import crypto from "node:crypto";
import type { FetchLike } from "../../src/integrations/http.js";
import type { GriipClient } from "./griipClient.js";
import { type Harness, startServer } from "./harness.js";

/**
 * Test support for the integrations framework (docs/INTEGRATIONS.md › Tests). Servers get the sandbox provider, a
 * throwaway token key and a public address that tests map back to the harness, plus helpers that walk the browser side
 * of a sign-in (begin → consent → callback) with real redirects and cookies, the way a phone's browser would.
 * No real provider, credential or network is involved.
 */
export const PUBLIC = "http://meldiq.test";

export function integrationEnv(extra: Record<string, string> = {}): Record<string, string> {
  return {
    INTEGRATIONS_SANDBOX: "on",
    INTEGRATIONS_PUBLIC_URL: PUBLIC,
    INTEGRATIONS_TOKEN_KEY: crypto.randomBytes(32).toString("base64"),
    INTEGRATIONS_SANDBOX_WEBHOOK_SECRET: "test-sandbox-webhook-secret",
    // Test people connect many apps within a minute; the limit itself is unit-sized (10) by default.
    INTEGRATIONS_CONNECTS_PER_MINUTE: "200",
    ...extra,
  };
}

export async function startIntegrationServer(extra: Record<string, string> = {}, fetch?: FetchLike): Promise<Harness> {
  return startServer(integrationEnv(extra), { integrationsFetch: fetch });
}

/** Platform administrators are configuration; tests add one after the user exists. */
export function makePlatformAdmin(h: Harness, c: GriipClient): void {
  (h.ctx.config.integrations.platformAdmins as Set<string>).add(c.userId);
}

/** The harness address for a URL the server built with its public address. */
export const local = (h: Harness, url: string) => url.replace(PUBLIC, h.baseUrl);

export interface BrowserResult {
  status: number;
  html: string;
  location: string | null;
  cookie: string | null;
}

/** One browser request without following redirects (cookies are the test's to carry). */
export async function browse(h: Harness, url: string, cookie?: string | null): Promise<BrowserResult> {
  const res = await fetch(local(h, url), { redirect: "manual", headers: cookie ? { cookie } : {} });
  const setCookie = res.headers.get("set-cookie");
  return { status: res.status, html: await res.text(), location: res.headers.get("location"), cookie: setCookie ? setCookie.split(";")[0] : null };
}

export interface ConnectOutcome {
  flowId: string;
  callback: BrowserResult;
  /** The one-time finish code the callback page handed to the app (meldiq://integrations?…&finish=…), if any. */
  finishCode: string | null;
  status: string;
}

/** The finish code in a callback page's "Return to Meldiq" link. */
export const finishCodeOf = (html: string) => /[?&;]finish=([A-Za-z0-9_-]+)/.exec(html)?.[1] ?? null;

/** What the app does when the browser hands it back: finish the sign-in as its own signed-in person. */
export async function finishFlow(c: GriipClient, flowId: string, code: string | null): Promise<string> {
  if (code) return (await c.req("POST", `/v1/integrations/flows/${flowId}/finish`, { code })).status as string;
  return (await c.req("GET", `/v1/integrations/flows/${flowId}`)).status as string;
}

/**
 * Connects [c]'s sandbox account in [orgId] through the whole browser flow. [person] picks the sandbox identity;
 * "deny" refuses at the consent page; [returnTo] is the web origin a browser client names. Options let a test break one
 * step (drop the cookie) or leave the finishing to it.
 */
export async function sandboxConnect(
  h: Harness,
  c: GriipClient,
  orgId: string,
  opts: { person?: "sam" | "alex" | "deny"; dropCookie?: boolean; connector?: string; skipFinish?: boolean; returnTo?: string } = {},
): Promise<ConnectOutcome> {
  const body = opts.returnTo === undefined ? undefined : { return: opts.returnTo };
  const start = await c.req("POST", `/v1/orgs/${orgId}/integrations/${opts.connector ?? "meldiq-sandbox"}/connect`, body);
  const begin = await browse(h, start.beginUrl);
  if (begin.status !== 302 || !begin.location) throw new Error(`begin failed: ${begin.status} ${begin.html.slice(0, 200)}`);
  const consent = await browse(h, begin.location);
  let next: string;
  if (opts.person === "deny") {
    next = consent.html.match(/href="([^"]+error=access_denied[^"]*)"/)![1].replace(/&amp;/g, "&");
  } else {
    const approve = consent.html.match(new RegExp(`href="(/v1/integrations/sandbox/approve\\?[^"]*person=${opts.person ?? "sam"})"`))![1].replace(/&amp;/g, "&");
    const approved = await browse(h, `${PUBLIC}${approve}`);
    next = approved.location!;
  }
  const callback = await browse(h, next, opts.dropCookie ? null : begin.cookie);
  const finishCode = finishCodeOf(callback.html);
  const status = await finishFlow(c, start.flowId, opts.skipFinish ? null : finishCode);
  return { flowId: start.flowId, callback, finishCode, status };
}

/** A workspace with an owner, an admin, two members and a guest, the sandbox installed. */
export async function sandboxWorkspace(h: Harness, name = "Apps Lab") {
  const [owner, admin, member, member2, guest, outsider] = await Promise.all(["owner", "admin", "member", "membertwo", "guest", "outsider"].map((n) => h.client(n)));
  const orgId = (await owner.req("POST", "/v1/orgs", { name })).org.id as string;
  const invite = async (role: string) => (await owner.req("POST", `/v1/orgs/${orgId}/invites`, { role })).invite.code as string;
  await admin.req("POST", "/v1/invites/accept", { code: await invite("ADMIN") });
  await member.req("POST", "/v1/invites/accept", { code: await invite("MEMBER") });
  await member2.req("POST", "/v1/invites/accept", { code: await invite("MEMBER") });
  await guest.req("POST", "/v1/invites/accept", { code: await invite("GUEST") });
  return { owner, admin, member, member2, guest, outsider, orgId };
}
