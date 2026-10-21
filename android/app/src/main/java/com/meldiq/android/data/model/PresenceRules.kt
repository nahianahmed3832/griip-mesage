package com.meldiq.android.data.model

/**
 * Batch B6b (Discord reference NAV-CTRL-010): the presence a person chooses and how others see it. Invisible people
 * appear offline (the server says so); idle and do not disturb show as such to the people who may see presence.
 */
object PresenceRules {
    const val AUTO = "auto"
    const val IDLE = "idle"
    const val DND = "dnd"
    const val INVISIBLE = "invisible"
    val MODES = listOf(AUTO, IDLE, DND, INVISIBLE)

    /** Ends a choice can have (null: until you change it). The server allows at most 30 days. */
    val DURATIONS: List<Long?> = listOf(3_600_000L, 8 * 3_600_000L, 24 * 3_600_000L, 7 * 24 * 3_600_000L, null)
    const val PAUSE_MAX_MS = 30L * 24 * 3_600_000

    /** What a user row keeps from the server's "online/idle/dnd/offline": idle and dnd only (the rest is [online]). */
    fun stored(presence: String?): String? = presence?.takeIf { it == IDLE || it == DND }

    /** Do not disturb pauses notifications as long as it lasts; "until changed" pauses for the longest the server allows. */
    fun pauseUntil(until: Long?, now: Long): Long = until ?: (now + PAUSE_MAX_MS)

    /** The choice still running: a timed one that has ended is automatic again. */
    fun current(mode: String?, until: Long?, now: Long): String =
        if (mode == null || mode == AUTO || (until != null && until <= now)) AUTO else mode
}
