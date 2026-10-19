package com.meldiq.android.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** AAC, mono, 44.1 kHz at 48 kbit/s: about 360 KB a minute, so the 10-minute limit stays near 3.6 MB. */
class AndroidAudioRecorder(private val context: Context) : AudioRecorder {
    private var rec: MediaRecorder? = null
    private var startedAt = 0L

    override fun start(file: File, maxDurationMs: Long, onLimit: () -> Unit, onError: () -> Unit) {
        release()
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(48_000)
            r.setMaxDuration(maxDurationMs.toInt())
            r.setOutputFile(file.path)
            r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onLimit() }
            r.setOnErrorListener { _, _, _ -> onError() }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            Log.w(TAG, "recorder start failed: ${e.javaClass.simpleName}")
            throw e
        }
        rec = r
        startedAt = SystemClock.elapsedRealtime()
    }

    override fun stop(file: File): Long? {
        val r = rec ?: return null
        rec = null
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        // stop() throws when no audio was captured (a very short tap); the file is then unusable.
        val stopped = runCatching { r.stop() }.isSuccess
        r.release()
        val probed = probeDuration(file)
        return probed ?: if (stopped) elapsed else null
    }

    override fun release() {
        rec?.let { r -> runCatching { r.reset() }; r.release() }
        rec = null
    }

    private fun probeDuration(file: File): Long? {
        if (!file.isFile || file.length() == 0L) return null
        val m = MediaMetadataRetriever()
        return try {
            m.setDataSource(file.path)
            m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { m.release() }
        }
    }

    private companion object { const val TAG = "VoiceRecorder" }
}

/**
 * One voice clip plays at a time, app-wide. Playback takes transient audio focus and pauses when it's
 * lost (another app, a ringing call). Main-thread only.
 */
class VoicePlayer(context: Context) {
    data class State(val key: String? = null, val playing: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0)

    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener({ change -> if (change < 0) pause() }, main)
        .build()
    private var player: MediaPlayer? = null
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!_state.value.playing) return
            _state.update { it.copy(positionMs = runCatching { p.currentPosition.toLong() }.getOrDefault(it.positionMs)) }
            main.postDelayed(this, 200)
        }
    }

    /** Plays or pauses the clip [key]; false when the file can't be played. */
    fun toggle(key: String, file: File): Boolean {
        val s = _state.value
        if (s.key == key && player != null) {
            if (s.playing) pause() else resume()
            return true
        }
        if (!load(key, file, 0)) return false
        resume()
        return true
    }

    /** Moves to [positionMs]; a clip that isn't loaded yet is loaded there, paused. */
    fun seek(key: String, file: File?, positionMs: Long) {
        val p = player
        if (_state.value.key == key && p != null) {
            runCatching { p.seekTo(positionMs.toInt()) }
            _state.update { it.copy(positionMs = positionMs) }
        } else if (file != null) {
            load(key, file, positionMs)
        }
    }

    fun pause() {
        val p = player ?: return
        if (_state.value.playing) runCatching { p.pause() }
        main.removeCallbacks(tick)
        audio.abandonAudioFocusRequest(focus)
        _state.update { it.copy(playing = false, positionMs = runCatching { p.currentPosition.toLong() }.getOrDefault(it.positionMs)) }
    }

    /** Stops playback; with [onlyIf], only when the loaded clip matches. */
    fun stop(onlyIf: ((String) -> Boolean)? = null) {
        val k = _state.value.key ?: return
        if (onlyIf != null && !onlyIf(k)) return
        release()
        _state.value = State()
    }

    private fun resume() {
        val p = player ?: return
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        runCatching { p.start() }.onFailure { stop(); return }
        _state.update { it.copy(playing = true) }
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private fun load(key: String, file: File, startMs: Long): Boolean {
        release()
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(attrs)
            p.setDataSource(file.path)
            p.prepare()
        } catch (e: Exception) {
            p.release()
            _state.value = State()
            Log.w(TAG, "can't play clip: ${e.javaClass.simpleName}")
            return false
        }
        p.setOnCompletionListener {
            main.removeCallbacks(tick)
            audio.abandonAudioFocusRequest(focus)
            runCatching { it.seekTo(0) }
            _state.update { s -> s.copy(playing = false, positionMs = 0) }
        }
        p.setOnErrorListener { _, _, _ -> stop(); true }
        val duration = p.duration.toLong().coerceAtLeast(0)
        val start = startMs.coerceIn(0, duration)
        if (start > 0) runCatching { p.seekTo(start.toInt()) }
        player = p
        _state.value = State(key, false, start, duration)
        return true
    }

    private fun release() {
        main.removeCallbacks(tick)
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        audio.abandonAudioFocusRequest(focus)
    }

    private companion object { const val TAG = "VoicePlayer" }
}
