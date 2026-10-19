import crypto from "node:crypto";
import { connectorDef } from "../catalog.js";
import type { Connector, ConnectorDeps } from "../connector.js";
import { ProviderError } from "../http.js";
import { hmacSha256Hex, pkceChallenge, randomSecret, safeEqual } from "../tokens.js";
import type { NormalizedEvent, Resource } from "../types.js";
import { oauth2Provider } from "./oauth2.js";

/**
 * Meldiq Sandbox: a simulated provider for development and tests only (INTEGRATIONS_SANDBOX=on, refused in production;
 * docs/INTEGRATIONS.md › Sandbox). It runs the same framework paths as a real provider: a consent page, an authorisation
 * code with PKCE, a token endpoint, refresh, an API that checks bearer tokens, a signed webhook. Every file and event is
 * made up; nothing leaves the server. Its "provider" lives at a reserved host that is never resolved: the framework's
 * fetch hands those requests to [SandboxProvider.handle].
 */
export const SANDBOX_HOST = "https://sandbox.meldiq.invalid";
const ACCESS_TTL_MS = 3600_000;
const REFRESH_TTL_MS = 30 * 86_400_000;
const CODE_TTL_MS = 120_000;
const WEBHOOK_SKEW_MS = 5 * 60_000;

export const SANDBOX_PEOPLE: Readonly<Record<string, { id: string; name: string; email: string }>> = {
  sam: { id: "sbx-user-sam", name: "Sam Sample", email: "sam@sandbox.invalid" },
  alex: { id: "sbx-user-alex", name: "Alex Example", email: "alex@sandbox.invalid" },
};

interface SampleFile {
  id: string;
  parent: string | null;
  name: string;
  kind: Resource["kind"];
  owner: string;
  size: number | null;
}
const FILES: readonly SampleFile[] = [
  { id: "sbxf-projects", parent: null, name: "Projects", kind: "folder", owner: "Sam Sample", size: null },
  { id: "sbxf-finance", parent: null, name: "Finance", kind: "folder", owner: "Alex Example", size: null },
  { id: "sbxf-plan", parent: "sbxf-projects", name: "Q4 launch plan", kind: "document", owner: "Sam Sample", size: 48_213 },
  { id: "sbxf-checklist", parent: "sbxf-projects", name: "Launch checklist", kind: "spreadsheet", owner: "Sam Sample", size: 12_004 },
  { id: "sbxf-budget", parent: "sbxf-finance", name: "Budget 2026.xlsx", kind: "workbook", owner: "Alex Example", size: 91_550 },
  { id: "sbxf-brand", parent: null, name: "Brand guide.pdf", kind: "file", owner: "Alex Example", size: 2_310_442 },
];

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

export class SandboxProvider {
  private readonly codes = new Map<string, { challenge: string | null; redirectUri: string; scopes: string; person: string; expiresAt: number }>();
  /** Refresh tokens revoked by a disconnect (the provider forgets the grant). */
  private readonly revoked = new Set<string>();

  constructor(
    private readonly secret: string,
    private readonly publicUrl: string,
    private readonly now: () => number,
  ) {}

  /** The consent page's "Allow": a single-use code for [redirectUri], bound to the PKCE challenge. */
  issueCode(p: { redirectUri: string; challenge: string | null; scopes: string; person: string }): string {
    const code = randomSecret(24);
    this.codes.set(code, { ...p, expiresAt: this.now() + CODE_TTL_MS });
    return code;
  }

  fileUrl(id: string): string {
    // A reserved https host that never resolves: link cards need https, and nothing real may be implied.
    return `${SANDBOX_HOST}/files/${id}`;
  }

  private token(kind: "a" | "r", person: string, scopes: string, ttl: number): string {
    const body = `${kind}.${person}.${this.now() + ttl}.${Buffer.from(scopes).toString("base64url")}.${randomSecret(8)}`;
    return `sbx.${body}.${hmacSha256Hex(this.secret, body)}`;
  }

  private check(token: string, kind: "a" | "r"): { person: string; scopes: string[] } | null {
    const parts = token.split(".");
    if (parts.length !== 7 || parts[0] !== "sbx" || parts[1] !== kind) return null;
    const body = parts.slice(1, 6).join(".");
    if (!safeEqual(parts[6], hmacSha256Hex(this.secret, body))) return null;
    if (Number(parts[3]) <= this.now() || this.revoked.has(token)) return null;
    return { person: parts[2], scopes: Buffer.from(parts[4], "base64url").toString("utf8").split(" ").filter(Boolean) };
  }

