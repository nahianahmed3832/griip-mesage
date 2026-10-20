import crypto from "node:crypto";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { webhookUrlProblem } from "../src/developer/transport.js";
import type { GriipClient } from "./support/griipClient.js";
import { expectStatus, type Harness, startServer } from "./support/harness.js";

/** Round 7 slice C6: API keys, the external API and webhooks (docs/DEVELOPER_API.md). */
let h: Harness;
let owner: GriipClient, member: GriipClient;
let orgId: string, general: string, secretChannel: string;
const sent: { url: string; body: string; headers: Record<string, string> }[] = [];
let respond = 200;

const ext = async (token: string, method: string, path: string, body?: unknown, headers: Record<string, string> = {}) => {
  const res = await fetch(`${h.baseUrl}${path}`, {
    method,
    headers: { authorization: `Bearer ${token}`, ...(body ? { "content-type": "application/json" } : {}), ...headers },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, json: (await res.json().catch(() => null)) as any };
};

beforeAll(async () => {
  h = await startServer({ WORKFLOW_TICK_MS: "0", INTEGRATIONS_TOKEN_KEY: crypto.randomBytes(32).toString("base64") });
  h.ctx.developer.sender = async (url, body, headers) => {
    sent.push({ url, body, headers });
    return { status: respond };
  };
  [owner, member] = await Promise.all(["devowner", "devmember"].map((n) => h.client(n)));
  orgId = (await owner.req("POST", "/v1/orgs", { name: "Dev Co" })).org.id;
  const code = (await owner.req("POST", `/v1/orgs/${orgId}/invites`, { role: "MEMBER" })).invite.code;
  await member.req("POST", "/v1/invites/accept", { code });
  general = (await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "general", scope: "ORG", kind: "PUBLIC" })).channel.id;
  secretChannel = (await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "secret-plans", scope: "ORG", kind: "PRIVATE" })).channel.id;
});
afterAll(async () => h.close());

