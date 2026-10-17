package com.griipbuddy.orbit.data.model

import com.griipbuddy.orbit.data.api.GriipJson
import com.griipbuddy.orbit.data.repo.LinkPreviewParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contact cards, locations, animated emoji and links (2026-10-17, docs/RICH_MESSAGES.md, docs/LINKS.md). */
class RichRulesTest {
    @Test
    fun receivedContactsAndLocationsAreBounded() {
        val c = RichRules.clean(ContactCard("  Ayesha  ", listOf("+880 1712", "+880 1712", "", "x".repeat(200)) + List(10) { "+1$it" }))!!
        assertEquals("Ayesha", c.name)
        assertEquals("+880 1712", c.phones.first())
        assertTrue(c.phones.size <= RichRules.MAX_FIELDS)
        assertTrue(c.phones.all { it.length <= RichRules.MAX_FIELD })
        assertNull(RichRules.clean(ContactCard("   ")))
        assertNotNull(RichRules.clean(SharedLocation(23.81, 90.41, 12f, "Office")))
        assertNull(RichRules.clean(SharedLocation(95.0, 10.0)))
        assertNull(RichRules.clean(SharedLocation(Double.NaN, 10.0)))
        assertNull(RichRules.clean(SharedLocation(10.0, 10.0, -5f))?.accuracyM)
    }

    @Test
    fun animatedEmojiIsOneEmoji() {
        assertEquals("❤️", RichRules.cleanEmoji("❤️"))
        assertEquals("👍", RichRules.cleanEmoji(" 👍 "))
        assertNull(RichRules.cleanEmoji("hello"))
        assertNull(RichRules.cleanEmoji("👍👍"))
        assertNull(RichRules.cleanEmoji(""))
    }

    @Test
    fun olderAppsGetReadableText() {
        assertEquals("👤 Ayesha\n+8801712", RichRules.fallbackText(RichContent(contact = ContactCard("Ayesha", listOf("+8801712")))))
        val loc = RichRules.fallbackText(RichContent(location = SharedLocation(23.8, 90.4, label = "Office")))
        assertTrue(loc.startsWith("📍 Office\nhttps://www.openstreetmap.org/?mlat=23.800000&mlon=90.400000"))
        assertEquals("geo:23.800000,90.400000?q=23.800000,90.400000(Gulshan%202)", RichRules.geoUri(SharedLocation(23.8, 90.4, label = "Gulshan 2")))
        // Ordinary messages keep their exact wire format: no rich keys at all.
        val plain = GriipJson.encodeToString(Payload.serializer(), Payload(type = "text", text = "hi"))
        listOf("contact", "location", "animoji", "forwarded", "link").forEach { assertFalse(plain.contains("\"$it\"")) }
    }

    @Test
    fun linkPreviewsMustMatchALinkInTheText() {
        val p = LinkPreview("https://example.com/a", "Title", "Desc")
        assertNotNull(RichRules.clean(p, "see https://example.com/a"))
        assertNull(RichRules.clean(p, "no link here")) // a preview for a link that isn't in the message
        assertNull(RichRules.clean(p.copy(url = "http://example.com/a"), "http://example.com/a")) // https only
        assertNull(RichRules.clean(LinkPreview("https://example.com/a"), "https://example.com/a")) // nothing to show
    }

    @Test
    fun findsHttpAndHttpsLinksOnly() {
        val t = "See https://orbit.example/a?b=1, www.example.org. Not intent://x or javascript:alert(1) or file:///etc (https://en.wikipedia.org/wiki/Orbit_(film))"
        val links = LinkRules.find(t)
        assertEquals(listOf("https://orbit.example/a?b=1", "https://www.example.org", "https://en.wikipedia.org/wiki/Orbit_(film)"), links.map { it.url })
        assertEquals("www.example.org", t.substring(links[1].start, links[1].end))
        assertTrue(LinkRules.find("ftp://x.y mailto:a@b.c").isEmpty())
        assertNull(LinkRules.normalize("https://"))
        assertEquals("example.org", LinkRules.domain("https://www.example.org/x"))
    }

    @Test
    fun misleadingAddressesAreFlagged() {
        assertNull(LinkRules.warning("https://www.griipbuddy.com/path"))
        assertNotNull(LinkRules.warning("https://paypal.com@evil.example/login")) // the real host is after "@"
        assertNotNull(LinkRules.warning("https://xn--pypal-4ve.com/")) // homograph
        assertNotNull(LinkRules.warning("https://аpple.com/")) // Cyrillic "а"
        assertNotNull(LinkRules.warning("https://192.168.0.1/admin"))
        assertNotNull(LinkRules.warning("http://example.com/"))
        assertNotNull(LinkRules.warning("https://login.secure.account.verify.bank.example.com/"))
    }

    @Test
    fun previewParsingReadsOpenGraphFirst() {
        val html = """<html><head><title>Fallback &amp; title</title>
            <meta property="og:title" content="Orbit &#8212; private by design">
            <meta content='A messenger' name='description'>
            <meta property="og:site_name" content="GriipBuddy"></head><body>ignored</body></html>"""
        val p = LinkPreviewParser.parse("https://orbit.example", html)!!
        assertEquals("Orbit — private by design", p.title)
        assertEquals("A messenger", p.description)
        assertEquals("GriipBuddy", p.siteName)
        assertEquals("Fallback & title", LinkPreviewParser.parse("https://x.example", "<title>Fallback &amp; title</title>")!!.title)
        assertNull(LinkPreviewParser.parse("https://x.example", "<html><body>no head</body></html>"))
    }
}
