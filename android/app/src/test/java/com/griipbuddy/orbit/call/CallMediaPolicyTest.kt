package com.griipbuddy.orbit.call

import com.griipbuddy.orbit.ui.screens.calls.CallChrome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Physical-device reports (2026-10-16): echo, blurry camera video, unreadable screen shares, controls over the video. */
class CallMediaPolicyTest {
    @Test
    fun screenCaptureKeepsTheAspectAndCapsAtTenEighty() {
        // HONOR ELI-AN00 (1200×2664 portrait): the SDK default would capture all 3.2 MP at 30 fps.
        val (long, short) = CallMediaPolicy.screenCaptureSize(1200, 2664)
        assertEquals(864, short)
        assertEquals(1920, long)
        assertEquals(1200.0 / 2664, short.toDouble() / long, 0.002) // not stretched
        assertEquals(1920 to 1080, CallMediaPolicy.screenCaptureSize(2560, 1440)) // landscape tablet: 16:9
        assertEquals(720 to 360, CallMediaPolicy.screenCaptureSize(360, 720)) // small screens are never scaled up
        val (l, sh) = CallMediaPolicy.screenCaptureSize(1081, 2401)
        assertTrue(l % 2 == 0 && sh % 2 == 0) // encoders need even sizes
    }

    @Test
    fun webrtcsEchoCancellerIsTheDefaultAndTheChoiceIsLoggedWithoutIdentifiers() {
        assertFalse(CallMediaPolicy.useHardwareEchoCanceller(false))
        assertTrue(CallMediaPolicy.useHardwareEchoCanceller(true))
        assertEquals("audio: device=ELI-AN00 api=34 hwAecAvailable=true echoCanceller=webrtc-aec3", CallMediaPolicy.describeAudio("ELI-AN00", 34, true, false))
        assertEquals("audio: device=X api=34 hwAecAvailable=true echoCanceller=hardware", CallMediaPolicy.describeAudio("X", 34, true, true))
        // Asked for hardware but the phone has none: WebRTC's own canceller is what runs.
        assertTrue(CallMediaPolicy.describeAudio("X", 30, false, true).endsWith("webrtc-aec3"))
    }

    private fun rtp(type: String, vararg kv: Pair<String, Any?>) = type to mapOf("kind" to "video", *kv)

    @Test
    fun senderStatsReportTheLargestLayerAndBitrateFromByteGrowth() {
        val s = VideoStatsSampler()
        val t0 = listOf(
            rtp("outbound-rtp", "rid" to "f", "frameWidth" to 1280, "frameHeight" to 720, "framesPerSecond" to 29.0, "bytesSent" to 1_000_000L, "qualityLimitationReason" to "none"),
            rtp("outbound-rtp", "rid" to "h", "frameWidth" to 640, "frameHeight" to 360, "framesPerSecond" to 30.0, "bytesSent" to 200_000L),
            "codec" to mapOf("mimeType" to "video/VP8"),
        )
        val first = s.sample("out:cam", t0, outbound = true, nowMs = 10_000)!!
        assertEquals(1280, first.width)
        assertNull(first.kbps) // no previous sample yet
        val t1 = listOf(
            rtp("outbound-rtp", "rid" to "f", "frameWidth" to 960, "frameHeight" to 540, "framesPerSecond" to 24.0, "bytesSent" to 1_400_000L, "qualityLimitationReason" to "bandwidth"),
            rtp("outbound-rtp", "rid" to "h", "frameWidth" to 640, "frameHeight" to 360, "framesPerSecond" to 30.0, "bytesSent" to 300_000L),
        )
        val second = s.sample("out:cam", t1, outbound = true, nowMs = 12_000)!!
        assertEquals(960, second.width)
        assertEquals(2000, second.kbps) // 500 000 bytes in 2 s
        assertEquals("960×540 · 24 fps · 2000 kbps · limited by bandwidth", second.describe())
    }

    @Test
    fun receiverStatsAndMissingStreams() {
        val s = VideoStatsSampler()
        assertNull(s.sample("in:x", listOf("inbound-rtp" to mapOf("kind" to "audio", "bytesReceived" to 5L)), outbound = false, nowMs = 0))
        s.sample("in:x", listOf(rtp("inbound-rtp", "frameWidth" to 320, "frameHeight" to 180, "bytesReceived" to 0L)), outbound = false, nowMs = 0)
        val v = s.sample("in:x", listOf(rtp("inbound-rtp", "frameWidth" to 320, "frameHeight" to 180, "framesPerSecond" to 15, "bytesReceived" to 25_000L)), outbound = false, nowMs = 1000)!!
        assertEquals("320×180 · 15 fps · 200 kbps", v.describe())
        // A stream that restarted (counters reset) gives no bitrate rather than a negative one.
        assertNull(s.sample("in:x", listOf(rtp("inbound-rtp", "frameWidth" to 320, "frameHeight" to 180, "bytesReceived" to 10L)), outbound = false, nowMs = 2000)!!.kbps)
    }

    @Test
    fun controlsHideOnlyOverVideoInAConnectedCall() {
        assertTrue(CallChrome.autoHides(hasVideo = true, phase = CallPhase.Connected, menuOpen = false))
        assertFalse(CallChrome.autoHides(hasVideo = false, phase = CallPhase.Connected, menuOpen = false)) // voice call
        assertFalse(CallChrome.autoHides(hasVideo = true, phase = CallPhase.Incoming, menuOpen = false)) // answer/decline must stay
        assertFalse(CallChrome.autoHides(hasVideo = true, phase = CallPhase.Reconnecting, menuOpen = false))
        assertFalse(CallChrome.autoHides(hasVideo = true, phase = CallPhase.Connected, menuOpen = true))
    }
}
