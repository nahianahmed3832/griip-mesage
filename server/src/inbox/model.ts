import { z } from "zod";

/**
 * The Unified Inbox's shared vocabulary (docs/UNIFIED_INBOX.md). Each provider declares what it really supports; the
 * routes and the apps only offer what's declared, so nothing unsupported looks like it works.
 */
export const INBOX_PROVIDERS = ["messenger", "instagram", "whatsapp", "tiktok"] as const;
export type InboxProvider = (typeof INBOX_PROVIDERS)[number];

export const STATUSES = ["waiting_agent", "waiting_customer", "resolved"] as const;
export type ConversationStatus = (typeof STATUSES)[number];
export const PRIORITIES = ["low", "normal", "high", "urgent"] as const;
export const HANDLING = ["human", "ai_draft", "ai_auto", "awaiting_review", "escalated"] as const;
export type Handling = (typeof HANDLING)[number];

const HOUR = 3_600_000;

export interface Capabilities {
  name: string;
  /** "live": built on the provider's official API; "awaiting_access": the provider must grant API access first. */
  readiness: "live" | "awaiting_access";
  connect: "token";
  inbound: readonly string[];
  outbound: readonly string[];
  /** Free-form replies are allowed this long after the customer's last message. */
  freeReplyWindowMs: number;
  /** Messenger and Instagram: a human agent may reply up to 7 days with the HUMAN_AGENT tag (needs Meta's approval). */
  humanAgentWindowMs: number | null;
  /** WhatsApp: outside the window only approved templates may be sent. */
  templates: boolean;
  /** Only the customer can start a conversation. */
  customerInitiatedOnly: boolean;
  deliveryStates: readonly string[];
  attachmentsIn: boolean;
  textLimit: number;
  eligibility: string;
}

export const CAPABILITIES: Record<InboxProvider, Capabilities> = {
  messenger: {
    name: "Facebook Messenger",
    readiness: "live",
    connect: "token",
    inbound: ["text", "image", "video", "audio", "file", "sticker"],
    outbound: ["text"],
    freeReplyWindowMs: 24 * HOUR,
    humanAgentWindowMs: 7 * 24 * HOUR,
    templates: false,
    customerInitiatedOnly: true,
    deliveryStates: ["sent", "delivered", "read"],
    attachmentsIn: true,
    textLimit: 2000,
    eligibility: "A Facebook Page you manage, connected to a Meta app with pages_messaging (Advanced Access needs Meta's app review).",
  },
  instagram: {
    name: "Instagram",
    readiness: "live",
    connect: "token",
    inbound: ["text", "image", "video", "audio", "file", "share"],
    outbound: ["text"],
    freeReplyWindowMs: 24 * HOUR,
    humanAgentWindowMs: 7 * 24 * HOUR,
    templates: false,
    customerInitiatedOnly: true,
    deliveryStates: ["sent", "read"],
    attachmentsIn: true,
    // Meta: message text is at most 1000 bytes (UTF-8); checked in bytes when sending.
    textLimit: 1000,
    eligibility: "An Instagram professional (Business or Creator) account, through Instagram API with Instagram Login (instagram_business_manage_messages). Personal accounts can't be connected.",
  },
  whatsapp: {
    name: "WhatsApp Business",
    readiness: "live",
    connect: "token",
    inbound: ["text", "image", "video", "audio", "document", "sticker", "location"],
    outbound: ["text", "template"],
    freeReplyWindowMs: 24 * HOUR,
    humanAgentWindowMs: null,
    templates: true,
    customerInitiatedOnly: false,
    deliveryStates: ["sent", "delivered", "read", "failed"],
    attachmentsIn: true,
    textLimit: 4096,
    eligibility: "A WhatsApp Business Platform (Cloud API) phone number and its business account, with a system user token (whatsapp_business_messaging). A personal WhatsApp account can't be connected.",
  },
  tiktok: {
    name: "TikTok",
    readiness: "awaiting_access",
    connect: "token",
    inbound: [],
    outbound: [],
    freeReplyWindowMs: 48 * HOUR,
    humanAgentWindowMs: null,
    templates: false,
    customerInitiatedOnly: true,
    deliveryStates: [],
    attachmentsIn: false,
    textLimit: 0,
    eligibility: "TikTok's Business Messaging API needs TikTok to approve the app first, and isn't offered for business accounts registered in the US, EEA, Switzerland or the UK. Not connectable until access is granted and verified.",
  },
};

