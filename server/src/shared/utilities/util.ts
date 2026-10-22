import crypto from "node:crypto";

export const now = (): number => Date.now();

/** Unguessable, URL-safe identifier with a readable type prefix. */
export function newId(prefix: string, bytes = 16): string {
  return `${prefix}_${crypto.randomBytes(bytes).toString("base64url")}`;
}

export function sha256Hex(input: string | Buffer): string {
  return crypto.createHash("sha256").update(input).digest("hex");
}

const CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"; // no I, L, O, 0, 1

/** Human-typeable random code (5 bits/char, rejection-free since 31 < 32 → use mod with 256 rejection). */
export function humanCode(length: number): string {
  let out = "";
  while (out.length < length) {
    const buf = crypto.randomBytes(length * 2);
    for (const b of buf) {
      if (b >= 248) continue; // 248 = 8 * 31, keeps distribution uniform
      out += CODE_ALPHABET[b % 31];
      if (out.length === length) break;
    }
  }
  return out;
}

export function normalizeCode(code: string): string {
  return code.toUpperCase().replace(/[^A-Z0-9]/g, "");
}
