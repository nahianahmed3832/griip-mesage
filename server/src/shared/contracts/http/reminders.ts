import { z } from "zod/v4";
import { OkTrue } from "./auth.js";
import { zTime } from "./primitives.js";
import { route } from "./route.js";

/**
 * Reminders (docs/REMINDERS.md): the person's own, on any of their devices. Content is sealed by the app; only the owner
 * can list, change or delete theirs (anyone else's id answers like one that doesn't exist).
 */

export const Recurrence = z.enum(["daily", "weekdays", "weekly", "monthly"]);
export const ReminderState = z.enum(["scheduled", "fired", "done", "dismissed"]);

/** "HH:MM", 24-hour. */
const LOCAL_TIME = /^([01]\d|2[0-3]):([0-5]\d)$/;
/** "YYYY-MM-DD". */
const LOCAL_DATE = /^(\d{4})-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/;

/** Whether [tz] is an IANA time zone this runtime knows ("Asia/Dhaka", "UTC"). */
export function isValidTimeZone(tz: string): boolean {
  if (tz.length === 0 || tz.length > 64) return false;
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: tz });
    return true;
  } catch {
    return false;
  }
}

/** Sealed content: base64url, small (a title, a note and what it points at, encrypted on the phone). */
const zSealed = z.string().min(24).max(6000).regex(/^[A-Za-z0-9_-]+$/);
/** Chosen by the app. */
const zReminderId = z.string().min(8).max(64).regex(/^[A-Za-z0-9_-]+$/);
const zTimeZone = z.string().min(1).max(64).refine(isValidTimeZone, "Unknown time zone");
/** Due times from the year 2020 to 2100 (catches seconds sent as milliseconds and other unit slips). */
const zInstant = z.number().int().min(1_577_836_800_000).max(4_102_444_800_000);
/** A repeating reminder keeps its local wall-clock time in its time zone; null: it doesn't repeat. */
const zRepeat = z.object({ recurrence: Recurrence, localTime: z.string().regex(LOCAL_TIME), anchorDate: z.string().regex(LOCAL_DATE) }).nullable();

export const ReminderParams = z.object({ id: zReminderId });
export const ReminderListQuery = z.object({ state: ReminderState.optional() });
/** Creating the same id again answers the reminder already created (a retried request). */
export const ReminderCreateBody = z.object({ id: zReminderId, sealed: zSealed, dueAt: zInstant, timeZone: zTimeZone, repeat: zRepeat.optional() });
/** rev: the revision this change is based on. A new due time or rule puts the reminder back to scheduled. */
export const ReminderUpdateBody = z
  .object({
    rev: z.number().int().min(1),
    sealed: zSealed.optional(),
    dueAt: zInstant.optional(),
    timeZone: zTimeZone.optional(),
    repeat: zRepeat.optional(),
    // "fired" is the server's to set; people schedule (snooze), finish or dismiss.
    state: ReminderState.exclude(["fired"]).optional(),
  })
  .refine((b) => Object.keys(b).length > 1, "Nothing to change");
/** The occurrence a device showed. */
export const ReminderAckBody = z.object({ firedAt: zInstant });

export const Reminder = z.strictObject({
  id: z.string(),
  sealed: z.string(),
  dueAt: zTime,
  timeZone: z.string(),
  repeat: z.strictObject({ recurrence: Recurrence, localTime: z.string(), anchorDate: z.string() }).nullable(),
  state: ReminderState,
  firedAt: zTime.nullable(),
  deliveredAt: zTime.nullable(),
  /** "failed" after every delivery attempt of the last occurrence failed. */
  delivery: z.literal("failed").nullable(),
  rev: z.number().int().positive(),
  createdAt: zTime,
  updatedAt: zTime,
  completedAt: zTime.nullable(),
});
export const ReminderOnly = z.strictObject({ reminder: Reminder });

export const reminderRoutes = [
  route({
    method: "GET",
    url: "/v1/reminders",
    tag: "reminders",
    summary: "Your reminders (optionally in one state), by due time.",
    auth: true,
    query: ReminderListQuery,
    response: z.strictObject({ reminders: z.array(Reminder) }),
  }),
  route({
    method: "GET",
    url: "/v1/reminders/:id",
    tag: "reminders",
    summary: "One of your reminders.",
    auth: true,
    params: ReminderParams,
    response: ReminderOnly,
    errors: ["reminder_not_found"],
  }),
  route({
    method: "POST",
    url: "/v1/reminders",
    tag: "reminders",
    summary: "Create a reminder (idempotent on its id).",
    auth: true,
    body: ReminderCreateBody,
    response: ReminderOnly,
    status: 201,
    errors: ["reminder_id_taken", "too_many_reminders"],
  }),
  route({
    method: "PATCH",
    url: "/v1/reminders/:id",
    tag: "reminders",
    summary: "Edit, snooze, reschedule, complete or dismiss a reminder (409 with the current copy if it changed meanwhile).",
    auth: true,
    params: ReminderParams,
    body: ReminderUpdateBody,
    response: ReminderOnly,
    errors: ["reminder_not_found", "stale_reminder"],
  }),
  route({
    method: "DELETE",
    url: "/v1/reminders/:id",
    tag: "reminders",
    summary: "Delete a reminder.",
    auth: true,
    params: ReminderParams,
    response: OkTrue,
    errors: ["reminder_not_found"],
  }),
  route({
    method: "POST",
    url: "/v1/reminders/:id/ack",
    tag: "reminders",
    summary: "A device showed the occurrence that fired at firedAt.",
    auth: true,
    params: ReminderParams,
    body: ReminderAckBody,
    response: OkTrue,
    errors: ["reminder_not_found"],
  }),
];
