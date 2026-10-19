import type { FastifyBaseLogger } from "fastify";
import type { Authz } from "../authz.js";
import type { AppContext } from "../context.js";
import type { DB } from "../db.js";
import type { Entitlements } from "../entitlements.js";
import type { Hub } from "../hub.js";
import type { MeldiqMongo } from "../mongo/client.js";
import { domainTables } from "../mongo/client.js";
import { MongoRows, SqliteRows } from "../store/rows.js";
import * as accounts from "./accounts.js";
import type { IntegrationsConfig } from "./config.js";
import type { FetchLike } from "./http.js";
import { Kernel } from "./kernel.js";
import * as lifecycle from "./lifecycle.js";
import * as notifications from "./notifications.js";
import * as resources from "./resources.js";
import { IntegrationStore } from "./store.js";

export type { IntegrationsConfig } from "./config.js";

/**
 * The integrations service (docs/INTEGRATIONS.md): the kernel and every operation, plus the background worker. Its
 * records live in SQLite, or in MongoDB when MONGODB_DOMAINS includes "integrations" (same documents either way).
 */
export class Integrations {
  readonly kernel: Kernel;
  private timer: NodeJS.Timeout | null = null;

  constructor(deps: {
    db: DB;
    authz: Authz;
    hub: Hub;
    log: FastifyBaseLogger;
    audit: AppContext["audit"];
    config: IntegrationsConfig;
    entitlements?: Entitlements;
    mongo?: MeldiqMongo;
    fetch?: FetchLike;
    now?: () => number;
  }) {
    const rows = deps.mongo?.owns("integrations") ? new MongoRows(deps.mongo, domainTables(deps.db, ["integrations"])) : new SqliteRows(deps.db);
    this.kernel = new Kernel({
      db: deps.db,
      authz: deps.authz,
      hub: deps.hub,
      log: deps.log,
      audit: deps.audit,
      config: deps.config,
      entitlements: deps.entitlements,
      store: new IntegrationStore(rows),
      fetch: deps.fetch ?? ((url, init) => fetch(url, init)),
      now: deps.now ?? Date.now,
    });
  }

  get store(): IntegrationStore {
    return this.kernel.deps.store;
  }

  get config(): IntegrationsConfig {
    return this.kernel.deps.config;
  }

  /** Background delivery of queued events (every few seconds), stopped on shutdown. */
  start(intervalMs = 3000): void {
    if (this.timer) return;
    this.timer = setInterval(() => {
      notifications.runJobs(this.kernel).catch((e) => this.kernel.deps.log.error({ err: e }, "integration jobs failed"));
    }, intervalMs);
    this.timer.unref();
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  runJobs = () => notifications.runJobs(this.kernel);
  usage = (orgId: string) => notifications.usage(this.kernel, orgId);
  /** Retention: expired flows, deliveries, notifications and old finished jobs. */
  purge = (now: number) => this.store.purge(now, now - this.config.jobRetentionDays * 86_400_000);
  forgetWorkspace = (orgId: string) => accounts.forgetWorkspace(this.kernel, orgId);
  forgetMember = (orgId: string, userId: string) => accounts.forgetMember(this.kernel, orgId, userId);
  forgetUser = (userId: string) => accounts.forgetUser(this.kernel, userId);
  forgetConversation = (conversationId: string) => this.store.deleteEventsForConversation(conversationId);
}

export { accounts, lifecycle, notifications, resources };
