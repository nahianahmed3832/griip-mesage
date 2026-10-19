import crypto from "node:crypto";

/**
 * Sealing of stored provider credentials (docs/INTEGRATIONS.md › Credentials). AES-256-GCM, the same AEAD the project
 * already uses, under a 32-byte server key from INTEGRATIONS_TOKEN_KEY; no new protocol. Each sealed value is bound to
 * the record it belongs to (associated data), so a token copied into another account's row doesn't open. A second key
 * (INTEGRATIONS_TOKEN_KEY_PREVIOUS) keeps older values readable during a key rotation; new values always use the first.
 *
 * Format: `v1.<key id>.<12-byte nonce, base64url>.<ciphertext and tag, base64url>`.
 */
export interface TokenKeyring {
  active: { id: string; key: Buffer };
  keys: ReadonlyMap<string, Buffer>;
}

const KEY_BYTES = 32;
const NONCE_BYTES = 12;
const TAG_BYTES = 16;

function decodeKey(b64: string, name: string): Buffer {
  const key = Buffer.from(b64, "base64");
  if (key.length !== KEY_BYTES || !/^[A-Za-z0-9+/=_-]+$/.test(b64)) {
    throw new Error(`${name} must be ${KEY_BYTES} random bytes in base64 (for example: openssl rand -base64 32)`);
  }
  return key;
}

/** A key's public id: derived from the key, so it names the key without revealing it. */
export function keyId(key: Buffer): string {
  return crypto.createHash("sha256").update("orbit-integration-token-key-id\n").update(key).digest("hex").slice(0, 12);
}

export function keyringFrom(current: string | null, previous: string | null): TokenKeyring | null {
  if (!current) return null;
  const active = decodeKey(current, "INTEGRATIONS_TOKEN_KEY");
  const keys = new Map<string, Buffer>([[keyId(active), active]]);
  if (previous) {
    const old = decodeKey(previous, "INTEGRATIONS_TOKEN_KEY_PREVIOUS");
    keys.set(keyId(old), old);
  }
  return { active: { id: keyId(active), key: active }, keys };
}

/** Associated data: what the sealed value is (purpose) and which record it belongs to. */
const aadFor = (purpose: string, recordId: string) => Buffer.from(`orbit-integration-v1\n${purpose}\n${recordId}`, "utf8");

export function seal(ring: TokenKeyring, plaintext: string, purpose: string, recordId: string): string {
  const nonce = crypto.randomBytes(NONCE_BYTES);
  const cipher = crypto.createCipheriv("aes-256-gcm", ring.active.key, nonce, { authTagLength: TAG_BYTES });
  cipher.setAAD(aadFor(purpose, recordId));
  const body = Buffer.concat([cipher.update(plaintext, "utf8"), cipher.final(), cipher.getAuthTag()]);
  return `v1.${ring.active.id}.${nonce.toString("base64url")}.${body.toString("base64url")}`;
}

export class SealError extends Error {}

/** Opens a sealed value; throws [SealError] for a wrong record, a tampered value or a key that is no longer configured. */
export function open(ring: TokenKeyring, sealed: string, purpose: string, recordId: string): string {
  const parts = sealed.split(".");
  if (parts.length !== 4 || parts[0] !== "v1") throw new SealError("Unknown sealed format");
  const key = ring.keys.get(parts[1]);
  if (!key) throw new SealError("Sealed with a key this server no longer has");
  const nonce = Buffer.from(parts[2], "base64url");
  const body = Buffer.from(parts[3], "base64url");
  if (nonce.length !== NONCE_BYTES || body.length < TAG_BYTES) throw new SealError("Malformed sealed value");
  try {
    const decipher = crypto.createDecipheriv("aes-256-gcm", key, nonce, { authTagLength: TAG_BYTES });
    decipher.setAAD(aadFor(purpose, recordId));
    decipher.setAuthTag(body.subarray(body.length - TAG_BYTES));
    return Buffer.concat([decipher.update(body.subarray(0, body.length - TAG_BYTES)), decipher.final()]).toString("utf8");
  } catch {
    throw new SealError("Sealed value doesn't open (wrong record or tampered)");
  }
}

/** The key a sealed value was made with (to find values to re-seal after a rotation). */
export function sealedKeyId(sealed: string): string | null {
  const parts = sealed.split(".");
  return parts.length === 4 && parts[0] === "v1" ? parts[1] : null;
}

/** Random URL-safe secret (state, PKCE verifier, begin token, browser cookie). */
export function randomSecret(bytes = 32): string {
  return crypto.randomBytes(bytes).toString("base64url");
}

/** PKCE S256 challenge for a verifier (RFC 7636). */
export function pkceChallenge(verifier: string): string {
  return crypto.createHash("sha256").update(verifier).digest("base64url");
}

/** Constant-time comparison of two strings (signatures, hashes). */
export function safeEqual(a: string, b: string): boolean {
  const x = Buffer.from(a, "utf8");
  const y = Buffer.from(b, "utf8");
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

export function hmacSha256Hex(secret: string, data: Buffer | string): string {
  return crypto.createHmac("sha256", secret).update(data).digest("hex");
}
