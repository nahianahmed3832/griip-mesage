# Mentions in groups and channels

Added 2026-10-17. Mentions work in groups and channels (not direct messages). Message content stays end-to-end
encrypted; the server never learns whom a message mentions by name.

## What people see

| Step | Behaviour |
|---|---|
| Typing `@` | A list of the conversation's members appears above the composer: display name and `@username` (people with the same display name are told apart by username). Typing filters by name or username; `@everyone` comes first where you're allowed to use it |
| Choosing | Inserts `@username ` (a person on another server: `@name:server`). Typing a username by hand works too, if it belongs to a member |
| In the chat | Mentions are bold; a mention of you, and `@everyone`, are highlighted. Tapping a person's mention opens their profile. A stray `@word` that wasn't a mention stays plain |
| Notifications | A mention of you uses the Mentions channel and notifies even in a muted conversation. `@everyone` uses the Mentions channel too but respects a muted conversation. The app's notification switch always wins. The conversation list counts mentions |

## How it works

- **Targets are user ids.** The encrypted payload's `mentions` holds the mentioned members' user ids (unchanged field). The
  sender resolves `@username` tokens against the conversation's member list only (`MentionRules.resolve`), so a token can't
  point at someone outside the conversation, at a same-named person on another server, or at a display name. Renaming
  someone changes nothing: the id is the target. Edits carry the new text's `mentions` (an older app's edit leaves them).
- **Rendering by id.** A message's text is drawn with mentions only where a token's username belongs to one of the
  message's mentioned ids (`MentionRules.spans`). Departed or unknown people's tokens stay plain text.
- **`@everyone` needs the server.** It is the only mention-all form (no `@all`/`@here`). The sender:
  1. puts `mentionAll: true` in the encrypted payload (omitted otherwise, so ordinary messages are byte-identical), and
  2. sends `mentionAll: true` in the clear with `POST /v1/messages`.

  The server checks its policy and hourly limit, refuses with `403 mention_all_not_allowed` / `mention_all_not_in_dm` or
  `429 mention_all_rate_limited`, and otherwise adds `mentionAll: true` to every recipient's queue payload. A recipient
  treats `@everyone` as a mention only when **both** the payload and the server's mark say so, in a group or channel
  (`MentionRules.received`). A modified client that only claims it in the payload gets plain text.
- **Policy** (`server/src/mentions.ts`): group admins (`conversation_members.role = ADMIN`, the creator by default) and
  channel managers (org owners/admins, branch managers, team managers) may always; other members only where the
  conversation has at most `MENTION_ALL_MEMBER_LIMIT` (default 10) members. Everyone: at most `MENTION_ALL_PER_HOUR`
  (default 5) per conversation per hour. A retried message (same message id) is a duplicate and isn't counted again.
  The app offers `@everyone` only where the conversation view says `canMentionAll`.
- **Retries, offline delivery, receipts.** Mentions live in the encrypted payload and the mark in the stored queue
  payload, so they survive retries, the 7-day offline queue, quarantine of held messages and redelivery. Duplicate
  deliveries are ignored before any notification.
- **Federation.** Federated conversations are direct messages, where `@everyone` doesn't apply; a peer server's mark is
  removed before queueing (`withoutMentionAll`). Individual mentions in a federated DM are just ids inside the encrypted
  payload, as before. Groups aren't federated.

## What the server can see

That a message asked for `@everyone`, in which conversation, and when (`mention_all_events`, kept one day for the limit).
Never which people are mentioned by name, nor the text.

## Tests

`server/test/mentions.test.ts` (8), `android/.../data/model/MentionRulesTest.kt` (10). Phone check (2026-10-17, HONOR
ELI-AN00, local server): autocomplete listing four members all named "Griip Bot" by username, sending a mention (the
recipient decrypted the mentioned user id), `@everyone` attested for every recipient, mentions of you highlighted,
tapping a mention opening the profile, and a background mention notification.
