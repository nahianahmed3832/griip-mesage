package com.meldiq.android.call

import io.livekit.android.e2ee.E2EEManager
import io.livekit.android.e2ee.E2EEState
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.TrackPublication
import livekit.org.webrtc.FrameCryptor
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps LiveKit's frame-cryptor state callbacks from aborting the process (docs/KNOWN_ISSUES.md › Calls).
 *
 * LiveKit Android 2.29.0's E2EEManager sets an observer on every FrameCryptor that evaluates
 * `publication.track!!`. RemoteParticipant.unpublishTrack clears that track, yet the cryptor can still
 * report afterwards: a state change already queued on WebRTC's signaling thread when the SDK disposes the
 * cryptor, or, on disconnect, a cryptor the SDK never disposes (Room.cleanupRoom drops its E2EEManager
 * before unpublishing the remote tracks). The NullPointerException is thrown inside a JNI callback, so
 * WebRTC's exception check aborts the process: the SIGABRT on signaling_thread seen on 2026-10-16.
 *
 * As soon as the SDK has set up a cryptor we replace its observer with one that cannot throw and reports
 * through [onState] instead of RoomEvent.TrackE2EEStateEvent. Encryption is untouched: the same cryptor,
 * key provider, keys and enabled flag; only the status callback changes.
 *
 * The cryptors are only reachable through E2EEManager's private `frameCryptors` map, so this depends on
 * SDK internals; FrameCryptorGuardTest fails if a LiveKit upgrade moves them. If the field is missing at
 * runtime the guard does nothing and the SDK behaves as before.
 */
