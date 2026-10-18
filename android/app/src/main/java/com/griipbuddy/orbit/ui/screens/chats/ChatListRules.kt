package com.griipbuddy.orbit.ui.screens.chats

import com.griipbuddy.orbit.data.db.ConversationEntity

/**
 * Which chats the Chats tab shows (2026-10-17 swipe actions; unit-tested in `ChatListRulesTest`).
 *
 * Archiving is local to this phone and reversible: an archived chat leaves the main list but stays a member, keeps its
 * history and still notifies (unless muted). It is found under "Archived" at the top of the list, and by the name filter.
 */
object ChatListRules {
    const val ALL = 0
    const val UNREAD = 1
    const val DIRECT = 2
    const val GROUPS = 3
    const val CHANNELS = 4

    fun isArchived(c: ConversationEntity) = c.archivedAt != null

    /** Direct messages and personal groups: what the DMs tab lists (since UI-002 channels live on Home). */
    fun isPeople(c: ConversationEntity) = c.type == "DM" || c.type == "GROUP"

    /**
     * The chats to list. In the main view: not archived, matching [filter]; while filtering by name, archived matches show
     * too (so nothing is lost). In the archived view: archived chats only, in the main list's order (newest message first).
     * [peopleOnly] (the DMs tab): the main view lists direct messages and groups only; the archived view still lists every
     * archived chat, channels included, so each can be restored.
     */
    fun visible(
        convs: List<ConversationEntity>,
        filter: Int,
        query: String,
        archivedView: Boolean,
        peopleOnly: Boolean = false,
        title: (ConversationEntity) -> String,
    ): List<ConversationEntity> {
        val q = query.trim()
        return convs.filter { c ->
            val inView = when {
                archivedView -> isArchived(c)
                q.isNotEmpty() -> true
                else -> !isArchived(c)
            }
            inView && (archivedView || ((!peopleOnly || isPeople(c)) && matches(c, filter))) && (q.isEmpty() || title(c).contains(q, ignoreCase = true))
        }
    }

    fun matches(c: ConversationEntity, filter: Int): Boolean = when (filter) {
        UNREAD -> c.unreadCount > 0
        DIRECT -> c.type == "DM"
        GROUPS -> c.type == "GROUP"
        CHANNELS -> c.type == "CHANNEL"
        else -> true
    }

    /** Unread chats for the "Unread" chip: archived ones aren't counted (they're out of the main list). */
    fun unreadCount(convs: List<ConversationEntity>, peopleOnly: Boolean = false): Int =
        convs.count { !isArchived(it) && it.unreadCount > 0 && (!peopleOnly || isPeople(it)) }

    data class Archived(val chats: Int, val unreadChats: Int)

    /** The "Archived" row: how many chats, and how many of them have unread messages. */
    fun archived(convs: List<ConversationEntity>): Archived =
        convs.filter(::isArchived).let { a -> Archived(a.size, a.count { it.unreadCount > 0 }) }

    /** Swiping right toggles read state: unread chats become read, read chats become unread. */
    fun swipeMarksRead(c: ConversationEntity): Boolean = c.unreadCount > 0
}
