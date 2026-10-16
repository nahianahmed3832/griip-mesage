/**
 * HTTP API of a core node (docs/FEDERATION_PROTOCOL.md §3). Public and unauthenticated by design: every write is a
 * self-authenticating record (server-signed or core-threshold-signed), and every answer is signed by this node.
 */
import rateLimit from "@fastify/rate-limit";
import Fastify, { type FastifyInstance } from "fastify";
import { isDomain, isServerId } from "../federation/identity.js";
import { type CoreConfig, CoreError, CoreNode } from "./node.js";

export interface BuiltCore {
  app: FastifyInstance;
  node: CoreNode;
  /** Starts the replication and lease timers (not started in tests that drive them by hand). */
  startTimers: (replicateMs: number, sweepMs: number) => void;
  stop: () => Promise<void>;
}

export async function buildCoreApp(cfg: CoreConfig, opts: { logger?: boolean; fetchFn?: typeof fetch; rateLimitPerMinute?: number } = {}): Promise<BuiltCore> {
  const node = new CoreNode(cfg, opts.fetchFn);
  const app = Fastify({ logger: opts.logger ? { level: "info" } : false, bodyLimit: 256 * 1024 });
  await app.register(rateLimit, { global: true, max: opts.rateLimitPerMinute ?? 600, timeWindow: "1 minute" });

  app.setErrorHandler((err, _req, reply) => {
    if (err instanceof CoreError) return reply.code(err.status).send({ error: { code: err.code, message: err.message } });
    const status = (err as { statusCode?: number }).statusCode ?? 500;
    if (status === 429) return reply.code(429).send({ error: { code: "rate_limited", message: "Too many requests" } });
    if (status < 500) return reply.code(status).send({ error: { code: "bad_request", message: (err as Error).message } });
    app.log.error({ err }, "core error");
    return reply.code(500).send({ error: { code: "internal", message: "Core node error" } });
  });

  app.get("/core/v1/info", async () => ({
    coreId: node.coreId,
    publicKey: node.key.publicKey,
    peers: cfg.peers.map((p) => p.publicKey),
    revocationThreshold: cfg.revocationThreshold,
    registration: cfg.registration,
  }));

  app.post("/core/v1/register", { config: { rateLimit: { max: 30, timeWindow: "1 minute" } } }, async (req) => {
    const d = (req.body as { descriptor?: unknown })?.descriptor as never;
    const result = await node.register(d);
    return node.signed(`register:${result.serverId}`, result);
  });

  app.post("/core/v1/heartbeat", async (req) => node.heartbeat(req.body as never));
  app.post("/core/v1/decommission", async (req) => {
    const result = node.decommission(req.body as never);
    return node.signed(`server:${result.serverId}`, result);
  });
  app.post("/core/v1/revocations", async (req) => {
    const result = node.revoke(req.body as never);
    return node.signed(`server:${result.serverId}`, result);
  });

  app.get("/core/v1/domains/:domain", async (req) => {
    const { domain } = req.params as { domain: string };
    const d = decodeURIComponent(domain).toLowerCase();
    if (!isDomain(d)) throw new CoreError(400, "bad_domain", "Not a domain");
    return node.signed(`domain:${d}`, node.answerDomain(d));
  });

  app.get("/core/v1/servers/:serverId", async (req) => {
    const { serverId } = req.params as { serverId: string };
    if (!isServerId(serverId)) throw new CoreError(400, "bad_server_id", "Not a server id");
    return node.signed(`server:${serverId}`, node.answerServer(serverId));
  });

  app.get("/core/v1/feed", async (req) => {
    const q = req.query as { after?: string; limit?: string };
    const after = Math.max(0, Number.parseInt(q.after ?? "0", 10) || 0);
    const limit = Math.min(500, Math.max(1, Number.parseInt(q.limit ?? "200", 10) || 200));
    return { coreId: node.coreId, records: node.feed(after, limit) };
  });

  const timers: NodeJS.Timeout[] = [];
  return {
    app,
    node,
    startTimers: (replicateMs, sweepMs) => {
      if (replicateMs > 0) timers.push(setInterval(() => void node.replicateOnce().catch(() => {}), replicateMs));
      if (sweepMs > 0) timers.push(setInterval(() => node.sweep(), sweepMs));
    },
    stop: async () => {
      for (const t of timers) clearInterval(t);
      await app.close();
      node.close();
    },
  };
}

/** `url#publicKey,url#publicKey` → peers. */
export function parsePeers(v: string | undefined): { url: string; publicKey: string }[] {
  return (v ?? "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean)
    .map((s) => {
      const i = s.lastIndexOf("#");
      if (i < 0) throw new Error(`Peer "${s}" needs the form url#publicKey`);
      return { url: s.slice(0, i), publicKey: s.slice(i + 1) };
    });
}

export function loadCoreConfig(env: NodeJS.ProcessEnv = process.env): CoreConfig & { host: string; port: number; replicateMs: number; sweepMs: number } {
  const int = (n: string, d: number) => {
    const v = env[n];
    if (v === undefined || v === "") return d;
    const x = Number.parseInt(v, 10);
    if (!Number.isFinite(x)) throw new Error(`Invalid integer for ${n}`);
    return x;
  };
  const peers = parsePeers(env.CORE_PEERS);
  const registration = env.CORE_REGISTRATION === "allowlist" ? "allowlist" : "open";
  return {
    host: env.HOST ?? "0.0.0.0",
    port: int("PORT", 8090),
    dataDir: env.CORE_DATA_DIR ?? "core-data",
    peers,
    trustedCoreKeys: peers.map((p) => p.publicKey),
    revocationThreshold: int("CORE_REVOCATION_THRESHOLD", Math.floor((peers.length + 1) / 2) + 1),
    allowHttp: env.CORE_ALLOW_HTTP === "true",
    leaseMs: int("CORE_LEASE_SECONDS", 180) * 1000,
    domainReleaseMs: int("CORE_DOMAIN_RELEASE_DAYS", 90) * 86_400_000,
    registration,
    allowlist: (env.CORE_ALLOWLIST ?? "").split(",").map((s) => s.trim()).filter(Boolean),
    fetchTimeoutMs: int("CORE_FETCH_TIMEOUT_MS", 5000),
    replicateMs: int("CORE_REPLICATE_SECONDS", 15) * 1000,
    sweepMs: int("CORE_SWEEP_SECONDS", 15) * 1000,
  };
}
