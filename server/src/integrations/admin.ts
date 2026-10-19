import type { Config } from "../config.js";
import type { DB } from "../db.js";
import { Authz } from "../authz.js";
import { Hub } from "../hub.js";
import type { MeldiqMongo } from "../mongo/client.js";
import { openMongo } from "../mongo/index.js";
import { now } from "../util.js";
import { Integrations } from "./index.js";
import { open, seal, sealedKeyId } from "./tokens.js";

/**
 * Operator commands for integrations (admin.js; docs/INTEGRATIONS.md › Operations). They print names, ids and counts,
 * never a token, secret or provider account.
 *
 *   integrations-status            configuration (what's missing, by name), installs, connections, queued and dead jobs
 *   integrations-reseal [--yes]    after a token-key rotation, re-seal stored tokens and webhook secrets under the new
 *                                  key (dry run without --yes); then INTEGRATIONS_TOKEN_KEY_PREVIOUS can be removed
 */
export const INTEGRATION_COMMANDS = ["integrations-status", "integrations-reseal"];

const quietLog = { info: () => undefined, warn: () => undefined, error: (o: object, m?: string) => console.error(m ?? "", o) } as never;

export async function runIntegrationCommand(cmd: string, args: string[], cfg: Config, db: DB): Promise<number> {
  let mongo: MeldiqMongo | undefined;
  if (cfg.mongo) mongo = await openMongo(cfg.mongo, db);
  try {
    const svc = new Integrations({
      db,
      authz: new Authz(db),
      hub: new Hub(db, cfg.abuse.wsMaxSocketsPerDevice),
      log: quietLog,
      audit: (e) => {
        db.prepare("INSERT INTO audit_log (at, actor_id, org_id, action, target, detail) VALUES (?, ?, ?, ?, ?, ?)").run(now(), e.actorId ?? null, e.orgId ?? null, e.action, e.target ?? null, e.detail ?? null);
      },
      config: cfg.integrations,
      mongo,
    });
    const rows = svc.store.rows;
    if (cmd === "integrations-status") {
      const c = cfg.integrations;
      console.log(`store: ${svc.store.backend} · public URL: ${c.publicUrl ?? "not set"} · token key: ${c.keyring ? `${c.keyring.active.id} (+${c.keyring.keys.size - 1} previous)` : "not set"} · sandbox: ${c.sandbox ? "on" : "off"}`);
      for (const [provider, missing] of Object.entries(c.missing)) {
        if (provider === "none") continue;
        console.log(`  ${provider.padEnd(10)} ${missing.length ? `needs ${missing.join(", ")}` : "configured"}`);
      }
      const count = async (table: string, where: Record<string, string>) => rows.count(table, where);
      for (const state of ["installed", "requested", "disabled", "uninstalled", "rejected"]) console.log(`installs ${state}: ${await count("integration_installs", { state })}`);
      for (const status of ["active", "expired", "revoked", "error"]) console.log(`accounts ${status}: ${await count("integration_accounts", { status })}`);
      console.log(`subscriptions: ${await rows.count("integration_subscriptions", {})} · webhooks: ${await rows.count("integration_hooks", {})}`);
      console.log(`jobs queued: ${await count("integration_jobs", { state: "queued" })} · running: ${await count("integration_jobs", { state: "running" })} · dead: ${await count("integration_jobs", { state: "dead" })}`);
      return 0;
    }
    if (cmd === "integrations-reseal") {
      const ring = cfg.integrations.keyring;
      if (!ring) {
        console.error("INTEGRATIONS_TOKEN_KEY isn't set");
        return 1;
      }
      const apply = args.includes("--yes");
      let creds = 0;
      let hooks = 0;
      let unreadable = 0;
      for (const r of await rows.find<Record<string, string | number | null>>("integration_credentials", {})) {
        if (sealedKeyId(String(r.access_token)) === ring.active.id && (!r.refresh_token || sealedKeyId(String(r.refresh_token)) === ring.active.id)) continue;
        const id = String(r.account_id);
        try {
          const access = open(ring, String(r.access_token), "access-token", id);
          const refresh = r.refresh_token ? open(ring, String(r.refresh_token), "refresh-token", id) : null;
          if (apply) {
            await rows.update("integration_credentials", { account_id: id }, {
              key_id: ring.active.id,
              access_token: seal(ring, access, "access-token", id),
              refresh_token: refresh ? seal(ring, refresh, "refresh-token", id) : null,
              updated_at: now(),
            });
          }
          creds++;
        } catch {
          unreadable++;
        }
      }
      for (const h of await rows.find<Record<string, string | number | null>>("integration_hooks", {})) {
        if (sealedKeyId(String(h.secret)) === ring.active.id) continue;
        const id = String(h.id);
        try {
          const secret = open(ring, String(h.secret), "incoming-webhook-secret", id);
          if (apply) await rows.update("integration_hooks", { id }, { secret: seal(ring, secret, "incoming-webhook-secret", id), updated_at: now() });
          hooks++;
        } catch {
          unreadable++;
        }
      }
      console.log(`${apply ? "re-sealed" : "would re-seal"}: ${creds} connection(s), ${hooks} webhook secret(s); unreadable with the configured keys: ${unreadable}`);
      if (!apply && creds + hooks > 0) console.log("dry run: nothing changed. Re-run with --yes.");
      if (unreadable) console.log("unreadable values were sealed with a key that is no longer configured: those connections must be made again.");
      return unreadable ? 1 : 0;
    }
    return 2;
  } finally {
    await mongo?.close();
  }
}
