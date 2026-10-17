package com.griipbuddy.orbit.data.repo

import android.content.Context
import androidx.core.content.edit
import com.griipbuddy.orbit.data.api.ApiClient
import com.griipbuddy.orbit.data.api.ApiErrors
import com.griipbuddy.orbit.data.api.ApiException
import com.griipbuddy.orbit.data.api.PushEndpointReq
import com.griipbuddy.orbit.data.api.PushEndpointRes
import com.griipbuddy.orbit.data.api.PushRemoveRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException

/** Where push wake-ups stand for one server profile (docs/BACKGROUND_DELIVERY.md); Settings shows it. */
sealed interface PushState {
    /** Turned off, or never used. */
    data object Off : PushState

    /** The server doesn't offer wake-ups ("push-unifiedpush" isn't in its server-info). */
    data object ServerUnsupported : PushState

    /** No UnifiedPush distributor app (such as ntfy) is installed or chosen. */
    data object NoDistributor : PushState

    /** Asked the distributor for an endpoint; its answer comes later. */
    data object Registering : PushState

    /** The distributor couldn't register: the connector's reason (NETWORK, ACTION_REQUIRED, VAPID_REQUIRED, …). */
    data class DistributorFailed(val reason: String) : PushState

    /** The server refused the endpoint. Usually the distributor uses another push server: [allowedHosts] are the server's. */
    data class ServerRefused(val host: String, val allowedHosts: List<String>) : PushState

    /** The server couldn't be told yet (unreachable): sent again when the app next connects. */
    data class Pending(val host: String) : PushState

    /** Wake-ups work: the server has this phone's endpoint on [host]. */
    data class Active(val host: String) : PushState

    companion object {
        private const val SEP = "|"

        fun encode(s: PushState): String = when (s) {
            Off -> "off"
            ServerUnsupported -> "unsupported"
            NoDistributor -> "no_distributor"
            Registering -> "registering"
            is DistributorFailed -> "failed$SEP${s.reason}"
            is ServerRefused -> "refused$SEP${s.host}$SEP${s.allowedHosts.joinToString(",")}"
            is Pending -> "pending$SEP${s.host}"
            is Active -> "active$SEP${s.host}"
        }

        /** Anything unreadable counts as [Off]: the next start works it out again. */
        fun decode(v: String?): PushState {
            val p = v?.split(SEP).orEmpty()
            return when (p.firstOrNull()) {
                "unsupported" -> ServerUnsupported
                "no_distributor" -> NoDistributor
                "registering" -> Registering
                "failed" -> DistributorFailed(p.getOrElse(1) { "" })
                "refused" -> ServerRefused(p.getOrElse(1) { "" }, p.getOrElse(2) { "" }.split(",").filter { it.isNotEmpty() })
                "pending" -> Pending(p.getOrElse(1) { "" })
                "active" -> Active(p.getOrElse(1) { "" })
                else -> Off
            }
        }
    }
}

/** What is kept per profile between starts: the state, and the endpoint the distributor gave. */
data class PushRecord(val state: PushState = PushState.Off, val endpoint: String? = null)

interface PushStore {
    fun read(instance: String): PushRecord
    fun write(instance: String, record: PushRecord)
    fun remove(instance: String)
}

/** The app's server: whether it offers wake-ups, and this device's endpoint there. */
interface PushServer {
    /** true / false once known; null while the server can't be asked. */
    suspend fun supported(): Boolean?

    /** Stores [endpoint] as this device's only wake-up address. Throws [ApiException]. */
    suspend fun register(endpoint: String)

    /** Removes this device's wake-up address. Throws [ApiException]. */
    suspend fun unregister()
}

/** The UnifiedPush distributor app, through the connector library. */
interface PushDistributor {
    /** A distributor app is chosen and still installed. */
    fun available(): Boolean

    /** Asks for [instance]'s endpoint; the answer comes later, through [PushRegistrar.onNewEndpoint]. */
    fun register(instance: String)

    /** Ends [instance]'s registration; the distributor stops delivering to it. */
    fun unregister(instance: String)
}

/**
 * Push wake-up registration for one server profile ([instance] is the profile id, which is also the UnifiedPush
 * instance). Pure logic: the distributor, the server and storage are interfaces (`PushRegistrarTest`).
 *
 * The endpoint goes to the server every time the distributor hands it over, even unchanged: re-registering at each
 * start is what the connector recommends, and the server may have dropped an endpoint that stopped working.
 */
