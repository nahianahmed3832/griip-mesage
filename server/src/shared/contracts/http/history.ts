import { z } from "zod/v4";
import { zCount, zTime } from "./primitives.js";
import { route } from "./route.js";

/** Opaque tags chosen by the account's devices (the server can't link them to messages or conversations). */
const TAG = /^[A-Za-z0-9_-]{16,64}$/;
export const HISTORY_MAX_BATCH = 200;

export const HistorySummary = z.strictObject({
  enabled: z.boolean(),
  items: zCount,
  bytes: zCount,
  /** The account's latest write (cursor for GET /v1/history/items). */
  seq: zCount,
  maxItems: zCount,
  maxBytes: zCount,
  maxItemBytes: zCount,
});

/** [maxBodyB64]: the server's item limit in base64 characters. */
export const historyPutBody = (maxBodyB64: number) =>
  z.object({
    items: z
      .array(
        z.object({
          tag: z.string().regex(TAG),
          conv: z.string().regex(TAG),
          ts: z.number().int().min(0).max(8_640_000_000_000_000),
          /** base64 ciphertext, or null to mark the item deleted (other devices then drop it too). */
          body: z.string().max(maxBodyB64).nullable(),
        }),
      )
      .min(1)
      .max(HISTORY_MAX_BATCH),
  });

export const HistoryItemsQuery = z.object({
  after: z.coerce.number().int().min(0).default(0),
  limit: z.coerce.number().int().min(1).max(1000).default(500),
});

export const HistoryItemsPage = z.strictObject({
  items: z.array(z.strictObject({ tag: z.string(), conv: z.string(), seq: zCount, ts: zTime, body: z.string().nullable() })),
  /** Pass as `after` for the next page. */
  next: zCount,
  more: z.boolean(),
});

export const historyRoutes = [
  route({ method: "GET", url: "/v1/history/summary", tag: "history", summary: "The account's encrypted history: counts, size and limits.", auth: true, response: HistorySummary }),
  route({
    method: "PUT",
    url: "/v1/history/items",
    tag: "history",
    summary: "Add, replace or mark deleted encrypted history items. All or nothing.",
    auth: true,
    // Documented with the default item limit; the server applies its configured one.
    body: historyPutBody(Math.ceil((65_536 * 4) / 3) + 4),
    response: z.strictObject({ seq: zCount, items: zCount, bytes: zCount }),
    errors: ["history_off", "too_large", "quota_exceeded", "history_full"],
  }),
  route({
    method: "GET",
    url: "/v1/history/items",
    tag: "history",
    summary: "Items written after a cursor, oldest write first.",
    auth: true,
    query: HistoryItemsQuery,
    response: HistoryItemsPage,
    errors: ["history_off"],
  }),
  route({ method: "DELETE", url: "/v1/history", tag: "history", summary: "Delete the account's whole history from the server.", auth: true, response: z.strictObject({ deleted: zCount }) }),
];
