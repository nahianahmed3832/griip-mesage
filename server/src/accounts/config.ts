import path from "node:path";

/** Which deployment this is. Production refuses settings meant only for development (docs/ORBIT_2_ARCHITECTURE §9). */
export type AppEnv = "development" | "staging" | "production";

export type MailConfig =
  | { transport: "none" }
  /** Development: each message is written to a file in [dir] (mode 0600) instead of being sent. */
  | { transport: "file"; dir: string; from: string }
  | { transport: "ses"; from: string; region: string; credentials: { accessKeyId: string; secretAccessKey: string } | null };

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
  };
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

function mailConfig(env: NodeJS.ProcessEnv, dataDir: string, deployment: AppEnv): MailConfig {
  const transport = (env.MAIL_TRANSPORT ?? "none").trim();
  const from = (env.MAIL_FROM ?? "").trim();
  if (transport === "none") return { transport: "none" };
  if (transport === "file") {
    if (deployment === "production") throw new Error("MAIL_TRANSPORT=file writes codes to disk: not allowed with APP_ENV=production");
    return { transport: "file", dir: env.MAIL_FILE_DIR ?? path.join(dataDir, "mail"), from: from || "Orbit <no-reply@localhost>" };
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
  throw new Error("MAIL_TRANSPORT must be none, file or ses");
}

export function accountsConfig(env: NodeJS.ProcessEnv, dataDir: string, int: (name: string, def: number) => number): AccountsConfig {
  const deployment = appEnv(env.APP_ENV);
  const scryptLogN = Math.min(Math.max(int("PASSWORD_SCRYPT_LOG_N", MIN_PRODUCTION_LOG_N), 10), 20);
  if (deployment === "production" && scryptLogN < MIN_PRODUCTION_LOG_N) {
    throw new Error(`PASSWORD_SCRYPT_LOG_N below ${MIN_PRODUCTION_LOG_N} is for tests only: not allowed with APP_ENV=production`);
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
    legacy: {
      login: onOff(env.LEGACY_LOGIN, "LEGACY_LOGIN", true),
      registration: onOff(env.LEGACY_REGISTRATION, "LEGACY_REGISTRATION", true),
      migration: onOff(env.LEGACY_MIGRATION, "LEGACY_MIGRATION", true),
    },
  };
}
