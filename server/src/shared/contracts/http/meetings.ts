import { z } from "zod/v4";
import { idParams, zTime } from "./primitives.js";
import { route } from "./route.js";

/**
 * Scheduled meetings in voice rooms (docs/WORKSPACE_C5.md), shown as community events in the apps. Title and times are
 * readable by the server, like a channel's name.
 */

/** Limits on what a meeting holds. */
export const MEETING_INPUT_LIMITS = { participants: 200, titleChars: 120, descriptionChars: 2000 } as const;
/** How a meeting repeats (batch B8b): the same values as community events (modules/organizations/eventRepeat.ts). */
export const MEETING_REPEATS = ["weekly", "biweekly", "monthly", "yearly"] as const;

const Repeat = z.enum(MEETING_REPEATS);
const MeetingAnswer = z.enum(["accepted", "declined", "tentative"]);
/** An invitation's state: "pending" until the person answers (and again after the time changes). */
const InvitationState = z.enum(["pending", ...MeetingAnswer.options]);

export const Meeting = z.strictObject({
  id: z.string(),
  orgId: z.string(),
  title: z.string(),
  description: z.string().nullable(),
  /** The voice room; null if it was deleted. */
  room: z.strictObject({ id: z.string(), name: z.string().nullable() }).nullable(),
  startsAt: zTime,
  endsAt: zTime,
  /** IANA time zone the meeting was planned in. */
  timeZone: z.string(),
  organizer: z.strictObject({ id: z.string(), name: z.string().nullable() }),
  participants: z.array(z.strictObject({ id: z.string(), name: z.string().nullable(), response: InvitationState })),
  /** null: the viewer isn't invited. */
  myResponse: InvitationState.nullable(),
  cancelled: z.boolean(),
  /** null: once. */
  repeat: Repeat.nullable(),
  canEdit: z.boolean(),
  /** Send back with PUT; a stale one answers stale_revision. */
  rev: z.number().int().positive(),
});
export const MeetingOnly = z.strictObject({ meeting: Meeting });

export const OrgParams = z.strictObject({ orgId: z.string().max(64) });
export const MeetingListQuery = z.object({ from: z.coerce.number().int().optional(), to: z.coerce.number().int().optional() });
export const MeetingBody = z.object({
  roomId: z.string().max(64),
  title: z.string().trim().min(1).max(MEETING_INPUT_LIMITS.titleChars),
  description: z.string().trim().max(MEETING_INPUT_LIMITS.descriptionChars).nullish(),
  startsAt: z.number().int(),
  endsAt: z.number().int(),
  timeZone: z.string().max(64),
  participantIds: z.array(z.string().max(64)).max(MEETING_INPUT_LIMITS.participants).default([]),
  /** User groups were removed with the rebuild; older apps send an empty list. */
  groupIds: z.array(z.string().max(64)).max(0, "User groups were removed: invite people instead").default([]),
  /**
   * Batch B8b: how the event repeats (absent on older apps: unchanged on edit, once on create). "none" (or null) turns
   * repeating off; apps that leave nulls out send "none".
   */
  repeat: z
    .enum([...MEETING_REPEATS, "none"])
    .nullable()
    .optional()
    .transform((v) => (v === "none" ? null : v)),
});
export const MeetingUpdateBody = MeetingBody.extend({ rev: z.number().int().min(1) });
export const MeetingRespondBody = z.object({ response: MeetingAnswer });

const meeting = (c: Omit<Parameters<typeof route>[0], "tag" | "auth" | "response">) => route({ tag: "meetings", auth: true, response: MeetingOnly, ...c });

export const meetingRoutes = [
  route({
    method: "GET",
    url: "/v1/orgs/:orgId/meetings",
    tag: "meetings",
    summary: "Meetings the person may see in [from, to), earliest first (default: from an hour ago, the next 30 days).",
    auth: true,
    params: OrgParams,
    query: MeetingListQuery,
    response: z.strictObject({ meetings: z.array(Meeting) }),
    errors: ["not_found"],
  }),
  meeting({
    method: "POST",
    url: "/v1/orgs/:orgId/meetings",
    summary: "Schedule a meeting in a voice room; everyone invited must be able to open the room.",
    params: OrgParams,
    body: MeetingBody,
    errors: ["not_found", "forbidden", "conflict"],
  }),
  meeting({ method: "GET", url: "/v1/meetings/:id", summary: "One meeting.", params: idParams, errors: ["not_found"] }),
  meeting({
    method: "PUT",
    url: "/v1/meetings/:id",
    summary: "Change a meeting (organizer or the room's managers), at the revision last read.",
    params: idParams,
    body: MeetingUpdateBody,
    errors: ["not_found", "forbidden", "conflict", "stale_revision"],
  }),
  meeting({
    method: "POST",
    url: "/v1/meetings/:id/cancel",
    summary: "Cancel a meeting (organizer or the room's managers).",
    params: idParams,
    errors: ["not_found", "forbidden"],
  }),
  meeting({
    method: "POST",
    url: "/v1/meetings/:id/respond",
    summary: "Answer an invitation: accepted, declined or tentative.",
    params: idParams,
    body: MeetingRespondBody,
    errors: ["not_found"],
  }),
];
