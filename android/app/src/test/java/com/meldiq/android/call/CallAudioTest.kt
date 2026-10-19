package com.meldiq.android.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI-003 audio routing rules and the loudness diagnostics' numbers. */
class CallAudioTest {
    private val speaker = AudioRoute(AudioRouteKind.Speaker)
    private val earpiece = AudioRoute(AudioRouteKind.Earpiece)
    private val buds = AudioRoute(AudioRouteKind.Bluetooth, "Buds")
    private val wired = AudioRoute(AudioRouteKind.Wired)

    @Test
    fun `voice calls start on the earpiece, video and group calls on the speaker, a headset always first`() {
        assertEquals(listOf(AudioRouteKind.Bluetooth, AudioRouteKind.Wired, AudioRouteKind.Earpiece, AudioRouteKind.Speaker), CallAudioPolicy.preferred(video = false, isGroup = false))
        assertEquals(AudioRouteKind.Speaker, CallAudioPolicy.preferred(video = true, isGroup = false)[2])
        // Group voice calls: the same whether started from a chat or joined from its banner (they used to differ).
        assertEquals(CallAudioPolicy.preferred(video = true, isGroup = false), CallAudioPolicy.preferred(video = false, isGroup = true))
    }

    @Test
    fun `the button toggles speaker and earpiece, and lists outputs when there are more`() {
        assertFalse(CallAudioPolicy.needsPicker(listOf(speaker, earpiece)))
        assertTrue(CallAudioPolicy.needsPicker(listOf(speaker, earpiece, buds)))
        assertTrue(CallAudioPolicy.needsPicker(listOf(speaker, wired)))
        assertEquals(earpiece, CallAudioPolicy.toggleTarget(speaker, listOf(speaker, earpiece)))
        assertEquals(speaker, CallAudioPolicy.toggleTarget(earpiece, listOf(speaker, earpiece)))
        assertEquals(speaker, CallAudioPolicy.toggleTarget(null, listOf(speaker, earpiece)))
        assertNull(CallAudioPolicy.toggleTarget(speaker, listOf(speaker))) // a tablet without an earpiece
    }

    @Test
    fun `a headset that appears takes over, and the camera moves an earpiece call to the speaker`() {
        assertTrue(CallAudioPolicy.headsetArrived(listOf(speaker, earpiece), listOf(speaker, earpiece, buds)))
        assertTrue(CallAudioPolicy.headsetArrived(listOf(speaker, earpiece), listOf(speaker, wired)))
        assertFalse(CallAudioPolicy.headsetArrived(listOf(speaker, earpiece, buds), listOf(speaker, earpiece, buds)))
        assertFalse(CallAudioPolicy.headsetArrived(listOf(speaker, earpiece, buds), listOf(speaker, earpiece)))
        assertTrue(CallAudioPolicy.speakerForVideo(earpiece, userChose = null))
        assertFalse(CallAudioPolicy.speakerForVideo(earpiece, userChose = AudioRouteKind.Earpiece))
        assertFalse(CallAudioPolicy.speakerForVideo(buds, userChose = null))
    }

    @Test
    fun `the reported route is matched to the one asked for`() {
        assertTrue(CallAudioPolicy.matches(speaker, speaker))
        assertFalse(CallAudioPolicy.matches(speaker, earpiece))
        assertFalse(CallAudioPolicy.matches(speaker, null))
        // The system's Bluetooth device may have no name or another spelling: matched by kind then.
        assertTrue(CallAudioPolicy.matches(buds, AudioRoute(AudioRouteKind.Bluetooth)))
        assertFalse(CallAudioPolicy.matches(buds, AudioRoute(AudioRouteKind.Bluetooth, "Car")))
        assertEquals("Couldn't switch the sound to Speaker. Try again, or pick another output.", CallAudioPolicy.routeFailed(speaker))
        assertEquals(listOf("Speaker", "Phone", "Buds", "Headphones", "Bluetooth"), listOf(speaker, earpiece, buds, wired, AudioRoute(AudioRouteKind.Bluetooth)).map { it.label })
    }

    @Test
    fun `low call volume is named, the user's volume is never changed`() {
        assertTrue(CallAudioPolicy.volumeLow(2, 7))
        assertFalse(CallAudioPolicy.volumeLow(3, 7))
        assertFalse(CallAudioPolicy.volumeLow(0, 0))
        assertEquals("2 of 7 (low)", CallAudioPolicy.describeVolume(2, 7))
        assertEquals("6 of 7", CallAudioPolicy.describeVolume(6, 7))
    }

    private fun inbound(level: Double, received: Long, lost: Long, samples: Long, concealed: Long, jitter: Double = 0.012) =
        listOf("inbound-rtp" to mapOf<String, Any?>(
            "kind" to "audio", "audioLevel" to level, "packetsReceived" to received, "packetsLost" to lost,
            "totalSamplesReceived" to samples, "concealedSamples" to concealed, "jitter" to jitter,
        ))

    @Test
    fun `audio stats give the level, and loss and concealment between two samples`() {
        val sampler = AudioStatsSampler()
        val first = sampler.inbound("a", inbound(0.1, 1000, 0, 480_000, 0))!!
        assertEquals(-20, first.levelDb)
        assertNull(first.lossPercent) // no previous sample yet
        assertEquals(12, first.jitterMs)
        val second = sampler.inbound("a", inbound(0.1, 1098, 2, 576_000, 9_600))!!
        assertEquals(2.0, second.lossPercent!!, 0.001)
        assertEquals(10.0, second.concealedPercent!!, 0.001)
        assertEquals("level -20 dBFS · loss 2.0% · jitter 12 ms · concealed 10.0%", second.describe())
        assertEquals(AudioStats.SILENT_DB, AudioStatsSampler.dbfs(0.0))
        assertNull(sampler.inbound("v", listOf("inbound-rtp" to mapOf<String, Any?>("kind" to "video"))))
        assertEquals(-6, sampler.outboundLevel(listOf("media-source" to mapOf<String, Any?>("kind" to "audio", "audioLevel" to 0.5))))
        assertEquals("silent", AudioStats(-60, null, null, null).describe())
    }

    @Test
    fun `the level is averaged over the interval from WebRTC's energy counters`() {
        val sampler = AudioStatsSampler()
        fun mic(energy: Double, duration: Double, level: Double = 0.0) =
            listOf("media-source" to mapOf<String, Any?>("kind" to "audio", "audioLevel" to level, "totalAudioEnergy" to energy, "totalSamplesDuration" to duration))
        // First sample: no interval yet, the instant level (silent at that moment).
        assertEquals(AudioStats.SILENT_DB, sampler.outboundLevel(mic(0.0, 10.0)))
        // Speech in the next 2 s (mean energy 0.01 = level 0.1 = -20 dBFS) though the instant reading is 0 again.
        assertEquals(-20, sampler.outboundLevel(mic(0.02, 12.0)))
        // A quiet interval reads silent.
        assertEquals(AudioStats.SILENT_DB, sampler.outboundLevel(mic(0.02, 14.0)))
        assertEquals(-20, AudioStatsSampler.rmsDb(0.01))
    }
}
