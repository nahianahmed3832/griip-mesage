import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { buildApp, type BuiltApp } from "../../src/app.js";
import { loadConfig } from "../../src/config.js";
import type { PushDeps } from "../../src/push/service.js";
import { GriipClient } from "./griipClient.js";

export interface Harness extends BuiltApp {
  baseUrl: string;
  dir: string;
  close: () => Promise<void>;
  client: (username: string, displayName?: string) => Promise<GriipClient>;
}

let counter = 0;

/** [push]: replaces DNS and the network for push wake-ups (test/push_wakeup.test.ts). */
export async function startServer(env: Record<string, string> = {}, opts: { push?: PushDeps } = {}): Promise<Harness> {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "griip-test-"));
  const config = loadConfig({
    DATA_DIR: dir,
    LOG_LEVEL: "silent",
    LIVEKIT_API_KEY: "devkey",
    LIVEKIT_API_SECRET: "test-secret-that-is-at-least-32-characters-long",
    LIVEKIT_PUBLIC_URL: "ws://127.0.0.1:1", // unreachable on purpose: tests don't need a media server
    RATE_LIMIT_PER_MINUTE: "100000",
    // Most suites create many users; the invite gate has its own suite (registration_quotas.test.ts).
    REGISTRATION_MODE: "open",
    // Every test user registers from 127.0.0.1: the abuse limits have their own suite (abuse_controls.test.ts).
    REGISTRATION_PER_ADDRESS_PER_HOUR: "0",
    REGISTRATION_PER_ADDRESS_PER_DAY: "0",
    REGISTRATION_OPEN_PER_DAY: "0",
    NEW_ACCOUNT_LIMIT_DAYS: "0",
    ...env,
  } as NodeJS.ProcessEnv);
  const built = await buildApp(config, { logger: false, push: opts.push });
  await built.app.listen({ host: "127.0.0.1", port: 0 });
  const addr = built.app.server.address();
  const port = typeof addr === "object" && addr ? addr.port : 0;
  const baseUrl = `http://127.0.0.1:${port}`;
  // Federated test servers advertise the address they actually listen on (timers aren't started: tests drive them).
  if (built.ctx.config.federation && !built.ctx.config.federation.publicUrl) built.ctx.config.federation.publicUrl = baseUrl;
  const clients: GriipClient[] = [];
  return {
    ...built,
    baseUrl,
    dir,
    client: async (username, displayName) => {
      const c = new GriipClient(baseUrl, `${username}${++counter}`.toLowerCase(), displayName ?? username);
      await c.register();
      clients.push(c);
      return c;
    },
    close: async () => {
      built.ctx.federation?.stop();
      for (const c of clients) await c.disconnect();
      await built.app.close();
      built.ctx.db.close();
      fs.rmSync(dir, { recursive: true, force: true });
    },
  };
}

export async function expectStatus(p: Promise<unknown>, status: number): Promise<any> {
  try {
    await p;
  } catch (e: any) {
    if (e?.status === status) return e.body;
    throw new Error(`expected HTTP ${status}, got ${e?.status ?? e}`);
  }
  throw new Error(`expected HTTP ${status}, got success`);
}
