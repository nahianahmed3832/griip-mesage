package com.meldiq.android.data.model

/**
 * Batch B5 (Discord reference CHAN-CTRL-011, the channel's Threads list): every thread in a channel that this phone
 * has, latest reply first, searchable by its first line. Built from the phone's own history: messages are end-to-end
 * encrypted, so the server can't list them.
 */
object ThreadListRules {
    /** At most this many threads are listed. */
    const val SHOWN = 200
    private const val TITLE_MAX = 120

    data class Row(val rootId: String, val title: String, val senderId: String, val outgoing: Boolean, val replies: Int, val lastReplyTs: Long)

    /** A thread's name: the root's own title (forum posts), else its first non-blank line, else [fallback] (a file). */
    fun title(richTitle: String?, text: String, fallback: String): String =
        richTitle?.trim()?.takeIf { it.isNotEmpty() }
            ?: text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(TITLE_MAX)
            ?: fallback

    /** The rows whose name contains [query] (any case); all of them for a blank query. */
    fun filter(rows: List<Row>, query: String): List<Row> {
        val q = query.trim()
        return if (q.isEmpty()) rows else rows.filter { it.title.contains(q, ignoreCase = true) }
    }
}
