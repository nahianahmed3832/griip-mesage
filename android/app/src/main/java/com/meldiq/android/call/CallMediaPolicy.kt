package com.meldiq.android.call

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
 * - Noise (round 7, bug 1/5): WebRTC's JavaAudioDeviceModule also switches on the phone's built-in noise suppressor
 *   whenever the phone has one, on top of WebRTC's own. Two suppressors in a row eat into speech (muffled, quieter
 *   voices, and the other side's AGC then lifts the noise floor). Now only WebRTC's suppressor runs, alongside its
 *   echo canceller, gain control and high-pass filter; typing-noise detection is off (it only gates on keyboard clicks).
 * - Local preview (round 7, bug 3): your own picture was mirrored whatever camera was on, so the back camera's
 *   picture moved the wrong way as the phone moved. Only the front camera is mirrored now; what's sent was never mirrored.
 * - Screen share rotation (round 7, bug 4): LiveKit resizes the capture only when the motion sensor reports a turn. A
 *   game that turns the display to landscape by itself (auto-rotate off, or the phone already sideways) kept a portrait
 *   capture, so the landscape game didn't fit. The call follows the display itself now ([ScreenShareRotation]).
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
    fun screenCaptureSize(displayW: Int, displayH: Int, mode: ShareMode = ShareMode.TEXT): Pair<Int, Int> {
        val long = maxOf(displayW, displayH).coerceAtLeast(2)
        val short = minOf(displayW, displayH).coerceAtLeast(2)
        val scale = minOf(1.0, mode.maxLong.toDouble() / long, mode.maxShort.toDouble() / short)
        fun even(v: Double) = (v.toInt() / 2 * 2).coerceAtLeast(2)
        return even(long * scale) to even(short * scale)
    }

    /**
     * The screen-capture size for the display as it is now: landscape when the display is landscape (a game turned it),
     * portrait otherwise, never stretched ([screenCaptureSize]).
     */
    fun orientedCaptureSize(displayW: Int, displayH: Int, mode: ShareMode = ShareMode.TEXT): Pair<Int, Int> {
        val (long, short) = screenCaptureSize(displayW, displayH, mode)
        return if (displayW > displayH) long to short else short to long
    }

    /**
     * Batch B11: app sound can go with a screen share from Android 10 (API 29, `AudioPlaybackCapture`). It's mixed into
     * the microphone track, so it's sent only while the microphone is on, and apps that forbid capture stay silent.
     */
    fun canShareAppSound(sdk: Int): Boolean = sdk >= 29

    /** Your own picture is mirrored only for the front camera, like a mirror; the back camera shows the world as it is. */
    fun mirrorPreview(isLocal: Boolean, backCamera: Boolean): Boolean = isLocal && !backCamera

    /** Only WebRTC's noise suppressor (see the class comment). */
    const val USE_HARDWARE_NOISE_SUPPRESSOR = false

    /** Microphone processing: WebRTC's echo canceller, noise suppressor, gain control and high-pass filter. */
    const val TYPING_NOISE_DETECTION = false

    /** Opus at up to 48 kbit/s with DTX and RED (LiveKit's own default, now explicit): full-band speech, loss-resilient. */
    const val AUDIO_MAX_BITRATE = 48_000

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

/**
 * What a screen share is tuned for (batch B11, CALL-06), chosen before each share.
 *
 * - [TEXT]: documents, chats and apps. Up to 1920×1080, 15 fps, 2.5 Mbit/s; congestion lowers the frame rate and keeps
 *   text sharp (the round-7 settings, unchanged).
 * - [GAME]: games and video. Up to 1280×720 at 30 fps and 3.5 Mbit/s; congestion lowers sharpness and keeps motion
 *   smooth. 720p is what phones encode at 30 fps without heating up, and what a receiving phone shows anyway.
 *
 * Both use VP8 with one layer. H.264 isn't used yet: hardware H.264 encoders differ by phone, and the other phones'
 * decoders and the frame encryption need checking on real devices first (`MELDIQ_CALLING_SCREENSHARE_AUDIT.md` §4).
 */
enum class ShareMode(val maxLong: Int, val maxShort: Int, val fps: Int, val maxBitrate: Int, val keepFrameRate: Boolean) {
    TEXT(CallMediaPolicy.SCREEN_MAX_LONG, CallMediaPolicy.SCREEN_MAX_SHORT, CallMediaPolicy.SCREEN_FPS, CallMediaPolicy.SCREEN_MAX_BITRATE, keepFrameRate = false),
    GAME(1280, 720, 30, 3_500_000, keepFrameRate = true),
    ;

    companion object {
        fun of(name: String?): ShareMode = entries.firstOrNull { it.name == name } ?: TEXT
    }
}
