# ADR 0006: Remote users are represented by "shadow" rows with their real ids

Date: 2026-10-16. Status: accepted.

## Context

`griip-envelope-v1` signs (and authenticates as AEAD data) the message id, conversation id, sender user id and sender device id, and the
HPKE key wrap binds the recipient device id. A recipient can verify a message only if those ids are identical on both servers.
The server's tables require local rows (foreign keys) for every conversation member and device.

## Decision

- A remote user and their devices are stored locally with **the same ids** as on their home server, `users.home_server_id` = that
  server's id, username `name:domain`. Keys come only from the home server and every device certificate is checked on arrival.
- Federated DMs get `cf_` + hash of the two sorted user ids as their id on both servers.
- Shadows can't sign in, aren't searchable, can't join groups; deliveries, key entries and call actions for a shadow are accepted only from
  its home server; an id that is local or bound to another server is refused.

## Alternatives considered

- *Namespaced remote ids* (`u_x@server`): cleaner tables, but every client would need a new signature format (a protocol change).
- *A separate remote-user table*: every membership, key and queue query would need a second path; more code, more ways to get it wrong.

## Consequences

- Almost no client changes: the app encrypts to and verifies shadows exactly like local users (pinning, safety numbers, key-change holds).
- A malicious server that claims a random id first can block a *later* legitimate claim of the same id on a third server (a denial of
  contact, not impersonation: the conflicting user can't be added). Ids are 128-bit random; documented as a residual risk.
