package com.meldiq.android.ui.screens.workspace.ai

import com.meldiq.android.data.api.WfInput
import com.meldiq.android.data.api.WfTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 7 slice C8 (docs/AI.md): what a catch-up sends, and the hand-over of an AI workflow draft. */
class AiRulesTest {
    private fun line(i: Int, text: String = "message $i", readable: Boolean = true) = AiRules.Line("Ana", text, i.toLong(), readable)

    @Test
    fun `catch-up keeps the newest readable messages, oldest first`() {
        val lines = listOf(line(1), line(2, readable = false), line(3, "  "), line(4))
        val picked = AiRules.catchUp(lines)
        assertEquals(listOf("message 1", "message 4"), picked.map { it.text })
        assertEquals(listOf(1L, 4L), picked.map { it.at })
    }

    @Test
    fun `catch-up stops at the message count`() {
        val picked = AiRules.catchUp((1..400).map { line(it) })
        assertEquals(AiRules.CATCH_UP_MAX, picked.size)
        assertEquals("message 400", picked.last().text)
        assertEquals("message ${400 - AiRules.CATCH_UP_MAX + 1}", picked.first().text)
    }

    @Test
    fun `catch-up stays within the text budget and cuts very long messages`() {
        val long = "x".repeat(10_000)
        val picked = AiRules.catchUp((1..100).map { line(it, long) })
        assertTrue(picked.all { it.text.length == AiRules.CATCH_UP_MESSAGE_CHARS })
        assertTrue(picked.sumOf { it.text.length + it.author.length + 2 } <= AiRules.CATCH_UP_CHARS)
        assertEquals(100L, picked.last().at)
    }

    @Test
    fun `a draft is handed over once, switched off and unsaved`() {
        AiRules.DraftInbox.put(WfInput("Welcome", WfTrigger("member.joined"), actions = emptyList(), enabled = true, rev = 4))
        val taken = AiRules.DraftInbox.take()
        assertEquals("Welcome", taken?.name)
        assertFalse(taken?.enabled ?: true)
        assertNull(taken?.rev)
        assertNull(AiRules.DraftInbox.take())
    }

    @Test
    fun `month limit label`() {
        assertEquals("12", AiRules.monthLabelLimit(12, "no limit"))
        assertEquals("no limit", AiRules.monthLabelLimit(null, "no limit"))
    }
}