  /** The simulated provider's HTTP endpoints. */
  async handle(url: string, init?: RequestInit): Promise<Response> {
    const u = new URL(url);
    if (u.pathname === "/token") {
      const form = new URLSearchParams(String(init?.body ?? ""));
      if (form.get("grant_type") === "authorization_code") {
        const c = this.codes.get(form.get("code") ?? "");
        this.codes.delete(form.get("code") ?? "");
        if (!c || c.expiresAt <= this.now() || c.redirectUri !== form.get("redirect_uri")) return json(400, { error: "invalid_grant" });
        if (c.challenge && pkceChallenge(form.get("code_verifier") ?? "") !== c.challenge) return json(400, { error: "invalid_grant" });
        return json(200, this.tokens(c.person, c.scopes));
      }
      if (form.get("grant_type") === "refresh_token") {
        const old = form.get("refresh_token") ?? "";
        const r = this.check(old, "r");
        if (!r) return json(400, { error: "invalid_grant" });
        this.revoked.add(old); // rotates, like Atlassian and Notion
        return json(200, this.tokens(r.person, r.scopes.join(" ")));
      }
      return json(400, { error: "unsupported_grant_type" });
    }
    if (u.pathname === "/revoke") {
      const t = new URLSearchParams(String(init?.body ?? "")).get("token") ?? "";
      this.revoked.add(t);
      return json(200, {});
    }
    const auth = new Headers(init?.headers).get("authorization") ?? "";
    const who = this.check(auth.replace(/^Bearer /, ""), "a");
    if (!who) return json(401, { error: "invalid_token" });
    const person = Object.values(SANDBOX_PEOPLE).find((p) => p.id === who.person);
    if (!person) return json(401, { error: "invalid_token" });
    if (u.pathname === "/me") return json(200, { id: person.id, name: person.name, email: person.email });
    if (!who.scopes.includes("sandbox.files.read") && u.pathname.startsWith("/files")) return json(403, { error: "insufficient_scope" });
    if (u.pathname === "/files") {
      const q = (u.searchParams.get("q") ?? "").toLowerCase();
      const parent = u.searchParams.get("parent");
      const list = FILES.filter((f) => (q ? f.name.toLowerCase().includes(q) : parent !== null ? f.parent === (parent || null) : true));
      return json(200, { files: list.map((f) => this.view(f)) });
    }
    const m = /^\/files\/([a-z0-9-]{3,40})$/.exec(u.pathname);
    if (m) {
      const f = FILES.find((x) => x.id === m[1]);
      return f ? json(200, this.view(f)) : json(404, { error: "not_found" });
    }
    if (u.pathname === "/installations") return json(200, { installations: [{ id: "sbx-org-1", name: "Sample organisation" }] });
    return json(404, { error: "not_found" });
  }

  private tokens(person: string, scopes: string) {
    return {
      access_token: this.token("a", person, scopes, ACCESS_TTL_MS),
      refresh_token: this.token("r", person, scopes, REFRESH_TTL_MS),
      expires_in: ACCESS_TTL_MS / 1000,
      scope: scopes,
      token_type: "bearer",
    };
  }

  private view(f: SampleFile) {
    return { ...f, url: this.fileUrl(f.id), modifiedAt: Date.UTC(2026, 9, 1) };
  }

  /** Signs a test delivery the way the sandbox "provider" would (for an admin's "Send a test event"). */
  signDelivery(payload: object): { raw: Buffer; headers: Record<string, string> } {
    const raw = Buffer.from(JSON.stringify(payload));
    const t = String(this.now());
    return {
      raw,
      headers: {
        "x-sandbox-delivery": crypto.randomUUID(),
        "x-sandbox-event": "sample",
        "x-sandbox-timestamp": t,
        "x-sandbox-signature": `sha256=${hmacSha256Hex(this.secret, Buffer.concat([Buffer.from(`${t}.`), raw]))}`,
      },
    };
  }
}

interface SandboxFileView {
  id?: string;
  name?: string;
  kind?: Resource["kind"];
  owner?: string;
  size?: number | null;
  url?: string;
  modifiedAt?: number;
}

const resourceOf = (f: SandboxFileView): Resource => {
  if (!f.id) throw new ProviderError("bad_response", "The sandbox sent a file without an id");
  return { id: f.id, kind: f.kind ?? "file", title: f.name ?? "Untitled", url: f.url ?? null, owner: f.owner ?? null, size: f.size ?? null, modifiedAt: f.modifiedAt ?? null, browsable: f.kind === "folder" };
};

