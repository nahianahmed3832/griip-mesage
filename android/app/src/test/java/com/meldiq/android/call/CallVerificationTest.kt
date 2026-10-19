package com.meldiq.android.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Call verification codes, reconnection rules and picture-in-picture eligibility (2026-10-17). */
class CallVerificationTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun hkdfMatchesRfc5869() {
        // RFC 5869, test case 1 (SHA-256).
        val okm = CallVerification.hkdf(ByteArray(22) { 0x0b }, hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun bothEndsOfTheSameKeyAndCallSeeTheSameCode() {
        val key = "q83vEjRWeJA0ZVYDAhXzG7m0z1aE0t7K1oVQ3m0X9iY="
        val a = CallVerification.code(key, "call_1")
        val b = CallVerification.code(key, "call_1")
        assertEquals(a, b)
        assertEquals(4, a.emoji.size)
        assertTrue(a.emoji.all { it in CallVerification.EMOJI })
        assertTrue(Regex("""\d{3} \d{3}""").matches(a.digits))
        // A substituted key or another call gives a different code.
        assertNotEquals(a, CallVerification.code("q83vEjRWeJA0ZVYDAhXzG7m0z1aE0t7K1oVQ3m0X9iZ=", "call_1"))
        assertNotEquals(a, CallVerification.code(key, "call_2"))
        assertEquals(64, CallVerification.EMOJI.toSet().size) // 6 bits per emoji, no duplicates
    }

    @Test
    fun onlyConnectionLossesAreRetried() {
        listOf(null, "UNKNOWN_REASON", "SIGNAL_CLOSE", "CONNECTION_TIMEOUT", "MEDIA_FAILURE", "JOIN_FAILURE").forEach { assertTrue(it.toString(), CallRecovery.retryable(it)) }
        listOf("CLIENT_INITIATED", "PARTICIPANT_REMOVED", "ROOM_DELETED", "DUPLICATE_IDENTITY", "ROOM_CLOSED").forEach { assertFalse(it, CallRecovery.retryable(it)) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 6_000L, 6_000L), (0..4).map(CallRecovery::backoff))
        assertTrue(CallRecovery.PEER_WINDOW_MS in 15_000..60_000)
    }

    @Test
    fun pictureInPictureOnlyDuringACall() {
        assertTrue(CallPip.eligible(CallPhase.Connected))
        assertTrue(CallPip.eligible(CallPhase.Reconnecting))
        assertFalse(CallPip.eligible(CallPhase.Incoming))
        assertFalse(CallPip.eligible(CallPhase.Outgoing))
        assertFalse(CallPip.eligible(CallPhase.Idle))
    }

    @Test
    fun pictureInPictureForVideoCallsOnlyAndNeverWhileSharing() {
        val connected = CallUiState(phase = CallPhase.Connected)
        val remoteCam = ParticipantUi("b.1", "b", "Bea", isLocal = false, speaking = false, micOn = true, camera = null, screen = null, cameraOn = true)
        val remoteVoice = remoteCam.copy(cameraOn = false)
        assertTrue(CallPip.eligible(connected.copy(video = true)))
        assertTrue(CallPip.eligible(connected.copy(participants = listOf(remoteCam)))) // a voice call where the other person turned video on
        // A voice call carries on in the background with its notification instead (UI-003).
        assertFalse(CallPip.eligible(connected.copy(participants = listOf(remoteVoice))))
        // Sharing this phone's screen: the window would be captured into the share and cover what's shown.
        assertFalse(CallPip.eligible(connected.copy(video = true, screenSharing = true)))
        assertFalse(CallPip.eligible(CallUiState(phase = CallPhase.Incoming, video = true)))
    }
}
