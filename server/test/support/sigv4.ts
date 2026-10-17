import crypto from "node:crypto";

/**
 * AWS Signature Version 4, independent of the AWS SDK, for the fake S3 in tests (test/support/fakeS3.ts) to check the
 * SDK's requests and presigned URLs against. Checked against AWS's published S3 examples in test/storage_s3.test.ts.
 * https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-authenticating-requests.html
 */

export interface AwsCredentials {
  accessKeyId: string;
  secretAccessKey: string;
  sessionToken?: string;
}

export const UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
export const EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

const hmac = (key: Buffer | string, data: string) => crypto.createHmac("sha256", key).update(data, "utf8").digest();
export const sha256Hex = (data: Buffer | string) => crypto.createHash("sha256").update(data).digest("hex");

/** RFC 3986 encoding as SigV4 wants it: everything but A–Z a–z 0–9 - _ . ~ ; slashes kept in paths. */
export function uriEncode(s: string, keepSlash = false): string {
  let out = "";
  for (const ch of Buffer.from(s, "utf8")) {
    const c = String.fromCharCode(ch);
    if (/[A-Za-z0-9\-_.~]/.test(c) || (keepSlash && c === "/")) out += c;
    else out += "%" + ch.toString(16).toUpperCase().padStart(2, "0");
  }
  return out;
}

/** 20130524T000000Z */
export function amzDate(d: Date): string {
  return d.toISOString().replace(/[:-]|\.\d{3}/g, "");
}

function signingKey(secret: string, date: string, region: string, service: string): Buffer {
  return hmac(hmac(hmac(hmac(`AWS4${secret}`, date), region), service), "aws4_request");
}

function canonicalQuery(params: [string, string][]): string {
  return params
    .map(([k, v]) => [uriEncode(k), uriEncode(v)] as const)
    .sort(([a, av], [b, bv]) => (a < b ? -1 : a > b ? 1 : av < bv ? -1 : av > bv ? 1 : 0))
    .map(([k, v]) => `${k}=${v}`)
    .join("&");
}

interface Canonical {
  method: string;
  /** Already-encoded absolute path ("/bucket/key" or "/key"). */
  path: string;
  query: [string, string][];
  headers: Record<string, string>;
  payloadHash: string;
}

function canonicalRequest(c: Canonical): { text: string; signedHeaders: string } {
  const names = Object.keys(c.headers).map((h) => h.toLowerCase()).sort();
  const lower: Record<string, string> = {};
  for (const [k, v] of Object.entries(c.headers)) lower[k.toLowerCase()] = String(v).trim().replace(/\s+/g, " ");
  const signedHeaders = names.join(";");
  const text = [c.method, c.path, canonicalQuery(c.query), names.map((n) => `${n}:${lower[n]}\n`).join(""), signedHeaders, c.payloadHash].join("\n");
  return { text, signedHeaders };
}

function stringToSign(date: Date, scope: string, canonical: string): string {
  return ["AWS4-HMAC-SHA256", amzDate(date), scope, sha256Hex(canonical)].join("\n");
}

export interface SignInput {
  method: string;
  url: URL;
  region: string;
  service?: string;
  credentials: AwsCredentials;
  /** Headers to send and sign (host is added). */
  headers?: Record<string, string>;
  payloadHash?: string;
  date?: Date;
}

/** The headers to send for a header-signed request: the given ones plus host, x-amz-date, x-amz-content-sha256, Authorization. */
export function signHeaders(i: SignInput): Record<string, string> {
  const date = i.date ?? new Date();
  const service = i.service ?? "s3";
  const payloadHash = i.payloadHash ?? EMPTY_SHA256;
  const day = amzDate(date).slice(0, 8);
  const scope = `${day}/${i.region}/${service}/aws4_request`;
  const headers: Record<string, string> = {
    ...(i.headers ?? {}),
    host: i.url.host,
    "x-amz-date": amzDate(date),
    "x-amz-content-sha256": payloadHash,
  };
  if (i.credentials.sessionToken) headers["x-amz-security-token"] = i.credentials.sessionToken;
  const query = [...i.url.searchParams.entries()];
  const { text, signedHeaders } = canonicalRequest({ method: i.method, path: i.url.pathname, query, headers, payloadHash });
  const sig = hmac(signingKey(i.credentials.secretAccessKey, day, i.region, service), stringToSign(date, scope, text)).toString("hex");
  headers.authorization = `AWS4-HMAC-SHA256 Credential=${i.credentials.accessKeyId}/${scope}, SignedHeaders=${signedHeaders}, Signature=${sig}`;
  return headers;
}

/** A presigned URL valid for [expiresSeconds] (1 s – 7 days): only the host header is signed, the payload is not. */
export function presignUrl(i: { method: string; url: URL; region: string; credentials: AwsCredentials; expiresSeconds: number; date?: Date; service?: string }): string {
  const date = i.date ?? new Date();
  const service = i.service ?? "s3";
  const day = amzDate(date).slice(0, 8);
  const scope = `${day}/${i.region}/${service}/aws4_request`;
  const expires = Math.max(1, Math.min(604_800, Math.floor(i.expiresSeconds)));
  const query: [string, string][] = [...i.url.searchParams.entries()];
  query.push(["X-Amz-Algorithm", "AWS4-HMAC-SHA256"]);
  query.push(["X-Amz-Credential", `${i.credentials.accessKeyId}/${scope}`]);
  query.push(["X-Amz-Date", amzDate(date)]);
  query.push(["X-Amz-Expires", String(expires)]);
  if (i.credentials.sessionToken) query.push(["X-Amz-Security-Token", i.credentials.sessionToken]);
  query.push(["X-Amz-SignedHeaders", "host"]);
  const { text } = canonicalRequest({ method: i.method, path: i.url.pathname, query, headers: { host: i.url.host }, payloadHash: UNSIGNED_PAYLOAD });
  const sig = hmac(signingKey(i.credentials.secretAccessKey, day, i.region, service), stringToSign(date, scope, text)).toString("hex");
  return `${i.url.protocol}//${i.url.host}${i.url.pathname}?${canonicalQuery(query)}&X-Amz-Signature=${sig}`;
}

/**
 * The signature of any request, header-signed or presigned: [query] without X-Amz-Signature, [headers] holding exactly
 * the signed headers (lower-case names) with the values received. Lets the fake S3 verify what the SDK sent.
 */
export function signatureFor(i: {
  method: string;
  path: string;
  query: [string, string][];
  headers: Record<string, string>;
  payloadHash: string;
  date: Date;
  region: string;
  secretAccessKey: string;
  service?: string;
}): string {
  const service = i.service ?? "s3";
  const day = amzDate(i.date).slice(0, 8);
  const scope = `${day}/${i.region}/${service}/aws4_request`;
  const { text } = canonicalRequest({ method: i.method, path: i.path, query: i.query, headers: i.headers, payloadHash: i.payloadHash });
  return hmac(signingKey(i.secretAccessKey, day, i.region, service), stringToSign(i.date, scope, text)).toString("hex");
}

/** "20130524T000000Z" → Date. */
export function parseAmzDate(s: string): Date {
  return new Date(`${s.slice(0, 4)}-${s.slice(4, 6)}-${s.slice(6, 8)}T${s.slice(9, 11)}:${s.slice(11, 13)}:${s.slice(13, 15)}Z`);
}
