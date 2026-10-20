import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { open } from "../src/integrations/tokens.js";
import type { GriipClient } from "./support/griipClient.js";
import { expectStatus, type Harness } from "./support/harness.js";
import { browse, finishCodeOf, local, makePlatformAdmin, sandboxConnect, sandboxWorkspace, startIntegrationServer } from "./support/integrations.js";

/**
 * The integrations framework end to end against the Meldiq Sandbox provider (docs/INTEGRATIONS.md › Tests): directory
 * states, install and approval, policy, the OAuth flow and its defences, sealed tokens, refresh and revocation,
 * isolation between people and workspaces, and verified webhooks into channels. No third party is involved: this proves
 * the framework, not any real provider.
 */
let h: Harness;
let owner: GriipClient, admin: GriipClient, member: GriipClient, member2: GriipClient, guest: GriipClient, outsider: GriipClient;
let orgId: string, otherOrg: string, channelId: string, privateChannel: string;

const app = async (c: GriipClient, id = "meldiq-sandbox", org = orgId) => (await c.req("GET", `/v1/orgs/${org}/integrations`)).apps.find((a: any) => a.id === id);
const detail = (c: GriipClient, id = "meldiq-sandbox", org = orgId) => c.req("GET", `/v1/orgs/${org}/integrations/${id}`);
const search = (c: GriipClient, q = "", org = orgId) => c.req("GET", `/v1/orgs/${org}/integrations/meldiq-sandbox/search?q=${encodeURIComponent(q)}`);
const store = () => h.ctx.integrations.store;

beforeAll(async () => {
  h = await startIntegrationServer();
  ({ owner, admin, member, member2, guest, outsider, orgId } = await sandboxWorkspace(h));
  otherOrg = (await outsider.req("POST", "/v1/orgs", { name: "Elsewhere" })).org.id;
  channelId = (await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "dev-updates", scope: "ORG", kind: "PUBLIC", autoJoin: true })).channel.id;
  privateChannel = (await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "leadership", scope: "ORG", kind: "PRIVATE", memberIds: [admin.userId] })).channel.id;
});
afterAll(async () => h.close());

describe("directory", () => {
  it("lists every connector with an honest state: needs configuration without credentials, coming soon for planned ones", async () => {
    const list = (await member.req("GET", `/v1/orgs/${orgId}/integrations?planned=true`)).apps as any[];
    const state = (id: string) => list.find((a) => a.id === id)?.state;
    for (const id of ["google-drive", "google-calendar", "microsoft-onedrive", "microsoft-sharepoint", "microsoft-outlook-calendar", "github", "jira", "notion", "linear"]) expect([id, state(id)]).toEqual([id, "needs_configuration"]);
    for (const id of ["gmail", "outlook-mail", "microsoft-teams", "salesforce", "zapier"]) expect([id, state(id)]).toEqual([id, "coming_soon"]);
    expect(list.find((a) => a.id === "gmail").capabilities).toEqual([]);
    expect(list.find((a) => a.id === "gmail").tier).toBe("planned");
    expect(state("meldiq-sandbox")).toBe("available");
    expect(list.find((a) => a.id === "meldiq-sandbox").actions).toEqual(["request"]);
    expect((await app(admin)).actions).toEqual(["install"]);
    await expectStatus(member.req("POST", `/v1/orgs/${orgId}/integrations/google-drive/install`), 403);
    expect((await expectStatus(owner.req("POST", `/v1/orgs/${orgId}/integrations/google-drive/install`), 409)).error.code).toBe("needs_configuration");
    await expectStatus(owner.req("POST", `/v1/orgs/${orgId}/integrations/gmail/install`), 409);
  });

  it("shows only apps that can be connected unless planned ones are asked for, essential ones marked", async () => {
    const list = (await member.req("GET", `/v1/orgs/${orgId}/integrations`)).apps as any[];
    expect(list.some((a) => a.availability === "coming_soon")).toBe(false);
    const tier = (id: string) => list.find((a) => a.id === id)?.tier;
    for (const id of ["google-drive", "google-calendar", "github", "jira", "notion", "linear", "incoming-webhooks"]) expect([id, tier(id)]).toEqual([id, "essential"]);
    for (const id of ["gitlab", "asana", "zoom", "figma", "microsoft-sharepoint"]) expect([id, tier(id)]).toEqual([id, "optional"]);
  });

  it("is closed to guests by default and invisible to outsiders", async () => {
    await expectStatus(guest.req("GET", `/v1/orgs/${orgId}/integrations`), 403);
    await expectStatus(outsider.req("GET", `/v1/orgs/${orgId}/integrations`), 404);
    await expectStatus(outsider.req("GET", `/v1/orgs/${orgId}/integrations/meldiq-sandbox`), 404);
  });
});

