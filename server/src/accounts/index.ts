import type { AppContext, AuthInfo } from "../context.js";
import { now } from "../util.js";
import { IdentityService } from "./identity.js";
import { LegacyMigrationService } from "./legacy.js";
import { createMailer, type Mailer, meteredMailer } from "./mailer.js";
import { AccountService } from "./service.js";
import { MongoAccountStore, sqliteIdentityRows } from "../mongo/accounts.js";
import { type AccountStore, SqliteAccountStore } from "./store.js";
import { RecoveryService } from "./recovery.js";
import { type RecoveryStore, SqliteRecoveryStore } from "./recoveryStore.js";
import { MongoRecoveryStore } from "../mongo/recovery.js";
import { WorkspaceService } from "./workspaces.js";

/**
 * Orbit 2.0 accounts (docs/ORBIT_2_ARCHITECTURE_2026-10-18.md): email-and-password sign-in, sessions, device setup,
 * workspaces and the legacy migration, as one module of the existing server.
 */
export class Accounts {
  readonly store: AccountStore;
  readonly mailer: Mailer;
  readonly service: AccountService;
  readonly identity: IdentityService;
  readonly legacy: LegacyMigrationService;
  readonly workspaces: WorkspaceService;
  readonly recovery: RecoveryService;

  constructor(ctx: AppContext, opts: { mailer?: Mailer; store?: AccountStore; recoveryStore?: RecoveryStore } = {}) {
    // MONGODB_DOMAINS includes "accounts": the account rows live in MongoDB (docs/MONGODB.md).
    this.store =
      opts.store ?? (ctx.mongo?.owns("accounts") ? new MongoAccountStore(ctx.mongo, sqliteIdentityRows(ctx.db)) : new SqliteAccountStore(ctx.db));
    this.mailer = opts.mailer ?? createMailer(ctx.config.accounts.mail);
    this.service = new AccountService(ctx, this.store, meteredMailer(this.mailer, ctx.metrics));
    this.identity = new IdentityService(ctx, this.service);
    this.legacy = new LegacyMigrationService(ctx, this.service);
    this.workspaces = new WorkspaceService(ctx, this.store);
    const recoveryStore = opts.recoveryStore ?? (ctx.mongo?.owns("accounts") ? new MongoRecoveryStore(ctx.mongo) : new SqliteRecoveryStore(ctx.db));
    this.recovery = new RecoveryService(ctx, this.service, recoveryStore);
  }

  /**
   * The messaging identity behind a v2 access token, for the v1 routes and the WebSocket: only a session bound to a
   * device (one that has set up encryption) may use them.
   */
  async deviceAuth(token: string | undefined): Promise<AuthInfo | null> {
    const s = await this.service.sessions.authenticate(token, now());
    if (!s || !s.device_id || !s.user_id) return null;
    return { userId: s.user_id, deviceId: s.device_id, sessionId: s.id };
  }

  /**
   * Whether a v2 session still may be used from [deviceId] (an open WebSocket re-checks this, not its first access
   * token): not revoked or expired, account and device in good standing, still bound to that device.
   */
  sessionStillValid(sessionId: string, deviceId: string): Promise<boolean> {
    return this.service.sessions.stillValid(sessionId, deviceId, now());
  }

  async purgeExpired(): Promise<number> {
    return (await this.store.purgeExpired(now())) + (await this.recovery.purgeExpired());
  }
}
