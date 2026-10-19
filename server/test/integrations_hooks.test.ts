import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { hmacSha256Hex } from "../src/integrations/tokens.js";
import type { GriipClient } from "./support/griipClient.js";
import { expectStatus, type Harness } from "./support/harness.js";
import { sandboxConnect, sandboxWorkspace, startIntegrationServer } from "./support/integrations.js";

/**
 * Incoming webhooks, workflows, search across apps and server-wide limits (docs/INTEGRATIONS.md, docs/PLANS.md). A
 * tool posting to a webhook is simulated with plain HTTP requests, signed or with the bearer secret.
 */
let h: Harness;
let owner: GriipClient, admin: GriipClient, member: GriipClient, outsider: GriipClient;
let orgId: string, channelId: string, otherChannel: string;

const post = (hookUrl: string, body: unknown, headers: Record<string, string>) =>
  fetch(hookUrl.replace("http://meldiq.test", h.baseUrl), { method: "POST", headers: { "content-type": "application/json", ...headers }, body: typeof body === "string" ? body : JSON.stringify(body) });
const signed = (secret: string, body: object, ts = Date.now()) => {
  const raw = JSON.stringify(body);
  return { raw, headers: { "x-meldiq-timestamp": String(ts), "x-meldiq-signature": `sha256=${hmacSha256Hex(secret, `${ts}.${raw}`)}` } };
};
const events = async (c: GriipClient, conv = channelId) => (await c.req("GET", `/v1/conversations/${conv}/integration-events`)).events as any[];

beforeAll(async () => {
  h = await startIntegrationServer({ ENTITLEMENT_LIMITS: "integrations.webhooks=2,roles.custom=3" });
  ({ owner, admin, member, outsider, orgId } = await sandboxWorkspace(h, "Hooks Lab"));
  channelId = (await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "builds", scope: "ORG", kind: "PUBLIC", autoJoin: true })).channel.id;
  otherChannel = (await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "ops", scope: "ORG", kind: "PUBLIC", autoJoin: true })).channel.id;
});
afterAll(async () => h.close());

