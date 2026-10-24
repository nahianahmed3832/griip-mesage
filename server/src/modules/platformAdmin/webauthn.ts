import { createHash } from "node:crypto";
import {
  type AuthenticationResponseJSON,
  type AuthenticatorTransport,
  generateAuthenticationOptions,
  generateRegistrationOptions,
  type RegistrationResponseJSON,
  verifyAuthenticationResponse,
  verifyRegistrationResponse,
} from "@simplewebauthn/server";
import type { AdminConsoleConfig } from "./config.js";
import type { AdminPasskeyRow } from "./store.js";

/**
 * Console passkey ceremonies: one relying party (the console's host), one origin (the console's), user verification
 * always required, no attestation asked for, ES256 and RS256. The same library and rules as account passkeys
 * (modules/accounts/passkeys.ts), on their own relying party and table.
 */
export const CEREMONY_MS = 5 * 60_000;
const RP_NAME = "Meldiq Admin";
const ALGORITHMS = [-7, -257];

type Options = Record<string, unknown>;

const transportsOf = (r: Pick<AdminPasskeyRow, "transports">) => (r.transports ? (JSON.parse(r.transports) as AuthenticatorTransport[]) : undefined);

export async function registrationOptions(cfg: AdminConsoleConfig, account: { id: string; email: string; name: string }, existing: AdminPasskeyRow[]): Promise<{ challenge: string; options: Options }> {
  const options = await generateRegistrationOptions({
    rpName: RP_NAME,
    rpID: cfg.rpId,
    // An opaque handle, not the email or the account id.
    userID: createHash("sha256").update(`meldiq-admin-passkey:${account.id}`).digest(),
    userName: account.email,
    userDisplayName: `${account.name} (Meldiq Admin)`,
    attestationType: "none",
    excludeCredentials: existing.map((e) => ({ id: e.id, transports: transportsOf(e) })),
    authenticatorSelection: { residentKey: "preferred", userVerification: "required" },
    supportedAlgorithmIDs: ALGORITHMS,
    timeout: CEREMONY_MS,
  });
  return { challenge: options.challenge, options: options as unknown as Options };
}

export async function verifyRegistration(
  cfg: AdminConsoleConfig,
  response: unknown,
  expectedChallenge: string,
): Promise<{ id: string; publicKey: Uint8Array; counter: number; transports: string[] | undefined; backedUp: boolean } | null> {
  const result = await verifyRegistrationResponse({
    response: response as RegistrationResponseJSON,
    expectedChallenge,
    expectedOrigin: cfg.origin,
    expectedRPID: cfg.rpId,
    requireUserVerification: true,
  }).catch(() => ({ verified: false as const }));
  if (!result.verified) return null;
  const { credential, credentialBackedUp } = result.registrationInfo;
  return { id: credential.id, publicKey: credential.publicKey, counter: credential.counter, transports: credential.transports, backedUp: credentialBackedUp };
}

/** Options for signing in or re-authenticating: only the admin's own console passkeys are offered. */
export async function authenticationOptions(cfg: AdminConsoleConfig, passkeys: AdminPasskeyRow[]): Promise<{ challenge: string; options: Options }> {
  const options = await generateAuthenticationOptions({
    rpID: cfg.rpId,
    userVerification: "required",
    allowCredentials: passkeys.map((p) => ({ id: p.id, transports: transportsOf(p) })),
    timeout: CEREMONY_MS,
  });
  return { challenge: options.challenge, options: options as unknown as Options };
}

export async function verifyAuthentication(
  cfg: AdminConsoleConfig,
  response: unknown,
  expectedChallenge: string,
  row: AdminPasskeyRow,
): Promise<{ newCounter: number; backedUp: boolean } | null> {
  const result = await verifyAuthenticationResponse({
    response: response as AuthenticationResponseJSON,
    expectedChallenge,
    expectedOrigin: cfg.origin,
    expectedRPID: cfg.rpId,
    credential: { id: row.id, publicKey: Buffer.from(row.public_key, "base64url"), counter: row.counter, transports: transportsOf(row) },
    requireUserVerification: true,
  }).catch(() => ({ verified: false as const }));
  if (!result.verified) return null;
  return { newCounter: result.authenticationInfo.newCounter, backedUp: result.authenticationInfo.credentialBackedUp };
}
