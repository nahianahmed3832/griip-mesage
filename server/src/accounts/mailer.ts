import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { SendEmailCommand, SESv2Client } from "@aws-sdk/client-sesv2";
import type { MailConfig } from "./config.js";

export interface MailMessage {
  to: string;
  subject: string;
  text: string;
}

/** Sends account emails (verification and reset codes). Implementations never log a message's body: it holds codes. */
export interface Mailer {
  readonly kind: MailConfig["transport"] | "memory";
  /** False when no transport is configured: routes that must send email answer 503 instead of pretending. */
  readonly available: boolean;
  send(message: MailMessage): Promise<void>;
}

class NoMailer implements Mailer {
  readonly kind = "none" as const;
  readonly available = false;
  async send(): Promise<void> {
    throw new Error("no mail transport configured");
  }
}

/** Development only (refused with APP_ENV=production): one file per message, readable only by the server's user. */
class FileMailer implements Mailer {
  readonly kind = "file" as const;
  readonly available = true;
  constructor(
    private readonly dir: string,
    private readonly from: string,
  ) {}

  async send(m: MailMessage): Promise<void> {
    await fs.promises.mkdir(this.dir, { recursive: true, mode: 0o700 });
    const name = `${Date.now()}-${crypto.randomBytes(4).toString("hex")}.eml`;
    const body = `From: ${this.from}\nTo: ${m.to}\nSubject: ${m.subject}\n\n${m.text}\n`;
    await fs.promises.writeFile(path.join(this.dir, name), body, { mode: 0o600 });
  }
}

/** The part of the SES client this mailer uses (tests pass a fake). */
export interface SesSender {
  send(command: SendEmailCommand): Promise<unknown>;
}

export class SesMailer implements Mailer {
  readonly kind = "ses" as const;
  readonly available = true;
  constructor(
    private readonly client: SesSender,
    private readonly from: string,
  ) {}

  async send(m: MailMessage): Promise<void> {
    await this.client.send(
      new SendEmailCommand({
        FromEmailAddress: this.from,
        Destination: { ToAddresses: [m.to] },
        Content: { Simple: { Subject: { Data: m.subject, Charset: "UTF-8" }, Body: { Text: { Data: m.text, Charset: "UTF-8" } } } },
      }),
    );
  }
}

export function createMailer(config: MailConfig): Mailer {
  switch (config.transport) {
    case "none":
      return new NoMailer();
    case "file":
      return new FileMailer(config.dir, config.from);
    case "ses":
      return new SesMailer(new SESv2Client({ region: config.region, ...(config.credentials ? { credentials: config.credentials } : {}) }), config.from);
  }
}

/**
 * The texts of account emails. Plain text, no links: the person types the code into the app. Nothing anyone typed (a
 * name) goes into them, so the sign-up form can't be used to send someone else's words from Orbit's address.
 */
export const mailTexts = {
  verifyEmail: (code: string): Omit<MailMessage, "to"> => ({
    subject: `${code} is your Orbit verification code`,
    text: `Hello,\n\nYour Orbit verification code is ${code}. It expires in 15 minutes.\n\nIf you didn't create an Orbit account, you can ignore this email.`,
  }),
  resetPassword: (code: string): Omit<MailMessage, "to"> => ({
    subject: "Reset your Orbit password",
    text: `Hello,\n\nYour Orbit password reset code is ${code}. It expires in 15 minutes.\n\nIf you didn't ask to reset your password, ignore this email: your password stays the same.`,
  }),
  alreadyRegistered: (): Omit<MailMessage, "to"> => ({
    subject: "Someone tried to create an Orbit account with your email",
    text: "Hello,\n\nSomeone tried to create a new Orbit account with this email address, which already has an account. If it was you, sign in instead, or reset your password from the sign-in screen.\n\nIf it wasn't you, you don't need to do anything.",
  }),
};
