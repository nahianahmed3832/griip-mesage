# Connections, message requests and DM permissions: specification (2026-10-21)

Batch B6. Evidence: `MELDIQ_SOCIAL_AND_COMMUNICATION_AUDIT.md`. **Needs the owner's approval before it is built**,
because it reverses the earlier decision in matrix row DM-04 ("no friend graph").

## 1. Decision

Meldiq gets a small **connections** model:
- a request, an acceptance, and a list of people you are connected with;
- **message requests**, so a stranger's first DM waits for your answer;
- per-person **DM permissions**.

It does not get friend suggestions, address-book matching, "friends of friends", "mutual friends" lists or activity
feeds.

**Why a model at all:**
1. `POST /v1/conversations/dm` creates a conversation with any user id, and the recipient has no say until they block.
   Consent needs a stored answer ("accepted", "declined").
2. The privacy audience "contacts" is derived today from shared conversations and communities (`profile.ts`
   `relation()` via `authz.canSeePresence`). That makes everyone in a 4,000-member community a "contact". A deliberate
   connection is a better audience for last seen, phone lookup and profile photo.
3. The owner asked for the friend workflow: request, accept or decline, cancel, remove, block.

**Why not Discord's whole friend system:**
- Suggestions and mutual-friend lists come from mining the social graph and contacts.
- Meldiq's privacy stance (`docs/PROFILE_AND_PRIVACY.md`) keeps the server from building that graph beyond what
  delivery needs.

## 2. Data (MongoDB-ready from the start)

A new domain, `relationships`, built with the same recipe as history:
- a store interface;
- a SQLite store and a MongoDB store;
- `ctx.mongo?.owns("relationships")` picks the store;
- tests run on both stores.

| Collection / table | Fields | Notes |
|---|---|---|
| `connections` | `pair` (the two user ids sorted, joined), `a`, `b`, `state` (`pending` / `accepted`), `requested_by`, `created_at`, `accepted_at` | One document per pair. Unique on `pair`. Declining or removing deletes it; the declined person isn't told |
| `dm_requests` | `conversation_id`, `recipient`, `sender`, `state` (`pending` / `declined`), `created_at` | A DM waiting for an answer |
| `users.dm_policy` | `everyone` / `communities` (default) / `connections` | Who can open a DM with you directly. Stored with the account (accounts domain) |
| `users.request_policy` | `everyone` (default) / `communities` / `nobody` | Who can send you a connection request or a message request |

- **Optional note:** a connection request can carry a note of up to 120 characters. It is not stored by the server in
  plain text. The app sends it as the first encrypted message of the request DM (§3), so only the recipient reads it.
- **Retention and deletion:** account deletion removes the person's rows in both collections (cascade after commit,
  sweep for orphans, as in history). Declined requests are deleted after 30 days.

## 3. Rules the server enforces

Order of checks for `POST /v1/conversations/dm {userId}` and `POST /v1/connections {userId | username}`:

1. **Unknown, deleted, disabled or blocked:** the target is unknown, deleted or disabled, or either side blocked the
   other. The answer is `404 not_available`, "You can't message this person", the same in every case, so a block
   isn't revealed.
2. **Same person:** `400`.
3. **Direct:** connected, or the target's `dm_policy` allows you (`everyone`, or `communities` and you share a
   community where you're a member, not a guest). The DM is created or returned as today.
4. **As a request:** the target's `request_policy` allows you. The DM is created with a `dm_requests` row
   (`pending`).
   - The sender may send messages. They are delivered encrypted as usual, but the recipient's apps show the
     conversation under **Requests**, send no push notification, and don't send read receipts.
   - At most 3 messages until the recipient accepts (`429 request_limit`).
5. **Otherwise:** `403 dm_not_allowed`, "This person only accepts messages from people they're connected with".

- **Duplicates:** a repeated request returns the existing one (`200`, idempotent). If the other person already
  requested you, a request in the other direction accepts theirs.
- **Rate limit:** 20 new requests an hour per person (`abuse.ts`), 429 with `retryAfter`.
- **Answering a request:**
  - **Accept** (connection or message request) connects the two people, and the DM moves to the main list.
  - **Decline** deletes the request and removes the conversation from the recipient's view. The server drops queued
    envelopes for the recipient's devices. The sender's view doesn't change.
  - **Block** declines and blocks.
- **Removing a connection** deletes it. Existing conversations stay, but policy checks apply to new DMs again.
- **Encryption:**
  - Nothing changes in the message format.
  - Declining or removing never re-shares or exposes history.
  - Group membership and key boundaries are unchanged (messages are encrypted to devices present at send time).

## 4. API

| Method | Path | Body / query | Answer |
|---|---|---|---|
| GET | `/v1/connections` | — | `{ connected: [user], incoming: [{user, at}], outgoing: [{user, at}] }` |
| POST | `/v1/connections` | `{ userId }` or `{ username }` | `{ state: "pending" | "accepted" }` |
| POST | `/v1/connections/:userId/accept` | — | `{ state: "accepted" }` |
| DELETE | `/v1/connections/:userId` | — | Cancel, decline or remove: `{ ok }` |
| GET | `/v1/conversations?requests=1` | — | Pending message requests for you |
| POST | `/v1/conversations/:id/request` | `{ action: "accept" | "decline" | "block" }` | `{ ok }` |
| PUT | `/v1/users/me/messaging` | `{ dmPolicy?, requestPolicy? }` | The new values |

- **Realtime:** `{ t: "connections.updated" }` to both people; `conv.updated` when a request is answered.
- **Push:** "New connection request" and "New message request", without names or content in the payload.

## 5. Android

| Where | What |
|---|---|
| Chats tab | A **Requests (n)** row at the top of the chat list when there are any. It opens the list of message requests. Each request opens read-only, with Accept, Decline and Block at the bottom and the sender's shared communities shown for context |
| Chats › "+" › People | **People** screen with Connected, Requests (incoming, with Accept and Decline; outgoing, with Cancel) and "Add by username" (field, optional note of 120 characters, Send request). Errors, in plain words: not available, already connected, request already sent, limit reached |
| Profile | The button shows the state: Connect / Request sent (Cancel) / Accept / Connected ▾ (Message, Remove connection). The ⋯ menu adds "Invite to community ›" and "Report" (with B4) |
| Settings › Privacy | "Who can message you directly" (Everyone / People in your communities / Only connections), "Who can send you requests" (Everyone / People in your communities / Nobody) |
| Activity | Request received and request accepted items |

## 6. Tests

- **Server (both stores):**
  - every check in §3, in order, including a block in either direction answering exactly like an unknown person;
  - duplicate and crossed requests;
  - the 3-message limit;
  - no push for requests;
  - decline drops queued envelopes;
  - account deletion cascades;
  - rate limit;
  - another person can't accept or remove a connection that isn't theirs (403);
  - a guest isn't "in a community" for `communities`.
- **Android:**
  - `ConnectionRules` (button state per relationship) and `RequestRules` (where a conversation is listed);
  - ViewModel tests with fakes;
  - emulator walk-through with two accounts.
- **Phone (owner):** the checklist in the B6 report.
