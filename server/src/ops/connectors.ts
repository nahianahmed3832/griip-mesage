import type { AppContext } from "../context.js";
import { AI_ENV, AI_PROVIDER_IDS } from "../ai/provider.js";
import { CONNECTORS } from "../integrations/catalog.js";
import type { ConnectorDef } from "../integrations/types.js";
import { now } from "../util.js";
import { storageCheck } from "./health.js";
import type { ProviderStats as ProviderMetric } from "./metrics.js";

/**
 * The connector register (docs/CONNECTORS.md): every outside service Meldiq can use, in one list for platform admins,
 * with its real state. A connector is "working" only after a measured success (a call, a check or a validated
 * connection); configured but never exercised is "configured", never "working". Names of missing settings only, never
 * values; counts only, never who or what.
 */
export type ConnectorState = "working" | "configured" | "failing" | "needs_configuration" | "disabled" | "awaiting_provider" | "planned";

export interface ConnectorStatus {
  id: string;
  name: string;
  category: string;
  state: ConnectorState;
  /** Environment names still missing (names only). */
  missing: string[];
  /** One plain sentence about what it does here and now. */
  detail: string;
  /** Counts only (workspaces, connections, devices…). */
  usage: Record<string, number>;
  lastOkAt: number | null;
  lastFailureAt: number | null;
  lastErrorKind: string | null;
  /** True when [checkConnector] can test it without sending anything to a person or spending money. */
  checkable: boolean;
  /** What the owner has to do outside Meldiq, when anything. */
  ownerAction: string | null;
  docs: string;
}

interface Signal {
  lastOkAt: number | null;
  lastFailureAt: number | null;
  lastErrorKind: string | null;
}

/** A failure newer than the last success counts as "failing" for this long, then as configured-but-unverified. */
const FAILING_WINDOW_MS = 24 * 3600_000;
const NO_SIGNAL: Signal = { lastOkAt: null, lastFailureAt: null, lastErrorKind: null };
/** Only short machine kinds are shown; anything longer (a provider's message) is reduced to "error". */
const errorKind = (s: string | null | undefined): string | null => (s ? (/^[a-z0-9_.:-]{1,40}$/i.test(s) ? s : "error") : null);
const latest = (...xs: (number | null | undefined)[]): number | null => xs.reduce<number | null>((a, x) => (typeof x === "number" && (a === null || x > a) ? x : a), null);

function metricSignal(metrics: readonly ProviderMetric[], ...names: string[]): Signal {
  return metrics
    .filter((m) => names.includes(m.name))
    .reduce<Signal>(
      (a, m) => ({
        lastOkAt: latest(a.lastOkAt, m.lastOkAt),
        lastFailureAt: latest(a.lastFailureAt, m.lastFailureAt),
        lastErrorKind: m.lastFailureAt !== null && m.lastFailureAt === latest(a.lastFailureAt, m.lastFailureAt) ? m.lastErrorKind : a.lastErrorKind,
      }),
      NO_SIGNAL,
    );
}

function merge(a: Signal, b: Signal): Signal {
  const failure = latest(a.lastFailureAt, b.lastFailureAt);
  return { lastOkAt: latest(a.lastOkAt, b.lastOkAt), lastFailureAt: failure, lastErrorKind: failure === b.lastFailureAt && failure !== null ? b.lastErrorKind : a.lastErrorKind };
}

/** The state from configuration and measured signals (pure: unit-tested). */
export function stateFor(missing: readonly string[], signal: Signal, t: number, opts: { disabled?: boolean; awaiting?: boolean; planned?: boolean } = {}): ConnectorState {
  if (opts.planned) return "planned";
  if (opts.awaiting) return "awaiting_provider";
  if (opts.disabled) return "disabled";
  if (missing.length) return "needs_configuration";
  const failingNow = signal.lastFailureAt !== null && (signal.lastOkAt === null || signal.lastFailureAt > signal.lastOkAt);
  if (failingNow && t - (signal.lastFailureAt ?? 0) < FAILING_WINDOW_MS) return "failing";
  return signal.lastOkAt !== null ? "working" : "configured";
}

type Row = Omit<ConnectorStatus, "state" | keyof Signal> & { signal: Signal; flags?: Parameters<typeof stateFor>[3] };

const finish = (r: Row, t: number): ConnectorStatus => {
  const { signal, flags, ...rest } = r;
  return { ...rest, ...signal, state: stateFor(r.missing, signal, t, flags) };
};

