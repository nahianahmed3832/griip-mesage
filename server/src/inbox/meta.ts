import crypto from "node:crypto";
import type { FetchLike } from "../integrations/http.js";
import type { InboundMessage, InboxProvider, StatusUpdate } from "./model.js";

/**
 * Meta's official APIs for the inbox (docs/UNIFIED_INBOX.md): the Messenger Platform (Pages), the Instagram API with
 * Instagram Login (professional accounts) and the WhatsApp Business Platform (Cloud API). Only the endpoints Meta
 * documents; no scraping, no private endpoints, no personal accounts.
 */
export type MetaProvider = Exclude<InboxProvider, "tiktok">;

export type MetaFailure = "unauthorized" | "permission" | "rate_limited" | "unavailable" | "window_closed" | "rejected" | "bad_response";

/** A provider failure by kind (the provider's own message can echo customer text, so it's never kept). */
export class MetaError extends Error {
  constructor(readonly kind: MetaFailure, readonly retryable: boolean, readonly code: number | null = null) {
    super(kind);
  }
}

export interface MetaConfig {
  graphVersion: string;
  fetch: FetchLike;
  /** Development only (META_TEST_BASE_URL): a local stand-in for Meta's hosts. */
  testBaseUrl?: string | null;
}

let testBase: string | null = null;
/** Set once at start-up from the config (development only). */
export function useTestBase(url: string | null): void {
  testBase = url;
}
const FB = (v: string) => (testBase ? `${testBase}/fb/${v}` : `https://graph.facebook.com/${v}`);
const IG = (v: string) => (testBase ? `${testBase}/ig/${v}` : `https://graph.instagram.com/${v}`);

/** `X-Hub-Signature-256: sha256=<hex HMAC-SHA256 of the raw body with the app secret>`, compared in constant time. */
export function verifySignature(raw: Buffer, header: string | undefined, secret: string | null): boolean {
  if (!secret || !header?.startsWith("sha256=")) return false;
  const want = crypto.createHmac("sha256", secret).update(raw).digest();
  const got = Buffer.from(header.slice(7), "hex");
  return got.length === want.length && crypto.timingSafeEqual(got, want);
}

function failureFor(status: number, code: number | null): MetaError {
  if (status === 429 || code === 4 || code === 17 || code === 32 || code === 613 || code === 80007 || code === 130429) return new MetaError("rate_limited", true, code);
  if (status >= 500) return new MetaError("unavailable", true, code);
  if (code === 190 || status === 401) return new MetaError("unauthorized", false, code);
  if (code === 10 || code === 200 || code === 3 || status === 403) return new MetaError("permission", false, code);
  // WhatsApp 131047: more than 24 hours since the customer's last message. Messenger 10 with subcode 2018278 lands above.
  if (code === 131047 || code === 2018278) return new MetaError("window_closed", false, code);
  return new MetaError("rejected", false, code);
}

async function call<T>(cfg: MetaConfig, url: string, token: string, init: { method?: string; body?: unknown } = {}): Promise<T> {
  let res: Response;
  try {
    res = await cfg.fetch(url, {
      method: init.method ?? "GET",
      headers: { authorization: `Bearer ${token}`, ...(init.body ? { "content-type": "application/json" } : {}) },
      body: init.body ? JSON.stringify(init.body) : undefined,
      signal: AbortSignal.timeout(15_000),
    });
  } catch {
    throw new MetaError("unavailable", true);
  }
  const body = (await res.json().catch(() => null)) as { error?: { code?: number } } | null;
  if (!res.ok || body?.error) throw failureFor(res.status, typeof body?.error?.code === "number" ? body.error.code : null);
  if (body === null) throw new MetaError("bad_response", false);
  return body as T;
}

export interface AccountCheck {
  externalId: string;
  name: string;
  meta: Record<string, string>;
}