describe("requests, approval and installing", () => {
  it("lets a member request, an admin approve with fewer capabilities, and refuses members installing", async () => {
    await expectStatus(member.req("POST", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/install`), 403);
    await member.req("POST", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/request`, { note: "For the launch files" });
    expect((await app(member)).state).toBe("approval_required");
    expect((await app(admin)).actions).toEqual(["approve", "decline"]);
    const d = await detail(admin);
    expect(d.install).toMatchObject({ state: "requested", requestedByName: expect.any(String), requestNote: "For the launch files" });
    // Approve without "Preview shared links", to prove granted capabilities are enforced.
    await admin.req("POST", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/install`, { capabilities: ["files.search", "files.browse", "files.link", "notify.channels"] });
    const after = await detail(admin);
    expect(after.install.state).toBe("installed");
    expect(after.install.grantedCapabilities).toEqual(["files.browse", "files.link", "files.search", "notify.channels"]);
    expect((await app(member)).state).toBe("installed");
    expect((await app(member)).actions).toEqual(["connect"]);
  });

  it("is idempotent and refuses unknown capabilities", async () => {
    const again = await admin.req("POST", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/install`);
    expect(again.install.state).toBe("installed");
    await expectStatus(admin.req("PATCH", `/v1/orgs/${orgId}/integrations/meldiq-sandbox`, { baseRev: again.install.rev, grantedCapabilities: ["files.delete_everything"] }), 400);
  });
});

describe("connecting an account", () => {
  it("connects through consent with PKCE and a browser-bound flow, finished by the person's own app, and records granted scopes", async () => {
    const r = await sandboxConnect(h, member, orgId);
    expect(r.status).toBe("connected");
    expect(r.callback.status).toBe(200);
    expect(r.callback.html).toContain("finish connecting");
    expect(r.callback.html).toContain("meldiq://integrations?flow=");
    expect(r.finishCode).toMatch(/^[A-Za-z0-9_-]{20,64}$/);
    // The finish code works once: the flow has ended, so a second use just reports how it ended.
    expect((await member.req("POST", `/v1/integrations/flows/${r.flowId}/finish`, { code: r.finishCode })).status).toBe("connected");
    const mine = (await app(member)).myAccount;
    expect(mine).toMatchObject({ status: "active", label: "sam@sandbox.invalid", scopes: ["sandbox.files.read"] });
    expect((await app(member)).state).toBe("connected");
  });

  it("stores only sealed tokens, bound to their account", async () => {
    const acct = (await app(member)).myAccount;
    const creds = (await store().credentials(acct.id))!;
    expect(creds.access_token).toMatch(/^v1\.[0-9a-f]{12}\./);
    expect(creds.access_token).not.toContain("sbx.");
    const ring = h.ctx.config.integrations.keyring!;
    expect(open(ring, creds.access_token, "access-token", acct.id)).toMatch(/^sbx\./);
    expect(() => open(ring, creds.access_token, "access-token", "acct_someone_else")).toThrow();
    // Nothing secret ever comes back through the API.
    expect(JSON.stringify(await detail(owner))).not.toContain(creds.access_token.slice(0, 20));
  });

  it("refuses a callback in a different browser and spends the flow", async () => {
    const r = await sandboxConnect(h, member2, orgId, { dropCookie: true });
    expect(r.status).toBe("failed");
    expect(r.callback.html).toContain("different browser");
    expect(r.finishCode).toBeNull();
    expect((await app(member2)).myAccount).toBeNull();
  });

  it("never attaches someone else's account through a forwarded sign-in link", async () => {
    // member2 starts a sign-in and sends the begin link to member, who opens and approves it in their own browser.
    const r = await sandboxConnect(h, member2, orgId, { person: "alex", skipFinish: true });
    expect(r.status).toBe("approved");
    // The finish code reached member's browser and app; member's Meldiq session isn't the one that asked.
    await expectStatus(member.req("POST", `/v1/integrations/flows/${r.flowId}/finish`, { code: r.finishCode }), 404);
    // member2 can't finish without the code that went to the other browser.
    await expectStatus(member2.req("POST", `/v1/integrations/flows/${r.flowId}/finish`, { code: "x".repeat(32) }), 403);
    expect((await app(member2)).myAccount).toBeNull();
    // Unfinished, it expires.
    await store().rows.update("integration_oauth_states", { id: r.flowId }, { expires_at: Date.now() - 1000 });
    expect((await member2.req("GET", `/v1/integrations/flows/${r.flowId}`)).status).toBe("expired");
    expect((await member2.req("POST", `/v1/integrations/flows/${r.flowId}/finish`, { code: r.finishCode })).status).toBe("failed");
    expect((await app(member2)).myAccount).toBeNull();
  });

  it("refuses reused, unknown and denied sign-ins", async () => {
    const start = await member2.req("POST", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/connect`);
    const begin = await browse(h, start.beginUrl);
    expect(begin.status).toBe(302);
    // The begin link works once.
    expect((await browse(h, start.beginUrl)).html).toContain("already opened");
    // A made-up state is refused.
    expect((await browse(h, `${h.baseUrl}/v1/integrations/oauth/callback?state=${"x".repeat(43)}&code=abc`, begin.cookie)).html).toContain("start this sign-in");
    // Denying at the consent page connects nothing.
    const denied = await sandboxConnect(h, member2, orgId, { person: "deny" });
    expect(denied.status).toBe("denied");
    expect(denied.callback.html).toContain("allow access");
  });

  it("refuses a replayed callback", async () => {
    const start = await member2.req("POST", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/connect`);
    const begin = await browse(h, start.beginUrl);
    const consent = await browse(h, begin.location!);
    const approve = consent.html.match(/href="(\/v1\/integrations\/sandbox\/approve\?[^"]*person=alex)"/)![1].replace(/&amp;/g, "&");
    const cb = (await browse(h, `${h.baseUrl}${approve}`)).location!;
    const first = await browse(h, cb, begin.cookie);
    expect(first.status).toBe(200);
    const replay = await browse(h, cb, begin.cookie);
    expect(replay.html).toContain("already");
    expect(finishCodeOf(replay.html)).toBeNull();
    expect((await member2.req("POST", `/v1/integrations/flows/${start.flowId}/finish`, { code: finishCodeOf(first.html) })).status).toBe("connected");
    expect((await app(member2)).myAccount.label).toBe("alex@sandbox.invalid");
  });

  it("expires sign-ins that weren't finished in time", async () => {
    const start = await member.req("POST", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/connect`);
    await store().rows.update("integration_oauth_states", { id: start.flowId }, { expires_at: Date.now() - 1000 });
    expect((await browse(h, start.beginUrl)).html).toContain("expired");
    expect((await member.req("GET", `/v1/integrations/flows/${start.flowId}`)).status).toBe("expired");
    await expectStatus(member2.req("GET", `/v1/integrations/flows/${start.flowId}`), 404); // someone else's flow
  });
});

describe("using a connected account", () => {
  it("searches, browses and looks items up with the member's own account", async () => {
    expect((await search(member, "launch")).items.map((i: any) => i.title)).toEqual(["Q4 launch plan", "Launch checklist"]);
    const top = await member.req("GET", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/browse`);
    expect(top.items.map((i: any) => i.title)).toEqual(["Projects", "Finance", "Brand guide.pdf"]);
    const inside = await member.req("GET", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/browse?container=sbxf-projects`);
    expect(inside.items).toHaveLength(2);
    const item = await member.req("GET", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/item?id=sbxf-budget`);
    expect(item.resource).toMatchObject({ kind: "workbook", title: "Budget 2026.xlsx", url: "https://sandbox.meldiq.invalid/files/sbxf-budget" });
  });

  it("enforces granted capabilities, connections, membership and workspace boundaries on every call", async () => {
    // Link previews weren't granted at install.
    const unfurl = await member.req("POST", `/v1/orgs/${orgId}/integrations/unfurl`, { url: "https://sandbox.meldiq.invalid/files/sbxf-plan" });
    expect(unfurl).toMatchObject({ connectorId: "meldiq-sandbox", resource: null, reason: "capability_not_granted" });
    // Someone who hasn't connected can't use another person's account.
    expect((await expectStatus(owner.req("GET", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/search?q=x`), 409)).error.code).toBe("not_connected");
    await expectStatus(guest.req("GET", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/search?q=x`), 403);
    await expectStatus(outsider.req("GET", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/search?q=x`), 404);
    // The member's connection belongs to this workspace only.
    await expectStatus(member.req("GET", `/v1/orgs/${otherOrg}/integrations/meldiq-sandbox/search?q=x`), 404);
  });

  it("grants previews once an admin allows them", async () => {
    const d = await detail(admin);
    await admin.req("PATCH", `/v1/orgs/${orgId}/integrations/meldiq-sandbox`, { baseRev: d.install.rev, grantedCapabilities: [...d.install.grantedCapabilities, "files.unfurl"] });
    const unfurl = await member.req("POST", `/v1/orgs/${orgId}/integrations/unfurl`, { url: "https://sandbox.meldiq.invalid/files/sbxf-plan" });
    expect(unfurl.resource.title).toBe("Q4 launch plan");
    const notMine = await owner.req("POST", `/v1/orgs/${orgId}/integrations/unfurl`, { url: "https://sandbox.meldiq.invalid/files/sbxf-plan" });
    expect(notMine).toMatchObject({ resource: null, reason: "not_connected" });
    expect((await member.req("POST", `/v1/orgs/${orgId}/integrations/unfurl`, { url: "https://example.com/x" })).reason).toBe("unsupported");
  });

  it("refreshes an expired token (rotating the refresh token) and marks a revoked one for reconnecting", async () => {
    const acct = (await app(member)).myAccount;
    const before = (await store().credentials(acct.id))!;
    await store().rows.update("integration_credentials", { account_id: acct.id }, { expires_at: Date.now() - 1000 });
    expect((await search(member, "budget")).items).toHaveLength(1);
    const after = (await store().credentials(acct.id))!;
    expect(after.access_token).not.toBe(before.access_token);
    expect(after.refresh_token).not.toBe(before.refresh_token);
    // The provider forgets the grant: the next call can't refresh, so the account needs reconnecting.
    const ring = h.ctx.config.integrations.keyring!;
    const sandbox = h.ctx.integrations.kernel.sandbox!;
    await sandbox.handle("https://sandbox.meldiq.invalid/revoke", { method: "POST", body: new URLSearchParams({ token: open(ring, after.access_token, "access-token", acct.id) }).toString() });
    await sandbox.handle("https://sandbox.meldiq.invalid/revoke", { method: "POST", body: new URLSearchParams({ token: open(ring, after.refresh_token!, "refresh-token", acct.id) }).toString() });
    expect((await expectStatus(search(member, "budget"), 409)).error.code).toBe("reconnect_required");
    expect((await app(member)).state).toBe("error");
    expect((await app(member)).actions).toContain("reconnect");
    expect((await sandboxConnect(h, member, orgId)).status).toBe("connected");
    expect((await app(member)).state).toBe("connected");
  });

  it("limits an app to chosen roles, and a restricting role stops personal connections", async () => {
    const role = (await owner.req("POST", `/v1/orgs/${orgId}/roles`, { name: "Files team", permissions: [] })).role;
    let d = await detail(admin);
    await admin.req("PATCH", `/v1/orgs/${orgId}/integrations/meldiq-sandbox`, { baseRev: d.install.rev, access: { mode: "roles", roleIds: [role.id] } });
    expect((await app(member)).state).toBe("blocked");
    await expectStatus(search(member, "plan"), 403);
    await owner.req("PUT", `/v1/roles/${role.id}/members/${member.userId}`);
    expect((await search(member, "plan")).items.length).toBeGreaterThan(0);
    d = await detail(admin);
    await expectStatus(admin.req("PATCH", `/v1/orgs/${orgId}/integrations/meldiq-sandbox`, { baseRev: d.install.rev, access: { mode: "roles", roleIds: ["rl_not_here"] } }), 400);
    await admin.req("PATCH", `/v1/orgs/${orgId}/integrations/meldiq-sandbox`, { baseRev: d.install.rev, access: { mode: "everyone", roleIds: [] } });
    const ro = (await owner.req("POST", `/v1/orgs/${orgId}/roles`, { template: "read_only" })).role;
    await owner.req("PUT", `/v1/roles/${ro.id}/members/${member.userId}`);
    await expectStatus(search(member, "plan"), 403);
    await owner.req("DELETE", `/v1/roles/${ro.id}/members/${member.userId}`);
    expect((await search(member, "plan")).items.length).toBeGreaterThan(0);
  });

  it("renews once when several requests find the token expired at the same moment (single-use refresh tokens)", async () => {
    const acct = (await app(member)).myAccount;
    await store().rows.update("integration_credentials", { account_id: acct.id }, { expires_at: Date.now() - 1000 });
    const results = await Promise.all([search(member, "budget"), search(member, "budget"), search(member, "plan")]);
    expect(results.every((r) => r.items.length > 0)).toBe(true);
    expect((await app(member)).myAccount.status).toBe("active");
  });

  it("keeps a turned-off app's settings: a member's request can't reset them", async () => {
    let d = await detail(admin);
    const granted = d.install.grantedCapabilities;
    await admin.req("PATCH", `/v1/orgs/${orgId}/integrations/meldiq-sandbox`, { baseRev: d.install.rev, enabled: false });
    expect((await expectStatus(member.req("POST", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/request`, {}), 409)).error.code).toBe("disabled");
    d = await detail(admin);
    expect(d.install).toMatchObject({ state: "disabled", grantedCapabilities: granted });
    await admin.req("PATCH", `/v1/orgs/${orgId}/integrations/meldiq-sandbox`, { baseRev: d.install.rev, enabled: true });
    expect((await detail(admin)).install.state).toBe("installed");
  });
});

