package com.griipbuddy.orbit.call

/**
 * How calls capture and send media (pure numbers; unit-tested in `CallMediaPolicyTest`). Measured against LiveKit
 * Android 2.29.0's own defaults, read from the SDK:
 *
 * - Camera: 1280×720 at 30 fps, simulcast (720p/360p/180p layers), VP8, WebRTC's balanced degradation. Kept, and
 *   now set explicitly: the receiver's adaptive stream picks the layer its view and network can take, so video is
 *   sharp when bandwidth allows and degrades instead of freezing on slow links. Not raised to 1080p, which would
 *   cost bandwidth and battery without a sharper picture on a phone-sized view.
 * - Screen share: the SDK default captures at the display's full native size (e.g. 1200×2664), 30 fps, 7 Mbit/s,
 *   with simulcast and the default degradation, which on a constrained link drops resolution first: text turns
 *   to mush. Now: at most 1920 on the long edge and 1080 on the short edge (aspect kept), 15 fps, up to 2.5 Mbit/s,
 *   one layer, and "maintain resolution" so congestion lowers the frame rate, not sharpness.
 * - Echo: on Android 10+ LiveKit enables the phone's hardware echo canceller, and WebRTC then turns its own (AEC3)
 *   off. Echo cancellation therefore depended entirely on the phone model. Default now: WebRTC's AEC3 (hardware
 *   AEC not requested); the hardware one stays available as a setting.
 */
object CallMediaPolicy {
    const val CAMERA_WIDTH = 1280
    const val CAMERA_HEIGHT = 720
    const val CAMERA_FPS = 30

    const val SCREEN_MAX_LONG = 1920
    const val SCREEN_MAX_SHORT = 1080
    const val SCREEN_FPS = 15
    const val SCREEN_MAX_BITRATE = 2_500_000

    /**
     * Screen-capture size for a display of [displayW]×[displayH] pixels, as (long edge, short edge): scaled down to fit
     * [SCREEN_MAX_LONG]×[SCREEN_MAX_SHORT] without stretching, never scaled up, even numbers (encoders need them).
     * LiveKit orients it to the screen (portrait capture on a portrait screen).
     */
    fun screenCaptureSize(displayW: Int, displayH: Int): Pair<Int, Int> {
        val long = maxOf(displayW, displayH).coerceAtLeast(2)
        val short = minOf(displayW, displayH).coerceAtLeast(2)
        val scale = minOf(1.0, SCREEN_MAX_LONG.toDouble() / long, SCREEN_MAX_SHORT.toDouble() / short)
        fun even(v: Double) = (v.toInt() / 2 * 2).coerceAtLeast(2)
        return even(long * scale) to even(short * scale)
    }

    /** Whether to ask for the phone's built-in echo canceller (see the class comment). */
    fun useHardwareEchoCanceller(setting: Boolean): Boolean = setting

    /** One-line, redacted description of the call's audio set-up for diagnostics (no names, no identifiers). */
    fun describeAudio(model: String, sdk: Int, hardwareAecAvailable: Boolean, hardwareAecRequested: Boolean): String =
        "audio: device=$model api=$sdk hwAecAvailable=$hardwareAecAvailable echoCanceller=" +
            if (hardwareAecRequested && hardwareAecAvailable) "hardware" else "webrtc-aec3"
}

/** What one video stream is doing right now, from WebRTC statistics. */
data class VideoStats(val width: Int, val height: Int, val fps: Double?, val kbps: Int?, val limitation: String?) {
    fun describe(): String = buildString {
        append("${width}×$height")
        fps?.let { append(" · ${"%.0f".format(it)} fps") }
        kbps?.let { append(" · $it kbps") }
        if (!limitation.isNullOrEmpty() && limitation != "none") append(" · limited by $limitation")
    }
}

/** The numbers the call's "Connection details" panel shows (and debug builds log), refreshed every two seconds. */
data class CallMediaStats(
    val sendingCamera: VideoStats? = null,
    val sendingScreen: VideoStats? = null,
    val receivingVideo: VideoStats? = null,
    val receivingScreen: VideoStats? = null,
    val audioRoute: String? = null,
    val echoCanceller: String? = null,
    /** The loudest remote sound arriving now (UI-003 loudness diagnostics). */
    val receivingAudio: AudioStats? = null,
    /** The microphone's level as sent, dBFS. */
    val sendingAudioDb: Int? = null,
    /** The phone's call volume, "3 of 7". */
    val callVolume: String? = null,
    val volumeLow: Boolean = false,
)

/**
 * Turns WebRTC stats snapshots (type + members, as `RTCStats` provides them) into [VideoStats]. Bitrates come from the
 * byte counters' growth between two samples of the same stream, so the first sample of a stream has no bitrate.
 */
class VideoStatsSampler {
    private val last = HashMap<String, Pair<Long, Long>>() // stream key -> (bytes, time ms)

    /**
     * [entries]: one track's stats. Outbound ([outbound]) picks the largest active simulcast layer, which is what a
     * full-screen receiver gets when bandwidth allows; inbound has a single stream.
     */
    fun sample(key: String, entries: List<Pair<String, Map<String, Any?>>>, outbound: Boolean, nowMs: Long): VideoStats? {
        val type = if (outbound) "outbound-rtp" else "inbound-rtp"
        val video = entries.filter { it.first == type && (it.second["kind"] ?: it.second["mediaType"]) == "video" }.map { it.second }
        if (video.isEmpty()) return null
        val bytesKey = if (outbound) "bytesSent" else "bytesReceived"
        val bytes = video.sumOf { (it[bytesKey] as? Number)?.toLong() ?: 0L }
        val best = video.filter { (it["frameWidth"] as? Number)?.toInt() ?: 0 > 0 }
            .maxByOrNull { ((it["frameWidth"] as Number).toInt()) * ((it["frameHeight"] as? Number)?.toInt() ?: 0) } ?: return null
        val prev = last[key]
        last[key] = bytes to nowMs
        val kbps = prev?.let { (b, t) -> if (nowMs > t && bytes >= b) ((bytes - b) * 8 / (nowMs - t)).toInt() else null }
        return VideoStats(
            width = (best["frameWidth"] as Number).toInt(),
            height = (best["frameHeight"] as? Number)?.toInt() ?: 0,
            fps = (best["framesPerSecond"] as? Number)?.toDouble(),
            kbps = kbps,
            limitation = if (outbound) best["qualityLimitationReason"] as? String else null,
        )
    }

    fun forget(keep: Set<String>) {
        last.keys.retainAll(keep)
    }
}
