import crypto from "node:crypto";
import { DAY_MS, networkKey, SlidingWindow } from "../../app/middleware/abuse.js";
import type { AppContext } from "../../app/context.js";
import { ApiError, humanCode, newId, normalizeCode, now, sha256Hex } from "../../shared/utilities/util.js";
import { mailTexts } from "../../infrastructure/mail/mailer.js";
import { hashPassword } from "./passwords.js";
import type { RecoveryCodeRow, RecoveryStore } from "./recoveryStore.js";
import { type AccountService, maskEmail, weakPassword } from "./service.js";
import type { AccountRow, ActiveSession } from "./store.js";

const MINUTE_MS = 60_000;
/** Codes in a set, and characters per code (31-letter alphabet: about 59 bits each). */
export const RECOVERY_CODE_COUNT = 10;
export const RECOVERY_CODE_LENGTH = 12;
const EMAIL_CODE_DIGITS = 6;
/** How long the person has to enter the code sent to the new address. */
const TICKET_TTL_MS = 30 * MINUTE_MS;
/** Wrong recovery codes per address and network in 15 minutes, and per address in a day, before recovery waits. */
const FAILURES_PER_NETWORK = 5;
const FAILURES_PER_ACCOUNT = 20;
const TICKET_BYTES = 32;

type Reply = { header: (k: string, v: string) => unknown };

const invalidRecovery = () => new ApiError(400, "invalid_recovery", "That email and recovery code don't match an account, or the code was already used.");
const invalidEmailCode = () => new ApiError(400, "invalid_code", "That code isn't right, or it has expired. Start the recovery again.");
const emailUnavailable = () => new ApiError(503, "email_unavailable", "This server can't send email right now, so the new address can't be confirmed. Try again later.");

const codeHash = (accountId: string, code: string) => sha256Hex(`${accountId}:recovery:${normalizeCode(code)}`);
const emailCodeHash = (ticketHash: string, code: string) => sha256Hex(`${ticketHash}:email:${code.replace(/\D/g, "")}`);

/** "ABCD-EFGH-JKLM": groups of four, easier to copy by hand; dashes and case are ignored when checking. */
export function formatRecoveryCode(code: string): string {
  return code.match(/.{1,4}/g)?.join("-") ?? code;
}

