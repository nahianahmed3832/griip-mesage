/**
 * Records shared by core nodes and home servers (docs/SERVER_LIFECYCLE.md). Every record verifies on its own:
 * - decommission: signed by the server's current key (self-signed, carries its key chain);
 * - revocation: signed by at least `threshold` distinct trusted core keys;
 * - core answer: signed by the answering core node, so a resolver can attribute and count answers.
 */
import crypto from "node:crypto";
import { isServerId, type SelfSigned, type ServerDescriptor, verifySelfSigned } from "./identity.js";
import { base32, canonicalJson, type KeyPair, signRecord, signText, verifyRecord, verifyText } from "./sign.js";

export type LifecycleState = "pending" | "active" | "unreachable" | "suspended" | "decommissioned" | "revoked";
export const FINAL_STATES: LifecycleState[] = ["decommissioned", "revoked"];

export function coreIdFor(publicKey: string): string {
  return "gc1" + base32(crypto.createHash("sha256").update(Buffer.from(publicKey, "base64")).digest()).slice(0, 26);
}

export interface Decommission extends SelfSigned {
  t: "griip.server.decommission";
  reason: string;
  at: number;
}

export function verifyDecommission(r: Decommission): string | null {
  if (!r || r.t !== "griip.server.decommission" || typeof r.reason !== "string" || !Number.isSafeInteger(r.at)) return null;
  return verifySelfSigned(r) ? r.serverId : null;
}

export interface Revocation {
  t: "griip.server.revocation";
  serverId: string;
  reason: string;
  at: number;
  sigs: { coreKey: string; sig: string }[];
}

const revocationBody = (r: { serverId: string; reason: string; at: number }) =>
  canonicalJson({ t: "griip.server.revocation", serverId: r.serverId, reason: r.reason, at: r.at });

export function signRevocation(core: KeyPair, r: { serverId: string; reason: string; at: number }) {
  return { coreKey: core.publicKey, sig: signText(core, revocationBody(r)) };
}

/** Valid when `threshold` distinct trusted core keys signed exactly this serverId/reason/time. */
export function verifyRevocation(r: Revocation, trustedCoreKeys: Set<string>, threshold: number): boolean {
  if (!r || r.t !== "griip.server.revocation" || !isServerId(r.serverId) || !Array.isArray(r.sigs) || threshold < 1) return false;
  const body = revocationBody(r);
  const good = new Set<string>();
  for (const s of r.sigs) {
    if (trustedCoreKeys.has(s?.coreKey) && verifyText(s.coreKey, body, s.sig)) good.add(s.coreKey);
  }
  return good.size >= threshold;
}

export type FinalRecord = { kind: "decommissioned"; record: Decommission } | { kind: "revoked"; record: Revocation };

export interface ServerAnswer {
  serverId: string | null;
  domain: string | null;
  state: LifecycleState | "unknown";
  descriptor: ServerDescriptor | null;
  final: FinalRecord | null;
  leaseUntil: number | null;
}

export interface CoreAnswer {
  t: "griip.core.answer";
  coreId: string;
  coreKey: string;
  at: number;
  query: string;
  result: ServerAnswer;
  sig: string;
}

export function signAnswer(core: KeyPair, query: string, result: ServerAnswer, now = Date.now()): CoreAnswer {
  return signRecord(core, { t: "griip.core.answer" as const, coreId: coreIdFor(core.publicKey), coreKey: core.publicKey, at: now, query, result }) as CoreAnswer;
}

/** The answer must be signed by the core key the resolver was configured with, and be about the question asked. */
export function verifyAnswer(a: CoreAnswer, expectedCoreKey: string, query: string, now = Date.now()): boolean {
  if (!a || a.t !== "griip.core.answer" || a.coreKey !== expectedCoreKey || a.query !== query) return false;
  if (Math.abs(now - a.at) > 10 * 60_000) return false; // a replayed old answer can't roll a resolver back
  return verifyRecord(expectedCoreKey, a);
}
