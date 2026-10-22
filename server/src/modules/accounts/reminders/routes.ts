import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { type AppContext } from "../../../app/context.js";
import { me, requireAuth } from "../../auth/index.js";
import { parse } from "../../../shared/validation/index.js";
import { badRequest } from "../../../shared/errors.js";
import { isValidTimeZone, LOCAL_DATE, LOCAL_TIME, RECURRENCES } from "./schedule.js";

/** Sealed content: base64url, small (a title, a note and what it points at, encrypted on the phone). */
const zSealed = z.string().min(24).max(6000).regex(/^[A-Za-z0-9_-]+$/);
const zId = z.string().min(8).max(64).regex(/^[A-Za-z0-9_-]+$/);
const zTimeZone = z.string().min(1).max(64).refine(isValidTimeZone, "Unknown time zone");
/** Due times from the year 2020 to 2100 (catches seconds sent as milliseconds and other unit slips). */
const zInstant = z.number().int().min(1_577_836_800_000).max(4_102_444_800_000);
const zRepeat = z
  .object({ recurrence: z.enum(RECURRENCES), localTime: z.string().regex(LOCAL_TIME), anchorDate: z.string().regex(LOCAL_DATE) })
  .nullable();
const zState = z.enum(["scheduled", "fired", "done", "dismissed"]);

const createBody = z.object({ id: zId, sealed: zSealed, dueAt: zInstant, timeZone: zTimeZone, repeat: zRepeat.optional() });
const updateBody = z
  .object({
    rev: z.number().int().min(1),
    sealed: zSealed.optional(),
    dueAt: zInstant.optional(),
    timeZone: zTimeZone.optional(),
    repeat: zRepeat.optional(),
    // "fired" is the server's to set; people schedule (snooze), finish or dismiss.
    state: zState.exclude(["fired"]).optional(),
  })
  .refine((b) => Object.keys(b).length > 1, "Nothing to change");

/**
 * Reminders (docs/REMINDERS.md): the person's own, on any of their devices. Content is sealed by the app; only the
 * owner can list, change or delete theirs (anyone else's id answers like one that doesn't exist).
 */
export default async function reminderRoutes(app: FastifyInstance, ctx: AppContext) {
  const auth = { preHandler: requireAuth(ctx) };
  const writes = { ...auth, config: { rateLimit: { max: 120, timeWindow: "1 minute" } } };
  const reminders = ctx.reminders;

  app.get("/v1/reminders", auth, async (req) => {
    const { userId } = me(req);
    const { state } = parse(z.object({ state: zState.optional() }), req.query);
    return { reminders: reminders.list(userId, state) };
  });

  app.get("/v1/reminders/:id", auth, async (req) => {
    const { userId } = me(req);
    const { id } = parse(z.object({ id: zId }), req.params);
    return { reminder: reminders.get(userId, id) };
  });

  app.post("/v1/reminders", writes, async (req, reply) => {
    const { userId, deviceId } = me(req);
    const body = parse(createBody, req.body);
    return reply.code(201).send({ reminder: reminders.create(userId, deviceId, body) });
  });

  app.patch("/v1/reminders/:id", writes, async (req) => {
    const { userId, deviceId } = me(req);
    const { id } = parse(z.object({ id: zId }), req.params);
    const body = parse(updateBody, req.body);
    if (body.repeat && !isValidTimeZone(body.timeZone ?? "UTC")) throw badRequest("Unknown time zone");
    return { reminder: reminders.update(userId, deviceId, id, body) };
  });

  app.delete("/v1/reminders/:id", writes, async (req) => {
    const { userId, deviceId } = me(req);
    const { id } = parse(z.object({ id: zId }), req.params);
    reminders.delete(userId, deviceId, id);
    return { ok: true };
  });

  app.post("/v1/reminders/:id/ack", writes, async (req) => {
    const { userId } = me(req);
    const { id } = parse(z.object({ id: zId }), req.params);
    const { firedAt } = parse(z.object({ firedAt: zInstant }), req.body);
    reminders.ack(userId, id, firedAt);
    return { ok: true };
  });
}
