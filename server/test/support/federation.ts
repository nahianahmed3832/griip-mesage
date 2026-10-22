import Fastify from "fastify";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { buildCoreApp, type BuiltCore } from "../../src/modules/federation/core/app.js";
import { type CoreConfig, CoreNode } from "../../src/modules/federation/core/node.js";
import { type ServerDescriptor, ServerIdentity } from "../../src/modules/federation/identity.js";

export interface TestCore extends BuiltCore {
  url: string;
  dir: string;
}

/** Starts `n` core nodes that know each other as peers (http allowed, short leases). */
export async function startCores(n: number, overrides: Partial<CoreConfig> = {}): Promise<TestCore[]> {
  const dirs = Array.from({ length: n }, () => fs.mkdtempSync(path.join(os.tmpdir(), "griip-core-")));
  // Create keys first so every node can be configured with the others' public keys.
  const keys = dirs.map((dir) => {
    const tmp = new CoreNode({ ...baseCfg(dir), peers: [], trustedCoreKeys: [] });
    const pk = tmp.key.publicKey;
    tmp.close();
    return pk;
  });
  const cores: TestCore[] = [];
  for (let i = 0; i < n; i++) {
    const built = await buildCoreApp({ ...baseCfg(dirs[i]), peers: [], trustedCoreKeys: keys, revocationThreshold: Math.floor(n / 2) + 1, ...overrides }, { rateLimitPerMinute: 100000 });
    await built.app.listen({ host: "127.0.0.1", port: 0 });
    const addr = built.app.server.address();
    const url = `http://127.0.0.1:${typeof addr === "object" && addr ? addr.port : 0}`;
    cores.push({ ...built, url, dir: dirs[i] });
  }
  for (let i = 0; i < n; i++) cores[i].node.cfg.peers = cores.filter((_, j) => j !== i).map((c) => ({ url: c.url, publicKey: c.node.key.publicKey }));
  return cores;
}

function baseCfg(dir: string): CoreConfig {
  return {
    dataDir: dir,
    peers: [],
    trustedCoreKeys: [],
    revocationThreshold: 2,
    allowHttp: true,
    leaseMs: 60_000,
    domainReleaseMs: 90 * 86_400_000,
    registration: "open",
    allowlist: [],
    fetchTimeoutMs: 3000,
  };
}

export async function stopCores(cores: TestCore[]) {
  for (const c of cores) {
    await c.stop().catch(() => {});
    fs.rmSync(c.dir, { recursive: true, force: true });
  }
}

/** A stand-in federation endpoint: answers core challenges for `identity`, serving `descriptor()`. */
export async function fakeServer(identity: ServerIdentity, domain: string) {
  const app = Fastify();
  let current: ServerDescriptor | null = null;
  app.get("/.well-known/griip-server", async (req) => {
    const q = req.query as { challenge?: string; core?: string };
    return {
      descriptor: current,
      proof: q.challenge ? identity.selfSigned({ t: "griip.server.challenge", coreId: q.core, nonce: q.challenge }) : null,
    };
  });
  await app.listen({ host: "127.0.0.1", port: 0 });
  const addr = app.server.address();
  const url = `http://127.0.0.1:${typeof addr === "object" && addr ? addr.port : 0}`;
  const publish = (d?: Partial<{ domain: string; endpoint: string }>) => {
    current = identity.descriptor({ domain: d?.domain ?? domain, endpoint: d?.endpoint ?? url, capabilities: ["dm"] });
    return current;
  };
  return { app, url, identity, publish, get current() { return current; }, close: () => app.close() };
}

export function tempIdentity(): { identity: ServerIdentity; dir: string } {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "griip-sid-"));
  return { identity: ServerIdentity.loadOrCreate(dir), dir };
}
