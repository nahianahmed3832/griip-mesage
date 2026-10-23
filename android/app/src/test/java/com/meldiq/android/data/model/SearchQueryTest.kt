package com.meldiq.android.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class SearchQueryTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `operators come out of the text, the rest is searched`() {
        val q = SearchQuery.parse("from:ana has:image  invoice due before:2026-10-02 after:2026-09-30", utc)
        assertEquals("invoice due", q.text)
        assertEquals("ana", q.from)
        assertEquals(SearchQuery.Has.IMAGE, q.has)
        assertEquals(1790899200000L, q.before) // 2026-10-02 00:00 UTC
        assertEquals(1790726400000L, q.after) // 2026-09-30 00:00 UTC, then the whole day counts
        assertTrue(q.filtered)
    }

    @Test
    fun `unknown or malformed operators stay as words`() {
        val q = SearchQuery.parse("has:everything before:soon from: hello", utc)
        assertEquals("has:everything before:soon from: hello", q.text)
        assertNull(q.has)
        assertNull(q.before)
        assertNull(q.from)
        assertFalse(q.filtered)
    }

    @Test
    fun `matching applies every filter`() {
        val q = SearchQuery.parse("from:ana has:link after:2026-09-30", utc)
        val day = 1790726400000L
        assertTrue(q.matches(senderNames = listOf("Ana Lee", "ana"), sentAt = day + 1000, text = "see https://x.y", mimes = emptyList()))
        assertFalse(q.matches(senderNames = listOf("Bob"), sentAt = day + 1000, text = "see https://x.y", mimes = emptyList()))
        assertFalse(q.matches(senderNames = listOf("ana"), sentAt = day - 1, text = "see https://x.y", mimes = emptyList()))
        assertFalse(q.matches(senderNames = listOf("ana"), sentAt = day + 1000, text = "no link", mimes = emptyList()))
        val files = SearchQuery.parse("has:file", utc)
        assertTrue(files.matches(listOf("x"), 0, "", listOf("application/pdf")))
        assertFalse(SearchQuery.parse("has:video", utc).matches(listOf("x"), 0, "", listOf("image/png")))
    }
}
