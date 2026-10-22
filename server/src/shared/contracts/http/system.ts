import { z } from "zod/v4";
import { zCount, zTime } from "./primitives.js";
import { route } from "./route.js";

/**
 * Public, unauthenticated answers: health and what a server is (for setup screens and compatibility checks). Fields
 * are only ever added; `features` names are never reused.
 */
export const ServerInfo = z.strictObject({
  name: z.string(),
  /** Version of this document. */
  infoVersion: z.number().int().positive(),
  /** The client API this server speaks, and the oldest client API it accepts: a client older than minClientApi must update. */
  api: z.number().int().positive(),
  minClientApi: z.number().int().positive(),
  instanceName: z.string().nullable(),
  registration: z.enum(["open", "invite"]),
  federation: z.strictObject({ domain: z.string(), serverId: z.string(), protocols: z.array(z.string()) }).nullable(),
  version: z.string(),
  calling: z.boolean(),
  queueTtlSeconds: zCount,
  attachmentMaxBytes: zCount,
  /** The largest file a person may send (bytes of the file itself; apps check before encrypting). */
  fileMaxBytes: zCount,
  accounts: z.strictObject({
    registration: z.boolean(),
    legacyLogin: z.boolean(),
    legacyRegistration: z.boolean(),
    legacyMigration: z.boolean(),
    legacyMigrationRequired: z.boolean(),
  }),
  attachmentTtlSeconds: zCount,
  encryption: z.string(),
  /** What this server supports beyond API 1 (e.g. "accounts-v2", "direct-upload", "history"). */
  features: z.array(z.string()),
  history: z.strictObject({ maxItemBytes: zCount }).nullable(),
  attachmentKeepDays: zCount,
  push: z.strictObject({ unifiedPushHosts: z.array(z.string()) }).nullable(),
});

export const Health = z.strictObject({
  ok: z.boolean(),
  version: z.string(),
  time: zTime,
  livekit: z.boolean(),
  /** null: this server doesn't use MongoDB; false answers 503. */
  mongodb: z.boolean().nullable(),
});

export const systemRoutes = [
  route({ method: "GET", url: "/healthz", tag: "system", summary: "Liveness and database reachability (503 when MongoDB is unreachable).", auth: false, response: Health }),
  route({ method: "GET", url: "/v1/server-info", tag: "system", summary: "What this server is and supports.", auth: false, response: ServerInfo }),
];
