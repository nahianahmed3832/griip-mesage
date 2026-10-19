package com.meldiq.android.data.model

import com.meldiq.android.crypto.ReminderKeys
import com.meldiq.android.data.account.RecoveryRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Reminders and recovery codes on the phone (round 6): lists, snoozes, ids, sealing, and what people type. */
class ReminderRulesTest {
    private val dhaka = ZoneId.of("Asia/Dhaka")

    private fun reminder(id: String, state: ReminderState, dueAt: Long, firedAt: Long? = null, completedAt: Long? = null) =
        Reminder(id, ReminderContent(title = id), dueAt, "Asia/Dhaka", null, null, null, state, firedAt, false, 1, completedAt)

    private fun local(ms: Long) = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), dhaka)

    @Test
    fun `groups reminders into upcoming, overdue and done, each in a useful order`() {
        val g = ReminderRules.group(
            listOf(
                reminder("later", ReminderState.SCHEDULED, 300),
                reminder("soon", ReminderState.SCHEDULED, 100),
                reminder("old", ReminderState.FIRED, 50, firedAt = 60),
                reminder("recent", ReminderState.FIRED, 70, firedAt = 80),
                reminder("finished", ReminderState.DONE, 10, completedAt = 20),
                reminder("stopped", ReminderState.DISMISSED, 11, completedAt = 30),
            ),
        )
        assertEquals(listOf("soon", "later"), g.upcoming.map { it.id })
        assertEquals(listOf("recent", "old"), g.overdue.map { it.id })
        assertEquals(listOf("stopped", "finished"), g.done.map { it.id })
    }

    @Test
    fun `snoozes land on the expected local times`() {
        val afternoon = ZonedDateTime.of(2026, 10, 19, 15, 20, 0, 0, dhaka).toInstant().toEpochMilli()
        assertEquals(afternoon + 600_000, ReminderRules.snoozeUntil(ReminderRules.Snooze.MINUTES_10, afternoon, dhaka))
        assertEquals(afternoon + 3_600_000, ReminderRules.snoozeUntil(ReminderRules.Snooze.HOUR_1, afternoon, dhaka))
        local(ReminderRules.snoozeUntil(ReminderRules.Snooze.EVENING, afternoon, dhaka)).let { assertEquals(listOf(19, 18, 0), listOf(it.dayOfMonth, it.hour, it.minute)) }
        local(ReminderRules.snoozeUntil(ReminderRules.Snooze.TOMORROW, afternoon, dhaka)).let { assertEquals(listOf(20, 9, 0), listOf(it.dayOfMonth, it.hour, it.minute)) }
        // After 18:00, "this evening" is tomorrow evening rather than a time that has passed.
        val night = ZonedDateTime.of(2026, 10, 19, 21, 0, 0, 0, dhaka).toInstant().toEpochMilli()
        local(ReminderRules.snoozeUntil(ReminderRules.Snooze.EVENING, night, dhaka)).let { assertEquals(listOf(20, 18), listOf(it.dayOfMonth, it.hour)) }
    }

    @Test
    fun `a new reminder starts on the next quarter hour, at least five minutes ahead`() {
        val t = ZonedDateTime.of(2026, 10, 19, 10, 7, 30, 0, dhaka).toInstant().toEpochMilli()
        local(ReminderRules.defaultTime(t, dhaka)).let { assertEquals(listOf(10, 15, 0), listOf(it.hour, it.minute, it.second)) }
        val close = ZonedDateTime.of(2026, 10, 19, 10, 12, 0, 0, dhaka).toInstant().toEpochMilli()
        local(ReminderRules.defaultTime(close, dhaka)).let { assertEquals(listOf(10, 30), listOf(it.hour, it.minute)) }
    }

    @Test
    fun `repeating reminders are sent with their local time and first date`() {
        val t = ZonedDateTime.of(2026, 10, 21, 9, 5, 0, 0, dhaka).toInstant().toEpochMilli()
        assertEquals("09:05" to "2026-10-21", ReminderRules.repeatRule(t, dhaka))
    }

    @Test
    fun `a saved message's reminder id is the same on every device and fits the server's rules`() {
        val a = ReminderRules.laterId("m_123")
        assertEquals(a, ReminderRules.laterId("m_123"))
        assertNotEquals(a, ReminderRules.laterId("m_124"))
        assertTrue(a.matches(Regex("^[A-Za-z0-9_-]{8,64}$")))
        assertTrue(ReminderRules.newId().matches(Regex("^[A-Za-z0-9_-]{8,64}$")))
        assertNotEquals(ReminderRules.newId(), ReminderRules.newId())
    }

    @Test
    fun `reminder content is sealed to the account and the reminder id`() {
        val seed = ByteArray(32) { it.toByte() }
        val keys = ReminderKeys(seed, "u_alice")
        val sealed = keys.seal("rem_1", "Send the report".toByteArray())
        assertTrue("url-safe, as the server requires", sealed.matches(Regex("^[A-Za-z0-9_-]+$")))
        assertFalse(sealed.contains("report"))
        assertEquals("Send the report", keys.open("rem_1", sealed).decodeToString())
        // Moved to another reminder, or read by another account: refused.
        assertThrows(Exception::class.java) { keys.open("rem_2", sealed) }
        assertThrows(Exception::class.java) { ReminderKeys(seed, "u_bob").open("rem_1", sealed) }
        // The largest content the app makes still fits the server's limit.
        val biggest = """{"v":1,"title":"${"x".repeat(ReminderRules.TITLE_MAX)}","note":"${"y".repeat(ReminderRules.NOTE_MAX)}","ref":{"kind":"message","conversationId":"${"c".repeat(64)}","messageId":"${"m".repeat(64)}"},"source":"personal"}"""
        assertTrue(keys.seal("rem_1", biggest.toByteArray()).length <= 6000)
    }

    @Test
    fun `recovery codes are read the way the server reads them`() {
        assertEquals("ABCDEFGHJKLM", RecoveryRules.normalize(" abcd-efgh jklm "))
        assertTrue(RecoveryRules.isCode("abcd-efgh-jklm"))
        assertFalse(RecoveryRules.isCode("abcd-efgh"))
        assertEquals("ABCD-EFGH-JK", RecoveryRules.display("abcdefghjk"))
        assertEquals("ABCD-EFGH-JKLM", RecoveryRules.display("abcdefghjklmnop"))
    }

    @Test
    fun `the code field's dashes are only drawn, so typing one character at a time keeps the order`() {
        // Phone test: re-inserting dashes into the field's text moved the cursor and scrambled typed codes.
        // Raw "ABCDEFGHJKLM" is drawn as "ABCD-EFGH-JKLM": the cursor after raw char n sits after display char n + dashes.
        assertEquals(listOf(0, 1, 4, 6, 8, 9, 11, 14), listOf(0, 1, 4, 5, 7, 8, 9, 12).map { RecoveryRules.toDisplayOffset(it) })
        assertEquals(listOf(0, 4, 4, 5, 8, 9, 12), listOf(0, 4, 5, 6, 10, 11, 14).map { RecoveryRules.toRawOffset(it, 12) })
        for (raw in 0..12) assertEquals(raw, RecoveryRules.toRawOffset(RecoveryRules.toDisplayOffset(raw), 12))
    }
}
