package com.griipbuddy.orbit.call

import kotlin.math.log10
import kotlin.math.roundToInt

/** Where call audio plays (UI-003). */
enum class AudioRouteKind { Speaker, Earpiece, Bluetooth, Wired, Other }

/** One output the phone offers for this call; [name] is the device's own name (a Bluetooth headset's, for example). */
data class AudioRoute(val kind: AudioRouteKind, val name: String = "") {
    val label: String
        get() = when (kind) {
            AudioRouteKind.Speaker -> "Speaker"
            AudioRouteKind.Earpiece -> "Phone"
            AudioRouteKind.Bluetooth -> name.ifBlank { "Bluetooth" }
            AudioRouteKind.Wired -> "Headphones"
            AudioRouteKind.Other -> name.ifBlank { "Other" }
        }
}

/**
 * How call audio is routed (UI-003; unit-tested in `CallAudioTest`). LiveKit's AudioSwitchHandler owns the phone's
 * audio (communication mode, audio focus, and on Android 12+ `setCommunicationDevice`); Orbit only tells it the order
 * of preference and the person's choice, and reads back what it actually selected. Nothing else in the app changes the
 * audio mode or the route, so there is a single owner.
 *
 * Before UI-003 the speaker button was swapped for "Flip camera" whenever the camera was on, so video calls and screen
 * sharing had no way to change the output; group calls started from a chat went to the earpiece while joined ones went
 * to the speaker; and LiveKit's own default (speaker before earpiece) applied until the device list arrived.
 */
object CallAudioPolicy {
    /** Automatic choice: a headset first; then the speaker for video and group calls, the earpiece for 1:1 voice calls. */
    fun preferred(video: Boolean, isGroup: Boolean): List<AudioRouteKind> =
        if (video || isGroup) {
            listOf(AudioRouteKind.Bluetooth, AudioRouteKind.Wired, AudioRouteKind.Speaker, AudioRouteKind.Earpiece)
        } else {
            listOf(AudioRouteKind.Bluetooth, AudioRouteKind.Wired, AudioRouteKind.Earpiece, AudioRouteKind.Speaker)
        }

    /** With only the speaker and the earpiece the button switches between them; anything else opens the list. */
    fun needsPicker(routes: List<AudioRoute>): Boolean =
        routes.any { it.kind != AudioRouteKind.Speaker && it.kind != AudioRouteKind.Earpiece }

    /** What one tap on the button selects when [needsPicker] is false: the other of speaker and earpiece. */
    fun toggleTarget(current: AudioRoute?, routes: List<AudioRoute>): AudioRoute? {
        val want = if (current?.kind == AudioRouteKind.Speaker) AudioRouteKind.Earpiece else AudioRouteKind.Speaker
        return routes.firstOrNull { it.kind == want }
    }

    /** A headset or Bluetooth device appeared during the call: it takes over, even after the person picked another route. */
    fun headsetArrived(before: List<AudioRoute>, after: List<AudioRoute>): Boolean =
        after.any { (it.kind == AudioRouteKind.Bluetooth || it.kind == AudioRouteKind.Wired) && it !in before }

    /** Turning the camera on in a call on the earpiece moves it to the speaker, unless the person chose the earpiece. */
    fun speakerForVideo(current: AudioRoute?, userChose: AudioRouteKind?): Boolean =
        current?.kind == AudioRouteKind.Earpiece && userChose != AudioRouteKind.Earpiece

    /** Whether the route the phone reports is the one asked for (a Bluetooth device is matched by name when it has one). */
    fun matches(asked: AudioRoute, actual: AudioRoute?): Boolean =
        actual != null && actual.kind == asked.kind && (asked.kind != AudioRouteKind.Bluetooth || asked.name.isBlank() || actual.name.isBlank() || asked.name == actual.name)

    fun routeFailed(asked: AudioRoute): String = "Couldn't switch the sound to ${asked.label}. Try again, or pick another output."

    /** Call volume at or below 30 % of the maximum is named as a likely reason for quiet sound. */
    fun volumeLow(current: Int, max: Int): Boolean = max > 0 && current * 10 <= max * 3

    fun describeVolume(current: Int, max: Int): String = "$current of $max" + if (volumeLow(current, max)) " (low)" else ""
}

