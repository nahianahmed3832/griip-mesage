/**
 * End-to-end check of the 1 GiB file limit (docs/COMMUNITY_REBUILD_2026-10-21.md §8): real bytes, not just limits.
 * Not part of `npm test` (it moves several gigabytes); run it by hand on a machine with ~4 GB free disk and memory:
 *
 *   npx tsx test/acceptance/files_1g_e2e.ts [work dir]
 *
 * In a community channel, with the server's default limits:
 * 1. streamed through the server to its disk: the encrypted size of a 1 GiB file uploads, a member downloads it
 *    byte-identical (SHA-256), an outsider gets 404, one byte over the limit is refused;
 * 2. direct to S3 in parts (the in-repo SigV4-checking S3 stand-in): presigned part uploads, completion, a member's
 *    download through the presigned redirect, byte-identical;
 * 3. a member a channel permission denies file sharing can't start an upload; a cancelled upload is aborted.
 * Prints timings. Random bytes stand in for ciphertext (the server never sees plaintext anyway).
 */
import crypto from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { buildApp } from "../../src/app/server.js";
import { loadConfig } from "../../src/app/config.js";
import { startFakeS3 } from "../support/fakeS3.js";
import { GriipClient } from "../support/griipClient.js";

const MiB = 1024 * 1024;
const GiB = 1024 * MiB;
/** Ciphertext of a 1 GiB file in the app's chunked format: 16 bytes of tag per 1 MiB chunk. */
const ENCRYPTED_1G = GiB + 16 * 1024;
const work = process.argv[2] ?? fs.mkdtempSync(path.join(os.tmpdir(), "meldiq-1g-"));
fs.mkdirSync(work, { recursive: true });

const results: { check: string; ok: boolean; detail: string }[] = [];
const check = (name: string, ok: boolean, detail = "") => {
  results.push({ check: name, ok, detail });
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? `  (${detail})` : ""}`);
};
const seconds = (t0: number) => ((Date.now() - t0) / 1000).toFixed(1) + " s";

/** A random file of [size] bytes on disk, and its SHA-256 (base64). */
async function randomFile(file: string, size: number): Promise<string> {
  const hash = crypto.createHash("sha256");
  const out = fs.createWriteStream(file);
  const chunk = 8 * MiB;
  for (let done = 0; done < size; done += chunk) {
    const b = crypto.randomBytes(Math.min(chunk, size - done));
    hash.update(b);
    if (!out.write(b)) await new Promise<void>((r) => out.once("drain", () => r()));
  }
  await new Promise<void>((r) => out.end(r));
  return hash.digest("base64");
}

/** Streams [file] as a request body (never fully in memory). */
function streamUpload(url: string, token: string, file: string): Promise<{ status: number; body: string }> {
  return new Promise((resolve, reject) => {
    const size = fs.statSync(file).size;
    const req = http.request(url, { method: "POST", headers: { authorization: `Bearer ${token}`, "content-type": "application/octet-stream", "content-length": size } }, (res) => {
      let body = "";
      res.on("data", (d) => (body += d));
      res.on("end", () => resolve({ status: res.statusCode ?? 0, body }));
    });
    req.on("error", reject);
    fs.createReadStream(file).pipe(req);
  });
}

/** Downloads [url] (following one redirect) and returns status, size and SHA-256 without buffering the body. */
function streamDownload(url: string, token?: string): Promise<{ status: number; size: number; sha256: string }> {
  return new Promise((resolve, reject) => {
    http.get(url, { headers: token ? { authorization: `Bearer ${token}` } : {} }, (res) => {
      if (res.statusCode && res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.resume();
        streamDownload(res.headers.location).then(resolve, reject);
        return;
      }
      const hash = crypto.createHash("sha256");
      let size = 0;
      res.on("data", (d: Buffer) => {
        hash.update(d);
        size += d.length;
      });
      res.on("end", () => resolve({ status: res.statusCode ?? 0, size, sha256: hash.digest("base64") }));
    }).on("error", reject);
  });
}

/** Sends a presigned part exactly as the app does: the URL, the signed headers and the body. */
function put(up: { url: string; headers: Record<string, string> }, body: Buffer): Promise<{ status: number; etag?: string }> {
  return new Promise((resolve, reject) => {
    const req = http.request(up.url, { method: "PUT", headers: up.headers }, (res) => {
      res.resume();
      res.on("end", () => resolve({ status: res.statusCode ?? 0, etag: res.headers.etag as string | undefined }));
    });
    req.on("error", reject);
    req.end(body);
  });
}

async function server(env: Record<string, string>) {
  const dir = fs.mkdtempSync(path.join(work, "server-"));
  const config = loadConfig({
    DATA_DIR: dir,
    LOG_LEVEL: "silent",
    LIVEKIT_API_KEY: "devkey",
    LIVEKIT_API_SECRET: "e2e-secret-that-is-at-least-32-characters-long",
    LIVEKIT_PUBLIC_URL: "ws://127.0.0.1:1",
    REGISTRATION_MODE: "open",
    RATE_LIMIT_PER_MINUTE: "100000",
    REGISTRATION_PER_ADDRESS_PER_HOUR: "0",
    REGISTRATION_PER_ADDRESS_PER_DAY: "0",
    REGISTRATION_OPEN_PER_DAY: "0",
    NEW_ACCOUNT_LIMIT_DAYS: "0",
    ...env,
  } as NodeJS.ProcessEnv);
  const built = await buildApp(config, { logger: false });
  await built.app.listen({ host: "127.0.0.1", port: 0 });
  const addr = built.app.server.address();
  const baseUrl = `http://127.0.0.1:${typeof addr === "object" && addr ? addr.port : 0}`;
  let n = 0;
  const client = async (name: string) => {
    const c = new GriipClient(baseUrl, `${name}${++n}`, name);
    await c.register();
    return c;
  };
  return { built, baseUrl, client, dir, close: async () => { await built.app.close(); built.ctx.db.close(); } };
}

