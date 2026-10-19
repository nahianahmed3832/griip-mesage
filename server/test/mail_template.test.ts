import type { SendEmailCommand } from "@aws-sdk/client-sesv2";
import { describe, expect, it } from "vitest";
import { mailTexts, SesMailer } from "../src/accounts/mailer.js";
import { escapeHtml, MAIL_LOGO_CID, renderMail } from "../src/accounts/mailTemplate.js";

/** The designed account emails (mailTemplate.ts): layout, safety rules and the plain-text twin. */
const all = [
  mailTexts.verifyEmail("123456"),
  mailTexts.resetPassword("ABCDE-FGHJK"),
  mailTexts.recoveryEmail("654321"),
  mailTexts.recoveryCodesCreated(),
  mailTexts.recoveryAddressChanged("a•••@example.org"),
  mailTexts.recoveryFinished(1),
  mailTexts.alreadyRegistered(),
];

describe("account email template", () => {
  it("gives every email an HTML version with the embedded logo and a plain-text twin", () => {
    for (const m of all) {
      expect(m.html).toContain(`src="cid:${MAIL_LOGO_CID}"`);
      expect(m.inline).toEqual([expect.objectContaining({ cid: MAIL_LOGO_CID, contentType: "image/png" })]);
      expect(m.inline![0]!.content.subarray(0, 8)).toEqual(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]));
      expect(m.text).toMatch(/^Hello,/);
      expect(m.text).not.toContain("**");
      expect(m.html).toContain(`<title>${escapeHtml(m.subject)}</title>`);
    }
  });

  it("puts codes in both versions, with the expiry and the never-ask line", () => {
    const v = mailTexts.verifyEmail("123456");
    expect(v.text).toMatch(/\b123456\b/);
    expect(v.text).toContain("expires in 15 minutes");
    expect(v.text).toContain("never ask you for a code");
    expect(v.html).toMatch(/user-select:all[^>]*>123456</);
    expect(v.html).toContain("Tap the code to select it.");
    expect(mailTexts.resetPassword("ABCDE-FGHJK").text).toMatch(/\bABCDE-FGHJK\b/);
    expect(mailTexts.recoveryEmail("654321").text).toContain("expires in 30 minutes");
    expect(mailTexts.recoveryCodesCreated().text).not.toContain("never ask you for a code");
  });

  it("has no links or remote images, so nothing can be fetched or clicked", () => {
    for (const m of all) {
      expect(m.html).not.toMatch(/<a\s|href=|https?:\/\//i);
      expect(m.html!.match(/src="([^"]+)"/g)).toEqual([`src="cid:${MAIL_LOGO_CID}"`]);
    }
  });

  it("escapes values, so markup can't be smuggled in", () => {
    const evil = renderMail("<b>x</b>", {
      preview: "<script>",
      heading: "<img src=x>",
      paragraphs: ['"quoted" & **<i>bold</i>**'],
      code: { value: "<1>", note: "n" },
      footnote: "f",
    });
    expect(evil.html).not.toMatch(/<script>|<img src=x>|<i>|<1>|<b>x/);
    expect(evil.html).toContain("&quot;quoted&quot; &amp; <strong");
    expect(evil.html).toContain("&lt;i&gt;bold&lt;/i&gt;</strong>");
    expect(evil.text).toContain('"quoted" & <i>bold</i>');
  });

  it("keeps grammar right for one recovery code", () => {
    expect(mailTexts.recoveryFinished(1).text).toContain("1 recovery code left");
    expect(mailTexts.recoveryFinished(3).text).toContain("3 recovery codes left");
  });
});

describe("SES with the designed email", () => {
  it("sends a raw MIME message with text, HTML and the inline logo", async () => {
    const sent: SendEmailCommand[] = [];
    const mailer = new SesMailer({ send: async (c) => { sent.push(c); return {}; } }, "Meldiq <no-reply@example.com>");
    await mailer.send({ to: "ann@example.org", ...mailTexts.verifyEmail("123456") });
    const input = sent[0]!.input;
    expect(input.Destination?.ToAddresses).toEqual(["ann@example.org"]);
    const raw = Buffer.from(input.Content!.Raw!.Data!).toString("utf8");
    expect(raw).toMatch(/multipart\/alternative/);
    expect(raw).toMatch(/multipart\/related/);
    expect(raw).toContain(`Content-ID: <${MAIL_LOGO_CID}>`);
    expect(raw).toMatch(/Content-Type: image\/png/);
    expect(raw).toContain("Subject: 123456 is your Meldiq verification code");
  });

  it("still sends plain text the simple way when there's no HTML", async () => {
    const sent: SendEmailCommand[] = [];
    const mailer = new SesMailer({ send: async (c) => { sent.push(c); return {}; } }, "x@example.com");
    await mailer.send({ to: "a@b.c", subject: "s", text: "t" });
    expect(sent[0]!.input.Content?.Simple?.Body?.Text?.Data).toBe("t");
  });
});
