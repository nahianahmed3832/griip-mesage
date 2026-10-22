import { z } from "zod/v4";
import { UserSummary } from "./conversations.js";
import { route } from "./route.js";

/**
 * Federation for this server's own signed-in people (docs/FEDERATION_PROTOCOL.md): their address and looking up
 * someone on another server. These routes exist only when FEDERATION_DOMAIN is set. The server-to-server API
 * (/fed/v1/*) speaks its own signed protocol and isn't documented here.
 */

export const FederationInfo = z.strictObject({
  enabled: z.literal(true),
  domain: z.string(),
  serverId: z.string(),
  /** This person's address, "@name:domain". */
  address: z.string(),
});

export const ResolveQuery = z.object({ address: z.string().trim().min(3).max(300) });

export const ResolvedUser = z.strictObject({
  user: UserSummary.extend({
    identityKey: z.string(),
    /** For comparing in person (safety number). */
    fingerprint: z.string(),
    address: z.string(),
  }),
  /** The person's server as the network's registry verified it ("local" for this server). */
  server: z.strictObject({
    serverId: z.string(),
    domain: z.string(),
    state: z.enum(["pending", "active", "unreachable", "suspended", "decommissioned", "revoked", "unknown"]),
    /** How many registry nodes agreed (0 when served from cache or local). */
    verifiedBy: z.number().int().nonnegative(),
    source: z.enum(["quorum", "cache", "local"]),
    /** The domain now resolves to a different server than the one pinned before. */
    identityChanged: z.boolean(),
  }),
});

export const federationRoutes = [
  route({
    method: "GET",
    url: "/v1/federation/info",
    tag: "federation",
    summary: "This server's federation domain and id, and the person's own address.",
    auth: true,
    requires: "federation",
    response: FederationInfo,
  }),
  route({
    method: "GET",
    url: "/v1/federation/resolve",
    tag: "federation",
    summary: "Resolve an exact address (@name:server) to a verified identity on this or another server.",
    auth: true,
    requires: "federation",
    query: ResolveQuery,
    response: ResolvedUser,
    errors: [
      "not_found",
      "bad_address",
      "bad_domain",
      "unknown_domain",
      "directory_unavailable",
      "directory_conflict",
      "server_unresolvable",
      "bad_descriptor",
      "self",
      "server_retired",
      "server_blocked",
      "not_allowed",
      "insecure_peer",
      "server_not_active",
      "peer_refused",
      "lookup_failed",
      "lookup_mismatch",
      "bad_response_signature",
    ],
  }),
];
