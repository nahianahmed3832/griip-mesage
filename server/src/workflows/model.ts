import { z } from "zod";
import { isValidTimeZone, LOCAL_DATE, LOCAL_TIME, RECURRENCES } from "../reminders/schedule.js";

/**
 * Workspace workflows (docs/WORKFLOWS.md, round 7 slice C3): the model, its validation, the catalog the apps show and
 * the templates. Pure: no database. Only triggers with a real server event behind them and a fixed set of actions;
 * nothing here can run code, impersonate a person or reach a channel its owner couldn't.
 */

export const TRIGGERS = [
  "member.joined",
  "member.left",
  "member.role_changed",
  "channel.created",
  "schedule",
  // Round 8 (docs/UNIFIED_INBOX.md): ids and names only, never the customer or their message.
  "inbox.conversation_created",
  "inbox.conversation_overdue",
  "inbox.connection_failed",
] as const;
export type TriggerType = (typeof TRIGGERS)[number];

export const ACTIONS = ["post_channel", "add_to_channels", "email_admins"] as const;
export type ActionType = (typeof ACTIONS)[number];

export const LIMITS = {
  workflowsPerOrg: 25,
  conditions: 5,
  actions: 5,
  channelsPerAction: 10,
  /** An app update's summary holds 300 characters. */
  textChars: 300,
  /** Runs a workspace may start per hour; more are recorded as skipped ("rate_limited"), never silently dropped. */
  runsPerOrgPerHour: 300,
  maxAttempts: 5,
  /** Events caused by a workflow's own action carry depth + 1; past this, nothing more runs (loop guard). */
  maxDepth: 2,
  runTimeoutMs: 15_000,
  leaseMs: 60_000,
  /** Admins emailed by one action at most. */
  emailRecipients: 20,
} as const;

const zId = z.string().min(1).max(64);
const zText = z.string().trim().min(1).max(LIMITS.textChars);

export const zSchedule = z.object({
  recurrence: z.enum(RECURRENCES),
  localTime: z.string().regex(LOCAL_TIME),
  anchorDate: z.string().regex(LOCAL_DATE),
  timeZone: z.string().max(64).refine(isValidTimeZone, "Unknown time zone"),
});

export const CONDITION_FIELDS = {
  "member.role": ["member.joined", "member.role_changed"],
  "member.new_role": ["member.role_changed"],
  "member.leave_reason": ["member.left"],
  "channel.kind": ["channel.created"],
  "channel.scope": ["channel.created"],
  "channel.name": ["channel.created"],
  "inbox.provider": ["inbox.conversation_created", "inbox.conversation_overdue", "inbox.connection_failed"],
  "inbox.queue": ["inbox.conversation_created", "inbox.conversation_overdue"],
} as const satisfies Record<string, readonly TriggerType[]>;
export type ConditionField = keyof typeof CONDITION_FIELDS;

export const zCondition = z.object({
  field: z.enum(Object.keys(CONDITION_FIELDS) as [ConditionField, ...ConditionField[]]),
  op: z.enum(["is", "is_not", "contains"]),
  value: z.string().trim().min(1).max(60),
});
export type Condition = z.infer<typeof zCondition>;

export const zAction = z.discriminatedUnion("type", [
  z.object({ type: z.literal("post_channel"), channelId: zId, text: zText }),
  z.object({ type: z.literal("add_to_channels"), channelIds: z.array(zId).min(1).max(LIMITS.channelsPerAction) }),
  z.object({ type: z.literal("email_admins"), subject: z.string().trim().min(1).max(120), text: zText }),
]);
export type Action = z.infer<typeof zAction>;

export const zWorkflowInput = z.object({
  name: z.string().trim().min(1).max(80),
  trigger: z.discriminatedUnion("type", [
    z.object({ type: z.literal("member.joined") }),
    z.object({ type: z.literal("member.left") }),
    z.object({ type: z.literal("member.role_changed") }),
    z.object({ type: z.literal("channel.created") }),
    z.object({ type: z.literal("schedule"), schedule: zSchedule }),
    z.object({ type: z.literal("inbox.conversation_created") }),
    z.object({ type: z.literal("inbox.conversation_overdue") }),
    z.object({ type: z.literal("inbox.connection_failed") }),
  ]),
  conditions: z.array(zCondition).max(LIMITS.conditions).default([]),
  actions: z.array(zAction).min(1).max(LIMITS.actions),
  enabled: z.boolean().default(true),
});
export type WorkflowInput = z.infer<typeof zWorkflowInput>;

/** Rules a schema can't express: which conditions and actions fit the trigger. Empty when valid. */
export function structuralProblems(w: WorkflowInput): string[] {
  const out: string[] = [];
  for (const c of w.conditions) {
    if (!(CONDITION_FIELDS[c.field] as readonly string[]).includes(w.trigger.type)) out.push(`The condition on ${c.field} doesn't apply to this trigger`);
  }
  const memberTrigger = w.trigger.type === "member.joined" || w.trigger.type === "member.role_changed";
  for (const a of w.actions) {
    if (a.type === "add_to_channels" && !memberTrigger) out.push("Adding to channels needs a member trigger (joined or role changed)");
  }
  return out;
}

/** What one event carries: ids and names only (a workspace's own structure; never message content). */
export interface WorkflowEvent {
  type: TriggerType;
  /** The event's own id: a run is unique per workflow and event. */
  key: string;
  orgId: string;
  depth: number;
  member?: { id: string; name: string; role: string; newRole?: string; leaveReason?: "left" | "removed" };
  channel?: { id: string; name: string; kind: string; scope: string };
  occurrenceAt?: number;
  /** Inbox events: the provider, the connected account's name and the queue's name. */
  inbox?: { provider: string; account: string; queue: string };
}

