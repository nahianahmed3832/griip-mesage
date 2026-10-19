import crypto from "node:crypto";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { hmacSha256Hex } from "../src/integrations/tokens.js";
import type { GriipClient } from "./support/griipClient.js";
import { expectStatus, type Harness } from "./support/harness.js";
import { FakeProviders, json, providerConnect, providerEnv, type Recorded, tokenAnswer } from "./support/fakeProviders.js";
import { sandboxWorkspace, startIntegrationServer } from "./support/integrations.js";

/**
 * Each connector against a mocked provider (docs/INTEGRATIONS.md › Tests): the exact authorisation request (scopes,
 * PKCE, prompts), the token exchange format, identity, every operation's requests and answers, refresh, error mapping
 * and webhook signatures. Mocked HTTP follows each provider's published API; nothing here talks to a real provider or
 * uses real credentials, so live behaviour still needs testing with configured accounts.
 */
const fake = new FakeProviders();
let h: Harness;
let owner: GriipClient, member: GriipClient, outsider: GriipClient;
let orgId: string, channelId: string;

const install = (id: string, capabilities?: string[]) => owner.req("POST", `/v1/orgs/${orgId}/integrations/${id}/install`, capabilities ? { capabilities } : {});
const form = (r: Recorded) => Object.fromEntries(new URLSearchParams(r.body));
const bearer = (r: Recorded) => r.headers.get("authorization");

beforeAll(async () => {
  h = await startIntegrationServer(providerEnv(), fake.fetch);
  ({ owner, member, outsider, orgId } = await sandboxWorkspace(h, "Provider Lab"));
  channelId = (await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "eng", scope: "ORG", kind: "PUBLIC", autoJoin: true })).channel.id;
});
afterAll(async () => h.close());

describe("configured providers", () => {
  it("are available (not 'needs configuration') once their credentials, the public URL and the token key exist", async () => {
    const apps = (await owner.req("GET", `/v1/orgs/${orgId}/integrations`)).apps as any[];
    for (const id of ["google-drive", "google-calendar", "microsoft-onedrive", "microsoft-sharepoint", "microsoft-outlook-calendar", "github", "jira", "notion", "linear", "incoming-webhooks"]) {
      expect([id, apps.find((a) => a.id === id).state]).toEqual([id, "available"]);
    }
  });
});

