package com.meldiq.android.call

import com.meldiq.android.ui.screens.calls.CallTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-10-24, as on Meldiq Web: High screen sharing, several shares at once and which one this phone watches. */
class ShareHighAndSelectionTest {
    private val vp8 = "video/x-vnd.on2.vp8"

    @Test
    fun `high is 1080p at 60 fps and keeps the frame rate`() {
        assertEquals(1920 to 1080, CallMediaPolicy.screenCaptureSize(1920, 1080, ShareMode.HIGH))
        assertEquals(1920 to 864, CallMediaPolicy.screenCaptureSize(2664, 1200, ShareMode.HIGH))
        assertEquals(60, ShareMode.HIGH.fps)
        assertTrue(ShareMode.HIGH.keepFrameRate)
        assertTrue(ShareMode.HIGH.maxBitrate > ShareMode.GAME.maxBitrate)
        assertEquals(ShareMode.HIGH, ShareMode.of("HIGH"))
    }

    @Test
    fun `high needs a hardware vp8 encoder that does 1080p60`() {
        assertTrue(CallMediaPolicy.canShareHigh(listOf(CallMediaPolicy.Encoder(vp8, hardware = true, does1080p60 = true))))
        assertFalse(CallMediaPolicy.canShareHigh(listOf(CallMediaPolicy.Encoder(vp8, hardware = false, does1080p60 = true))))
        assertFalse(CallMediaPolicy.canShareHigh(listOf(CallMediaPolicy.Encoder(vp8, hardware = true, does1080p60 = false))))
        // Calls send VP8: an H.264 encoder that could do it doesn't count.
        assertFalse(CallMediaPolicy.canShareHigh(listOf(CallMediaPolicy.Encoder("video/avc", hardware = true, does1080p60 = true))))
        assertFalse(CallMediaPolicy.canShareHigh(emptyList()))
    }

    @Test
    fun `a high share sending clearly less is noticed`() {
        assertFalse(CallMediaPolicy.sendingLessThanHigh(null))
        assertFalse(CallMediaPolicy.sendingLessThanHigh(VideoStats(1920, 1080, 58.0, 6000, null)))
        assertFalse(CallMediaPolicy.sendingLessThanHigh(VideoStats(1080, 2400, 60.0, 6000, null)))
        assertTrue(CallMediaPolicy.sendingLessThanHigh(VideoStats(1280, 720, 60.0, 4000, "cpu")))
        assertTrue(CallMediaPolicy.sendingLessThanHigh(VideoStats(1920, 1080, 30.0, 4000, "bandwidth")))
    }

    @Test
    fun `the watched share stays while it lasts and a new share never takes over`() {
        assertNull(CallTiles.watchedShare(emptyList(), null))
        assertEquals("a", CallTiles.watchedShare(listOf("a"), null))
        // b starts sharing, and joined earlier so comes first: a stays watched.
        assertEquals("a", CallTiles.watchedShare(listOf("b", "a"), "a"))
        // a stops: the next share is shown.
        assertEquals("b", CallTiles.watchedShare(listOf("b"), "a"))
    }

    @Test
    fun `a fourth screen waits`() {
        assertTrue(CallTiles.canStartShare(0))
        assertTrue(CallTiles.canStartShare(2))
        assertFalse(CallTiles.canStartShare(CallTiles.MAX_SHARES))
    }
}
