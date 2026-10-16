package com.griipbuddy.message.call

import io.livekit.android.e2ee.E2EEState
import livekit.org.webrtc.FrameCryptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCryptorGuardTest {
    private val states = mutableListOf<Pair<String, E2EEState>>()
    private var late = 0

    @Test
    fun observerNeverThrowsIntoWebRtc() {
        // An exception escaping this callback reaches WebRTC's JNI check and aborts the process.
        val throwingState = FrameCryptorGuard.safeObserver("p:TR_1", { false }, { _, _ -> throw NullPointerException() }, {})
        throwingState.onFrameCryptionStateChanged("p", FrameCryptor.FrameCryptionState.OK)
        val throwingCheck = FrameCryptorGuard.safeObserver("p:TR_1", { throw IllegalStateException() }, { _, _ -> }, {})
        throwingCheck.onFrameCryptionStateChanged("p", FrameCryptor.FrameCryptionState.MISSINGKEY)
    }

    @Test
    fun failuresInsideTheCallbackAreReportedNotHidden() {
        val errors = mutableListOf<Throwable>()
        val observer = FrameCryptorGuard.safeObserver("p:TR_1", { false }, { _, _ -> throw IllegalStateException("badge") }, {}, { errors += it })
        observer.onFrameCryptionStateChanged("p", FrameCryptor.FrameCryptionState.DECRYPTIONFAILED)
        assertEquals(listOf("badge"), errors.map { it.message })
        // Even a failing error reporter must not reach WebRTC.
        val worst = FrameCryptorGuard.safeObserver("p:TR_1", { false }, { _, _ -> throw IllegalStateException() }, {}, { throw IllegalStateException() })
        worst.onFrameCryptionStateChanged("p", FrameCryptor.FrameCryptionState.OK)
    }

    @Test
    fun copyingTheSdkMapSurvivesConcurrentChanges() {
        // The SDK changes its LinkedHashMap on its own threads; a copy can fail in more ways than ConcurrentModificationException.
        var attempts = 0
        val flaky = FrameCryptorGuard.snapshot {
            attempts++
            if (attempts == 1) throw ConcurrentModificationException()
            if (attempts == 2) throw NoSuchElementException()
            mapOf("k" to 1)
        }
        assertEquals(mapOf("k" to 1), flaky)
        assertEquals(null, FrameCryptorGuard.snapshot<String, Int> { throw NullPointerException() }) // gives up instead of throwing
    }

    @Test
    fun reportsStatesWhileTheTrackExists() {
        val observer = FrameCryptorGuard.safeObserver("p:TR_1", { false }, { k, s -> states += k to s }, { late++ })
        observer.onFrameCryptionStateChanged("p", FrameCryptor.FrameCryptionState.MISSINGKEY)
        observer.onFrameCryptionStateChanged("p", FrameCryptor.FrameCryptionState.OK)
        assertEquals(listOf("p:TR_1" to E2EEState.MISSING_KEY, "p:TR_1" to E2EEState.OK), states)
        assertEquals(0, late)
    }

    @Test
    fun dropsCallbacksAfterTheTrackWasCleared() {
        // The case that aborted with LiveKit 2.29.0's observer, which evaluates publication.track!!.
        var trackGone = false
        val observer = FrameCryptorGuard.safeObserver("p:TR_1", { trackGone }, { k, s -> states += k to s }, { late++ })
        observer.onFrameCryptionStateChanged("p", FrameCryptor.FrameCryptionState.OK)
        trackGone = true
        observer.onFrameCryptionStateChanged("p", FrameCryptor.FrameCryptionState.MISSINGKEY)
        assertEquals(listOf("p:TR_1" to E2EEState.OK), states)
        assertEquals(1, late)
    }

    @Test
    fun mapsEveryWebRtcStateLikeTheSdk() {
        for (s in FrameCryptor.FrameCryptionState.values()) {
            assertEquals(s.name, FrameCryptorGuard.e2eeState(s).name.replace("_", ""))
        }
        assertEquals(E2EEState.INTERNAL_ERROR, FrameCryptorGuard.e2eeState(null))
    }

    @Test
    fun keyMatchesTrackE2EEStateEventKey() {
        assertEquals("u_1.d_2:TR_abc", FrameCryptorGuard.key("u_1.d_2", "TR_abc"))
    }

    @Test
    fun liveKitStillKeepsCryptorsWhereTheGuardLooks() {
        // Fails after a LiveKit upgrade that moves E2EEManager.frameCryptors: re-check the guard (and whether
        // the SDK fixed its observer) before changing this test.
        val field = FrameCryptorGuard.cryptorsField
        assertNotNull("E2EEManager.frameCryptors is gone; FrameCryptorGuard would silently do nothing", field)
        assertTrue(Map::class.java.isAssignableFrom(field!!.type))
        assertNotNull(FrameCryptor::class.java.getMethod("setObserver", FrameCryptor.Observer::class.java))
    }
}
