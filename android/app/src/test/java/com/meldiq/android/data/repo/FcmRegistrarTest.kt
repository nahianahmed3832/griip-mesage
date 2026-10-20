package com.meldiq.android.data.repo

import com.meldiq.android.data.api.ApiException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FcmRegistrarTest {
    private class FakeTokens(var available: Boolean = true, var token: String? = "token-1-aaaaaaaaaaaaaaaaaaaa") : FcmTokenSource {
        override fun available() = available
        override suspend fun token() = token
    }

    private class FakeServer(var supported: Boolean? = true) : FcmServer {
        val sent = mutableListOf<String>()
        var failWith: Exception? = null
        override suspend fun supported() = supported
        override suspend fun register(token: String) {
            failWith?.let { throw it }
            sent += token
        }
    }

    @Test
    fun `start sends the token and becomes active`() = runTest {
        val server = FakeServer()
        val r = FcmRegistrar(FakeTokens(), server)
        r.start()
        assertEquals(FcmState.Active, r.state.value)
        assertEquals(listOf("token-1-aaaaaaaaaaaaaaaaaaaa"), server.sent)
    }

    @Test
    fun `a server without push-fcm or a build without Firebase never registers`() = runTest {
        val noFcm = FakeServer(supported = false)
        FcmRegistrar(FakeTokens(), noFcm).apply { start(); assertEquals(FcmState.ServerUnsupported, state.value) }
        val server = FakeServer()
        FcmRegistrar(FakeTokens(available = false), server).apply { start(); assertEquals(FcmState.Unavailable, state.value) }
        assertEquals(emptyList<String>(), noFcm.sent + server.sent)
    }

    @Test
    fun `a network failure is retried on reconnect, a refusal is not`() = runTest {
        val server = FakeServer().apply { failWith = ApiException(0, "network", "offline") }
        val r = FcmRegistrar(FakeTokens(), server)
        r.start()
        assertEquals(FcmState.Pending, r.state.value)
        server.failWith = null
        r.retryPending()
        assertEquals(FcmState.Active, r.state.value)

        val refusing = FakeServer().apply { failWith = ApiException(400, "invalid", "bad token") }
        val r2 = FcmRegistrar(FakeTokens(), refusing)
        r2.start()
        assertEquals(FcmState.Unavailable, r2.state.value)
        refusing.failWith = null
        r2.retryPending()
        assertEquals(emptyList<String>(), refusing.sent)
    }

    @Test
    fun `a rotated token is sent only while wake-ups apply, and sign-out forgets`() = runTest {
        val server = FakeServer()
        val r = FcmRegistrar(FakeTokens(), server)
        r.onNewToken("ignored-before-start-aaaaaaa")
        assertEquals(emptyList<String>(), server.sent)
        r.start()
        r.onNewToken("token-2-bbbbbbbbbbbbbbbbbbbb")
        assertEquals(listOf("token-1-aaaaaaaaaaaaaaaaaaaa", "token-2-bbbbbbbbbbbbbbbbbbbb"), server.sent)
        r.reset()
        r.onNewToken("token-3-cccccccccccccccccccc")
        assertEquals(FcmState.Off, r.state.value)
        assertEquals(2, server.sent.size)
    }
}
