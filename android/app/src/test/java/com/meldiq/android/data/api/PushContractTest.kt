package com.meldiq.android.data.api

import com.meldiq.android.data.repo.ApiPushServer
import com.meldiq.android.data.repo.PushDistributor
import com.meldiq.android.data.repo.PushRecord
import com.meldiq.android.data.repo.PushRegistrar
import com.meldiq.android.data.repo.PushState
import com.meldiq.android.data.repo.PushStore
import com.meldiq.android.data.repo.ServerFeatures
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Push wake-up registration against the server's real response shapes (server/src/routes/push.ts, server-info), through
 * the real ApiClient.
 */
class PushContractTest {
    private val server = MockWebServer().apply { start() }
    private val api = ApiClient({ server.url("/").toString() }, { "token-for-this-device" }, { false })
    private val features = ServerFeatures(api)
    private val push = ApiPushServer(api, features)

    @After fun stop() = server.shutdown()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("content-type", "application/json").setBody(body)

    private val memory = object : PushStore {
        val records = HashMap<String, PushRecord>()
        override fun read(instance: String) = records[instance] ?: PushRecord()
        override fun write(instance: String, record: PushRecord) {
            records[instance] = record
        }
        override fun remove(instance: String) {
            records.remove(instance)
        }
    }
    /** A registrar that has asked the distributor (start() does that before an endpoint can arrive). */
    private fun registering() = PushRegistrar("p1", memory.also { it.write("p1", PushRecord(PushState.Registering)) }, push, noDistributor)

    private val noDistributor = object : PushDistributor {
        override fun available() = true
        override fun register(instance: String) = Unit
        override fun unregister(instance: String) = Unit
    }

    @Test
    fun theEndpointGoesToThisDevicesPushRouteWithTheSession() = runBlocking {
        server.enqueue(json(200, """{"push":{"kind":"unifiedpush","host":"ntfy.example.net","updatedAt":1760700000000}}"""))
        push.register("https://ntfy.example.net/upAbCdEfGhIjKl?up=1")
        val sent = server.takeRequest()
        assertEquals("PUT", sent.method)
        assertEquals("/v1/devices/current/push", sent.path)
        assertEquals("Bearer token-for-this-device", sent.getHeader("Authorization"))
        assertEquals("""{"kind":"unifiedpush","endpoint":"https://ntfy.example.net/upAbCdEfGhIjKl?up=1"}""", sent.body.readUtf8())
    }

    @Test
    fun removingItDeletesTheSameRoute() = runBlocking {
        server.enqueue(json(200, """{"ok":true,"removed":true}"""))
        push.unregister()
        val sent = server.takeRequest()
        assertEquals("DELETE", sent.method)
        assertEquals("/v1/devices/current/push", sent.path)
    }

    @Test
    fun theServersRefusalReachesTheSettingsWithItsPushServer() = runBlocking {
        server.enqueue(
            json(
                400,
                """{"error":{"code":"push_host_not_allowed","message":"This server only sends wake-ups to its own push server","details":{"allowedHosts":["ntfy.griipbuddy.cloud"]}}}""",
            ),
        )
        val r = registering()
        r.onNewEndpoint("https://ntfy.sh/upAbCdEfGhIjKl?up=1")
        assertEquals(PushState.ServerRefused("ntfy.sh", listOf("ntfy.griipbuddy.cloud")), r.state.value)
    }

    @Test
    fun aServerWithPushOffOrTooOldForItCountsAsNotOffering() = runBlocking {
        server.enqueue(json(404, """{"error":{"code":"push_unavailable","message":"This server doesn't send push wake-ups"}}"""))
        val r = registering()
        r.onNewEndpoint("https://ntfy.example.net/upAbCdEfGhIjKl?up=1")
        assertEquals(PushState.ServerUnsupported, r.state.value)

        server.enqueue(json(404, """{"message":"Route PUT:/v1/devices/current/push not found","error":"Not Found","statusCode":404}"""))
        r.onNewEndpoint("https://ntfy.example.net/upAbCdEfGhIjKl?up=1")
        assertEquals(PushState.ServerUnsupported, r.state.value)
    }

    @Test
    fun serverInfoSaysWhetherWakeupsAreOfferedAndThroughWhichHost() = runBlocking {
        server.enqueue(
            json(
                200,
                """{"name":"Griip Message relay","version":"0.3.0","calling":true,"queueTtlSeconds":604800,"attachmentMaxBytes":1,"attachmentTtlSeconds":1,
                   "encryption":"griip-envelope-v1","features":["profiles","push-unifiedpush"],"push":{"unifiedPushHosts":["ntfy.griipbuddy.cloud"]}}""",
            ),
        )
        assertEquals(true, push.supported())
        assertEquals(listOf("ntfy.griipbuddy.cloud"), features.pushHosts())
        assertEquals("server-info is asked once and cached", 1, server.requestCount)

        // A server from before wake-ups: no features list and no push field.
        server.enqueue(json(200, """{"name":"x","version":"0.2.0","calling":true,"queueTtlSeconds":1,"attachmentMaxBytes":1,"attachmentTtlSeconds":1,"encryption":"e"}"""))
        val old = ServerFeatures(api)
        assertEquals(false, old.has(ServerFeatures.PUSH_UNIFIEDPUSH))
        assertEquals(emptyList<String>(), old.pushHosts())
    }
}
