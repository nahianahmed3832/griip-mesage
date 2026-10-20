package com.meldiq.android.data.repo

import com.meldiq.android.data.api.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable data class ExperimentAssignmentDto(val experiment: String, val variant: String, val layer: String = "")
@Serializable data class ExperimentsRes(val mode: String = "off", val analytics: Boolean = false, val assignments: List<ExperimentAssignmentDto> = emptyList())
@Serializable data class AnalyticsEventDto(val name: String, val props: Map<String, String>? = null, val value: Long? = null)
@Serializable data class AnalyticsBatchReq(val platform: String, val events: List<AnalyticsEventDto>)
@Serializable data class AnalyticsBatchRes(val accepted: Int = 0, val enabled: Boolean = false)

/**
 * The analytics buffer (docs/product/EXPERIMENTS.md), pure so it is unit-tested (`ProductSignalsTest`): it holds at
 * most [capacity] events, dropping the oldest, and hands them out in batches the server accepts.
 */
class AnalyticsQueue(private val capacity: Int = 200) {
    private val events = ArrayDeque<AnalyticsEventDto>()

    val size: Int get() = events.size

    fun add(e: AnalyticsEventDto) {
        if (events.size == capacity) events.removeFirst()
        events.addLast(e)
    }

    fun take(max: Int = BATCH): List<AnalyticsEventDto> = List(minOf(max, events.size)) { events.removeFirst() }

    /** Puts a batch that failed to send back in front, within the capacity. */
    fun putBack(batch: List<AnalyticsEventDto>) {
        batch.asReversed().forEach { if (events.size < capacity) events.addFirst(it) }
    }

    fun clear() = events.clear()

    companion object {
        const val BATCH = 50
    }
}

/**
 * Experiments and product analytics for this account (docs/product/EXPERIMENTS.md). Both stay silent unless the server
 * has them and its operator turned them on: no assignment means everyone sees the established experience, and with
 * analytics off nothing is collected or sent. Events carry only the server's closed list of property values; never
 * message text, names or ids of people, channels or workspaces.
 */
class ProductSignals(private val api: ApiClient, private val features: ServerFeatures, private val scope: CoroutineScope) {
    private val _assignments = MutableStateFlow<Map<String, String>>(emptyMap())
    val assignments: StateFlow<Map<String, String>> = _assignments.asStateFlow()
    @Volatile private var analyticsOn = false
    private val queue = AnalyticsQueue()
    private val lock = Mutex()
    private val exposed = mutableSetOf<String>()
    private var flushing = false

    /** Asks the server for this person's assignments and whether analytics is on (on sign-in and app start). */
    suspend fun refresh() {
        val f = runCatching { features.get() }.getOrNull().orEmpty()
        if (ServerFeatures.EXPERIMENTS !in f) {
            _assignments.value = emptyMap()
            analyticsOn = false
            return
        }
        val res = try {
            api.get<ExperimentsRes>("/v1/experiments")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return // Keep what we had; the established experience is the fallback anyway.
        }
        _assignments.value = res.assignments.associate { it.experiment to it.variant }
        analyticsOn = res.analytics && ServerFeatures.ANALYTICS_EVENTS in f
        if (!analyticsOn) lock.withLock { queue.clear() }
    }

    /** The variant of [experiment] this person sees, or [control] when they aren't in it (the default for everyone). */
    fun variant(experiment: String, control: String): String = _assignments.value[experiment] ?: control

    /** Records that the person saw an experiment's variant, once per app start, and only when they are in it. */
    fun exposed(experiment: String) {
        val v = _assignments.value[experiment] ?: return
        if (!exposed.add(experiment)) return
        track("experiment.exposed", mapOf("experiment" to experiment, "variant" to v))
    }

    /** Queues an event when analytics is on; does nothing otherwise. */
    fun track(name: String, props: Map<String, String> = emptyMap(), value: Long? = null) {
        if (!analyticsOn) return
        scope.launch {
            lock.withLock { queue.add(AnalyticsEventDto(name, props.ifEmpty { null }, value)) }
            scheduleFlush()
        }
    }

    private fun scheduleFlush() {
        if (flushing) return
        flushing = true
        scope.launch {
            try {
                delay(FLUSH_DELAY_MS)
                flush()
            } finally {
                flushing = false
            }
        }
    }

    /** Sends what's queued (also when the app goes to the background). A failed batch waits for the next try. */
    suspend fun flush() {
        while (analyticsOn) {
            val batch = lock.withLock { queue.take() }
            if (batch.isEmpty()) return
            try {
                api.post<AnalyticsBatchReq, AnalyticsBatchRes>("/v1/analytics/events", AnalyticsBatchReq(PLATFORM, batch))
            } catch (e: CancellationException) {
                lock.withLock { queue.putBack(batch) }
                throw e
            } catch (_: Exception) {
                lock.withLock { queue.putBack(batch) }
                return
            }
        }
    }

    companion object {
        const val PLATFORM = "android"
        private const val FLUSH_DELAY_MS = 30_000L
    }
}
