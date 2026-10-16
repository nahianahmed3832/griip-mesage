package com.griipbuddy.orbit.call

import io.livekit.android.e2ee.E2EEState
import io.livekit.android.e2ee.E2EEState.DECRYPTION_FAILED
import io.livekit.android.e2ee.E2EEState.INTERNAL_ERROR
import io.livekit.android.e2ee.E2EEState.KEY_RATCHETED
import io.livekit.android.e2ee.E2EEState.MISSING_KEY
import io.livekit.android.e2ee.E2EEState.NEW
import io.livekit.android.e2ee.E2EEState.OK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The E2EE badge claims "End-to-end encrypted" only on evidence: every live track has encrypted or
 * decrypted with our key, including at least one remote track. A wrong-key peer, which WebRTC never
 * reports with failureTolerance -1, must not look verified (device test R3, 2026-10-16).
 */
class MediaSecurityTrackerTest {
    private val t = MediaSecurityTracker(maxRemembered = 4)
    private val mic = "me.d1:TR_mic"            // local, published
    private val peerMic = "peer.d2:TR_pmic"     // remote, subscribed
    private val peerCam = "peer.d2:TR_pcam"

    private fun call() = t.apply {
        trackAdded(mic, remote = false, muted = false)
        trackAdded(peerMic, remote = true, muted = false)
    }

    @Test
    fun matchingKeysAreVerifiedOnceBothDirectionsProveThemselves() {
        call()
        assertEquals(MediaSecurity.Unknown, t.security())
        t.state(mic, OK)
        assertEquals("our own encryption alone proves nothing about the peer's media", MediaSecurity.Unknown, t.security())
        t.state(peerMic, OK)
        assertEquals(MediaSecurity.E2eeActive, t.security())
    }

    @Test
    fun aSilentWrongKeyPeerIsNotShownAsVerified() {
        // R3: the phone encrypts fine (OK) while the peer's frames fail to decrypt and are dropped silently.
        call()
        t.state(mic, OK)
        assertEquals(MediaSecurity.Unknown, t.security())
    }

    @Test
    fun aMissingKeyIsAProblemAndRecoversWhenTheKeyArrives() {
        call()
        t.state(mic, OK)
        t.state(peerMic, MISSING_KEY)
        assertEquals(MediaSecurity.E2eeProblem, t.security())
        t.state(peerMic, OK)
        assertEquals(MediaSecurity.E2eeActive, t.security())
    }

    @Test
    fun keyChangesLeaveNoStaleStatus() {
        call()
        t.state(mic, OK)
        t.state(peerMic, OK)
        t.state(peerMic, MISSING_KEY) // the sender moved to a key index we don't have yet
        assertEquals(MediaSecurity.E2eeProblem, t.security())
        t.state(peerMic, KEY_RATCHETED)
        assertEquals(MediaSecurity.E2eeActive, t.security())
        t.state(peerMic, DECRYPTION_FAILED) // e.g. a malformed trailer
        assertEquals(MediaSecurity.E2eeProblem, t.security())
        t.state(peerMic, OK)
        assertEquals(MediaSecurity.E2eeActive, t.security())
    }

    @Test
    fun everyLiveRemoteTrackMustProveItself() {
        call()
        t.trackAdded(peerCam, remote = true, muted = false)
        t.state(mic, OK)
        t.state(peerMic, OK)
        assertEquals("the camera hasn't decrypted a frame yet", MediaSecurity.Unknown, t.security())
        t.state(peerCam, OK)
        assertEquals(MediaSecurity.E2eeActive, t.security())
    }

    @Test
    fun onePeerWithTheWrongKeyInAGroupKeepsTheCallUnverified() {
        call()
        t.trackAdded("other.d3:TR_omic", remote = true, muted = false)
        t.state(mic, OK)
        t.state(peerMic, OK) // the other participant stays silent: wrong key
        assertEquals(MediaSecurity.Unknown, t.security())
    }

    @Test
    fun aRemovedTrackStopsCountingAndItsLateCallbacksAreIgnored() {
        call()
        t.trackAdded(peerCam, remote = true, muted = false)
        t.state(mic, OK)
        t.state(peerMic, OK)
        t.state(peerCam, MISSING_KEY)
        assertEquals(MediaSecurity.E2eeProblem, t.security())
        t.trackRemoved(peerCam) // unsubscribed: its failure no longer describes the call
        assertEquals(MediaSecurity.E2eeActive, t.security())
        assertFalse("a callback after removal is ignored", t.state(peerCam, DECRYPTION_FAILED))
        assertEquals(MediaSecurity.E2eeActive, t.security())
    }

