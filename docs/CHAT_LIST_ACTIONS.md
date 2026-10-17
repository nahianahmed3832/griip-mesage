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

## The list jumping (fixed 2026-10-17)

The list jumped in three ways. All three were reproduced on the emulator and fixed:

| What you saw | Cause | Fix |
|---|---|---|
| Swiping a chat to the right threw the whole row off the screen and slid it back from the other side | The row was Material's `SwipeToDismissBox`, which always animates a "dismissed" row to the full width before it can be reset | `SwipeableChat` is now its own drag: marking read or unread reveals the action up to 45% of the row (with resistance past the threshold) and springs back. Archiving still slides the row out, because it leaves the list. Rules in `ChatSwipe`, tested in `ChatSwipeRulesTest`. |
| Coming back from a chat, the list was back at the top | The chats were collected in the screen with an empty starting value, so the first frame after returning had no rows and the saved scroll position was clamped to the top | `ChatsViewModel` holds the chats while a chat is open on top, so the list comes back with its rows and its scroll position (checked on the emulator, mid-list and at the end) |
| Near the end of the list, it came back shifted by the height of the tab bar | The tab bar's space disappeared as soon as a chat opened, so the list was briefly taller and clamped its position | The tab bar is drawn over the screens (`LocalTabBarPadding` gives tab screens matching bottom padding), so screens keep one height |

Also:

- **Stable order.** Ties in the chat order (same time) are broken by id, so rows never swap places between two reads.
- **The Back gesture.** A drag that begins in Android's Back-gesture area at either screen edge belongs to Android: rows
  (and swipe-to-reply in chats) ignore it, so the row doesn't move while the system shows its Back animation. Edges come
  from `WindowInsets.systemGestures`, which is 0 with button navigation.
- **Vertical scrolling** still wins over a mostly vertical drag (Compose's touch slop decides), so the list scrolls
  normally.

## Stuck swipe-to-reply (fixed in the same round)

The reply preview stayed on screen after sending, or a reply went out without its target. Causes: photos, files and
voice messages ignored the target and never cleared it; the target was read after the send started, so a new swipe
during sending could be cleared or used by mistake; and a failed send cleared it. Now the target is taken at the tap,
sent with every kind of message, cleared only once that send succeeded (a target chosen meanwhile stays), kept when
sending fails (the text goes back into an empty composer), dropped if the message is deleted, and never carried into
another conversation. Tests: `ReplyTargetTest`.
