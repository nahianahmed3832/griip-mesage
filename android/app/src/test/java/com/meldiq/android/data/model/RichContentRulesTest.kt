package com.meldiq.android.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Polls, stickers and media links (2026-10-19): what's accepted from other phones and how votes count. */
class RichContentRulesTest {
    private val poll = Poll("Lunch?", listOf(PollOption("o1", "Pizza"), PollOption("o2", "Sushi"), PollOption("o3", "Salad")))

    @Test
    fun `polls are bounded and need a question and two options`() {
        assertNull(PollRules.clean(Poll("  ", poll.options)))
        assertNull(PollRules.clean(Poll("Q", listOf(PollOption("o1", "Only one")))))
        // Duplicate ids, blank texts and malformed ids are dropped; whitespace collapses.
        val cleaned = PollRules.clean(Poll("  Lunch   today? ", listOf(PollOption("o1", " A "), PollOption("o1", "dupe"), PollOption("o2", "  "), PollOption("bad id!", "x"), PollOption("o3", "B"))))!!
        assertEquals("Lunch today?", cleaned.question)
        assertEquals(listOf("o1" to "A", "o3" to "B"), cleaned.options.map { it.id to it.text })
        val many = PollRules.clean(Poll("Q", (1..20).map { PollOption("o$it", "Option $it") }))!!
        assertEquals(PollRules.MAX_OPTIONS, many.options.size)
        assertEquals(listOf("o1", "o2"), PollRules.compose("Q", listOf("A", " ", "B"), multi = false)!!.options.map { it.id })
    }

    @Test
    fun `a vote replaces the voter's previous one and counts only real options`() {
        var r = RichContent(poll = poll)
        r = PollRules.vote(r, "ann", listOf("o1"))
        r = PollRules.vote(r, "bob", listOf("o2", "o3")) // single choice: only the first counts
        r = PollRules.vote(r, "cat", listOf("nope"))
        assertEquals(mapOf("ann" to listOf("o1"), "bob" to listOf("o2")), r.votes)
        r = PollRules.vote(r, "ann", listOf("o2"))
        assertEquals(listOf(0, 2, 0), PollRules.tally(r).map { it.second })
        r = PollRules.vote(r, "ann", emptyList()) // taken back
        assertEquals(1, PollRules.voters(r))
        val multi = PollRules.vote(RichContent(poll = poll.copy(multi = true)), "ann", listOf("o1", "o3", "o1"))
        assertEquals(listOf("o1", "o3"), multi.votes!!["ann"])
    }

    @Test
    fun `only the poll's creator can close it and a closed poll takes no votes`() {
        val r = PollRules.vote(RichContent(poll = poll), "ann", listOf("o1"))
        assertEquals(r, PollRules.close(r, pollSender = "ann", closer = "bob"))
        val closed = PollRules.close(r, pollSender = "ann", closer = "ann")
        assertTrue(closed.pollClosed == true)
        assertEquals(closed, PollRules.vote(closed, "bob", listOf("o2")))
    }

    @Test
    fun `received payloads keep polls and well-formed stickers, with a readable fallback`() {
        val r = RichRules.fromPayload(Payload(type = "text", text = "x", poll = poll))!!
        assertTrue(r.structured)
        assertTrue(RichRules.fallbackText(r).startsWith("📊 Lunch?\n1. Pizza"))
        assertNotNull(RichRules.fromPayload(Payload(type = "text", text = "👍 LGTM", sticker = StickerRef("work", "lgtm")))?.sticker)
        assertNull(RichRules.fromPayload(Payload(type = "text", text = "x", sticker = StickerRef("../etc", "lgtm"))))
    }

    private fun catalogText(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "shared/stickers/meldiq-stickers-v1.json").exists()) dir = dir.parentFile
        return File(requireNotNull(dir) { "shared/stickers not found" }, "shared/stickers/meldiq-stickers-v1.json").readText()
    }

    @Test
    fun `the shared sticker catalogue parses completely and lookups work`() {
        val packs = StickerCatalog.parse(catalogText())
        assertEquals(listOf("work", "celebrate", "feels"), packs.map { it.id })
        assertEquals(28, packs.sumOf { it.stickers.size }) // every sticker in the file is well formed
        StickerCatalog.load(catalogText())
        val lgtm = StickerCatalog.find(StickerRef("work", "lgtm"))!!
        assertEquals("👍 LGTM", lgtm.fallback)
        assertNull(StickerCatalog.find(StickerRef("work", "unknown")))
        assertTrue(StickerCatalog.search("review").any { it.spec.id == "need-eyes" })
        assertEquals(StickerRef("feels", "love"), StickerCatalog.fromKey(StickerCatalog.key(StickerRef("feels", "love"))))
        assertTrue(StickerCatalog.parse("not json").isEmpty())
    }

    @Test
    fun `music and video links are recognised from the address alone`() {
        fun d(u: String) = MediaLinks.detect(u)?.let { it.provider to it.kind }
        assertEquals(MediaLinks.Provider.YOUTUBE to MediaLinks.Kind.VIDEO, d("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10"))
        assertEquals(MediaLinks.Provider.YOUTUBE to MediaLinks.Kind.VIDEO, d("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(MediaLinks.Provider.YOUTUBE to MediaLinks.Kind.SHORT, d("https://youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals(MediaLinks.Provider.YOUTUBE_MUSIC to MediaLinks.Kind.TRACK, d("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(MediaLinks.Provider.SPOTIFY to MediaLinks.Kind.PLAYLIST, d("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals(MediaLinks.Provider.SPOTIFY to MediaLinks.Kind.TRACK, d("https://open.spotify.com/intl-de/track/4uLU6hMCjMI75M1A2tKUQC"))
        assertEquals(MediaLinks.Provider.SOUNDCLOUD to MediaLinks.Kind.TRACK, d("https://soundcloud.com/artist/a-track"))
        assertEquals(MediaLinks.Provider.VIMEO to MediaLinks.Kind.VIDEO, d("https://vimeo.com/76979871"))
        assertNull(d("http://youtube.com/watch?v=dQw4w9WgXcQ")) // not https
        assertNull(d("https://youtube.com/watch?v=short"))
        assertNull(d("https://evil.example/watch?v=dQw4w9WgXcQ"))
        assertNull(d("https://open.spotify.com/user/someone"))
        assertEquals(MediaLinks.Provider.SPOTIFY, MediaLinks.find("listen https://open.spotify.com/album/4aawyAB9vmqN3uQ7FjRGTy now")?.provider)
    }
}
