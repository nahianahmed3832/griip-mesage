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
  /**
   * @everyone policy (docs/MENTIONS.md). Group admins and channel managers may always mention everyone; other members
   * only in conversations with at most this many members (0: admins and managers only).
   */
  mentionAllMemberLimit: number;
  /** @everyone messages one person may send per conversation per hour (any role). */
  mentionAllPerHour: number;
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
  /** Federation with other Griip servers (docs/FEDERATION_PROTOCOL.md). Null when FEDERATION_DOMAIN isn't set. */
  federation: FederationConfig | null;
  logLevel: string;
  rateLimitPerMinute: number;
}

export interface FederationConfig {
  /** This server's federation domain: the part after ":" in user addresses (@name:domain). */
  domain: string;
  /** Public base URL other servers and core nodes reach this server at. Set after listen() in tests if empty. */
  publicUrl: string;
  /** Core nodes: url and public key of each. */
  coreNodes: { url: string; publicKey: string }[];
  /** How many core nodes must agree on a domain → server binding. */
  coreQuorum: number;
  /** Development only: allow plain http between servers and to core nodes. */
  allowHttp: boolean;
  /** "open": any registered, verified server may federate; "allowlist": only listed server ids or domains. */
  policy: "open" | "allowlist";
  allowlist: string[];
  heartbeatMs: number;
  outboxIntervalMs: number;
  /** Bound on queued federation items per destination server. */
  outboxMaxPerPeer: number;
  /** Where the server's identity key lives (default DATA_DIR/federation). */
  keyDir: string;
}

function federationConfig(env: NodeJS.ProcessEnv, dataDir: string, int: (n: string, d: number) => number): FederationConfig | null {
  const domain = (env.FEDERATION_DOMAIN ?? "").trim().toLowerCase();
  if (!domain) return null;
  const coreNodes = (env.FEDERATION_CORE_NODES ?? "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean)
    .map((s) => {
      const i = s.lastIndexOf("#");
      if (i < 0) throw new Error("FEDERATION_CORE_NODES entries need the form url#publicKey");
      return { url: s.slice(0, i), publicKey: s.slice(i + 1) };
    });
  return {
    domain,
    publicUrl: (env.FEDERATION_PUBLIC_URL ?? "").trim().replace(/\/$/, ""),
    coreNodes,
    coreQuorum: int("FEDERATION_CORE_QUORUM", Math.floor(coreNodes.length / 2) + 1),
    allowHttp: env.FEDERATION_ALLOW_HTTP === "true",
    policy: env.FEDERATION_POLICY === "allowlist" ? "allowlist" : "open",
    allowlist: (env.FEDERATION_ALLOWLIST ?? "").split(",").map((s) => s.trim()).filter(Boolean),
    heartbeatMs: int("FEDERATION_HEARTBEAT_SECONDS", 60) * 1000,
    outboxIntervalMs: int("FEDERATION_OUTBOX_INTERVAL_MS", 2000),
    outboxMaxPerPeer: int("FEDERATION_OUTBOX_MAX_PER_PEER", 10000),
    keyDir: env.FEDERATION_KEY_DIR ?? path.join(dataDir, "federation"),
  };
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
    mentionAllMemberLimit: Math.max(int("MENTION_ALL_MEMBER_LIMIT", 10), 0),
    mentionAllPerHour: Math.max(int("MENTION_ALL_PER_HOUR", 5), 1),
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
    federation: federationConfig(env, dataDir, int),
    logLevel: env.LOG_LEVEL ?? "info",
    rateLimitPerMinute: int("RATE_LIMIT_PER_MINUTE", 600),
  };
}
