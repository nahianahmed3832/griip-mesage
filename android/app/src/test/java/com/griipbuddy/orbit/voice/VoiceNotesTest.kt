package com.griipbuddy.orbit.voice

import com.griipbuddy.orbit.data.model.AttachmentRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoiceNotesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun ref(mime: String = "audio/mp4", voice: Boolean? = true, ms: Long? = 7_300) =
        AttachmentRef("a", "voice.m4a", mime, 100, "k", "n", "h", voice, ms)

    @Test
    fun onlyAudioMarkedAsVoicePlaysInTheApp() {
        assertTrue(VoiceNote.isVoice(ref()))
        assertFalse(VoiceNote.isVoice(ref(voice = null)))
        assertFalse(VoiceNote.isVoice(ref(voice = false)))
        assertFalse("a peer can't make a non-audio file play as voice", VoiceNote.isVoice(ref(mime = "application/pdf")))
    }

    @Test
    fun labelsAndClock() {
        assertEquals("🎤 Voice message (0:07)", VoiceNote.label(listOf(ref())))
        assertEquals("🎤 Voice message", VoiceNote.label(listOf(ref(ms = null))))
        assertNull(VoiceNote.label(listOf(ref(voice = null))))
        assertNull(VoiceNote.label(null))
        assertEquals("0:00", VoiceNote.clock(-5))
        assertEquals("1:05", VoiceNote.clock(65_000))
        assertEquals("10:00", VoiceNote.clock(VoiceNote.MAX_DURATION_MS))
        assertEquals("1:00:00", VoiceNote.clock(3_600_000))
        assertEquals("99:00:00", VoiceNote.clock(Long.MAX_VALUE)) // absurd length from a peer is clamped
        assertEquals("7 seconds", VoiceNote.spoken(7_000))
        assertEquals("1 second", VoiceNote.spoken(1_000))
        assertEquals("1 minute 5 seconds", VoiceNote.spoken(65_000))
        assertEquals("2 minutes", VoiceNote.spoken(120_000))
        assertEquals(0.5f, VoiceNote.fraction(500, 1000), 0f)
        assertEquals(0f, VoiceNote.fraction(500, 0), 0f)
        assertEquals(1f, VoiceNote.fraction(5000, 1000), 0f)
        assertTrue(VoiceNote.fileName(0).matches(Regex("voice-\\d{8}-\\d{6}\\.m4a")))
    }

    @Test
    fun staleRecordingsAreCleanedButFreshOnesKept() {
        val dir = tmp.newFolder("voice")
        val old = File(dir, "old.m4a").apply { writeText("x"); setLastModified(1_000) }
        val keep = File(dir, "keep.m4a").apply { writeText("x"); setLastModified(1_000) }
        val fresh = File(dir, "fresh.m4a").apply { writeText("x"); setLastModified(VoiceTemp.STALE_MS) }
        assertEquals(1, VoiceTemp.cleanStale(dir, now = VoiceTemp.STALE_MS + 2_000, keep = keep))
        assertFalse(old.exists())
        assertTrue(keep.exists())
        assertTrue(fresh.exists())
    }
}

class VoiceDraftControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Writes [bytes] into the file on start and reports [length] on stop, like MediaRecorder. */
    private class FakeRecorder(var length: Long? = 3_000, var bytes: Int = 10, var failStart: Boolean = false) : AudioRecorder {
        var file: File? = null
        var onLimit: () -> Unit = {}
        var onError: () -> Unit = {}
        var releases = 0
        override fun start(file: File, maxDurationMs: Long, onLimit: () -> Unit, onError: () -> Unit) {
            if (failStart) throw IllegalStateException("mic busy")
            file.writeBytes(ByteArray(bytes))
            this.file = file
            this.onLimit = onLimit
            this.onError = onError
        }
        override fun stop(file: File): Long? = length
        override fun release() { releases++ }
    }

    private var now = 1_000L
    private val problems = mutableListOf<String>()

    private fun controller(rec: AudioRecorder, dir: File = tmp.newFolder()) =
        VoiceDraftController(dir, rec, { now }, { System.currentTimeMillis() }) { problems += it }

    @Test
    fun recordStopPreviewAndTake() {
        val rec = FakeRecorder()
        val v = controller(rec)
        v.start()
        assertEquals(VoiceDraftController.Draft.Recording(1_000), v.state.value)
        v.stop()
        val ready = v.state.value as VoiceDraftController.Draft.Ready
        assertEquals(3_000L, ready.durationMs)
        assertTrue(ready.file.exists())
        assertEquals(ready, v.take())
        assertEquals(VoiceDraftController.Draft.Idle, v.state.value)
        assertTrue("the sender owns the file after take()", ready.file.exists())
        assertNull(v.take())
        assertTrue(problems.isEmpty())
    }

    @Test
    fun tooShortOrEmptyClipsAreDiscarded() {
        for (rec in listOf(FakeRecorder(length = 400), FakeRecorder(length = null), FakeRecorder(bytes = 0))) {
            problems.clear()
            val v = controller(rec)
            v.start()
            v.stop()
            assertEquals(VoiceDraftController.Draft.Idle, v.state.value)
            assertFalse(rec.file!!.exists())
            assertEquals(listOf(VoiceDraftController.TOO_SHORT), problems)
        }
    }

    @Test
    fun cancelDeletesWhileRecordingOrAfter() {
        val rec = FakeRecorder()
        val v = controller(rec)
        v.start()
        v.cancel()
        assertEquals(VoiceDraftController.Draft.Idle, v.state.value)
        assertFalse(rec.file!!.exists())
        assertEquals(1, rec.releases)

        v.start()
        v.stop()
        val f = (v.state.value as VoiceDraftController.Draft.Ready).file
        v.cancel()
        assertFalse(f.exists())
        assertEquals(VoiceDraftController.Draft.Idle, v.state.value)
    }

    @Test
    fun microphoneFailuresAreReportedNotHidden() {
        val busy = FakeRecorder(failStart = true)
        val v = controller(busy)
        v.start()
        assertEquals(VoiceDraftController.Draft.Idle, v.state.value)
        assertEquals(listOf(VoiceDraftController.MIC_FAILED), problems)

        problems.clear()
        val rec = FakeRecorder()
        val w = controller(rec)
        w.start()
        rec.onError()
        assertEquals(VoiceDraftController.Draft.Idle, w.state.value)
        assertFalse(rec.file!!.exists())
        assertEquals(listOf(VoiceDraftController.MIC_FAILED), problems)
    }

    @Test
    fun theLengthLimitStopsIntoAPreview() {
        val rec = FakeRecorder(length = VoiceNote.MAX_DURATION_MS)
        val v = controller(rec)
        v.start()
        rec.onLimit()
        assertTrue(v.state.value is VoiceDraftController.Draft.Ready)
        assertEquals(listOf(VoiceDraftController.LIMIT), problems)
    }

    @Test
    fun startIsIgnoredUnlessIdleAndCloseCleansUp() {
        val rec = FakeRecorder()
        val dir = tmp.newFolder()
        val v = controller(rec, dir)
        v.start()
        val first = rec.file
        v.start() // a double tap doesn't open a second recording
        assertEquals(first, rec.file)
        v.stop()
        v.start() // nor does tapping record over an unsent clip
        assertTrue(v.state.value is VoiceDraftController.Draft.Ready)
        v.close()
        assertEquals(0, dir.listFiles()!!.size)
    }
}