describe("policy and platform", () => {
  it("lets the workspace block unlisted apps and turn off requests", async () => {
    const p = await owner.req("GET", `/v1/orgs/${orgId}/integrations/policy`);
    await expectStatus(member.req("PUT", `/v1/orgs/${orgId}/integrations/policy`, { baseRev: p.rev, policy: { blockUnlisted: true } }), 403);
    const set = await owner.req("PUT", `/v1/orgs/${orgId}/integrations/policy`, { baseRev: p.rev, policy: { blockUnlisted: true, allowedConnectors: ["github"], requestsEnabled: false } });
    expect(set.policy).toMatchObject({ blockUnlisted: true, allowedConnectors: ["github"], requestsEnabled: false });
    expect((await app(member)).state).toBe("blocked");
    await expectStatus(search(member, "plan"), 403);
    await expectStatus(owner.req("PUT", `/v1/orgs/${orgId}/integrations/policy`, { baseRev: p.rev, policy: {} }), 409); // stale
    await owner.req("PUT", `/v1/orgs/${orgId}/integrations/policy`, { baseRev: set.rev, policy: { blockUnlisted: false } });
    expect((await app(member)).state).toBe("connected");
  });

  it("keeps the global registry for platform admins, whatever their workspace role", async () => {
    await expectStatus(owner.req("GET", "/v1/platform/integrations"), 403);
    expect((await owner.req("GET", "/v1/platform/me")).platformAdmin).toBe(false);
    makePlatformAdmin(h, member2);
    const reg = await member2.req("GET", "/v1/platform/integrations");
    expect(reg.tokenKey.configured).toBe(true);
    expect(reg.connectors.find((c: any) => c.id === "google-drive")).toMatchObject({ configured: false, missing: expect.arrayContaining(["GOOGLE_OAUTH_CLIENT_ID", "GOOGLE_OAUTH_CLIENT_SECRET"]) });
    expect(JSON.stringify(reg)).not.toContain(h.ctx.config.integrations.keyring!.active.key.toString("base64"));
    await member2.req("PUT", "/v1/platform/integrations", { baseRev: reg.rev, policy: { blocked: ["meldiq-sandbox"] } });
    expect((await app(member)).state).toBe("blocked");
    // Platform power gives nothing inside a workspace: member2 still can't install or configure there.
    await expectStatus(member2.req("POST", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/install`), 403);
    const reg2 = await member2.req("GET", "/v1/platform/integrations");
    await member2.req("PUT", "/v1/platform/integrations", { baseRev: reg2.rev, policy: { blocked: [] } });
    expect((await app(member)).state).toBe("connected");
  });
});

describe("channel notifications", () => {
  it("links the workspace only to what the admin's own account can reach, then posts verified events once", async () => {
    // The admin needs their own sandbox connection first; it proves what they can link.
    expect((await expectStatus(admin.req("GET", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/link-targets`), 409)).error.code).toBe("not_connected");
    expect((await sandboxConnect(h, admin, orgId)).status).toBe("connected");
    const targets = await admin.req("GET", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/link-targets`);
    expect(targets.targets).toEqual([{ id: "sbx-org-1", label: "Sample organisation" }]);
    await expectStatus(admin.req("POST", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/link`, { targetId: "someone-elses-org" }), 403);
    await expectStatus(member.req("POST", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/link`, { targetId: "sbx-org-1" }), 403);
    await admin.req("POST", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/link`, { targetId: "sbx-org-1" });
    expect((await detail(member)).workspaceConnections).toHaveLength(1);

    // Destinations: only live channels of this workspace the admin is in; only resources the connection sees.
    const sub = (body: object) => admin.req("POST", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/subscriptions`, body);
    await expectStatus(sub({ conversationId: channelId, resource: "other/repo", events: ["sample.created"] }), 403);
    await expectStatus(sub({ conversationId: channelId, resource: "sample/one", events: ["nope"] }), 400);
    const dm = (await admin.req("POST", "/v1/conversations/dm", { userId: member.userId })).conversation.id;
    await expectStatus(sub({ conversationId: dm, resource: "sample/one", events: ["sample.created"] }), 404);
    await expectStatus(member.req("POST", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/subscriptions`, { conversationId: channelId, resource: "sample/one", events: ["sample.created"] }), 403);
    const created = (await sub({ conversationId: channelId, resource: "sample/one", events: ["sample.created"] })).subscription;
    expect(created).toMatchObject({ channelName: "dev-updates", resource: "sample/one", active: true });

    const sent = await admin.req("POST", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/test-event`, { resource: "sample/one" });
    expect(sent).toMatchObject({ accepted: true, events: 1 });
    const events = (await member.req("GET", `/v1/conversations/${channelId}/integration-events`)).events;
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ connectorId: "meldiq-sandbox", appName: "Meldiq Sandbox", kind: "sample.created", title: "Sample item created in the sandbox" });
    // Only the channel's members can read it.
    await expectStatus(outsider.req("GET", `/v1/conversations/${channelId}/integration-events`), 404);
  });

  it("refuses unsigned, tampered and stale deliveries, and acknowledges duplicates without posting twice", async () => {
    const sandbox = h.ctx.integrations.kernel.sandbox!;
    const link = (await detail(admin)).workspaceConnections[0];
    expect(link).toBeTruthy();
    const payload = { connection: "sbx-org-1", resource: "sample/one", event: "sample.created", title: "Hook", id: "evt-1" };
    const post = (raw: Buffer, headers: Record<string, string>) =>
      fetch(`${h.baseUrl}/v1/integrations/webhooks/meldiq-sandbox`, { method: "POST", headers: { "content-type": "application/json", ...headers }, body: new Uint8Array(raw) });
    const ok = sandbox.signDelivery(payload);
    expect((await post(ok.raw, ok.headers)).status).toBe(202);
    expect((await post(ok.raw, ok.headers)).status).toBe(200); // the same delivery again: a duplicate
    const tampered = Buffer.from(ok.raw.toString().replace("Hook", "Pwned"));
    expect((await post(tampered, { ...ok.headers, "x-sandbox-delivery": crypto.randomUUID() })).status).toBe(401);
    expect((await post(ok.raw, { ...ok.headers, "x-sandbox-signature": "sha256=" + "0".repeat(64), "x-sandbox-delivery": crypto.randomUUID() })).status).toBe(401);
    const old = sandbox.signDelivery(payload);
    old.headers["x-sandbox-timestamp"] = String(Date.now() - 3_600_000);
    expect((await post(old.raw, { ...old.headers, "x-sandbox-delivery": crypto.randomUUID() })).status).toBe(401);
    // A second, different delivery of the same change (provider redelivery) still posts once.
    const redelivered = sandbox.signDelivery(payload);
    expect((await post(redelivered.raw, redelivered.headers)).status).toBe(202);
    await h.ctx.integrations.runJobs();
    const titles = (await member.req("GET", `/v1/conversations/${channelId}/integration-events`)).events.map((e: any) => e.title);
    expect(titles.filter((t: string) => t === "Hook")).toHaveLength(1);
    expect(titles.includes("Pwned")).toBe(false);
  });

  it("checks again at delivery time: a creator who lost the power pauses the notification", async () => {
    const before = (await member.req("GET", `/v1/conversations/${channelId}/integration-events`)).events.length;
    await owner.req("PATCH", `/v1/orgs/${orgId}/members/${admin.userId}`, { role: "MEMBER" });
    await owner.req("POST", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/test-event`, { resource: "sample/one" });
    expect((await member.req("GET", `/v1/conversations/${channelId}/integration-events`)).events.length).toBe(before);
    const subs = (await owner.req("GET", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/subscriptions`)).subscriptions;
    expect(subs[0]).toMatchObject({ active: false, lastError: "The person who set this up can no longer manage apps" });
    await owner.req("PATCH", `/v1/orgs/${orgId}/members/${admin.userId}`, { role: "ADMIN" });
  });

  it("shows activity to people allowed to see it", async () => {
    const a = await owner.req("GET", `/v1/orgs/${orgId}/integrations/activity`);
    expect(a.audit.map((e: any) => e.action)).toEqual(expect.arrayContaining(["integration.request", "integration.approve", "integration.connect", "integration.link", "integration.subscription.create"]));
    expect(a.notifications.length).toBeGreaterThan(0);
    await expectStatus(member.req("GET", `/v1/orgs/${orgId}/integrations/activity`), 403);
    // The audit never carries tokens or provider account names.
    expect(JSON.stringify(a)).not.toMatch(/sbx\.|sam@sandbox/);
  });
});

describe("disconnecting, leaving and uninstalling", () => {
  it("lets only the owner of a connection (or an uninstaller) disconnect it", async () => {
    const acct = (await app(member2)).myAccount;
    await expectStatus(member.req("DELETE", `/v1/orgs/${orgId}/integration-accounts/${acct.id}`), 403);
    await expectStatus(member2.req("DELETE", `/v1/orgs/${otherOrg}/integration-accounts/${acct.id}`), 404);
    await member2.req("DELETE", `/v1/orgs/${orgId}/integration-accounts/${acct.id}`);
    expect((await app(member2)).myAccount).toBeNull();
    expect(await store().credentials(acct.id)).toBeNull();
  });

  it("removes a person's connections when they leave the workspace", async () => {
    const acct = (await app(member)).myAccount;
    await member.req("DELETE", `/v1/orgs/${orgId}/members/${member.userId}`);
    expect(await store().account(acct.id)).toBeNull();
    expect(await store().credentials(acct.id)).toBeNull();
  });

  it("uninstalls: every connection revoked and removed, notifications stop", async () => {
    await expectStatus(guest.req("DELETE", `/v1/orgs/${orgId}/integrations/meldiq-sandbox`), 403);
    const r = await owner.req("DELETE", `/v1/orgs/${orgId}/integrations/meldiq-sandbox`);
    expect(r.removedConnections).toBeGreaterThanOrEqual(2); // the admin's own and the workspace link
    expect((await store().accounts({ workspace_id: orgId })).length).toBe(0);
    expect((await store().subscriptions({ workspace_id: orgId })).length).toBe(0);
    expect((await app(owner)).state).toBe("available");
  });

  it("forgets everything of a deleted workspace", async () => {
    const lab = await owner.req("POST", "/v1/orgs", { name: "Short lived" });
    await owner.req("POST", `/v1/orgs/${lab.org.id}/integrations/meldiq-sandbox/install`);
    expect((await sandboxConnect(h, owner, lab.org.id)).status).toBe("connected");
    await owner.req("DELETE", `/v1/orgs/${lab.org.id}`, { confirmName: "Short lived" });
    expect(await store().installs(lab.org.id)).toEqual([]);
    expect(await store().accounts({ workspace_id: lab.org.id })).toEqual([]);
  });
});

describe("browser pages", () => {
  it("send strict security headers and no referrer", async () => {
    const res = await fetch(local(h, "http://meldiq.test/v1/integrations/oauth/callback?state=" + "y".repeat(43)));
    expect(res.headers.get("content-security-policy")).toContain("default-src 'none'");
    expect(res.headers.get("referrer-policy")).toBe("no-referrer");
    expect(res.headers.get("x-frame-options")).toBe("DENY");
    expect(res.headers.get("cache-control")).toBe("no-store");
  });
});
