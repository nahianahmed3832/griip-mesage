import { createHash, generateKeyPairSync, randomBytes, sign } from "node:crypto";
import { encodeCBOR } from "@levischuck/tiny-cbor";

const b64url = (b: Uint8Array) => Buffer.from(b).toString("base64url");
const sha256 = (b: Uint8Array | string) => createHash("sha256").update(b).digest();

/**
 * A software passkey (ES256) that answers WebAuthn ceremonies the way a phone's password manager does: "none"
 * attestation, user present and verified, a signature counter that stays 0 (synced passkeys don't count).
 * [origin] is what the platform reports: "https://<rp>" for a browser, "android:apk-key-hash:…" for an app.
 */
export class FakePasskey {
  private readonly keys = generateKeyPairSync("ec", { namedCurve: "P-256" });
  readonly credentialId = randomBytes(16);
  userVerified = true;

  constructor(
    private readonly rpId: string,
    public origin: string,
  ) {}

  get id(): string {
    return b64url(this.credentialId);
  }

  private cosePublicKey(): Uint8Array {
    const jwk = this.keys.publicKey.export({ format: "jwk" }) as { x: string; y: string };
    return encodeCBOR(
      new Map<number, number | Uint8Array>([
        [1, 2], // kty: EC2
        [3, -7], // alg: ES256
        [-1, 1], // crv: P-256
        [-2, Buffer.from(jwk.x, "base64url")],
        [-3, Buffer.from(jwk.y, "base64url")],
      ]),
    );
  }

  private authData(withCredential: boolean, rpId = this.rpId): Buffer {
    // Flags: user present (0x01), user verified (0x04), backup eligible and backed up (0x08, 0x10), credential data (0x40).
    const flags = 0x01 | (this.userVerified ? 0x04 : 0) | 0x08 | 0x10 | (withCredential ? 0x40 : 0);
    const parts: Buffer[] = [sha256(rpId), Buffer.from([flags]), Buffer.alloc(4)];
    if (withCredential) {
      const len = Buffer.alloc(2);
      len.writeUInt16BE(this.credentialId.length);
      parts.push(Buffer.alloc(16), len, this.credentialId, Buffer.from(this.cosePublicKey()));
    }
    return Buffer.concat(parts);
  }

  /** The answer to POST …/passkeys/options' [options] (navigator.credentials.create). */
  create(options: any): any {
    const clientDataJSON = Buffer.from(JSON.stringify({ type: "webauthn.create", challenge: options.challenge, origin: this.origin, crossOrigin: false }));
    const attestationObject = encodeCBOR(
      new Map<string, unknown>([
        ["fmt", "none"],
        ["attStmt", new Map()],
        ["authData", this.authData(true)],
      ]) as any,
    );
    return {
      id: this.id,
      rawId: this.id,
      type: "public-key",
      response: { clientDataJSON: b64url(clientDataJSON), attestationObject: b64url(attestationObject), transports: ["internal", "hybrid"] },
      clientExtensionResults: {},
      authenticatorAttachment: "platform",
    };
  }

  /** The answer to POST /v2/auth/passkey/options' [options] (navigator.credentials.get); [rpId] to forge another site. */
  get(options: any, userHandle?: string, rpId?: string): any {
    const clientDataJSON = Buffer.from(JSON.stringify({ type: "webauthn.get", challenge: options.challenge, origin: this.origin, crossOrigin: false }));
    const authenticatorData = this.authData(false, rpId);
    const signature = sign("sha256", Buffer.concat([authenticatorData, sha256(clientDataJSON)]), this.keys.privateKey);
    return {
      id: this.id,
      rawId: this.id,
      type: "public-key",
      response: {
        clientDataJSON: b64url(clientDataJSON),
        authenticatorData: b64url(authenticatorData),
        signature: b64url(signature),
        ...(userHandle ? { userHandle } : {}),
      },
      clientExtensionResults: {},
      authenticatorAttachment: "platform",
    };
  }
}