function equalHex(a: string, b: string): boolean {
  const x = Buffer.from(a, "hex");
  const y = Buffer.from(b, "hex");
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

export interface RecoveryStatus {
  /** Codes still usable; 0 with [createdAt] null means no set was ever made. */
  remaining: number;
  total: number;
  createdAt: number | null;
}

/**
 * Account recovery codes (docs/ACCOUNT_RECOVERY.md): a way back in for someone who lost the email address their
 * account uses. A valid code plus a new address proven with an emailed code changes the address and the password and
 * signs every session out. It restores account access only: encrypted history still needs the encryption recovery key
 * or another signed-in device, and nothing here can decrypt anything.
 *
 * - Codes are random, single-use and stored hashed; a new set revokes the old one; making or revoking a set needs the
 *   password again.
 * - Wrong codes are throttled per address and network and per address, and every failure gives one answer, so this
 *   doesn't reveal which addresses have accounts or codes.
 */
export class RecoveryService {
  private readonly failuresByNetwork = new SlidingWindow(FAILURES_PER_NETWORK, 15 * MINUTE_MS);
  private readonly failuresByAccount = new SlidingWindow(FAILURES_PER_ACCOUNT, DAY_MS);

  constructor(
    private readonly ctx: AppContext,
    private readonly accounts: AccountService,
    readonly store: RecoveryStore,
  ) {}

  private get cfg() {
    return this.ctx.config.accounts;
  }

  async status(accountId: string): Promise<RecoveryStatus> {
    const rows = await this.store.codes(accountId);
    return { remaining: rows.filter((r) => r.used_at === null).length, total: rows.length, createdAt: rows[0]?.created_at ?? null };
  }

  /** A new set (shown once; the old set stops working). Needs the password, like changing it. */
  async generate(s: ActiveSession, password: string, ip: string, reply: Reply): Promise<{ codes: string[]; createdAt: number }> {
    const a = await this.requireAccount(s);
    await this.accounts.checkPassword(a, password, ip, reply);
    const t = now();
    const codes = Array.from({ length: RECOVERY_CODE_COUNT }, () => humanCode(RECOVERY_CODE_LENGTH));
    const rows: RecoveryCodeRow[] = codes.map((c) => ({ id: newId("rc"), account_id: a.id, code_hash: codeHash(a.id, c), created_at: t, used_at: null }));
    await this.store.replaceCodes(a.id, rows);
    this.ctx.audit({ actorId: a.user_id, action: "account.recovery_codes_created", target: a.id, detail: String(RECOVERY_CODE_COUNT) });
    await this.notify(a.email, mailTexts.recoveryCodesCreated());
    return { codes: codes.map(formatRecoveryCode), createdAt: t };
  }

  async revoke(s: ActiveSession, password: string, ip: string, reply: Reply): Promise<void> {
    const a = await this.requireAccount(s);
    await this.accounts.checkPassword(a, password, ip, reply);
    const removed = await this.store.deleteCodes(a.id);
    this.ctx.audit({ actorId: a.user_id, action: "account.recovery_codes_revoked", target: a.id, detail: String(removed) });
  }

  /**
   * Step 1: the account's current email address, one of its recovery codes and the new address. The code is used up
   * here (a ticket carries the recovery on), and a 6-digit code goes to the new address.
   */
  async start(email: string, code: string, newEmail: string, ip: string, reply: Reply): Promise<{ ticket: string; email: string; expiresAt: number }> {
    if (!this.accounts.mailer.available) throw emailUnavailable();
    this.requireNotThrottled(email, ip, reply);
    const a = await this.accounts.store.accountByEmail(email);
    const row = a && !a.disabled_at && a.email_verified_at ? await this.matchingCode(a, code) : undefined;
    if (!a || !row) {
      this.recordFailure(email, ip);
      throw invalidRecovery();
    }
    if (newEmail === a.email) throw new ApiError(400, "same_email", "That's the address the account already uses. Sign in with it, or reset your password.");
    const other = await this.accounts.store.accountByEmail(newEmail);
    if (other && other.id !== a.id) throw new ApiError(409, "email_in_use", "Another account uses that address. Choose a different one.");
    const t = now();
    if (!(await this.store.useCode(a.id, row.code_hash, t))) throw invalidRecovery();
    const ticket = crypto.randomBytes(TICKET_BYTES).toString("base64url");
    const ticketHash = sha256Hex(ticket);
    const emailCode = crypto.randomInt(0, 10 ** EMAIL_CODE_DIGITS).toString().padStart(EMAIL_CODE_DIGITS, "0");
    const expiresAt = t + TICKET_TTL_MS;
    await this.store.insertTicket({
      ticket_hash: ticketHash,
      account_id: a.id,
      new_email: newEmail,
      email_code_hash: emailCodeHash(ticketHash, emailCode),
      attempts: 0,
      created_at: t,
      expires_at: expiresAt,
      used_at: null,
    });
    this.ctx.audit({ actorId: a.user_id, action: "account.recovery_started", target: a.id, detail: networkKey(ip) });
    try {
      await this.accounts.mailer.send({ to: newEmail, ...mailTexts.recoveryEmail(emailCode) });
    } catch (e) {
      // The code itself is never logged; only that sending failed. The recovery code stays used: start again with another.
      this.ctx.log.error({ err: e }, "recovery email not sent");
      throw new ApiError(503, "email_failed", "We couldn't send the email just now. Try again with another recovery code.");
    }
    return { ticket, email: newEmail, expiresAt };
  }

  /**
   * Step 2: the code sent to the new address and a new password. The address and password change together, every
   * session is signed out, and the old address is told (if anyone still reads it).
   */
  async complete(ticket: string, emailCode: string, newPassword: string): Promise<{ email: string; remainingCodes: number }> {
    const ticketHash = sha256Hex(ticket);
    const t = now();
    const row = await this.store.ticket(ticketHash);
    if (!row || row.used_at !== null || row.expires_at <= t || row.attempts >= this.cfg.codeMaxAttempts) throw invalidEmailCode();
    if (!equalHex(row.email_code_hash, emailCodeHash(ticketHash, emailCode))) {
      await this.store.countTicketAttempt(ticketHash);
      throw invalidEmailCode();
    }
    const weak = weakPassword(newPassword, row.new_email);
    if (weak) throw weak;
    const a = await this.accounts.store.accountById(row.account_id);
    if (!a || a.disabled_at) throw invalidEmailCode();
    const other = await this.accounts.store.accountByEmail(row.new_email);
    if (other && other.id !== a.id) throw new ApiError(409, "email_in_use", "Another account uses that address now. Start the recovery again with a different one.");
    const hash = await hashPassword(newPassword, this.cfg.scryptLogN);
    if (!(await this.store.useTicket(ticketHash, now()))) throw invalidEmailCode();
    const at = now();
    const oldEmail = a.email;
    if (!(await this.accounts.store.changeVerifiedEmail(a.id, row.new_email, at))) {
      throw new ApiError(409, "email_in_use", "Another account uses that address now. Start the recovery again with a different one.");
    }
    await this.accounts.store.updateAccount(a.id, { password_hash: hash, password_changed_at: at }, at);
    await this.accounts.revokeSessions(a.id, "account_recovered");
    this.failuresByAccount.clear(oldEmail);
    this.failuresByNetwork.clearPrefix(`${oldEmail}|`);
    const status = await this.status(a.id);
    this.ctx.audit({ actorId: a.user_id, action: "account.recovered", target: a.id, detail: `remaining:${status.remaining}` });
    await this.notify(oldEmail, mailTexts.recoveryAddressChanged(maskEmail(row.new_email)));
    await this.notify(row.new_email, mailTexts.recoveryFinished(status.remaining));
    return { email: row.new_email, remainingCodes: status.remaining };
  }

  purgeExpired(): Promise<number> {
    return this.store.purgeExpired(now());
  }

  private async requireAccount(s: ActiveSession): Promise<AccountRow> {
    const a = await this.accounts.store.accountById(s.account_id);
    if (!a) throw new ApiError(401, "unauthorized", "Sign in again.");
    return a;
  }

  /** The unused code of [a]'s set equal to [code], compared in constant time against every row. */
  private async matchingCode(a: AccountRow, code: string): Promise<RecoveryCodeRow | undefined> {
    const given = codeHash(a.id, code);
    let found: RecoveryCodeRow | undefined;
    for (const r of await this.store.codes(a.id)) if (equalHex(r.code_hash, given) && r.used_at === null) found = r;
    return found;
  }

  private keys(email: string, ip: string) {
    return { network: `${email}|${networkKey(ip)}`, account: email };
  }

  private recordFailure(email: string, ip: string): void {
    const k = this.keys(email, ip);
    this.failuresByNetwork.record(k.network);
    this.failuresByAccount.record(k.account);
  }

  private requireNotThrottled(email: string, ip: string, reply: Reply): void {
    const k = this.keys(email, ip);
    const wait = Math.max(this.failuresByNetwork.retryAfterMs(k.network), this.failuresByAccount.retryAfterMs(k.account));
    if (wait > 0) {
      reply.header("retry-after", String(Math.ceil(wait / 1000)));
      throw new ApiError(429, "recovery_throttled", "Too many wrong recovery codes. Wait a while and try again.");
    }
  }

  /** Security notices are best effort: a failed notice never blocks the action, and is logged without its content. */
  private async notify(to: string, text: { subject: string; text: string }): Promise<void> {
    if (!this.accounts.mailer.available) return;
    await this.accounts.mailer.send({ to, ...text }).catch((e) => this.ctx.log.warn({ err: e }, "recovery notice not sent"));
  }
}
