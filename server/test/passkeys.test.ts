import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { androidOrigin } from "../src/modules/accounts/passkeys.js";
import { passkeyConfig } from "../src/modules/accounts/config.js";
import { AccountClient, email, MemoryMailer } from "./support/accounts.js";
import { FakePasskey } from "./support/fakePasskey.js";
import { expectStatus, type Harness, startServer } from "./support/harness.js";

/** Passkeys (2026-10-23, docs/PASSKEYS.md): adding one while signed in, signing in with it, and every refusal. */
const RP = "msg.example.com";
const CERT = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";
const APP = "com.meldiq.android.test";
/** A web app on another host: its own relying party (PASSKEY_WEB_ORIGINS). */
const WEB = "web.example.com";
const WEB_ORIGIN = `https://${WEB}`;

let h: Harness;
let off: Harness;
const mailer = new MemoryMailer();
beforeAll(async () => {
  h = await startServer({ PASSKEY_RP_ID: RP, PASSKEY_ANDROID_APPS: `${APP}=${CERT}`, PASSKEY_WEB_ORIGINS: WEB_ORIGIN }, { mailer });
  off = await startServer({}, { mailer });
});
afterAll(async () => {
  await h.close();
  await off.close();
});

async function account(name: string, server = h): Promise<AccountClient> {
  const c = await new AccountClient(server.baseUrl, email(name)).signUp(mailer);
  await c.signIn();
  return c;
}

/** Adds [key] to [c]'s account: options, the platform's answer, then the server's verdict. */
async function addPasskey(c: AccountClient, key: FakePasskey, name = "Pixel", rpId?: string): Promise<any> {
  const { challengeId, options } = await c.req("POST", "/v2/account/passkeys/options", rpId ? { rpId } : {});
  return c.req("POST", "/v2/account/passkeys", { challengeId, response: key.create(options), name });
}

async function passkeyLogin(key: FakePasskey, mutate: (r: any) => any = (r) => r, rpId?: string): Promise<any> {
  const anon = new AccountClient(h.baseUrl, "nobody@example.com");
  const { challengeId, options } = await anon.req("POST", "/v2/auth/passkey/options", rpId ? { rpId } : {});
  return anon.req("POST", "/v2/auth/passkey/login", { challengeId, response: mutate(key.get(options)), deviceName: "test phone" });
}