internal class FrameCryptorGuard(
    private val onState: (key: String, state: E2EEState) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private val replaced: MutableSet<FrameCryptor> = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap()))
    private val late = AtomicInteger()
    private val failed = AtomicInteger()
    @Volatile private var manager: E2EEManager? = null

    /** Callbacks dropped because their publication or track was gone (LiveKit's observer throws on a cleared remote track). */
    val lateCallbacks: Int get() = late.get()

    /** Callbacks whose own handling threw. They are counted and logged, never rethrown into WebRTC. */
    val failedCallbacks: Int get() = failed.get()

    /**
     * Call on TrackSubscribed / TrackPublished. The SDK posts those events only after it has created the
     * track's cryptor and set its own observer, so ours is not overwritten.
     */
    fun guard(room: Room, publication: TrackPublication, participant: Participant) {
        // Runs on the room-event coroutine: anything thrown here would crash the app, which the guard must never do.
        try {
            if (publication.track == null) return // already unpublished; the SDK has disposed its cryptor
            val cryptor = cryptors(room)?.get(publication.sid to participant.identity) ?: return
            replace(cryptor, key(participant.identity?.value, publication.sid), WeakReference(publication))
        } catch (e: Exception) {
            log("frame-cryptor guard skipped a track: ${e.javaClass.simpleName}") // the SDK's own observer stays in place
        }
    }

    /** Before disconnecting: cover cryptors whose TrackSubscribed event was never handled. */
    fun sweep(room: Room) {
        val all = cryptors(room) ?: return log("frame-cryptor guard: no cryptors reachable, nothing to sweep")
        var n = 0
        for ((k, cryptor) in all) {
            if (cryptor in replaced) continue
            val sid = (k as? Pair<*, *>)?.first as? String ?: continue
            val identity = (k as Pair<*, *>).second as? Participant.Identity ?: continue
            // A participant who already left has no publication any more: its callbacks count as late.
            val publication = if (identity == room.localParticipant.identity) {
                room.localParticipant.trackPublications[sid]
            } else {
                room.remoteParticipants[identity]?.trackPublications?.get(sid)
            }
            if (replace(cryptor, key(identity.value, sid), WeakReference(publication))) n++
        }
        log("frame-cryptor guard: ${replaced.size} observers replaced ($n at teardown), ${late.get()} late callbacks ignored")
    }

    private fun replace(cryptor: FrameCryptor, key: String, publication: WeakReference<TrackPublication?>): Boolean {
        val observer = safeObserver(
            key = key,
            trackGone = { publication.get()?.track == null },
            onState = onState,
            onLate = { late.incrementAndGet(); log("late frame-cryptor callback for ${key.takeLast(6)} ignored (track already cleared)") },
            onError = { failed.incrementAndGet(); log("frame-cryptor state callback for ${key.takeLast(6)} failed: ${it.javaClass.simpleName}") },
        )
        return try {
            cryptor.setObserver(observer)
            replaced += cryptor
            true
        } catch (e: IllegalStateException) {
            false // disposed in the meantime ("FrameCryptor has been disposed")
        }
    }

    /** A snapshot of E2EEManager.frameCryptors, keyed by (track sid, participant identity). */
    private fun cryptors(room: Room): Map<Any?, FrameCryptor>? {
        // When the SDK disconnects (also when the server ends the call) Room.cleanupRoom drops its reference,
        // but the cryptors it leaves running are the ones that most need the guard, so keep our own.
        val manager = room.e2eeManager?.also { manager = it } ?: manager ?: return null
        val field = cryptorsField ?: return null
        @Suppress("UNCHECKED_CAST")
        return snapshot { field.get(manager) as Map<Any?, FrameCryptor> }
    }

    companion object {
        /** E2EEManager's private `Map<Pair<String, Participant.Identity>, FrameCryptor>`; null if renamed. */
        val cryptorsField: Field? by lazy {
            runCatching { E2EEManager::class.java.getDeclaredField("frameCryptors").apply { isAccessible = true } }.getOrNull()
        }

        /**
         * Copies the SDK's map. It's an unsynchronised LinkedHashMap that the SDK changes on its own threads, so a
         * copy can fail with ConcurrentModificationException, NoSuchElementException or worse; retry, then give up.
         */
        fun <K, V> snapshot(read: () -> Map<K, V>): Map<K, V>? {
            repeat(3) {
                try {
                    return HashMap(read())
                } catch (e: RuntimeException) {
                    // changed while we copied; try again
                }
            }
            return null
        }

        /** Same key format CallManager uses for RoomEvent.TrackE2EEStateEvent. */
        fun key(identity: String?, sid: String?) = "$identity:$sid"

        /**
         * An observer that never lets anything escape into WebRTC's JNI callback: an exception there aborts the
         * process. Callbacks for a track that has already been cleared are dropped.
         */
        fun safeObserver(
            key: String,
            trackGone: () -> Boolean,
            onState: (String, E2EEState) -> Unit,
            onLate: () -> Unit,
            onError: (Throwable) -> Unit = {},
        ) = FrameCryptor.Observer { _, state ->
            try {
                if (trackGone()) onLate() else onState(key, e2eeState(state))
            } catch (t: Throwable) {
                // Never rethrown (see above), but reported, so a stuck security badge leaves a trace.
                try {
                    onError(t)
                } catch (_: Throwable) {
                }
            }
        }

        /** Mirrors E2EEManager.e2eeStateFromFrameCryptoState (private in the SDK). */
        fun e2eeState(state: FrameCryptor.FrameCryptionState?): E2EEState = when (state) {
            FrameCryptor.FrameCryptionState.NEW -> E2EEState.NEW
            FrameCryptor.FrameCryptionState.OK -> E2EEState.OK
            FrameCryptor.FrameCryptionState.KEYRATCHETED -> E2EEState.KEY_RATCHETED
            FrameCryptor.FrameCryptionState.MISSINGKEY -> E2EEState.MISSING_KEY
            FrameCryptor.FrameCryptionState.ENCRYPTIONFAILED -> E2EEState.ENCRYPTION_FAILED
            FrameCryptor.FrameCryptionState.DECRYPTIONFAILED -> E2EEState.DECRYPTION_FAILED
            FrameCryptor.FrameCryptionState.INTERNALERROR, null -> E2EEState.INTERNAL_ERROR
        }
    }
}
