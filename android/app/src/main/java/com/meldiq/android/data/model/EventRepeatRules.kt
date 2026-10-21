package com.meldiq.android.data.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/** Batch B8b: how a community event can repeat (the server's `meetings.repeat`), and what the choices say. */
object EventRepeatRules {
    const val NONE = "none"
    const val WEEKLY = "weekly"
    const val BIWEEKLY = "biweekly"
    const val MONTHLY = "monthly"
    const val YEARLY = "yearly"
    val CHOICES = listOf(NONE, WEEKLY, BIWEEKLY, MONTHLY, YEARLY)

    /** The start's weekday and which one of the month it is (1–5), in [zone]: "the third Wednesday". */
    fun weekdayAndNth(startMs: Long, zone: String): Pair<DayOfWeek, Int>? = runCatching {
        val d = Instant.ofEpochMilli(startMs).atZone(ZoneId.of(zone)).toLocalDate()
        d.dayOfWeek to ((d.dayOfMonth - 1) / 7 + 1)
    }.getOrNull()
}
