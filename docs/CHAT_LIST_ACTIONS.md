# Chat list: swipe actions and archive

Added 2026-10-17.

| Gesture | Action |
|---|---|
| Swipe right | Mark read, or mark unread (on this phone; no read receipt is sent either way) |
| Swipe left | Archive (with Undo for 5 s); in the archived list, unarchive |
| Long press | Mute or unmute, mark read or unread, archive or unarchive, chat info |
| Accessibility | The same three actions as custom actions on each row |

- A swipe must pass a third of the row (haptic tick at the threshold); a shorter drag springs back.
- **Archive is local and reversible.** You stay a member, history is kept, nothing is sent, and the chat still notifies
  unless it is muted (the archived list says so). Archived chats leave the main list and filters, and the badge on the
  Chats tab, but the name filter still finds them (so nothing seems lost). "Archived" at the top of the list shows how
  many chats and how many unread; Back leaves the archived list.
- Not the same as a channel admin's "Archive channel" (read-only for everyone), which is unchanged.
- Deleting a conversation isn't offered: there's no safe way to do it yet.

Storage: a nullable `conversations.archivedAt` column (database version 5, migration `4 → 5`); every existing chat stays
unarchived. Server refreshes keep it.

Tests: `ChatListRulesTest` (what's listed and counted), `MigrationsTest` (the v5 schema and existing rows).

## Stuck swipe-to-reply (fixed in the same round)

The reply preview stayed on screen after sending, or a reply went out without its target. Causes: photos, files and
voice messages ignored the target and never cleared it; the target was read after the send started, so a new swipe
during sending could be cleared or used by mistake; and a failed send cleared it. Now the target is taken at the tap,
sent with every kind of message, cleared only once that send succeeded (a target chosen meanwhile stays), kept when
sending fails (the text goes back into an empty composer), dropped if the message is deleted, and never carried into
another conversation. Tests: `ReplyTargetTest`.