describe("Google Drive", () => {
  beforeAll(async () => {
    fake.on("POST", "https://oauth2.googleapis.com/token", (r) => tokenAnswer(fake, r, "form"));
    fake.on("GET", "https://openidconnect.googleapis.com/v1/userinfo", () => ({ sub: "g-123", email: "member@example.com" }));
    await install("google-drive");
  });

  it("asks only for the default capabilities' scopes, offline, with PKCE, and records what Google granted", async () => {
    // Google answers "email" as userinfo.email: normalised back.
    const r = await providerConnect(h, fake, member, orgId, "google-drive", {
      grantScope: "openid https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/drive.metadata.readonly",
    });
    expect(r.status).toBe("connected");
    const q = r.authorize.searchParams;
    expect(r.authorize.origin + r.authorize.pathname).toBe("https://accounts.google.com/o/oauth2/v2/auth");
    expect(q.get("scope")).toBe("email https://www.googleapis.com/auth/drive.metadata.readonly openid");
    expect(q.get("scope")).not.toMatch(/auth\/drive( |$)|drive\.readonly|spreadsheets/);
    expect(q.get("code_challenge_method")).toBe("S256");
    expect(q.get("access_type")).toBe("offline");
    expect(q.get("prompt")).toBe("consent");
    expect(q.get("client_id")).toBe("test-google-client");
    expect(q.get("redirect_uri")).toBe("http://meldiq.test/v1/integrations/oauth/callback");
    const token = form(fake.last("https://oauth2.googleapis.com/token")!);
    expect(token).toMatchObject({ grant_type: "authorization_code", client_id: "test-google-client", client_secret: "test-google-secret" });
    expect(token.code_verifier).toMatch(/^[A-Za-z0-9_-]{43,}$/);
    const app = (await member.req("GET", `/v1/orgs/${orgId}/integrations/google-drive`)).myAccount;
    expect(app).toMatchObject({ status: "active", label: "member@example.com", scopes: ["email", "https://www.googleapis.com/auth/drive.metadata.readonly", "openid"] });
  });

  it("searches with an escaped query across drives and maps file types", async () => {
    fake.on("GET", "https://www.googleapis.com/drive/v3/files", () => ({
      files: [
        { id: "doc1234567890", name: "O'Brien notes", mimeType: "application/vnd.google-apps.document", webViewLink: "https://docs.google.com/document/d/doc1234567890/edit", owners: [{ displayName: "Pat" }] },
        { id: "fold234567890", name: "Plans", mimeType: "application/vnd.google-apps.folder" },
      ],
      nextPageToken: "next-1",
    }));
    const r = await member.req("GET", `/v1/orgs/${orgId}/integrations/google-drive/search?q=${encodeURIComponent("O'Brien")}&filter=documents`);
    const call = fake.last("https://www.googleapis.com/drive/v3/files")!;
    expect(call.url.searchParams.get("q")).toBe("trashed = false and name contains 'O\\'Brien' and mimeType = 'application/vnd.google-apps.document'");
    expect(call.url.searchParams.get("supportsAllDrives")).toBe("true");
    expect(call.url.searchParams.get("corpora")).toBe("allDrives");
    expect(bearer(call)).toMatch(/^Bearer at-/);
    expect(r.items.map((i: any) => [i.kind, i.title])).toEqual([["document", "O'Brien notes"], ["folder", "Plans"]]);
    expect(r.cursor).toBe("next-1");
  });

  it("unfurls a Docs link with the viewer's own access, and refuses ids it can't recognise", async () => {
    fake.on("GET", "https://www.googleapis.com/drive/v3/files/doc1234567890", () => ({ id: "doc1234567890", name: "O'Brien notes", mimeType: "application/vnd.google-apps.document", webViewLink: "https://docs.google.com/document/d/doc1234567890/edit" }));
    const u = await member.req("POST", `/v1/orgs/${orgId}/integrations/unfurl`, { url: "https://docs.google.com/document/d/doc1234567890/edit?usp=sharing" });
    expect(u).toMatchObject({ connectorId: "google-drive", resource: { title: "O'Brien notes", kind: "document" } });
    await expectStatus(member.req("GET", `/v1/orgs/${orgId}/integrations/google-drive/item?id=${encodeURIComponent("../../etc")}`), 404);
  });

  it("refreshes after a 401 and retries once", async () => {
    let first = true;
    fake.on("GET", "https://www.googleapis.com/drive/v3/files/fold234567890", () => {
      if (first) {
        first = false;
        return json(401, { error: { code: 401 } });
      }
      return { id: "fold234567890", name: "Plans", mimeType: "application/vnd.google-apps.folder" };
    });
    const r = await member.req("GET", `/v1/orgs/${orgId}/integrations/google-drive/item?id=fold234567890`);
    expect(r.resource.title).toBe("Plans");
    expect(form(fake.last("https://oauth2.googleapis.com/token")!).grant_type).toBe("refresh_token");
  });

  it("maps provider refusals, rate limits and outages without passing their bodies on", async () => {
    fake.on("GET", "https://www.googleapis.com/drive/v3/files/secret12345678", () => json(403, { error: { message: "private: salary.xlsx" } }));
    const forbidden = await expectStatus(member.req("GET", `/v1/orgs/${orgId}/integrations/google-drive/item?id=secret12345678`), 403);
    expect(forbidden.error.code).toBe("provider_forbidden");
    expect(JSON.stringify(forbidden)).not.toContain("salary");
    fake.on("GET", "https://www.googleapis.com/drive/v3/files/slow123456789", () => json(429, {}, { "retry-after": "7" }));
    const limited = await expectStatus(member.req("GET", `/v1/orgs/${orgId}/integrations/google-drive/item?id=slow123456789`), 429);
    expect(limited.error).toMatchObject({ code: "rate_limited", details: { retryAfterMs: 7000 } });
    fake.on("GET", "https://www.googleapis.com/drive/v3/files/down123456789", () => json(503, {}));
    expect((await expectStatus(member.req("GET", `/v1/orgs/${orgId}/integrations/google-drive/item?id=down123456789`), 503)).error.code).toBe("provider_unavailable");
  });

  it("refuses Sheets details until an admin grants them, then asks for the extra scope only on reconnect", async () => {
    expect((await expectStatus(member.req("GET", `/v1/orgs/${orgId}/integrations/google-drive/details?id=doc1234567890`), 403)).error.code).toBe("capability_not_granted");
    const d = await owner.req("GET", `/v1/orgs/${orgId}/integrations/google-drive`);
    await owner.req("PATCH", `/v1/orgs/${orgId}/integrations/google-drive`, { baseRev: d.install.rev, grantedCapabilities: [...d.install.grantedCapabilities, "sheets.inspect"] });
    // Connected before the grant: the account lacks the scope until the person reconnects.
    expect((await expectStatus(member.req("GET", `/v1/orgs/${orgId}/integrations/google-drive/details?id=doc1234567890`), 403)).error.code).toBe("insufficient_scope");
    const r = await providerConnect(h, fake, member, orgId, "google-drive");
    expect(r.authorize.searchParams.get("scope")).toContain("https://www.googleapis.com/auth/spreadsheets.readonly");
    fake.on("GET", "https://www.googleapis.com/drive/v3/files/sheet123456789", () => ({ id: "sheet123456789", name: "Budget", mimeType: "application/vnd.google-apps.spreadsheet" }));
    fake.on("GET", "https://sheets.googleapis.com/v4/spreadsheets/sheet123456789", () => ({ sheets: [{ properties: { title: "Q1", index: 0, gridProperties: { rowCount: 100, columnCount: 8 } } }] }));
    const det = await member.req("GET", `/v1/orgs/${orgId}/integrations/google-drive/details?id=sheet123456789`);
    expect(det.sections[0]).toEqual({ title: "Tabs", rows: [{ label: "Q1", value: "100 rows × 8 columns" }] });
    expect(fake.last("https://sheets.googleapis.com")!.url.searchParams.get("fields")).not.toContain("values");
  });

  it("revokes the grant at Google when the person disconnects", async () => {
    fake.on("POST", "https://oauth2.googleapis.com/revoke", () => ({}));
    const acct = (await member.req("GET", `/v1/orgs/${orgId}/integrations/google-drive`)).myAccount;
    await member.req("DELETE", `/v1/orgs/${orgId}/integration-accounts/${acct.id}`);
    expect(form(fake.last("https://oauth2.googleapis.com/revoke")!).token).toMatch(/^rt-/);
  });

  it("records a narrower grant and says what's missing", async () => {
    const r = await providerConnect(h, fake, member, orgId, "google-drive", { grantScope: "openid https://www.googleapis.com/auth/userinfo.email" });
    expect(r.status).toBe("connected");
    expect(r.note).toContain("allow everything");
    expect((await expectStatus(member.req("GET", `/v1/orgs/${orgId}/integrations/google-drive/search?q=x`), 403)).error.code).toBe("insufficient_scope");
  });
});

