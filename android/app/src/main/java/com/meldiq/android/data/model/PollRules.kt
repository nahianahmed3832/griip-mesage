package com.meldiq.android.data.model

/**
 * Polls (2026-10-19, docs/RICH_MESSAGES.md › Polls). Pure, unit-tested in `PollRulesTest`. A poll and its votes come from
 * other phones, so everything is bounded and checked; a vote counts only for options the poll really has.
 */
object PollRules {
    const val MAX_QUESTION = 300
    const val MAX_OPTION = 100
    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 10
    private val OPTION_ID = Regex("^[A-Za-z0-9_-]{1,16}$")

    /** A received or composed poll, cleaned; null when it isn't a usable poll (no question, fewer than two options). */
    fun clean(p: Poll?): Poll? {
        p ?: return null
        val question = p.question.replace(Regex("\\s+"), " ").trim().take(MAX_QUESTION)
        if (question.isEmpty()) return null
        val seen = mutableSetOf<String>()
        val options = p.options
            .filter { OPTION_ID.matches(it.id) && seen.add(it.id) }
            .map { it.copy(text = it.text.replace(Regex("\\s+"), " ").trim().take(MAX_OPTION)) }
            .filter { it.text.isNotEmpty() }
            .take(MAX_OPTIONS)
        if (options.size < MIN_OPTIONS) return null
        return Poll(question, options.map { it.copy(emoji = cleanEmoji(it.emoji)) }, p.multi, p.endsAt?.takeIf { it > 0 })
    }

    /**
     * A poll from the composer's fields: blank options dropped (with their emoji), ids "o1", "o2", … in order; voting
     * ends [durationMs] after [now] (null: when its creator closes it). Null when it isn't valid.
     */
    fun compose(question: String, options: List<String>, multi: Boolean, emojis: List<String?> = emptyList(), durationMs: Long? = null, now: Long = 0): Poll? =
        clean(
            Poll(
                question,
                options.mapIndexed { i, t -> t.trim() to emojis.getOrNull(i) }.filter { it.first.isNotEmpty() }
                    .mapIndexed { i, (t, e) -> PollOption("o${i + 1}", t, e) },
                multi, durationMs?.let { now + it },
            ),
        )

    /** Batch B8: how long voting can last, as the composer offers it (null: until closed by hand). */
    val DURATIONS: List<Long?> = listOf(3_600_000L, 4 * 3_600_000L, 8 * 3_600_000L, 24 * 3_600_000L, 3 * 24 * 3_600_000L, 7 * 24 * 3_600_000L, null)
    const val DEFAULT_DURATION_INDEX = 3

    /** One emoji (or a short sequence of them): nothing with letters or digits, at most 16 characters. */
    fun cleanEmoji(e: String?): String? = e?.trim()?.takeIf { it.isNotEmpty() && it.length <= 16 && !it.any { ch -> ch.isLetterOrDigit() } }

    /** Closed by its creator, or past its end time. */
    fun closed(rich: RichContent, now: Long): Boolean = rich.pollClosed == true || (rich.poll?.endsAt?.let { now >= it } == true)

    /**
     * [voter]'s vote applied to [rich]: their previous choices replaced by [choices] (unknown options dropped; one choice
     * unless the poll allows several; empty takes the vote back). Unchanged when there's no poll or it's closed.
     */
    fun vote(rich: RichContent, voter: String, choices: List<String>, at: Long? = null): RichContent {
        val poll = rich.poll ?: return rich
        if (rich.pollClosed == true) return rich
        // Batch B8: votes the server accepted after the poll ended don't count (every phone uses the server's time).
        if (poll.endsAt != null && at != null && at > poll.endsAt) return rich
        val valid = poll.options.map { it.id }.toSet()
        val picked = choices.filter { it in valid }.distinct().let { if (poll.multi) it else it.take(1) }
        val votes = rich.votes.orEmpty().toMutableMap()
        if (picked.isEmpty()) votes.remove(voter) else votes[voter] = picked
        return rich.copy(votes = votes)
    }

    /** Closed by its creator: only the poll's own sender can close it. */
    fun close(rich: RichContent, pollSender: String, closer: String): RichContent =
        if (rich.poll == null || pollSender != closer) rich else rich.copy(pollClosed = true)

    /** Votes per option id (every option listed, zero included), in the poll's order. */
    fun tally(rich: RichContent): List<Pair<PollOption, Int>> {
        val poll = rich.poll ?: return emptyList()
        val counts = rich.votes.orEmpty().values.flatten().groupingBy { it }.eachCount()
        return poll.options.map { it to (counts[it.id] ?: 0) }
    }

    /** How many people voted (not how many choices). */
    fun voters(rich: RichContent): Int = rich.votes.orEmpty().count { it.value.isNotEmpty() }

    /** The text older apps and notifications show. */
    fun fallbackText(p: Poll): String =
        "📊 " + p.question + "\n" + p.options.mapIndexed { i, o -> "${i + 1}. " + listOfNotNull(o.emoji, o.text).joinToString(" ") }.joinToString("\n")
}
