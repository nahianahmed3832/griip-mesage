import path from "node:path";
import type { S3Config } from "./storage/s3.js";

const SEVEN_DAYS_S = 7 * 24 * 3600;
const MiB = 1024 * 1024;

export type StorageBackend = "local" | "s3";

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
  /** How long a 1:1 call waits for someone whose media connection dropped before it ends as "lost" (ms). */
  callPeerGraceMs: number;
  /** Profile photos (published by their owners, not end-to-end encrypted; served per each person's privacy settings). */
  profilePhotoDir: string;
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
  /**
   * Durable files (2026-10-17): an attachment a member keeps for their encrypted history (POST /v1/attachments/:id/keep)
   * stays this many days instead of the 7-day relay window (0: files are never kept longer). Kept bytes count against
   * the uploader's own quota for kept files, not the 7-day one.
   */
  attachmentKeepDays: number;
  attachmentUserKeptBytes: number;
  /**
   * Where files live: the server's disk (default) or Amazon S3 / a compatible store (docs/STORAGE_AND_DATA.md).
   * [readFallback]: the other backend, still read (and deleted from) after a switch, so files stored before it stay
   * available until they expire; new files go to [backend] only. [s3] is set whenever either one is S3.
   */
  storage: { backend: StorageBackend; readFallback: StorageBackend | null; s3: S3Config | null };
  /** Encrypted message history kept for each account (docs/MESSAGE_HISTORY.md). */
  history: { enabled: boolean; maxItemsPerUser: number; maxBytesPerUser: number; maxItemBytes: number; tombstoneDays: number };
  /**
   * Direct uploads from the app to S3 (2026-10-17). How long an upload URL may be started with, how long an upload may
   * take before it counts as abandoned (its object or parts are then deleted), and how many a person may have open.
   */
  directUploads: { urlTtlSeconds: number; sessionSeconds: number; maxPendingPerUser: number };
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
    profilePhotoDir: env.PROFILE_PHOTO_DIR ?? path.join(dataDir, "profile-photos"),
    callPeerGraceMs: int("CALL_PEER_GRACE_MS", 30_000),
    queueTtlSeconds: Math.max(queueTtl, 1),
    attachmentTtlSeconds: Math.min(int("ATTACHMENT_TTL_SECONDS", SEVEN_DAYS_S), SEVEN_DAYS_S),
    // 500 MB per file since 2026-10-17 (files over 25 MB use the chunked format; docs/ATTACHMENTS.md). The reverse proxy
    // in front must allow bodies this large (nginx client_max_body_size).
    attachmentMaxBytes: int("ATTACHMENT_MAX_BYTES", 500 * MiB),
    attachmentUserDailyBytes: int("ATTACHMENT_USER_DAILY_BYTES", 2048 * MiB),
    attachmentUserStoredBytes: int("ATTACHMENT_USER_STORED_BYTES", 4096 * MiB),
    attachmentTotalBytes: int("ATTACHMENT_TOTAL_BYTES", 10 * 1024 * MiB),
    attachmentMaxConcurrentPerUser: int("ATTACHMENT_MAX_CONCURRENT_PER_USER", 2),
    attachmentMaxConcurrent: int("ATTACHMENT_MAX_CONCURRENT", 8),
    attachmentMaxPendingBytes: int("ATTACHMENT_MAX_PENDING_BYTES", 1024 * MiB),
    attachmentKeepDays: Math.max(int("ATTACHMENT_KEEP_DAYS", 365), 0),
    attachmentUserKeptBytes: int("ATTACHMENT_USER_KEPT_BYTES", 10 * 1024 * MiB),
    storage: storageConfig(env, dataDir, int),
    directUploads: {
      urlTtlSeconds: Math.min(Math.max(int("S3_UPLOAD_URL_TTL_SECONDS", 900), 60), 3600),
      sessionSeconds: Math.min(Math.max(int("DIRECT_UPLOAD_SESSION_SECONDS", 6 * 3600), 600), 7 * 86_400),
      maxPendingPerUser: Math.max(int("DIRECT_UPLOAD_MAX_PENDING_PER_USER", 10), 1),
    },
    history: {
      enabled: env.HISTORY_ENABLED !== "false",
      maxItemsPerUser: Math.max(int("HISTORY_MAX_ITEMS_PER_USER", 500_000), 0),
      maxBytesPerUser: Math.max(int("HISTORY_MAX_BYTES_PER_USER", 1024 * MiB), 0),
      maxItemBytes: Math.min(Math.max(int("HISTORY_MAX_ITEM_BYTES", 64 * 1024), 1024), 1024 * 1024),
      tombstoneDays: Math.max(int("HISTORY_TOMBSTONE_DAYS", 90), 1),
    },
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

