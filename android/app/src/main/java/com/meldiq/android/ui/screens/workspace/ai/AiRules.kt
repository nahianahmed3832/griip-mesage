package com.meldiq.android.ui.screens.workspace.ai

import com.meldiq.android.data.api.AiCatchUpMessage
import com.meldiq.android.data.api.WfInput
import java.util.concurrent.atomic.AtomicReference

/** What the AI sheets decide, without Compose (docs/AI.md, round 7 slice C8). */
object AiRules {
    val WRITE_MODES = listOf("improve", "shorter", "friendlier", "formal", "fix_grammar")

    /** At most this many recent messages go into a catch-up, and at most this much text in all. */
    const val CATCH_UP_MAX = 150
    const val CATCH_UP_CHARS = 30_000
    /** One very long message is cut, so it can't crowd out the rest. */
    const val CATCH_UP_MESSAGE_CHARS = 2_000

    /** A readable message, before it's chosen for a catch-up. */
    data class Line(val author: String, val text: String, val at: Long, val readable: Boolean)

    /**
     * The newest readable messages, oldest first, within [CATCH_UP_MAX] and [CATCH_UP_CHARS]. Deleted, system and
     * undecryptable messages are left out by the caller marking them unreadable.
     */
    fun catchUp(lines: List<Line>): List<AiCatchUpMessage> {
        val picked = ArrayList<AiCatchUpMessage>()
        var chars = 0
        for (l in lines.asReversed()) {
            if (picked.size >= CATCH_UP_MAX) break
            val text = l.text.trim().take(CATCH_UP_MESSAGE_CHARS)
            if (!l.readable || text.isEmpty()) continue
            val cost = text.length + l.author.length + 2
            if (chars + cost > CATCH_UP_CHARS) break
            chars += cost
            picked.add(AiCatchUpMessage(l.author.take(80).ifBlank { "?" }, text, l.at))
        }
        return picked.asReversed()
    }

    fun monthLabelLimit(limit: Int?, unlimited: String): String = limit?.toString() ?: unlimited

    /** A workflow draft handed from the AI sheet to the editor (route id [EDITOR_ID]); taken once. */
    object DraftInbox {
        const val EDITOR_ID = "ai"
        private val pending = AtomicReference<WfInput?>(null)
        fun put(draft: WfInput) = pending.set(draft.copy(enabled = false, rev = null))
        fun take(): WfInput? = pending.getAndSet(null)
    }
}