class PushRegistrar(
    private val instance: String,
    private val store: PushStore,
    private val server: PushServer,
    private val distributor: PushDistributor,
) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(store.read(instance).state)
    val state: StateFlow<PushState> = _state.asStateFlow()
    /** Registered with the distributor since this process started (the app comes to the foreground often). */
    private var registeredThisRun = false

    /**
     * Wake-ups are on for this profile (app start, boot, Settings): register with the distributor, or record why not.
     * Once active, later calls in the same process do nothing unless [force]d (Settings).
     */
    suspend fun start(force: Boolean = false) = lock.withLock {
        if (!force && registeredThisRun && _state.value is PushState.Active) return@withLock
        when {
            server.supported() == false -> {
                release()
                set(PushState.ServerUnsupported)
            }
            !distributor.available() -> {
                forget()
                set(PushState.NoDistributor)
            }
            else -> {
                if (_state.value !is PushState.Active) set(PushState.Registering)
                distributor.register(instance)
                registeredThisRun = true
            }
        }
    }

    /** Wake-ups are off, or this profile is closing: end the registration and tell the server. Nothing to do if unused. */
    suspend fun stop() = lock.withLock {
        registeredThisRun = false
        if (_state.value == PushState.Off && store.read(instance).endpoint == null) return@withLock
        release()
        set(PushState.Off)
    }

    /**
     * The distributor's endpoint for this profile: new, changed, or the same one again after re-registering. Ignored once
     * wake-ups are off: one that arrives late must not bring them back.
     */
    suspend fun onNewEndpoint(endpoint: String) = lock.withLock {
        if (_state.value == PushState.Off) return@withLock
        store.write(instance, store.read(instance).copy(endpoint = endpoint))
        set(send(endpoint))
    }

    /** Sends an endpoint the server couldn't be told about before. Called whenever the app (re)connects. */
    suspend fun retryPending() = lock.withLock {
        if (_state.value !is PushState.Pending) return@withLock
        val endpoint = store.read(instance).endpoint ?: return@withLock
        set(send(endpoint))
    }

    fun onRegistrationFailed(reason: String) = set(PushState.DistributorFailed(reason))

    /** The distributor ended the registration (uninstalled, or Orbit removed there): the server stops waking us. */
    suspend fun onUnregistered() = lock.withLock {
        forget()
        set(PushState.NoDistributor)
    }

    private suspend fun send(endpoint: String): PushState {
        val host = hostOf(endpoint)
        return try {
            server.register(endpoint)
            PushState.Active(host)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            refusal(e, host)
        }
    }

    /** Distributor, server and local endpoint all let go. */
    private suspend fun release() {
        distributor.unregister(instance)
        forget()
    }

    /** The server and the local endpoint let go (the server also drops endpoints that stop working; best effort). */
    private suspend fun forget() {
        store.write(instance, store.read(instance).copy(endpoint = null))
        try {
            server.unregister()
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiException) {
            // unreachable or an older server: it drops the endpoint itself once the push server reports it gone
        }
    }

    private fun set(s: PushState) {
        _state.value = s
        store.write(instance, store.read(instance).copy(state = s))
    }

    companion object {
        /** What a failed registration means for the person. Unit-tested. */
        fun refusal(e: ApiException, host: String): PushState = when {
            e.isNetwork || e.status == 401 || e.status == 429 || e.status >= 500 -> PushState.Pending(host)
            e.code == "push_unavailable" || e.code == ApiErrors.UNSUPPORTED -> PushState.ServerUnsupported
            else -> PushState.ServerRefused(host, allowedHosts(e.details))
        }

        fun hostOf(endpoint: String): String = runCatching { URI(endpoint).authority }.getOrNull().orEmpty()

        private fun allowedHosts(details: JsonElement?): List<String> =
            ((details as? JsonObject)?.get("allowedHosts") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
    }
}

/** The app's server over the existing REST client. */
class ApiPushServer(private val api: ApiClient, private val features: ServerFeatures) : PushServer {
    override suspend fun supported(): Boolean? = features.has(ServerFeatures.PUSH_UNIFIEDPUSH)

    override suspend fun register(endpoint: String) {
        api.put<PushEndpointReq, PushEndpointRes>(PATH, PushEndpointReq(KIND, endpoint))
    }

    override suspend fun unregister() {
        api.delete<PushRemoveRes>(PATH)
    }

    companion object {
        const val PATH = "/v1/devices/current/push"
        const val KIND = "unifiedpush"
    }
}

/** Per-profile push state in the app's private preferences (not secret: the endpoint only wakes this phone). */
class PrefsPushStore(context: Context) : PushStore {
    private val prefs = context.getSharedPreferences("orbit_push", Context.MODE_PRIVATE)

    override fun read(instance: String) = PushRecord(PushState.decode(prefs.getString("$instance.state", null)), prefs.getString("$instance.endpoint", null))

    override fun write(instance: String, record: PushRecord) = prefs.edit {
        putString("$instance.state", PushState.encode(record.state))
        putString("$instance.endpoint", record.endpoint)
    }

    override fun remove(instance: String) = prefs.edit {
        remove("$instance.state")
        remove("$instance.endpoint")
    }
}
