import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { AccountClient, email, MemoryMailer, PASSWORD } from "./support/accounts.js";
import { expectStatus, type Harness, startServer } from "./support/harness.js";

let h: Harness;
const mailer = new MemoryMailer();
beforeAll(async () => {
  h = await startServer({}, { mailer });
});
afterAll(async () => h.close());

const anon = () => new AccountClient(h.baseUrl, email("anon"));

describe("Orbit 2.0 sign-up and email verification", () => {
  it("registers, refuses sign-in until the email is verified, then signs in", async () => {
    const c = new AccountClient(h.baseUrl, email("ada"), PASSWORD, "Ada");
    const r = await c.req("POST", "/v2/auth/register", { name: "Ada", email: c.email.toUpperCase(), password: PASSWORD });
    expect(r).toEqual({ status: "verification_sent", email: c.email });
    const unverified = await expectStatus(c.req("POST", "/v2/auth/login", { email: c.email, password: PASSWORD }), 403);
    expect(unverified.error.code).toBe("email_unverified");
    await c.req("POST", "/v2/auth/email/verify", { email: c.email, code: mailer.verifyCode(c.email), password: PASSWORD });
    const s = await c.signIn();
    expect(s.account).toMatchObject({ email: c.email, name: "Ada", identity: null, legacyLinkedAt: null });
    expect(s.session.deviceId).toBeNull();
    expect(s.accessToken).not.toBe(s.refreshToken);
    const me = await c.req("GET", "/v2/account");
    expect(me.account.id).toBe(s.account.id);
  });

  it("never stores or echoes the password, and stores it hashed with scrypt", async () => {
    const c = await new AccountClient(h.baseUrl, email("hash")).signUp(mailer);
    const row = h.ctx.db.prepare("SELECT password_hash FROM accounts WHERE email = ?").get(c.email) as { password_hash: string };
    expect(row.password_hash.startsWith("scrypt$")).toBe(true);
    expect(row.password_hash).not.toContain(PASSWORD);
    const codes = h.ctx.db.prepare("SELECT code_hash FROM account_codes").all() as { code_hash: string }[];
    for (const r of codes) expect(r.code_hash).toMatch(/^[0-9a-f]{64}$/);
  });

  it("answers sign-up the same for a taken address and tells the owner instead", async () => {
    const c = await new AccountClient(h.baseUrl, email("taken")).signUp(mailer);
    const again = await c.req("POST", "/v2/auth/register", { name: "Someone", email: c.email, password: "another long password" });
    expect(again).toEqual({ status: "verification_sent", email: c.email });
    expect(mailer.lastSubject(c.email)).toMatch(/tried to create an Orbit account/);
    // The original password still works; the attempt changed nothing.
    await c.signIn();
  });

  it("lets a newer sign-up take over an address nobody verified", async () => {
    const addr = email("squat");
    const squatter = new AccountClient(h.baseUrl, addr, "squatter password 1");
    await squatter.req("POST", "/v2/auth/register", { name: "Squatter", email: addr, password: "squatter password 1" });
    const owner = new AccountClient(h.baseUrl, addr, "owner password 123", "Owner");
    await owner.signUp(mailer);
    const s = await owner.signIn();
    expect(s.account.name).toBe("Owner");
    await expectStatus(squatter.signIn(), 401);
  });

  it("enforces the password rules on the server", async () => {
    const c = anon();
    const short = await expectStatus(c.req("POST", "/v2/auth/register", { name: "X", email: c.email, password: "short" }), 400);
    expect(short.error).toMatchObject({ code: "weak_password", details: { reason: "too_short" } });
    const same = await expectStatus(c.req("POST", "/v2/auth/register", { name: "X", email: c.email, password: c.email }), 400);
    expect(same.error.details.reason).toBe("same_as_email");
    await expectStatus(c.req("POST", "/v2/auth/register", { name: "X", email: "not-an-email", password: PASSWORD }), 400);
  });

  it("limits wrong verification codes and accepts a resent one", async () => {
    const c = anon();
    await c.req("POST", "/v2/auth/register", { name: "Codes", email: c.email, password: PASSWORD });
    const right = mailer.verifyCode(c.email);
    const wrong = right === "000000" ? "111111" : "000000";
    for (let i = 0; i < 5; i++) expect((await expectStatus(c.req("POST", "/v2/auth/email/verify", { email: c.email, code: wrong, password: PASSWORD }), 400)).error.code).toBe("invalid_code");
    // Used up: even the right code is refused, with the same answer as any wrong one (nothing to tell addresses apart).
    expect((await expectStatus(c.req("POST", "/v2/auth/email/verify", { email: c.email, code: right, password: PASSWORD }), 400)).error.code).toBe("invalid_code");
    await c.req("POST", "/v2/auth/email/resend", { email: c.email });
    const fresh = mailer.verifyCode(c.email);
    await c.req("POST", "/v2/auth/email/verify", { email: c.email, code: fresh, password: PASSWORD });
    await c.signIn();
  });

  it("verifies only with the password chosen at sign-up, so a later sign-up can't take the account over", async () => {
    const addr = email("victim");
    const victim = new AccountClient(h.baseUrl, addr, "victim password 1");
    await victim.req("POST", "/v2/auth/register", { name: "Victim", email: addr, password: "victim password 1" });
    // The attacker uses up the address's code allowance, then signs up again with their own password.
    for (let i = 0; i < 6; i++) await victim.req("POST", "/v2/auth/email/resend", { email: addr });
    const code = mailer.verifyCode(addr);
    await victim.req("POST", "/v2/auth/register", { name: "Attacker", email: addr, password: "attacker password" });
    // No new code went out, so nothing changed: the victim's code and password still verify, and only theirs sign in.
    await expectStatus(victim.req("POST", "/v2/auth/email/verify", { email: addr, code: mailer.verifyCode(addr), password: "attacker password" }), 400);
    await victim.req("POST", "/v2/auth/email/verify", { email: addr, code: mailer.verifyCode(addr), password: "victim password 1" });
    expect(mailer.verifyCode(addr)).toBe(code);
    await victim.signIn();
    await expectStatus(new AccountClient(h.baseUrl, addr, "attacker password").signIn(), 401);
  });

  it("answers an unknown address like a known one (verify, resend, forgot, reset)", async () => {
    const c = anon();
    expect((await expectStatus(c.req("POST", "/v2/auth/email/verify", { email: c.email, code: "123456", password: PASSWORD }), 400)).error.code).toBe("invalid_code");
    expect(await c.req("POST", "/v2/auth/email/resend", { email: c.email })).toEqual({ status: "sent_if_pending" });
    expect(await c.req("POST", "/v2/auth/password/forgot", { email: c.email })).toEqual({ status: "sent_if_account" });
    expect(mailer.sent.some((m) => m.to === c.email)).toBe(false);
    // Wrong reset codes: a known and an unknown address answer exactly alike, also once the attempts are used up.
    const known = await new AccountClient(h.baseUrl, email("knownreset")).signUp(mailer);
    await known.req("POST", "/v2/auth/password/forgot", { email: known.email });
    const answers = async (addr: string) => {
      const out: string[] = [];
      for (let i = 0; i < 6; i++) {
        const e = await expectStatus(c.req("POST", "/v2/auth/password/reset", { email: addr, code: "AAAAA-AAAAA", newPassword: "another long password" }), 400);
        out.push(e.error.code);
      }
      return out;
    };
    expect(await answers(known.email)).toEqual(await answers(c.email));
  });

  it("puts nothing anyone typed into account emails", async () => {
    const c = new AccountClient(h.baseUrl, email("named"), PASSWORD, "Visit evil.example now");
    await c.req("POST", "/v2/auth/register", { name: "Visit evil.example now", email: c.email, password: PASSWORD });
    const sent = mailer.sent.filter((m) => m.to === c.email);
    expect(sent.length).toBeGreaterThan(0);
    for (const m of sent) expect(m.text + m.subject).not.toContain("evil.example");
  });
});

