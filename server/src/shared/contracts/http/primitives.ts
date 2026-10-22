import { z } from "zod/v4";

/** Wire primitives shared by the HTTP and WebSocket contracts. */
export const zId = z.string().min(3).max(64).regex(/^[A-Za-z0-9_-]+$/);
export const zB64 = z.string().min(4).max(400_000).regex(/^[A-Za-z0-9+/]+={0,2}$/);
/** A 32-byte key, base64. */
export const zKey = z.string().length(44).regex(/^[A-Za-z0-9+/]+={0,2}$/);
/** An Ed25519 signature, base64. */
export const zSig = z.string().length(88).regex(/^[A-Za-z0-9+/]+={0,2}$/);
/** Epoch milliseconds. */
export const zTime = z.number().int().nonnegative();
export const zCount = z.number().int().nonnegative();
/** An id the server issued (opaque). */
export const zRef = z.string().min(1).max(128);
export const idParams = z.strictObject({ id: z.string().max(64) });