function fieldValue(e: WorkflowEvent, field: ConditionField): string | undefined {
  switch (field) {
    case "member.role":
      return e.member?.role;
    case "member.new_role":
      return e.member?.newRole;
    case "member.leave_reason":
      return e.member?.leaveReason;
    case "channel.kind":
      return e.channel?.kind;
    case "channel.scope":
      return e.channel?.scope;
    case "channel.name":
      return e.channel?.name;
    case "inbox.provider":
      return e.inbox?.provider;
    case "inbox.queue":
      return e.inbox?.queue;
  }
}

/** Whether every condition holds for [e] (case-insensitive). */
export function conditionsHold(conditions: readonly Condition[], e: WorkflowEvent): boolean {
  return conditions.every((c) => {
    const v = (fieldValue(e, c.field) ?? "").toLowerCase();
    const want = c.value.toLowerCase();
    if (c.op === "is") return v === want;
    if (c.op === "is_not") return v !== want;
    return v.includes(want);
  });
}

/**
 * A message template with {member}, {role}, {channel} and {workspace} filled in. Values are plain names the workspace
 * already shows its members; text is posted as an app update (shown as plain text, never as markup).
 */
export function render(text: string, e: WorkflowEvent, workspaceName: string): string {
  const vars: Record<string, string> = {
    member: e.member?.name ?? "",
    role: (e.member?.newRole ?? e.member?.role ?? "").toLowerCase(),
    channel: e.channel ? `#${e.channel.name}` : "",
    workspace: workspaceName,
    provider: e.inbox?.provider ?? "",
    account: e.inbox?.account ?? "",
    queue: e.inbox?.queue ?? "",
  };
  return text.replace(/\{(member|role|channel|workspace|provider|account|queue)\}/g, (_, k: string) => vars[k]).slice(0, LIMITS.textChars);
}

/** The catalog the apps build their editor from (labels live here once). */
export const CATALOG = {
  triggers: [
    { type: "member.joined", label: "Someone joins the workspace", description: "After they accept an invitation." },
    { type: "member.left", label: "Someone leaves or is removed", description: "When a member leaves or an admin removes them." },
    { type: "member.role_changed", label: "A member's access changes", description: "Between owner, admin, member and guest." },
    { type: "channel.created", label: "A channel is created", description: "Any new channel in the workspace." },
    { type: "schedule", label: "On a schedule", description: "Daily, on weekdays, weekly or monthly, at a time in a time zone." },
    { type: "inbox.conversation_created", label: "A customer starts a conversation", description: "In the Unified Inbox. You can use {provider}, {account} and {queue}; the customer and their message are never included." },
    { type: "inbox.conversation_overdue", label: "A customer has waited too long", description: "Past the inbox's response target, once per wait." },
    { type: "inbox.connection_failed", label: "A business account stops working", description: "Its token was refused or it needs reconnecting." },
  ],
  conditions: Object.entries(CONDITION_FIELDS).map(([field, triggers]) => ({ field, triggers })),
  actions: [
    { type: "post_channel", label: "Post an update in a channel", description: "Shown as a Workflows app update (not end-to-end encrypted). You can use {member}, {role}, {channel} and {workspace}." },
    { type: "add_to_channels", label: "Add the member to channels", description: "Public channels in their reach, or private channels you manage." },
    { type: "email_admins", label: "Email the workspace's admins", description: "To owners and admins with a verified email address." },
  ],
  /** Kinds the brief asks for that need modules this server doesn't have yet; listed so the apps can say so. */
  unavailable: [
    { type: "task.*", reason: "Tasks and projects aren't built yet" },
    { type: "shift.*", reason: "Shifts and attendance aren't built yet" },
    { type: "message.*", reason: "Messages are end-to-end encrypted: the server can't read them" },
    { type: "reminder.due", reason: "Reminders are sealed: the server knows only when, not what" },
  ],
  limits: LIMITS,
} as const;

/** Ready-made workflows; the app fills in the channels the person picks before saving. */
export const TEMPLATES = [
  {
    id: "welcome",
    name: "Welcome new members",
    description: "Add newcomers to chosen channels and greet them in one.",
    needs: ["channel"],
    workflow: {
      trigger: { type: "member.joined" },
      conditions: [{ field: "member.role", op: "is_not", value: "GUEST" }],
      actions: [
        { type: "add_to_channels", channelIds: ["{channel}"] },
        { type: "post_channel", channelId: "{channel}", text: "Welcome to {workspace}, {member}! 👋" },
      ],
    },
  },
  {
    id: "leaver",
    name: "Tell admins when someone leaves",
    description: "Email the owners and admins when a member leaves or is removed.",
    needs: [],
    workflow: {
      trigger: { type: "member.left" },
      conditions: [],
      actions: [{ type: "email_admins", subject: "{member} left {workspace}", text: "{member} is no longer a member of {workspace}." }],
    },
  },
  {
    id: "new-channel",
    name: "Announce new channels",
    description: "Post in a channel whenever a public channel is created.",
    needs: ["channel"],
    workflow: {
      trigger: { type: "channel.created" },
      conditions: [{ field: "channel.kind", op: "is", value: "PUBLIC" }],
      actions: [{ type: "post_channel", channelId: "{channel}", text: "New channel: {channel}" }],
    },
  },
  {
    id: "weekly",
    name: "Weekly reminder post",
    description: "Post the same reminder in a channel every week.",
    needs: ["channel", "schedule"],
    workflow: {
      trigger: { type: "schedule", schedule: { recurrence: "weekly", localTime: "09:00", anchorDate: "{today}", timeZone: "{timeZone}" } },
      conditions: [],
      actions: [{ type: "post_channel", channelId: "{channel}", text: "Weekly reminder: please update your project status." }],
    },
  },
] as const;