const sqlCount = (ctx: AppContext, sql: string, ...p: unknown[]): number => (ctx.db.prepare(sql).get(...p) as { n: number | null }).n ?? 0;

/** Catalog connectors (docs/INTEGRATIONS.md): OAuth apps a workspace installs. */
async function catalogRows(ctx: AppContext): Promise<Row[]> {
  const cfg = ctx.config.integrations;
  const rows = ctx.integrations.store.rows;
  const out: Row[] = [];
  for (const def of CONNECTORS) {
    const planned = def.availability === "coming_soon";
    if (planned) {
      out.push({ id: def.id, name: def.name, category: def.category, missing: [], detail: def.summary, usage: {}, checkable: false, ownerAction: null, docs: "docs/INTEGRATIONS.md", signal: NO_SIGNAL, flags: { planned: true } });
      continue;
    }
    const installs = await rows.find<{ state: string; last_event_at: number | null; last_error: string | null; last_error_at: number | null }>("integration_installs", { connector_id: def.id });
    const accounts = await rows.find<{ status: string; last_validated_at: number | null; last_used_at: number | null; last_error: string | null; last_error_at: number | null }>("integration_accounts", { connector_id: def.id });
    const failed = [...installs, ...accounts].filter((r) => r.last_error_at !== null).sort((a, b) => (b.last_error_at ?? 0) - (a.last_error_at ?? 0))[0];
    const signal: Signal = {
      lastOkAt: latest(...accounts.map((a) => a.last_validated_at), ...accounts.map((a) => a.last_used_at), ...installs.map((i) => i.last_event_at)),
      lastFailureAt: failed?.last_error_at ?? null,
      lastErrorKind: errorKind(failed?.last_error),
    };
    const missing = def.provider === "none" ? (cfg.keyring ? [] : ["INTEGRATIONS_TOKEN_KEY"]) : [...(cfg.missing[def.provider] ?? [])];
    const sandbox = def.availability === "sandbox";
    out.push({
      id: def.id,
      name: def.name,
      category: def.category,
      missing: sandbox && !cfg.sandbox ? ["INTEGRATIONS_SANDBOX"] : missing,
      detail: def.summary,
      usage: {
        workspaces: installs.filter((i) => i.state === "installed").length,
        connections: accounts.filter((a) => a.status === "active").length,
        needReconnect: accounts.filter((a) => a.status === "expired" || a.status === "revoked" || a.status === "error").length,
      },
      checkable: false,
      ownerAction: missing.length && !sandbox ? ownerActionFor(def) : null,
      docs: def.docsUrl || "docs/INTEGRATIONS.md",
      signal,
      flags: { disabled: cfg.disabled.has(def.id) },
    });
  }
  return out;
}

function ownerActionFor(def: ConnectorDef): string {
  const review = def.reviewNote ? ` ${def.reviewNote}` : "";
  return `Register the ${def.publisher} app with the redirect URI <INTEGRATIONS_PUBLIC_URL>/v1/integrations/oauth/callback and put its client id and secret in the server's secret files.${review}`;
}

/** Unified Inbox channels (docs/UNIFIED_INBOX.md). */
function inboxRows(ctx: AppContext, metrics: readonly ProviderMetric[]): Row[] {
  const c = ctx.config.inbox;
  const webhook = metricSignal(metrics, "inbox.webhook");
  const names: Record<string, string> = { messenger: "Facebook Messenger", instagram: "Instagram messaging", whatsapp: "WhatsApp Business Platform", tiktok: "TikTok Business Messaging" };
  return ["messenger", "instagram", "whatsapp", "tiktok"].map((p) => {
    const accounts = ctx.db.prepare("SELECT status, last_error, last_event_at, updated_at FROM inbox_accounts WHERE provider = ? AND live_key IS NOT NULL").all(p) as { status: string; last_error: string | null; last_event_at: number | null; updated_at: number }[];
    const bad = accounts.filter((a) => a.status !== "active");
    const own: Signal = {
      lastOkAt: latest(...accounts.map((a) => a.last_event_at)),
      lastFailureAt: bad.length ? latest(...bad.map((a) => a.updated_at)) : null,
      lastErrorKind: errorKind(bad[0]?.last_error),
    };
    const tiktok = p === "tiktok";
    return {
      id: `inbox-${p}`,
      name: names[p],
      category: "messaging",
      missing: tiktok ? [] : p === "instagram" ? c.missing.instagram : c.missing.meta,
      detail: tiktok
        ? "Shown as awaiting access: TikTok must approve the app, and Business Messaging isn't offered in the US, EEA, Switzerland or UK."
        : "Customer conversations in the Unified Inbox through Meta's official API, with signed webhooks.",
      usage: { connectedAccounts: accounts.length, needAttention: bad.length },
      checkable: false,
      ownerAction: tiktok
        ? "Apply for TikTok Business Messaging API access for an eligible region."
        : "Create a Meta app (Business type), add the product, set the webhook to <PUBLIC_API_URL>/v1/inbox/webhooks/meta with META_WEBHOOK_VERIFY_TOKEN, and put the app secret in META_APP_SECRET. Going live needs Meta App Review and business verification.",
      docs: "docs/UNIFIED_INBOX.md",
      signal: tiktok ? NO_SIGNAL : merge(own, accounts.length ? webhook : NO_SIGNAL),
      flags: { awaiting: tiktok },
    };
  });
}

