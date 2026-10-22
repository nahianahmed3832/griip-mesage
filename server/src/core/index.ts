/**
 * Entry point of a core node: `node dist/core/index.js` (configuration in docs/FEDERATION_PROTOCOL.md §3.5).
 * A core node is a separate process with its own key and database; it doesn't need the messaging server.
 */
import { buildCoreApp, loadCoreConfig } from "../modules/federation/core/app.js";

const cfg = loadCoreConfig();
const core = await buildCoreApp(cfg, { logger: true });
core.startTimers(cfg.replicateMs, cfg.sweepMs);
await core.app.listen({ host: cfg.host, port: cfg.port });
core.app.log.info({ coreId: core.node.coreId, publicKey: core.node.key.publicKey, peers: cfg.peers.length }, "core node listening");

for (const sig of ["SIGINT", "SIGTERM"] as const) {
  process.on(sig, () => {
    void core.stop().then(() => process.exit(0));
  });
}