describe("Orbit 2.0 sign-in", () => {
  it("gives the same answer for an unknown email and a wrong password", async () => {
    const c = await new AccountClient(h.baseUrl, email("cred")).signUp(mailer);
    const wrong = await expectStatus(c.req("POST", "/v2/auth/login", { email: c.email, password: "wrong password!!" }), 401);
    const unknown = await expectStatus(c.req("POST", "/v2/auth/login", { email: email("nobody"), password: "wrong password!!" }), 401);
    expect(wrong.error).toEqual(unknown.error);
    expect(wrong.error.code).toBe("invalid_credentials");
  });

  it("throttles repeated wrong passwords for an account", async () => {
    const c = await new AccountClient(h.baseUrl, email("throttle")).signUp(mailer);
    for (let i = 0; i < 5; i++) await expectStatus(c.req("POST", "/v2/auth/login", { email: c.email, password: `wrong password ${i}` }), 401);
    const blocked = await expectStatus(c.req("POST", "/v2/auth/login", { email: c.email, password: PASSWORD }), 429);
    expect(blocked.error.code).toBe("login_throttled");
    // An address without an account is throttled the same way, so throttling doesn't reveal which addresses exist.
    const nobody = email("nobody");
    for (let i = 0; i < 5; i++) await expectStatus(c.req("POST", "/v2/auth/login", { email: nobody, password: `wrong password ${i}` }), 401);
    expect((await expectStatus(c.req("POST", "/v2/auth/login", { email: nobody, password: PASSWORD }), 429)).error.code).toBe("login_throttled");
  });

  it("rotates tokens on refresh, recovers a lost answer, and ends the session when an old token comes back", async () => {
    const c = await new AccountClient(h.baseUrl, email("rotate")).signUp(mailer);
    const first = await c.signIn();
    expect(first.refreshToken.startsWith(`${first.session.id}.`)).toBe(true);
    const second = await c.refresh();
    expect(second.refreshToken).not.toBe(first.refreshToken);
    expect(second.session.id).toBe(first.session.id);
    // The replaced access token no longer works; the new one does.
    c.token = first.accessToken;
    await expectStatus(c.req("GET", "/v2/account"), 401);
    c.token = second.accessToken;
    await c.req("GET", "/v2/account");
    // The answer to that refresh was lost: within seconds the old refresh token works once more, and the lost pair ends.
    const recovered = await c.req("POST", "/v2/auth/refresh", { refreshToken: first.refreshToken });
    await expectStatus(c.req("GET", "/v2/account"), 401);
    c.token = recovered.accessToken;
    await c.req("GET", "/v2/account");
    // A token of the session other than the current one (here the lost one) means a copy: the whole session ends.
    expect((await expectStatus(c.req("POST", "/v2/auth/refresh", { refreshToken: second.refreshToken }), 401)).error.code).toBe("session_revoked");
    await expectStatus(c.req("GET", "/v2/account"), 401);
    expect((await expectStatus(c.req("POST", "/v2/auth/refresh", { refreshToken: recovered.refreshToken }), 401)).error.code).toBe("session_revoked");
  });

  it("catches a stolen refresh token however many times the thief renewed it", async () => {
    const victim = await new AccountClient(h.baseUrl, email("stolen")).signUp(mailer);
    const s = await victim.signIn();
    const thief = new AccountClient(h.baseUrl, victim.email);
    thief.refreshToken = s.refreshToken;
    await thief.refresh();
    await thief.refresh();
    h.ctx.db.prepare("UPDATE account_sessions SET refreshed_at = refreshed_at - 120000 WHERE id = ?").run(s.session.id);
    // The victim's old token comes back: the session (and the thief's tokens) end.
    expect((await expectStatus(victim.req("POST", "/v2/auth/refresh", { refreshToken: s.refreshToken }), 401)).error.code).toBe("session_revoked");
    await expectStatus(thief.req("GET", "/v2/account"), 401);
    expect((await expectStatus(thief.req("POST", "/v2/auth/refresh", { refreshToken: thief.refreshToken }), 401)).error.code).toBe("session_revoked");
  });

  it("expires sessions by idle time and absolute lifetime", async () => {
    const c = await new AccountClient(h.baseUrl, email("expire")).signUp(mailer);
    const s = await c.signIn();
    h.ctx.db.prepare("UPDATE account_sessions SET refresh_expires_at = ? WHERE id = ?").run(Date.now() - 1, s.session.id);
    expect((await expectStatus(c.req("POST", "/v2/auth/refresh", { refreshToken: s.refreshToken }), 401)).error.code).toBe("session_expired");
    await expectStatus(c.req("GET", "/v2/account"), 401);
    const s2 = await c.signIn();
    h.ctx.db.prepare("UPDATE account_sessions SET access_expires_at = ? WHERE id = ?").run(Date.now() - 1, s2.session.id);
    await expectStatus(c.req("GET", "/v2/account"), 401);
    await c.refresh();
    await c.req("GET", "/v2/account");
  });

  it("signs out one session, lists sessions and revokes another", async () => {
    const c = await new AccountClient(h.baseUrl, email("sessions")).signUp(mailer);
    const phone = await c.signIn();
    const tablet = new AccountClient(h.baseUrl, c.email);
    const t = await tablet.signIn();
    const list = await c.req("GET", "/v2/account/sessions");
    expect(list.sessions.map((x: any) => x.id).sort()).toEqual([phone.session.id, t.session.id].sort());
    expect(list.sessions.find((x: any) => x.id === phone.session.id).current).toBe(true);
    await c.req("DELETE", `/v2/account/sessions/${t.session.id}`);
    await expectStatus(tablet.req("GET", "/v2/account"), 401);
    // Someone else's session id answers like one that doesn't exist.
    const other = await new AccountClient(h.baseUrl, email("other")).signUp(mailer);
    const o = await other.signIn();
    await expectStatus(c.req("DELETE", `/v2/account/sessions/${o.session.id}`), 404);
    await c.req("POST", "/v2/auth/logout");
    await expectStatus(c.req("GET", "/v2/account"), 401);
  });

  it("refuses a disabled account at sign-in and refresh", async () => {
    const c = await new AccountClient(h.baseUrl, email("disabled")).signUp(mailer);
    const s = await c.signIn();
    h.ctx.db.prepare("UPDATE accounts SET disabled_at = ? WHERE id = ?").run(Date.now(), s.account.id);
    await expectStatus(c.req("GET", "/v2/account"), 401);
    expect((await expectStatus(c.req("POST", "/v2/auth/refresh", { refreshToken: s.refreshToken }), 401)).error.code).toBe("account_disabled");
    expect((await expectStatus(c.req("POST", "/v2/auth/login", { email: c.email, password: PASSWORD }), 401)).error.code).toBe("account_disabled");
  });
});

