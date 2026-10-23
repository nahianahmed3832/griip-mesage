package com.meldiq.android.data.model

import java.util.TimeZone

/**
 * Search operators (2026-10-23, the reference's "from: / has: / before: / after:" filters) for messages on this phone,
 * which the server can't search (they're end-to-end encrypted). Unknown or malformed operators stay ordinary words.
 * Dates are days (yyyy-mm-dd) in [zone]: before: means earlier than that day, after: means later than its start.
 */
data class SearchQuery(val text: String, val from: String?, val has: Has?, val before: Long?, val after: Long?) {
    enum class Has(val word: String) { FILE("file"), IMAGE("image"), VIDEO("video"), LINK("link") }

    val filtered: Boolean get() = from != null || has != null || before != null || after != null

    /** Whether a message passes every filter (the text itself is matched by the caller's search). */
    fun matches(senderNames: List<String>, sentAt: Long, text: String, mimes: List<String>): Boolean {
        if (from != null && senderNames.none { it.contains(from, ignoreCase = true) }) return false
        if (before != null && sentAt >= before) return false
        if (after != null && sentAt < after) return false
        return when (has) {
            null -> true
            Has.FILE -> mimes.isNotEmpty()
            Has.IMAGE -> mimes.any { it.startsWith("image/") }
            Has.VIDEO -> mimes.any { it.startsWith("video/") }
            Has.LINK -> LINK.containsMatchIn(text)
        }
    }

    companion object {
        private val LINK = Regex("https?://", RegexOption.IGNORE_CASE)
        private val DAY = Regex("""(\d{4})-(\d{2})-(\d{2})""")

        fun parse(q: String, zone: TimeZone = TimeZone.getDefault()): SearchQuery {
            var from: String? = null
            var has: Has? = null
            var before: Long? = null
            var after: Long? = null
            val words = q.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.filter { w ->
                val key = w.substringBefore(':', "").lowercase()
                val value = w.substringAfter(':', "")
                when {
                    value.isEmpty() -> true
                    key == "from" -> { from = value; false }
                    key == "has" -> Has.entries.firstOrNull { it.word == value.lowercase() }?.let { has = it; false } ?: true
                    key == "before" -> dayStart(value, zone)?.let { before = it; false } ?: true
                    key == "after" -> dayStart(value, zone)?.let { after = it; false } ?: true
                    else -> true
                }
            }
            return SearchQuery(words.joinToString(" "), from, has, before, after)
        }

        private fun dayStart(s: String, zone: TimeZone): Long? {
            val m = DAY.matchEntire(s) ?: return null
            val (y, mo, d) = m.destructured
            val cal = java.util.Calendar.getInstance(zone).apply {
                isLenient = false
                clear()
                set(y.toInt(), mo.toInt() - 1, d.toInt(), 0, 0, 0)
            }
            return runCatching { cal.timeInMillis }.getOrNull()
        }
    }
}
