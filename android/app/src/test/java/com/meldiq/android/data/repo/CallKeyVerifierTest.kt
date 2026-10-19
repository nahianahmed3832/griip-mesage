package com.meldiq.android.data.repo

import com.meldiq.android.data.api.ApiException
import com.meldiq.android.data.api.CallDto
import com.meldiq.android.data.repo.CallKeyVerifier.Verdict
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** T5.2: only the call's creator, in the call's own conversation, can set its media key. */
class CallKeyVerifierTest {
    private val calls = mapOf(
        "call-1" to CallDto(id = "call-1", conversationId = "c1", createdBy = "alice", video = true, state = "RINGING", createdAt = 1),
    )
    private val verifier = CallKeyVerifier { calls[it] }

    @Test
    fun theCreatorsKeyIsAccepted() = runTest {
        assertEquals(Verdict.ACCEPT, verifier.check("call-1", "c1", "alice"))
    }

    @Test
    fun anotherMembersKeyIsRefused() = runTest {
        assertEquals(Verdict.NOT_CREATOR, verifier.check("call-1", "c1", "mallory"))
    }

    @Test
    fun aKeySentFromAnotherConversationIsRefused() = runTest {
        // alice is the creator, but this key arrived in a different conversation from the call's
        assertEquals(Verdict.WRONG_CONVERSATION, verifier.check("call-1", "c2", "alice"))
    }

    @Test
    fun aKeyForAnUnknownCallIsRefused() = runTest {
        assertEquals(Verdict.UNKNOWN_CALL, verifier.check("call-404", "c1", "alice"))
    }

    @Test
    fun aServerFailureIsNotAVerdict() = runTest {
        // The repository maps 404 to null; any other failure must propagate so the item is redelivered.
        val down = CallKeyVerifier { throw ApiException(503, "unavailable", "down") }
        assertThrows(ApiException::class.java) { kotlinx.coroutines.runBlocking { down.check("call-1", "c1", "alice") } }
    }
}
