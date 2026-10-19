import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { integrationsConfig } from "../src/integrations/config.js";
import type { GriipClient } from "./support/griipClient.js";
import type { Harness } from "./support/harness.js";
import { FakeProviders, json, providerConnect, providerEnv, type Recorded, tokenAnswer } from "./support/fakeProviders.js";
import { sandboxWorkspace, startIntegrationServer } from "./support/integrations.js";

/**
 * Figma and GitLab (2026-10-19) against mocked providers following their published APIs (developers.figma.com,
 * docs.gitlab.com): the exact sign-in requests, token formats, identity, search and link previews. No real provider or
 * credential is involved, so live behaviour still needs a configured account.
 */
const fake = new FakeProviders();
let h: Harness;
let owner: GriipClient, member: GriipClient;
let orgId: string;

const install = (id: string) => owner.req("POST", `/v1/orgs/${orgId}/integrations/${id}/install`, {});
const form = (r: Recorded) => Object.fromEntries(new URLSearchParams(r.body));

beforeAll(async () => {
  h = await startIntegrationServer(providerEnv(), fake.fetch);
  ({ owner, member, orgId } = await sandboxWorkspace(h, "Design Lab"));
});
afterAll(async () => h.close());

describe("Figma", () => {
  it("is offered once configured, signs in with HTTP Basic and PKCE for two read-only scopes, and previews links from metadata only", async () => {
    const apps = (await owner.req("GET", `/v1/orgs/${orgId}/integrations`)).apps as { id: string; state: string }[];
    expect(apps.find((a) => a.id === "figma")?.state).toBe("available");
    fake.on("POST", "https://api.figma.com/v1/oauth/token", (r) => {
      if (r.headers.get("authorization") !== `Basic ${Buffer.from("test-figma-client:test-figma-secret").toString("base64")}`) return json(401, {});
      if (form(r).client_secret) return json(400, { error: "secret_in_body" });
      return tokenAnswer(fake, r, "form", { user_id_string: "fig-1" });
    });
    fake.on("GET", "https://api.figma.com/v1/me", () => ({ id: "fig-1", handle: "Mem", email: "member@example.com" }));
    await install("figma");
    const r = await providerConnect(h, fake, member, orgId, "figma");
    expect(r.status).toBe("connected");
    expect(r.authorize.origin + r.authorize.pathname).toBe("https://www.figma.com/oauth");
    expect(r.authorize.searchParams.get("scope")).toBe("current_user:read file_metadata:read");
    expect(r.authorize.searchParams.get("code_challenge_method")).toBe("S256");
    fake.on("GET", "https://api.figma.com/v1/files/AbCdEf1234567890/meta", () => ({
      file: { name: "Checkout flow", folder_name: "Mobile", editorType: "figma", last_touched_at: "2026-10-18T10:00:00Z", last_touched_by: { handle: "Ann" }, url: "https://www.figma.com/design/AbCdEf1234567890" },
    }));
    const u = await member.req("POST", `/v1/orgs/${orgId}/integrations/unfurl`, { url: "https://www.figma.com/design/AbCdEf1234567890/Checkout-flow?node-id=1-2" });
    expect(u.resource).toMatchObject({ id: "AbCdEf1234567890", title: "Checkout flow", status: "Figma Design", container: "Mobile", detail: "Ann" });
    // The design itself is never requested: only the metadata endpoint.
    expect(fake.calls.some((c) => c.url.pathname === "/v1/files/AbCdEf1234567890")).toBe(false);
  });
});

describe("GitLab", () => {
  it("signs in with PKCE for read_api and read_user, searches open issues and merge requests, and previews links", async () => {
    fake.on("POST", "https://gitlab.com/oauth/token", (r) => tokenAnswer(fake, r, "form", { scope: "read_api read_user" }));
    fake.on("GET", "https://gitlab.com/api/v4/user", () => ({ id: 77, username: "mem", email: "member@example.com" }));
    await install("gitlab");
    const r = await providerConnect(h, fake, member, orgId, "gitlab");
    expect(r.status).toBe("connected");
    expect(r.authorize.searchParams.get("scope")).toBe("read_api read_user");
    expect(r.authorize.searchParams.get("code_challenge_method")).toBe("S256");
    expect(form(fake.last("https://gitlab.com/oauth/token")!)).toMatchObject({ client_id: "test-gitlab-client", grant_type: "authorization_code" });
    fake.on("GET", "https://gitlab.com/api/v4/issues", () => [
      { iid: 12, title: "Crash on login", state: "opened", web_url: "https://gitlab.com/acme/app/-/issues/12", updated_at: "2026-10-18T09:00:00Z", author: { name: "Ann" } },
    ]);
    fake.on("GET", "https://gitlab.com/api/v4/merge_requests", () => [
      { iid: 7, title: "Fix login", state: "opened", web_url: "https://gitlab.com/acme/app/-/merge_requests/7", updated_at: "2026-10-18T10:00:00Z", author: { name: "Bob" } },
    ]);
    const s = await member.req("GET", `/v1/orgs/${orgId}/integrations/gitlab/search?q=login`);
    expect(s.items.map((i: { id: string; kind: string }) => [i.id, i.kind])).toEqual([["mr:acme/app:7", "pull_request"], ["issue:acme/app:12", "issue"]]);
    const issuesCall = fake.last("https://gitlab.com/api/v4/issues")!;
    expect(issuesCall.url.searchParams.get("search")).toBe("login");
    expect(issuesCall.url.searchParams.get("state")).toBe("opened");
    fake.on("GET", "https://gitlab.com/api/v4/projects/acme%2Fapp/merge_requests/7", () => ({
      iid: 7, title: "Fix login", state: "merged", web_url: "https://gitlab.com/acme/app/-/merge_requests/7", references: { full: "acme/app!7" },
    }));
    const u = await member.req("POST", `/v1/orgs/${orgId}/integrations/unfurl`, { url: "https://gitlab.com/acme/app/-/merge_requests/7/diffs" });
    expect(u.resource).toMatchObject({ id: "mr:acme/app:7", title: "acme/app!7 Fix login", status: "merged" });
  });

  it("only accepts a plain https address for a self-managed instance", () => {
    const int = (_n: string, d: number) => d;
    expect(() => integrationsConfig({ GITLAB_BASE_URL: "http://gitlab.example.com" }, "development", int)).toThrow(/GITLAB_BASE_URL/);
    expect(() => integrationsConfig({ GITLAB_BASE_URL: "https://gitlab.example.com/sub" }, "development", int)).toThrow(/GITLAB_BASE_URL/);
    const ok = integrationsConfig(
      {
        GITLAB_BASE_URL: "https://gitlab.example.com/", GITLAB_OAUTH_CLIENT_ID: "a", GITLAB_OAUTH_CLIENT_SECRET: "b",
        INTEGRATIONS_PUBLIC_URL: "https://m.example.com", INTEGRATIONS_TOKEN_KEY: Buffer.alloc(32, 1).toString("base64"),
      },
      "development", int,
    );
    expect(ok.gitlab?.baseUrl).toBe("https://gitlab.example.com");
  });
});
