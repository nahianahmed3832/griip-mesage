import crypto from "node:crypto";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { parseAmzDate, signatureFor, UNSIGNED_PAYLOAD } from "./sigv4.js";

/**
 * An in-process stand-in for Amazon S3 (path-style URLs) for tests, speaking what the AWS SDK and presigned URLs send:
 * objects with Range, HEAD (with stored SHA-256 checksums), DELETE, conditional writes (If-None-Match: *), multipart
 * uploads with per-part SHA-256, ListParts and ListObjectsV2. Every request must carry a valid SigV4 signature,
 * header-signed or presigned, recomputed independently of the SDK from what was actually received; checksums and a
 * signed payload hash must match the body. Nothing here talks to AWS.
 */
export interface FakeObject {
  body: Buffer;
  sse: string | undefined;
  sha256: string | undefined;
  modifiedAt: number;
}

export interface FakeS3 {
  url: string;
  objects: Map<string, FakeObject>;
  /** Multipart uploads not completed or aborted yet. */
  uploads: Map<string, { key: string; parts: Map<number, unknown> }>;
  requests: { method: string; path: string; query: string; presigned: boolean }[];
  /** Make the next n requests fail with 500 (retry tests). */
  failNext: (n: number) => void;
  /** Cut the connection of the next n requests that have a body after receiving part of it (interrupted uploads). */
  dropNext: (n: number) => void;
  close: () => Promise<void>;
}

const NS = 'xmlns="http://s3.amazonaws.com/doc/2006-03-01/"';
const xml = (s: string) => `<?xml version="1.0" encoding="UTF-8"?>${s}`;
const b64sha = (b: Buffer) => crypto.createHash("sha256").update(b).digest("base64");

