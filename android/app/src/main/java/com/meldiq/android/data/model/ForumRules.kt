package com.meldiq.android.data.model

/**
 * Forum channels (communities, 2026-10-21): every top-level message is a post with a title, and its replies are the
 * post's thread. Titles travel inside the end-to-end encrypted message, so everything here comes from this phone's own
 * history. Pure and unit-tested (`ForumRulesTest`).
 */
object ForumRules {
    const val MAX_TITLE = 120

    /** A received or typed title: one line, trimmed, bounded; null when nothing is left. */
    fun cleanTitle(t: String?): String? = t?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_TITLE)?.ifEmpty { null }

    /** What a post sends as its text: the title, a blank line and the body, so apps without forums still show both. */
    fun text(title: String, body: String): String = listOf(title.trim(), body.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")

    /** The body to show under a post's [title]: its text without the leading title line. */
    fun body(title: String?, text: String): String {
        val t = title ?: return text
        return if (text.startsWith(t)) text.removePrefix(t).trimStart('\n', ' ') else text
    }

    /** A post's title: the one it was sent with, or its first line for messages sent before the channel was a forum. */
    fun titleOf(title: String?, text: String): String = cleanTitle(title) ?: cleanTitle(text.lineSequence().firstOrNull()) ?: ""

    /** A post in the list: its message, the replies' count and the latest activity (the post or its newest reply). */
    data class Post<T>(val root: T, val replies: Int, val lastActivity: Long)

    enum class Sort { LATEST_ACTIVITY, NEWEST }

    /** A thread's replies as this phone has them: how many and the newest one's time. */
    data class Replies(val count: Int, val last: Long)

    /**
     * A forum's posts from its top-level messages ([roots]) and their threads' [replies] by root id, newest first by
     * [sort]. Deleted posts ([visible] false) are left out.
     */
    fun <T> posts(roots: List<T>, replies: Map<String, Replies>, sort: Sort, id: (T) -> String, time: (T) -> Long, visible: (T) -> Boolean = { true }): List<Post<T>> =
        roots.filter(visible)
            .map { m -> val r = replies[id(m)]; Post(m, r?.count ?: 0, maxOf(time(m), r?.last ?: 0L)) }
            .sortedByDescending { if (sort == Sort.LATEST_ACTIVITY) it.lastActivity else time(it.root) }
}
