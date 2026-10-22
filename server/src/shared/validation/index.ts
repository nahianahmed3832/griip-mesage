import { z } from "zod";
import { badRequest } from "../errors.js";

/** Request validation shared by every route: zod schemas and the 400 they produce (docs/architecture/ARCHITECTURE_1_0_PLAN.md §4). */
export function parse<T extends z.ZodTypeAny>(schema: T, data: unknown): z.infer<T> {
  const r = schema.safeParse(data);
  if (!r.success) {
    throw badRequest(
      "Invalid request",
      r.error.issues.map((i) => ({ path: i.path.join("."), message: i.message })),
    );
  }
  return r.data;
}

export const zId = z.string().min(3).max(64).regex(/^[A-Za-z0-9_-]+$/);
export const zB64 = z.string().min(4).max(400_000).regex(/^[A-Za-z0-9+/]+={0,2}$/);
export const zKey = z.string().length(44).regex(/^[A-Za-z0-9+/]+={0,2}$/);
export const zSig = z.string().length(88).regex(/^[A-Za-z0-9+/]+={0,2}$/);
export const zName = z.string().trim().min(1).max(80);
export const zText = z.string().trim().max(500);
