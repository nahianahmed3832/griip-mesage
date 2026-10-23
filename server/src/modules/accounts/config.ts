import path from "node:path";

/** Which deployment this is. Production refuses settings meant only for development (docs/ORBIT_2_ARCHITECTURE §9). */
export type AppEnv = "development" | "staging" | "production";

export type MailConfig =
  | { transport: "none" }
  /** Development: each message is written to a file in [dir] (mode 0600) instead of being sent. */
  | { transport: "file"; dir: string; from: string }
  | { transport: "ses"; from: string; region: string; credentials: { accessKeyId: string; secretAccessKey: string } | null }
  /**
   * Any SMTP provider (Hostinger Mail for development and early testing, 2026-10-19). [secure]: TLS from the first byte
   * (port 465); otherwise STARTTLS is required, never plain text.
   */
  | { transport: "smtp"; from: string; host: string; port: number; secure: boolean; auth: { user: string; pass: string } | null };

export interface AccountsConfig {
  appEnv: AppEnv;
  mail: MailConfig;
  /** scrypt cost as log2(N). 15 with r = 8, p = 3 matches OWASP's scrypt guidance (32 MiB per hash). */
  scryptLogN: number;
  accessTokenTtlMs: number;
  refreshIdleMs: number;
  sessionMaxMs: number;
  codeTtlMs: number;
  codeMaxAttempts: number;
  /** Requests per minute per network to the unauthenticated account routes (they hash passwords or send email). */
  authRateLimitPerMinute: number;
  /** Account emails (codes, notices) one network may cause per hour. */
  emailsPerNetworkPerHour: number;
  legacy: {
    /** v1 device-key sign-in (apps before 2.0). */
    login: boolean;
    /** v1 sign-up (apps before 2.0). Turned off in production once the 2.0 app ships. */
    registration: boolean;
    /** The routes that link a legacy account to a new email-and-password account. */
    migration: boolean;
    /** Apps with the move must do it before opening (no "Later"). Old apps keep signing in while [login] is on. */
    migrationRequired: boolean;
  };
  /** Passkeys (2026-10-23, docs/PASSKEYS.md); off when [rpId] is null. */
  passkeys: PasskeyConfig;
}

export interface PasskeyConfig {
  /** The WebAuthn relying party: this server's host name as people reach it (PASSKEY_RP_ID, e.g. msg.meldiq.app). */
  rpId: string | null;
  /** Android apps allowed to use the passkeys: package and SHA-256 signing-certificate fingerprint (PASSKEY_ANDROID_APPS). */
  androidApps: { packageName: string; certSha256: string }[];
}

