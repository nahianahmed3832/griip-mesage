package com.meldiq.android.call

import io.livekit.android.e2ee.E2EEState

/**
 * Decides what the call's E2EE badge may claim, from evidence only (one instance per call).
 *
 * WebRTC reports a frame cryptor's state only when it changes: OK (or KEY_RATCHETED) after a frame was
 * actually encrypted or decrypted with our key, MISSING_KEY when a frame names a key we don't have.
 * With LiveKit Android's default failureTolerance (-1) a frame that fails to decrypt, such as one from
 * a peer using the wrong key, is dropped *without any report*: that cryptor simply stays silent.
 * Treating "every state we heard is OK" as verified therefore showed "End-to-end encrypted" for a
 * wrong-key peer, because only our own outgoing OK had been heard.
 *
 * So the badge is [MediaSecurity.E2eeActive] only when every live track (remote subscribed or local
 * published, not muted) has proven itself with OK/KEY_RATCHETED and at least one of them is remote
 * media. A reported failure on any current track is [MediaSecurity.E2eeProblem]. Anything else,
 * including a silent wrong-key track, is [MediaSecurity.Unknown]: not verified.
 *
 * Tracks are keyed like [FrameCryptorGuard.key]. Removed tracks are forgotten, and late callbacks for
 * them are ignored, so they can't revive state. Called from the room-event coroutine and from WebRTC's
 * signaling thread, hence synchronised.
 */
internal class MediaSecurityTracker(private val maxRemembered: Int = 2048) {
    private class Track(val remote: Boolean, var muted: Boolean)

    private val tracks = HashMap<String, Track>()
    /** States by track key. May arrive before the track is registered; only registered tracks count. */
    private val states = HashMap<String, E2EEState>()
    /** Keys of removed tracks: their late callbacks are ignored. Bounded, oldest forgotten first. */
    private val removed = LinkedHashSet<String>()

    @Synchronized
    fun trackAdded(key: String, remote: Boolean, muted: Boolean) {
        if (removed.remove(key)) states.remove(key) // re-added after removal: earlier states belong to the old cryptor
        tracks[key] = Track(remote, muted)
    }

    @Synchronized
    fun trackMuted(key: String, muted: Boolean) {
        tracks[key]?.muted = muted
    }

    @Synchronized
    fun trackRemoved(key: String) {
        tracks.remove(key)
        states.remove(key)
        removed += key
        if (removed.size > maxRemembered) removed.iterator().let { it.next(); it.remove() }
    }

    /** Removes every track of a participant who left. Keys are "identity:sid". */
    @Synchronized
    fun participantLeft(identity: String) {
        val prefix = "$identity:"
        for (k in tracks.keys.filter { it.startsWith(prefix) }) trackRemoved(k)
        for (k in states.keys.filter { it.startsWith(prefix) }) trackRemoved(k)
    }

    /** Records a cryptor state. Returns false if it was ignored (the track was already removed). */
    @Synchronized
    fun state(key: String, state: E2EEState): Boolean {
        if (key in removed) return false
        states[key] = state
        return true
    }

    /** Identities whose tracks report an encryption failure (for a warning on that person's tile). */
    @Synchronized
    fun problemIdentities(): Set<String> =
        tracks.keys.filter { states[it]?.let(::isFailure) == true }.map { it.substringBeforeLast(':') }.toSet()

    @Synchronized
    fun security(): MediaSecurity {
        if (tracks.keys.any { states[it]?.let(::isFailure) == true }) return MediaSecurity.E2eeProblem
        val live = tracks.filterValues { !it.muted }
        val proven = live.isNotEmpty() && live.values.any { it.remote } && live.keys.all { states[it]?.let(::isProven) == true }
        return if (proven) MediaSecurity.E2eeActive else MediaSecurity.Unknown
    }

    companion object {
        fun isProven(s: E2EEState) = s == E2EEState.OK || s == E2EEState.KEY_RATCHETED
        fun isFailure(s: E2EEState) =
            s == E2EEState.DECRYPTION_FAILED || s == E2EEState.ENCRYPTION_FAILED || s == E2EEState.MISSING_KEY || s == E2EEState.INTERNAL_ERROR
    }
}
