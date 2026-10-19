package com.meldiq.android.data.model

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** How a reminder repeats (the server's names). */
enum class Recurrence(val wire: String) {
    DAILY("daily"), WEEKDAYS("weekdays"), WEEKLY("weekly"), MONTHLY("monthly");

    companion object {
        fun of(wire: String?): Recurrence? = entries.firstOrNull { it.wire == wire }
    }
}

/** What a reminder points at, if anything. */
@Serializable
data class ReminderRef(val kind: String, val conversationId: String? = null, val messageId: String? = null)

/**
 * What a reminder says: sealed on the phone before it is sent (crypto/ReminderCrypto.kt), so the server never sees it.
 * [source] is "personal" (made on the Reminders screen or from a message) or "later" (a saved message's reminder).
 */
@Serializable
data class ReminderContent(val v: Int = 1, val title: String, val note: String? = null, val ref: ReminderRef? = null, val source: String = "personal")

enum class ReminderState(val wire: String) {
    SCHEDULED("scheduled"), FIRED("fired"), DONE("done"), DISMISSED("dismissed");

    companion object {
        fun of(wire: String): ReminderState = entries.firstOrNull { it.wire == wire } ?: SCHEDULED
    }
}

/** A reminder as the app shows it: decrypted content (null when this device can't read it) and the server's schedule. */
data class Reminder(
    val id: String,
    val content: ReminderContent?,
    val dueAt: Long,
    val timeZone: String,
    val recurrence: Recurrence?,
    val localTime: String?,
    val anchorDate: String?,
    val state: ReminderState,
    val firedAt: Long?,
    val deliveryFailed: Boolean,
    val rev: Int,
    val completedAt: Long?,
)

/** Rules behind the Reminders screen and the "remind me" choices (pure; unit-tested in `ReminderRulesTest`). */
object ReminderRules {
    const val TITLE_MAX = 200
    const val NOTE_MAX = 1000

    /** The three lists: still to come, gone off and not dealt with, finished. */
    data class Groups(val upcoming: List<Reminder>, val overdue: List<Reminder>, val done: List<Reminder>)

    fun group(all: List<Reminder>): Groups = Groups(
        upcoming = all.filter { it.state == ReminderState.SCHEDULED }.sortedBy { it.dueAt },
        overdue = all.filter { it.state == ReminderState.FIRED }.sortedByDescending { it.firedAt ?: it.dueAt },
        done = all.filter { it.state == ReminderState.DONE || it.state == ReminderState.DISMISSED }.sortedByDescending { it.completedAt ?: it.dueAt },
    )

    /** Snooze choices: 10 minutes, 1 hour, this evening (18:00, if still ahead), tomorrow 09:00. */
    enum class Snooze { MINUTES_10, HOUR_1, EVENING, TOMORROW }

    fun snoozeUntil(choice: Snooze, now: Long, zone: ZoneId): Long {
        val local = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        return when (choice) {
            Snooze.MINUTES_10 -> now + 10 * 60_000
            Snooze.HOUR_1 -> now + 60 * 60_000
            Snooze.EVENING -> local.toLocalDate().atTime(EVENING).atZone(zone).toInstant().toEpochMilli().let { if (it > now) it else local.toLocalDate().plusDays(1).atTime(EVENING).atZone(zone).toInstant().toEpochMilli() }
            Snooze.TOMORROW -> local.toLocalDate().plusDays(1).atTime(MORNING).atZone(zone).toInstant().toEpochMilli()
        }
    }

    /** The local wall-clock time and first date a repeating reminder is sent with ("HH:MM", "YYYY-MM-DD"). */
    fun repeatRule(dueAt: Long, zone: ZoneId): Pair<String, String> {
        val local = ZonedDateTime.ofInstant(Instant.ofEpochMilli(dueAt), zone)
        return "%02d:%02d".format(local.hour, local.minute) to local.toLocalDate().toString()
    }

    /** The first time a new reminder can be set for: rounded up to the next quarter hour, at least 5 minutes ahead. */
    fun defaultTime(now: Long, zone: ZoneId): Long {
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now + 5 * 60_000), zone).withSecond(0).withNano(0)
        val add = (15 - t.minute % 15) % 15
        return t.plusMinutes(add.toLong()).toInstant().toEpochMilli()
    }

    /** Combines a picked date and time in [zone]. */
    fun at(date: LocalDate, time: LocalTime, zone: ZoneId): Long = date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    fun validTitle(title: String): Boolean = title.isNotBlank() && title.length <= TITLE_MAX

    /**
     * The id of a saved message's reminder: the same on every device of the person (derived from the message id), so
     * two phones bridging the same saved item make one reminder, not two.
     */
    fun laterId(messageId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("later|$messageId".toByteArray())
        return "later_" + digest.take(16).joinToString("") { "%02x".format(it) }
    }

    /** A new personal reminder's id (random, URL-safe). */
    fun newId(): String = "rem_" + java.util.UUID.randomUUID().toString().replace("-", "")

    /** Whether a weekday reminder would go off on [day]. */
    fun isWeekday(day: DayOfWeek): Boolean = day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY

    private val EVENING = LocalTime.of(18, 0)
    private val MORNING = LocalTime.of(9, 0)
}
