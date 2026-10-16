import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { API_VERSION, MIN_CLIENT_API, VERSION } from "../src/app.js";
import { type Harness, startServer } from "./support/harness.js";

let h: Harness;
let named: Harness;
beforeAll(async () => {
  h = await startServer();
  named = await startServer({ INSTANCE_NAME: "  Example Community  ", REGISTRATION_MODE: "invite" });
});
afterAll(async () => {
  await h.close();
  await named.close();
});

describe("server info for client setup and self-hosting", () => {
  it("states its API range, version and registration mode without secrets", async () => {
    const r = await (await fetch(`${h.baseUrl}/v1/server-info`)).json();
    expect(r).toMatchObject({ infoVersion: 1, api: API_VERSION, minClientApi: MIN_CLIENT_API, version: VERSION, instanceName: null, registration: "open" });
    expect(r.encryption).toMatch(/^griip-envelope-v1/);
    const text = JSON.stringify(r);
    for (const secret of ["apiSecret", "apiKey", "LIVEKIT", "databasePath", "attachmentDir"]) expect(text).not.toContain(secret);
  });

  it("uses a trimmed INSTANCE_NAME and the configured registration mode", async () => {
    const r = await (await fetch(`${named.baseUrl}/v1/server-info`)).json();
    expect(r.instanceName).toBe("Example Community");
    expect(r.registration).toBe("invite");
  });

  it("the health check works without authentication", async () => {
    const res = await fetch(`${h.baseUrl}/healthz`);
    expect(res.status).toBe(200);
    expect((await res.json()).version).toBe(VERSION);
  });
});
