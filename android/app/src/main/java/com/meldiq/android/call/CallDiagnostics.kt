package com.meldiq.android.call

import android.util.Log
import com.meldiq.android.BuildConfig
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Debug-build media pipeline diagnostics (tag "CallDiag"). Logs, per video track: publication state,
 * subscription/stream state, and WebRTC encode/decode counters, so a black tile can be attributed to
 * capture, publish, forward, subscribe, decrypt/decode or render. Never logs key material, tokens,
 * message content or names — participants are identified by a short identity prefix only.
 */
internal class CallDiagnostics(private val scope: CoroutineScope) {
    private var job: Job? = null

    fun start(room: Room) {
        if (!BuildConfig.DEBUG) return
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                delay(3000)
                runCatching { snapshot(room) }.onFailure { Log.w(TAG, "snapshot failed: ${it.javaClass.simpleName}") }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun log(line: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, line)
    }

    fun event(ev: RoomEvent) {
        if (!BuildConfig.DEBUG) return
        val line = when (ev) {
            is RoomEvent.Connected -> "room connected"
            is RoomEvent.ParticipantConnected -> "participant joined ${who(ev.participant)}"
            is RoomEvent.ParticipantDisconnected -> "participant left ${who(ev.participant)}"
            is RoomEvent.TrackPublished -> "published ${who(ev.participant)} ${pub(ev.publication)}"
            is RoomEvent.TrackUnpublished -> "unpublished ${who(ev.participant)} ${pub(ev.publication)}"
            is RoomEvent.TrackSubscribed -> "subscribed ${who(ev.participant)} ${pub(ev.publication)}"
            is RoomEvent.TrackUnsubscribed -> "unsubscribed ${who(ev.participant)} ${ev.track.kind}"
            is RoomEvent.TrackSubscriptionFailed -> "SUBSCRIPTION FAILED ${who(ev.participant)} ${ev.exception.javaClass.simpleName}: ${ev.exception.message}"
            is RoomEvent.TrackPublicationFailed -> "PUBLICATION FAILED ${ev.track.kind} ${ev.track.javaClass.simpleName}"
            is RoomEvent.TrackMuted -> "muted ${who(ev.participant)} ${pub(ev.publication)}"
            is RoomEvent.TrackUnmuted -> "unmuted ${who(ev.participant)} ${pub(ev.publication)}"
            is RoomEvent.TrackStreamStateChanged -> "stream ${ev.streamState} ${pub(ev.trackPublication)}"
            is RoomEvent.TrackE2EEStateEvent -> "e2ee ${ev.state} ${who(ev.participant)} ${pub(ev.publication)}"
            is RoomEvent.Reconnecting -> "reconnecting"
            is RoomEvent.Reconnected -> "reconnected"
            is RoomEvent.Disconnected -> "disconnected ${ev.reason}"
            else -> return
        }
        Log.i(TAG, line)
    }

    private suspend fun snapshot(room: Room) {
        val lp = room.localParticipant
        val sb = StringBuilder("local mic=${lp.isMicrophoneEnabled} cam=${lp.isCameraEnabled} screen=${lp.isScreenShareEnabled}")
        for ((p, t) in lp.videoTrackPublications) sb.append("\n  out ").append(pub(p)).append(' ').append(stats(t, outbound = true))
        for (rp in room.remoteParticipants.values) {
            sb.append("\nremote ").append(who(rp))
            for ((p, t) in rp.videoTrackPublications) sb.append("\n  in ").append(pub(p)).append(" track=").append(t != null).append(' ').append(stats(t, outbound = false))
        }
        Log.i(TAG, sb.toString())
    }

    private suspend fun stats(t: Track?, outbound: Boolean): String {
        t ?: return "(no track)"
        val report = runCatching { t.getRTCStats() }.getOrNull() ?: return "(no stats)"
        val want = if (outbound) "outbound-rtp" else "inbound-rtp"
        val keys = if (outbound) {
            listOf("rid", "framesEncoded", "framesSent", "frameWidth", "frameHeight", "encoderImplementation", "qualityLimitationReason")
        } else {
            listOf("framesReceived", "framesDecoded", "framesDropped", "keyFramesDecoded", "frameWidth", "frameHeight", "decoderImplementation", "pliCount")
        }
        return report.statsMap.values.filter { it.type == want && it.members["kind"] == "video" }
            .joinToString(" | ") { s -> keys.mapNotNull { k -> s.members[k]?.let { "$k=$it" } }.joinToString(",") }
            .ifEmpty { "(no $want)" }
    }

    private fun pub(p: TrackPublication) = "${p.source}/${p.kind} sid=${p.sid.takeLast(6)} muted=${p.muted}"
    private fun who(p: Participant) = (p.identity?.value ?: "?").take(10)

    companion object {
        const val TAG = "CallDiag"
    }
}
