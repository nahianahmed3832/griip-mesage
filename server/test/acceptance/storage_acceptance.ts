/**
 * Acceptance checks of a deployed server's S3 file storage (docs/AWS_STORAGE_PLAN.md), with throwaway accounts and
 * random ciphertext only. Two phases, with a restart of the API between them:
 *
 *   npx tsx test/acceptance/storage_acceptance.ts https://msg.example.com prepare /private/path/state.json
 *   (restart the API)
 *   npx tsx test/acceptance/storage_acceptance.ts https://msg.example.com verify /private/path/state.json
 *
 * The server must offer direct uploads and allow registration (or set ORBIT_ACCEPTANCE_INVITE). The state file holds
 * the throwaway accounts' session tokens and the test files' keys: keep it outside the repository and delete it
 * afterwards. Presigned URLs are never printed. Prints the ids of what it stored, for checking the bucket.
 */
import crypto from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import https from "node:https";
import { GriipClient } from "../support/griipClient.js";

const [baseUrl, phase, stateFile] = process.argv.slice(2);
if (!baseUrl || !["prepare", "verify"].includes(phase ?? "") || !stateFile) {
  console.error("usage: storage_acceptance.ts <server url> prepare|verify <state file>");
  process.exit(2);
}
const KiB = 1024;
const MiB = 1024 * KiB;
const LINK_TTL_S = 300;

interface StoredFile { kind: string; id: string; key: string; nonce: string; sha256: string; size: number }
interface State {
  baseUrl: string;
  run: string;
  accounts: Record<"alice" | "bob" | "eve", { username: string; userId: string; token: string }>;
  files: StoredFile[];
  photoSha256: string;
}