describe("incoming webhooks", () => {
  let hook: any, secret: string;

  it("is available without any provider and needs installing", async () => {
    const app = (await owner.req("GET", `/v1/orgs/${orgId}/integrations`)).apps.find((a: any) => a.id === "incoming-webhooks");
    expect(app).toMatchObject({ state: "available", availability: "available" });
    await expectStatus(admin.req("POST", `/v1/orgs/${orgId}/integration-hooks`, { conversationId: channelId, name: "CI" }), 409); // not installed
    await admin.req("POST", `/v1/orgs/${orgId}/integrations/incoming-webhooks/install`);
    // Webhooks post to channels without linking the workspace anywhere: no "Link workspace" for them.
    const detail = await owner.req("GET", `/v1/orgs/${orgId}/integrations/incoming-webhooks`);
    expect(detail.actions).not.toContain("link_workspace");
    expect(detail.app.supports.workspaceLink).toBe(false);
  });

  it("creates a webhook for a channel and shows its secret only once", async () => {
    await expectStatus(member.req("POST", `/v1/orgs/${orgId}/integration-hooks`, { conversationId: channelId, name: "CI" }), 403);
    const r = await admin.req("POST", `/v1/orgs/${orgId}/integration-hooks`, { conversationId: channelId, name: "Jenkins" });
    hook = r.hook;
    secret = r.secret;
    expect(hook.url).toBe(`http://meldiq.test/v1/integrations/hooks/${hook.id}`);
    expect(secret).toMatch(/^[A-Za-z0-9_-]{40,}$/);
    const list = (await admin.req("GET", `/v1/orgs/${orgId}/integration-hooks`)).hooks;
    expect(JSON.stringify(list)).not.toContain(secret);
    expect(list[0]).toMatchObject({ name: "Jenkins", channelName: "builds", active: true });
    await expectStatus(member.req("GET", `/v1/orgs/${orgId}/integration-hooks`), 403);
  });

  it("posts signed or bearer-authenticated requests and refuses everything else", async () => {
    const s = signed(secret, { title: "Build #42 passed", text: "main · 3m 12s", url: "https://ci.example.com/42", actor: "jenkins" });
    expect((await post(hook.url, s.raw, s.headers)).status).toBe(202);
    // A captured signed request replayed inside the window posts nothing, whatever Idempotency-Key it carries.
    expect((await post(hook.url, s.raw, s.headers)).status).toBe(200);
    expect((await post(hook.url, s.raw, { ...s.headers, "idempotency-key": "another" })).status).toBe(200);
    expect((await post(hook.url, { text: "Deploy finished\nproduction · v1.4.2" }, { authorization: `Bearer ${secret}` })).status).toBe(202);
    expect((await post(hook.url, { title: "x" }, { authorization: "Bearer wrong" })).status).toBe(401);
    expect((await post(hook.url, { title: "x" }, {})).status).toBe(401);
    const tampered = signed(secret, { title: "real" });
    expect((await post(hook.url, tampered.raw.replace("real", "fake"), tampered.headers)).status).toBe(401);
    const old = signed(secret, { title: "replayed" }, Date.now() - 10 * 60_000);
    expect((await post(hook.url, old.raw, old.headers)).status).toBe(401);
    expect((await post(hook.url, { nothing: true }, { authorization: `Bearer ${secret}` })).status).toBe(400);
    expect((await post(hook.url, "not json", { authorization: `Bearer ${secret}` })).status).toBe(400);
    expect((await post(`http://meldiq.test/v1/integrations/hooks/hook_doesnotexist12345`, { title: "x" }, { authorization: `Bearer ${secret}` })).status).toBe(404);
    const posted = await events(member);
    expect(posted.map((e: any) => [e.title, e.summary])).toEqual([
      ["Build #42 passed", "main · 3m 12s"],
      ["Deploy finished", "production · v1.4.2"],
    ]);
    expect(posted[0]).toMatchObject({ connectorId: "incoming-webhooks", url: "https://ci.example.com/42", actor: "jenkins" });
    await expectStatus(outsider.req("GET", `/v1/conversations/${channelId}/integration-events`), 404);
  });

  it("posts once per idempotency key and keeps only https links", async () => {
    const send = () => post(hook.url, { title: "Release 2.0", url: "javascript:alert(1)" }, { authorization: `Bearer ${secret}`, "idempotency-key": "rel-2.0" });
    expect((await send()).status).toBe(202);
    expect((await send()).status).toBe(200);
    const rel = (await events(member)).filter((e: any) => e.title === "Release 2.0");
    expect(rel).toHaveLength(1);
    expect(rel[0].url).toBeNull();
  });

  it("rotates the secret: the old one stops working at once", async () => {
    const r = await admin.req("POST", `/v1/orgs/${orgId}/integration-hooks/${hook.id}/rotate`);
    expect(r.secret).not.toBe(secret);
    expect((await post(hook.url, { title: "old secret" }, { authorization: `Bearer ${secret}` })).status).toBe(401);
    secret = r.secret;
    expect((await post(hook.url, { title: "new secret" }, { authorization: `Bearer ${secret}` })).status).toBe(202);
  });

  it("pauses when its creator can no longer manage apps, and stops when the app is turned off", async () => {
    await owner.req("PATCH", `/v1/orgs/${orgId}/members/${admin.userId}`, { role: "MEMBER" });
    expect((await post(hook.url, { title: "after demotion" }, { authorization: `Bearer ${secret}` })).status).toBe(409);
    expect((await owner.req("GET", `/v1/orgs/${orgId}/integration-hooks`)).hooks[0]).toMatchObject({ active: false, lastError: "The person who set this up can no longer manage apps" });
    expect((await post(hook.url, { title: "paused" }, { authorization: `Bearer ${secret}` })).status).toBe(404);
    await owner.req("PATCH", `/v1/orgs/${orgId}/members/${admin.userId}`, { role: "ADMIN" });
    const again = await admin.req("POST", `/v1/orgs/${orgId}/integration-hooks/${hook.id}/rotate`); // rotating turns it back on
    secret = again.secret;
    const d = await owner.req("GET", `/v1/orgs/${orgId}/integrations/incoming-webhooks`);
    await owner.req("PATCH", `/v1/orgs/${orgId}/integrations/incoming-webhooks`, { baseRev: d.install.rev, enabled: false });
    expect((await post(hook.url, { title: "app off" }, { authorization: `Bearer ${secret}` })).status).toBe(409);
    const d2 = await owner.req("GET", `/v1/orgs/${orgId}/integrations/incoming-webhooks`);
    await owner.req("PATCH", `/v1/orgs/${orgId}/integrations/incoming-webhooks`, { baseRev: d2.install.rev, enabled: true });
    expect((await post(hook.url, { title: "app on" }, { authorization: `Bearer ${secret}` })).status).toBe(202);
    expect((await events(member)).map((e: any) => e.title)).not.toContain("after demotion");
  });

  it("enforces the server's webhook limit honestly, and removes webhooks on delete", async () => {
    await admin.req("POST", `/v1/orgs/${orgId}/integration-hooks`, { conversationId: otherChannel, name: "Datadog" });
    const limited = await expectStatus(admin.req("POST", `/v1/orgs/${orgId}/integration-hooks`, { conversationId: otherChannel, name: "Third" }), 409);
    expect(limited.error).toMatchObject({ code: "limit_reached", details: { key: "integrations.webhooks", limit: 2 } });
    const plan = await member.req("GET", `/v1/orgs/${orgId}/plan`);
    expect(plan).toMatchObject({ plan: null, source: "server", upgrade: null });
    expect(plan.capabilities.find((c: any) => c.key === "integrations.webhooks")).toMatchObject({ limit: 2, used: 2, available: false });
    expect(plan.capabilities.find((c: any) => c.key === "integrations.installed")).toMatchObject({ limit: null, available: true });
    await admin.req("DELETE", `/v1/orgs/${orgId}/integration-hooks/${hook.id}`);
    expect((await post(hook.url, { title: "gone" }, { authorization: `Bearer ${secret}` })).status).toBe(404);
  });
});