async function community(owner: GriipClient, member: GriipClient) {
  const org = (await owner.req("POST", "/v1/orgs", { name: `Big Files ${crypto.randomUUID().slice(0, 6)}` })).org.id;
  const code = (await owner.req("POST", `/v1/orgs/${org}/invites`, { role: "MEMBER" })).invite.code;
  await member.req("POST", "/v1/invites/accept", { code });
  const channel = (await owner.req("POST", `/v1/orgs/${org}/channels`, { name: "media" })).channel.id;
  return { org, channel };
}

async function main() {
  console.log(`work dir: ${work}`);
  const file = path.join(work, "one-gib.bin");
  let t0 = Date.now();
  const sha = await randomFile(file, ENCRYPTED_1G);
  console.log(`made ${ENCRYPTED_1G} random bytes in ${seconds(t0)}`);

  // ── 1. Through the server, to its disk ──
  if (!process.env.ONLY_S3) {
    const s = await server({});
    const [owner, member, outsider] = [await s.client("owner"), await s.client("member"), await s.client("outsider")];
    const { channel } = await community(owner, member);
    const info = await owner.req("GET", "/v1/server-info");
    check("server advertises a 1 GiB file limit", info.fileMaxBytes === GiB, `fileMaxBytes=${info.fileMaxBytes}, attachmentMaxBytes=${info.attachmentMaxBytes}`);
    t0 = Date.now();
    const up = await streamUpload(`${s.baseUrl}/v1/attachments?conversationId=${channel}`, owner.token, file);
    const id = up.status === 200 ? (JSON.parse(up.body) as { id: string }).id : null;
    check("streams the encrypted size of a 1 GiB file through the server", up.status === 200 && !!id, `HTTP ${up.status} in ${seconds(t0)}`);
    if (id) {
      t0 = Date.now();
      const down = await streamDownload(`${s.baseUrl}/v1/attachments/${id}`, member.token);
      check("a member downloads it byte-identical", down.status === 200 && down.size === ENCRYPTED_1G && down.sha256 === sha, `HTTP ${down.status}, ${down.size} bytes in ${seconds(t0)}`);
      const denied = await streamDownload(`${s.baseUrl}/v1/attachments/${id}`, outsider.token);
      check("an outsider can't download it", denied.status === 404, `HTTP ${denied.status}`);
      const anonymous = await streamDownload(`${s.baseUrl}/v1/attachments/${id}`);
      check("nobody signed out can download it", anonymous.status === 401, `HTTP ${anonymous.status}`);
    }
    const over = path.join(work, "over.bin");
    fs.copyFileSync(file, over);
    fs.appendFileSync(over, crypto.randomBytes(MiB + 1));
    console.log("trying one byte over…");
    const tooBig = await streamUpload(`${s.baseUrl}/v1/attachments?conversationId=${channel}`, owner.token, over).catch((e: Error) => ({ status: -1, body: e.message }));
    check("refuses a file over the limit", tooBig.status === 413 || tooBig.status === -1, `HTTP ${tooBig.status}`);
    fs.rmSync(over);
    console.log("closing the disk server…");
    await Promise.race([s.close(), new Promise((r) => setTimeout(r, 10_000))]);
    s.built.app.server.closeAllConnections();
  }

  // ── 2. Direct to S3 in parts ──
  {
    const creds = { accessKeyId: "AKIDE2E1G", secretAccessKey: "e2e-1g-secret" };
    const s3 = await startFakeS3("meldiq-e2e", "ap-southeast-1", creds);
    const s = await server({
      STORAGE_BACKEND: "s3",
      S3_BUCKET: "meldiq-e2e",
      S3_REGION: "ap-southeast-1",
      S3_ENDPOINT: s3.url,
      S3_FORCE_PATH_STYLE: "true",
      S3_ACCESS_KEY_ID: creds.accessKeyId,
      S3_SECRET_ACCESS_KEY: creds.secretAccessKey,
    });
    const [owner, member, muted] = [await s.client("s3owner"), await s.client("s3member"), await s.client("s3muted")];
    const { org, channel } = await community(owner, member);
    const fd = fs.openSync(file, "r");
    t0 = Date.now();
    console.log("starting the direct upload…");
    const session = await owner.req("POST", "/v1/attachments/uploads", { conversationId: channel, size: ENCRYPTED_1G, sha256: sha });
    check("starts a multipart upload for the 1 GiB file", session.mode === "multipart", `${session.partCount} parts of ${session.partSize / MiB} MiB`);
    const parts: { n: number; size: number; sha256: string }[] = [];
    for (let n = 1; n <= session.partCount; n++) {
      const size = Math.min(session.partSize, ENCRYPTED_1G - (n - 1) * session.partSize);
      const b = Buffer.alloc(size);
      fs.readSync(fd, b, 0, size, (n - 1) * session.partSize);
      parts.push({ n, size, sha256: crypto.createHash("sha256").update(b).digest("base64") });
    }
    console.log("asking for part URLs…");
    const urls = (await owner.req("POST", `/v1/attachments/uploads/${session.uploadId}/parts`, { parts })).parts as { n: number; upload: { url: string; headers: Record<string, string> } }[];
    let allOk = true;
    for (const u of urls) {
      if (u.n % 8 === 1) console.log(`part ${u.n}/${urls.length}…`);
      const p = parts[u.n - 1];
      const b = Buffer.alloc(p.size);
      fs.readSync(fd, b, 0, p.size, (u.n - 1) * session.partSize);
      const r = await put(u.upload, b);
      if (r.status !== 200) allOk = false;
    }
    fs.closeSync(fd);
    check("every part uploads with its presigned URL", allOk);
    const have = (await owner.req("GET", `/v1/attachments/uploads/${session.uploadId}/parts`)).parts as { n: number; etag: string; sha256: string }[];
    const done = await owner.req("POST", `/v1/attachments/uploads/${session.uploadId}/complete`, { parts: have.map((p) => ({ n: p.n, etag: p.etag, sha256: p.sha256 })) });
    check("the multipart upload completes", done.size === ENCRYPTED_1G, `${done.size} bytes in ${seconds(t0)}`);
    t0 = Date.now();
    const down = await streamDownload(`${s.baseUrl}/v1/attachments/${done.id}`, member.token);
    check("a member downloads it from S3 byte-identical", down.status === 200 && down.sha256 === sha, `HTTP ${down.status}, ${down.size} bytes in ${seconds(t0)}`);

    // A channel that doesn't let @everyone share files: no upload starts; a cancelled upload is aborted.
    const code = (await owner.req("POST", `/v1/orgs/${org}/invites`, { role: "MEMBER" })).invite.code;
    await muted.req("POST", "/v1/invites/accept", { code });
    await owner.req("PUT", `/v1/conversations/${channel}/overwrites`, { subject: "everyone", deny: ["messages.attach"] });
    const refused = await muted.req("POST", "/v1/attachments/uploads", { conversationId: channel, size: 1000, sha256: sha }).catch((e: { status: number }) => e);
    check("a channel permission can stop file sharing", refused.status === 403, `HTTP ${refused.status}`);
    const cancel = await owner.req("POST", "/v1/attachments/uploads", { conversationId: channel, size: 200 * MiB, sha256: sha });
    await owner.req("DELETE", `/v1/attachments/uploads/${cancel.uploadId}`);
    check("a cancelled upload is aborted at the store", ![...s3.uploads.values()].some(() => true), `${s3.uploads.size} open multipart uploads`);
    await s.close();
    await s3.close();
  }

  fs.rmSync(file);
  const failed = results.filter((r) => !r.ok);
  console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
  process.exitCode = failed.length ? 1 : 0;
}

main().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