/** Confirms the token works for that account and returns what Meta calls it. Never trusts the typed-in name. */
export async function checkAccount(cfg: MetaConfig, p: MetaProvider, input: { externalId: string; token: string; businessAccountId?: string }): Promise<AccountCheck> {
  const v = cfg.graphVersion;
  if (p === "messenger") {
    const r = await call<{ id?: string; name?: string }>(cfg, `${FB(v)}/${encodeURIComponent(input.externalId)}?fields=id,name`, input.token);
    if (!r.id) throw new MetaError("bad_response", false);
    return { externalId: r.id, name: r.name ?? r.id, meta: {} };
  }
  if (p === "instagram") {
    const r = await call<{ user_id?: string; id?: string; username?: string }>(cfg, `${IG(v)}/me?fields=user_id,username`, input.token);
    const id = r.user_id ?? r.id;
    if (!id) throw new MetaError("bad_response", false);
    return { externalId: id, name: r.username ? `@${r.username}` : id, meta: {} };
  }
  const r = await call<{ id?: string; display_phone_number?: string; verified_name?: string }>(cfg, `${FB(v)}/${encodeURIComponent(input.externalId)}?fields=id,display_phone_number,verified_name`, input.token);
  if (!r.id) throw new MetaError("bad_response", false);
  if (!input.businessAccountId) throw new MetaError("rejected", false);
  return { externalId: r.id, name: r.verified_name ?? r.display_phone_number ?? r.id, meta: { wabaId: input.businessAccountId, number: r.display_phone_number ?? "" } };
}

/** Subscribes Meta's app to the account's message webhooks (best effort; the account works without it if the app dashboard already subscribes). */
export async function subscribe(cfg: MetaConfig, p: MetaProvider, a: { externalId: string; token: string; meta: Record<string, string> }): Promise<void> {
  const v = cfg.graphVersion;
  if (p === "messenger") {
    await call(cfg, `${FB(v)}/${encodeURIComponent(a.externalId)}/subscribed_apps?subscribed_fields=messages,message_deliveries,message_reads`, a.token, { method: "POST" });
  } else if (p === "instagram") {
    await call(cfg, `${IG(v)}/me/subscribed_apps?subscribed_fields=messages,messaging_seen`, a.token, { method: "POST" });
  } else {
    await call(cfg, `${FB(v)}/${encodeURIComponent(a.meta.wabaId ?? "")}/subscribed_apps`, a.token, { method: "POST" });
  }
}

export type SendBody = { kind: "text"; text: string; humanAgentTag: boolean } | { kind: "template"; name: string; language: string };

/** Sends one reply; returns the provider's message id. */
export async function send(cfg: MetaConfig, p: MetaProvider, a: { externalId: string; token: string }, to: string, body: SendBody): Promise<string> {
  const v = cfg.graphVersion;
  if (p === "whatsapp") {
    const payload =
      body.kind === "template"
        ? { messaging_product: "whatsapp", recipient_type: "individual", to, type: "template", template: { name: body.name, language: { code: body.language } } }
        : { messaging_product: "whatsapp", recipient_type: "individual", to, type: "text", text: { body: body.text, preview_url: false } };
    const r = await call<{ messages?: { id?: string }[] }>(cfg, `${FB(v)}/${encodeURIComponent(a.externalId)}/messages`, a.token, { method: "POST", body: payload });
    const id = r.messages?.[0]?.id;
    if (!id) throw new MetaError("bad_response", false);
    return id;
  }
  if (body.kind !== "text") throw new MetaError("rejected", false);
  const tag = body.humanAgentTag ? { messaging_type: "MESSAGE_TAG", tag: "HUMAN_AGENT" } : { messaging_type: "RESPONSE" };
  const base = p === "messenger" ? FB(v) : IG(v);
  const r = await call<{ message_id?: string }>(cfg, `${base}/${encodeURIComponent(a.externalId)}/messages`, a.token, {
    method: "POST",
    body: { recipient: { id: to }, ...tag, message: { text: body.text } },
  });
  if (!r.message_id) throw new MetaError("bad_response", false);
  return r.message_id;
}

export interface TemplateInfo {
  name: string;
  language: string;
  category: string | null;
}

/** WhatsApp: the business account's approved templates (the only messages allowed outside the 24-hour window). */
export async function templates(cfg: MetaConfig, a: { token: string; meta: Record<string, string> }): Promise<TemplateInfo[]> {
  const r = await call<{ data?: { name?: string; language?: string; status?: string; category?: string }[] }>(
    cfg,
    `${FB(cfg.graphVersion)}/${encodeURIComponent(a.meta.wabaId ?? "")}/message_templates?status=APPROVED&fields=name,language,status,category&limit=100`,
    a.token,
  );
  return (r.data ?? []).filter((t) => t.name && t.language && (t.status ?? "APPROVED") === "APPROVED").map((t) => ({ name: t.name as string, language: t.language as string, category: t.category ?? null }));
}

