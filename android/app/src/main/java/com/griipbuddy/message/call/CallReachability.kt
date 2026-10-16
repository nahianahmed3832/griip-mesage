package com.griipbuddy.message.call

import com.griipbuddy.message.data.api.CallDto

/**
 * Ring recovery: the server rings recipients only over live WebSockets, so a device that was
 * disconnected or reconnecting when a call started never got the ring. After each reconnect the app
 * asks `GET /v1/calls/active`; this decides which of those calls should ring now.
 */
object RingRecovery {
    /** The server marks unanswered 1:1 calls as missed after its ring timeout (45 s by default). */
    const val RING_WINDOW_MS = 45_000L

    fun shouldRing(call: CallDto, me: String?, now: Long, handled: Set<String>, idle: Boolean): Boolean =
        idle &&
            me != null &&
            call.state == "RINGING" &&
            call.createdBy != me &&
            call.id !in handled &&
            now - call.createdAt in 0..RING_WINDOW_MS &&
            call.participants.none { it.userId == me } // already answered on another of my devices
}

/** Whether remote media is actually arriving, from WebRTC inbound byte counters sampled every few seconds. */
enum class MediaFlow { None, Waiting, Flowing, Stalled }

/**
 * Turns successive "total inbound bytes from remote tracks" samples into a [MediaFlow]. A room join or
 * a subscribed track is not proof that media arrives; increasing inbound bytes are. [stallSamples]
 * samples without growth after media had flowed mean the link is stalled (reconnecting or broken).
 */
class MediaFlowMonitor(private val stallSamples: Int = 2) {
    private var last: Long? = null
    private var flowed = false
    private var quiet = 0
    var state: MediaFlow = MediaFlow.None
        private set

    /** [inboundBytes] is null when there is no remote track to measure yet. */
    fun sample(inboundBytes: Long?): MediaFlow {
        if (inboundBytes == null) {
            // No remote track to measure (nobody publishing yet, or everyone else left): waiting, not broken.
            reset()
            state = MediaFlow.Waiting
            return state
        }
        val prev = last
        last = inboundBytes
        // A drop means the set of tracks changed (re-subscribed, one left): a new baseline, not a stall.
        if (prev == null || inboundBytes < prev) {
            if (!flowed) state = MediaFlow.Waiting
            return state
        }
        val grew = inboundBytes > prev
        if (grew) {
            flowed = true
            quiet = 0
            state = MediaFlow.Flowing
        } else if (flowed) {
            quiet++
            if (quiet >= stallSamples) state = MediaFlow.Stalled
        } else {
            state = MediaFlow.Waiting
        }
        return state
    }

    fun reset() {
        last = null
        flowed = false
        quiet = 0
        state = MediaFlow.None
    }
}

/**
 * The network path the call took, from WebRTC's selected candidate pair, without any addresses:
 * e.g. "udp · direct", "tcp · direct" (UDP blocked or slow), "relay (tls)". Null until ICE has a pair.
 */
object MediaPath {
    fun describe(stats: Collection<livekit.org.webrtc.RTCStats>): String? {
        val byId = stats.associateBy { it.id }
        val transport = stats.firstOrNull { it.type == "transport" }
        val pairId = transport?.members?.get("selectedCandidatePairId") as? String
        val pair = pairId?.let { byId[it] }
            ?: stats.firstOrNull { it.type == "candidate-pair" && it.members["state"] == "succeeded" && it.members["nominated"] == true }
            ?: return null
        val local = byId[pair.members["localCandidateId"] as? String] ?: return null
        val protocol = (local.members["protocol"] as? String)?.lowercase() ?: "?"
        return when (local.members["candidateType"] as? String) {
            "relay" -> "relay (${(local.members["relayProtocol"] as? String)?.lowercase() ?: protocol})"
            null -> null
            else -> "$protocol · direct"
        }
    }
}

/**
 * User-facing call failure messages, one per failure class, so a token problem, an ended call, a
 * missing media server, a join timeout and a capture failure don't all read "something went wrong".
 */
object CallFailure {
    const val MICROPHONE = "Couldn't start the microphone. Check that Griip Message may use it."
    const val CAMERA = "Couldn't start the camera. Check that Griip Message may use it."

    fun forApi(e: com.griipbuddy.message.data.api.ApiException): String = when {
        e.status == 410 -> "This call has already ended."
        e.status == 404 -> "This call no longer exists."
        e.status == 401 || e.status == 403 -> "You're not allowed to join this call."
        e.status == 503 || e.code == "media_unavailable" -> "Calling isn't set up on this server."
        e.code == "no_key" -> e.message
        e.status == 0 -> "Couldn't reach the server. Check your connection and try again."
        else -> e.message
    }

    /** Joining the media room failed after the server had authorised it. Exception names only, no details. */
    fun forConnect(e: Throwable): String {
        val name = generateSequence(e) { it.cause }.take(4).joinToString(" ") { it.javaClass.simpleName }
        return when {
            "Timeout" in name -> "The call's media server didn't answer in time. The network may block it; try another network."
            "Unauthorized" in name || "Auth" in name -> "The call server refused this device's call pass. Try calling again."
            else -> "Couldn't connect to the call's media server. Check your connection and try again."
        }
    }
}
