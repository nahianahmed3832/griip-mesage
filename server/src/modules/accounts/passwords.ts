import crypto from "node:crypto";
import { promisify } from "node:util";

const scrypt = promisify(crypto.scrypt) as (password: string, salt: Buffer, keylen: number, options: crypto.ScryptOptions) => Promise<Buffer>;

/** scrypt block size and parallelism: with log2(N) = 15 this is OWASP's "N=2^15, r=8, p=3" setting. */
const BLOCK_SIZE = 8;
const PARALLELISM = 3;
const SALT_BYTES = 16;
const HASH_BYTES = 32;
const PREFIX = "scrypt";

export const PASSWORD_MIN_LENGTH = 10;
export const PASSWORD_MAX_LENGTH = 128;

interface Params {
  logN: number;
  r: number;
  p: number;
}

/** Memory scrypt needs for these parameters, plus headroom; Node refuses by default above 32 MiB. */
function maxmem({ logN, r, p }: Params): number {
  return 128 * r * (2 ** logN + p + 2) + 1024 * 1024;
}

async function derive(password: string, salt: Buffer, params: Params): Promise<Buffer> {
  return scrypt(password.normalize("NFKC"), salt, HASH_BYTES, { N: 2 ** params.logN, r: params.r, p: params.p, maxmem: maxmem(params) });
}

/** Stored form: `scrypt$logN$r$p$salt$hash` (base64url). The parameters travel with the hash so they can be raised later. */
export async function hashPassword(password: string, logN: number): Promise<string> {
  const salt = crypto.randomBytes(SALT_BYTES);
  const params = { logN, r: BLOCK_SIZE, p: PARALLELISM };
  const hash = await derive(password, salt, params);
  return [PREFIX, logN, BLOCK_SIZE, PARALLELISM, salt.toString("base64url"), hash.toString("base64url")].join("$");
}

function parse(stored: string): (Params & { salt: Buffer; hash: Buffer }) | null {
  const parts = stored.split("$");
  if (parts.length !== 6 || parts[0] !== PREFIX) return null;
  const [logN, r, p] = parts.slice(1, 4).map((x) => Number.parseInt(x, 10));
  if (![logN, r, p].every(Number.isSafeInteger) || logN < 1 || logN > 24 || r < 1 || p < 1) return null;
  return { logN, r, p, salt: Buffer.from(parts[4], "base64url"), hash: Buffer.from(parts[5], "base64url") };
}

/** Constant-time comparison of the derived hash. A malformed stored hash never matches. */
export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  const p = parse(stored);
  if (!p || p.hash.length !== HASH_BYTES) return false;
  const candidate = await derive(password, p.salt, p);
  return crypto.timingSafeEqual(candidate, p.hash);
}

/** True when the stored hash uses weaker parameters than the configured ones (rehashed at the next sign-in). */
export function needsRehash(stored: string, logN: number): boolean {
  const p = parse(stored);
  return !p || p.logN < logN || p.r !== BLOCK_SIZE || p.p !== PARALLELISM;
}

let dummy: Promise<string> | null = null;
let dummyLogN = 0;

/**
 * Spends the same time as checking a real password, for sign-ins with an unknown email: the answer and its timing
 * don't tell anyone which emails have accounts.
 */
export async function burnPasswordCheck(password: string, logN: number): Promise<void> {
  if (!dummy || dummyLogN !== logN) {
    dummyLogN = logN;
    dummy = hashPassword(crypto.randomBytes(18).toString("base64url"), logN);
  }
  await verifyPassword(password, await dummy);
}

export type PasswordProblem = "too_short" | "too_long" | "same_as_email";

/** The server's password rules (apps show the same ones, but this is the one that counts). */
export function passwordProblem(password: string, email: string): PasswordProblem | null {
  const length = [...password].length;
  if (length < PASSWORD_MIN_LENGTH) return "too_short";
  if (length > PASSWORD_MAX_LENGTH) return "too_long";
  if (password.trim().toLowerCase() === email.trim().toLowerCase()) return "same_as_email";
  return null;
}

export const PASSWORD_PROBLEMS: Record<PasswordProblem, string> = {
  too_short: `Use at least ${PASSWORD_MIN_LENGTH} characters.`,
  too_long: `Use at most ${PASSWORD_MAX_LENGTH} characters.`,
  same_as_email: "Your password can't be your email address.",
};
