import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { GriipClient } from "./support/griipClient.js";
import type { Harness } from "./support/harness.js";
import { FakeProviders, json, providerConnect, providerEnv, type Recorded, tokenAnswer } from "./support/fakeProviders.js";
import { sandboxWorkspace, startIntegrationServer } from "./support/integrations.js";

/**
 * Asana and Zoom (round 6, 2026-10-19) against mocked providers following their published OAuth documentation
 * (developers.asana.com/docs/oauth, developers.zoom.us/docs/integrations/oauth): the sign-in requests, client
 * authentication, identity and link previews. No real provider or credential is involved.
 */
const fake = new FakeProviders();
let h: Harness;
let owner: GriipClient, member: GriipClient;
let orgId: string;

const install = (id: string) => owner.req("POST", `/v1/orgs/${orgId}/integrations/${id}/install`, {});
const form = (r: Recorded) => Object.fromEntries(new URLSearchParams(r.body));

beforeAll(async () => {
  h = await startIntegrationServer(providerEnv(), fake.fetch);
  ({ owner, member, orgId } = await sandboxWorkspace(h, "Tools Lab"));
});
afterAll(async () => h.close());

describe("Asana", () => {
  it("signs in with PKCE and the secret in the form, for three read scopes, and previews both kinds of task link", async () => {
    const apps = (await owner.req("GET", `/v1/orgs/${orgId}/integrations`)).apps as { id: string; state: string }[];
    expect(apps.find((a) => a.id === "asana")?.state).toBe("available");
    fake.on("POST", "https://app.asana.com/-/oauth_token", (r) => {
      const f = form(r);
      if (f.client_id !== "test-asana-client" || f.client_secret !== "test-asana-secret") return json(401, { error: "invalid_client" });
      if (f.grant_type === "authorization_code" && !f.code_verifier) return json(400, { error: "pkce_required" });
      return tokenAnswer(fake, r, "form", {});
    });
    fake.on("GET", "https://app.asana.com/api/1.0/users/me", () => ({ data: { gid: "1201", name: "Mem", email: "member@example.com" } }));
    await install("asana");
    const r = await providerConnect(h, fake, member, orgId, "asana");
    expect(r.status).toBe("connected");
    expect(r.authorize.origin + r.authorize.pathname).toBe("https://app.asana.com/-/oauth_authorize");
    expect(r.authorize.searchParams.get("scope")).toBe("projects:read tasks:read users:read");
    expect(r.authorize.searchParams.get("code_challenge_method")).toBe("S256");
    fake.on("GET", "https://app.asana.com/api/1.0/tasks/1209876543210", () => ({
      data: {
        gid: "1209876543210", name: "Ship the release notes", completed: false, due_on: "2026-10-24", assignee: { name: "Ann" },
        memberships: [{ project: { name: "Launch" } }], permalink_url: "https://app.asana.com/0/111/1209876543210", modified_at: "2026-10-19T08:00:00.000Z",
      },
    }));
    for (const link of ["https://app.asana.com/0/1200000000001/1209876543210/f", "https://app.asana.com/1/1100000000001/project/1200000000001/task/1209876543210"]) {
      const u = await member.req("POST", `/v1/orgs/${orgId}/integrations/unfurl`, { url: link });
      expect(u.resource).toMatchObject({ id: "1209876543210", title: "Ship the release notes", status: "Due 2026-10-24", container: "Launch", detail: "Ann" });
    }
    // Only the fields shown are asked for: never the description (notes) or comments.
    const taskCall = fake.calls.find((c) => c.url.pathname === "/api/1.0/tasks/1209876543210")!;
    expect(taskCall.url.searchParams.get("opt_fields")).not.toMatch(/notes|stories|attachments/);
  });
});

describe("Zoom", () => {
  it("signs in with HTTP Basic and PKCE, and previews a meeting link without its passcode", async () => {
    const basic = `Basic ${Buffer.from("test-zoom-client:test-zoom-secret").toString("base64")}`;
    fake.on("POST", "https://zoom.us/oauth/token", (r) => {
      if (r.headers.get("authorization") !== basic) return json(401, { error: "invalid_client" });
      if (form(r).client_secret) return json(400, { error: "secret_in_body" });
      return tokenAnswer(fake, r, "form", {});
    });
    fake.on("GET", "https://api.zoom.us/v2/users/me", () => ({ id: "zu-1", email: "member@example.com", account_id: "acct-1" }));
    await install("zoom");
    const r = await providerConnect(h, fake, member, orgId, "zoom");
    expect(r.status).toBe("connected");
    expect(r.authorize.origin + r.authorize.pathname).toBe("https://zoom.us/oauth/authorize");
    expect(r.authorize.searchParams.get("scope")).toBe("meeting:read:meeting user:read:user");
    fake.on("GET", "https://api.zoom.us/v2/meetings/81234567890", () => ({
      id: 81234567890, topic: "Weekly sync", start_time: "2026-10-20T03:00:00Z", duration: 30, timezone: "Asia/Dhaka", status: "waiting",
      password: "secret-pass", join_url: "https://us02web.zoom.us/j/81234567890?pwd=abc",
    }));
    const u = await member.req("POST", `/v1/orgs/${orgId}/integrations/unfurl`, { url: "https://us02web.zoom.us/j/81234567890?pwd=abc" });
    expect(u.resource).toMatchObject({ id: "81234567890", kind: "event", title: "Weekly sync", status: "30 min", startAt: Date.parse("2026-10-20T03:00:00Z") });
    expect(u.resource.endAt - u.resource.startAt).toBe(30 * 60_000);
    expect(JSON.stringify(u)).not.toMatch(/secret-pass|pwd=/);
  });
});
