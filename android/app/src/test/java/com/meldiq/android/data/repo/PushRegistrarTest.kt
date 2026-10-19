package com.meldiq.android.data.repo

import com.meldiq.android.data.api.ApiErrors
import com.meldiq.android.data.api.ApiException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Push wake-up registration for one server profile (docs/BACKGROUND_DELIVERY.md), with fake distributor and server. */
class PushRegistrarTest {
    private class FakeStore : PushStore {
        val records = HashMap<String, PushRecord>()
        override fun read(instance: String) = records[instance] ?: PushRecord()
        override fun write(instance: String, record: PushRecord) {
            records[instance] = record
        }
        override fun remove(instance: String) {
            records.remove(instance)
        }
    }

    private class FakeServer : PushServer {
        var offers: Boolean? = true
        var failWith: ApiException? = null
        val registered = ArrayList<String>()
        var unregistered = 0
        override suspend fun supported() = offers
        override suspend fun register(endpoint: String) {
            failWith?.let { throw it }
            registered += endpoint
        }
        override suspend fun unregister() {
            unregistered++
        }
    }

    private class FakeDistributor : PushDistributor {
        var installed = true
        val registers = ArrayList<String>()
        val unregisters = ArrayList<String>()
        override fun available() = installed
        override fun register(instance: String) {
            registers += instance
        }
        override fun unregister(instance: String) {
            unregisters += instance
        }
    }

    private val store = FakeStore()
    private val server = FakeServer()
    private val distributor = FakeDistributor()
    private fun registrar() = PushRegistrar("p1", store, server, distributor)

    /** A registrar that has asked the distributor, as [PushRegistrar.start] does before any endpoint can arrive. */
    private suspend fun started() = registrar().also { it.start() }

    private val ntfy = "https://ntfy.example.net/upAbCdEfGhIjKl?up=1"
    private val ntfy2 = "https://push.example.org:8443/upZyXwVuTsRqPo?up=1"

    @Test
    fun startingRegistersWithTheDistributorAndTheEndpointMakesItActive() = runTest {
        val r = registrar()
        r.start()
        assertEquals(PushState.Registering, r.state.value)
        assertEquals(listOf("p1"), distributor.registers)

        r.onNewEndpoint(ntfy)
        assertEquals(listOf(ntfy), server.registered)
        assertEquals(PushState.Active("ntfy.example.net"), r.state.value)
        assertEquals(ntfy, store.read("p1").endpoint)
    }

    @Test
    fun aChangedEndpointReplacesTheOldOneOnTheServer() = runTest {
        val r = started()
        r.onNewEndpoint(ntfy)
        r.onNewEndpoint(ntfy2)
        assertEquals(listOf(ntfy, ntfy2), server.registered)
        assertEquals(PushState.Active("push.example.org:8443"), r.state.value)
        assertEquals(ntfy2, store.read("p1").endpoint)
    }

    @Test
    fun theSameEndpointIsSentAgainBecauseTheServerMayHaveDroppedIt() = runTest {
        val r = started()
        r.onNewEndpoint(ntfy)
        r.onNewEndpoint(ntfy)
        assertEquals(listOf(ntfy, ntfy), server.registered)
    }

    @Test
    fun aServerThatOnlyAcceptsItsOwnPushServerSaysWhichOne() = runTest {
        server.failWith = ApiException(
            400, "push_host_not_allowed", "Use your server's push server",
            JsonObject(mapOf("allowedHosts" to JsonArray(listOf(JsonPrimitive("ntfy.griipbuddy.cloud"))))),
        )
        val r = started()
        r.onNewEndpoint("https://ntfy.sh/upAbCdEfGhIjKl?up=1")
        assertEquals(PushState.ServerRefused("ntfy.sh", listOf("ntfy.griipbuddy.cloud")), r.state.value)
    }

    @Test
    fun anUnreachableServerKeepsTheEndpointPendingUntilTheAppReconnects() = runTest {
        server.failWith = ApiException(0, "network", "Can't reach the server.")
        val r = started()
        r.onNewEndpoint(ntfy)
        assertEquals(PushState.Pending("ntfy.example.net"), r.state.value)

        r.retryPending() // still unreachable
        assertEquals(PushState.Pending("ntfy.example.net"), r.state.value)
        server.failWith = null
        r.retryPending()
        assertEquals(PushState.Active("ntfy.example.net"), r.state.value)
        assertEquals(listOf(ntfy), server.registered)
        r.retryPending() // active: nothing to send
        assertEquals(1, server.registered.size)
    }

    @Test
    fun aServerWithoutWakeupsGetsNoRegistrationAndAnEarlierOneIsReleased() = runTest {
        store.write("p1", PushRecord(PushState.Active("ntfy.example.net"), ntfy))
        server.offers = false
        val r = registrar()
        r.start()
        assertEquals(PushState.ServerUnsupported, r.state.value)
        assertTrue(distributor.registers.isEmpty())
        assertEquals(listOf("p1"), distributor.unregisters)
        assertEquals(1, server.unregistered)
        assertNull(store.read("p1").endpoint)
    }

    @Test
    fun anUnknownServerAnswerStillRegisters() = runTest {
        server.offers = null // server-info couldn't be fetched: the endpoint upload decides
        val r = registrar()
        r.start()
        assertEquals(listOf("p1"), distributor.registers)
    }