describe("Google Calendar", () => {
  it("lists upcoming events read-only and resolves event links", async () => {
    await install("google-calendar");
    const r = await providerConnect(h, fake, member, orgId, "google-calendar");
    expect(r.authorize.searchParams.get("scope")).toBe("email https://www.googleapis.com/auth/calendar.events.readonly openid");
    fake.on("GET", "https://www.googleapis.com/calendar/v3/calendars/primary/events", () => ({
      items: [{ id: "evt1", summary: "Standup", htmlLink: "https://www.google.com/calendar/event?eid=x", location: "Room 2", start: { dateTime: "2026-10-20T03:00:00Z" }, end: { dateTime: "2026-10-20T03:15:00Z" } }, { id: "evt2", status: "cancelled" }],
    }));
    const list = await member.req("GET", `/v1/orgs/${orgId}/integrations/google-calendar/search`);
    const call = fake.last("https://www.googleapis.com/calendar/v3/calendars/primary/events")!;
    expect(call.url.searchParams.get("singleEvents")).toBe("true");
    expect(call.url.searchParams.get("orderBy")).toBe("startTime");
    expect(list.items).toEqual([expect.objectContaining({ id: "primary~evt1", kind: "event", title: "Standup", detail: "Room 2", startAt: Date.parse("2026-10-20T03:00:00Z") })]);
    const eid = Buffer.from("evt1 member@example.com").toString("base64url");
    fake.on("GET", "https://www.googleapis.com/calendar/v3/calendars/member%40example.com/events/evt1", () => ({ id: "evt1", summary: "Standup", start: { date: "2026-10-20" } }));
    const u = await member.req("POST", `/v1/orgs/${orgId}/integrations/unfurl`, { url: `https://www.google.com/calendar/event?eid=${eid}` });
    expect(u.resource).toMatchObject({ title: "Standup", kind: "event" });
  });

  it("doesn't revoke at Google while the same Google account still backs another connection (Drive)", async () => {
    const revokes = () => fake.calls.filter((c) => c.url.toString().startsWith("https://oauth2.googleapis.com/revoke")).length;
    const before = revokes();
    const acct = (await member.req("GET", `/v1/orgs/${orgId}/integrations/google-calendar`)).myAccount;
    await member.req("DELETE", `/v1/orgs/${orgId}/integration-accounts/${acct.id}`);
    expect(revokes()).toBe(before);
    expect((await member.req("GET", `/v1/orgs/${orgId}/integrations/google-drive`)).myAccount.status).toBe("active");
    expect((await providerConnect(h, fake, member, orgId, "google-calendar")).status).toBe("connected"); // for the next tests
  });
});