/** Instagram user tokens last 60 days and can be refreshed while valid. */
export async function refreshInstagramToken(cfg: MetaConfig, token: string): Promise<{ token: string; expiresAt: number }> {
  const r = await call<{ access_token?: string; expires_in?: number }>(cfg, `${IG(cfg.graphVersion)}/refresh_access_token?grant_type=ig_refresh_token`, token);
  if (!r.access_token) throw new MetaError("bad_response", false);
  return { token: r.access_token, expiresAt: Date.now() + (r.expires_in ?? 60 * 86_400) * 1000 };
}

/** Hosts Meta serves message media from; a stored reference outside them is never fetched. */
const MEDIA_HOSTS = [".fbcdn.net", ".fbsbx.com", ".cdninstagram.com", ".facebook.com", ".whatsapp.net"];
export function mediaHostAllowed(url: string): boolean {
  try {
    const u = new URL(url);
    if (testBase && url.startsWith(`${testBase}/`)) return true;
    return u.protocol === "https:" && MEDIA_HOSTS.some((h) => u.hostname.endsWith(h) || `.${u.hostname}` === h);
  } catch {
    return false;
  }
}

const MAX_MEDIA_BYTES = 100 * 1024 * 1024;

/** Downloads one inbound attachment: WhatsApp by media id (with the token), Messenger and Instagram by Meta's own URL. */
export async function media(cfg: MetaConfig, p: MetaProvider, token: string, ref: string): Promise<{ bytes: Buffer; mime: string }> {
  let url = ref;
  let auth: Record<string, string> = {};
  if (p === "whatsapp") {
    const info = await call<{ url?: string; mime_type?: string; file_size?: number }>(cfg, `${FB(cfg.graphVersion)}/${encodeURIComponent(ref)}`, token);
    if (!info.url || (info.file_size ?? 0) > MAX_MEDIA_BYTES) throw new MetaError("rejected", false);
    url = info.url;
    auth = { authorization: `Bearer ${token}` };
  }
  if (!mediaHostAllowed(url)) throw new MetaError("rejected", false);
  let res: Response;
  try {
    res = await cfg.fetch(url, { headers: auth, redirect: "error", signal: AbortSignal.timeout(30_000) });
  } catch {
    throw new MetaError("unavailable", true);
  }
  if (!res.ok) throw failureFor(res.status, null);
  const bytes = Buffer.from(await res.arrayBuffer());
  if (bytes.length > MAX_MEDIA_BYTES) throw new MetaError("rejected", false);
  return { bytes, mime: res.headers.get("content-type") ?? "application/octet-stream" };
}

// ───────── Webhook payloads ─────────

type Obj = Record<string, unknown>;
const obj = (v: unknown): Obj | null => (v && typeof v === "object" && !Array.isArray(v) ? (v as Obj) : null);
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const str = (v: unknown): string | null => (typeof v === "string" && v.length > 0 && v.length <= 4096 ? v : typeof v === "number" ? String(v) : null);
const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : typeof v === "string" && /^\d+$/.test(v) ? Number(v) : null);

export interface ParsedWebhook {
  inbound: InboundMessage[];
  statuses: StatusUpdate[];
}

/** Which provider a payload is for, from its `object` field; null for anything else. */
export function providerOf(payload: unknown): MetaProvider | null {
  const o = obj(payload)?.object;
  return o === "page" ? "messenger" : o === "instagram" ? "instagram" : o === "whatsapp_business_account" ? "whatsapp" : null;
}

