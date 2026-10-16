# ADR 0005: Griip is a federated network of independent servers with a replicated core registry

Date: 2026-10-16. Status: accepted. Supersedes ADR 0001. Specification: `../FEDERATION_PROTOCOL.md`.

## Context

The owner's requirement: people on different, independently operated servers must find and message each other without an account on
each server; communities must be able to join; the network must survive the loss of a core node; infrastructure must never read content.

## Decision

- **Home servers federate directly** with signed server-to-server requests (`griip-fed/1`). No full mesh, no standing connections.
- **Servers have self-certifying ids** (hash of an Ed25519 root key) and signed, versioned descriptors with a key-rotation chain.
- **Several independently operated core nodes** keep a replicated registry of servers: endpoint-challenged domain bindings, leases,
  server-signed decommissions, threshold-signed revocations. Resolvers require a quorum and verify everything themselves.
- **No new cryptography for content:** `griip-envelope-v1`, attachment encryption and call E2EE are reused unchanged. This works because
  remote users keep identical ids (ADR 0006) and federated DMs get a conversation id both servers derive.
- **v1 scope:** DMs, receipts, attachments, voice messages and 1:1 calls (ADR 0007). Groups and channels stay server-local until clients can
  verify membership (MLS or a signed membership log, evaluated in `../DECENTRALISATION_READINESS.md`).
- Exact-address discovery only; no global name index.

## Alternatives considered

- *Matrix-style room replication*: every server stores the full room history and state. Rejected: Griip keeps history only on devices
  and relays ciphertext for ≤ 7 days; replicating room state contradicts that and is far larger.
- *A single central directory*: simple, but a single point of failure and control, against the requirements.
- *A blockchain or global ledger*: not needed for the trust model (signed records + quorum + pinning), and slow and public.
- *Moving to MLS now*: correct long-term direction for groups, but not required for 1:1 federation; replacing the envelope without an
  audit and migration plan was ruled out.

## Consequences

- Existing accounts get global addresses with no migration; standalone servers keep working with federation off.
- Operators must protect and back up the server identity key (included in `scripts/backup.sh`).
- Metadata is visible to both home servers of a conversation (`../SECURITY_AND_PRIVACY.md` §4).
