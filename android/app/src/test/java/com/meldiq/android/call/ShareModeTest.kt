package com.meldiq.android.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batch B11 (CALL-06): the two screen-share modes and app sound. */
class ShareModeTest {
    @Test
    fun `game mode captures at most 720p and keeps the shape`() {
        // HONOR ELI-AN00 (1200×2664) turned landscape by a game.
        assertEquals(1280 to 576, CallMediaPolicy.orientedCaptureSize(2664, 1200, ShareMode.GAME))
        assertEquals(576 to 1280, CallMediaPolicy.orientedCaptureSize(1200, 2664, ShareMode.GAME))
        assertEquals(1280 to 720, CallMediaPolicy.screenCaptureSize(1920, 1080, ShareMode.GAME))
        // Text mode is unchanged from round 7.
        assertEquals(1920 to 864, CallMediaPolicy.orientedCaptureSize(2664, 1200, ShareMode.TEXT))
    }

    @Test
    fun `game mode trades sharpness for frame rate, text mode the other way`() {
        assertEquals(30, ShareMode.GAME.fps)
        assertTrue(ShareMode.GAME.keepFrameRate)
        assertEquals(CallMediaPolicy.SCREEN_FPS, ShareMode.TEXT.fps)
        assertFalse(ShareMode.TEXT.keepFrameRate)
        assertTrue(ShareMode.GAME.maxBitrate > ShareMode.TEXT.maxBitrate)
    }

    @Test
    fun `an unknown saved choice falls back to text`() {
        assertEquals(ShareMode.GAME, ShareMode.of("GAME"))
        assertEquals(ShareMode.TEXT, ShareMode.of(null))
        assertEquals(ShareMode.TEXT, ShareMode.of("FAST"))
    }

    @Test
    fun `app sound needs Android 10`() {
        assertFalse(CallMediaPolicy.canShareAppSound(28))
        assertTrue(CallMediaPolicy.canShareAppSound(29))
    }
}
