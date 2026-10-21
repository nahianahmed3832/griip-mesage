package com.meldiq.android.data.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The generated catalogue (assets/emoji/emoji.json) and the rules around it (docs/EMOJI.md). */
class EmojiCatalogTest {
    // Unit tests run in the module folder.
    private val groups = EmojiCatalog.parse(File("src/main/assets/emoji/emoji.json").readText())
    private val all = groups.flatMap { it.items }

    @Test
    fun `the catalogue has Unicode's nine groups in order and every emoji once`() {
        assertEquals(listOf("smileys", "people", "nature", "food", "travel", "activities", "objects", "symbols", "flags"), groups.map { it.id })
        assertTrue("got ${all.size}", all.size > 1800)
        assertEquals(all.size, all.map { it.emoji }.toSet().size)
        assertTrue(all.all { it.name.isNotBlank() && it.version.isNotBlank() })
    }

    @Test
    fun `skin tones are folded into their base, light to dark`() {
        val wave = all.single { it.emoji == "👋" }
        assertEquals(listOf("👋🏻", "👋🏼", "👋🏽", "👋🏾", "👋🏿"), wave.tones)
        assertEquals("👋🏽", wave.inTone(3))
        assertEquals("👋", wave.inTone(0))
        assertEquals("😀", all.single { it.emoji == "😀" }.inTone(4))
        assertTrue(all.none { it.emoji == "👋🏻" })
    }

    @Test
    fun `every emoji and tone in the catalogue is a valid reaction`() {
        val bad = all.flatMap { listOf(it.emoji) + it.tones.orEmpty() }.filterNot(EmojiRules::isReaction)
        assertEquals(emptyList<String>(), bad)
    }

    @Test
    fun `text, spaces, control characters and long strings are not reactions`() {
        listOf(null, "", " ", "lol", "👍 👍", "👍\n", "a👍", "\u0000", "👍".repeat(33)).forEach {
            assertFalse("$it", EmojiRules.isReaction(it))
        }
        listOf("👍", "ℹ️", "1️⃣", "🏴󠁧󠁢󠁥󠁮󠁧󠁿", "👨‍👩‍👧‍👦", "🇧🇩").forEach { assertTrue(it, EmojiRules.isReaction(it)) }
    }

    @Test
    fun `search finds by name and by keyword, best match first`() {
        assertEquals("👍", EmojiCatalog.search(groups, "thumbs up").first().emoji)
        assertTrue(EmojiCatalog.search(groups, "red heart").first().emoji == "❤️")
        assertTrue(EmojiCatalog.search(groups, "  HAPPY ").any { it.emoji == "😀" })
        assertTrue(EmojiCatalog.search(groups, "bangladesh").any { it.emoji == "🇧🇩" })
        assertEquals(emptyList<EmojiItem>(), EmojiCatalog.search(groups, "zzqqxx"))
        assertEquals(emptyList<EmojiItem>(), EmojiCatalog.search(groups, "   "))
        assertEquals(5, EmojiCatalog.search(groups, "face", limit = 5).size)
    }
}