/** Platform services: email, storage, push, AI, calls, database, developer webhooks. */
function platformRows(ctx: AppContext, metrics: readonly ProviderMetric[], storage: Signal): Row[] {
  const cfg = ctx.config;
  const mail = cfg.accounts.mail;
  const mailOn = mail.transport === "smtp" || mail.transport === "ses";
  const rows: Row[] = [
    {
      id: "email",
      name: mail.transport === "smtp" ? "Email (SMTP)" : mail.transport === "ses" ? "Email (Amazon SES)" : "Email",
      category: "email",
      missing: mailOn || mail.transport === "file" ? [] : ["MAIL_TRANSPORT"],
      detail: mailOn ? "Verification codes, invitations, recovery and notices." : mail.transport === "file" ? "Development: emails are written to files, not sent." : "No email is sent: sign-up by email and recovery are unavailable.",
      usage: {},
      checkable: mail.transport === "smtp",
      ownerAction: null,
      docs: "docs/EMAIL.md",
      signal: metricSignal(metrics, `mail.${mail.transport}`),
    },
    {
      id: "storage",
      name: cfg.storage.backend === "s3" ? "File storage (Amazon S3)" : "File storage (this server's disk)",
      category: "storage",
      missing: [],
      detail: "Encrypted attachments, photos and documents.",
      usage: {},
      checkable: true,
      ownerAction: null,
      docs: "docs/AWS_STORAGE_PLAN.md",
      signal: storage,
    },
    {
      id: "push-unifiedpush",
      name: "UnifiedPush wake-ups",
      category: "push",
      missing: cfg.push ? [] : ["PUSH_UNIFIEDPUSH_HOSTS"],
      detail: "Wakes phones without Google services (for example through ntfy). Wake-ups carry no content.",
      usage: { devices: sqlCount(ctx, "SELECT COUNT(*) AS n FROM push_endpoints") },
      checkable: false,
      ownerAction: null,
      docs: "docs/PUSH_SERVER.md",
      signal: metricSignal(metrics, "push.unifiedpush"),
    },
    {
      id: "push-fcm",
      name: "Firebase Cloud Messaging",
      category: "push",
      missing: cfg.fcm ? [] : ["FCM_PROJECT_ID", "FCM_SERVICE_ACCOUNT_FILE"],
      detail: "Wakes Android phones through Google. Wake-ups carry no content.",
      usage: { devices: sqlCount(ctx, "SELECT COUNT(*) AS n FROM fcm_tokens") },
      checkable: !!cfg.fcm,
      ownerAction: cfg.fcm ? null : "Create a Firebase project for com.meldiq.android, add the app's google-services.json to the build, and give the server a service account with the Firebase Cloud Messaging API role.",
      docs: "docs/PUSH_FCM.md",
      signal: metricSignal(metrics, "push.fcm"),
    },
    ...AI_PROVIDER_IDS.map((id): Row => {
      const configured = cfg.ai.providers.find((p) => p.id === id);
      const env = AI_ENV[id];
      const label = { anthropic: "Claude (Anthropic)", openai: "ChatGPT (OpenAI)", gemini: "Gemini (Google)" }[id];
      return {
        id: `ai-${id}`,
        name: label,
        category: "ai",
        missing: configured ? [] : [`AI_PROVIDERS (include ${id})`, env.key, ...(env.defaultModel ? [] : [env.model])],
        detail: configured ? `Model ${configured.model}. Off in every workspace until its owner turns AI on.` : "Not configured: AI features stay hidden.",
        usage: { workspacesOn: sqlCount(ctx, "SELECT COUNT(*) AS n FROM orgs WHERE ai_enabled = 1 AND (ai_provider IS NULL OR ai_provider = ?)", id) },
        checkable: false,
        ownerAction: configured ? null : `Approve a budget, create an API key for ${label} and put it in ${env.key}_FILE. Each request is billed by the provider.`,
        docs: "docs/AI.md",
        signal: metricSignal(metrics, `ai.${id}`),
      };
    }),
    {
      id: "calls",
      name: "Calls (LiveKit)",
      category: "calls",
      missing: cfg.livekit ? [] : ["LIVEKIT_API_KEY", "LIVEKIT_API_SECRET", "LIVEKIT_PUBLIC_URL"],
      detail: "Voice and video calls, screen sharing and voice rooms.",
      usage: {},
      checkable: false,
      ownerAction: null,
      docs: "docs/CALLING_AND_E2EE.md",
      signal: NO_SIGNAL,
    },
    {
      id: "mongodb",
      name: "MongoDB",
      category: "database",
      missing: cfg.mongo ? [] : ["MONGODB_URI"],
      detail: cfg.mongo ? "Accounts, preferences and apps for the domains it owns." : "Not used: everything is in the server's own database.",
      usage: {},
      checkable: !!cfg.mongo,
      ownerAction: null,
      docs: "docs/MONGODB.md",
      signal: metricSignal(metrics, "mongodb"),
    },
    {
      id: "outgoing-webhooks",
      name: "Outgoing webhooks and API keys",
      category: "automation",
      missing: [],
      detail: "Workspaces send signed events to their own systems and call Meldiq's external API.",
      usage: {
        webhooks: sqlCount(ctx, "SELECT COUNT(*) AS n FROM webhooks WHERE enabled = 1"),
        disabledWebhooks: sqlCount(ctx, "SELECT COUNT(*) AS n FROM webhooks WHERE enabled = 0"),
        apiKeys: sqlCount(ctx, "SELECT COUNT(*) AS n FROM api_keys WHERE revoked_at IS NULL"),
      },
      checkable: false,
      ownerAction: null,
      docs: "docs/DEVELOPER_API.md",
      signal: metricSignal(metrics, "webhooks", "api.external"),
    },
  ];
  return rows;
}

