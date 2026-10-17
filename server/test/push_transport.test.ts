import { spawnSync } from "node:child_process";
import fs from "node:fs";
import https from "node:https";
import type { AddressInfo } from "node:net";
import os from "node:os";
import path from "node:path";
import type { TLSSocket } from "node:tls";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { postWake, type WakeTarget } from "../src/push/transport.js";

/**
 * The wake-up request on the wire, against a local HTTPS server with a throwaway certificate for "push.test" (made with
 * the openssl command line; skipped where it isn't installed). "push.test" doesn't resolve: requests only arrive
 * because the transport connects to the pinned address it is given.
 */
const hasOpenssl = spawnSync("openssl", ["version"]).status === 0;

interface Seen {
  method?: string;
  url?: string;
  headers: Record<string, string | string[] | undefined>;
  body: Buffer;
  servername?: string | false | null;
}

describe.skipIf(!hasOpenssl)("the HTTPS wake-up request", () => {
  let dir: string;
  let cert: string;
  let server: https.Server;
  let port: number;
  const seen: Seen[] = [];

  beforeAll(async () => {
    dir = fs.mkdtempSync(path.join(os.tmpdir(), "griip-push-tls-"));
    const key = path.join(dir, "key.pem");
    const crt = path.join(dir, "cert.pem");
    const made = spawnSync("openssl", [
      "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes", "-keyout", key, "-out", crt,
      "-days", "1", "-subj", "/CN=push.test", "-addext", "subjectAltName=DNS:push.test",
    ]);
    if (made.status !== 0) throw new Error(`openssl failed: ${made.stderr.toString()}`);
    cert = fs.readFileSync(crt, "utf8");
    server = https.createServer({ key: fs.readFileSync(key), cert }, (req, res) => {
      const chunks: Buffer[] = [];
      req.on("data", (c: Buffer) => chunks.push(c));
      req.on("end", () => {
        seen.push({ method: req.method, url: req.url, headers: req.headers, body: Buffer.concat(chunks), servername: (req.socket as TLSSocket).servername });
        if (req.url?.startsWith("/slow")) return; // never answers
        if (req.url?.startsWith("/redirect")) {
          res.writeHead(301, { location: `https://push.test:${port}/followed` });
          res.end();
          return;
        }
        if (req.url?.startsWith("/gone")) {
          res.writeHead(410);
          res.end();
          return;
        }
        res.writeHead(201);
        res.end("a body the server never reads");
      });
    });
    await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
    port = (server.address() as AddressInfo).port;
  });

  afterAll(async () => {
    server.closeAllConnections();
    await new Promise((r) => server.close(r));
    fs.rmSync(dir, { recursive: true, force: true });
  });

  const target = (p: string, to = port): WakeTarget => ({ url: new URL(`https://push.test:${to}${p}`), address: "127.0.0.1", family: 4 });

  it("POSTs an empty body to the pinned address, with transport headers only", async () => {
    const r = await postWake(target("/upAbc123?up=1"), { timeoutMs: 3000, ttlSeconds: 604_800, ca: cert });
    expect(r).toEqual({ status: 201 });
    const s = seen.at(-1)!;
    expect(s.method).toBe("POST");
    expect(s.url).toBe("/upAbc123?up=1");
    expect(s.body.length).toBe(0);
    expect(s.headers["content-length"]).toBe("0");
    expect(s.headers.host).toBe(`push.test:${port}`);
    expect(s.servername).toBe("push.test"); // TLS name checked against the push host, not the address
    expect(s.headers.ttl).toBe("604800");
    expect(s.headers.urgency).toBe("high");
    for (const name of Object.keys(s.headers)) expect(["connection", "content-length", "host", "ttl", "urgency", "user-agent"]).toContain(name);
  });

  it("returns the push server's status, and never follows a redirect", async () => {
    expect(await postWake(target("/gone"), { timeoutMs: 3000, ttlSeconds: 60, ca: cert })).toEqual({ status: 410 });
    const before = seen.length;
    expect(await postWake(target("/redirect"), { timeoutMs: 3000, ttlSeconds: 60, ca: cert })).toEqual({ status: 301 });
    await new Promise((r) => setTimeout(r, 200));
    expect(seen.length).toBe(before + 1);
    expect(seen.some((s) => s.url === "/followed")).toBe(false);
  });

  it("gives up at its deadline when the push server doesn't answer", async () => {
    const started = Date.now();
    expect(await postWake(target("/slow"), { timeoutMs: 300, ttlSeconds: 60, ca: cert })).toEqual({ error: "timeout" });
    expect(Date.now() - started).toBeLessThan(2000);
  });

  it("refuses a certificate it can't verify", async () => {
    expect(await postWake(target("/upAbc123"), { timeoutMs: 3000, ttlSeconds: 60 })).toEqual({ error: "tls" });
  });

  it("reports a refused connection", async () => {
    const closed = https.createServer();
    await new Promise<void>((r) => closed.listen(0, "127.0.0.1", r));
    const unused = (closed.address() as AddressInfo).port;
    await new Promise((r) => closed.close(r));
    expect(await postWake(target("/upAbc123", unused), { timeoutMs: 3000, ttlSeconds: 60, ca: cert })).toEqual({ error: "refused" });
  });
});