/**
 * STORAGE_BACKEND=s3 stores attachments and profile photos in a private S3 bucket instead of DATA_DIR. Credentials come
 * from S3_ACCESS_KEY_ID/S3_SECRET_ACCESS_KEY, or else from the AWS SDK's default chain (AWS_* variables, AWS_PROFILE,
 * or a `credential_process` such as IAM Roles Anywhere's signing helper); never from clients. Missing settings stop the
 * server at startup rather than silently falling back to disk.
 *
 * STORAGE_READ_FALLBACK names the other backend after a switch (s3 when going back to the disk, local when moving to
 * S3): files it holds stay readable, nothing is copied or removed from it except by normal expiry.
 */
function storageConfig(env: NodeJS.ProcessEnv, dataDir: string, int: (n: string, d: number) => number): Config["storage"] {
  const backend = storageBackend(env.STORAGE_BACKEND, "STORAGE_BACKEND") ?? "local";
  const readFallback = storageBackend(env.STORAGE_READ_FALLBACK, "STORAGE_READ_FALLBACK");
  if (readFallback === backend) throw new Error("STORAGE_READ_FALLBACK must differ from STORAGE_BACKEND");
  const usesS3 = backend === "s3" || readFallback === "s3";
  return { backend, readFallback, s3: usesS3 ? s3Config(env, dataDir, int) : null };
}

function storageBackend(v: string | undefined, name: string): StorageBackend | null {
  const s = (v ?? "").trim();
  if (s === "") return null;
  if (s !== "local" && s !== "s3") throw new Error(`${name} must be local or s3`);
  return s;
}

function s3Config(env: NodeJS.ProcessEnv, dataDir: string, int: (n: string, d: number) => number): S3Config {
  const need = (name: string, v: string | undefined) => {
    if (!v || !v.trim()) throw new Error(`S3 storage needs ${name}`);
    return v.trim();
  };
  const sse = (env.S3_SSE ?? "AES256").trim();
  if (sse !== "AES256" && sse !== "aws:kms" && sse !== "none") throw new Error("S3_SSE must be AES256, aws:kms or none");
  const prefix = (env.S3_PREFIX ?? "orbit/").trim();
  if (prefix && !/^[A-Za-z0-9_\-/.]{1,64}$/.test(prefix)) throw new Error("S3_PREFIX may only use letters, digits, _ - . /");
  const partSize = Math.max(int("S3_PART_SIZE", 16 * MiB), 5 * MiB);
  return {
    bucket: need("S3_BUCKET", env.S3_BUCKET),
    region: need("S3_REGION", env.S3_REGION ?? env.AWS_REGION),
    endpoint: env.S3_ENDPOINT?.trim() || null,
    forcePathStyle: env.S3_FORCE_PATH_STYLE === "true",
    prefix: prefix && !prefix.endsWith("/") ? prefix + "/" : prefix,
    credentials: s3Credentials(env),
    sse: sse === "none" ? null : sse,
    kmsKeyId: env.S3_KMS_KEY_ID?.trim() || null,
    // On by default with S3: downloads go straight from S3 after the server's checks (5-minute links).
    presignedDownloads: env.S3_PRESIGNED_DOWNLOADS !== "false",
    presignTtlSeconds: Math.min(Math.max(int("S3_PRESIGN_TTL_SECONDS", 300), 30), 3600),
    multipartThreshold: Math.max(int("S3_MULTIPART_THRESHOLD", 64 * MiB), partSize),
    partSize,
    spoolDir: env.S3_SPOOL_DIR ?? path.join(dataDir, "upload-spool"),
  };
}

/** Static keys when both are set (one without the other is a mistake); null: the AWS SDK's default credential chain. */
function s3Credentials(env: NodeJS.ProcessEnv): S3Config["credentials"] {
  const id = env.S3_ACCESS_KEY_ID?.trim();
  const secret = env.S3_SECRET_ACCESS_KEY?.trim();
  if (!id && !secret) return null;
  if (!id || !secret) throw new Error("Set both S3_ACCESS_KEY_ID and S3_SECRET_ACCESS_KEY, or neither (to use the AWS default credential chain)");
  return { accessKeyId: id, secretAccessKey: secret, sessionToken: env.S3_SESSION_TOKEN?.trim() || undefined };
}
