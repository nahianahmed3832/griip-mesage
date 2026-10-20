package com.meldiq.android.ui.screens.workspace.meetings

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Meeting editor rules (round 7 slice C5, docs/WORKSPACE_C5.md); pure, unit-tested in `MeetingRulesTest`. Times are
 * entered in the person's own time zone and sent as UTC milliseconds with that zone's name (DST-correct).
 */
object MeetingRules {
    val DURATIONS = listOf(15, 30, 45, 60, 90)
    private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    /** UTC ms for [date] "YYYY-MM-DD" at [time] "HH:MM" in [zone], or null when either doesn't parse. */
    fun startMs(date: String, time: String, zone: String): Long? = runCatching {
        if (!DATE.matches(date) || !TIME.matches(time)) return null
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), ZoneId.of(zone)).toInstant().toEpochMilli()
    }.getOrNull()

    /** The next half hour from [nowMs] in [zone], as ("YYYY-MM-DD", "HH:MM"). */
    fun nextSlot(nowMs: Long, zone: String): Pair<String, String> {
        val z = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZoneId.of(zone))
        val next = z.withSecond(0).withNano(0).plusMinutes((30 - z.minute % 30).toLong())
        return next.toLocalDate().toString() to next.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
    }

    /** ("YYYY-MM-DD", "HH:MM", minutes) for an existing meeting, in [zone]. */
    fun fields(startsAt: Long, endsAt: Long, zone: String): Triple<String, String, Int> {
        val z = ZonedDateTime.ofInstant(Instant.ofEpochMilli(startsAt), ZoneId.of(zone))
        return Triple(z.toLocalDate().toString(), z.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")), ((endsAt - startsAt) / 60_000).toInt())
    }

    /** What's missing before Save, in order; empty when the server can be asked. */
    fun problems(title: String, roomId: String?, date: String, time: String, zone: String, nowMs: Long): List<String> = buildList {
        if (title.isBlank()) add("Give the meeting a title")
        if (roomId == null) add("Pick a voice room")
        val start = startMs(date, time, zone)
        if (start == null) add("Set the date as YYYY-MM-DD and the time as HH:MM")
        else if (start < nowMs - 5 * 60_000) add("Pick a time that hasn't passed yet")
    }

    /** "Mon 20 Oct, 14:30–15:00" in [zone]. */
    fun whenText(startsAt: Long, endsAt: Long, zone: String): String {
        val z = ZoneId.of(zone)
        val s = ZonedDateTime.ofInstant(Instant.ofEpochMilli(startsAt), z)
        val e = ZonedDateTime.ofInstant(Instant.ofEpochMilli(endsAt), z)
        val day = DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.getDefault())
        val hm = DateTimeFormatter.ofPattern("HH:mm")
        return "${s.format(day)}, ${s.format(hm)}–${e.format(hm)}"
    }
}
