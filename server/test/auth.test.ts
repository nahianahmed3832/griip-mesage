import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { canonical, GriipClient, newEd25519, sign } from "./support/griipClient.js";
import { expectStatus, type Harness, startServer } from "./support/harness.js";

let h: Harness;
beforeAll(async () => {
  h = await startServer();
});
afterAll(async () => h.close());

describe("identity & authentication", () => {
  it("registers two distinct users with device certificates", async () => {
    const a = await h.client("alice");
    const b = await h.client("bob");
    expect(a.userId).not.toBe(b.userId);
    const meA = await a.req("GET", "/v1/users/me");
    expect(meA.user.username).toBe(a.username);
    expect(meA.user.identityKey).toBe(a.identityKeyB64);
    expect(meA.user.fingerprint).toMatch(/^(\d{5} ){5}\d{5}$/);
  });

  it("logs in with a fresh signed challenge and rejects replays", async () => {
    const a = await h.client("carol");
    const { nonce } = await a.req("POST", "/v1/auth/challenge");
    const signature = sign(a.device, canonical.authProof("login", nonce, a.signingKeyB64));
    const ok = await a.req("POST", "/v1/auth/login", { deviceId: a.deviceId, nonce, signature });
    expect(ok.sessionToken).toBeTruthy();
    // Same nonce again → rejected (single-use)
    await expectStatus(a.req("POST", "/v1/auth/login", { deviceId: a.deviceId, nonce, signature }), 401);
  });

  it("rejects an invalid proof (wrong key) and never authenticates by username alone", async () => {
    const a = await h.client("dave");
    const attacker = newEd25519();
    const { nonce } = await a.req("POST", "/v1/auth/challenge");
    await expectStatus(
      a.req("POST", "/v1/auth/login", { deviceId: a.deviceId, nonce, signature: sign(attacker, canonical.authProof("login", nonce, a.signingKeyB64)) }),
      401,
    );
    // add-device requires a certificate from the identity key — knowing the username is not enough
    const mallory = new GriipClient(h.baseUrl, "x");
    const { nonce: n2 } = await a.req("POST", "/v1/auth/challenge");
    await expectStatus(
      mallory.req("POST", "/v1/auth/add-device", {
        username: a.username,
        device: { name: "evil", signingKey: mallory.signingKeyB64, encryptionKey: mallory.encKeyB64, certificate: mallory.certificate() },
        nonce: n2,
        signature: sign(mallory.device, canonical.authProof("add-device", n2, mallory.signingKeyB64)),
      }),
      401,
    );
  });

  it("rejects registration with a certificate not signed by the identity key", async () => {
    const c = new GriipClient(h.baseUrl, "certfail");
    const other = newEd25519();
    const { nonce } = await c.req("POST", "/v1/auth/challenge");
    await expectStatus(
      c.req("POST", "/v1/auth/register", {
        username: "certfail",
        displayName: "Cert Fail",
        identityKey: c.identityKeyB64,
        device: {
          name: "x",
          signingKey: c.signingKeyB64,
          encryptionKey: c.encKeyB64,
          certificate: sign(other, canonical.deviceCert(c.identityKeyB64, c.signingKeyB64, c.encKeyB64)),
        },
        nonce,
        signature: sign(c.device, canonical.authProof("register", nonce, c.signingKeyB64)),
      }),
      401,
    );
  });

  it("enforces unique usernames and reports availability", async () => {
    const a = await h.client("erin");
    const r = await a.req("GET", `/v1/auth/username-available?username=${a.username}`);
    expect(r.available).toBe(false);
    const bad = await a.req("GET", `/v1/auth/username-available?username=A!`);
    expect(bad.available).toBe(false);
    const dup = new GriipClient(h.baseUrl, a.username);
    await expectStatus(dup.register(), 409);
  });

  it("requires authentication on protected routes", async () => {
    const anon = new GriipClient(h.baseUrl, "anon");
    await expectStatus(anon.req("GET", "/v1/users/me"), 401);
    await expectStatus(anon.req("GET", "/v1/conversations"), 401);
    anon.token = "not-a-real-token-but-long-enough-xxxxxxxx";
    await expectStatus(anon.req("GET", "/v1/users/me"), 401);
  });

  it("recovers an account onto a new device with the identity key, and revokes devices", async () => {
    const a = await h.client("frank");
    // New phone: same identity seed (recovery key), fresh device keys
    const phone2 = new GriipClient(h.baseUrl, a.username);
    phone2.identity = a.identity;
    const { nonce } = await phone2.req("POST", "/v1/auth/challenge");
    const added = await phone2.req("POST", "/v1/auth/add-device", {
      username: a.username,
      device: { name: "Pixel 2", signingKey: phone2.signingKeyB64, encryptionKey: phone2.encKeyB64, certificate: phone2.certificate() },
      nonce,
      signature: sign(phone2.device, canonical.authProof("add-device", nonce, phone2.signingKeyB64)),
    });
    expect(added.userId).toBe(a.userId);
    phone2.token = added.sessionToken;
    phone2.deviceId = added.deviceId;
    const devices = await phone2.req("GET", "/v1/devices");
    expect(devices.devices).toHaveLength(2);
    // Revoke the old device from the new one → its session dies immediately
    await phone2.req("DELETE", `/v1/devices/${a.deviceId}`);
    await expectStatus(a.req("GET", "/v1/users/me"), 401);
    const { nonce: n3 } = await a.req("POST", "/v1/auth/challenge");
    const err = await expectStatus(
      a.req("POST", "/v1/auth/login", { deviceId: a.deviceId, nonce: n3, signature: sign(a.device, canonical.authProof("login", n3, a.signingKeyB64)) }),
      401,
    );
    expect(err.error.code).toBe("device_revoked");
  });

  it("pairs a new device via short code; server only relays sealed identity material", async () => {
    const a = await h.client("gina");
    const newPhone = new GriipClient(h.baseUrl, "");
    const transfer = newEd25519(); // any 32-byte public key works for the relay test
    const req = await newPhone.req("POST", "/v1/pairing", {
      deviceName: "Tablet",
      signingKey: newPhone.signingKeyB64,
      encryptionKey: newPhone.encKeyB64,
      transferKey: transfer.pub.toString("base64"),
    });
    expect((await newPhone.req("GET", `/v1/pairing/${req.pairingId}`)).status).toBe("pending");
    const seen = await a.req("GET", `/v1/pairing/code/${req.code}`);
    expect(seen.signingKey).toBe(newPhone.signingKeyB64);
    // Approve with a certificate signed by A's identity key
    const cert = sign(a.identity, canonical.deviceCert(a.identityKeyB64, seen.signingKey, seen.encryptionKey));
    await a.req("POST", `/v1/pairing/code/${req.code}/approve`, { certificate: cert, sealedIdentity: "b3BhcXVlLWNpcGhlcnRleHQ=", sealedEnc: transfer.pub.toString("base64") });
    const done = await newPhone.req("GET", `/v1/pairing/${req.pairingId}`);
    expect(done.status).toBe("approved");
    expect(done.username).toBe(a.username);
    newPhone.identity = a.identity;
    newPhone.username = a.username;
    const { nonce } = await newPhone.req("POST", "/v1/auth/challenge");
    const added = await newPhone.req("POST", "/v1/auth/add-device", {
      username: a.username,
      device: { name: "Tablet", signingKey: newPhone.signingKeyB64, encryptionKey: newPhone.encKeyB64, certificate: done.certificate },
      nonce,
      signature: sign(newPhone.device, canonical.authProof("add-device", nonce, newPhone.signingKeyB64)),
    });
    expect(added.userId).toBe(a.userId);
  });
});
