import type { FastifyBaseLogger } from "fastify";
import type { PushDeps } from "../../src/push/service.js";
import type { PostOptions, WakeResult, WakeTarget } from "../../src/push/transport.js";

export interface SentWake {
  url: string;
  address: string;
  family: number;
  opts: PostOptions;
  at: number;
}

export interface LogLine {
  level: string;
  obj: unknown;
  msg?: string;
}

/**
 * DNS, network and log for push wake-ups, in memory. Records every lookup and request (the transport itself is tested
 * against a real HTTPS server in push_transport.test.ts) and answers with [respond].
 */
export class FakePushNetwork {
  sent: SentWake[] = [];
  lookups: string[] = [];
  logs: LogLine[] = [];
  readonly dns = new Map<string, { address: string; family: number }[]>();
  respond: (target: WakeTarget) => WakeResult = () => ({ status: 201 });

  reset(): void {
    this.sent = [];
    this.lookups = [];
    this.logs = [];
    this.respond = () => ({ status: 201 });
  }

  deps(): PushDeps {
    return {
      lookup: async (host) => {
        this.lookups.push(host);
        const found = this.dns.get(host);
        if (!found) throw Object.assign(new Error(`getaddrinfo ENOTFOUND ${host}`), { code: "ENOTFOUND" });
        return found;
      },
      post: async (target, opts) => {
        this.sent.push({ url: target.url.href, address: target.address, family: target.family, opts, at: Date.now() });
        return this.respond(target);
      },
      log: this.logger(),
    };
  }

  private logger(): FastifyBaseLogger {
    const at = (level: string) => (obj: unknown, msg?: string) => {
      this.logs.push({ level, obj, msg });
    };
    const log = {
      level: "info",
      fatal: at("fatal"),
      error: at("error"),
      warn: at("warn"),
      info: at("info"),
      debug: at("debug"),
      trace: at("trace"),
      silent: () => {},
      child: () => log,
    };
    return log as unknown as FastifyBaseLogger;
  }
}
