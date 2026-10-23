package com.meldiq.android.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** 2026-10-23: which links play inside Meldiq (YouTube videos, Shorts, live streams) and the player address. */
class MediaLinksPlayerTest {
    @Test
    fun `YouTube videos, shorts and live streams carry the id the in-app player plays`() {
        assertEquals("dQw4w9WgXcQ", MediaLinks.detect("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10")?.videoId)
        assertEquals("dQw4w9WgXcQ", MediaLinks.detect("https://youtu.be/dQw4w9WgXcQ")?.videoId)
        assertEquals("dQw4w9WgXcQ", MediaLinks.detect("https://youtube.com/shorts/dQw4w9WgXcQ")?.videoId)
        assertEquals("dQw4w9WgXcQ", MediaLinks.detect("https://m.youtube.com/live/dQw4w9WgXcQ")?.videoId)
        // Music, playlists and other providers open in their own app instead.
        assertEquals(null, MediaLinks.detect("https://music.youtube.com/watch?v=dQw4w9WgXcQ")?.videoId)
        assertEquals(null, MediaLinks.detect("https://www.youtube.com/playlist?list=PL123")?.videoId)
        assertEquals(null, MediaLinks.detect("https://vimeo.com/123456")?.videoId)
    }

    @Test
    fun `only a well-formed id becomes a privacy-enhanced player address`() {
        assertEquals("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ?autoplay=1&playsinline=1&rel=0", MediaLinks.embedUrl("dQw4w9WgXcQ"))
        assertEquals(null, MediaLinks.embedUrl("x\"><script>"))
    }
}