describe("passkeys", () => {
  it("are off, and say so, until the server names its relying party", async () => {
    const c = await account("off", off);
    expect(await c.req("GET", "/v2/account/passkeys")).toEqual({ available: false, passkeys: [] });
    expect((await expectStatus(c.req("POST", "/v2/account/passkeys/options", {}), 503)).error.code).toBe("passkey_unavailable");
    expect(await c.req("GET", "/.well-known/assetlinks.json")).toEqual([]);
    expect((await c.req("GET", "/v1/server-info")).passkeys).toBeNull();
  });

  it("can be added from the Android app and then sign in to the same account", async () => {
    const c = await account("ada");
    const key = new FakePasskey(RP, androidOrigin(CERT));
    const { passkey } = await addPasskey(c, key);
    expect(passkey).toMatchObject({ id: key.id, name: "Pixel", lastUsedAt: null, synced: true });
    expect((await c.req("GET", "/v2/account/passkeys")).passkeys.map((p: any) => p.id)).toEqual([key.id]);

    const s = await passkeyLogin(key);
    expect(s.account.id).toBe(c.accountId);
    expect(s.session.deviceId).toBeNull();
    // The new session works like a password one.
    const fresh = new AccountClient(h.baseUrl, c.email);
    fresh.useSession(s);
    expect((await fresh.req("GET", "/v2/account")).account.email).toBe(c.email);
    expect((await c.req("GET", "/v2/account/passkeys")).passkeys[0].lastUsedAt).toEqual(expect.any(Number));
  });

  it("work from the server's own web address too", async () => {
    const c = await account("web");
    const key = new FakePasskey(RP, `https://${RP}`);
    await addPasskey(c, key);
    expect((await passkeyLogin(key)).account.id).toBe(c.accountId);
  });

  it("work from a web app on another host, as its own relying party, next to the Android ones", async () => {
    const anon = new AccountClient(h.baseUrl, "info@example.com");
    expect((await anon.req("GET", "/v1/server-info")).passkeys).toEqual({ webOrigins: [WEB_ORIGIN] });
    const c = await account("other-host");
    const phone = new FakePasskey(RP, androidOrigin(CERT));
    const browser = new FakePasskey(WEB, WEB_ORIGIN);
    await addPasskey(c, phone, "Pixel");
    const { challengeId, options } = await c.req("POST", "/v2/account/passkeys/options", { rpId: WEB });
    expect(options.rp).toEqual({ name: "Meldiq", id: WEB });
    // The phone's passkey is already listed; it is for the other relying party, so the browser's prompt ignores it.
    expect(options.excludeCredentials.map((e: any) => e.id)).toEqual([phone.id]);
    await c.req("POST", "/v2/account/passkeys", { challengeId, response: browser.create(options), name: "Chrome" });
    expect((await c.req("GET", "/v2/account/passkeys")).passkeys.map((p: any) => p.name)).toEqual(["Pixel", "Chrome"]);

    const login = await anon.req("POST", "/v2/auth/passkey/options", { rpId: WEB });
    expect(login.options.rpId).toBe(WEB);
    expect((await passkeyLogin(browser, undefined, WEB)).account.id).toBe(c.accountId);
    // The Android passkey still signs in as before (no rpId: this server's own).
    expect((await passkeyLogin(phone)).account.id).toBe(c.accountId);
  });

  it("refuse a relying party they don't serve, and a passkey answered from another relying party's origin", async () => {
    const anon = new AccountClient(h.baseUrl, "rp@example.com");
    for (const rpId of ["evil.example.org", "example.com"]) {
      expect((await expectStatus(anon.req("POST", "/v2/auth/passkey/options", { rpId }), 503)).error.code).toBe("passkey_unavailable");
    }
    const c = await account("cross-rp");
    // A web passkey reported from this server's own web address, and this server's passkey from the web app's.
    const mixed = new FakePasskey(WEB, `https://${RP}`);
    expect((await expectStatus(addPasskey(c, mixed, "Mixed", WEB), 401)).error.code).toBe("passkey_failed");
    const other = new FakePasskey(RP, WEB_ORIGIN);
    expect((await expectStatus(addPasskey(c, other, "Other"), 401)).error.code).toBe("passkey_failed");

    const web = new FakePasskey(WEB, WEB_ORIGIN);
    await addPasskey(c, web, "Chrome", WEB);
    web.origin = `https://${RP}`;
    expect((await expectStatus(passkeyLogin(web, undefined, WEB), 401)).error.code).toBe("passkey_failed");
  });

  it("refuse other origins, other sites, missing user verification and forged signatures", async () => {
    const c = await account("strict");
    const key = new FakePasskey(RP, androidOrigin(CERT));
    await addPasskey(c, key);

    key.origin = "https://evil.example.org";
    expect((await expectStatus(passkeyLogin(key), 401)).error.code).toBe("passkey_failed");
    key.origin = androidOrigin(CERT.replace("AA:BB", "AB:BB"));
    expect((await expectStatus(passkeyLogin(key), 401)).error.code).toBe("passkey_failed");
    key.origin = androidOrigin(CERT);

    const anon = new AccountClient(h.baseUrl, "x@example.com");
    const opts = await anon.req("POST", "/v2/auth/passkey/options", {});
    const forged = key.get(opts.options, undefined, "evil.example.org");
    expect((await expectStatus(anon.req("POST", "/v2/auth/passkey/login", { challengeId: opts.challengeId, response: forged }), 401)).error.code).toBe("passkey_failed");

    key.userVerified = false;
    expect((await expectStatus(passkeyLogin(key), 401)).error.code).toBe("passkey_failed");
    key.userVerified = true;

    const tampered = await expectStatus(
      passkeyLogin(key, (r) => ({ ...r, response: { ...r.response, signature: Buffer.from("not a signature").toString("base64url") } })),
      401,
    );
    expect(tampered.error.code).toBe("passkey_failed");
    // And a passkey nobody registered.
    expect((await expectStatus(passkeyLogin(new FakePasskey(RP, androidOrigin(CERT))), 401)).error.code).toBe("passkey_unknown");
  });

  it("use each challenge once, for its own purpose and account", async () => {
    const a = await account("once-a");
    const b = await account("once-b");
    const key = new FakePasskey(RP, androidOrigin(CERT));
    const { challengeId, options } = await a.req("POST", "/v2/account/passkeys/options", {});
    // Another account can't finish a ceremony someone else started.
    const stolen = await expectStatus(b.req("POST", "/v2/account/passkeys", { challengeId, response: key.create(options) }), 401);
    expect(stolen.error.code).toBe("challenge_expired");
    // …and that attempt used it up.
    expect((await expectStatus(a.req("POST", "/v2/account/passkeys", { challengeId, response: key.create(options) }), 401)).error.code).toBe("challenge_expired");

    await addPasskey(a, key);
    const anon = new AccountClient(h.baseUrl, "y@example.com");
    const login = await anon.req("POST", "/v2/auth/passkey/options", {});
    const answer = key.get(login.options);
    await anon.req("POST", "/v2/auth/passkey/login", { challengeId: login.challengeId, response: answer });
    const replay = await expectStatus(anon.req("POST", "/v2/auth/passkey/login", { challengeId: login.challengeId, response: answer }), 401);
    expect(replay.error.code).toBe("challenge_expired");
  });

  it("can be removed, and then don't sign in; the same passkey can't be added twice", async () => {
    const c = await account("remove");
    const key = new FakePasskey(RP, androidOrigin(CERT));
    await addPasskey(c, key);
    expect((await expectStatus(addPasskey(c, key), 401)).error.code).toBe("passkey_failed");
    const other = await account("remove-other");
    await expectStatus(other.req("DELETE", `/v2/account/passkeys/${key.id}`), 404);
    expect(await c.req("DELETE", `/v2/account/passkeys/${key.id}`)).toEqual({ ok: true });
    expect((await c.req("GET", "/v2/account/passkeys")).passkeys).toEqual([]);
    expect((await expectStatus(passkeyLogin(key), 401)).error.code).toBe("passkey_unknown");
  });

  it("are listed for Android in Digital Asset Links", async () => {
    const anon = new AccountClient(h.baseUrl, "z@example.com");
    expect(await anon.req("GET", "/.well-known/assetlinks.json")).toEqual([
      {
        relation: ["delegate_permission/common.handle_all_urls", "delegate_permission/common.get_login_creds"],
        target: { namespace: "android_app", package_name: APP, sha256_cert_fingerprints: [CERT] },
      },
    ]);
  });

  it("refuse malformed settings at start-up", () => {
    expect(() => passkeyConfig({ PASSKEY_RP_ID: "https://msg.example.com" })).toThrow(/host name/);
    expect(() => passkeyConfig({ PASSKEY_RP_ID: RP, PASSKEY_ANDROID_APPS: "com.x=nothex" })).toThrow(/fingerprint/);
    expect(() => passkeyConfig({ PASSKEY_ANDROID_APPS: `${APP}=${CERT}` })).toThrow(/needs PASSKEY_RP_ID/);
    expect(passkeyConfig({ PASSKEY_RP_ID: "MSG.Example.com", PASSKEY_ANDROID_APPS: `${APP}=${CERT.toLowerCase()}` })).toEqual({
      rpId: RP,
      androidApps: [{ packageName: APP, certSha256: CERT }],
      webOrigins: [],
    });
    for (const bad of ["web.example.com", "http://web.example.com", "https://web.example.com/app", "https://localhost", "ftp://x.example.com"]) {
      expect(() => passkeyConfig({ PASSKEY_RP_ID: RP, PASSKEY_WEB_ORIGINS: bad })).toThrow(/PASSKEY_WEB_ORIGINS/);
    }
    expect(() => passkeyConfig({ PASSKEY_WEB_ORIGINS: WEB_ORIGIN })).toThrow(/needs PASSKEY_RP_ID/);
    expect(passkeyConfig({ PASSKEY_RP_ID: RP, PASSKEY_WEB_ORIGINS: `${WEB_ORIGIN}/, http://localhost:5173` }).webOrigins).toEqual([WEB_ORIGIN, "http://localhost:5173"]);
  });
});