export async function startFakeS3(bucket: string, region: string, credentials: { accessKeyId: string; secretAccessKey: string }): Promise<FakeS3> {
  const objects = new Map<string, FakeObject>();
  const uploads = new Map<string, { key: string; sse: string | undefined; checksum: string | undefined; parts: Map<number, { etag: string; body: Buffer; sha256?: string }> }>();
  const requests: FakeS3["requests"] = [];
  let failures = 0;
  let drops = 0;

  const server = http.createServer(async (req, res) => {
    const fail = (status: number, code: string) => {
      res.writeHead(status, { "content-type": "application/xml" });
      res.end(status === 304 || req.method === "HEAD" ? undefined : xml(`<Error><Code>${code}</Code><Message>${code}</Message></Error>`));
    };
    const hasBody = req.method === "PUT" || req.method === "POST";
    if (hasBody && drops > 0 && Number(req.headers["content-length"] ?? 0) > 1024) {
      drops--;
      req.once("data", () => req.socket.destroy());
      return;
    }
    const chunks: Buffer[] = [];
    for await (const c of req) chunks.push(c as Buffer);
    const body = Buffer.concat(chunks);
    const url = new URL(req.url ?? "/", `http://${req.headers.host}`);
    const presigned = url.searchParams.has("X-Amz-Signature");
    requests.push({ method: req.method ?? "", path: url.pathname, query: url.search, presigned });
    if (failures > 0) {
      failures--;
      return fail(500, "InternalError");
    }
    // ── Authentication: recompute the signature from the request as received ──
    const query = [...url.searchParams.entries()].filter(([k]) => k !== "X-Amz-Signature");
    let signedNames: string[];
    let date: Date;
    let given: string;
    let payloadHash: string;
    if (presigned) {
      const cred = url.searchParams.get("X-Amz-Credential") ?? "";
      if (!cred.startsWith(`${credentials.accessKeyId}/`)) return fail(403, "InvalidAccessKeyId");
      date = parseAmzDate(url.searchParams.get("X-Amz-Date") ?? "");
      if (Date.now() > date.getTime() + Number(url.searchParams.get("X-Amz-Expires")) * 1000) return fail(403, "AccessDenied");
      signedNames = (url.searchParams.get("X-Amz-SignedHeaders") ?? "").split(";");
      given = url.searchParams.get("X-Amz-Signature") ?? "";
      payloadHash = UNSIGNED_PAYLOAD;
    } else {
      const m = /Credential=([^/]+)\/[^,]+, ?SignedHeaders=([^,]+), ?Signature=([0-9a-f]+)/.exec(String(req.headers.authorization ?? ""));
      if (!m || m[1] !== credentials.accessKeyId) return fail(403, "InvalidAccessKeyId");
      signedNames = m[2].split(";");
      given = m[3];
      date = parseAmzDate(String(req.headers["x-amz-date"] ?? ""));
      payloadHash = String(req.headers["x-amz-content-sha256"] ?? "");
    }
    const headers: Record<string, string> = {};
    for (const h of signedNames) {
      if (req.headers[h] === undefined) return fail(403, "SignatureDoesNotMatch"); // signed but not sent
      headers[h] = String(req.headers[h]);
    }
    const expected = signatureFor({ method: req.method ?? "", path: url.pathname, query, headers, payloadHash, date, region, secretAccessKey: credentials.secretAccessKey });
    if (expected !== given) return fail(403, "SignatureDoesNotMatch");
    if (payloadHash !== UNSIGNED_PAYLOAD && payloadHash !== crypto.createHash("sha256").update(body).digest("hex")) return fail(400, "XAmzContentSHA256Mismatch");
    const checksum = req.headers["x-amz-checksum-sha256"] as string | undefined;
    if (checksum !== undefined && checksum !== b64sha(body)) return fail(400, "BadDigest");
    if (req.headers["content-length"] !== undefined && Number(req.headers["content-length"]) !== body.length) return fail(400, "IncompleteBody");

    // ── Routing (path-style: /bucket[/key]) ──
    if (url.pathname === `/${bucket}` || url.pathname === `/${bucket}/`) {
      if (req.method === "GET" && url.searchParams.get("list-type") === "2") {
        const prefix = url.searchParams.get("prefix") ?? "";
        const items = [...objects.entries()].filter(([k]) => k.startsWith(prefix)).sort(([a], [b]) => (a < b ? -1 : 1));
        res.writeHead(200, { "content-type": "application/xml" });
        return res.end(
          xml(
            `<ListBucketResult ${NS}><Name>${bucket}</Name><Prefix>${prefix}</Prefix><KeyCount>${items.length}</KeyCount><IsTruncated>false</IsTruncated>` +
              items.map(([k, o]) => `<Contents><Key>${k}</Key><Size>${o.body.length}</Size><LastModified>${new Date(o.modifiedAt).toISOString()}</LastModified></Contents>`).join("") +
              "</ListBucketResult>",
          ),
        );
      }
      return fail(400, "InvalidRequest");
    }
    const prefix = `/${bucket}/`;
    if (!url.pathname.startsWith(prefix)) return fail(404, "NoSuchBucket");
    const key = decodeURIComponent(url.pathname.slice(prefix.length));
    const sse = req.headers["x-amz-server-side-encryption"] as string | undefined;
    const uploadId = url.searchParams.get("uploadId");
    const exists = objects.has(key);
    const noOverwrite = req.headers["if-none-match"] === "*";

    if (req.method === "POST" && url.searchParams.has("uploads")) {
      const id = crypto.randomUUID();
      uploads.set(id, { key, sse, checksum: req.headers["x-amz-checksum-algorithm"] as string | undefined, parts: new Map() });
      res.writeHead(200, { "content-type": "application/xml" });
      return res.end(xml(`<InitiateMultipartUploadResult ${NS}><Bucket>${bucket}</Bucket><Key>${key}</Key><UploadId>${id}</UploadId></InitiateMultipartUploadResult>`));
    }
    if (uploadId) {
      const u = uploads.get(uploadId);
      if (!u || u.key !== key) return fail(404, "NoSuchUpload");
      if (req.method === "PUT") {
        if (u.checksum === "SHA256" && checksum === undefined) return fail(400, "InvalidRequest"); // a SHA-256 upload needs part checksums
        const etag = `"${crypto.createHash("md5").update(body).digest("hex")}"`;
        u.parts.set(Number(url.searchParams.get("partNumber")), { etag, body, sha256: checksum });
        res.writeHead(200, checksum ? { etag, "x-amz-checksum-sha256": checksum } : { etag });
        return res.end();
      }
      if (req.method === "GET") {
        const parts = [...u.parts.entries()].sort(([a], [b]) => a - b);
        res.writeHead(200, { "content-type": "application/xml" });
        return res.end(
          xml(
            `<ListPartsResult ${NS}><Bucket>${bucket}</Bucket><Key>${key}</Key><UploadId>${uploadId}</UploadId><IsTruncated>false</IsTruncated>` +
              parts.map(([n, p]) => `<Part><PartNumber>${n}</PartNumber><ETag>${p.etag}</ETag><Size>${p.body.length}</Size>${p.sha256 ? `<ChecksumSHA256>${p.sha256}</ChecksumSHA256>` : ""}</Part>`).join("") +
              "</ListPartsResult>",
          ),
        );
      }
      if (req.method === "POST") {
        if (noOverwrite && exists) return fail(412, "PreconditionFailed");
        const listed = [...body.toString().matchAll(/<Part>(.*?)<\/Part>/g)].map((x) => ({
          n: Number(/<PartNumber>(\d+)<\/PartNumber>/.exec(x[1])?.[1]),
          etag: /<ETag>([^<]+)<\/ETag>/.exec(x[1])?.[1]?.replace(/&quot;/g, '"'),
          sha256: /<ChecksumSHA256>([^<]+)<\/ChecksumSHA256>/.exec(x[1])?.[1],
        }));
        const assembled: Buffer[] = [];
        for (const p of listed) {
          const have = u.parts.get(p.n);
          if (!have || have.etag !== p.etag || (u.checksum === "SHA256" && have.sha256 !== p.sha256)) return fail(400, "InvalidPart");
          assembled.push(have.body);
        }
        const composite = u.checksum === "SHA256" ? `${b64sha(Buffer.concat(listed.map((p) => Buffer.from(p.sha256!, "base64"))))}-${listed.length}` : undefined;
        objects.set(key, { body: Buffer.concat(assembled), sse: u.sse, sha256: composite, modifiedAt: Date.now() });
        uploads.delete(uploadId);
        res.writeHead(200, { "content-type": "application/xml" });
        return res.end(xml(`<CompleteMultipartUploadResult ${NS}><Bucket>${bucket}</Bucket><Key>${key}</Key><ETag>"done"</ETag></CompleteMultipartUploadResult>`));
      }
      if (req.method === "DELETE") {
        uploads.delete(uploadId);
        res.writeHead(204);
        return res.end();
      }
    }
    const obj = objects.get(key);
    switch (req.method) {
      case "PUT":
        if (req.headers["content-length"] === undefined) return fail(411, "MissingContentLength");
        if (noOverwrite && exists) return fail(412, "PreconditionFailed");
        objects.set(key, { body, sse, sha256: checksum, modifiedAt: Date.now() });
        res.writeHead(200, { etag: `"${crypto.createHash("md5").update(body).digest("hex")}"` });
        return res.end();
      case "HEAD": {
        if (!obj) return fail(404, "NotFound");
        const h: Record<string, string> = { "content-length": String(obj.body.length), etag: '"x"', "last-modified": new Date(obj.modifiedAt).toUTCString() };
        if (req.headers["x-amz-checksum-mode"] === "ENABLED" && obj.sha256) h["x-amz-checksum-sha256"] = obj.sha256;
        res.writeHead(200, h);
        return res.end();
      }
      case "GET": {
        if (!obj) return fail(404, "NoSuchKey");
        const range = /^bytes=(\d+)-(\d*)$/.exec(String(req.headers.range ?? ""));
        if (range) {
          const start = Number(range[1]);
          const end = range[2] === "" ? obj.body.length - 1 : Math.min(Number(range[2]), obj.body.length - 1);
          res.writeHead(206, { "content-range": `bytes ${start}-${end}/${obj.body.length}`, "content-length": String(end - start + 1) });
          return res.end(obj.body.subarray(start, end + 1));
        }
        res.writeHead(200, { "content-length": String(obj.body.length) });
        return res.end(obj.body);
      }
      case "DELETE":
        objects.delete(key);
        res.writeHead(204);
        return res.end();
      default:
        return fail(405, "MethodNotAllowed");
    }
  });
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", () => r()));
  const port = (server.address() as AddressInfo).port;
  return {
    url: `http://127.0.0.1:${port}`,
    objects,
    uploads: uploads as unknown as FakeS3["uploads"],
    requests,
    failNext: (n) => {
      failures = n;
    },
    dropNext: (n) => {
      drops = n;
    },
    close: () =>
      new Promise<void>((r) => {
        server.closeAllConnections();
        server.close(() => r());
      }),
  };
}