const HOST = /^(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/;
const PACKAGE = /^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/;
const FINGERPRINT = /^([0-9A-F]{2}:){31}[0-9A-F]{2}$/;

/**
 * PASSKEY_RP_ID: a host name (no scheme or port). PASSKEY_ANDROID_APPS: "package=FINGERPRINT" entries separated by
 * commas, the fingerprint as keytool prints it (32 hex pairs with colons). Malformed values stop the server starting.
 */
export function passkeyConfig(env: NodeJS.ProcessEnv): PasskeyConfig {
  const rp = (env.PASSKEY_RP_ID ?? "").trim().toLowerCase();
  if (rp && !HOST.test(rp)) throw new Error("PASSKEY_RP_ID must be a host name such as msg.example.com");
  const androidApps = (env.PASSKEY_ANDROID_APPS ?? "")
    .split(",")
    .map((e) => e.trim())
    .filter(Boolean)
    .map((e) => {
      const [packageName, cert] = e.split("=").map((x) => x?.trim() ?? "");
      const certSha256 = cert.toUpperCase();
      if (!PACKAGE.test(packageName) || !FINGERPRINT.test(certSha256)) {
        throw new Error("PASSKEY_ANDROID_APPS entries must be package=SHA-256 fingerprint (AA:BB:… as keytool prints it)");
      }
      return { packageName, certSha256 };
    });
  if (androidApps.length > 0 && !rp) throw new Error("PASSKEY_ANDROID_APPS needs PASSKEY_RP_ID");
  return { rpId: rp || null, androidApps };
}

const DAY_MS = 86_400_000;
const MINUTE_MS = 60_000;
const MIN_PRODUCTION_LOG_N = 15;

function onOff(v: string | undefined, name: string, def: boolean): boolean {
  const s = (v ?? "").trim().toLowerCase();
  if (s === "") return def;
  if (s === "on" || s === "true") return true;
  if (s === "off" || s === "false") return false;
  throw new Error(`${name} must be on or off`);
}

function appEnv(v: string | undefined): AppEnv {
  const s = (v ?? "").trim();
  if (s === "") return "development";
  if (s === "development" || s === "staging" || s === "production") return s;
  throw new Error("APP_ENV must be development, staging or production");
}

/**
 * The sender: MAIL_FROM ("Meldiq <no-reply@example.com>"), or MAIL_FROM_NAME and MAIL_FROM_ADDRESS. The name may not carry
 * characters that would break the header.
 */
function mailFrom(env: NodeJS.ProcessEnv): string {
  const from = (env.MAIL_FROM ?? "").trim();
  if (from) return from;
  const address = (env.MAIL_FROM_ADDRESS ?? "").trim();
  if (!address) return "";
  if (!/^[^\s@<>"]+@[^\s@<>"]+\.[^\s@<>"]+$/.test(address)) throw new Error("MAIL_FROM_ADDRESS must be an email address");
  const name = (env.MAIL_FROM_NAME ?? "").trim();
  if (/[\r\n<>"]/.test(name)) throw new Error("MAIL_FROM_NAME may not contain line breaks, quotes or angle brackets");
  return name ? `${name} <${address}>` : address;
}

function smtpConfig(env: NodeJS.ProcessEnv, from: string): MailConfig {
  if (!from) throw new Error("MAIL_TRANSPORT=smtp needs MAIL_FROM (or MAIL_FROM_ADDRESS), the mailbox the SMTP account may send as");
  const host = (env.SMTP_HOST ?? "").trim();
  if (!host || /[\s/]/.test(host)) throw new Error("MAIL_TRANSPORT=smtp needs SMTP_HOST (a host name, as the provider gives it)");
  const port = Number((env.SMTP_PORT ?? "465").trim());
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("SMTP_PORT must be a port number");
  const secureRaw = (env.SMTP_SECURE ?? "").trim().toLowerCase();
  if (secureRaw && !["true", "false"].includes(secureRaw)) throw new Error("SMTP_SECURE must be true or false");
  // Port 465 is TLS from the start; anything else upgrades with STARTTLS (required, see mailer.ts).
  const secure = secureRaw ? secureRaw === "true" : port === 465;
  const user = env.SMTP_USER?.trim();
  const pass = env.SMTP_PASS;
  if (!!user !== !!pass) throw new Error("Set both SMTP_USER and SMTP_PASS, or neither");
  return { transport: "smtp", from, host, port, secure, auth: user && pass ? { user, pass } : null };
}

function mailConfig(env: NodeJS.ProcessEnv, dataDir: string, deployment: AppEnv): MailConfig {
  const transport = (env.MAIL_TRANSPORT ?? "none").trim();
  const from = mailFrom(env);
  if (transport === "none") return { transport: "none" };
  if (transport === "file") {
    if (deployment === "production") throw new Error("MAIL_TRANSPORT=file writes codes to disk: not allowed with APP_ENV=production");
    return { transport: "file", dir: env.MAIL_FILE_DIR ?? path.join(dataDir, "mail"), from: from || "Meldiq <no-reply@localhost>" };
  }
  if (transport === "ses") {
    if (!from) throw new Error("MAIL_TRANSPORT=ses needs MAIL_FROM, an address verified in Amazon SES");
    const id = env.SES_ACCESS_KEY_ID?.trim();
    const secret = env.SES_SECRET_ACCESS_KEY?.trim();
    if (!!id !== !!secret) throw new Error("Set both SES_ACCESS_KEY_ID and SES_SECRET_ACCESS_KEY, or neither (to use the AWS default credential chain)");
    return {
      transport: "ses",
      from,
      region: (env.SES_REGION ?? "ap-southeast-1").trim(),
      credentials: id && secret ? { accessKeyId: id, secretAccessKey: secret } : null,
    };
  }
  if (transport === "smtp") return smtpConfig(env, from);
  throw new Error("MAIL_TRANSPORT must be none, file, ses or smtp");
}

export function accountsConfig(env: NodeJS.ProcessEnv, dataDir: string, int: (name: string, def: number) => number): AccountsConfig {
  const deployment = appEnv(env.APP_ENV);
  const scryptLogN = Math.min(Math.max(int("PASSWORD_SCRYPT_LOG_N", MIN_PRODUCTION_LOG_N), 10), 20);
  if (deployment === "production" && scryptLogN < MIN_PRODUCTION_LOG_N) {
    throw new Error(`PASSWORD_SCRYPT_LOG_N below ${MIN_PRODUCTION_LOG_N} is for tests only: not allowed with APP_ENV=production`);
  }
  const legacy = {
    login: onOff(env.LEGACY_LOGIN, "LEGACY_LOGIN", true),
    registration: onOff(env.LEGACY_REGISTRATION, "LEGACY_REGISTRATION", true),
    migration: onOff(env.LEGACY_MIGRATION, "LEGACY_MIGRATION", true),
    // Updated apps must move before they open (no "Later"); apps from before the move still sign in until they update.
    migrationRequired: onOff(env.LEGACY_MIGRATION_REQUIRED, "LEGACY_MIGRATION_REQUIRED", false),
  };
  if (legacy.migrationRequired && !legacy.migration) {
    throw new Error("LEGACY_MIGRATION_REQUIRED=on needs LEGACY_MIGRATION=on: the move it requires is switched off");
  }
  return {
    appEnv: deployment,
    mail: mailConfig(env, dataDir, deployment),
    scryptLogN,
    accessTokenTtlMs: Math.min(Math.max(int("ACCESS_TOKEN_TTL_SECONDS", 900), 60), 3600) * 1000,
    refreshIdleMs: Math.min(Math.max(int("REFRESH_TOKEN_IDLE_DAYS", 60), 1), 365) * DAY_MS,
    sessionMaxMs: Math.min(Math.max(int("SESSION_MAX_DAYS", 180), 1), 730) * DAY_MS,
    codeTtlMs: 15 * MINUTE_MS,
    codeMaxAttempts: 5,
    authRateLimitPerMinute: Math.max(int("AUTH_RATE_LIMIT_PER_MINUTE", 20), 1),
    emailsPerNetworkPerHour: Math.max(int("ACCOUNT_EMAILS_PER_NETWORK_PER_HOUR", 30), 1),
    legacy,
    passkeys: passkeyConfig(env),
  };
}
