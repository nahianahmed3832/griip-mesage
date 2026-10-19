package com.griipbuddy.orbit.data.model

import com.griipbuddy.orbit.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Thread replies on the Activity tab: grouped per thread, unseen first, seen marks never go backwards. */
class ThreadActivityRulesTest {
    private fun reply(id: String, root: String, ts: Long, conv: String = "c1") = MessageEntity(
        id = id, conversationId = conv, senderUserId = "u_other", senderDeviceId = "d", outgoing = false, kind = "text", text = id,
        threadRootId = root, sentAt = ts, sortTs = ts, status = "RECEIVED",
    )

    @Test
    fun `threads with unseen replies come first, each with its newest reply`() {
        val replies = listOf(reply("r5", "a", 50), reply("r4", "b", 40), reply("r3", "a", 30), reply("r2", "c", 20), reply("r1", "b", 10))
        val seen = mapOf("a" to 50L, "b" to 10L)
        val out = ThreadActivityRules.summarize(replies, seen)
        assertEquals(listOf("b", "c", "a"), out.map { it.rootId })
        assertEquals(listOf(1, 1, 0), out.map { it.unseen })
        assertEquals("r4", out[0].latest.id)
        assertEquals(2, ThreadActivityRules.unseenTotal(out))
    }

    @Test
    fun `a seen mark only moves forward and the list stays bounded`() {
        var seen = ThreadActivityRules.markSeen(emptyMap(), "a", 50)
        seen = ThreadActivityRules.markSeen(seen, "a", 20)
        assertEquals(50L, seen["a"])
        val many = (1..10).fold(emptyMap<String, Long>()) { m, i -> ThreadActivityRules.markSeen(m, "t$i", i.toLong(), max = 5) }
        assertEquals(5, many.size)
        assertTrue("the newest are kept", many.keys.containsAll(listOf("t6", "t7", "t8", "t9", "t10")))
    }

    @Test
    fun `messages outside a thread are ignored`() {
        val stray = reply("x", "a", 5).copy(threadRootId = null)
        assertTrue(ThreadActivityRules.summarize(listOf(stray), emptyMap()).isEmpty())
    }
}
