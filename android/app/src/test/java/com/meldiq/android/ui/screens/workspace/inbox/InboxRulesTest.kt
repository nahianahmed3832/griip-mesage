package com.meldiq.android.ui.screens.workspace.inbox

import com.meldiq.android.data.api.InboxAccountRefDto
import com.meldiq.android.data.api.InboxConversationDto
import com.meldiq.android.data.api.InboxCustomerDto
import com.meldiq.android.data.api.InboxDetailRes
import com.meldiq.android.data.api.InboxReplyDto
import com.meldiq.android.data.api.InboxReplyRuleDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 8 (docs/UNIFIED_INBOX.md): what the inbox composer offers, and how customers are labelled. */
class InboxRulesTest {
    private fun conv(provider: String = "messenger", name: String? = null, handle: String? = null) =
        InboxConversationDto(id = "c1", provider = provider, account = InboxAccountRefDto("a1", "Acme", "active"), customer = InboxCustomerDto("k1", name, handle), status = "waiting_agent")

    private fun detail(rule: InboxReplyRuleDto, canReply: Boolean = true, templates: Boolean = false) =
        InboxDetailRes(conv(), emptyList(), InboxReplyDto(rule, canReply = canReply, templates = templates, textLimit = 2000))

    @Test
    fun `the composer follows the server's reply rule`() {
        assertEquals(InboxRules.Composer.Text, InboxRules.composer(detail(InboxReplyRuleDto(true, "free"))))
        assertEquals(InboxRules.Composer.TemplateOnly, InboxRules.composer(detail(InboxReplyRuleDto(false, reason = "template_required"), templates = true)))
        assertEquals(InboxRules.Composer.Closed("window_closed"), InboxRules.composer(detail(InboxReplyRuleDto(false, reason = "window_closed"))))
        assertEquals(InboxRules.Composer.NoPermission, InboxRules.composer(detail(InboxReplyRuleDto(true, "free"), canReply = false)))
    }

    @Test
    fun `customers are shown only as the provider named them`() {
        assertEquals("Rina", InboxRules.customerLabel(conv(name = "Rina"), "Customer"))
        assertEquals("+8801700000000", InboxRules.customerLabel(conv("whatsapp", handle = "+8801700000000"), "Customer"))
        assertEquals("Customer", InboxRules.customerLabel(conv(), "Customer"))
    }

    @Test
    fun `window hours round up and vanish when closed`() {
        assertEquals(24, InboxRules.hoursLeft(1_000L + 24 * 3_600_000L, 1_000L))
        assertEquals(1, InboxRules.hoursLeft(1_000L + 60_000L, 1_000L))
        assertNull(InboxRules.hoursLeft(1_000L, 2_000L))
        assertNull(InboxRules.hoursLeft(null, 2_000L))
    }

    @Test
    fun `text limits count Instagram in bytes`() {
        assertTrue(InboxRules.fits("instagram", "é".repeat(500), 1000))
        assertFalse(InboxRules.fits("instagram", "é".repeat(501), 1000))
        assertTrue(InboxRules.fits("messenger", "é".repeat(2000), 2000))
    }

    @Test
    fun `tags are cleaned`() {
        assertEquals(listOf("vip", "shipping"), InboxRules.parseTags(" VIP, shipping, ,vip "))
        assertEquals(10, InboxRules.parseTags((1..20).joinToString(",") { "t$it" }).size)
    }

    @Test
    fun `durations read naturally`() {
        assertEquals("5m", InboxRules.minutesLabel(5 * 60_000L))
        assertEquals("2h 5m", InboxRules.minutesLabel(125 * 60_000L))
        assertNull(InboxRules.minutesLabel(null))
    }
}