/** The whole register, platform services first. */
export async function connectorReport(ctx: AppContext): Promise<ConnectorStatus[]> {
  const t = now();
  const metrics = ctx.metrics.snapshot().providers;
  const storage = metricSignal(metrics, "storage");
  const rows = [...platformRows(ctx, metrics, storage), ...inboxRows(ctx, metrics), ...(await catalogRows(ctx))];
  return rows.map((r) => finish(r, t));
}

/** Connector ids [checkConnector] can test. */
export const CHECKS = ["email", "storage", "push-fcm", "mongodb"] as const;

/**
 * Tests one connector without sending anything to anyone and without spending money: signs in to SMTP, lists storage,
 * gets an FCM access token, pings MongoDB. The outcome is recorded like any other call, so the register shows it.
 */
export async function checkConnector(ctx: AppContext, id: string): Promise<{ ok: boolean; errorKind: string | null }> {
  const record = (ok: boolean, e?: unknown) => {
    if (id === "storage" || id === "mongodb") ctx.metrics.recordProvider(id, ok ? undefined : e ?? new Error("unreachable"));
  };
  try {
    switch (id) {
      case "email": {
        const verify = ctx.accounts.mailer.verify;
        if (!verify) return { ok: false, errorKind: "not_checkable" };
        await verify.call(ctx.accounts.mailer);
        return { ok: true, errorKind: null };
      }
      case "storage": {
        const s = await storageCheck(ctx);
        const ok = s.available && s.reachable;
        record(ok, new Error(s.available ? s.errorKind ?? "unreachable" : "unavailable"));
        return { ok, errorKind: ok ? null : s.available ? s.errorKind ?? "unreachable" : "unavailable" };
      }
      case "push-fcm": {
        if (!ctx.fcm) return { ok: false, errorKind: "not_configured" };
        await ctx.fcm.checkCredentials();
        return { ok: true, errorKind: null };
      }
      case "mongodb": {
        if (!ctx.mongo) return { ok: false, errorKind: "not_configured" };
        const ok = await ctx.mongo.ping();
        record(ok);
        return { ok, errorKind: ok ? null : "unreachable" };
      }
      default:
        return { ok: false, errorKind: "not_checkable" };
    }
  } catch (e) {
    const code = (e as { code?: unknown }).code;
    return { ok: false, errorKind: typeof code === "string" && /^[A-Za-z0-9_.-]{1,40}$/.test(code) ? code : "error" };
  }
}
