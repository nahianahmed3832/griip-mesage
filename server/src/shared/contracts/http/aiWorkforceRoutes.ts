import { z } from "zod/v4";
import {
  AiAnswerBody,
  AiApprovalParams,
  AiChannelBody,
  AiChannelParams,
  AiConversationParams,
  AiDecisionBody,
  AiDeliverablesQuery,
  AiFileParams,
  AiInstallBody,
  AiInstallParams,
  AiInstallPatch,
  AiInvokers,
  AiJobBody,
  AiJobParams,
  AiJobsQuery,
  AiOrgParams,
  AiPostsQuery,
  AiReviseBody,
  AiUploadQuery,
} from "./aiWorkforce.js";
import { zCount, zTime } from "./primitives.js";
import { route } from "./route.js";
import type { ErrorCode } from "../../errors.js";

/**
 * Answers of AI Workforce's community routes (docs/AI_WORKFORCE.md §11), as modules/workforce/views.ts and routes.ts
 * build them. Content (briefs, outputs, posts) is only in answers for people who may see it; shared channel messages
 * are never in any answer, only their count.
 */
const str = z.string();
const nstr = z.string().nullable();
const ntime = zTime.nullable();
const int = z.number().int();

const Activation = z.enum(["mention", "command", "explicit"]);
const ExecutionMode = z.enum(["review", "auto"]);
const JobState = z.enum(["queued", "planning", "running", "awaiting_input", "awaiting_approval", "completed", "failed", "cancelled"]);

export const AiPermissions = z.strictObject({ canUse: z.boolean(), canManage: z.boolean(), canApprove: z.boolean() });

export const AiInstallView = z.strictObject({
  id: str,
  agentId: str,
  version: int,
  state: z.enum(["active", "disabled", "uninstalled"]),
  displayName: str,
  avatar: z.strictObject({ sha256: str }).nullable(),
  avatarColor: nstr,
  description: str,
  roleTitle: str,
  language: nstr,
  style: str,
  instructions: str,
  tools: z.array(str),
  executionMode: ExecutionMode,
  effectiveMode: ExecutionMode,
  maxJobsPerDay: int.nullable(),
  maxConcurrent: int.nullable(),
  invokers: AiInvokers,
  rev: int,
  skill: z.strictObject({ id: str, version: int, name: str, category: str }).nullable(),
  badge: z.literal("AI"),
  createdAt: zTime,
  updatedAt: zTime,
});

/** A catalog entry as clients show it (service.agentSummary). */
export const AiAgentDefinition = z.strictObject({
  id: str,
  version: int,
  name: str,
  description: str,
  roleTitle: str,
  category: str,
  capabilities: z.array(str),
  examples: z.array(str),
  avatarColor: str,
  skill: z.strictObject({
    id: str,
    version: int,
    tools: z.array(str),
    requiredTools: z.array(str),
    modelCategories: z.array(str),
    requiredModelCategories: z.array(str),
    files: z.strictObject({ min: zCount, max: zCount, maxBytes: zCount, types: z.array(str) }),
    readsMessages: z.boolean(),
    limits: z.record(str, z.number()),
    approvals: z.array(str),
    errors: z.array(str),
    /** The skill's input as JSON Schema (null when it can't be expressed). */
    inputSchema: z.unknown(),
  }),
  integrations: z.array(z.strictObject({ tool: str, label: str, connected: z.boolean() })),
  defaults: z.strictObject({ executionMode: ExecutionMode, maxJobsPerDay: int.nullable() }),
});

const AiSetup = z.strictObject({ ready: z.boolean(), missing: z.array(str), unavailableActions: z.array(str) });
const AiAssignment = z.strictObject({ installId: str, conversationId: str, activation: Activation, addedBy: str, addedAt: zTime });

export const AiFileView = z.strictObject({
  id: str,
  role: z.enum(["input", "artifact", "avatar"]),
  jobId: nstr,
  installId: nstr,
  agentId: nstr,
  skillVersion: int.nullable(),
  kind: nstr,
  name: str,
  mime: str,
  size: zCount,
  sha256: str,
  version: int,
  supersedes: nstr,
  approvalState: z.enum(["not_required", "pending", "approved", "rejected", "superseded"]),
  createdAt: zTime,
  expiresAt: zTime,
});

export const AiApprovalView = z.strictObject({
  id: str,
  jobId: str,
  action: str,
  fileId: nstr,
  fileSha256: nstr,
  summary: str,
  state: z.enum(["pending", "approved", "rejected", "invalidated"]),
  requestedAt: zTime,
  decidedBy: nstr,
  decidedAt: ntime,
  note: nstr,
});

export const AiJobSummary = z.strictObject({
  id: str,
  agent: z.strictObject({ installId: str, agentId: str, version: int, displayName: str, badge: z.literal("AI") }),
  skillId: str,
  state: JobState,
  progress: z.number(),
  note: nstr,
  requestedBy: str,
  conversationId: nstr,
  parentJobId: nstr,
  rootJobId: str,
  depth: zCount,
  revision: zCount,
  attempts: zCount,
  maxAttempts: zCount,
  error: z.strictObject({ code: str, message: str }).nullable(),
  createdAt: zTime,
  startedAt: ntime,
  finishedAt: ntime,
  updatedAt: zTime,
});

