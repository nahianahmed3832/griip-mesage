package com.griipbuddy.message.voice

import com.griipbuddy.message.data.model.AttachmentRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Voice messages are ordinary encrypted attachments (AAC in an MP4 container) marked `voice = true` with
 * their length, so they reuse the attachment pipeline end to end: encrypted on this phone, uploaded as
 * ciphertext, key sent inside the message envelope.
 */
object VoiceNote {
    const val MIME = "audio/mp4"
    const val MAX_DURATION_MS = 10 * 60_000L
    const val MIN_DURATION_MS = 1_000L

    /** Only clips marked as voice and typed as audio play in the app; anything else is shown as a file. */
    fun isVoice(a: AttachmentRef): Boolean = a.voice == true && a.mime.startsWith("audio/")

    /** Chat-list, notification and quote text for a voice message, or null when [refs] isn't one. */
    fun label(refs: List<AttachmentRef>?): String? {
        val a = refs?.firstOrNull()?.takeIf(::isVoice) ?: return null
        return "🎤 Voice message" + (a.durationMs?.takeIf { it > 0 }?.let { " (${clock(it)})" } ?: "")
    }

    /** m:ss, or h:mm:ss past an hour; a negative or absurd length from a peer is clamped. */
    fun clock(ms: Long): String {
        val s = (ms.coerceIn(0, 99 * 3_600_000L) + 500) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** Spoken length for TalkBack: "7 seconds", "1 minute 5 seconds". */
    fun spoken(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 500) / 1000
        val m = s / 60
        val sec = s % 60
        fun unit(n: Long, w: String) = "$n $w" + if (n == 1L) "" else "s"
        return when {
            m == 0L -> unit(sec, "second")
            sec == 0L -> unit(m, "minute")
            else -> unit(m, "minute") + " " + unit(sec, "second")
        }
    }

    fun fileName(now: Long): String = "voice-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now)) + ".m4a"

    fun fraction(positionMs: Long, durationMs: Long): Float =
        if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/** The microphone side of a voice message; the Android implementation is [AndroidAudioRecorder]. */
interface AudioRecorder {
    /** Starts recording into [file]. Throws when the microphone can't be opened. */
    fun start(file: File, maxDurationMs: Long, onLimit: () -> Unit, onError: () -> Unit)

    /** Stops and returns the clip's length in ms, or null when nothing usable was written. */
    fun stop(file: File): Long?

    /** Drops any recording in progress without keeping it. */
    fun release()
}

/** Recordings live only in this app's cache folder; copies left behind by a crash are removed later. */
object VoiceTemp {
    const val STALE_MS = 6 * 3_600_000L

    fun cleanStale(dir: File, now: Long, keep: File? = null, maxAgeMs: Long = STALE_MS): Int {
        var n = 0
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f != keep && now - f.lastModified() > maxAgeMs && f.delete()) n++
        }
        return n
    }
}

/**
 * The composer's voice draft: idle, recording, or a finished clip waiting to be previewed and sent or
 * discarded. Main-thread only. Interruptions (the app leaving the screen, a call) stop the recording and
 * keep what was captured as a draft; nothing records in the background.
 */
class VoiceDraftController(
    private val dir: File,
    private val recorder: AudioRecorder,
    private val elapsed: () -> Long,
    wallClock: () -> Long,
    private val onProblem: (String) -> Unit,
) {
    sealed interface Draft {
        data object Idle : Draft
        data class Recording(val startedAt: Long) : Draft
        data class Ready(val file: File, val durationMs: Long) : Draft
    }

    private val _state = MutableStateFlow<Draft>(Draft.Idle)
    val state: StateFlow<Draft> = _state.asStateFlow()
    private var file: File? = null

    init {
        VoiceTemp.cleanStale(dir, wallClock())
    }

    val recording: Boolean get() = _state.value is Draft.Recording

    fun start() {
        if (_state.value !is Draft.Idle) return
        dir.mkdirs()
        val f = File(dir, "rec-${UUID.randomUUID()}.m4a")
        try {
            recorder.start(f, VoiceNote.MAX_DURATION_MS, onLimit = { limitReached() }, onError = { failed() })
        } catch (e: Exception) {
            f.delete()
            onProblem(MIC_FAILED)
            return
        }
        file = f
        _state.value = Draft.Recording(elapsed())
    }

    /** Stops recording and keeps the clip for preview; a clip under a second is discarded. */
    fun stop() {
        if (_state.value !is Draft.Recording) return
        val f = file ?: return
        val length = runCatching { recorder.stop(f) }.getOrNull()
        if (length == null || length < VoiceNote.MIN_DURATION_MS || !f.isFile || f.length() == 0L) {
            discard()
            onProblem(TOO_SHORT)
            return
        }
        _state.value = Draft.Ready(f, length)
    }

    /** Throws away the recording or the finished clip. */
    fun cancel() {
        if (_state.value is Draft.Recording) recorder.release()
        discard()
    }

    /** Hands the finished clip to the sender, which deletes the file once it's been copied. */
    fun take(): Draft.Ready? {
        val r = _state.value as? Draft.Ready ?: return null
        file = null
        _state.value = Draft.Idle
        return r
    }

    fun close() {
        cancel()
        recorder.release()
    }

    private fun limitReached() {
        stop()
        if (_state.value is Draft.Ready) onProblem(LIMIT)
    }

    private fun failed() {
        if (_state.value !is Draft.Recording) return
        recorder.release()
        discard()
        onProblem(MIC_FAILED)
    }

    private fun discard() {
        file?.delete()
        file = null
        _state.value = Draft.Idle
    }

    companion object {
        const val MIC_FAILED = "Couldn't record from the microphone. Another app or a call may be using it."
        const val TOO_SHORT = "Too short to send. Tap the microphone and speak for at least a second."
        const val LIMIT = "Recording stopped at the 10-minute limit."
        const val PERMISSION = "Orbit needs microphone access to record voice messages. You can allow it in Android Settings."
    }
}
