import path from "node:path";

const SEVEN_DAYS_S = 7 * 24 * 3600;
const MiB = 1024 * 1024;

function registrationMode(v: string | undefined): "invite" | "open" {
  if (v === undefined || v === "" || v === "invite") return "invite";
  if (v === "open") return "open";
  throw new Error("REGISTRATION_MODE must be invite or open");
}

function intFrom(env: NodeJS.ProcessEnv, name: string, def: number): number {
  const v = env[name];
  if (v === undefined || v === "") return def;
  const n = Number.parseInt(v, 10);
  if (!Number.isFinite(n)) throw new Error(`Invalid integer for ${name}`);
  return n;
}

export interface Config {
  host: string;
  port: number;
  databasePath: string;
  attachmentDir: string;
  /** Delivery queue TTL in seconds. Hard-capped at 7 days. */
  queueTtlSeconds: number;
  attachmentTtlSeconds: number;
  attachmentMaxBytes: number;
  /** Upload quotas protecting the shared VPS (bytes). */
  attachmentUserDailyBytes: number;
  attachmentUserStoredBytes: number;
  attachmentTotalBytes: number;
  /** Uploads streaming at once, per user and for the whole server, and the bytes they may have in flight. */
  attachmentMaxConcurrentPerUser: number;
  attachmentMaxConcurrent: number;
  attachmentMaxPendingBytes: number;
  /** "invite" (default): registering needs a registration or org invite code. "open": anyone may register. */
  registrationMode: "invite" | "open";
  sessionTtlSeconds: number;
  cleanupIntervalSeconds: number;
  ringTimeoutSeconds: number;
  /** Ended calls (and their participant rows) older than this are deleted (RETENTION.md). */
  callRecordRetentionDays: number;
  /** Audit-log rows older than this are deleted (RETENTION.md). */
  auditRetentionDays: number;
  livekit: {
    /** URL clients use to reach LiveKit (ws:// or wss://). */
    publicUrl: string;
    /** URL the API uses for LiveKit's server API (http:// or https://). */
    apiUrl: string;
    apiKey: string;
    apiSecret: string;
  } | null;
  trustProxy: boolean;
  /** Optional display name for this instance, shown on client setup screens (INSTANCE_NAME). */
  instanceName: string | null;
  logLevel: string;
  rateLimitPerMinute: number;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const int = (name: string, def: number) => intFrom(env, name, def);
  const dataDir = env.DATA_DIR ?? path.resolve("data");
  const lkKey = env.LIVEKIT_API_KEY;
  const lkSecret = env.LIVEKIT_API_SECRET;
  const lkPublic = env.LIVEKIT_PUBLIC_URL;
  if (lkSecret && lkSecret.length < 32) {
    throw new Error("LIVEKIT_API_SECRET must be at least 32 characters");
  }
  const queueTtl = Math.min(int("QUEUE_TTL_SECONDS", SEVEN_DAYS_S), SEVEN_DAYS_S);
  return {
    host: env.HOST ?? "0.0.0.0",
    port: int("PORT", 8080),
    databasePath: env.DATABASE_PATH ?? path.join(dataDir, "griip.sqlite"),
    attachmentDir: env.ATTACHMENT_DIR ?? path.join(dataDir, "attachments"),
    queueTtlSeconds: Math.max(queueTtl, 1),
    attachmentTtlSeconds: Math.min(int("ATTACHMENT_TTL_SECONDS", SEVEN_DAYS_S), SEVEN_DAYS_S),
    attachmentMaxBytes: int("ATTACHMENT_MAX_BYTES", 25 * MiB),
    attachmentUserDailyBytes: int("ATTACHMENT_USER_DAILY_BYTES", 250 * MiB),
    attachmentUserStoredBytes: int("ATTACHMENT_USER_STORED_BYTES", 1024 * MiB),
    attachmentTotalBytes: int("ATTACHMENT_TOTAL_BYTES", 5 * 1024 * MiB),
    attachmentMaxConcurrentPerUser: int("ATTACHMENT_MAX_CONCURRENT_PER_USER", 2),
    attachmentMaxConcurrent: int("ATTACHMENT_MAX_CONCURRENT", 8),
    attachmentMaxPendingBytes: int("ATTACHMENT_MAX_PENDING_BYTES", 200 * MiB),
    registrationMode: registrationMode(env.REGISTRATION_MODE),
    sessionTtlSeconds: int("SESSION_TTL_SECONDS", 7 * 24 * 3600),
    cleanupIntervalSeconds: Math.max(int("CLEANUP_INTERVAL_SECONDS", 60), 1),
    ringTimeoutSeconds: int("RING_TIMEOUT_SECONDS", 45),
    callRecordRetentionDays: Math.max(int("CALL_RECORD_RETENTION_DAYS", 90), 1),
    auditRetentionDays: Math.max(int("AUDIT_RETENTION_DAYS", 365), 30),
    livekit:
      lkKey && lkSecret && lkPublic
        ? {
            publicUrl: lkPublic,
            apiUrl: env.LIVEKIT_API_URL ?? lkPublic.replace(/^ws/, "http"),
            apiKey: lkKey,
            apiSecret: lkSecret,
          }
        : null,
    trustProxy: env.TRUST_PROXY === "true",
    instanceName: (env.INSTANCE_NAME ?? "").trim().slice(0, 80) || null,
    logLevel: env.LOG_LEVEL ?? "info",
    rateLimitPerMinute: int("RATE_LIMIT_PER_MINUTE", 600),
  };
}