export const AiJobDetail = AiJobSummary.extend({
  input: z.unknown(),
  output: z.unknown(),
  files: z.array(AiFileView),
  /** How many channel messages were shared with the request; the messages themselves are never returned. */
  sharedMessages: zCount,
  approvals: z.array(AiApprovalView),
  steps: z.array(AiJobSummary),
  events: z.array(z.strictObject({ at: zTime, kind: str, detail: z.unknown() })),
});

export const AiPostView = z.strictObject({
  id: str,
  conversationId: str,
  threadRoot: nstr,
  kind: z.enum(["request", "ack", "progress", "result", "approval", "error"]),
  body: str,
  jobId: nstr,
  approvalId: nstr,
  authorId: nstr,
  agent: z.strictObject({ installId: str, displayName: str, avatarColor: nstr, hasAvatar: z.boolean(), skill: nstr, badge: z.literal("AI") }),
  files: z.array(AiFileView),
  /** Agent posts are stored on the server: never end-to-end encrypted, and clients say so. */
  encrypted: z.literal(false),
  createdAt: zTime,
});

const Agent = z.strictObject({ agent: AiInstallView });
const Job = z.strictObject({ job: AiJobSummary });
const Channels = z.strictObject({ channels: z.array(AiAssignment) });

const tag = "ai-workforce";
const COMMON: readonly ErrorCode[] = ["not_found", "forbidden", "ai_workforce_disabled"];
const errs = (...codes: ErrorCode[]): readonly ErrorCode[] => [...COMMON, ...codes];

const jobAction = (action: "cancel" | "retry" | "revise" | "answer", summary: string, body?: z.ZodType) =>
  route({ method: "POST", url: `/v1/orgs/:orgId/ai/jobs/:jobId/${action}`, tag, summary, auth: true, params: AiJobParams, body, response: Job, errors: errs("conflict", "job_not_retryable", "quota_exceeded", "ai_paused") });