const results: { check: string; ok: boolean; detail: string }[] = [];
function check(name: string, ok: boolean, detail = ""): void {
  results.push({ check: name, ok, detail });
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? ` (${detail})` : ""}`);
}
async function attempt(name: string, fn: () => Promise<string | void>): Promise<void> {
  try {
    check(name, true, (await fn()) ?? "");
  } catch (e) {
    check(name, false, (e as Error).message.replace(/https?:\/\/\S+/g, "<url>"));
  }
}
function expect(cond: unknown, what: string): void {
  if (!cond) throw new Error(what);
}

const sha = (b: Buffer, enc: "hex" | "base64" = "hex") => crypto.createHash("sha256").update(b).digest(enc);
function encrypt(plain: Buffer) {
  const key = crypto.randomBytes(32);
  const nonce = crypto.randomBytes(12);
  const c = crypto.createCipheriv("aes-256-gcm", key, nonce);
  return { key, nonce, ct: Buffer.concat([c.update(plain), c.final(), c.getAuthTag()]) };
}
function decrypt(ct: Buffer, key: Buffer, nonce: Buffer): Buffer {
  const d = crypto.createDecipheriv("aes-256-gcm", key, nonce);
  d.setAuthTag(ct.subarray(ct.length - 16));
  return Buffer.concat([d.update(ct.subarray(0, ct.length - 16)), d.final()]);
}

/** A raw request with exactly these headers (presigned requests must not gain any). */
function send(method: string, url: string, headers: Record<string, string>, body?: Buffer): Promise<{ status: number; text: string; headers: http.IncomingHttpHeaders; body: Buffer }> {
  const u = new URL(url);
  const lib = u.protocol === "https:" ? https : http;
  return new Promise((resolve, reject) => {
    const req = lib.request({ method, hostname: u.hostname, port: u.port || undefined, path: u.pathname + u.search, headers }, (res) => {
      const chunks: Buffer[] = [];
      res.on("data", (c) => chunks.push(c));
      res.on("end", () => {
        const b = Buffer.concat(chunks);
        resolve({ status: res.statusCode ?? 0, text: b.subarray(0, 2000).toString(), headers: res.headers, body: b });
      });
    });
    req.on("error", reject);
    if (body) req.write(body);
    req.end();
  });
}
/** Sends part of the body, then drops the connection, as a phone losing its network would. */
function sendAndDrop(url: string, headers: Record<string, string>, body: Buffer, bytes: number): Promise<string> {
  const u = new URL(url);
  return new Promise((resolve) => {
    const req = https.request({ method: "PUT", hostname: u.hostname, path: u.pathname + u.search, headers }, (res) => {
      res.resume();
      res.on("end", () => resolve(`answered ${res.statusCode}`));
    });
    req.on("error", () => resolve("connection dropped"));
    req.write(body.subarray(0, bytes));
    setTimeout(() => req.destroy(new Error("simulated network loss")), 1500);
  });
}

type Upload = { method: string; url: string; headers: Record<string, string> };
const put = (up: Upload, body: Buffer, headers = up.headers) => send("PUT", up.url, headers, body);

async function login(username: string, token: string, userId: string): Promise<GriipClient> {
  const c = new GriipClient(baseUrl, username);
  c.token = token;
  c.userId = userId;
  return c;
}

/** GET as [token]: the server's redirect, then the bytes from the link (no Orbit token sent there). */
async function download(token: string, id: string, range?: string) {
  const r = await send("GET", `${baseUrl}/v1/attachments/${id}`, { authorization: `Bearer ${token}` });
  if (r.status !== 302) return { status: r.status, link: null as string | null, body: r.body };
  const link = String(r.headers.location);
  const got = await send("GET", link, range ? { range } : {});
  return { status: got.status, link, body: got.body };
}

async function directUpload(c: GriipClient, conversationId: string, ct: Buffer): Promise<{ id: string; upload: Upload }> {
  const s = await c.req("POST", "/v1/attachments/uploads", { conversationId, size: ct.length, sha256: sha(ct, "base64") });
  expect(s.mode === "single", `expected a single upload, got ${s.mode}`);
  const r = await put(s.upload, ct);
  expect(r.status === 200, `S3 answered ${r.status}`);
  expect(r.headers["x-amz-server-side-encryption"] === "AES256", "no SSE-S3 on the stored object");
  const done = await c.req("POST", `/v1/attachments/uploads/${s.uploadId}/complete`, {});
  expect(done.size === ct.length, "recorded size differs");
  return { id: done.id, upload: s.upload };
}

async function prepare(): Promise<void> {
  const info = await new GriipClient(baseUrl, "x").req("GET", "/v1/server-info");
  const maxBytes: number = info.attachmentMaxBytes;
  check("server offers direct uploads", (info.features ?? []).includes("direct-upload"), `limit ${maxBytes} bytes`);
  const run = crypto.randomBytes(3).toString("hex");
  const invite = process.env.ORBIT_ACCEPTANCE_INVITE;
  const mk = async (n: string) => new GriipClient(baseUrl, `s3chk${run}${n}`, `S3 check ${n.toUpperCase()}`).register(invite);
  const alice = await mk("a");
  const bob = await mk("b");
  const eve = await mk("e");
  const dm = (await alice.req("POST", "/v1/conversations/dm", { userId: bob.userId })).conversation.id;
  console.log(`accounts: ${alice.username}, ${bob.username}, ${eve.username}`);
  const files: StoredFile[] = [];
  // The first download link, kept to check that it expires (assigned inside the checks below).
  const firstLink: { link: string; at: number } = { link: "", at: 0 };

  // Photo, voice message, document and a video close to the server's limit, each sent as the app does.
  const kinds: [string, number][] = [["photo", 400 * KiB], ["voice", 30 * KiB], ["file", MiB], ["video", maxBytes - MiB]];
  for (const [kind, plainSize] of kinds) {
    await attempt(`${kind}: direct upload to S3, encrypted at rest, recorded by the server`, async () => {
      const { key, nonce, ct } = encrypt(crypto.randomBytes(plainSize));
      const { id, upload } = await directUpload(alice, dm, ct);
      expect(new URL(upload.url).hostname.endsWith(".amazonaws.com"), "upload doesn't go to S3");
      expect(!("authorization" in upload.headers), "presigned upload carries an authorization header");
      files.push({ kind, id, key: key.toString("base64"), nonce: nonce.toString("base64"), sha256: sha(ct), size: ct.length });
      return `${ct.length} bytes, ${id}`;
    });
  }
  const photo = files.find((f) => f.kind === "photo");

  await attempt("message with the attachments reaches the recipient, who downloads and decrypts every file", async () => {
    await bob.connect();
    const sent = await alice.send(dm, { type: "text", text: "S3 acceptance (synthetic)", attachments: files.map((f) => ({ id: f.id, key: f.key, nonce: f.nonce, sha256: f.sha256 })) });
    const msg = await bob.waitFor(() => bob.received.find((m) => m.messageId === sent.messageId) ?? false, 20_000);
    const payload = msg.payload as { attachments: { id: string; key: string; nonce: string; sha256: string }[] };
    expect(payload.attachments.length === files.length, "attachments missing from the message");
    for (const a of payload.attachments) {
      const d = await download(bob.token, a.id);
      expect(d.status === 200 && d.link, `download answered ${d.status}`);
      if (!firstLink.link) Object.assign(firstLink, { link: d.link!, at: Date.now() });
      expect(new URL(d.link!).searchParams.get("X-Amz-Expires") === String(LINK_TTL_S), "download link isn't short-lived");
      expect(sha(d.body) === a.sha256, `${a.id}: bytes differ`);
      decrypt(d.body, Buffer.from(a.key, "base64"), Buffer.from(a.nonce, "base64"));
    }
    await bob.disconnect();
    return `${payload.attachments.length} files`;
  });

  await attempt("a range of a file (resumed download) comes from S3 too", async () => {
    const d = await download(bob.token, photo!.id, "bytes=0-99");
    expect(d.status === 206 && d.body.length === 100, `answered ${d.status}, ${d.body.length} bytes`);
  });

  await attempt("someone outside the conversation gets 404 and no link", async () => {
    const d = await download(eve.token, photo!.id);
    expect(d.status === 404 && !d.link, `answered ${d.status}`);
    const s = await eve.req("POST", "/v1/attachments/uploads", { conversationId: dm, size: 10, sha256: sha(Buffer.alloc(10), "base64") }).catch((e) => e);
    expect(s.status === 403 || s.status === 404, `outsider could start an upload (${s.status})`);
  });

  await attempt("S3 refuses anonymous, plain-HTTP, edited and tampered requests", async () => {
    const link = new URL(firstLink.link);
    const bare = `${link.origin}${link.pathname}`;
    const codes = [
      (await send("GET", bare, {})).status,
      (await send("GET", bare.replace("https:", "http:"), {})).status,
      (await send("GET", firstLink.link.replace(photo!.id, files[1].id), {})).status,
      (await send("GET", firstLink.link.replace(/X-Amz-Signature=([0-9a-f])/, (_m, c) => `X-Amz-Signature=${c === "0" ? "1" : "0"}`), {})).status,
    ];
    expect(codes.every((c) => c === 403), `answers ${codes.join(", ")}`);
    return codes.join(", ");
  });

  await attempt("S3 refuses other bytes, another size, no overwrite condition, and an overwrite", async () => {
    const body = crypto.randomBytes(20 * KiB);
    const s = await alice.req("POST", "/v1/attachments/uploads", { conversationId: dm, size: body.length, sha256: sha(body, "base64") });
    const other = await put(s.upload, crypto.randomBytes(20 * KiB));
    expect(/BadDigest|XAmzContentChecksumMismatch|InvalidDigest/.test(other.text), `other bytes: ${other.status}`);
    const short = await put(s.upload, body.subarray(0, 10 * KiB), { ...s.upload.headers, "content-length": String(10 * KiB) });
    expect(short.status === 403, `another size: ${short.status}`);
    const { "if-none-match": _n, ...noCondition } = s.upload.headers;
    expect((await put(s.upload, body, noCondition)).status === 403, "accepted without the no-overwrite condition");
    expect((await put(s.upload, body)).status === 200, "the real upload failed");
    const done = await alice.req("POST", `/v1/attachments/uploads/${s.uploadId}/complete`, {});
    expect((await put(s.upload, body)).status === 412, "an overwrite was accepted");
    files.push({ kind: "refusals", id: done.id, key: "", nonce: "", sha256: sha(body), size: body.length });
  });

  await attempt("an upload cut off mid-way is retried and completes intact", async () => {
    const { key, nonce, ct } = encrypt(crypto.randomBytes(2 * MiB));
    const s = await alice.req("POST", "/v1/attachments/uploads", { conversationId: dm, size: ct.length, sha256: sha(ct, "base64") });
    const first = await sendAndDrop(s.upload.url, s.upload.headers, ct, 256 * KiB);
    const early = await alice.req("POST", `/v1/attachments/uploads/${s.uploadId}/complete`, {}).catch((e) => e);
    expect(early.status >= 400, "completed before the bytes arrived");
    expect((await put(s.upload, ct)).status === 200, "retry failed");
    const done = await alice.req("POST", `/v1/attachments/uploads/${s.uploadId}/complete`, {});
    files.push({ kind: "retried", id: done.id, key: key.toString("base64"), nonce: nonce.toString("base64"), sha256: sha(ct), size: ct.length });
    const d = await download(bob.token, done.id);
    expect(sha(d.body) === sha(ct), "bytes differ after the retry");
    return `first attempt: ${first}; completion before the retry: ${early.status}`;
  });

  await attempt("a cancelled upload can't be completed", async () => {
    const body = crypto.randomBytes(5 * KiB);
    const s = await alice.req("POST", "/v1/attachments/uploads", { conversationId: dm, size: body.length, sha256: sha(body, "base64") });
    await alice.req("DELETE", `/v1/attachments/uploads/${s.uploadId}`);
    const r = await alice.req("POST", `/v1/attachments/uploads/${s.uploadId}/complete`, {}).catch((e) => e);
    expect(r.status === 404 || r.status === 409, `completion answered ${r.status ?? "ok"}`);
  });

  await attempt("files over the server's limit are refused before any upload", async () => {
    const r = await alice.req("POST", "/v1/attachments/uploads", { conversationId: dm, size: maxBytes + 1, sha256: sha(Buffer.alloc(1), "base64") }).catch((e) => e);
    expect(r.status === 413, `answered ${r.status ?? "ok"}`);
  });

  await attempt("older apps' upload through the server lands in S3", async () => {
    const blob = crypto.randomBytes(50 * KiB);
    const up = await alice.req("POST", `/v1/attachments?conversationId=${dm}`, undefined, blob);
    const d = await download(bob.token, up.id);
    expect(d.status === 200 && d.link && new URL(d.link).hostname.endsWith(".amazonaws.com"), `answered ${d.status}`);
    expect(sha(d.body) === sha(blob), "bytes differ");
    files.push({ kind: "legacy", id: up.id, key: "", nonce: "", sha256: sha(blob), size: blob.length });
  });

  const jpeg = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), crypto.randomBytes(8 * KiB), Buffer.from([0xff, 0xd9])]);
  await attempt("profile photo is stored and served through the server", async () => {
    const r = await alice.req("PUT", "/v1/users/me/photo", undefined, jpeg);
    const got = await bob.req("GET", `/v1/users/${alice.userId}/photo`);
    expect(sha(Buffer.from(got)) === sha(jpeg), "photo differs");
    return `profile-photos/${alice.userId}-${r.photoRev}`;
  });

  // Download links stop working after their 5 minutes.
  if (firstLink.link) {
    const wait = firstLink.at + (LINK_TTL_S + 5) * 1000 - Date.now();
    if (wait > 0) console.log(`waiting ${Math.ceil(wait / 1000)} s for the first download link to expire…`);
    await new Promise((r) => setTimeout(r, Math.max(wait, 0)));
    await attempt("an expired download link is refused", async () => {
      const r = await send("GET", firstLink.link, {});
      expect(r.status === 403, `answered ${r.status}`);
    });
  }

  const state: State = {
    baseUrl,
    run,
    accounts: {
      alice: { username: alice.username, userId: alice.userId, token: alice.token },
      bob: { username: bob.username, userId: bob.userId, token: bob.token },
      eve: { username: eve.username, userId: eve.userId, token: eve.token },
    },
    files,
    photoSha256: sha(jpeg),
  };
  fs.writeFileSync(stateFile, JSON.stringify(state), { mode: 0o600 });
  console.log(`stored attachments: ${files.map((f) => f.id).join(" ")}`);
}

async function verify(): Promise<void> {
  const state = JSON.parse(fs.readFileSync(stateFile, "utf8")) as State;
  const bob = await login(state.accounts.bob.username, state.accounts.bob.token, state.accounts.bob.userId);
  const info = await bob.req("GET", "/v1/server-info");
  check("after the restart, the server still offers direct uploads", (info.features ?? []).includes("direct-upload"));
  for (const f of state.files) {
    await attempt(`after the restart: ${f.kind} ${f.id} downloads intact`, async () => {
      const d = await download(bob.token, f.id);
      expect(d.status === 200, `answered ${d.status}`);
      expect(sha(d.body) === f.sha256, "bytes differ");
      if (f.key) decrypt(d.body, Buffer.from(f.key, "base64"), Buffer.from(f.nonce, "base64"));
    });
  }
  await attempt("after the restart: profile photo", async () => {
    const got = await bob.req("GET", `/v1/users/${state.accounts.alice.userId}/photo`);
    expect(sha(Buffer.from(got)) === state.photoSha256, "photo differs");
  });
  await attempt("after the restart: outsiders still get 404", async () => {
    const d = await download(state.accounts.eve.token, state.files[0].id);
    expect(d.status === 404, `answered ${d.status}`);
  });
}

try {
  await (phase === "prepare" ? prepare() : verify());
} catch (e) {
  check(`${phase} stopped`, false, (e as Error).message.replace(/https?:\/\/\S+/g, "<url>"));
}
const failed = results.filter((r) => !r.ok).length;
console.log(`\n${results.length - failed}/${results.length} checks passed`);
process.exit(failed ? 1 : 0);
