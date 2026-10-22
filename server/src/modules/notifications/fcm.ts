import crypto from "node:crypto";
import fs from "node:fs";
import type { FastifyBaseLogger } from "fastify";
import type { DB } from "../../infrastructure/database/db.js";
import type { Hub } from "../realtime/hub.js";
import type { Metrics } from "../administration/ops/metrics.js";
import { now } from "../../shared/utilities/util.js";

/**
 * Firebase Cloud Messaging as an optional second wake-up channel (docs/PUSH_FCM.md), beside UnifiedPush. Off unless
 * FCM_PROJECT_ID and FCM_SERVICE_ACCOUNT_FILE are set. Messages are data-only and carry nothing but `{"w":"1"}`: the
 * app then connects and fetches its encrypted messages, ringing calls and reminders itself, so Google sees that a
 * device was woken and when, never what for. The service-account key stays on the server (a file outside Git, read at
 * start); clients can't send pushes to anyone, only register their own device's token.
 *
 * HTTP v1 API: an OAuth 2.0 access token from a JWT signed with the service account's key (RS256, scope
 * firebase.messaging, 1 hour), then POST https://fcm.googleapis.com/v1/projects/<id>/messages:send.
 */
export interface FcmConfig {
  projectId: string;
  clientEmail: string;
  privateKey: string;
  /** Minimum time between two ordinary wake-ups of one device (calls and reminders don't wait). */
  wakeIntervalMs: number;
}

const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const TOKEN_URL = "https://oauth2.googleapis.com/token";
const TOKEN_LIFETIME_S = 3600;
const TIMEOUT_MS = 8000;
/** Tokens failing for this long are dropped (the app registers a fresh one when it next runs). */
const DROP_AFTER_FAILING_MS = 7 * 86_400_000;
/** FCM registration tokens are long opaque strings; this only bounds what is stored. */
export const MAX_FCM_TOKEN_LENGTH = 4096;

export type FetchFn = (url: string, init: { method: string; headers: Record<string, string>; body: string; signal: AbortSignal }) => Promise<{ status: number; json: () => Promise<unknown> }>;

/** Reads FCM_* settings; null when FCM isn't configured. Throws on a half or broken configuration. */
export function fcmConfig(env: NodeJS.ProcessEnv): FcmConfig | null {
  const projectId = env.FCM_PROJECT_ID?.trim();
  const file = env.FCM_SERVICE_ACCOUNT_FILE?.trim();
  if (!projectId && !file) return null;
  if (!projectId || !file) throw new Error("FCM needs both FCM_PROJECT_ID and FCM_SERVICE_ACCOUNT_FILE");
  if (!/^[a-z][a-z0-9-]{4,61}[a-z0-9]$/.test(projectId)) throw new Error("FCM_PROJECT_ID must be a Firebase project id such as meldiq-12345");
  let sa: { client_email?: unknown; private_key?: unknown; project_id?: unknown; type?: unknown };
  try {
    sa = JSON.parse(fs.readFileSync(file, "utf8"));
  } catch (e) {
    throw new Error(`FCM_SERVICE_ACCOUNT_FILE can't be read as a service-account JSON file: ${(e as Error).message.split("\n")[0]}`);
  }
  if (sa.type !== "service_account" || typeof sa.client_email !== "string" || typeof sa.private_key !== "string") {
    throw new Error("FCM_SERVICE_ACCOUNT_FILE must be a Google service-account key (type service_account)");
  }
  if (typeof sa.project_id === "string" && sa.project_id !== projectId) throw new Error("FCM_SERVICE_ACCOUNT_FILE belongs to another project than FCM_PROJECT_ID");
  const interval = Number(env.FCM_WAKE_INTERVAL_SECONDS ?? 30);
  return { projectId, clientEmail: sa.client_email, privateKey: sa.private_key, wakeIntervalMs: Math.min(Math.max(Number.isFinite(interval) ? interval : 30, 5), 3600) * 1000 };
}

