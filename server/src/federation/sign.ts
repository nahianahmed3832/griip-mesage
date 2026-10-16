/**
 * Signing helpers for federation records. Ed25519 from node:crypto only (no custom primitives); keys and
 * signatures are standard base64 of the raw 32-byte public key / 64-byte signature, as elsewhere in Griip.
 * Records are signed over their canonical JSON (keys sorted, no whitespace), so any field change breaks the signature.
 */
import crypto from "node:crypto";
import { verifyEd25519 } from "../crypto.js";

export interface KeyPair {
  publicKey: string; // base64 raw 32 bytes
  privateKey: string; // base64url JWK "d" (32-byte seed)
}

export function generateKeyPair(): KeyPair {
  const { publicKey, privateKey } = crypto.generateKeyPairSync("ed25519");
  const pub = publicKey.export({ format: "jwk" }) as { x: string };
  const priv = privateKey.export({ format: "jwk" }) as { d: string };
  return { publicKey: Buffer.from(pub.x, "base64url").toString("base64"), privateKey: priv.d };
}

export function signText(kp: KeyPair, message: string): string {
  const key = crypto.createPrivateKey({
    key: { kty: "OKP", crv: "Ed25519", x: Buffer.from(kp.publicKey, "base64").toString("base64url"), d: kp.privateKey },
    format: "jwk",
  });
  return crypto.sign(null, Buffer.from(message, "utf8"), key).toString("base64");
}

export const verifyText = verifyEd25519;

/** Deterministic JSON: object keys sorted, no whitespace, `undefined` fields dropped. Arrays keep their order. */
export function canonicalJson(v: unknown): string {
  if (v === null || typeof v !== "object") {
    if (typeof v === "number" && !Number.isFinite(v)) throw new Error("non-finite number in signed record");
    return JSON.stringify(v);
  }
  if (Array.isArray(v)) return `[${v.map(canonicalJson).join(",")}]`;
  const o = v as Record<string, unknown>;
  const keys = Object.keys(o).filter((k) => o[k] !== undefined).sort();
  return `{${keys.map((k) => `${JSON.stringify(k)}:${canonicalJson(o[k])}`).join(",")}}`;
}

/** Signs `record` (without its `sig` field) and returns it with `sig` set. */
export function signRecord<T extends object>(kp: KeyPair, record: T): T & { sig: string } {
  const { sig: _drop, ...body } = record as T & { sig?: string };
  return { ...(body as T), sig: signText(kp, canonicalJson(body)) };
}

export function verifyRecord(publicKey: string, record: { sig?: string; [k: string]: unknown } | object): boolean {
  const r = record as { sig?: unknown; [k: string]: unknown };
  if (typeof r?.sig !== "string") return false;
  const { sig, ...body } = r;
  try {
    return verifyText(publicKey, canonicalJson(body), sig);
  } catch {
    return false;
  }
}

export function sha256b64(data: string | Buffer): string {
  return crypto.createHash("sha256").update(data).digest("base64");
}

const B32 = "abcdefghijklmnopqrstuvwxyz234567";

/** RFC 4648 base32, lowercase, no padding. */
export function base32(buf: Buffer): string {
  let bits = 0;
  let value = 0;
  let out = "";
  for (const byte of buf) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      out += B32[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) out += B32[(value << (5 - bits)) & 31];
  return out;
}

export function randomToken(bytes = 16): string {
  return crypto.randomBytes(bytes).toString("base64url");
}
