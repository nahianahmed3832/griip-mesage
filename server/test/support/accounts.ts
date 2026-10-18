import type { Mailer, MailMessage } from "../../src/accounts/mailer.js";
import { canonical, GriipClient, sign } from "./griipClient.js";

/** Collects account emails instead of sending them. */
export class MemoryMailer implements Mailer {
  readonly kind = "memory" as const;
  readonly available = true;
  readonly sent: MailMessage[] = [];

  async send(m: MailMessage): Promise<void> {
    this.sent.push(m);
  }

  private last(to: string): MailMessage | undefined {
    return [...this.sent].reverse().find((m) => m.to === to);
  }

  /** The newest 6-digit verification code sent to [to]. */
  verifyCode(to: string): string {
    const m = [...this.sent].reverse().find((x) => x.to === to && /verification code/.test(x.subject));
    const code = m?.text.match(/\b(\d{6})\b/)?.[1];
    if (!code) throw new Error(`no verification code sent to ${to}`);
    return code;
  }

  /** The newest password reset code sent to [to] ("ABCDE-FGHJK"). */
  resetCode(to: string): string {
    const m = [...this.sent].reverse().find((x) => x.to === to && /reset/i.test(x.subject));
    const code = m?.text.match(/\b([A-Z2-9]{5}-[A-Z2-9]{5})\b/)?.[1];
    if (!code) throw new Error(`no reset code sent to ${to}`);
    return code;
  }

  lastSubject(to: string): string | undefined {
    return this.last(to)?.subject;
  }
}

let counter = 0;

/** A unique test address. */
export function email(name: string): string {
  return `${name}.${Date.now().toString(36)}${++counter}@example.test`;
}

export const PASSWORD = "correct horse battery";

/**
 * An Orbit 2.0 client: an email-and-password account on top of the reference protocol client, which supplies the keys
 * and messaging. After setting up a device, `token` is the v2 access token, so every v1 call and the socket use it.
 */
export class AccountClient extends GriipClient {
  accessToken = "";
  refreshToken = "";
  sessionId = "";
  accountId = "";

  constructor(
    baseUrl: string,
    public email: string,
    public password = PASSWORD,
    public name = "Test Person",
  ) {
    super(baseUrl, "", name);
  }

  /** Signs up and verifies the address with the code from [mailer]. */
  async signUp(mailer: MemoryMailer): Promise<this> {
    this.token = "";
    await this.req("POST", "/v2/auth/register", { name: this.name, email: this.email, password: this.password });
    await this.req("POST", "/v2/auth/email/verify", { email: this.email, code: mailer.verifyCode(this.email), password: this.password });
    return this;
  }

  useSession(r: any): any {
    this.accessToken = r.accessToken;
    this.refreshToken = r.refreshToken;
    this.sessionId = r.session.id;
    this.accountId = r.account.id;
    this.token = r.accessToken;
    if (r.account.identity) {
      this.userId = r.account.identity.userId;
      this.username = r.account.identity.username;
    }
    if (r.session.deviceId) this.deviceId = r.session.deviceId;
    return r;
  }

  /** Signs in; with [withDevice], proves this client's existing device key so the session is bound to it. */
  async signIn(withDevice = false): Promise<any> {
    this.token = "";
    let device: unknown;
    if (withDevice) {
      const { nonce } = await this.req("POST", "/v1/auth/challenge");
      device = { deviceId: this.deviceId, nonce, signature: sign(this.device, canonical.authProof("login", nonce, this.signingKeyB64)) };
    }
    return this.useSession(await this.req("POST", "/v2/auth/login", { email: this.email, password: this.password, deviceName: "test phone", ...(device ? { device } : {}) }));
  }

  /** Body for POST /v2/account/devices (or identity/reset) proving this client's device key. */
  async deviceBody(purpose: "register" | "add-device", extra: Record<string, unknown> = {}): Promise<any> {
    const { nonce } = await this.req("POST", "/v1/auth/challenge");
    return {
      ...extra,
      device: { name: "test phone", signingKey: this.signingKeyB64, encryptionKey: this.encKeyB64, certificate: this.certificate() },
      nonce,
      signature: sign(this.device, canonical.authProof(purpose, nonce, this.signingKeyB64)),
    };
  }

  /** The account's first device: creates its encryption identity. */
  async setUpIdentity(username?: string): Promise<any> {
    const r = await this.req("POST", "/v2/account/devices", await this.deviceBody("register", { identityKey: this.identityKeyB64, ...(username ? { username } : {}) }));
    this.userId = r.userId;
    this.deviceId = r.deviceId;
    this.username = r.username;
    return r;
  }

  /** A later device, certified by the identity this client holds (as after typing the recovery key). */
  async addThisDevice(): Promise<any> {
    const r = await this.req("POST", "/v2/account/devices", await this.deviceBody("add-device"));
    this.userId = r.userId;
    this.deviceId = r.deviceId;
    return r;
  }

  async refresh(): Promise<any> {
    return this.useSession(await this.req("POST", "/v2/auth/refresh", { refreshToken: this.refreshToken }));
  }

  /** Sign-up, verification, sign-in and the first device: an account ready to message. */
  static async ready(baseUrl: string, mailer: MemoryMailer, name = "ready"): Promise<AccountClient> {
    const c = new AccountClient(baseUrl, email(name), PASSWORD, name);
    await c.signUp(mailer);
    await c.signIn();
    await c.setUpIdentity();
    return c;
  }
}
