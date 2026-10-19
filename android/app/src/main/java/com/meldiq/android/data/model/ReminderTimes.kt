package com.meldiq.android.data.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** Reminder choices for a saved message (Home › Later), in the phone's time zone. Pure, unit-tested in `ReminderTimesTest`. */
object ReminderTimes {
    enum class Choice { IN_1_HOUR, IN_3_HOURS, TOMORROW_MORNING, NEXT_WEEK }

    private const val HOUR_MS = 3_600_000L
    private val MORNING: LocalTime = LocalTime.of(9, 0)

    /** When [choice] falls, counted from [now]. */
    fun at(choice: Choice, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val today = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        return when (choice) {
            Choice.IN_1_HOUR -> now + HOUR_MS
            Choice.IN_3_HOURS -> now + 3 * HOUR_MS
            Choice.TOMORROW_MORNING -> today.toLocalDate().plusDays(1).atTime(MORNING).atZone(zone).toInstant().toEpochMilli()
            Choice.NEXT_WEEK -> today.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atTime(MORNING).atZone(zone).toInstant().toEpochMilli()
        }
    }

    /** A reminder whose time has passed and that's still open. */
    fun overdue(remindAt: Long?, done: Boolean, now: Long): Boolean = !done && remindAt != null && remindAt <= now
}