describe("Orbit 2.0 passwords", () => {
  it("resets a forgotten password and ends every session", async () => {
    const c = await new AccountClient(h.baseUrl, email("forgot")).signUp(mailer);
    await c.signIn();
    await c.req("POST", "/v2/auth/password/forgot", { email: c.email });
    const code = mailer.resetCode(c.email);
    await expectStatus(c.req("POST", "/v2/auth/password/reset", { email: c.email, code: "AAAAA-AAAAA", newPassword: "brand new password" }), 400);
    await c.req("POST", "/v2/auth/password/reset", { email: c.email, code: code.toLowerCase(), newPassword: "brand new password" });
    await expectStatus(c.req("GET", "/v2/account"), 401);
    await expectStatus(c.signIn(), 401);
    c.password = "brand new password";
    await c.signIn();
    // The code is single use.
    await expectStatus(c.req("POST", "/v2/auth/password/reset", { email: c.email, code, newPassword: "yet another password" }), 400);
  });

  it("lets a password reset clear failed sign-ins others caused", async () => {
    const c = await new AccountClient(h.baseUrl, email("lockout")).signUp(mailer);
    for (let i = 0; i < 5; i++) await expectStatus(c.req("POST", "/v2/auth/login", { email: c.email, password: `guess number ${i}` }), 401);
    await expectStatus(c.signIn(), 429);
    await c.req("POST", "/v2/auth/password/forgot", { email: c.email });
    await c.req("POST", "/v2/auth/password/reset", { email: c.email, code: mailer.resetCode(c.email), newPassword: "recovered password 1" });
    c.password = "recovered password 1";
    await c.signIn();
  });

  it("keeps reset codes coming however many sign-up attempts use the address", async () => {
    const c = await new AccountClient(h.baseUrl, email("noticed")).signUp(mailer);
    for (let i = 0; i < 6; i++) await c.req("POST", "/v2/auth/register", { name: "Someone", email: c.email, password: "some other password" });
    expect(mailer.sent.filter((m) => m.to === c.email && /tried to create/.test(m.subject)).length).toBe(1);
    await c.req("POST", "/v2/auth/password/forgot", { email: c.email });
    expect(mailer.resetCode(c.email)).toMatch(/^[A-Z2-9]{5}-[A-Z2-9]{5}$/);
  });

  it("changes the password with the current one and ends the other sessions", async () => {
    const c = await new AccountClient(h.baseUrl, email("change")).signUp(mailer);
    await c.signIn();
    const other = new AccountClient(h.baseUrl, c.email);
    await other.signIn();
    await expectStatus(c.req("POST", "/v2/account/password", { currentPassword: "not my password", newPassword: "next password 99" }), 401);
    await c.req("POST", "/v2/account/password", { currentPassword: PASSWORD, newPassword: "next password 99" });
    await c.req("GET", "/v2/account");
    await expectStatus(other.req("GET", "/v2/account"), 401);
  });
});

describe("Orbit 2.0 without an email transport", () => {
  it("refuses sign-up instead of making accounts nobody can verify", async () => {
    const bare = await startServer();
    try {
      const c = new AccountClient(bare.baseUrl, email("nomail"));
      const r = await expectStatus(c.req("POST", "/v2/auth/register", { name: "N", email: c.email, password: PASSWORD }), 503);
      expect(r.error.code).toBe("email_unavailable");
      const info = await c.req("GET", "/v1/server-info");
      expect(info.accounts.registration).toBe(false);
    } finally {
      await bare.close();
    }
  });
});
