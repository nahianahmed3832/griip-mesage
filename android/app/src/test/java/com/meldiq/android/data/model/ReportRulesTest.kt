package com.meldiq.android.data.model

import com.meldiq.android.data.api.GriipJson
import com.meldiq.android.data.api.ReportReq
import com.meldiq.android.ui.screens.workspace.AuditFilters
import com.meldiq.android.ui.screens.workspace.InviteChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batch B4: what a report shares and where it goes; Security actions; audit filters; invite choices. */
class ReportRulesTest {
    private fun line(id: String, at: Long, text: String = "m$id", sender: String = "u1") = ReportRules.Line(id, sender, text, at)

    @Test
    fun `a message report shares the message and up to five messages before it, oldest first`() {
        val convo = (1..9).map { line("$it", it * 1000L) }
        val c = ReportRules.content(line("8", 8000), convo.shuffled())
        assertEquals("m8", c.text)
        assertEquals(8000L, c.sentAt)
        assertEquals(listOf("m3", "m4", "m5", "m6", "m7"), c.context?.map { it.text })
        assertNull(c.attachments)
    }

    @Test
    fun `the first message has no context, empty lines are skipped, and long text is cut to the server's limit`() {
        val convo = listOf(line("1", 1, text = ""), line("2", 2, text = "x".repeat(5000)))
        val c = ReportRules.content(convo[1], convo)
        assertNull(c.context)
        assertEquals(4000, c.text?.length)
        assertNull(ReportRules.content(convo[0], convo).text)
    }

    @Test
    fun `channel reports go to the community, DMs and illegal content to Meldiq`() {
        assertTrue(ReportRules.goesToCommunity("spam", "CHANNEL", false))
        assertFalse(ReportRules.goesToCommunity("spam", "DM", true))
        assertFalse(ReportRules.goesToCommunity("illegal", "CHANNEL", true))
        assertTrue(ReportRules.goesToCommunity("harassment", null, personInCommunity = true))
        assertFalse(ReportRules.goesToCommunity("harassment", null, personInCommunity = false))
        assertEquals(listOf("spam", "harassment", "harmful", "private_info", "illegal", "other"), ReportRules.REASONS)
    }

    @Test
    fun `a report request leaves out what isn't set`() {
        val json = GriipJson.encodeToString(ReportReq.serializer(), ReportReq("u2", "spam"))
        assertEquals("""{"targetUserId":"u2","reason":"spam"}""", json)
    }

    @Test
    fun `security pauses run until their time and offer 1 hour to 7 days`() {
        assertTrue(SecurityRules.running(2000, now = 1000))
        assertFalse(SecurityRules.running(1000, now = 1000))
        assertFalse(SecurityRules.running(null))
        assertEquals(7 * 24 * 3_600_000L, SecurityRules.DURATIONS.last())
    }

    @Test
    fun `audit filters and invite choices stay within what the server accepts`() {
        assertNull(AuditFilters.GROUPS.first().first)
        assertTrue(AuditFilters.GROUPS.drop(1).all { (k, _) -> k!!.matches(Regex("^[a-z_.]{1,40}$")) })
        assertTrue(InviteChoices.USES.all { it in 1..500 })
        assertTrue(InviteChoices.HOURS.all { (h, _) -> h in 1..720 })
    }
}
