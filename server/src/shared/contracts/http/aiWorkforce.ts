import { z } from "zod/v4";

/**
 * AI Workforce for communities (docs/AI_WORKFORCE.md): request shapes of /v1/orgs/:orgId/ai/* and
 * /v1/conversations/:id/ai/*. The routes validate with these; skill inputs are validated again by each skill's own
 * schema. Content sent here is not end-to-end encrypted, and the apps say so before sending it.
 */
const id = z.string().regex(/^[A-Za-z0-9_-]{1,64}$/);

export const AiOrgParams = z.strictObject({ orgId: id });
export const AiInstallParams = z.strictObject({ orgId: id, installId: id });
export const AiChannelParams = z.strictObject({ orgId: id, installId: id, conversationId: id });
export const AiJobParams = z.strictObject({ orgId: id, jobId: id });
export const AiApprovalParams = z.strictObject({ orgId: id, approvalId: id });
export const AiFileParams = z.strictObject({ orgId: id, fileId: id });
export const AiConversationParams = z.strictObject({ id });

export const AiInstallBody = z.strictObject({
  agentId: z.string().regex(/^[a-z][a-z0-9_]{1,40}$/),
  displayName: z.string().max(60).optional(),
  description: z.string().max(500).optional(),
  roleTitle: z.string().max(60).optional(),
});

export const AiInvokers = z.strictObject({ everyone: z.boolean(), roles: z.array(id).max(50) });

export const AiInstallPatch = z.strictObject({
  rev: z.number().int().nonnegative(),
  state: z.enum(["active", "disabled"]).optional(),
  displayName: z.string().max(60).optional(),
  description: z.string().max(500).optional(),
  roleTitle: z.string().max(60).optional(),
  avatarColor: z.string().regex(/^#[0-9A-Fa-f]{6}$/).nullable().optional(),
  language: z.string().max(10).nullable().optional(),
  style: z.enum(["balanced", "concise", "detailed", "friendly", "formal"]).optional(),
  instructions: z.string().max(2000).optional(),
  tools: z.array(z.string().max(40)).max(30).optional(),
  executionMode: z.enum(["review", "auto"]).optional(),
  maxJobsPerDay: z.number().int().min(0).max(10_000).nullable().optional(),
  maxConcurrent: z.number().int().min(1).max(16).nullable().optional(),
  invokers: AiInvokers.optional(),
  upgradeTo: z.number().int().positive().optional(),
});

export const AiChannelBody = z.strictObject({ activation: z.enum(["mention", "command", "explicit"]).default("mention") });

export const AiUploadQuery = z.strictObject({ name: z.string().min(1).max(200), type: z.string().min(3).max(100) });

/** Limits on channel messages shared with a request (the web and Android apps pick within the same limits). */
export const AI_SHARED_MAX = 150;
export const AI_SHARED_CHARS = 30_000;
export const AI_SHARED_MESSAGE_CHARS = 2_000;

/** One channel message the person's app decrypted and chose to share: who wrote it (display name), its text, when. */
export const AiSharedMessage = z.strictObject({
  author: z.string().trim().min(1).max(80),
  text: z.string().trim().min(1).max(AI_SHARED_MESSAGE_CHARS),
  at: z.number().int().positive(),
});
export const AiSharedMessages = z
  .array(AiSharedMessage)
  .min(1)
  .max(AI_SHARED_MAX)
  .refine((ms) => ms.reduce((n, m) => n + m.author.length + m.text.length + 2, 0) <= AI_SHARED_CHARS, `At most ${AI_SHARED_CHARS} characters of messages.`);

export const AiJobBody = z.strictObject({
  installId: id,
  input: z.record(z.string(), z.unknown()),
  conversationId: id.nullable().default(null),
  threadRoot: z.string().max(100).nullable().default(null),
  fileIds: z.array(id).max(20).default([]),
  idempotencyKey: z.string().min(8).max(100).nullable().default(null),
  /** What the channel shows as the request (the brief as the person wrote it). */
  requestText: z.string().max(8000).nullable().default(null),
  /**
   * Recent messages of [conversationId], decrypted and picked in the person's app after a notice that they leave
   * end-to-end encryption. Only with a channel request, and only for agents that read text.
   */
  channelMessages: AiSharedMessages.nullable().default(null),
});

export const AiJobsQuery = z.strictObject({
  scope: z.enum(["mine", "all"]).default("mine"),
  state: z.enum(["queued", "planning", "running", "awaiting_input", "awaiting_approval", "completed", "failed", "cancelled"]).optional(),
  installId: id.optional(),
  before: z.coerce.number().int().positive().optional(),
  limit: z.coerce.number().int().min(1).max(100).default(30),
});

export const AiReviseBody = z.strictObject({ note: z.string().trim().min(1).max(2000) });
export const AiAnswerBody = z.strictObject({ text: z.string().trim().min(1).max(4000) });
export const AiDecisionBody = z.strictObject({ decision: z.enum(["approved", "rejected"]), note: z.string().max(1000).nullable().default(null) });
export const AiPostsQuery = z.strictObject({ since: z.coerce.number().int().nonnegative().optional(), before: z.coerce.number().int().positive().optional(), limit: z.coerce.number().int().min(1).max(200).default(100) });
export const AiDeliverablesQuery = z.strictObject({ installId: id.optional(), before: z.coerce.number().int().positive().optional(), limit: z.coerce.number().int().min(1).max(100).default(50) });