describe("API keys", () => {
  let token: string;
  let keyId: string;

  it("only people who manage developer access make keys; the token is shown once and never stored", async () => {
    await expectStatus(member.req("POST", `/v1/orgs/${orgId}/api-keys`, { name: "x", scopes: ["workspace:read"] }), 403);
    const r = await owner.req("POST", `/v1/orgs/${orgId}/api-keys`, { name: "CI bot", scopes: ["workspace:read", "members:read", "channels:read", "updates:write"], expiresInDays: 30 });
    token = r.token;
    keyId = r.key.id;
    expect(token).toMatch(/^mdq\.ak_[A-Za-z0-9_-]+\.[A-Za-z0-9_-]{20,}$/);
    const listed = (await owner.req("GET", `/v1/orgs/${orgId}/api-keys`)).keys;
    expect(JSON.stringify(listed)).not.toContain(token.split(".")[2]);
    expect(JSON.stringify(h.ctx.db.prepare("SELECT * FROM api_keys").all())).not.toContain(token.split(".")[2]);
  });

  it("reads only what its scopes allow, and never private channels or member contact details", async () => {
    expect((await ext(token, "GET", "/ext/v1/workspace")).json.workspace).toMatchObject({ name: "Dev Co", memberCount: 2 });
    const members = (await ext(token, "GET", "/ext/v1/members")).json.items;
    expect(members).toHaveLength(2);
    expect(Object.keys(members[0]).sort()).toEqual(["displayName", "id", "joinedAt", "role", "title", "username"]);
    const channels = (await ext(token, "GET", "/ext/v1/channels")).json.items.map((c: { name: string }) => c.name);
    expect(channels).toContain("general");
    expect(channels).not.toContain("secret-plans");
    expect((await ext(token, "GET", "/ext/v1/documents")).status).toBe(403); // no documents:read
  });

  it("posts updates in public channels only, once per idempotency key, labelled with the key's name", async () => {
    const one = await ext(token, "POST", `/ext/v1/channels/${general}/updates`, { title: "Build 42 passed" }, { "idempotency-key": "b42" });
    const two = await ext(token, "POST", `/ext/v1/channels/${general}/updates`, { title: "Build 42 passed" }, { "idempotency-key": "b42" });
    expect([one.json.posted, two.json.posted]).toEqual([true, false]);
    const events = (await member.req("GET", `/v1/conversations/${general}/integration-events`)).events as { appName: string; title: string }[];
    expect(events.find((e) => e.title === "Build 42 passed")?.appName).toBe("API · CI bot");
    expect((await ext(token, "POST", `/ext/v1/channels/${secretChannel}/updates`, { title: "x" })).status).toBe(404);
  });

  it("session tokens can't use the external API, and keys can't use the app API", async () => {
    expect((await ext("not-a-key", "GET", "/ext/v1/workspace")).status).toBe(401);
    const res = await fetch(`${h.baseUrl}/v1/conversations`, { headers: { authorization: `Bearer ${token}` } });
    expect(res.status).toBe(401);
  });

  it("rotation replaces the key; revoked keys and keys whose creator lost the right stop working", async () => {
    const rotated = await owner.req("POST", `/v1/orgs/${orgId}/api-keys/${keyId}/rotate`);
    expect((await ext(token, "GET", "/ext/v1/workspace")).status).toBe(401);
    expect((await ext(rotated.token, "GET", "/ext/v1/workspace")).status).toBe(200);
    // A key made by an admin who is later demoted stops working.
    await owner.req("PATCH", `/v1/orgs/${orgId}/members/${member.userId}`, { role: "ADMIN" });
    const theirs = (await member.req("POST", `/v1/orgs/${orgId}/api-keys`, { name: "theirs", scopes: ["workspace:read"] })).token;
    expect((await ext(theirs, "GET", "/ext/v1/workspace")).status).toBe(200);
    await owner.req("PATCH", `/v1/orgs/${orgId}/members/${member.userId}`, { role: "MEMBER" });
    expect((await ext(theirs, "GET", "/ext/v1/workspace")).status).toBe(401);
    await owner.req("DELETE", `/v1/orgs/${orgId}/api-keys/${rotated.key.id}`);
    expect((await ext(rotated.token, "GET", "/ext/v1/workspace")).status).toBe(401);
  });

  it("each key is rate limited", async () => {
    const k = (await owner.req("POST", `/v1/orgs/${orgId}/api-keys`, { name: "busy", scopes: ["workspace:read"] })).token;
    let last = 0;
    for (let i = 0; i < 121; i++) last = (await ext(k, "GET", "/ext/v1/workspace")).status;
    expect(last).toBe(429);
  });
});