export function sandboxConnector(deps: ConnectorDeps, provider: SandboxProvider | null): Connector {
  const sb = deps.config.sandbox;
  const ready = !!(sb && provider && deps.config.publicUrl);
  return {
    def: connectorDef("meldiq-sandbox")!,
    oauth: ready
      ? oauth2Provider(
          {
            label: "Meldiq Sandbox",
            authorizeEndpoint: `${deps.config.publicUrl}/v1/integrations/sandbox/authorize`,
            tokenEndpoint: `${SANDBOX_HOST}/token`,
            clientId: "meldiq-sandbox",
            clientSecret: "not-a-secret",
            pkce: true,
            clientAuth: "body",
            tokenFormat: "form",
            scopeSeparator: " ",
            revoke: async (t) => {
              await deps.fetch(`${SANDBOX_HOST}/revoke`, { method: "POST", body: new URLSearchParams({ token: t.refreshToken ?? t.accessToken }).toString() });
            },
            now: deps.now,
          },
          deps.fetch,
        )
      : null,
    async identify(ctx) {
      const me = await ctx.api<{ id?: string; email?: string }>(`${SANDBOX_HOST}/me`);
      if (!me?.id) throw new ProviderError("bad_response", "The sandbox didn't say whose account this is");
      return { externalId: me.id, label: me.email ?? null };
    },
    search: {
      capability: "files.search",
      async run(ctx, query) {
        const r = await ctx.api<{ files?: SandboxFileView[] }>(`${SANDBOX_HOST}/files`, { query: { q: query.trim() } });
        return { items: (r?.files ?? []).map(resourceOf), cursor: null };
      },
    },
    browse: {
      capability: "files.browse",
      async run(ctx, containerId) {
        const r = await ctx.api<{ files?: SandboxFileView[] }>(`${SANDBOX_HOST}/files`, { query: { parent: containerId ?? "" } });
        return { items: (r?.files ?? []).map(resourceOf), cursor: null };
      },
    },
    get: {
      capability: "files.link",
      async run(ctx, id) {
        if (!/^[a-z0-9-]{3,40}$/.test(id)) throw new ProviderError("not_found", "That isn't a sandbox file");
        return resourceOf(await ctx.api<SandboxFileView>(`${SANDBOX_HOST}/files/${id}`));
      },
    },
    unfurlCapability: "files.unfurl",
    matchUrl(url) {
      if (!url.toString().startsWith(`${SANDBOX_HOST}/files/`)) return null;
      const id = url.pathname.split("/").pop() ?? "";
      return /^[a-z0-9-]{3,40}$/.test(id) ? id : null;
    },
    webhook:
      ready && sb
        ? {
            eventTypes: [
              { key: "sample.created", label: "Sample item created" },
              { key: "sample.updated", label: "Sample item updated" },
            ],
            verify(raw, headers) {
              const sig = headers["x-sandbox-signature"] ?? "";
              const t = headers["x-sandbox-timestamp"] ?? "";
              const delivery = headers["x-sandbox-delivery"] ?? "";
              if (!/^sha256=[0-9a-f]{64}$/.test(sig) || !/^\d{10,16}$/.test(t) || !/^[0-9a-f-]{36}$/.test(delivery)) return null;
              if (Math.abs(deps.now() - Number(t)) > WEBHOOK_SKEW_MS) return null; // replayed or stale
              const expected = `sha256=${hmacSha256Hex(sb.webhookSecret, Buffer.concat([Buffer.from(`${t}.`), raw]))}`;
              return safeEqual(sig, expected) ? { deliveryId: delivery, event: headers["x-sandbox-event"] ?? "sample" } : null;
            },
            parse(_event, payload) {
              const p = payload as Record<string, unknown> | null;
              if (!p || typeof p.connection !== "string" || typeof p.resource !== "string" || typeof p.event !== "string" || typeof p.title !== "string") return null;
              if (!/^sample\.(created|updated)$/.test(p.event)) return { externalId: p.connection, events: [] };
              const ev: NormalizedEvent = {
                resource: p.resource.slice(0, 200),
                event: p.event,
                title: p.title.slice(0, 200),
                url: typeof p.url === "string" && p.url.startsWith(`${SANDBOX_HOST}/`) ? p.url : null,
                summary: typeof p.summary === "string" ? p.summary.slice(0, 300) : null,
                actor: typeof p.actor === "string" ? p.actor.slice(0, 60) : null,
                occurredAt: deps.now(),
                dedupeKey: `sbx:${typeof p.id === "string" ? p.id.slice(0, 80) : crypto.randomUUID()}`,
              };
              return { externalId: p.connection, events: [ev] };
            },
          }
        : undefined,
    linking: ready
      ? {
          async targets(ctx) {
            const r = await ctx.api<{ installations?: { id: string; name: string }[] }>(`${SANDBOX_HOST}/installations`);
            return (r?.installations ?? []).map((i) => ({ id: i.id, label: i.name }));
          },
          installUrl: () => null,
          async resources() {
            return { items: [{ id: "sample/one", label: "sample/one" }, { id: "sample/two", label: "sample/two" }], cursor: null };
          },
        }
      : undefined,
  };
}