export type ReplyRule =
  | { allowed: true; mode: "free" }
  | { allowed: true; mode: "human_agent_tag" }
  | { allowed: false; reason: "window_closed" | "template_required" | "no_customer_message" | "unsupported" };

/**
 * May a free-form reply be sent now? Bypassing a provider's window is never possible: past it, Messenger and Instagram
 * allow only a person (never AI) with the HUMAN_AGENT tag when the operator has Meta's approval, and WhatsApp only an
 * approved template.
 */
export function replyRule(p: InboxProvider, lastInboundAt: number | null, at: number, opts: { humanAgentTag: boolean; byAi: boolean }): ReplyRule {
  const c = CAPABILITIES[p];
  if (c.readiness !== "live" || !c.outbound.includes("text")) return { allowed: false, reason: "unsupported" };
  if (lastInboundAt === null) return { allowed: false, reason: c.templates ? "template_required" : "no_customer_message" };
  const age = at - lastInboundAt;
  if (age <= c.freeReplyWindowMs) return { allowed: true, mode: "free" };
  if (c.humanAgentWindowMs && opts.humanAgentTag && !opts.byAi && age <= c.humanAgentWindowMs) return { allowed: true, mode: "human_agent_tag" };
  return { allowed: false, reason: c.templates ? "template_required" : "window_closed" };
}

/** UTF-8 aware length check (Instagram counts bytes). */
export function textFits(p: InboxProvider, text: string): boolean {
  const limit = CAPABILITIES[p].textLimit;
  return p === "instagram" ? Buffer.byteLength(text, "utf8") <= limit : [...text].length <= limit;
}

export const AI_MODES = ["off", "draft", "auto"] as const;

/** Intents automatic replies may ever cover; anything else goes to a person. */
export const AUTO_INTENTS = ["greeting", "opening_hours", "product_question", "order_status_question", "shipping_question", "general_question"] as const;
/** Never answered automatically, whatever the settings say. */
export const SENSITIVE_INTENTS = ["complaint", "refund", "payment", "legal", "account_change", "personal_data", "angry", "other"] as const;

export const zInboxSettings = z.object({
  aiMode: z.enum(AI_MODES).default("off"),
  /** Documents the AI may use as knowledge for customers (must be workspace documents the admin can read). */
  knowledgeDocumentIds: z.array(z.string().max(64)).max(20).default([]),
  autoIntents: z.array(z.enum(AUTO_INTENTS)).max(AUTO_INTENTS.length).default([]),
  /** 0–100: below this the automatic reply escalates to a person. */
  autoMinConfidence: z.number().int().min(50).max(100).default(80),
  autoMaxPerConversationPerHour: z.number().int().min(1).max(10).default(3),
  autoFallback: z.string().trim().max(500).default("Thanks for your message. A member of our team will reply soon."),
  autoPaused: z.boolean().default(false),
  /** Minutes a customer may wait before the conversation is overdue; 0: no target. */
  responseTargetMinutes: z.number().int().min(0).max(10_080).default(60),
  /** Days messages are kept; 0: kept until deleted. */
  retentionDays: z.number().int().min(0).max(3650).default(0),
  /** Default queue for new conversations, if any. */
  defaultQueueId: z.string().max(64).nullable().default(null),
});
export type InboxSettings = z.infer<typeof zInboxSettings>;
export const DEFAULT_SETTINGS: InboxSettings = zInboxSettings.parse({});

/** Inbound content, normalised from any provider. */
export interface InboundMessage {
  provider: InboxProvider;
  /** The connected account's provider id (Page id, Instagram account id, phone number id). */
  accountExternalId: string;
  customerId: string;
  customerName: string | null;
  externalId: string;
  at: number;
  kind: "text" | "attachment" | "unsupported";
  text: string | null;
  attachments: { type: string; ref: string; mime: string | null }[];
}

/** A delivery state the provider reported for one of our replies. */
export interface StatusUpdate {
  provider: InboxProvider;
  accountExternalId: string;
  externalId: string;
  state: "sent" | "delivered" | "read" | "failed";
  at: number;
  /** Messenger and Instagram report reads up to a time (every reply before it is read). */
  upTo?: boolean;
  customerId?: string;
}
