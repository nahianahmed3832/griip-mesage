import { z } from "zod/v4";
import { zTime } from "./primitives.js";
import { route } from "./route.js";

/**
 * Push wake-ups (docs/BACKGROUND_DELIVERY.md, docs/PUSH_FCM.md): the signed-in device registers, replaces or removes its
 * own UnifiedPush endpoint or Firebase token. Wake-ups never carry content.
 */

/** Longest endpoint URL a device may register (as src/modules/notifications/address.ts checks). */
export const MAX_PUSH_ENDPOINT_LENGTH = 1024;
/** Longest FCM registration token accepted (as src/modules/notifications/fcm.ts). */
export const MAX_FCM_TOKEN_LENGTH = 4096;

/** An https:// endpoint on one of the server's allowed push hosts (server-info › push.unifiedPushHosts). */
export const UnifiedPushBody = z.object({ kind: z.literal("unifiedpush"), endpoint: z.string().min(1).max(MAX_PUSH_ENDPOINT_LENGTH) });
export const FcmBody = z.object({ token: z.string().min(20).max(MAX_FCM_TOKEN_LENGTH).regex(/^[A-Za-z0-9_:\-.]+$/) });

export const UnifiedPushRegistration = z.strictObject({ kind: z.literal("unifiedpush"), host: z.string(), updatedAt: zTime });
export const FcmRegistration = z.strictObject({ kind: z.literal("fcm"), updatedAt: zTime });
/** removed: whether there was one to remove. */
export const PushRemoved = z.strictObject({ ok: z.literal(true), removed: z.boolean() });

export const notificationRoutes = [
  route({
    method: "PUT",
    url: "/v1/devices/current/push",
    tag: "notifications",
    summary: "Register or replace this device's UnifiedPush endpoint.",
    auth: true,
    body: UnifiedPushBody,
    response: z.strictObject({ push: UnifiedPushRegistration }),
    errors: ["push_unavailable", "push_endpoint_invalid", "push_host_not_allowed"],
  }),
  route({
    method: "DELETE",
    url: "/v1/devices/current/push",
    tag: "notifications",
    summary: "Remove this device's UnifiedPush endpoint (works while push is turned off too).",
    auth: true,
    response: PushRemoved,
  }),
  route({
    method: "PUT",
    url: "/v1/devices/current/fcm",
    tag: "notifications",
    summary: "Register or replace this device's Firebase registration token.",
    auth: true,
    body: FcmBody,
    response: z.strictObject({ push: FcmRegistration }),
    errors: ["push_unavailable"],
  }),
  route({
    method: "DELETE",
    url: "/v1/devices/current/fcm",
    tag: "notifications",
    summary: "Remove this device's Firebase registration token.",
    auth: true,
    response: PushRemoved,
  }),
];
