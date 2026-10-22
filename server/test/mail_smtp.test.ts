import { describe, expect, it } from "vitest";
import { createMailer, mailTexts, SmtpMailer, smtpTransport } from "../src/infrastructure/mail/mailer.js";
import { loadConfig } from "../src/app/config.js";

/** SMTP account email (Hostinger Mail for development and early testing, docs/EMAIL.md): configuration and sending. */
const base = { DATA_DIR: "/tmp/meldiq-smtp-test", APP_ENV: "production", MAIL_TRANSPORT: "smtp" };
const load = (env: Record<string, string>) => loadConfig({ ...base, ...env } as NodeJS.ProcessEnv).accounts.mail;

describe("SMTP configuration", () => {
  it("needs a sender and a host, and takes the sender from MAIL_FROM or name and address", () => {
    expect(() => load({ SMTP_HOST: "smtp.example.com" })).toThrow(/MAIL_FROM/);
    expect(() => load({ MAIL_FROM: "Meldiq <no-reply@example.com>" })).toThrow(/SMTP_HOST/);
    expect(() => load({ MAIL_FROM: "Meldiq <no-reply@example.com>", SMTP_HOST: "smtp.example.com/x" })).toThrow(/SMTP_HOST/);
    const named = load({ MAIL_FROM_NAME: "Meldiq", MAIL_FROM_ADDRESS: "no-reply@example.com", SMTP_HOST: "smtp.example.com" });
    expect(named).toMatchObject({ transport: "smtp", from: "Meldiq <no-reply@example.com>", host: "smtp.example.com", port: 465, secure: true, auth: null });
    expect(() => load({ MAIL_FROM_NAME: "Evil\nBcc: x@y", MAIL_FROM_ADDRESS: "no-reply@example.com", SMTP_HOST: "h" })).toThrow(/line breaks/);
    expect(() => load({ MAIL_FROM_ADDRESS: "not-an-address", SMTP_HOST: "h" })).toThrow(/email address/);
  });

  it("uses TLS from the start on 465 and STARTTLS elsewhere, and wants both credentials or neither", () => {
    const env = { MAIL_FROM: "Meldiq <no-reply@example.com>", SMTP_HOST: "smtp.example.com" };
    expect(load({ ...env, SMTP_PORT: "587" })).toMatchObject({ port: 587, secure: false });
    expect(load({ ...env, SMTP_PORT: "2525", SMTP_SECURE: "true" })).toMatchObject({ secure: true });
    expect(() => load({ ...env, SMTP_PORT: "70000" })).toThrow(/SMTP_PORT/);
    expect(() => load({ ...env, SMTP_SECURE: "yes" })).toThrow(/SMTP_SECURE/);
    expect(() => load({ ...env, SMTP_USER: "no-reply@example.com" })).toThrow(/SMTP_USER and SMTP_PASS/);
    expect(load({ ...env, SMTP_USER: "u", SMTP_PASS: "p" })).toMatchObject({ auth: { user: "u", pass: "p" } });
    expect(() => loadConfig({ ...base, MAIL_TRANSPORT: "carrier-pigeon" } as NodeJS.ProcessEnv)).toThrow(/none, file, ses or smtp/);
  });

  it("never sends in plain text and checks the server's certificate", () => {
    const starttls = smtpTransport({ transport: "smtp", from: "x@example.com", host: "smtp.example.com", port: 587, secure: false, auth: null }) as unknown as { options: Record<string, unknown> };
    expect(starttls.options).toMatchObject({ host: "smtp.example.com", port: 587, secure: false, requireTLS: true, tls: { rejectUnauthorized: true, minVersion: "TLSv1.2" }, logger: false });
    const tls = smtpTransport({ transport: "smtp", from: "x@example.com", host: "smtp.example.com", port: 465, secure: true, auth: { user: "u", pass: "p" } }) as unknown as { options: Record<string, unknown> };
    expect(tls.options).toMatchObject({ secure: true, auth: { user: "u", pass: "p" } });
    expect(createMailer({ transport: "smtp", from: "x@example.com", host: "smtp.example.com", port: 465, secure: true, auth: null }).kind).toBe("smtp");
  });
});

describe("SMTP sending", () => {
  it("sends the account texts from the configured sender and passes provider errors on", async () => {
    const sent: unknown[] = [];
    const mailer = new SmtpMailer({ sendMail: async (m) => { sent.push(m); return {}; } }, "Meldiq <no-reply@example.com>");
    await mailer.send({ to: "ann@example.org", ...mailTexts.verifyEmail("123456") });
    expect(sent).toEqual([
      {
        from: "Meldiq <no-reply@example.com>",
        to: "ann@example.org",
        subject: "123456 is your Meldiq verification code",
        text: expect.stringContaining("123456"),
        html: expect.stringContaining("123456"),
        attachments: [expect.objectContaining({ cid: "logo@meldiq.app", contentType: "image/png", contentDisposition: "inline" })],
      },
    ]);
    const failing = new SmtpMailer({ sendMail: async () => { throw new Error("535 authentication failed"); } }, "x@example.com");
    await expect(failing.send({ to: "a@b.c", subject: "s", text: "t" })).rejects.toThrow(/535/);
  });
});
