import crypto from "node:crypto";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { DEFAULT_FILE_MAX_BYTES, effectiveFileMaxBytes, loadConfig } from "../src/config.js";
import { type FakeS3, startFakeS3 } from "./support/fakeS3.js";
import type { GriipClient } from "./support/griipClient.js";
import { expectStatus, type Harness, startServer } from "./support/harness.js";

// Orbit 2.0 file limit: 1 GiB of the person's file, uploaded straight to S3 in parts (docs/ORBIT_2_ARCHITECTURE §7).
const MiB = 1024 * 1024;
const GiB = 1024 * MiB;
const creds = { accessKeyId: "AKIDFILES1G", secretAccessKey: "files-1g-test-secret" };
const anySha = crypto.createHash("sha256").update("x").digest("base64");
/** Ciphertext size of a file in the chunked format: 16 bytes of tag per started 1 MiB chunk. */
const encryptedSize = (plain: number) => plain + 16 * Math.max(Math.ceil(plain / MiB), 1);

describe("the 1 GiB limit in configuration", () => {
  it("defaults to exactly 1,073,741,824 bytes with room for encryption on top", () => {
    const c = loadConfig({ DATA_DIR: "/tmp/orbit-config-test" } as NodeJS.ProcessEnv);
    expect(DEFAULT_FILE_MAX_BYTES).toBe(1_073_741_824);
    expect(c.fileMaxBytes).toBe(GiB);
    expect(c.attachmentMaxBytes).toBe(GiB + MiB);
    expect(encryptedSize(GiB)).toBeLessThanOrEqual(c.attachmentMaxBytes);
    expect(effectiveFileMaxBytes(c)).toBe(GiB);
    expect(c.directUploads.sessionSeconds).toBe(24 * 3600);
  });

  it("tells apps a smaller file limit when the server's ciphertext limit is lower (production ran 25 MB)", () => {
    const c = loadConfig({ DATA_DIR: "/tmp/orbit-config-test", ATTACHMENT_MAX_BYTES: String(25 * MiB) } as NodeJS.ProcessEnv);
    const advertised = effectiveFileMaxBytes(c);
    expect(advertised).toBe(25 * MiB - 16 * 25);
    expect(encryptedSize(advertised)).toBeLessThanOrEqual(c.attachmentMaxBytes);
  });

  it("refuses development-only settings in production", () => {
    const base = { DATA_DIR: "/tmp/orbit-config-test", APP_ENV: "production" };
    expect(() => loadConfig({ ...base, MAIL_TRANSPORT: "file" } as NodeJS.ProcessEnv)).toThrow(/not allowed with APP_ENV=production/);
    expect(() => loadConfig({ ...base, PASSWORD_SCRYPT_LOG_N: "12" } as NodeJS.ProcessEnv)).toThrow(/not allowed with APP_ENV=production/);
    expect(() => loadConfig({ ...base, MAIL_TRANSPORT: "ses" } as NodeJS.ProcessEnv)).toThrow(/MAIL_FROM/);
    expect(loadConfig({ ...base, MAIL_TRANSPORT: "ses", MAIL_FROM: "Orbit <no-reply@example.com>" } as NodeJS.ProcessEnv).accounts.mail.transport).toBe("ses");
  });
});

describe("1 GiB uploads straight to S3", () => {
  let s3: FakeS3;
  let h: Harness;
  let alice: GriipClient, bob: GriipClient, eve: GriipClient;
  let dm: string;
  beforeAll(async () => {
    s3 = await startFakeS3("orbit-1g", "ap-southeast-1", creds);
    h = await startServer({
      STORAGE_BACKEND: "s3",
      S3_BUCKET: "orbit-1g",
      S3_REGION: "ap-southeast-1",
      S3_ENDPOINT: s3.url,
      S3_FORCE_PATH_STYLE: "true",
      S3_ACCESS_KEY_ID: creds.accessKeyId,
      S3_SECRET_ACCESS_KEY: creds.secretAccessKey,
    });
    alice = await h.client("alice1g");
    bob = await h.client("bob1g");
    eve = await h.client("eve1g");
    dm = (await alice.req("POST", "/v1/conversations/dm", { userId: bob.userId })).conversation.id;
  });
  afterAll(async () => {
    await h.close();
    await s3.close();
  });

  it("advertises the limit and the feature", async () => {
    const info = await alice.req("GET", "/v1/server-info");
    expect(info.fileMaxBytes).toBe(GiB);
    expect(info.attachmentMaxBytes).toBe(GiB + MiB);
    expect(info.features).toEqual(expect.arrayContaining(["files-1g", "direct-upload", "accounts-v2"]));
    expect(info.api).toBe(2);
    expect(info.minClientApi).toBe(1);
  });

  it("starts a multipart upload for an encrypted 1 GiB file, and refuses one byte over the limit", async () => {
    const size = encryptedSize(GiB);
    const s = await alice.req("POST", "/v1/attachments/uploads", { conversationId: dm, size, sha256: anySha });
    expect(s.mode).toBe("multipart");
    expect(s.partCount).toBe(Math.ceil(size / s.partSize));
    // Every part is presigned for its exact size: the last one is the remainder.
    const last = size - s.partSize * (s.partCount - 1);
    const urls = await alice.req("POST", `/v1/attachments/uploads/${s.uploadId}/parts`, { parts: [{ n: s.partCount, size: last, sha256: anySha }] });
    expect(urls.parts[0].upload.url).toContain("partNumber=" + s.partCount);
    await expectStatus(alice.req("POST", `/v1/attachments/uploads/${s.uploadId}/parts`, { parts: [{ n: s.partCount, size: last + 1, sha256: anySha }] }), 400);
    // Cancelling removes it from the store.
    await alice.req("DELETE", `/v1/attachments/uploads/${s.uploadId}`);
    await expectStatus(alice.req("GET", `/v1/attachments/uploads/${s.uploadId}/parts`), 404);
    const tooBig = await expectStatus(alice.req("POST", "/v1/attachments/uploads", { conversationId: dm, size: GiB + MiB + 1, sha256: anySha }), 413);
    expect(tooBig.error.code).toBe("too_large");
  });

  it("only lets the uploader continue an upload, and only members start one", async () => {
    const s = await alice.req("POST", "/v1/attachments/uploads", { conversationId: dm, size: 200 * MiB, sha256: anySha });
    await expectStatus(eve.req("GET", `/v1/attachments/uploads/${s.uploadId}/parts`), 404);
    await expectStatus(bob.req("POST", `/v1/attachments/uploads/${s.uploadId}/complete`, {}), 404);
    await expectStatus(eve.req("POST", "/v1/attachments/uploads", { conversationId: dm, size: MiB, sha256: anySha }), 404);
    await alice.req("DELETE", `/v1/attachments/uploads/${s.uploadId}`);
  });
});