/** What arrives in one direction of a call's audio, from WebRTC statistics (UI-003 loudness diagnostics). */
data class AudioStats(val levelDb: Int?, val lossPercent: Double?, val jitterMs: Int?, val concealedPercent: Double?) {
    fun describe(): String = buildList {
        add(levelDb?.let { if (it <= SILENT_DB) "silent" else "level $it dBFS" } ?: "level —")
        lossPercent?.let { add("loss ${"%.1f".format(java.util.Locale.ROOT, it)}%") }
        jitterMs?.let { add("jitter $it ms") }
        concealedPercent?.let { add("concealed ${"%.1f".format(java.util.Locale.ROOT, it)}%") }
    }.joinToString(" · ")

    companion object {
        const val SILENT_DB = -60
    }
}

/**
 * Turns WebRTC audio stats (type + members, as `RTCStats` gives them) into [AudioStats]: the level as dBFS, averaged over
 * the time since the last sample from `totalAudioEnergy` / `totalSamplesDuration` (a single `audioLevel` reading every
 * two seconds missed speech on the phone), else the instantaneous `audioLevel`; and packet loss and concealment
 * ("choppy" sound) from the growth of their counters between two samples of the same stream. Measured numbers only:
 * nothing here changes a gain.
 */
class AudioStatsSampler {
    private data class Counters(val received: Long, val lost: Long, val samples: Long, val concealed: Long)
    private val last = HashMap<String, Counters>()
    private val energy = HashMap<String, Pair<Double, Double>>() // key -> (totalAudioEnergy, totalSamplesDuration)

    /** [entries]: one remote audio track's stats. */
    fun inbound(key: String, entries: List<Pair<String, Map<String, Any?>>>): AudioStats? {
        val s = entries.firstOrNull { it.first == "inbound-rtp" && (it.second["kind"] ?: it.second["mediaType"]) == "audio" }?.second ?: return null
        val now = Counters(
            received = s.long("packetsReceived"), lost = s.long("packetsLost"),
            samples = s.long("totalSamplesReceived"), concealed = s.long("concealedSamples"),
        )
        val prev = last[key]
        last[key] = now
        val loss = prev?.let { p ->
            val got = now.received - p.received
            val lost = now.lost - p.lost
            if (got >= 0 && lost >= 0 && got + lost > 0) lost * 100.0 / (got + lost) else null
        }
        val concealed = prev?.let { p ->
            val total = now.samples - p.samples
            val hidden = now.concealed - p.concealed
            if (total > 0 && hidden >= 0) hidden * 100.0 / total else null
        }
        return AudioStats(level(key, s), loss, (s["jitter"] as? Number)?.let { (it.toDouble() * 1000).roundToInt() }, concealed)
    }

    /** The microphone's level as sent ("media-source" of kind audio), for "is my microphone heard". */
    fun outboundLevel(entries: List<Pair<String, Map<String, Any?>>>): Int? =
        entries.firstOrNull { it.first == "media-source" && it.second["kind"] == "audio" }?.second?.let { level("mic", it) }

    /** Average level since the previous sample of [key] when the energy counters are there, else the instant level. */
    private fun level(key: String, s: Map<String, Any?>): Int? {
        val e = (s["totalAudioEnergy"] as? Number)?.toDouble()
        val d = (s["totalSamplesDuration"] as? Number)?.toDouble()
        if (e != null && d != null) {
            val prev = energy.put(key, e to d)
            if (prev != null && d > prev.second && e >= prev.first) return rmsDb((e - prev.first) / (d - prev.second))
        }
        return dbfs(s["audioLevel"])
    }

    fun forget(keep: Set<String>) {
        last.keys.retainAll(keep)
        energy.keys.retainAll(keep + "mic")
    }

    private fun Map<String, Any?>.long(k: String) = (this[k] as? Number)?.toLong() ?: 0L

    companion object {
        /** `audioLevel` (linear, 0..1) as dBFS, floored at [AudioStats.SILENT_DB]. */
        fun dbfs(level: Any?): Int? {
            val v = (level as? Number)?.toDouble() ?: return null
            if (v <= 0.001) return AudioStats.SILENT_DB
            return (20 * log10(v.coerceAtMost(1.0))).roundToInt().coerceAtLeast(AudioStats.SILENT_DB)
        }

        /** Mean energy per second of audio (WebRTC's level² integrated over time) as dBFS. */
        fun rmsDb(meanEnergy: Double): Int = if (meanEnergy <= 1e-6) AudioStats.SILENT_DB else (10 * log10(meanEnergy.coerceAtMost(1.0))).roundToInt().coerceAtLeast(AudioStats.SILENT_DB)
    }
}
