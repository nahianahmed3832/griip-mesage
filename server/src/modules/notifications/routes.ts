import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { type AppContext } from "../../app/context.js";
import { me, requireAuth } from "../auth/index.js";
import { parse } from "../../shared/validation/index.js";
import { checkEndpoint, ENDPOINT_REFUSALS, MAX_ENDPOINT_LENGTH } from "./address.js";
import { removeUnifiedPushEndpoint } from "./service.js";
import { MAX_FCM_TOKEN_LENGTH } from "./fcm.js";
import { ApiError } from "../../shared/errors.js";

/**
 * Push wake-ups (docs/BACKGROUND_DELIVERY.md): the signed-in device registers, replaces or removes its own UnifiedPush
 * endpoint. Only endpoints on the server's allow-listed push hosts are accepted; wake-ups never carry content.
 */
export default async function pushRoutes(app: FastifyInstance, ctx: AppContext) {
  const auth = { preHandler: requireAuth(ctx) };

  app.put("/v1/devices/current/push", auth, async (req) => {
    const { deviceId } = me(req);
    const push = ctx.push;
    if (!push) throw new ApiError(404, "push_unavailable", "This server doesn't send push wake-ups");
    const body = parse(z.object({ kind: z.literal("unifiedpush"), endpoint: z.string().min(1).max(MAX_ENDPOINT_LENGTH) }), req.body);
    const checked = checkEndpoint(body.endpoint, push.config.unifiedPushHosts);
    if ("refusal" in checked) {
      const details = checked.refusal === "push_host_not_allowed" ? { allowedHosts: push.config.unifiedPushHosts } : undefined;
      throw new ApiError(400, checked.refusal, ENDPOINT_REFUSALS[checked.refusal], details);
    }
    return { push: push.register(deviceId, checked.url) };
  });

  /** Firebase (docs/PUSH_FCM.md): this device's FCM registration token, when the server has FCM configured. */
  app.put("/v1/devices/current/fcm", auth, async (req) => {
    const { deviceId } = me(req);
    if (!ctx.fcm) throw new ApiError(404, "push_unavailable", "This server doesn't send Firebase wake-ups");
    const { token } = parse(z.object({ token: z.string().min(20).max(MAX_FCM_TOKEN_LENGTH).regex(/^[A-Za-z0-9_:\-.]+$/) }), req.body);
    return { push: ctx.fcm.register(deviceId, token) };
  });

  app.delete("/v1/devices/current/fcm", auth, async (req) => {
    const { deviceId } = me(req);
    const removed = ctx.db.prepare("DELETE FROM fcm_tokens WHERE device_id = ?").run(deviceId).changes > 0;
    return { ok: true, removed };
  });

  // Also works while the server has push turned off, so a device can always withdraw its endpoint.
  app.delete("/v1/devices/current/push", auth, async (req) => {
    const { deviceId } = me(req);
    const removed = ctx.push ? ctx.push.unregister(deviceId) : removeUnifiedPushEndpoint(ctx.db, deviceId);
    return { ok: true, removed };
  });
}