    @Test
    fun withoutADistributorAppNothingIsRegistered() = runTest {
        distributor.installed = false
        val r = registrar()
        r.start()
        assertEquals(PushState.NoDistributor, r.state.value)
        assertTrue(distributor.registers.isEmpty())
    }

    @Test
    fun turningWakeupsOffEndsTheRegistrationEverywhere() = runTest {
        val r = registrar()
        r.start()
        r.onNewEndpoint(ntfy)
        r.stop()
        assertEquals(PushState.Off, r.state.value)
        assertEquals(listOf("p1"), distributor.unregisters)
        assertEquals(1, server.unregistered)
        assertNull(store.read("p1").endpoint)
    }

    @Test
    fun stoppingWhenWakeupsWereNeverUsedTouchesNothing() = runTest {
        registrar().stop()
        assertTrue(distributor.unregisters.isEmpty())
        assertEquals(0, server.unregistered)
    }

    @Test
    fun theDistributorEndingTheRegistrationTellsTheServer() = runTest {
        val r = started()
        r.onNewEndpoint(ntfy)
        r.onUnregistered()
        assertEquals(PushState.NoDistributor, r.state.value)
        assertEquals(1, server.unregistered)
        assertNull(store.read("p1").endpoint)
    }

    @Test
    fun aServerThatCantBeToldToStopStillEndsTheRegistrationHere() = runTest {
        val r = started()
        r.onNewEndpoint(ntfy)
        val failing = object : PushServer by server {
            override suspend fun unregister() = throw ApiException(0, "network", "offline")
        }
        val r2 = PushRegistrar("p1", store, failing, distributor)
        r2.stop()
        assertEquals(PushState.Off, r2.state.value)
        assertNull(store.read("p1").endpoint)
    }

    @Test
    fun aDistributorFailureIsShownWithItsReason() = runTest {
        val r = registrar()
        r.onRegistrationFailed("ACTION_REQUIRED")
        assertEquals(PushState.DistributorFailed("ACTION_REQUIRED"), r.state.value)
    }

    @Test
    fun onceActiveTheAppComingBackDoesntRegisterAgainUnlessForced() = runTest {
        val r = registrar()
        r.start()
        r.onNewEndpoint(ntfy)
        r.start()
        assertEquals(1, distributor.registers.size)
        r.start(force = true)
        assertEquals(2, distributor.registers.size)
    }

    @Test
    fun stateAndEndpointSurviveARestart() = runTest {
        started().onNewEndpoint(ntfy)
        val again = registrar()
        assertEquals(PushState.Active("ntfy.example.net"), again.state.value)
        again.start() // a new process registers again (the connector recommends it at every start)
        assertEquals(2, distributor.registers.size)
    }

    @Test
    fun anEndpointArrivingAfterWakeupsWereTurnedOffIsIgnored() = runTest {
        val r = started()
        r.stop()
        r.onNewEndpoint(ntfy) // answer to the registration that was just ended
        assertTrue(server.registered.isEmpty())
        assertEquals(PushState.Off, r.state.value)
        assertNull(store.read("p1").endpoint)
    }

    @Test
    fun failedUploadsMeanWhatThePersonCanDoAboutThem() {
        val host = "ntfy.example.net"
        assertEquals(PushState.Pending(host), PushRegistrar.refusal(ApiException(0, "network", "offline"), host))
        assertEquals(PushState.Pending(host), PushRegistrar.refusal(ApiException(401, "unauthorized", "expired"), host))
        assertEquals(PushState.Pending(host), PushRegistrar.refusal(ApiException(429, "rate_limited", "slow down"), host))
        assertEquals(PushState.Pending(host), PushRegistrar.refusal(ApiException(503, "http_503", "down"), host))
        assertEquals(PushState.ServerUnsupported, PushRegistrar.refusal(ApiException(404, "push_unavailable", "off"), host))
        assertEquals(PushState.ServerUnsupported, PushRegistrar.refusal(ApiException(404, ApiErrors.UNSUPPORTED, "old server"), host))
        assertEquals(PushState.ServerRefused(host, emptyList()), PushRegistrar.refusal(ApiException(400, "push_endpoint_invalid", "bad"), host))
    }

    @Test
    fun everyStateIsStoredAndReadBack() {
        val all = listOf(
            PushState.Off, PushState.ServerUnsupported, PushState.NoDistributor, PushState.Registering,
            PushState.DistributorFailed("VAPID_REQUIRED"), PushState.ServerRefused("ntfy.sh", listOf("a.example.net", "b.example.net:8443")),
            PushState.ServerRefused("ntfy.sh", emptyList()), PushState.Pending("ntfy.example.net"), PushState.Active("push.example.org:8443"),
        )
        for (s in all) assertEquals(s, PushState.decode(PushState.encode(s)))
        assertEquals(PushState.Off, PushState.decode(null))
        assertEquals(PushState.Off, PushState.decode("something newer"))
    }

    @Test
    fun theHostKeepsItsPort() {
        assertEquals("push.example.org:8443", PushRegistrar.hostOf(ntfy2))
        assertEquals("ntfy.example.net", PushRegistrar.hostOf(ntfy))
        assertEquals("", PushRegistrar.hostOf("not a url"))
    }
}
