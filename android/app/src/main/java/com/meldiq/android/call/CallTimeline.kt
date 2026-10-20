package com.meldiq.android.call

/**
 * How long each stage of setting up a call takes (round 7, bug 7), so changes to the setup are measured, not guessed.
 * Only stage names and milliseconds since the start are kept: no names, ids, numbers or addresses. Each stage counts
 * once (the first time it happens); unit-tested in `CallTimelineTest`.
 *
 * Caller: tap › call created › key sent › engine ready › media joined › mic on › peer joined › remote audio.
 * Callee: ring › accepted › join granted › key ready › engine ready › media joined › mic on › remote audio.
 */
class CallTimeline(private val clock: () -> Long = System::currentTimeMillis) {
    private var start = 0L
    private var role = ""
    private var marks: List<Pair<String, Long>> = emptyList()

    fun begin(role: String) {
        this.role = role
        start = clock()
        marks = emptyList()
    }

    /** Records [stage] once; returns its time in ms since [begin], or null before [begin] or when already recorded. */
    fun mark(stage: String): Long? {
        if (start == 0L || marks.any { it.first == stage }) return null
        val t = clock() - start
        marks = marks + (stage to t)
        return t
    }

    fun elapsed(stage: String): Long? = marks.firstOrNull { it.first == stage }?.second

    fun summary(): String = "call timing ($role): " + marks.joinToString(" › ") { (s, t) -> "$s ${t} ms" }

    fun clear() {
        start = 0L
        marks = emptyList()
    }
}
