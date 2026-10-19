package com.meldiq.android.data.model

import com.meldiq.android.data.db.MessageEntity

/** One thread with replies for you on the Activity tab. */
data class ThreadActivity(
    val conversationId: String,
    val rootId: String,
    /** The newest reply by someone else. */
    val latest: MessageEntity,
    /** Replies by others since you last opened the thread. */
    val unseen: Int,
)

/**
 * Thread replies on the Activity tab (docs/WORKSPACE_ADMINISTRATION.md): built from this phone's own history, since
 * messages are end-to-end encrypted. A thread counts as yours when you started it or replied in it.
 */
object ThreadActivityRules {
    /** How far back Activity looks for replies. */
    const val WINDOW_MS = 14L * 24 * 60 * 60 * 1000
    /** Threads remembered as seen; the oldest are forgotten first. */
    const val MAX_SEEN = 500

    /**
     * [replies] (newest first) grouped by thread: threads with unseen replies first, then the rest, each newest first.
     * [seen] is when you last opened each thread (its newest reply's sort time then).
     */
    fun summarize(replies: List<MessageEntity>, seen: Map<String, Long>): List<ThreadActivity> =
        replies.groupBy { it.threadRootId ?: "" }
            .filterKeys { it.isNotEmpty() }
            .map { (root, list) ->
                val since = seen[root] ?: 0L
                ThreadActivity(list.first().conversationId, root, list.maxBy { it.sortTs }, list.count { it.sortTs > since })
            }
            .sortedWith(compareByDescending<ThreadActivity> { it.unseen > 0 }.thenByDescending { it.latest.sortTs })

    /** Marks [rootId] seen up to [upTo] (never backwards), keeping at most [max] threads. */
    fun markSeen(seen: Map<String, Long>, rootId: String, upTo: Long, max: Int = MAX_SEEN): Map<String, Long> {
        val next = seen + (rootId to maxOf(seen[rootId] ?: 0L, upTo))
        if (next.size <= max) return next
        return next.entries.sortedByDescending { it.value }.take(max).associate { it.key to it.value }
    }

    /** Total unseen replies (the Activity section's count). */
    fun unseenTotal(threads: List<ThreadActivity>): Int = threads.sumOf { it.unseen }
}
