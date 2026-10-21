package com.meldiq.android.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForumRulesTest {
    private data class M(val id: String, val ts: Long, val deleted: Boolean = false)

    @Test
    fun `titles are one bounded line`() {
        assertEquals("Best telescope for beginners?", ForumRules.cleanTitle("  Best telescope\n for   beginners?  "))
        assertNull(ForumRules.cleanTitle("   "))
        assertEquals(ForumRules.MAX_TITLE, ForumRules.cleanTitle("x".repeat(500))!!.length)
    }

    @Test
    fun `a post's text starts with its title so apps without forums still show it`() {
        val text = ForumRules.text("Meetup on Friday", "Who's coming?")
        assertEquals("Meetup on Friday\n\nWho's coming?", text)
        assertEquals("Who's coming?", ForumRules.body("Meetup on Friday", text))
        assertEquals("Only a title", ForumRules.text("Only a title", "  "))
        // A message from before the channel was a forum: its first line stands in for a title.
        assertEquals("first line", ForumRules.titleOf(null, "first line\nmore"))
        assertEquals("Sent title", ForumRules.titleOf("Sent title", "whatever"))
    }

    @Test
    fun `posts sort by latest activity or newest, with their reply counts`() {
        val roots = listOf(M("a", 100), M("b", 200), M("gone", 300, deleted = true))
        val replies = mapOf("a" to ForumRules.Replies(3, 900))
        val latest = ForumRules.posts(roots, replies, ForumRules.Sort.LATEST_ACTIVITY, id = { it.id }, time = { it.ts }, visible = { !it.deleted })
        assertEquals(listOf("a", "b"), latest.map { it.root.id })
        assertEquals(listOf(3, 0), latest.map { it.replies })
        assertEquals(900L, latest.first().lastActivity)
        val newest = ForumRules.posts(roots, replies, ForumRules.Sort.NEWEST, id = { it.id }, time = { it.ts }, visible = { !it.deleted })
        assertEquals(listOf("b", "a"), newest.map { it.root.id })
    }

    @Test
    fun `a post's tags are well-formed, distinct and at most five`() {
        assertEquals(listOf("tag_a", "tag_b"), ForumRules.cleanTags(listOf("tag_a", "tag_a", "bad id!", "tag_b")))
        assertEquals(ForumRules.MAX_TAGS_PER_POST, ForumRules.cleanTags((1..9).map { "t$it" }).size)
        assertEquals(emptyList<String>(), ForumRules.cleanTags(null))
    }

    @Test
    fun `a forum's tag names are trimmed, bounded and not repeated`() {
        assertEquals(listOf("Bug", "Idea"), ForumRules.cleanTagNames(listOf("  Bug ", "Idea", "bug", " ")))
        assertEquals(ForumRules.MAX_TAG_NAME, ForumRules.cleanTagNames(listOf("x".repeat(40))).single().length)
    }

    @Test
    fun `search and tag filters run over this phone's posts`() {
        data class P(val title: String, val body: String, val tags: List<String>)
        val posts = listOf(P("Lens advice", "which one?", listOf("q")), P("Star party", "Friday night", listOf("event")), P("Untagged", "", emptyList()))
            .map { ForumRules.Post(it, 0, 0L) }
        fun run(q: String, sel: Set<String>) = ForumRules.filter(posts, q, sel, { it.title }, { it.body }, { it.tags }).map { it.root.title }
        assertEquals(listOf("Lens advice", "Star party", "Untagged"), run("", emptySet()))
        assertEquals(listOf("Star party"), run("FRIDAY", emptySet()))
        assertEquals(listOf("Lens advice", "Star party"), run("", setOf("q", "event")))
        assertEquals(emptyList<String>(), run("lens", setOf("event")))
    }
}
