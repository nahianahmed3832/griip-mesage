package com.griipbuddy.message.call

import com.griipbuddy.message.data.api.CallDto
import com.griipbuddy.message.data.api.CallParticipantDto
import livekit.org.webrtc.RTCStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Call reachability and media-flow observability (2026-10-16). */
class CallReachabilityTest {
    private val now = 1_000_000L
    private fun call(state: String = "RINGING", createdBy: String = "alice", ageMs: Long = 5_000, participants: List<String> = emptyList()) = CallDto(
        id = "call-1", conversationId = "c", createdBy = createdBy, video = false, state = state, createdAt = now - ageMs,
        participants = participants.map { CallParticipantDto(it, "$it.d", now) },
    )

    @Test
    fun aRingingCallFromSomeoneElseRingsAfterReconnect() {
        assertTrue(RingRecovery.shouldRing(call(), "bob", now, emptySet(), idle = true))
    }

    @Test
    fun recoveryNeverRingsTheWrongCalls() {
        assertFalse("my own call", RingRecovery.shouldRing(call(createdBy = "bob"), "bob", now, emptySet(), true))
        assertFalse("already active", RingRecovery.shouldRing(call(state = "ACTIVE"), "bob", now, emptySet(), true))
        assertFalse("past the ring window", RingRecovery.shouldRing(call(ageMs = 46_000), "bob", now, emptySet(), true))
        assertFalse("declined or answered here", RingRecovery.shouldRing(call(), "bob", now, setOf("call-1"), true))
        assertFalse("answered on my other device", RingRecovery.shouldRing(call(participants = listOf("bob")), "bob", now, emptySet(), true))
        assertFalse("busy in another call", RingRecovery.shouldRing(call(), "bob", now, emptySet(), idle = false))
        assertFalse("signed out", RingRecovery.shouldRing(call(), null, now, emptySet(), true))
    }

    @Test
    fun mediaIsFlowingOnlyWhenInboundBytesGrow() {
        val m = MediaFlowMonitor()
        assertEquals(MediaFlow.Waiting, m.sample(null)) // joined, nothing subscribed
        assertEquals(MediaFlow.Waiting, m.sample(500)) // first sample is only a baseline
        assertEquals(MediaFlow.Waiting, m.sample(500)) // subscribed but nothing arriving (e.g. ICE or key trouble)
        assertEquals(MediaFlow.Flowing, m.sample(9_000))
        assertEquals(MediaFlow.Flowing, m.sample(18_000))
    }

    @Test
    fun aStoppedStreamIsStalledAndRecovers() {
        val m = MediaFlowMonitor(stallSamples = 2)
        m.sample(0); m.sample(1_000)
        assertEquals(MediaFlow.Flowing, m.state)
        assertEquals("one quiet sample is tolerated", MediaFlow.Flowing, m.sample(1_000))
        assertEquals(MediaFlow.Stalled, m.sample(1_000))
        assertEquals(MediaFlow.Flowing, m.sample(5_000))
    }

    @Test
    fun aCounterDropIsANewBaselineNotAStall() {
        val m = MediaFlowMonitor(stallSamples = 1)
        m.sample(0); m.sample(10_000)
        assertEquals(MediaFlow.Flowing, m.sample(200)) // a track was re-subscribed: counters restarted
        assertEquals(MediaFlow.Flowing, m.sample(900))
    }

    private fun s(id: String, type: String, vararg members: Pair<String, Any>) = RTCStats(0L, type, id, mapOf(*members))

    @Test
    fun thePathComesFromTheSelectedPairWithoutAddresses() {
        val udp = listOf(
            s("T1", "transport", "selectedCandidatePairId" to "P1"),
            s("P1", "candidate-pair", "localCandidateId" to "L1", "state" to "succeeded", "nominated" to true),
            s("L1", "local-candidate", "candidateType" to "srflx", "protocol" to "udp", "address" to "203.0.113.9"),
        )
        assertEquals("udp · direct", MediaPath.describe(udp))
        val tcp = listOf(
            s("P2", "candidate-pair", "localCandidateId" to "L2", "state" to "succeeded", "nominated" to true),
            s("L2", "local-candidate", "candidateType" to "host", "protocol" to "tcp"),
        )
        assertEquals("tcp · direct", MediaPath.describe(tcp))
        val relay = listOf(
            s("T", "transport", "selectedCandidatePairId" to "P"),
            s("P", "candidate-pair", "localCandidateId" to "L"),
            s("L", "local-candidate", "candidateType" to "relay", "protocol" to "udp", "relayProtocol" to "tls"),
        )
        assertEquals("relay (tls)", MediaPath.describe(relay))
        assertNull("no pair yet", MediaPath.describe(listOf(s("T", "transport"))))
        assertFalse("never leaks an address", MediaPath.describe(udp)!!.contains("203."))
    }

    @Test
    fun failuresAreNamedByClassNotLumpedTogether() {
        fun api(status: Int, code: String = "x") = com.griipbuddy.message.data.api.ApiException(status, code, "server says")
        assertEquals("This call has already ended.", CallFailure.forApi(api(410)))
        assertEquals("You're not allowed to join this call.", CallFailure.forApi(api(403)))
        assertEquals("Calling isn't set up on this server.", CallFailure.forApi(api(503, "media_unavailable")))
        assertTrue(CallFailure.forApi(api(0)).startsWith("Couldn't reach the server"))
        assertTrue(CallFailure.forConnect(java.util.concurrent.TimeoutException()).contains("didn't answer in time"))
        assertTrue(CallFailure.forConnect(RuntimeException(java.util.concurrent.TimeoutException())).contains("didn't answer in time"))
        assertTrue(CallFailure.forConnect(IllegalStateException()).startsWith("Couldn't connect to the call's media server"))
    }
}