const b64url = (b: Buffer | string) => Buffer.from(b).toString("base64url");

/** The signed assertion for Google's token endpoint (RFC 7523). */
export function serviceAccountAssertion(cfg: Pick<FcmConfig, "clientEmail" | "privateKey">, issuedAt: number): string {
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const iat = Math.floor(issuedAt / 1000);
  const claims = b64url(JSON.stringify({ iss: cfg.clientEmail, scope: SCOPE, aud: TOKEN_URL, iat, exp: iat + TOKEN_LIFETIME_S }));
  const signature = crypto.sign("RSA-SHA256", Buffer.from(`${header}.${claims}`), cfg.privateKey);
  return `${header}.${claims}.${b64url(signature)}`;
}

export class FcmService {
  private accessToken: { value: string; expiresAt: number } | null = null;
  private readonly lastSent = new Map<string, number>();
  private stopped = false;

  constructor(
    private readonly db: DB,
    private readonly hub: Hub,
    readonly config: FcmConfig,
    private readonly log: FastifyBaseLogger,
    private readonly fetchFn: FetchFn = (url, init) => fetch(url, init),
  ) {}

  /** Health view counters (docs/OPERATIONS.md); set by buildApp. Outcome kinds only. */
  metrics?: Metrics;

  register(deviceId: string, token: string): { kind: "fcm"; updatedAt: number } {
    const t = now();
    this.db.transaction(() => {
      // A token belongs to one installation: the latest device to register it wins.
      this.db.prepare("DELETE FROM fcm_tokens WHERE token = ? AND device_id != ?").run(token, deviceId);
      this.db
        .prepare(
          `INSERT INTO fcm_tokens (device_id, token, created_at, updated_at) VALUES (?, ?, ?, ?)
           ON CONFLICT(device_id) DO UPDATE SET token = excluded.token, updated_at = excluded.updated_at, failing_since = NULL`,
        )
        .run(deviceId, token, t, t);
    })();
    return { kind: "fcm", updatedAt: t };
  }

  unregister(deviceId: string): boolean {
    return this.db.prepare("DELETE FROM fcm_tokens WHERE device_id = ?").run(deviceId).changes > 0;
  }

  /** Something was queued for a device with no open socket: wake it (at most once per interval). */
  queued(deviceId: string): void {
    void this.wake(deviceId, false);
  }

  /** A call rings or a reminder fired for [userId]: wake each of their devices with no open socket, right away. */
  wakeUser(userId: string): void {
    const rows = this.db
      .prepare("SELECT f.device_id FROM fcm_tokens f JOIN devices d ON d.id = f.device_id WHERE d.user_id = ? AND d.revoked_at IS NULL")
      .all(userId) as { device_id: string }[];
    for (const r of rows) void this.wake(r.device_id, true);
  }

  stop(): void {
    this.stopped = true;
  }

  /** One data-only wake-up. Never rejects: a failed wake-up must not break what triggered it. */
  async wake(deviceId: string, urgent: boolean): Promise<"sent" | "skipped" | "failed" | "dropped"> {
    try {
      if (this.stopped || !this.db.open || this.hub.isDeviceOnline(deviceId)) return "skipped";
      const row = this.db
        .prepare(
          `SELECT f.token FROM fcm_tokens f JOIN devices d ON d.id = f.device_id JOIN users u ON u.id = d.user_id
           WHERE f.device_id = ? AND d.revoked_at IS NULL AND u.disabled_at IS NULL`,
        )
        .get(deviceId) as { token: string } | undefined;
      if (!row) return "skipped";
      const t = Date.now();
      if (!urgent && t - (this.lastSent.get(deviceId) ?? Number.NEGATIVE_INFINITY) < this.config.wakeIntervalMs) return "skipped";
      this.lastSent.set(deviceId, t);
      const res = await this.fetchFn(`https://fcm.googleapis.com/v1/projects/${this.config.projectId}/messages:send`, {
        method: "POST",
        headers: { authorization: `Bearer ${await this.token()}`, "content-type": "application/json" },
        body: JSON.stringify({ message: { token: row.token, data: { w: "1" }, android: { priority: "HIGH", ttl: urgent ? "60s" : "86400s" } } }),
        signal: AbortSignal.timeout(TIMEOUT_MS),
      });
      return this.record(deviceId, row.token, res.status, res.status >= 400 ? await res.json().catch(() => null) : null);
    } catch (err) {
      // Only the error's type: a message could quote the token.
      this.log.warn({ deviceId, errorType: err instanceof Error ? err.name : typeof err }, "fcm wake-up failed");
      this.metrics?.recordProvider("push.fcm", err);
      return "failed";
    }
  }

