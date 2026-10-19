import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { integrationsConfig } from "../src/integrations/config.js";
import { webReturnOrigin } from "../src/integrations/returns.js";
import type { GriipClient } from "./support/griipClient.js";
import { expectStatus, type Harness } from "./support/harness.js";
import { browse, sandboxConnect, sandboxWorkspace, startIntegrationServer } from "./support/integrations.js";

/**
 * Coming back to a browser after a sign-in (docs/INTEGRATIONS.md › Connecting an account): the phone keeps its
 * meldiq://integrations link; a web client names its origin, which must be one the operator listed in
 * INTEGRATIONS_WEB_RETURN_URLS, and the page then links to the operator's address for it, never to the request's text.
 */
const WEB = "https://web.meldiq.app";
const WEB_URL = `${WEB}/#/workspace/apps`;
const DEV = "http://localhost:5173";
const int = (_: string, d: number) => d;

let h: Harness;
let owner: GriipClient, member: GriipClient, member2: GriipClient;
let orgId: string;

const connect = (c: GriipClient, body?: unknown) => c.req("POST", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/connect`, body);
const backLink = (html: string) => /<a class="btn" href="([^"]*)"/.exec(html)?.[1].replace(/&amp;/g, "&") ?? null;

beforeAll(async () => {
  h = await startIntegrationServer({ INTEGRATIONS_WEB_RETURN_URLS: `${WEB_URL}, ${DEV}/#/workspace/apps` });
  ({ owner, member, member2, orgId } = await sandboxWorkspace(h, "Web Return Lab"));
  await owner.req("POST", `/v1/orgs/${orgId}/integrations/meldiq-sandbox/install`);
});
afterAll(async () => h.close());