describe("webhooks", () => {
  let hookId: string;
  let secret: string;

  it("refuses non-https and private addresses", async () => {
    expect(webhookUrlProblem("http://example.com/hook")).toMatch(/https/);
    expect(webhookUrlProblem("https://127.0.0.1/hook")).toMatch(/private/);
    expect(webhookUrlProblem("https://10.0.0.5/hook")).toMatch(/private/);
    expect(webhookUrlProblem("https://printer.local/hook")).toMatch(/private/);
    expect(webhookUrlProblem("https://hooks.example.com/meldiq")).toBeNull();
    await expectStatus(owner.req("POST", `/v1/orgs/${orgId}/webhooks`, { name: "bad", url: "http://example.com", events: ["member.joined"] }), 400);
  });

  it("signs every delivery and starts with a ping; the secret is shown once", async () => {
    const r = await owner.req("POST", `/v1/orgs/${orgId}/webhooks`, { name: "HR sync", url: "https://hooks.example.com/meldiq", events: ["member.joined", "channel.created", "document.created", "meeting.created"] });
    hookId = r.webhook.id;
    secret = r.secret;
    await h.ctx.developer.tick();
    const ping = sent.at(-1)!;
    expect(ping.headers["meldiq-event"]).toBe("ping");
    const expected = crypto.createHmac("sha256", secret).update(`${ping.headers["meldiq-timestamp"]}.${ping.body}`).digest("hex");
    expect(ping.headers["meldiq-signature"]).toBe(`v1=${expected}`);
    expect(JSON.stringify((await owner.req("GET", `/v1/orgs/${orgId}/webhooks`)).webhooks)).not.toContain(secret);
    expect((await owner.req("GET", `/v1/orgs/${orgId}/webhooks`)).webhooks[0].verified).toBe(true);
  });

  it("sends workspace events, never private channels, restricted documents or private rooms' meetings", async () => {
    sent.length = 0;
    await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "launch", scope: "ORG", kind: "PUBLIC" });
    await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "layoffs", scope: "ORG", kind: "PRIVATE" });
    await owner.req("POST", `/v1/orgs/${orgId}/documents`, { title: "Handbook", content: "x", access: "workspace" });
    await owner.req("POST", `/v1/orgs/${orgId}/documents`, { title: "Salaries", content: "x", access: "restricted" });
    const joiner = await h.client("devjoiner");
    const code = (await owner.req("POST", `/v1/orgs/${orgId}/invites`, { role: "MEMBER" })).invite.code;
    await joiner.req("POST", "/v1/invites/accept", { code });
    await h.ctx.developer.tick();
    const bodies = sent.map((s) => JSON.parse(s.body));
    expect(bodies.map((b) => b.type).sort()).toEqual(["channel.created", "document.created", "member.joined"]);
    const all = JSON.stringify(bodies);
    expect(all).toContain("launch");
    expect(all).not.toContain("layoffs");
    expect(all).not.toContain("Salaries");
    expect(bodies.find((b) => b.type === "member.joined").data.member).toMatchObject({ id: joiner.userId });
    // The delivery id is the event's own: the same across retries, for receivers to de-duplicate.
    expect(new Set(sent.map((s) => s.headers["meldiq-delivery"])).size).toBe(sent.length);
  });

  it("retries failures with backoff, records them, and pauses a webhook that keeps failing", async () => {
    respond = 500;
    sent.length = 0;
    await owner.req("POST", `/v1/orgs/${orgId}/webhooks/${hookId}/test`);
    await h.ctx.developer.tick();
    const deliveries = (await owner.req("GET", `/v1/orgs/${orgId}/webhooks/${hookId}/deliveries`)).deliveries;
    expect(deliveries[0]).toMatchObject({ state: "queued", attempts: 1, lastStatus: 500 });
    // Nothing is retried before its time.
    await h.ctx.developer.tick();
    expect(sent).toHaveLength(1);
    h.ctx.db.prepare("UPDATE webhooks SET consecutive_failures = 19 WHERE id = ?").run(hookId);
    h.ctx.db.prepare("UPDATE webhook_deliveries SET next_at = 0 WHERE webhook_id = ? AND state = 'queued'").run(hookId);
    await h.ctx.developer.tick();
    const hook = (await owner.req("GET", `/v1/orgs/${orgId}/webhooks`)).webhooks[0];
    expect(hook).toMatchObject({ enabled: false });
    expect(hook.disabledReason).toMatch(/Paused after 20/);
    respond = 200;
    const re = (await owner.req("PUT", `/v1/orgs/${orgId}/webhooks/${hookId}`, { name: "HR sync", url: "https://hooks.example.com/meldiq", events: ["member.joined"], enabled: true })).webhook;
    expect(re).toMatchObject({ enabled: true, consecutiveFailures: 0, disabledReason: null });
  });

  it("rotating the secret signs later deliveries with the new one", async () => {
    const { secret: fresh } = await owner.req("POST", `/v1/orgs/${orgId}/webhooks/${hookId}/rotate-secret`);
    sent.length = 0;
    await owner.req("POST", `/v1/orgs/${orgId}/webhooks/${hookId}/test`);
    await h.ctx.developer.tick();
    const d = sent.at(-1)!;
    expect(d.headers["meldiq-signature"]).toBe(`v1=${crypto.createHmac("sha256", fresh).update(`${d.headers["meldiq-timestamp"]}.${d.body}`).digest("hex")}`);
    expect((await owner.req("GET", "/v1/server-info")).features).toContain("developer-api");
  });
});
