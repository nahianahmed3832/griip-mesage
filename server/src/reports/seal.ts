import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const KEY_BYTES = 32;
const IV_BYTES = 12;
const TAG_BYTES = 16;
const PREFIX = "r1.";

/**
 * Seals what a reporter chose to share (batch B4) with the server's reports key (AES-256-GCM, bound to the report id),
 * so a copy of the database alone doesn't show reported messages. The key comes from REPORTS_KEY (base64, 32 bytes:
 * required wherever several servers share one database) or, on a single server, from `reports.key` in the data
 * folder, created on first use with owner-only permissions.
 */
export class ReportSealer {
  private constructor(private readonly key: Buffer) {}

  static load(env: NodeJS.ProcessEnv, dataDir: string): ReportSealer {
    const fromEnv = env.REPORTS_KEY?.trim();
    if (fromEnv) {
      const key = Buffer.from(fromEnv, "base64");
      if (key.length !== KEY_BYTES) throw new Error("REPORTS_KEY must be 32 bytes, base64-encoded");
      return new ReportSealer(key);
    }
    const file = path.join(dataDir, "reports.key");
    if (!fs.existsSync(file)) {
      fs.mkdirSync(dataDir, { recursive: true });
      fs.writeFileSync(file, crypto.randomBytes(KEY_BYTES).toString("base64"), { mode: 0o600, flag: "wx" });
    }
    const key = Buffer.from(fs.readFileSync(file, "utf8").trim(), "base64");
    if (key.length !== KEY_BYTES) throw new Error(`${file} doesn't hold a 32-byte key`);
    return new ReportSealer(key);
  }

  seal(reportId: string, value: unknown): string {
    const iv = crypto.randomBytes(IV_BYTES);
    const cipher = crypto.createCipheriv("aes-256-gcm", this.key, iv);
    cipher.setAAD(Buffer.from(reportId));
    const body = Buffer.concat([cipher.update(JSON.stringify(value), "utf8"), cipher.final()]);
    return PREFIX + Buffer.concat([iv, body, cipher.getAuthTag()]).toString("base64url");
  }

  /** The sealed value, or null when it doesn't open (another key, tampered, or a different report). */
  open(reportId: string, sealed: string | null): unknown {
    if (!sealed?.startsWith(PREFIX)) return null;
    const bytes = Buffer.from(sealed.slice(PREFIX.length), "base64url");
    if (bytes.length <= IV_BYTES + TAG_BYTES) return null;
    try {
      const decipher = crypto.createDecipheriv("aes-256-gcm", this.key, bytes.subarray(0, IV_BYTES));
      decipher.setAAD(Buffer.from(reportId));
      decipher.setAuthTag(bytes.subarray(bytes.length - TAG_BYTES));
      const plain = Buffer.concat([decipher.update(bytes.subarray(IV_BYTES, bytes.length - TAG_BYTES)), decipher.final()]);
      return JSON.parse(plain.toString("utf8"));
    } catch {
      return null;
    }
  }
}