describe("web return addresses (configuration)", () => {
  it("keeps the phone's link and lists no web address by default", () => {
    const c = integrationsConfig({}, "production", int);
    expect(c.appReturnUrl).toBe("meldiq://integrations");
    expect(c.webReturnUrls.size).toBe(0);
  });

  it("keys each address by its origin", () => {
    const c = integrationsConfig({ INTEGRATIONS_WEB_RETURN_URLS: " https://Web.Meldiq.app/#/workspace/apps ,https://staging.meldiq.app/#/workspace/apps" }, "production", int);
    expect([...c.webReturnUrls]).toEqual([
      [WEB, WEB_URL],
      ["https://staging.meldiq.app", "https://staging.meldiq.app/#/workspace/apps"],
    ]);
  });

  it("allows plain http only for a local web app outside production", () => {
    for (const host of ["localhost:5173", "127.0.0.1:5173", "[::1]:5173"]) {
      expect(integrationsConfig({ INTEGRATIONS_WEB_RETURN_URLS: `http://${host}/#/workspace/apps` }, "development", int).webReturnUrls.size).toBe(1);
      expect(() => integrationsConfig({ INTEGRATIONS_WEB_RETURN_URLS: `http://${host}/#/workspace/apps` }, "production", int)).toThrow(/https/);
    }
    expect(() => integrationsConfig({ INTEGRATIONS_WEB_RETURN_URLS: "http://web.meldiq.app/#/workspace/apps" }, "development", int)).toThrow(/https/);
  });

  it("refuses addresses that could leak the finish code or aren't web pages", () => {
    const refused: [string, RegExp][] = [
      ["not a url", /isn't a URL/],
      ["javascript:alert(1)//#/x", /https/],
      ["meldiq://integrations#/x", /https/],
      ["https://user:pass@web.meldiq.app/#/workspace/apps", /user name/],
      ["https://web.meldiq.app/?next=x#/workspace/apps", /query/],
      ["https://web.meldiq.app/", /hash route/],
      ["https://web.meldiq.app/#/workspace/apps?x=1", /hash route/],
      [`${WEB_URL},https://web.meldiq.app/#/settings`, /twice/],
    ];
    for (const [value, message] of refused) {
      expect(() => integrationsConfig({ INTEGRATIONS_WEB_RETURN_URLS: value }, "development", int), value).toThrow(message);
    }
  });

  it("names only listed origins, compared exactly", () => {
    const allowed = new Map([[WEB, WEB_URL]]);
    expect(webReturnOrigin(allowed, undefined)).toBeNull();
    expect(webReturnOrigin(allowed, WEB)).toBe(WEB);
    for (const v of [`${WEB}/`, "https://WEB.MELDIQ.APP", `${WEB}:443`, "https://web.meldiq.app.evil.example"]) {
      expect(() => webReturnOrigin(allowed, v), v).toThrow(/doesn't send sign-ins back/);
    }
    expect(() => webReturnOrigin(new Map(), WEB)).toThrow(/doesn't send sign-ins back/);
  });
});

describe("coming back from a sign-in", () => {
  it("keeps the phone's meldiq://integrations link when no return is named", async () => {
    const r = await sandboxConnect(h, member, orgId);
    expect(r.status).toBe("connected");
    expect(backLink(r.callback.html)).toBe(`meldiq://integrations?flow=${r.flowId}&result=approved&finish=${r.finishCode}`);
  });

  it("returns to an allowed web origin, the finish code inside its hash route", async () => {
    const r = await sandboxConnect(h, member2, orgId, { returnTo: WEB });
    expect(r.status).toBe("connected");
    expect(backLink(r.callback.html)).toBe(`${WEB_URL}?flow=${r.flowId}&result=approved&finish=${r.finishCode}`);
    expect(r.callback.html).not.toContain("meldiq://");
    // Each listed origin gets its own address: a local web app comes back to itself.
    const dev = await sandboxConnect(h, member2, orgId, { returnTo: DEV, skipFinish: true });
    expect(backLink(dev.callback.html)).toBe(`${DEV}/#/workspace/apps?flow=${dev.flowId}&result=approved&finish=${dev.finishCode}`);
  });

  it("sends refused, denied and replayed sign-ins back to the web too", async () => {
    const wrongBrowser = await sandboxConnect(h, member2, orgId, { returnTo: WEB, dropCookie: true });
    expect(backLink(wrongBrowser.callback.html)).toBe(`${WEB_URL}?flow=${wrongBrowser.flowId}&result=failed`);
    const denied = await sandboxConnect(h, member2, orgId, { returnTo: WEB, person: "deny" });
    expect(backLink(denied.callback.html)).toBe(`${WEB_URL}?flow=${denied.flowId}&result=denied`);
    const start = await connect(member2, { return: WEB });
    expect((await browse(h, start.beginUrl)).status).toBe(302);
    const reopened = await browse(h, start.beginUrl);
    expect(reopened.html).toContain("already opened");
    expect(backLink(reopened.html)).toBe(`${WEB_URL}?flow=${start.flowId}&result=used`);
  });

  it("goes back to the phone's link if the origin is no longer listed when the sign-in returns", async () => {
    const urls = h.ctx.config.integrations.webReturnUrls as Map<string, string>;
    const start = await connect(member2, { return: WEB });
    urls.delete(WEB);
    try {
      const reopened = await browse(h, start.beginUrl);
      expect(reopened.status).toBe(302);
      expect(backLink((await browse(h, start.beginUrl)).html)).toBe(`meldiq://integrations?flow=${start.flowId}&result=used`);
    } finally {
      urls.set(WEB, WEB_URL);
    }
  });

  it("refuses unknown origins and other schemes before starting anything", async () => {
    for (const value of [
      "https://evil.example",
      "https://web.meldiq.app.evil.example",
      "http://web.meldiq.app",
      "https://staging.meldiq.app",
      "meldiq://integrations",
      "javascript:alert(document.cookie)",
      "data:text/html,<script>alert(1)</script>",
      "//evil.example",
      "",
    ]) {
      const res = await expectStatus(connect(member2, { return: value }), 400);
      expect([value, res.error.code]).toEqual([value, "return_not_allowed"]);
    }
    for (const value of [null, 42, [WEB], { origin: WEB }]) await expectStatus(connect(member2, { return: value }), 400);
  });

  it("can't be made an open redirect with encodings, user-info or look-alikes", async () => {
    for (const value of [
      `${WEB}@evil.example`,
      `https://evil.example@web.meldiq.app`,
      `https://user:pass@web.meldiq.app`,
      `${WEB}%2F@evil.example`,
      `${WEB}%40evil.example`,
      `${WEB}%2F%2Fevil.example`,
      "https://web%2Emeldiq%2Eapp",
      "%68ttps://web.meldiq.app",
      "https:\\\\evil.example",
      `${WEB}\\@evil.example`,
      `${WEB}/\\evil.example`,
      `${WEB}/`,
      `${WEB}/#/evil`,
      `${WEB}/../evil`,
      `${WEB}?next=https://evil.example`,
      `${WEB}:443`,
      `${WEB}.`,
      `${WEB} `,
      `${WEB}\n`,
      "https://WEB.MELDIQ.APP",
    ]) {
      const res = await expectStatus(connect(member2, { return: value }), 400);
      expect([value, res.error.code]).toEqual([value, "return_not_allowed"]);
    }
  });
});