describe("workflows", () => {
  it("lists every trigger and action with owner and last run, for app admins only", async () => {
    await owner.req("POST", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/install`);
    expect((await sandboxConnect(h, owner, orgId)).status).toBe("connected");
    await owner.req("POST", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/link`, { targetId: "sbx-org-1" });
    await owner.req("POST", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/subscriptions`, { conversationId: channelId, resource: "sample/one", events: ["sample.created"] });
    await owner.req("POST", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/test-event`, { resource: "sample/one" });
    const list = (await owner.req("GET", `/v1/orgs/${orgId}/integrations/workflows`)).workflows as any[];
    expect(list.map((w) => w.kind).sort()).toEqual(["app_events", "incoming_webhook"]);
    const appEvents = list.find((w) => w.kind === "app_events");
    expect(appEvents).toMatchObject({ appName: "Meldiq Sandbox", trigger: "Sample item created · sample/one", action: "post_to_channel", channelName: "builds", active: true });
    expect(appEvents.lastRunAt).toBeGreaterThan(0);
    expect(list.find((w) => w.kind === "incoming_webhook")).toMatchObject({ trigger: "Datadog", channelName: "ops" });
    await expectStatus(member.req("GET", `/v1/orgs/${orgId}/integrations/workflows`), 403);
  });
});

describe("search across connected apps", () => {
  it("searches every app the person connected, with their own access, and nothing else", async () => {
    const r = await owner.req("GET", `/v1/orgs/${orgId}/integrations/search?q=budget`);
    expect(r.results).toHaveLength(1);
    expect(r.results[0]).toMatchObject({ connectorId: "meldiq-sandbox", error: null });
    expect(r.results[0].items.map((i: any) => i.title)).toEqual(["Budget 2026.xlsx"]);
    // A member with no connections gets no results (and never someone else's).
    expect((await member.req("GET", `/v1/orgs/${orgId}/integrations/search?q=budget`)).results).toEqual([]);
    await expectStatus(outsider.req("GET", `/v1/orgs/${orgId}/integrations/search?q=budget`), 404);
    await expectStatus(owner.req("GET", `/v1/orgs/${orgId}/integrations/search?q=`), 400);
  });
});

describe("token key rotation", () => {
  it("re-seals stored connections and webhook secrets under a new key (dry run first)", async () => {
    const { keyringFrom, open, sealedKeyId } = await import("../src/integrations/tokens.js");
    const { runIntegrationCommand } = await import("../src/integrations/admin.js");
    const crypto = await import("node:crypto");
    const old = h.ctx.config.integrations.keyring!;
    const rotated = keyringFrom(crypto.randomBytes(32).toString("base64"), old.active.key.toString("base64"))!;
    const cfg = { ...h.ctx.config, integrations: { ...h.ctx.config.integrations, keyring: rotated } };
    const before = await h.ctx.integrations.store.rows.find<any>("integration_credentials", {});
    expect(before.length).toBeGreaterThan(0);
    expect(await runIntegrationCommand("integrations-reseal", [], cfg, h.ctx.db)).toBe(0);
    expect((await h.ctx.integrations.store.rows.find<any>("integration_credentials", {})).map((r) => r.access_token)).toEqual(before.map((r) => r.access_token));
    expect(await runIntegrationCommand("integrations-reseal", ["--yes"], cfg, h.ctx.db)).toBe(0);
    for (const r of await h.ctx.integrations.store.rows.find<any>("integration_credentials", {})) {
      expect(sealedKeyId(r.access_token)).toBe(rotated.active.id);
      expect(open(rotated, r.access_token, "access-token", r.account_id)).toMatch(/^sbx\./);
    }
    for (const hk of await h.ctx.integrations.store.rows.find<any>("integration_hooks", {})) expect(sealedKeyId(hk.secret)).toBe(rotated.active.id);
    expect(await runIntegrationCommand("integrations-status", [], cfg, h.ctx.db)).toBe(0);
  });
});

describe("custom role limit", () => {
  it("applies the server's custom-role limit", async () => {
    for (const name of ["One", "Two", "Three"]) await owner.req("POST", `/v1/orgs/${orgId}/roles`, { name, permissions: [] });
    expect((await expectStatus(owner.req("POST", `/v1/orgs/${orgId}/roles`, { name: "Four", permissions: [] }), 409)).error.code).toBe("limit_reached");
  });
});
