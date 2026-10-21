package com.meldiq.android.data.model

/**
 * Where a conversation's "New messages" line goes, and which messages are drawn as mentioning you
 * (docs/product/MELDIQ_COMMUNITY_CHAT_SPEC.md §5). Pure and unit-tested (`ConversationMarkersTest`).
 */
object ConversationMarkers {
    /** What the marker needs to know about a message. */
    data class Line(val id: String, val outgoing: Boolean, val countsAsUnread: Boolean)

    /**
     * The first unread message, given the conversation's [unread] count when it was opened and its messages newest
     * first. Unread counts only count other people's messages, so the line goes above the [unread]-th newest of those;
     * your own messages after it don't move it. Null when nothing was unread or the count is larger than the loaded
     * history (the line would point at a message not loaded yet, so it isn't drawn).
     */
    fun firstUnread(newestFirst: List<Line>, unread: Int): String? {
        if (unread <= 0) return null
        var seen = 0
        for (m in newestFirst) {
            if (m.outgoing || !m.countsAsUnread) continue
            seen++
            if (seen == unread) return m.id
        }
        return null
    }

    /**
     * Whether a received message mentions you: your id among its mentions, or @everyone. Your own messages never do
     * (the same rule that counts mentions when messages arrive).
     */
    fun mentionsYou(outgoing: Boolean, stored: MentionSet, me: String?): Boolean =
        !outgoing && me != null && (me in stored.ids || stored.all)

    /**
     * "Mark unread" from a message: how many messages count as unread again, from [fromId] to the newest (other people's
     * messages only, as the unread count does). Zero when the message isn't loaded.
     */
    fun unreadFrom(newestFirst: List<Line>, fromId: String): Int {
        val idx = newestFirst.indexOfFirst { it.id == fromId }
        if (idx < 0) return 0
        return newestFirst.take(idx + 1).count { !it.outgoing && it.countsAsUnread }
    }
}