    @Test
    fun aTrackAddedAgainStartsUnprovenInsteadOfReusingTheOldCryptorsState() {
        call()
        t.state(mic, OK)
        t.state(peerMic, OK)
        t.trackRemoved(peerMic)
        t.trackAdded(peerMic, remote = true, muted = false)
        assertEquals(MediaSecurity.Unknown, t.security())
        t.state(peerMic, OK)
        assertEquals(MediaSecurity.E2eeActive, t.security())
    }

    @Test
    fun aStateThatArrivesBeforeItsTrackIsRegisteredStillCounts() {
        t.trackAdded(mic, remote = false, muted = false)
        t.state(mic, OK)
        t.state(peerMic, OK) // the cryptor's first report raced its TrackSubscribed event
        assertEquals(MediaSecurity.Unknown, t.security())
        t.trackAdded(peerMic, remote = true, muted = false)
        assertEquals(MediaSecurity.E2eeActive, t.security())
    }

    @Test
    fun aParticipantWhoLeftIsForgottenOthersAreKept() {
        call()
        t.trackAdded("other.d3:TR_omic", remote = true, muted = false)
        t.state(mic, OK)
        t.state(peerMic, OK)
        t.state("other.d3:TR_omic", MISSING_KEY)
        assertEquals(MediaSecurity.E2eeProblem, t.security())
        t.participantLeft("other.d3")
        assertEquals(MediaSecurity.E2eeActive, t.security())
        assertFalse(t.state("other.d3:TR_omic", OK))
    }

    @Test
    fun mutedTracksNeedNoProofButTheirFailuresStillShow() {
        call()
        t.trackAdded(peerCam, remote = true, muted = true) // camera off: no frames, so no proof possible
        t.state(mic, OK)
        t.state(peerMic, OK)
        assertEquals(MediaSecurity.E2eeActive, t.security())
        t.trackMuted(peerCam, false)
        assertEquals("unmuted: now it has to prove itself", MediaSecurity.Unknown, t.security())
        t.trackMuted(peerCam, true)
        t.state(peerCam, INTERNAL_ERROR)
        assertEquals(MediaSecurity.E2eeProblem, t.security())
    }

    @Test
    fun noRemoteMediaMeansNotVerified() {
        t.trackAdded(mic, remote = false, muted = false)
        t.state(mic, OK)
        assertEquals(MediaSecurity.Unknown, t.security())
    }

    @Test
    fun aDroppedOrFailedCallbackNeverBecomesAPositiveStatus() {
        // When the guard's callback handling fails, no state is recorded; the track stays unproven.
        call()
        t.state(mic, OK)
        t.state(peerMic, NEW)
        assertEquals(MediaSecurity.Unknown, t.security())
    }

    @Test
    fun aNewCallStartsFromNothing() {
        call()
        t.state(mic, OK)
        t.state(peerMic, MISSING_KEY)
        assertEquals(MediaSecurity.E2eeProblem, t.security())
        val next = MediaSecurityTracker() // CallManager creates one per call and drops the old one
        assertEquals(MediaSecurity.Unknown, next.security())
    }

    @Test
    fun theRemovedListIsBounded() {
        for (i in 1..10) t.trackRemoved("peer.d2:TR_$i")
        // The oldest are forgotten: an ancient key's late callback is recorded again but counts only if re-registered.
        assertEquals(true, t.state("peer.d2:TR_1", OK))
        assertFalse(t.state("peer.d2:TR_10", OK))
    }

    @Test
    fun liveKitStillHidesDecryptionFailuresByDefault() {
        // This class assumes failureTolerance -1: failures are silent, so verification needs positive proof.
        // If LiveKit changes the default, re-check whether DECRYPTION_FAILED can now be shown directly.
        // Kotlin-internal in the SDK (E2EEOptions.kt), but a public static constant on the JVM; BaseKeyProvider() uses it.
        val tolerance = Class.forName("io.livekit.android.e2ee.E2EEOptionsKt").getField("defaultFailureTolerance").getInt(null)
        assertEquals(-1, tolerance)
    }

    @Suppress("unused")
    private fun allStates() = E2EEState.values() // keeps the import list honest if states are added
}
