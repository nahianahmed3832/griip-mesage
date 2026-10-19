import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { SendEmailCommand, SESv2Client } from "@aws-sdk/client-sesv2";
import nodemailer from "nodemailer";
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

/** The part of a nodemailer transport this mailer uses (tests pass a fake). */
export interface SmtpSender {
  sendMail(message: { from: string; to: string; subject: string; text: string }): Promise<unknown>;
}

/**
 * Any SMTP provider (Hostinger Mail for development and early testing). Always encrypted: TLS from the start on port
 * 465, or a required STARTTLS upgrade elsewhere, with the server's certificate checked. Errors are passed on without
 * the message (it holds a code); nodemailer's own logging stays off.
 */
export class SmtpMailer implements Mailer {
  readonly kind = "smtp" as const;
  readonly available = true;
  constructor(
    private readonly transport: SmtpSender,
    private readonly from: string,
  ) {}

  async send(m: MailMessage): Promise<void> {
    await this.transport.sendMail({ from: this.from, to: m.to, subject: m.subject, text: m.text });
  }
}

/** Connection timeouts for SMTP: a slow provider must not hold a sign-up request open. */
const SMTP_TIMEOUT_MS = 15_000;

export function smtpTransport(config: Extract<MailConfig, { transport: "smtp" }>): SmtpSender {
  return nodemailer.createTransport({
    host: config.host,
    port: config.port,
    secure: config.secure,
    requireTLS: !config.secure,
    auth: config.auth ?? undefined,
    tls: { minVersion: "TLSv1.2", rejectUnauthorized: true },
    connectionTimeout: SMTP_TIMEOUT_MS,
    greetingTimeout: SMTP_TIMEOUT_MS,
    socketTimeout: SMTP_TIMEOUT_MS,
    logger: false,
    debug: false,
  });
}

export function createMailer(config: MailConfig): Mailer {
  switch (config.transport) {
    case "none":
      return new NoMailer();
    case "file":
      return new FileMailer(config.dir, config.from);
    case "ses":
      return new SesMailer(new SESv2Client({ region: config.region, ...(config.credentials ? { credentials: config.credentials } : {}) }), config.from);
    case "smtp":
      return new SmtpMailer(smtpTransport(config), config.from);
  }
}

/**
 * The texts of account emails. Plain text, no links: the person types the code into the app. Nothing anyone typed (a
 * name) goes into them, so the sign-up form can't be used to send someone else's words from Meldiq's address.
 */
export const mailTexts = {
  verifyEmail: (code: string): Omit<MailMessage, "to"> => ({
    subject: `${code} is your Meldiq verification code`,
    text: `Hello,\n\nYour Meldiq verification code is ${code}. It expires in 15 minutes.\n\nIf you didn't create a Meldiq account, you can ignore this email.`,
  }),
  resetPassword: (code: string): Omit<MailMessage, "to"> => ({
    subject: "Reset your Meldiq password",
    text: `Hello,\n\nYour Meldiq password reset code is ${code}. It expires in 15 minutes.\n\nIf you didn't ask to reset your password, ignore this email: your password stays the same.`,
  }),
  recoveryEmail: (code: string): Omit<MailMessage, "to"> => ({
    subject: `${code} confirms your new Meldiq email address`,
    text: `Hello,\n\nSomeone is recovering a Meldiq account with a recovery code and wants to use this address from now on. To confirm, enter ${code}. It expires in 30 minutes.\n\nIf this wasn't you, ignore this email: nothing changes without this code.`,
  }),
  recoveryCodesCreated: (): Omit<MailMessage, "to"> => ({
    subject: "New recovery codes for your Meldiq account",
    text: "Hello,\n\nNew recovery codes were just created for your Meldiq account, and any older ones stopped working. Keep them somewhere safe and offline: each one can get you back in once if you lose access to this email address.\n\nIf this wasn't you, sign in, change your password and create new codes.",
  }),
  recoveryAddressChanged: (maskedNewEmail: string): Omit<MailMessage, "to"> => ({
    subject: "Your Meldiq account email was changed",
    text: `Hello,\n\nA recovery code was used to move your Meldiq account to ${maskedNewEmail}, and its password was changed. Every signed-in app was signed out.\n\nIf this wasn't you, contact your workspace administrator right away.`,
  }),
  recoveryFinished: (remaining: number): Omit<MailMessage, "to"> => ({
    subject: "Your Meldiq account is recovered",
    text: `Hello,\n\nYour Meldiq account now uses this email address. Sign in with it and your new password. You have ${remaining} recovery code${remaining === 1 ? "" : "s"} left; create a new set from Settings › Account once you're signed in.\n\nYour encrypted messages need your encryption recovery key or another signed-in phone; your email and password can't replace it.`,
  }),
  alreadyRegistered: (): Omit<MailMessage, "to"> => ({
    subject: "Someone tried to create a Meldiq account with your email",
    text: "Hello,\n\nSomeone tried to create a new Meldiq account with this email address, which already has an account. If it was you, sign in instead, or reset your password from the sign-in screen.\n\nIf it wasn't you, you don't need to do anything.",
  }),
};