/** Messenger and Instagram share the `entry[].messaging[]` shape. Echoes of the business's own messages are skipped. */
function parseMessaging(p: "messenger" | "instagram", payload: Obj): ParsedWebhook {
  const out: ParsedWebhook = { inbound: [], statuses: [] };
  for (const e of arr(payload.entry)) {
    const entry = obj(e);
    const account = str(entry?.id);
    if (!entry || !account) continue;
    for (const m of arr(entry.messaging)) {
      const ev = obj(m);
      const customer = str(obj(ev?.sender)?.id);
      const at = num(ev?.timestamp) ?? Date.now();
      if (!ev || !customer) continue;
      const msg = obj(ev.message);
      if (msg) {
        const mid = str(msg.mid);
        if (!mid || msg.is_echo === true || msg.is_deleted === true) continue;
        const attachments = arr(msg.attachments).flatMap((x) => {
          const a = obj(x);
          const url = str(obj(a?.payload)?.url);
          return a && url ? [{ type: str(a.type) ?? "file", ref: url, mime: null }] : [];
        });
        const text = typeof msg.text === "string" ? msg.text.slice(0, 20_000) : null;
        const kind = msg.is_unsupported === true ? "unsupported" : text ? "text" : attachments.length ? "attachment" : "unsupported";
        out.inbound.push({ provider: p, accountExternalId: account, customerId: customer, customerName: null, externalId: mid, at, kind, text, attachments });
        continue;
      }
      const delivery = obj(ev.delivery);
      if (delivery) {
        for (const mid of arr(delivery.mids)) {
          const id = str(mid);
          if (id) out.statuses.push({ provider: p, accountExternalId: account, externalId: id, state: "delivered", at });
        }
      }
      const read = obj(ev.read);
      if (read) {
        const mid = str(read.mid);
        const watermark = num(read.watermark);
        if (mid) out.statuses.push({ provider: p, accountExternalId: account, externalId: mid, state: "read", at });
        else if (watermark) out.statuses.push({ provider: p, accountExternalId: account, externalId: "", state: "read", at: watermark, upTo: true, customerId: customer });
      }
    }
  }
  return out;
}

const WA_MEDIA = ["image", "video", "audio", "document", "sticker"] as const;

function parseWhatsApp(payload: Obj): ParsedWebhook {
  const out: ParsedWebhook = { inbound: [], statuses: [] };
  for (const e of arr(payload.entry)) {
    for (const c of arr(obj(e)?.changes)) {
      const value = obj(obj(c)?.value);
      const account = str(obj(value?.metadata)?.phone_number_id);
      if (!value || !account) continue;
      const names = new Map(arr(value.contacts).flatMap((x) => {
        const ct = obj(x);
        const id = str(ct?.wa_id);
        const name = str(obj(ct?.profile)?.name);
        return id && name ? [[id, name.slice(0, 120)] as const] : [];
      }));
      for (const x of arr(value.messages)) {
        const m = obj(x);
        const from = str(m?.from);
        const id = str(m?.id);
        if (!m || !from || !id) continue;
        const type = str(m.type);
        const at = (num(m.timestamp) ?? Math.floor(Date.now() / 1000)) * 1000;
        const text = type === "text" ? str(obj(m.text)?.body) : null;
        const mediaType = WA_MEDIA.find((t) => t === type);
        const mediaObj = mediaType ? obj(m[mediaType]) : null;
        const mediaId = str(mediaObj?.id);
        const caption = str(mediaObj?.caption);
        const attachments = mediaType && mediaId ? [{ type: mediaType, ref: mediaId, mime: str(mediaObj?.mime_type) }] : [];
        const kind = text ? "text" : attachments.length ? "attachment" : "unsupported";
        out.inbound.push({ provider: "whatsapp", accountExternalId: account, customerId: from, customerName: names.get(from) ?? null, externalId: id, at, kind, text: text ?? caption, attachments });
      }
      for (const x of arr(value.statuses)) {
        const s = obj(x);
        const id = str(s?.id);
        const state = str(s?.status);
        if (!id || (state !== "sent" && state !== "delivered" && state !== "read" && state !== "failed")) continue;
        out.statuses.push({ provider: "whatsapp", accountExternalId: account, externalId: id, state, at: (num(s?.timestamp) ?? Math.floor(Date.now() / 1000)) * 1000 });
      }
    }
  }
  return out;
}

/** Normalises a verified delivery; anything unexpected yields nothing (never throws). */
export function parseWebhook(payload: unknown): ParsedWebhook {
  const p = providerOf(payload);
  const o = obj(payload);
  if (!p || !o) return { inbound: [], statuses: [] };
  return p === "whatsapp" ? parseWhatsApp(o) : parseMessaging(p, o);
}
