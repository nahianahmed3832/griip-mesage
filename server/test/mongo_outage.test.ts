import { MongoMemoryReplSet } from "mongodb-memory-server";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { AccountClient, MemoryMailer } from "./support/accounts.js";
import type { GriipClient } from "./support/griipClient.js";
import { expectStatus, type Harness, startServer } from "./support/harness.js";

/**
 * MongoDB going away while the server runs (docs/MONGODB.md): the health check says so, requests that need it answer
 * 503 (never a sign-out or a 500), and everything that lives in SQLite keeps working. Uses its own throwaway replica set,
 * stopped mid-test.
 */
let rs: MongoMemoryReplSet;
let h: Harness;
const mailer = new MemoryMailer();
let account: AccountClient;
let legacy: GriipClient, other: GriipClient;

beforeAll(async () => {
  rs = await MongoMemoryReplSet.create({ replSet: { count: 1, storageEngine: "wiredTiger" }, instanceOpts: [{ ip: "127.0.0.1" }] });
  h = await startServer({ MONGODB_URI: rs.getUri(), MONGODB_DB: "outage", MONGODB_DOMAINS: "accounts,preferences", MONGODB_TIMEOUT_MS: "700" }, { mailer });
  account = await AccountClient.ready(h.baseUrl, mailer, "outage");
  [legacy, other] = await Promise.all([h.client("legacyuser"), h.client("otheruser")]);
  await rs.stop({ doCleanup: true, force: true });
}, 60_000);

afterAll(async () => {
  // The database is gone, so the harness can't drop it: close the server directly.
  await h.app.close().catch(() => undefined);
  h.ctx.db.close();
});

describe("MongoDB unavailable", () => {
  it("the health check reports it with 503", async () => {
    const res = await fetch(`${h.baseUrl}/healthz`);
    expect(res.status).toBe(503);
    expect(await res.json()).toMatchObject({ ok: false, mongodb: false });
  });

  it("requests that need the account store answer 503, not a sign-out", async () => {
    const e = await expectStatus(account.req("GET", "/v2/account"), 503);
    expect(e.error.code).toBe("unavailable");
    expect((await expectStatus(account.req("GET", "/v1/me/preferences/personal"), 503)).error.code).toBe("unavailable");
  });

  it("retention still runs for everything kept in SQLite", async () => {
    const { runCleanup } = await import("../src/modules/administration/jobs.js");
    const r = await runCleanup(h.ctx);
    expect(r.accountRows).toBe(0);
    expect(r.queue).toBeGreaterThanOrEqual(0);
  });

  it("everything kept in SQLite keeps working (v1 sessions, conversations)", async () => {
    const dm = (await legacy.req("POST", "/v1/conversations/dm", { userId: other.userId })).conversation.id;
    expect(dm).toBeTruthy();
    expect((await legacy.req("GET", "/v1/users/me")).user.id).toBe(legacy.userId);
  });
});
