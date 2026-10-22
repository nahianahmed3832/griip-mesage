import type { AppContext, AuthInfo } from "../../app/context.js";
import { now } from "../../shared/utilities/util.js";
import { IdentityService } from "./identity.js";
import { LegacyMigrationService } from "./legacy.js";
import { createMailer, type Mailer, meteredMailer } from "../../infrastructure/mail/mailer.js";
import { AccountService } from "./service.js";
import { MongoAccountStore, sqliteIdentityRows } from "./mongoStore.js";
import { type AccountStore, SqliteAccountStore } from "./store.js";
import { RecoveryService } from "./recovery.js";
import { type RecoveryStore, SqliteRecoveryStore } from "./recoveryStore.js";
import { MongoRecoveryStore } from "./mongoRecoveryStore.js";
import { WorkspaceService } from "./workspaces.js";
import { KeyBackupService } from "./keyBackup.js";
import { type KeyBackupStore, SqliteKeyBackupStore } from "./keyBackupStore.js";
import { MongoKeyBackupStore } from "./mongoKeyBackupStore.js";

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
  readonly keyBackups: KeyBackupService;

  constructor(ctx: AppContext, opts: { mailer?: Mailer; store?: AccountStore; recoveryStore?: RecoveryStore; keyBackupStore?: KeyBackupStore } = {}) {
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
    const keyBackupStore = opts.keyBackupStore ?? (ctx.mongo?.owns("accounts") ? new MongoKeyBackupStore(ctx.mongo) : new SqliteKeyBackupStore(ctx.db));
    this.keyBackups = new KeyBackupService(ctx, this.service, keyBackupStore);
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
    return (await this.store.purgeExpired(now())) + (await this.recovery.purgeExpired()) + (await this.keyBackups.purgeExpired());
  }
}
