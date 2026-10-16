package com.griipbuddy.orbit.notify

/**
 * The last few notified messages per conversation, so a notification shows recent history rather than
 * only the newest line (T4.4). Held in memory only, never written to disk, and cleared when the
 * conversation is opened, read from the notification, or the device is erased.
 */
class NotificationHistory(private val perConversation: Int = 6, private val maxConversations: Int = 50) {
    data class Line(val sender: String, val text: String, val at: Long)

    private val lines = object : LinkedHashMap<String, ArrayDeque<Line>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayDeque<Line>>?) = size > maxConversations
    }

    /** Adds [line] and returns the conversation's history, oldest first. */
    @Synchronized
    fun add(conversationId: String, line: Line): List<Line> {
        val q = lines.getOrPut(conversationId) { ArrayDeque() }
        q.addLast(line)
        while (q.size > perConversation) q.removeFirst()
        return q.toList()
    }

    @Synchronized
    fun clear(conversationId: String) {
        lines.remove(conversationId)
    }

    @Synchronized
    fun clearAll() = lines.clear()
}
