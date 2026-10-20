import { describe, expect, it } from "vitest";
import { errorKind, Metrics, p95 } from "../src/ops/metrics.js";

describe("operational metrics (round 7, C1)", () => {
  it("counts requests by route template, with error classes and latency", () => {
    let t = 1_000_000;
    const m = new Metrics(() => t);
    m.recordRequest("GET", "/v1/conversations/:id", 200, 12);
    m.recordRequest("GET", "/v1/conversations/:id", 404, 8);
    m.recordRequest("GET", "/v1/conversations/:id", 500, 700);
    const r = m.snapshot().routes[0];
    expect(r).toMatchObject({ route: "GET /v1/conversations/:id", count: 3, clientErrors: 1, serverErrors: 1, maxMs: 700 });
    expect(r.avgMs).toBe(240);
    t += 1;
    expect(m.snapshot().window).toMatchObject({ requests: 3, serverErrors: 1 });
  });

  it("the window forgets minutes older than fifteen", () => {
    let t = 0;
    const m = new Metrics(() => t);
    m.recordRequest("GET", "/healthz", 500, 5);
    t += 16 * 60_000;
    m.recordRequest("GET", "/healthz", 200, 5);
    const w = m.snapshot().window;
    expect(w.requests).toBe(1);
    expect(w.serverErrors).toBe(0);
    expect(w.errorRate).toBe(0);
    // Totals since the start keep everything.
    expect(m.snapshot().routes[0].count).toBe(2);
  });

  it("p95 is the bucket bound where 95 % of requests fall", () => {
    expect(p95([0, 0, 0, 0, 0, 0, 0, 0, 0, 0])).toBeNull();
    expect(p95([95, 5, 0, 0, 0, 0, 0, 0, 0, 0])).toBe(10);
    expect(p95([90, 0, 0, 0, 0, 10, 0, 0, 0, 0])).toBe(500);
  });

  it("jobs and providers record runs and failure kinds, never messages", async () => {
    let t = 50;
    const m = new Metrics(() => t);
    await m.timeJob("cleanup", async () => 1);
    t = 60;
    await expect(m.timeJob("cleanup", async () => { throw Object.assign(new Error("secret user@example.com"), { code: "SQLITE_BUSY" }); })).rejects.toThrow();
    const job = m.snapshot().jobs[0];
    expect(job).toMatchObject({ name: "cleanup", runs: 2, failures: 1, lastFailureAt: 60, lastErrorKind: "SQLITE_BUSY" });
    m.recordProvider("mail.smtp");
    m.recordProvider("mail.smtp", new TypeError("connect ECONNREFUSED 10.0.0.1"));
    expect(m.snapshot().providers[0]).toMatchObject({ name: "mail.smtp", ok: 1, failed: 1, lastErrorKind: "TypeError" });
    expect(JSON.stringify(m.snapshot())).not.toMatch(/example\.com|10\.0\.0\.1/);
  });

  it("an error's kind is its code or class, else just 'error'", () => {
    expect(errorKind(new Error("x"))).toBe("error");
    expect(errorKind({ code: "ETIMEDOUT" })).toBe("ETIMEDOUT");
    expect(errorKind({ code: "has spaces and data" })).toBe("error");
    expect(errorKind("text")).toBe("error");
  });
});
