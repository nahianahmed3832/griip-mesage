package com.griipbuddy.orbit.data.repo

import com.griipbuddy.orbit.data.api.ApiClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The feature cache follows the server address (2026-10-19, found on the phone): a new account's profile starts on the
 * default server and gets its own address during sign-up, so what the default server supports must never be reused.
 */
class ServerFeaturesTest {
    private val old = MockWebServer().apply { start() }
    private val new = MockWebServer().apply { start() }

    @After
    fun tearDown() {
        old.shutdown()
        new.shutdown()
    }

    private fun info(vararg features: String) =
        MockResponse().setBody(
            """{"name":"x","version":"0.5.0","calling":true,"queueTtlSeconds":604800,"attachmentMaxBytes":1,"attachmentTtlSeconds":1,""" +
                """"encryption":"griip-envelope-v1","features":[${features.joinToString(",") { "\"$it\"" }}]}""",
        ).setHeader("content-type", "application/json")

    @Test
    fun `a changed server address is asked again instead of using the old answer`() = runBlocking {
        var url = old.url("/").toString()
        val features = ServerFeatures(ApiClient({ url }, { "t" }, { false }))
        old.enqueue(info("profiles"))
        new.enqueue(info("profiles", ServerFeatures.NAV_PREFERENCES))
        assertEquals(false, features.has(ServerFeatures.NAV_PREFERENCES))
        url = new.url("/").toString()
        assertEquals(true, features.has(ServerFeatures.NAV_PREFERENCES))
        assertEquals(1, old.requestCount)
        assertEquals(1, new.requestCount)
        // Same address again: the cached answer is used.
        assertTrue(features.has(ServerFeatures.PROFILES) == true)
        assertEquals(1, new.requestCount)
    }
}
