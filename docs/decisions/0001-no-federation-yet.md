# ADR 0001: Self-hosting means independent instances; no federation yet

Date: 2026-10-16. Status: **superseded** the same day by ADR 0005 (the owner clarified that Griip must be a federated network).
Relates to D-16 (`../DECISIONS.md`) and `../DECENTRALISATION_READINESS.md`. Kept for the record; the reasoning in Context still
applies to what federation v1 deliberately leaves out (groups, global search).

## Context

Communities want to run their own Griip Message servers. Existing users must keep their accounts. Federation (accounts on
server A messaging accounts on server B) is a much larger change:

- Identity: users are identified by server-local ids and usernames, and identity keys are pinned per server (TOFU). A federated
  directory would need signed identity documents and a way to resolve `user@server` safely.
- Group membership is asserted by the server and not verified by clients (`../SECURITY.md` §4). Across servers, a malicious server
  could add members to a conversation; client-verifiable membership (MLS or a signed membership log) has to come first.
- Delivery, receipts, retention and abuse handling would need server-to-server authentication and policy.
- Calls would need cross-server LiveKit access and key distribution.

## Decision

- Each server is an independent instance with its own accounts, organisations, conversations and LiveKit.
- The app supports several servers through separate local profiles (ADR 0002), not through federation.
- No server-to-server protocol, shared directory, global ledger or blockchain is built now. Nothing in this release presumes one.
- Federation, if pursued, starts with a separate proposal: client-verifiable group membership, signed identity documents,
  a server-to-server authentication design, a migration and interoperability plan, and a security review. `griip-envelope-v1` is not
  changed in place.

## Consequences

- Self-hosting works today without protocol changes; `/v1/server-info` lets the app check compatibility.
- People on different servers can't message each other; a person who belongs to two communities has two accounts.
- The envelope format and per-server identity remain compatible with a later federation design, because nothing here assumes
  a single global server.
