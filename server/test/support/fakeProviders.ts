import crypto from "node:crypto";
import type { FetchLike } from "../../src/integrations/http.js";
import type { GriipClient } from "./griipClient.js";
import type { Harness } from "./harness.js";
import { browse, finishCodeOf, finishFlow, PUBLIC } from "./integrations.js";

/**
 * Mock providers for the connector tests (docs/INTEGRATIONS.md › Tests). Requests the server makes to provider hosts are
 * answered here and recorded, so tests check exactly what Meldiq sends (scopes, PKCE, headers) and how it handles each
 * answer. These prove Meldiq's side of each API contract as documented; they are not tests against the real providers.
 */
export interface Recorded {
  method: string;
  url: URL;
  headers: Headers;
  body: string;
}

type Handler = (req: Recorded) => unknown | Response | Promise<unknown | Response>;

export class FakeProviders {
  readonly calls: Recorded[] = [];
  private readonly handlers: { method: string; prefix: string; handler: Handler }[] = [];
  /** Authorisation codes issued by the fake consent, with the PKCE challenge they're bound to. */
  private readonly codes = new Map<string, { challenge: string | null; scope: string | null }>();

  on(method: string, prefix: string, handler: Handler): this {
    // Newest first, so a test can override a default answer.
    this.handlers.unshift({ method, prefix, handler });
    return this;
  }

  reset(prefix: string): void {
    for (let i = this.handlers.length - 1; i >= 0; i--) if (this.handlers[i].prefix.startsWith(prefix)) this.handlers.splice(i, 1);
  }

  issueCode(challenge: string | null, scope: string | null = null): string {
    const code = crypto.randomBytes(12).toString("hex");
    this.codes.set(code, { challenge, scope });
    return code;
  }

  /** Checks a token request's code and verifier like a provider would (null when the code is unknown or the PKCE check fails). */
  redeem(code: string | null, verifier: string | null): { scope: string | null } | null {
    if (!code) return null;
    const c = this.codes.get(code);
    this.codes.delete(code);
    if (!c) return null;
    if (c.challenge && crypto.createHash("sha256").update(verifier ?? "").digest("base64url") !== c.challenge) return null;
    return { scope: c.scope };
  }

  last(prefix: string): Recorded | undefined {
    return [...this.calls].reverse().find((c) => c.url.toString().startsWith(prefix));
  }

  fetch: FetchLike = async (url, init) => {
    const req: Recorded = { method: init?.method ?? "GET", url: new URL(url), headers: new Headers(init?.headers), body: typeof init?.body === "string" ? init.body : "" };
    this.calls.push(req);
    const h = this.handlers.find((x) => x.method === req.method && url.startsWith(x.prefix));
    if (!h) return new Response(JSON.stringify({ error: "not_mocked", url }), { status: 404 });
    const out = await h.handler(req);
    if (out instanceof Response) return out;
    return new Response(JSON.stringify(out), { status: 200, headers: { "content-type": "application/json" } });
  };
}

export const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });

/** Fake client credentials for every provider: test values only, never real ones. */
export function providerEnv(): Record<string, string> {
  return {
    GOOGLE_OAUTH_CLIENT_ID: "test-google-client",
    GOOGLE_OAUTH_CLIENT_SECRET: "test-google-secret",
    MICROSOFT_OAUTH_CLIENT_ID: "test-ms-client",
    MICROSOFT_OAUTH_CLIENT_SECRET: "test-ms-secret",
    GITHUB_APP_ID: "4242",
    GITHUB_APP_SLUG: "orbit-test-app",
    GITHUB_APP_CLIENT_ID: "Iv1.testclient",
    GITHUB_APP_CLIENT_SECRET: "test-github-secret",
    GITHUB_APP_WEBHOOK_SECRET: "test-github-webhook-secret",
    ATLASSIAN_OAUTH_CLIENT_ID: "test-atlassian-client",
    ATLASSIAN_OAUTH_CLIENT_SECRET: "test-atlassian-secret",
    NOTION_OAUTH_CLIENT_ID: "test-notion-client",
    NOTION_OAUTH_CLIENT_SECRET: "test-notion-secret",
    LINEAR_OAUTH_CLIENT_ID: "test-linear-client",
    LINEAR_OAUTH_CLIENT_SECRET: "test-linear-secret",
    FIGMA_OAUTH_CLIENT_ID: "test-figma-client",
    FIGMA_OAUTH_CLIENT_SECRET: "test-figma-secret",
    GITLAB_OAUTH_CLIENT_ID: "test-gitlab-client",
    GITLAB_OAUTH_CLIENT_SECRET: "test-gitlab-secret",
    ASANA_OAUTH_CLIENT_ID: "test-asana-client",
    ASANA_OAUTH_CLIENT_SECRET: "test-asana-secret",
    ZOOM_OAUTH_CLIENT_ID: "test-zoom-client",
    ZOOM_OAUTH_CLIENT_SECRET: "test-zoom-secret",
  };
}

export interface ProviderConnect {
  authorize: URL;
  status: string;
  html: string;
  /** What the finished flow tells the app besides its status (a narrower grant than requested). */
  note: string | null;
}

/**
 * Runs a real provider's sign-in against the fake: begin → (the provider's authorize URL, checked by the test) → the
 * fake consent issues a code bound to the PKCE challenge → Meldiq's callback with the browser cookie.
 */
export async function providerConnect(h: Harness, fake: FakeProviders, c: GriipClient, orgId: string, connectorId: string, opts: { grantScope?: string | null } = {}): Promise<ProviderConnect> {
  const start = await c.req("POST", `/v1/orgs/${orgId}/integrations/${connectorId}/connect`);
  const begin = await browse(h, start.beginUrl);
  if (begin.status !== 302 || !begin.location) throw new Error(`begin failed ${begin.status}`);
  const authorize = new URL(begin.location);
  const code = fake.issueCode(authorize.searchParams.get("code_challenge"), opts.grantScope === undefined ? authorize.searchParams.get("scope") : opts.grantScope);
  const cb = await browse(h, `${PUBLIC}/v1/integrations/oauth/callback?${new URLSearchParams({ state: authorize.searchParams.get("state")!, code })}`, begin.cookie);
  const status = await finishFlow(c, start.flowId, finishCodeOf(cb.html));
  const flow = await c.req("GET", `/v1/integrations/flows/${start.flowId}`);
  return { authorize, status, html: cb.html, note: flow.note ?? null };
}

/** A token endpoint answer for a redeemed code (or invalid_grant). */
export function tokenAnswer(fake: FakeProviders, req: Recorded, format: "form" | "json", extra: Record<string, unknown> = {}): Response {
  const body = format === "form" ? Object.fromEntries(new URLSearchParams(req.body)) : (JSON.parse(req.body || "{}") as Record<string, string>);
  if (body.grant_type === "refresh_token") {
    return json(200, { access_token: `at-${crypto.randomUUID()}`, refresh_token: `rt-${crypto.randomUUID()}`, expires_in: 3600, ...extra });
  }
  const ok = fake.redeem(body.code ?? null, body.code_verifier ?? null);
  if (!ok) return json(400, { error: "invalid_grant" });
  return json(200, { access_token: `at-${crypto.randomUUID()}`, refresh_token: `rt-${crypto.randomUUID()}`, expires_in: 3600, ...(ok.scope !== null ? { scope: ok.scope } : {}), ...extra });
}
