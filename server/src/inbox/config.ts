import { secretFrom } from "../integrations/config.js";

/**
 * Operator settings for the Unified Inbox (docs/UNIFIED_INBOX.md). Nothing is invented: without a Meta app secret and
 * webhook verify token the Meta providers show "needs configuration", and TikTok stays "awaiting access" whatever is set.
 */
export interface InboxConfig {
  /** The Meta app's secret: signs Messenger and WhatsApp webhooks (X-Hub-Signature-256). */
  metaAppSecret: string | null;
  /** Instagram API with Instagram Login apps have their own secret; falls back to the Meta app secret. */
  instagramAppSecret: string | null;
  /** The token entered in Meta's webhook settings, echoed back during verification. */
  metaVerifyToken: string | null;
  graphVersion: string;
  /** Only with Meta's approval of the Human Agent feature: lets a person reply up to 7 days on Messenger and Instagram. */
  humanAgentTag: boolean;
  /** TikTok credentials, recorded only to show that setup has started; the connector stays awaiting access. */
  tiktokAppId: string | null;
  tickMs: number;
  /**
   * Development and device tests only: send Meta calls to a local stand-in (META_TEST_BASE_URL, e.g. a fake Meta on
   * localhost) instead of graph.facebook.com and graph.instagram.com. Refused in production.
   */
  testBaseUrl: string | null;
  /** Environment names still missing (names only, never values). */
  missing: { meta: string[]; instagram: string[] };
}

export function inboxConfig(env: NodeJS.ProcessEnv): InboxConfig {
  const metaAppSecret = secretFrom(env, "META_APP_SECRET");
  const instagramAppSecret = secretFrom(env, "INSTAGRAM_APP_SECRET") ?? metaAppSecret;
  const metaVerifyToken = secretFrom(env, "META_WEBHOOK_VERIFY_TOKEN");
  const graphVersion = (env.META_GRAPH_VERSION ?? "v25.0").trim();
  if (!/^v\d{1,3}\.\d$/.test(graphVersion)) throw new Error("META_GRAPH_VERSION must look like v25.0");
  const tick = Number(env.INBOX_TICK_MS ?? 3000);
  if (!Number.isInteger(tick) || tick < 0) throw new Error("INBOX_TICK_MS must be a whole number of milliseconds (0: off)");
  const testBaseUrl = env.META_TEST_BASE_URL?.trim().replace(/\/+$/, "") || null;
  if (testBaseUrl && (env.APP_ENV ?? "development") === "production") throw new Error("META_TEST_BASE_URL is for development and device tests only");
  if (testBaseUrl && !/^http:\/\/(127\.0\.0\.1|localhost)(:\d+)?$/.test(testBaseUrl)) throw new Error("META_TEST_BASE_URL must be a local address (http://127.0.0.1:<port>)");
  const missing = (pairs: [string, string | null][]) => pairs.filter(([, v]) => !v).map(([n]) => n);
  return {
    metaAppSecret,
    instagramAppSecret,
    metaVerifyToken,
    graphVersion,
    humanAgentTag: (env.META_HUMAN_AGENT_TAG ?? "off").trim() === "on",
    tiktokAppId: env.TIKTOK_APP_ID?.trim() || null,
    tickMs: tick,
    testBaseUrl,
    missing: {
      meta: missing([["META_APP_SECRET", metaAppSecret], ["META_WEBHOOK_VERIFY_TOKEN", metaVerifyToken]]),
      instagram: missing([["INSTAGRAM_APP_SECRET or META_APP_SECRET", instagramAppSecret], ["META_WEBHOOK_VERIFY_TOKEN", metaVerifyToken]]),
    },
  };
}