  private record(deviceId: string, token: string, status: number, body: unknown): "sent" | "failed" | "dropped" {
    this.metrics?.recordProvider("push.fcm", status >= 200 && status < 300 ? undefined : { code: `http_${status}` });
    const t = now();
    if (status >= 200 && status < 300) {
      this.db.prepare("UPDATE fcm_tokens SET last_wake_at = ?, failing_since = NULL WHERE device_id = ? AND token = ?").run(t, deviceId, token);
      return "sent";
    }
    const code = JSON.stringify(body ?? {}).match(/"(UNREGISTERED|INVALID_ARGUMENT|SENDER_ID_MISMATCH)"/)?.[1];
    // The token is no longer valid for this app: remove it (only that token; a newer one may have replaced it).
    if (status === 404 || code === "UNREGISTERED" || code === "SENDER_ID_MISMATCH" || (status === 400 && code === "INVALID_ARGUMENT")) {
      this.db.prepare("DELETE FROM fcm_tokens WHERE device_id = ? AND token = ?").run(deviceId, token);
      this.log.info({ deviceId, reason: code ?? `http_${status}` }, "fcm token removed");
      return "dropped";
    }
    if (status === 401) this.accessToken = null;
    this.db.prepare("UPDATE fcm_tokens SET failing_since = COALESCE(failing_since, ?) WHERE device_id = ? AND token = ?").run(t, deviceId, token);
    const since = (this.db.prepare("SELECT failing_since FROM fcm_tokens WHERE device_id = ?").get(deviceId) as { failing_since: number } | undefined)?.failing_since;
    if (since !== undefined && since <= t - DROP_AFTER_FAILING_MS) {
      this.db.prepare("DELETE FROM fcm_tokens WHERE device_id = ? AND token = ?").run(deviceId, token);
      return "dropped";
    }
    this.log.warn({ deviceId, reason: `http_${status}` }, "fcm wake-up failed");
    return "failed";
  }

  /** The connector check: signs in to Google with the service account (sends nothing to any phone). */
  async checkCredentials(): Promise<void> {
    this.accessToken = null;
    try {
      await this.token();
      this.metrics?.recordProvider("push.fcm");
    } catch (e) {
      this.metrics?.recordProvider("push.fcm", e);
      throw e;
    }
  }

  /** A cached OAuth access token for FCM, renewed a few minutes before it expires. */
  private async token(): Promise<string> {
    const t = Date.now();
    if (this.accessToken && this.accessToken.expiresAt - 300_000 > t) return this.accessToken.value;
    const res = await this.fetchFn(TOKEN_URL, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: serviceAccountAssertion(this.config, t) }).toString(),
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
    const body = (await res.json().catch(() => null)) as { access_token?: unknown; expires_in?: unknown } | null;
    if (res.status !== 200 || typeof body?.access_token !== "string") throw new Error(`Google token endpoint answered ${res.status}`);
    const expiresIn = typeof body.expires_in === "number" ? body.expires_in : TOKEN_LIFETIME_S;
    this.accessToken = { value: body.access_token, expiresAt: t + expiresIn * 1000 };
    return body.access_token;
  }
}
