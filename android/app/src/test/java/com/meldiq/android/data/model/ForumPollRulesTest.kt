package com.meldiq.android.data.model

import com.meldiq.android.data.api.GriipJson
import com.meldiq.android.data.repo.FollowRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batch B8: poll end times and answer emoji, forum tag matching, post-card reactions, guidelines, following threads. */
class ForumPollRulesTest {
    @Test
    fun `a composed poll keeps each answer's emoji, drops blank answers with theirs, and ends after its duration`() {
        val p = PollRules.compose("Lunch?", listOf("Pizza", " ", "Rice"), multi = false, emojis = listOf("🍕", "🙂", null), durationMs = 3_600_000, now = 1000)!!
        assertEquals(listOf("🍕" to "Pizza", null to "Rice"), p.options.map { it.emoji to it.text })
        assertEquals(listOf("o1", "o2"), p.options.map { it.id })
        assertEquals(3_601_000L, p.endsAt)
        assertNull(PollRules.compose("Q", listOf("a", "b"), false).let { it?.endsAt })
        assertEquals("📊 Lunch?\n1. 🍕 Pizza\n2. Rice", PollRules.fallbackText(p))
    }

    @Test
    fun `answer emoji can't carry letters or digits, or be long`() {
        assertEquals("🎉", PollRules.cleanEmoji(" 🎉 "))
        assertNull(PollRules.cleanEmoji("A"))
        assertNull(PollRules.cleanEmoji("🎉1"))
        assertNull(PollRules.cleanEmoji("🎉".repeat(10)))
        assertNull(PollRules.cleanEmoji(""))
    }

    @Test
    fun `a poll closes at its end time, and votes the server accepted after it don't count`() {
        val rich = RichContent(poll = Poll("Q", listOf(PollOption("o1", "a"), PollOption("o2", "b")), endsAt = 5000))
        assertFalse(PollRules.closed(rich, now = 4999))
        assertTrue(PollRules.closed(rich, now = 5000))
        assertTrue(PollRules.closed(rich.copy(pollClosed = true), now = 0))
        assertEquals(listOf("o1"), PollRules.vote(rich, "u1", listOf("o1"), at = 4000).votes?.get("u1"))
        assertEquals(rich, PollRules.vote(rich, "u1", listOf("o1"), at = 6000))
    }

    @Test
    fun `polls from older apps read without the new fields, and new ones keep them`() {
        val old = GriipJson.decodeFromString(Poll.serializer(), """{"question":"Q","options":[{"id":"o1","text":"a"},{"id":"o2","text":"b"}]}""")
        assertNull(old.endsAt)
        assertNull(old.options.first().emoji)
        val cleaned = PollRules.clean(Poll("Q", listOf(PollOption("o1", "a", "x"), PollOption("o2", "b", "👍")), endsAt = -5))!!
        assertEquals(listOf(null, "👍"), cleaned.options.map { it.emoji })
        assertNull(cleaned.endsAt)
    }

    private data class P(val id: String, val tags: List<String>)

    @Test
    fun `tag matching finds posts with any or with all of the chosen tags`() {
        val posts = listOf(P("a", listOf("t1")), P("b", listOf("t1", "t2")), P("c", listOf("t2"))).map { ForumRules.Post(it, 0, 0L) }
        val any = ForumRules.filter(posts, "", setOf("t1", "t2"), { it.id }, { "" }, { it.tags })
        val all = ForumRules.filter(posts, "", setOf("t1", "t2"), { it.id }, { "" }, { it.tags }, matchAll = true)
        assertEquals(listOf("a", "b", "c"), any.map { it.root.id })
        assertEquals(listOf("b"), all.map { it.root.id })
    }

    @Test
    fun `a post card shows its most-used reaction, counted by people`() {
        assertEquals("👍" to 2, ForumRules.topReaction(mapOf("🎉" to listOf("u1"), "👍" to listOf("u1", "u2", "u2"))))
        assertEquals("🎉" to 1, ForumRules.topReaction(mapOf("🎉" to listOf("u1"), "😀" to listOf("u2"))))
        assertNull(ForumRules.topReaction(mapOf("👍" to emptyList())))
    }

    @Test
    fun `guidelines collapse to their first lines`() {
        assertEquals("a\nb\nc" to true, ForumRules.guidelinesPreview("a\nb\nc\nd"))
        assertEquals("one" to false, ForumRules.guidelinesPreview("  one  "))
    }

    @Test
    fun `following puts the thread first, unfollowing removes it, and the list is bounded`() {
        assertEquals(listOf("b", "a"), FollowRules.toggle(listOf("a", "b"), "b", follow = true))
        assertEquals(listOf("a"), FollowRules.toggle(listOf("a", "b"), "b", follow = false))
        assertEquals(listOf("z", "a"), FollowRules.toggle(listOf("a", "b"), "z", follow = true, max = 2))
    }
}
