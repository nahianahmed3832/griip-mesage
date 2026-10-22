import crypto from "node:crypto";

/**
 * Canonical byte strings that clients sign. The server only ever VERIFIES signatures;
 * it never holds private keys and never decrypts message content.
 * Formats are documented in docs/SECURITY.md §3.
 */
export const canonical = {
  deviceCert: (identityKey: string, signingKey: string, encryptionKey: string) =>
    `griip-device-cert-v1\n${identityKey}\n${signingKey}\n${encryptionKey}`,
  authProof: (purpose: "register" | "login" | "add-device", nonce: string, signingKey: string) =>
    `griip-auth-v1\n${purpose}\n${nonce}\n${signingKey}`,
  /** Orbit 2.0 legacy migration: signed by the account IDENTITY key (derived from the recovery key on the phone). */
  legacyMigration: (username: string, nonce: string) => `griip-legacy-migration-v1\n${username}\n${nonce}`,
  envelope: (e: {
    messageId: string;
    conversationId: string;
    senderUserId: string;
    senderDeviceId: string;
    sentAt: number;
    cls: string;
    nonce: string;
    ciphertext: string;
  }) =>
    `griip-envelope-v1\n${e.messageId}\n${e.conversationId}\n${e.senderUserId}\n${e.senderDeviceId}\n${e.sentAt}\n${e.cls}\n${e.nonce}\n${e.ciphertext}`,
};

const B64 = /^[A-Za-z0-9+/]+={0,2}$/;

export function decodeKey(b64: string, expectedLen: number): Buffer | null {
  if (!B64.test(b64)) return null;
  const buf = Buffer.from(b64, "base64");
  return buf.length === expectedLen ? buf : null;
}

export function isValidPublicKey(b64: string): boolean {
  return decodeKey(b64, 32) !== null;
}

export function verifyEd25519(publicKeyB64: string, message: string, signatureB64: string): boolean {
  const pk = decodeKey(publicKeyB64, 32);
  const sig = decodeKey(signatureB64, 64);
  if (!pk || !sig) return false;
  try {
    const key = crypto.createPublicKey({
      key: { kty: "OKP", crv: "Ed25519", x: pk.toString("base64url") },
      format: "jwk",
    });
    return crypto.verify(null, Buffer.from(message, "utf8"), key, sig);
  } catch {
    return false;
  }
}

export function keyFingerprint(identityKeyB64: string): string {
  // SHA-256(identity key) → six 5-byte chunks → each mod 100000 → "12345 67890 …".
  // Same derivation as the Android client (docs/SECURITY.md §5).
  const h = crypto.createHash("sha256").update(Buffer.from(identityKeyB64, "base64")).digest();
  const groups: string[] = [];
  for (let i = 0; i < 6; i++) {
    const n = h.readUIntBE(i * 5, 5);
    groups.push(String(n % 100000).padStart(5, "0"));
  }
  return groups.join(" ");
}
