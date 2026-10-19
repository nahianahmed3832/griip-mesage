/**
 * WEB_ORIGINS (Orbit Web on another host): listed origins get CORS answers and may open the WebSocket; others get no
 * CORS headers and a closed socket; apps without an Origin are unaffected. Empty list: the previous behaviour.
 */
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import WebSocket from "ws";
import { webOrigins } from "../src/config.js";
import type { GriipClient } from "./support/griipClient.js";
import { type Harness, startServer } from "./support/harness.js";

let h: Harness;
let plain: Harness;
let alice: GriipClient;
const WEB = "https://web.example.test";

beforeAll(async () => {
  h = await startServer({ WEB_ORIGINS: `${WEB}, http://localhost:5173` });
  plain = await startServer();
  alice = await h.client("origins");
});
afterAll(async () => {
  await h.close();
  await plain.close();
});

function open(base: string, origin: string | undefined, token: string): Promise<{ code?: number; hello: boolean }> {
  return new Promise((resolve) => {
    const ws = new WebSocket(base.replace(/^http/, "ws") + "/v1/ws", origin ? { headers: { origin } } : {});
    let hello = false;
    ws.on("open", () => ws.send(JSON.stringify({ t: "auth", token })));
    ws.on("message", (d) => {
      if (JSON.parse(d.toString()).t === "hello") {
        hello = true;
        ws.close();
      }
    });
    ws.on("close", (code) => resolve({ code, hello }));
  });
}

describe("WEB_ORIGINS", () => {
  it("answers a listed origin's preflight and requests with CORS headers, and nobody else's", async () => {
    const pre = await fetch(`${h.baseUrl}/v1/users/me`, { method: "OPTIONS", headers: { origin: WEB, "access-control-request-method": "GET", "access-control-request-headers": "authorization" } });
    expect(pre.status).toBe(204);
    expect(pre.headers.get("access-control-allow-origin")).toBe(WEB);
    expect(pre.headers.get("access-control-allow-headers")).toContain("authorization");
    expect(pre.headers.get("access-control-allow-credentials")).toBeNull();
    const ok = await fetch(`${h.baseUrl}/v1/users/me`, { headers: { origin: WEB, authorization: `Bearer ${alice.token}` } });
    expect(ok.status).toBe(200);
    expect(ok.headers.get("access-control-allow-origin")).toBe(WEB);
    const other = await fetch(`${h.baseUrl}/v1/users/me`, { headers: { origin: "https://evil.example", authorization: `Bearer ${alice.token}` } });
    expect(other.headers.get("access-control-allow-origin")).toBeNull();
    // Without the setting there are no CORS answers at all.
    const none = await fetch(`${plain.baseUrl}/v1/server-info`, { headers: { origin: WEB } });
    expect(none.headers.get("access-control-allow-origin")).toBeNull();
  });

  it("lets listed origins and apps (no Origin) open the WebSocket, and closes others", async () => {
    expect(await open(h.baseUrl, WEB, alice.token)).toMatchObject({ hello: true });
    expect(await open(h.baseUrl, undefined, alice.token)).toMatchObject({ hello: true });
    expect(await open(h.baseUrl, "https://evil.example", alice.token)).toMatchObject({ code: 4403, hello: false });
  });

  it("accepts only https origins without a path (http only for localhost)", () => {
    expect(webOrigins("https://a.example, https://a.example/ ,http://localhost:5173")).toEqual(["https://a.example", "http://localhost:5173"]);
    expect(webOrigins(undefined)).toEqual([]);
    expect(() => webOrigins("http://a.example")).toThrow();
    expect(() => webOrigins("https://a.example/app")).toThrow();
    expect(() => webOrigins("*")).toThrow();
  });
});