export const aiWorkforceRoutes = [
  route({
    method: "GET",
    url: "/v1/orgs/:orgId/ai",
    tag,
    summary: "AI agents in a community: whether they're on, the person's permissions, the installed agents and the notice.",
    auth: true,
    params: AiOrgParams,
    response: z.strictObject({
      enabled: z.boolean(),
      paused: z.boolean(),
      aiRequestsOn: z.boolean(),
      approvalFloor: str,
      mediaEngine: z.strictObject({ available: z.boolean(), detail: str }),
      permissions: AiPermissions,
      agents: z.array(AiInstallView),
      notice: str,
    }),
    errors: errs(),
  }),
  route({
    method: "GET",
    url: "/v1/orgs/:orgId/ai/catalog",
    tag,
    summary: "The agents the platform published, with what each needs to work here and whether it's installed.",
    auth: true,
    params: AiOrgParams,
    response: z.strictObject({
      agents: z.array(
        z.strictObject({
          agent: AiAgentDefinition,
          available: z.boolean(),
          unavailableReason: nstr,
          setup: AiSetup,
          installed: z.strictObject({ installId: str, state: z.enum(["active", "disabled", "uninstalled"]), version: int, upgradeAvailable: z.boolean() }).nullable(),
        }),
      ),
    }),
    errors: errs(),
  }),
  route({ method: "POST", url: "/v1/orgs/:orgId/ai/agents", tag, summary: "Install an agent (ai.manage).", auth: true, params: AiOrgParams, body: AiInstallBody, response: Agent, status: 201, errors: errs("agent_unavailable", "name_not_allowed", "conflict") }),
  route({
    method: "GET",
    url: "/v1/orgs/:orgId/ai/agents/:installId",
    tag,
    summary: "One installed agent: its settings, definition, setup, channels and grantable tools.",
    auth: true,
    params: AiInstallParams,
    response: z.strictObject({
      agent: AiInstallView,
      definition: AiAgentDefinition.nullable(),
      upgrade: z.strictObject({ version: int }).nullable(),
      setup: AiSetup.nullable(),
      channels: z.array(AiAssignment),
      tools: z.array(z.strictObject({ key: str, label: str, description: str, risk: str, platformMode: str, granted: z.boolean(), required: z.boolean(), connected: z.boolean() })),
      permissions: AiPermissions,
    }),
    errors: errs(),
  }),
  route({ method: "PATCH", url: "/v1/orgs/:orgId/ai/agents/:installId", tag, summary: "Change an agent's settings, tools, limits or version (ai.manage).", auth: true, params: AiInstallParams, body: AiInstallPatch, response: Agent, errors: errs("name_not_allowed", "conflict", "agent_unavailable", "tool_not_allowed", "invalid_policy") }),
  route({ method: "PUT", url: "/v1/orgs/:orgId/ai/agents/:installId/avatar", tag, summary: "Set an agent's avatar (PNG, JPEG or WebP, at most 512 KB; ai.manage).", auth: true, params: AiInstallParams, binary: "request", response: Agent, errors: errs("too_large") }),
  route({ method: "GET", url: "/v1/orgs/:orgId/ai/agents/:installId/avatar", tag, summary: "An agent's avatar image.", auth: true, params: AiInstallParams, binary: "response", response: z.unknown(), errors: errs() }),
  route({ method: "DELETE", url: "/v1/orgs/:orgId/ai/agents/:installId", tag, summary: "Uninstall an agent: its jobs are cancelled and its channels removed (ai.manage).", auth: true, params: AiInstallParams, response: z.strictObject({ removed: z.literal(true) }), errors: errs() }),
  route({ method: "PUT", url: "/v1/orgs/:orgId/ai/agents/:installId/channels/:conversationId", tag, summary: "Assign an agent to a channel, or change how it's asked there (ai.manage).", auth: true, params: AiChannelParams, body: AiChannelBody, response: Channels, errors: errs("agent_not_assigned") }),
  route({ method: "DELETE", url: "/v1/orgs/:orgId/ai/agents/:installId/channels/:conversationId", tag, summary: "Remove an agent from a channel (ai.manage).", auth: true, params: AiChannelParams, response: Channels, errors: errs() }),
  route({ method: "POST", url: "/v1/orgs/:orgId/ai/files", tag, summary: "Upload a file for a request (raw bytes; not end-to-end encrypted).", auth: true, params: AiOrgParams, query: AiUploadQuery, binary: "request", response: z.strictObject({ file: AiFileView }), status: 201, errors: errs("agent_not_assigned", "too_large", "quota_exceeded") }),
  route({
    method: "POST",
    url: "/v1/orgs/:orgId/ai/jobs",
    tag,
    summary: "Ask an agent for work, optionally from a channel with files and the channel's recent messages the person chose to share.",
    auth: true,
    params: AiOrgParams,
    body: AiJobBody,
    response: z.strictObject({ job: AiJobDetail }),
    status: 202,
    errors: errs("agent_unavailable", "agent_not_assigned", "quota_exceeded", "too_large", "ai_paused", "ai_disabled"),
  }),
  route({ method: "GET", url: "/v1/orgs/:orgId/ai/jobs", tag, summary: "Requests: the person's own, or all they may see (root jobs, newest first).", auth: true, params: AiOrgParams, query: AiJobsQuery, response: z.strictObject({ jobs: z.array(AiJobSummary), next: ntime }), errors: errs() }),
  route({ method: "GET", url: "/v1/orgs/:orgId/ai/jobs/:jobId", tag, summary: "One request with its input, output, files, approvals, steps and events.", auth: true, params: AiJobParams, response: z.strictObject({ job: AiJobDetail }), errors: errs() }),
  jobAction("cancel", "Cancel a request (the requester or ai.manage)."),
  jobAction("retry", "Retry a failed or cancelled request; admitted like a new one."),
  jobAction("revise", "Ask for a revision of a finished request, with a note.", AiReviseBody),
  jobAction("answer", "Answer the question an agent asked.", AiAnswerBody),
  route({ method: "GET", url: "/v1/orgs/:orgId/ai/approvals", tag, summary: "Pending approvals of requests the person can see.", auth: true, params: AiOrgParams, response: z.strictObject({ approvals: z.array(AiApprovalView) }), errors: errs() }),
  route({ method: "POST", url: "/v1/orgs/:orgId/ai/approvals/:approvalId", tag, summary: "Approve or reject what an agent asked permission for (ai.approve, or the requester when allowed).", auth: true, params: AiApprovalParams, body: AiDecisionBody, response: Job, errors: errs("approval_stale", "conflict") }),
  route({ method: "GET", url: "/v1/orgs/:orgId/ai/deliverables", tag, summary: "Files agents made in requests the person can see, newest first.", auth: true, params: AiOrgParams, query: AiDeliverablesQuery, response: z.strictObject({ files: z.array(AiFileView), next: ntime }), errors: errs() }),
  route({ method: "GET", url: "/v1/orgs/:orgId/ai/files/:fileId", tag, summary: "Download a file of a request (ranges allowed).", auth: true, params: AiFileParams, binary: "response", response: z.unknown(), errors: errs("quota_exceeded") }),
  route({
    method: "GET",
    url: "/v1/conversations/:id/ai/agents",
    tag,
    summary: "The agents assigned to a channel, how each is asked there, and whether the person may ask them.",
    auth: true,
    params: AiConversationParams,
    response: z.strictObject({ agents: z.array(AiInstallView.extend({ activation: Activation, canInvoke: z.boolean() })), permissions: AiPermissions }),
    errors: errs(),
  }),
  route({ method: "GET", url: "/v1/conversations/:id/ai/posts", tag, summary: "What agents posted in a channel (not end-to-end encrypted).", auth: true, params: AiConversationParams, query: AiPostsQuery, response: z.strictObject({ posts: z.array(AiPostView) }), errors: errs() }),
];
