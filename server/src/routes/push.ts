import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { type AppContext, me, parse, requireAuth } from "../context.js";
import { checkEndpoint, ENDPOINT_REFUSALS, MAX_ENDPOINT_LENGTH } from "../push/address.js";
import { removePushEndpoint } from "../push/service.js";
import { ApiError } from "../util.js";

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

  // Also works while the server has push turned off, so a device can always withdraw its endpoint.
  app.delete("/v1/devices/current/push", auth, async (req) => {
    const { deviceId } = me(req);
    const removed = ctx.push ? ctx.push.unregister(deviceId) : removePushEndpoint(ctx.db, deviceId);
    return { ok: true, removed };
  });
}
