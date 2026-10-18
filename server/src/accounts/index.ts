import type { AppContext, AuthInfo } from "../context.js";
import { now } from "../util.js";
import { IdentityService } from "./identity.js";
import { LegacyMigrationService } from "./legacy.js";
import { createMailer, type Mailer } from "./mailer.js";
import { AccountService } from "./service.js";
import { type AccountStore, SqliteAccountStore } from "./store.js";
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

  constructor(ctx: AppContext, opts: { mailer?: Mailer; store?: AccountStore } = {}) {
    this.store = opts.store ?? new SqliteAccountStore(ctx.db);
    this.mailer = opts.mailer ?? createMailer(ctx.config.accounts.mail);
    this.service = new AccountService(ctx, this.store, this.mailer);
    this.identity = new IdentityService(ctx, this.service);
    this.legacy = new LegacyMigrationService(ctx, this.service);
    this.workspaces = new WorkspaceService(ctx, this.store);
  }

  /**
   * The messaging identity behind a v2 access token, for the v1 routes and the WebSocket: only a session bound to a
   * device (one that has set up encryption) may use them.
   */
  deviceAuth(token: string | undefined): AuthInfo | null {
    const s = this.service.sessions.authenticate(token, now());
    if (!s || !s.device_id || !s.user_id) return null;
    return { userId: s.user_id, deviceId: s.device_id, sessionId: s.id };
  }

  /**
   * Whether a v2 session still may be used from [deviceId] (an open WebSocket re-checks this, not its first access
   * token): not revoked or expired, account and device in good standing, still bound to that device.
   */
  sessionStillValid(sessionId: string, deviceId: string): boolean {
    return this.service.sessions.stillValid(sessionId, deviceId, now());
  }

  purgeExpired(): number {
    return this.store.purgeExpired(now());
  }
}
