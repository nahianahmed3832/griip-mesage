# ADR 0007: Cross-server calls are hosted by the caller's server

Date: 2026-10-16. Status: accepted. Details: `../CALLING_AND_E2EE.md` §6.

## Decision

The caller's server hosts the call on its own LiveKit and is the only one that issues tokens. The callee's server mirrors the call (same id,
`calls.host_server_id`) from signed events and forwards its users' actions (join, connected, alerted, decline, end, leave) to the host, which
checks that the user and device belong to the requesting server. The media key is still created by the caller and sent only inside the
end-to-end encrypted `call_invite` message.

## Why

- No change to the media architecture or the frame E2EE; LiveKit secrets never leave their server.
- The callee's server needs no LiveKit to take part.

## Consequences

- The callee's device connects directly to the host's media server, which therefore sees the callee's IP address and the call's timing
  (`../SECURITY_AND_PRIVACY.md` §4). No TURN relay is configured (unchanged limitation).
- A server without LiveKit can't start calls with remote people (the app hides the call buttons) but can join calls they start.
- If the host disappears mid-call, the mirror ends locally after 12 hours (ringing mirrors after twice the ring window).
