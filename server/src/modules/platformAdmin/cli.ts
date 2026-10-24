import type { AppContext } from "../../app/context.js";
import type { Config } from "../../app/config.js";
import type { DB } from "../../infrastructure/database/db.js";
import { openMongo } from "../../infrastructure/database/mongo/index.js";
import { Accounts } from "../accounts/index.js";
import { PlatformAudit } from "./audit.js";
import { CLI_ACTOR, grantAdmin, resetAdminPasskeys, revokeAdmin } from "./bootstrap.js";
import { jobStateCounts } from "../workforce/index.js";
import { platformSetting, writeSetting } from "./settings.js";
import { PlatformAdminStore } from "./store.js";

/**
 * Operator commands for the Platform Admin Console (admin.js), run inside the api container by someone with root on
 * the server. This is the only way to create the first console admin.
 *
 *   platform-admin-grant <email> <role> [note]       grant or change a role; prints a one-time enrolment code when the
 *                                                     admin has no console passkey yet (valid 30 minutes)
 *   platform-admin-list                              every grant, active or revoked (no secrets)
 *   platform-admin-revoke <email> [reason]           revoke a grant and end its console sessions
 *   platform-admin-reset-passkeys <email> [reason]   lost device: remove console passkeys, print a new enrolment code
 *   platform-audit-verify                            check the platform audit's hash chain end to end
 *   ai-workforce-status                              AI Workforce's switches and its jobs by state (no content)
 *   ai-workforce-pause <reason>                      emergency stop for new AI jobs (running steps finish), audited;
 *                                                     the running server reads it on its next tick, console or not
 *   ai-workforce-resume <reason>                     lift the pause, audited
 */
export const PLATFORM_ADMIN_COMMANDS = [
  "platform-admin-grant",
  "platform-admin-list",
  "platform-admin-revoke",
  "platform-admin-reset-passkeys",
  "platform-audit-verify",
  "ai-workforce-status",
  "ai-workforce-pause",
  "ai-workforce-resume",
];

const USAGE: Record<string, string> = {
  "platform-admin-grant": "usage: platform-admin-grant <email> <super_admin|operations_admin|support_admin|billing_admin|auditor> [note]",
  "platform-admin-revoke": "usage: platform-admin-revoke <email> [reason]",
  "platform-admin-reset-passkeys": "usage: platform-admin-reset-passkeys <email> [reason]",
  "ai-workforce-pause": "usage: ai-workforce-pause <reason>",
  "ai-workforce-resume": "usage: ai-workforce-resume <reason>",
};

/** AI Workforce from the command line: the emergency pause works even when the console can't be reached. */
function aiWorkforceCommand(cmd: string, args: string[], db: DB, audit: PlatformAudit): number {
  if (cmd === "ai-workforce-status") {
    const keys = ["ai.enabled", "ai.workforce.enabled", "ai.workforce.paused", "ai.workforce.max_concurrency", "ai.workforce.approval_floor"] as const;
    console.log(JSON.stringify(Object.fromEntries(keys.map((k) => [k, platformSetting(db, k)])), null, 2));
    console.table(jobStateCounts(db));
    return 0;
  }
  const reason = args.join(" ").trim();
  if (reason.length < 8) {
    console.error(USAGE[cmd]);
    return 2;
  }
  const paused = cmd === "ai-workforce-pause";
  const r = writeSetting(db, "ai.workforce.paused", paused, "cli", Date.now());
  audit.record({
    action: "ai.workforce_controls",
    outcome: "success",
    actorAccountId: CLI_ACTOR.accountId,
    actorRole: CLI_ACTOR.role,
    requestId: CLI_ACTOR.requestId,
    network: CLI_ACTOR.network,
    targetType: "ai_workforce",
    targetId: "platform",
    reason,
    detail: { "ai.workforce.paused": `${JSON.stringify(r.before)} -> ${paused}`, via: "cli" },
  });
  console.log(paused ? "New AI Workforce jobs are paused. Running steps finish; queued jobs wait." : "AI Workforce jobs are accepted again.");
  return 0;
}

async function accountsFor(cfg: Config, db: DB): Promise<{ accounts: Accounts; close: () => Promise<void> }> {
  const ctx = { db, config: cfg, log: console } as unknown as AppContext;
  if (cfg.mongo) ctx.mongo = await openMongo(cfg.mongo, db);
  return { accounts: new Accounts(ctx), close: async () => ctx.mongo?.close() };
}

function printEnrolment(code: string | null, email: string): void {
  if (!code) {
    console.log(`${email} already has a console passkey: no enrolment code needed.`);
    return;
  }
  console.log(`Enrolment code for ${email}: ${code}`);
  console.log("Shown once, valid 30 minutes. Hand it over in person or on a channel you trust; it isn't stored readable anywhere.");
}

export async function runPlatformAdminCommand(cmd: string, args: string[], cfg: Config, db: DB): Promise<number> {
  const store = new PlatformAdminStore(db);
  const audit = new PlatformAudit(db);
  if (cmd.startsWith("ai-workforce-")) return aiWorkforceCommand(cmd, args, db, audit);
  if (cmd === "platform-audit-verify") {
    const r = audit.verify();
    console.log(JSON.stringify(r, null, 2));
    return r.brokenAt === null ? 0 : 1;
  }
  if (cmd === "platform-admin-list") {
    console.table(store.grants().map((g) => ({ account: g.account_id, role: g.role, grantedAt: new Date(g.granted_at).toISOString(), revoked: g.revoked_at ? new Date(g.revoked_at).toISOString() : "", passkeys: store.passkeys(g.account_id).length })));
    return 0;
  }
  const [email, ...rest] = args;
  if (!email || (cmd === "platform-admin-grant" && !rest[0])) {
    console.error(USAGE[cmd]);
    return 2;
  }
  const { accounts, close } = await accountsFor(cfg, db);
  try {
    const account = await accounts.store.accountByEmail(email.trim().toLowerCase());
    if (cmd === "platform-admin-grant") {
      printEnrolment(grantAdmin(store, audit, account, rest[0], CLI_ACTOR, rest.slice(1).join(" ") || null), email);
      return 0;
    }
    if (!account) throw new Error("No account has that email address.");
    const reason = rest.join(" ") || null;
    if (cmd === "platform-admin-revoke") {
      console.log(revokeAdmin(store, audit, account.id, CLI_ACTOR, reason) ? `revoked ${email}` : `${email} had no active console role`);
      return 0;
    }
    printEnrolment(resetAdminPasskeys(store, audit, account.id, CLI_ACTOR, reason), email);
    return 0;
  } finally {
    await close();
  }
}
