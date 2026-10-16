import { buildApp } from "./app.js";
import { loadConfig } from "./config.js";
import { startJobs } from "./jobs.js";

const config = loadConfig();
const { app, ctx } = await buildApp(config);
const stopJobs = startJobs(ctx);

if (!config.livekit) app.log.warn("LIVEKIT_* not configured: voice/video calling is disabled");

const shutdown = async (signal: string) => {
  app.log.info({ signal }, "shutting down");
  stopJobs();
  ctx.federation?.stop();
  await app.close();
  ctx.db.close();
  process.exit(0);
};
process.on("SIGTERM", () => void shutdown("SIGTERM"));
process.on("SIGINT", () => void shutdown("SIGINT"));

await app.listen({ host: config.host, port: config.port });
if (ctx.federation) {
  ctx.federation.start(); // announce to the core nodes, then heartbeats and outbox delivery
  app.log.info({ serverId: ctx.federation.serverId, domain: ctx.federation.domain }, "federation enabled");
}