describe("Microsoft OneDrive, Excel and SharePoint", () => {
  beforeAll(async () => {
    fake.on("POST", "https://login.microsoftonline.com/organizations/oauth2/v2.0/token", (r) =>
      tokenAnswer(fake, r, "form", { scope: "https://graph.microsoft.com/Files.Read https://graph.microsoft.com/User.Read https://graph.microsoft.com/Sites.Read.All https://graph.microsoft.com/Files.Read.All https://graph.microsoft.com/Calendars.Read" }),
    );
    fake.on("GET", "https://graph.microsoft.com/v1.0/me", () => ({ id: "ms-1", displayName: "Member", userPrincipalName: "member@contoso.com" }));
    await install("microsoft-onedrive");
    await install("microsoft-sharepoint");
  });

  it("signs in to work accounts with delegated, least-privilege scopes and normalises what Microsoft returns", async () => {
    const r = await providerConnect(h, fake, member, orgId, "microsoft-onedrive");
    expect(r.status).toBe("connected");
    expect(r.authorize.pathname).toBe("/organizations/oauth2/v2.0/authorize");
    expect(r.authorize.searchParams.get("scope")).toBe("Files.Read User.Read offline_access");
    expect(form(fake.last("https://login.microsoftonline.com/organizations/oauth2/v2.0/token")!).scope).toBe("Files.Read User.Read offline_access");
    const acct = (await member.req("GET", `/v1/orgs/${orgId}/integrations/microsoft-onedrive`)).myAccount;
    expect(acct.scopes).toEqual(expect.arrayContaining(["Files.Read", "User.Read", "offline_access"]));
  });

  it("searches with an OData-escaped query and only follows page links back to Graph", async () => {
    fake.on("GET", "https://graph.microsoft.com/v1.0/me/drive/root/search", () => ({
      value: [{ id: "item1", name: "Budget.xlsx", webUrl: "https://contoso-my.sharepoint.com/x", file: { mimeType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" }, parentReference: { driveId: "b!drive1", name: "Finance" } }],
      "@odata.nextLink": "https://graph.microsoft.com/v1.0/me/drive/root/search(q='x')?$skiptoken=abc",
    }));
    const r = await member.req("GET", `/v1/orgs/${orgId}/integrations/microsoft-onedrive/search?q=${encodeURIComponent("O'Brien")}`);
    expect(fake.last("https://graph.microsoft.com/v1.0/me/drive/root/search")!.url.pathname).toContain(encodeURIComponent("O''Brien"));
    expect(r.items[0]).toMatchObject({ id: "b!drive1~item1", kind: "workbook", container: "Finance" });
    const foreign = Buffer.from("https://attacker.example/steal").toString("base64url");
    expect((await expectStatus(member.req("GET", `/v1/orgs/${orgId}/integrations/microsoft-onedrive/search?q=x&cursor=${foreign}`), 502)).error.code).toBe("provider_error");
  });

  it("lists an .xlsx workbook's worksheets and tables (names only), and explains consumer OneDrive", async () => {
    fake.on("GET", "https://graph.microsoft.com/v1.0/drives/b!drive1/items/item1", () => ({ id: "item1", name: "Budget.xlsx", parentReference: { driveId: "b!drive1" }, file: {} }));
    fake.on("GET", "https://graph.microsoft.com/v1.0/drives/b!drive1/items/item1/workbook/worksheets", () => ({ value: [{ name: "Summary", position: 0, visibility: "Visible" }, { name: "Raw", position: 1, visibility: "Hidden" }] }));
    fake.on("GET", "https://graph.microsoft.com/v1.0/drives/b!drive1/items/item1/workbook/tables", () => ({ value: [{ name: "Expenses", showHeaders: true }] }));
    const d = await member.req("GET", `/v1/orgs/${orgId}/integrations/microsoft-onedrive/details?id=${encodeURIComponent("b!drive1~item1")}`);
    expect(d.sections).toEqual([
      { title: "Worksheets", rows: [{ label: "Summary", value: "Visible" }, { label: "Raw", value: "Hidden" }] },
      { title: "Tables", rows: [{ label: "Expenses", value: "With headers" }] },
    ]);
    fake.on("GET", "https://graph.microsoft.com/v1.0/drives/b!drive2/items/item2", () => ({ id: "item2", name: "Home.xlsx", parentReference: { driveId: "b!drive2" }, file: {} }));
    fake.on("GET", "https://graph.microsoft.com/v1.0/drives/b!drive2/items/item2/workbook", () => json(404, {}));
    const consumer = await expectStatus(member.req("GET", `/v1/orgs/${orgId}/integrations/microsoft-onedrive/details?id=${encodeURIComponent("b!drive2~item2")}`), 404);
    expect(consumer.error.message).toContain("OneDrive for Business or SharePoint");
    fake.on("GET", "https://graph.microsoft.com/v1.0/drives/b!drive1/items/doc9", () => ({ id: "doc9", name: "Notes.docx", parentReference: { driveId: "b!drive1" }, file: {} }));
    await expectStatus(member.req("GET", `/v1/orgs/${orgId}/integrations/microsoft-onedrive/details?id=${encodeURIComponent("b!drive1~doc9")}`), 404);
  });

  it("resolves sharing links through /shares with the viewer's permissions", async () => {
    fake.on("GET", "https://graph.microsoft.com/v1.0/shares/", () => ({ id: "item1", name: "Budget.xlsx", parentReference: { driveId: "b!drive1" }, file: {} }));
    const link = "https://contoso.sharepoint.com/:x:/s/finance/EabcDEF?e=1";
    const u = await member.req("POST", `/v1/orgs/${orgId}/integrations/unfurl`, { url: link });
    expect(u.resource.title).toBe("Budget.xlsx");
    expect(fake.last("https://graph.microsoft.com/v1.0/shares/")!.url.pathname).toBe(`/v1.0/shares/u!${Buffer.from(link).toString("base64url")}/driveItem`);
  });

  it("browses SharePoint: followed sites, then a site's libraries", async () => {
    const r = await providerConnect(h, fake, member, orgId, "microsoft-sharepoint");
    expect(r.authorize.searchParams.get("scope")).toBe("Files.Read.All Sites.Read.All User.Read offline_access");
    fake.on("GET", "https://graph.microsoft.com/v1.0/me/followedSites", () => ({ value: [{ id: "contoso.sharepoint.com,1111,2222", displayName: "Finance" }] }));
    fake.on("GET", "https://graph.microsoft.com/v1.0/sites/contoso.sharepoint.com,1111,2222/drives", () => ({ value: [{ id: "b!lib1", name: "Documents" }] }));
    const sites = await member.req("GET", `/v1/orgs/${orgId}/integrations/microsoft-sharepoint/browse`);
    expect(sites.items[0]).toMatchObject({ kind: "site", title: "Finance", id: "site~contoso.sharepoint.com,1111,2222" });
    const libs = await member.req("GET", `/v1/orgs/${orgId}/integrations/microsoft-sharepoint/browse?container=${encodeURIComponent(sites.items[0].id)}`);
    expect(libs.items[0]).toMatchObject({ kind: "library", id: "lib~b!lib1" });
    await expectStatus(member.req("GET", `/v1/orgs/${orgId}/integrations/microsoft-sharepoint/browse?container=${encodeURIComponent("site~../../me")}`), 404);
  });

  it("reports each worksheet's used range (address and size), never its values", async () => {
    fake.on("GET", "https://graph.microsoft.com/v1.0/drives/b!drive1/items/item1/workbook/worksheets/Summary/usedRange(valuesOnly=true)", () => ({ address: "Summary!A1:H120", rowCount: 120, columnCount: 8 }));
    fake.on("GET", "https://graph.microsoft.com/v1.0/drives/b!drive1/items/item1/workbook/worksheets/Raw/usedRange(valuesOnly=true)", () => ({ address: "Raw!A1:B2", rowCount: 2, columnCount: 2 }));
    const d = await member.req("GET", `/v1/orgs/${orgId}/integrations/microsoft-onedrive/details?id=${encodeURIComponent("b!drive1~item1")}`);
    expect(d.sections[0].rows).toEqual([{ label: "Summary", value: "A1:H120 · 120 rows × 8 columns" }, { label: "Raw", value: "Hidden" }]);
    const rangeCalls = fake.calls.filter((c) => c.url.pathname.includes("usedRange"));
    expect(rangeCalls.every((c) => c.url.searchParams.get("$select") === "address,rowCount,columnCount")).toBe(true);
    expect(rangeCalls.some((c) => c.url.pathname.includes("/Raw/"))).toBe(false); // hidden sheets aren't read
  });

  it("lists upcoming Outlook events in UTC, read-only", async () => {
    await install("microsoft-outlook-calendar");
    const r = await providerConnect(h, fake, member, orgId, "microsoft-outlook-calendar");
    expect(r.status).toBe("connected");
    expect(r.authorize.searchParams.get("scope")).toBe("Calendars.Read User.Read offline_access");
    fake.on("GET", "https://graph.microsoft.com/v1.0/me/calendarView", () => ({
      value: [
        { id: "AAMkAGI2TAAA=", subject: "Planning", start: { dateTime: "2026-10-20T09:00:00.0000000" }, end: { dateTime: "2026-10-20T10:00:00.0000000" }, location: { displayName: "Room 4" } },
        { id: "AAMkAGI2TBBB=", subject: "Cancelled", isCancelled: true, start: { dateTime: "2026-10-21T09:00:00" } },
      ],
    }));
    const list = await member.req("GET", `/v1/orgs/${orgId}/integrations/microsoft-outlook-calendar/search`);
    const call = fake.last("https://graph.microsoft.com/v1.0/me/calendarView")!;
    expect(call.headers.get("prefer")).toBe('outlook.timezone="UTC"');
    expect(list.items).toEqual([expect.objectContaining({ title: "Planning", detail: "Room 4", startAt: Date.parse("2026-10-20T09:00:00Z") })]);
    const filtered = await member.req("GET", `/v1/orgs/${orgId}/integrations/microsoft-outlook-calendar/search?q=plan`);
    expect(filtered.items).toHaveLength(1);
  });
});

describe("Google Calendar events", () => {
  it("creates an event only with the granted write capability and a reconnect for its scope", async () => {
    const create = (fields: Record<string, string>) => member.req("POST", `/v1/orgs/${orgId}/integrations/google-calendar/actions/create_event`, { fields });
    const fields = { summary: "Design review", start: "2026-10-22T03:00:00Z", end: "2026-10-22T04:00:00Z", location: "Room 2" };
    expect((await expectStatus(create(fields), 403)).error.code).toBe("capability_not_granted");
    const d = await owner.req("GET", `/v1/orgs/${orgId}/integrations/google-calendar`);
    await owner.req("PATCH", `/v1/orgs/${orgId}/integrations/google-calendar`, { baseRev: d.install.rev, grantedCapabilities: [...d.install.grantedCapabilities, "events.create"] });
    expect((await expectStatus(create(fields), 403)).error.code).toBe("insufficient_scope");
    const r = await providerConnect(h, fake, member, orgId, "google-calendar");
    expect(r.authorize.searchParams.get("scope")).toContain("https://www.googleapis.com/auth/calendar.events ");
    fake.on("POST", "https://www.googleapis.com/calendar/v3/calendars/primary/events", (req) => ({ id: "evtNew", ...JSON.parse(req.body) }));
    await expectStatus(create({ ...fields, end: "2026-10-21T00:00:00Z" }), 400); // ends before it starts
    await expectStatus(create({ ...fields, summary: "" }), 400);
    const made = await create(fields);
    expect(made.resource).toMatchObject({ id: "primary~evtNew", title: "Design review", detail: "Room 2" });
    const sent = JSON.parse(fake.calls.filter((c) => c.method === "POST" && c.url.pathname.endsWith("/calendars/primary/events")).pop()!.body);
    expect(sent).toEqual({ summary: "Design review", location: "Room 2", start: { dateTime: "2026-10-22T03:00:00.000Z" }, end: { dateTime: "2026-10-22T04:00:00.000Z" } });
  });
});

describe("GitHub", () => {
  const sign = (body: string) => `sha256=${hmacSha256Hex("test-github-webhook-secret", Buffer.from(body))}`;
  const hook = (event: string, payload: object, opts: { signature?: string; delivery?: string } = {}) => {
    const body = JSON.stringify(payload);
    return fetch(`${h.baseUrl}/v1/integrations/webhooks/github`, {
      method: "POST",
      headers: { "content-type": "application/json", "x-github-event": event, "x-github-delivery": opts.delivery ?? crypto.randomUUID(), "x-hub-signature-256": opts.signature ?? sign(body) },
      body,
    });
  };

  beforeAll(async () => {
    fake.on("POST", "https://github.com/login/oauth/access_token", (r) => tokenAnswer(fake, r, "form", { scope: "", expires_in: 28800 }));
    fake.on("GET", "https://api.github.com/user", () => ({ id: 777, login: "octo-owner" }));
    fake.on("GET", "https://api.github.com/user/installations", () => ({
      installations: [{ id: 9001, app_id: 4242, account: { login: "orbit-labs", type: "Organization" } }, { id: 9002, app_id: 1, account: { login: "other-app-install" } }],
    }));
    // The installation also holds orbit-labs/secret, which this admin can't read: GitHub leaves it out of the list for
    // their own token (the installation token, which would include it, is never used).
    fake.on("GET", "https://api.github.com/user/installations/9001/repositories", (r) =>
      bearer(r)?.startsWith("Bearer at-") ? { total_count: 2, repositories: [{ full_name: "orbit-labs/app", private: true }, { full_name: "orbit-labs/site" }] } : json(401, {}),
    );
    fake.on("DELETE", "https://api.github.com/applications/Iv1.testclient/token", () => new Response(null, { status: 204 }));
    await install("github");
  });

  it("connects a member with PKCE and no scopes (the app's permissions decide), and searches their issues", async () => {
    const r = await providerConnect(h, fake, owner, orgId, "github");
    expect(r.status).toBe("connected");
    expect(r.authorize.origin + r.authorize.pathname).toBe("https://github.com/login/oauth/authorize");
    expect(r.authorize.searchParams.get("scope")).toBeNull();
    expect(r.authorize.searchParams.get("code_challenge")).toBeTruthy();
    fake.on("GET", "https://api.github.com/search/issues", () => ({ items: [{ number: 12, title: "Fix login", html_url: "https://github.com/orbit-labs/app/pull/12", state: "open", repository_url: "https://api.github.com/repos/orbit-labs/app", pull_request: {} }] }));
    const s = await owner.req("GET", `/v1/orgs/${orgId}/integrations/github/search`);
    expect(fake.last("https://api.github.com/search/issues")!.url.searchParams.get("q")).toBe("involves:octo-owner is:open");
    expect(fake.last("https://api.github.com/search/issues")!.headers.get("x-github-api-version")).toBe("2022-11-28");
    expect(s.items[0]).toMatchObject({ id: "orbit-labs/app#12", kind: "pull_request", title: "#12 Fix login", container: "orbit-labs/app" });
  });

  it("links only installations of this app the admin can reach, and offers only repositories the admin's own token can read", async () => {
    const t = await owner.req("GET", `/v1/orgs/${orgId}/integrations/github/link-targets`);
    expect(t.targets).toEqual([{ id: "9001", label: "orbit-labs (organisation)" }]);
    expect(t.installUrl).toBe("https://github.com/apps/orbit-test-app/installations/new");
    await expectStatus(owner.req("POST", `/v1/orgs/${orgId}/integrations/github/link`, { targetId: "9002" }), 403);
    await owner.req("POST", `/v1/orgs/${orgId}/integrations/github/link`, { targetId: "9001" });
    const repos = await owner.req("GET", `/v1/orgs/${orgId}/integrations/github/watchable`);
    expect(repos.items).toEqual([{ id: "orbit-labs/app", label: "orbit-labs/app (private)" }, { id: "orbit-labs/site", label: "orbit-labs/site" }]);
    expect(bearer(fake.last("https://api.github.com/user/installations/9001/repositories")!)).toMatch(/^Bearer at-/);
    const sub = (resource: string) => owner.req("POST", `/v1/orgs/${orgId}/integrations/github/subscriptions`, { conversationId: channelId, resource, events: ["pull_request.opened", "pull_request.merged", "workflow_run.failure"] });
    await expectStatus(sub("orbit-labs/secret"), 403);
    await sub("orbit-labs/app");
    // Saving the same channel and repository again changes the one subscription (a unique key, also under a race).
    await Promise.all([sub("orbit-labs/app"), sub("orbit-labs/app")]);
    expect((await owner.req("GET", `/v1/orgs/${orgId}/integrations/github/subscriptions`)).subscriptions).toHaveLength(1);
  });

  it("verifies webhook signatures and posts only subscribed events from linked installations", async () => {
    const pr = (action: string, merged = false) => ({
      action,
      installation: { id: 9001 },
      repository: { full_name: "orbit-labs/app" },
      sender: { login: "dev1" },
      pull_request: { number: 12, title: "Fix login", html_url: "https://github.com/orbit-labs/app/pull/12", merged, updated_at: `2026-10-19T0${action.length % 9}:00:00Z` },
    });
    expect((await hook("pull_request", pr("opened"), { signature: "sha256=" + "a".repeat(64) })).status).toBe(401);
    const delivery = crypto.randomUUID();
    expect((await hook("pull_request", pr("opened"), { delivery })).status).toBe(202);
    expect((await hook("pull_request", pr("opened"), { delivery })).status).toBe(200); // duplicate delivery
    expect((await hook("pull_request", pr("closed", true))).status).toBe(202);
    expect((await hook("pull_request", pr("closed", false))).status).toBe(202); // closed without merge: not subscribed
    expect((await hook("issues", { action: "opened", installation: { id: 9001 }, repository: { full_name: "orbit-labs/app" }, issue: { number: 3, title: "x" } })).status).toBe(202);
    // Another installation's events never reach this workspace.
    expect((await hook("pull_request", { ...pr("opened"), installation: { id: 9002 }, pull_request: { ...pr("opened").pull_request, number: 99 } })).status).toBe(202);
    expect((await hook("ping", { zen: "hi", hook_id: 1 })).status).toBe(202);
    await h.ctx.integrations.runJobs();
    const events = (await member.req("GET", `/v1/conversations/${channelId}/integration-events`)).events;
    expect(events.map((e: any) => [e.kind, e.title])).toEqual([["pull_request.opened", "#12 Fix login"], ["pull_request.merged", "#12 Fix login"]]);
    expect(events[0]).toMatchObject({ actor: "dev1", url: "https://github.com/orbit-labs/app/pull/12", summary: "Pull request opened in orbit-labs/app" });
    await expectStatus(outsider.req("GET", `/v1/conversations/${channelId}/integration-events`), 404);
  });
});

describe("Jira", () => {
  beforeAll(async () => {
    fake.on("POST", "https://auth.atlassian.com/oauth/token", (r) => tokenAnswer(fake, r, "json", { scope: "read:jira-work read:me offline_access" }));
    fake.on("GET", "https://api.atlassian.com/me", () => ({ account_id: "jira-acc-1", email: "member@example.com" }));
    fake.on("GET", "https://api.atlassian.com/oauth/token/accessible-resources", () => [{ id: "11111111-2222-3333-4444-555555555555", url: "https://meldiq.atlassian.net", name: "Meldiq", scopes: ["read:jira-work"] }]);
    await install("jira");
  });

  it("uses the Atlassian audience and a JSON token request, then finds the Jira site", async () => {
    const r = await providerConnect(h, fake, member, orgId, "jira");
    expect(r.status).toBe("connected");
    expect(r.authorize.searchParams.get("audience")).toBe("api.atlassian.com");
    expect(r.authorize.searchParams.get("scope")).toBe("offline_access read:jira-work read:me");
    const token = JSON.parse(fake.last("https://auth.atlassian.com/oauth/token")!.body);
    expect(token).toMatchObject({ grant_type: "authorization_code", client_id: "test-atlassian-client", redirect_uri: "http://meldiq.test/v1/integrations/oauth/callback" });
  });

  it("searches with JQL on the person's site and links issues back to it", async () => {
    fake.on("POST", "https://api.atlassian.com/ex/jira/11111111-2222-3333-4444-555555555555/rest/api/3/search/jql", () => ({
      issues: [{ key: "ENG-7", fields: { summary: "Crash on start", status: { name: "In Progress" }, assignee: { displayName: "Pat" }, project: { name: "Engineering" } } }],
    }));
    const s = await member.req("GET", `/v1/orgs/${orgId}/integrations/jira/search?q=${encodeURIComponent('say "hi"')}`);
    expect(JSON.parse(fake.last("https://api.atlassian.com/ex/jira/")!.body).jql).toBe('text ~ "say \\"hi\\"" ORDER BY updated DESC');
    expect(s.items[0]).toMatchObject({ id: "ENG-7", title: "ENG-7 Crash on start", url: "https://meldiq.atlassian.net/browse/ENG-7", status: "In Progress" });
    await member.req("GET", `/v1/orgs/${orgId}/integrations/jira/search?q=eng-7`);
    expect(JSON.parse(fake.last("https://api.atlassian.com/ex/jira/")!.body).jql).toBe('key = "ENG-7"');
  });

  it("previews issue links only from the person's own Jira site", async () => {
    fake.on("GET", "https://api.atlassian.com/ex/jira/11111111-2222-3333-4444-555555555555/rest/api/3/issue/ENG-7", () => ({ key: "ENG-7", fields: { summary: "Crash on start" } }));
    const mine = await member.req("POST", `/v1/orgs/${orgId}/integrations/unfurl`, { url: "https://meldiq.atlassian.net/browse/ENG-7" });
    expect(mine.resource).toMatchObject({ id: "ENG-7", title: "ENG-7 Crash on start" });
    const calls = fake.calls.length;
    // The same key on another site is another issue: nothing is looked up or shown.
    const other = await member.req("POST", `/v1/orgs/${orgId}/integrations/unfurl`, { url: "https://someone-else.atlassian.net/browse/ENG-7" });
    expect(other).toMatchObject({ connectorId: "jira", resource: null });
    expect(fake.calls.length).toBe(calls);
  });

  it("keeps creating issues off until granted, then creates exactly what the person confirmed", async () => {
    const create = (fields: Record<string, string>) => member.req("POST", `/v1/orgs/${orgId}/integrations/jira/actions/create_issue`, { fields });
    expect((await expectStatus(create({ projectKey: "ENG", summary: "From chat" }), 403)).error.code).toBe("capability_not_granted");
    const d = await owner.req("GET", `/v1/orgs/${orgId}/integrations/jira`);
    await owner.req("PATCH", `/v1/orgs/${orgId}/integrations/jira`, { baseRev: d.install.rev, grantedCapabilities: [...d.install.grantedCapabilities, "issues.create"] });
    fake.reset("https://auth.atlassian.com/oauth/token");
    fake.on("POST", "https://auth.atlassian.com/oauth/token", (r) => tokenAnswer(fake, r, "json", { scope: "read:jira-work write:jira-work read:me offline_access" }));
    const again = await providerConnect(h, fake, member, orgId, "jira");
    expect(again.authorize.searchParams.get("scope")).toBe("offline_access read:jira-work read:me write:jira-work");
    fake.on("POST", "https://api.atlassian.com/ex/jira/11111111-2222-3333-4444-555555555555/rest/api/3/issue", () => ({ key: "ENG-8" }));
    fake.on("GET", "https://api.atlassian.com/ex/jira/11111111-2222-3333-4444-555555555555/rest/api/3/issue/ENG-8", () => ({ key: "ENG-8", fields: { summary: "From chat" } }));
    await expectStatus(create({ projectKey: "eng!", summary: "x" }), 400);
    const made = await create({ projectKey: "ENG", summary: "From chat", description: "line one\nline two" });
    expect(made.resource).toMatchObject({ id: "ENG-8" });
    const sent = JSON.parse(fake.calls.filter((c) => c.method === "POST" && c.url.pathname.endsWith("/rest/api/3/issue")).pop()!.body);
    expect(sent.fields).toMatchObject({ project: { key: "ENG" }, summary: "From chat", issuetype: { name: "Task" } });
    expect(sent.fields.description.content).toHaveLength(2);
    const audit = await owner.req("GET", `/v1/orgs/${orgId}/audit?action=integration&limit=100`);
    expect(audit.entries.find((e: any) => e.action === "integration.action").detail).toBe("create_issue ENG-8");
  });

  it("stores Atlassian's rotated refresh token", async () => {
    const acct = (await member.req("GET", `/v1/orgs/${orgId}/integrations/jira`)).myAccount;
    const before = (await h.ctx.integrations.store.credentials(acct.id))!;
    await h.ctx.integrations.store.rows.update("integration_credentials", { account_id: acct.id }, { expires_at: Date.now() - 1 });
    await member.req("GET", `/v1/orgs/${orgId}/integrations/jira/search?q=x`);
    const refresh = JSON.parse(fake.last("https://auth.atlassian.com/oauth/token")!.body);
    expect(refresh.grant_type).toBe("refresh_token");
    const after = (await h.ctx.integrations.store.credentials(acct.id))!;
    expect(after.refresh_token).not.toBe(before.refresh_token);
  });
});

describe("Notion", () => {
  it("authenticates the token request with HTTP Basic, keeps the workspace, and searches shared pages", async () => {
    fake.on("POST", "https://api.notion.com/v1/oauth/token", (r) => {
      if (bearer(r) !== `Basic ${Buffer.from("test-notion-client:test-notion-secret").toString("base64")}`) return json(401, {});
      return tokenAnswer(fake, r, "json", { bot_id: "bot-1", workspace_id: "ws-1", workspace_name: "Acme", owner: { user: { id: "user-1", person: { email: "member@example.com" } } } });
    });
    fake.on("GET", "https://api.notion.com/v1/users/me", () => ({ id: "bot-1", object: "user" }));
    await install("notion");
    const r = await providerConnect(h, fake, member, orgId, "notion");
    expect(r.status).toBe("connected");
    expect(r.authorize.searchParams.get("owner")).toBe("user");
    const acct = (await member.req("GET", `/v1/orgs/${orgId}/integrations/notion`)).myAccount;
    expect(acct.label).toBe("member@example.com");
    fake.on("POST", "https://api.notion.com/v1/search", () => ({
      results: [{ object: "page", id: "0123456789abcdef0123456789abcdef", url: "https://www.notion.so/Roadmap-0123456789abcdef0123456789abcdef", properties: { Name: { type: "title", title: [{ plain_text: "Road" }, { plain_text: "map" }] } } }],
      has_more: false,
    }));
    const s = await member.req("GET", `/v1/orgs/${orgId}/integrations/notion/search?q=road`);
    expect(fake.last("https://api.notion.com/v1/search")!.headers.get("notion-version")).toBe("2022-06-28");
    expect(s.items[0]).toMatchObject({ kind: "page", title: "Roadmap" });
  });
});

describe("Linear", () => {
  it("connects with PKCE and searches issues through GraphQL", async () => {
    fake.on("POST", "https://api.linear.app/oauth/token", (r) => tokenAnswer(fake, r, "form", { scope: "read" }));
    fake.on("POST", "https://api.linear.app/graphql", (r) => {
      const q = JSON.parse(r.body).query as string;
      if (q.includes("viewer { id")) return { data: { viewer: { id: "lin-1", email: "member@example.com" }, organization: { id: "org-1", name: "Acme" } } };
      return { data: { issues: { nodes: [{ identifier: "ENG-3", title: "Polish", url: "https://linear.app/acme/issue/ENG-3/polish", state: { name: "Todo" } }], pageInfo: { hasNextPage: false } } } };
    });
    await install("linear");
    const r = await providerConnect(h, fake, member, orgId, "linear");
    expect(r.status).toBe("connected");
    expect(r.authorize.searchParams.get("scope")).toBe("read");
    expect(r.authorize.searchParams.get("code_challenge_method")).toBe("S256");
    const s = await member.req("GET", `/v1/orgs/${orgId}/integrations/linear/search?q=polish`);
    expect(JSON.parse(fake.last("https://api.linear.app/graphql")!.body).variables).toEqual({ q: "polish", after: null });
    expect(s.items[0]).toMatchObject({ id: "ENG-3", status: "Todo" });
  });
});
