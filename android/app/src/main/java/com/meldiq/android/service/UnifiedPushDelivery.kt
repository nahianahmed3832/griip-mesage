package com.meldiq.android.service

import android.app.Activity
import android.content.Context
import com.meldiq.android.GriipApp
import com.meldiq.android.data.repo.PrefsPushStore
import com.meldiq.android.data.repo.PushDistributor
import com.meldiq.android.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Wake-ups through a UnifiedPush distributor (docs/BACKGROUND_DELIVERY.md, option B), the second [BackgroundDelivery]
 * mechanism beside the persistent connection. The distributor app (for example ntfy, set to the push server that
 * Meldiq's server allows) keeps the phone's one push connection. When something waits for this device, Meldiq's server
 * sends this profile's endpoint an empty request; the app then fetches over its own connection ([WakeWorker]).
 * Registration logic lives in [com.meldiq.android.data.repo.PushRegistrar].
 */
object UnifiedPushDelivery : BackgroundDelivery {
    private const val RELEASE_TIMEOUT_MS = 10_000L

    override fun start(context: Context) {
        val c = container(context) ?: return
        c.scope.launch { c.push.start() }
    }

    override fun stop(context: Context) {
        val c = container(context) ?: return
        c.scope.launch { c.push.stop() }
    }

    /**
     * Settings turned wake-ups on: choose the distributor if none is chosen yet (Android shows its chooser when there are
     * several and none is the default), then register. Without one the state says so.
     */
    fun enable(activity: Activity) {
        val c = container(activity) ?: return
        synchronized(connector) {
            UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { c.scope.launch { c.push.start(force = true) } }
        }
    }

    /**
     * The person switches away from [c]'s profile: end its registration, since only the active profile is woken. Runs on
     * [appScope] because the container closes right after; telling the server is best effort (it also drops endpoints
     * that stop working).
     */
    fun release(c: AppContainer, appScope: CoroutineScope) {
        appScope.launch { withTimeoutOrNull(RELEASE_TIMEOUT_MS) { c.push.stop() } }
    }

    /**
     * Ends [instance]'s registration on this phone only: with the distributor and in local state; the server isn't told.
     * For events of a profile that isn't the active one, and for profiles being erased or removed.
     */
    fun endLocally(context: Context, instance: String) {
        unregister(context, instance)
        PrefsPushStore(context).remove(instance)
    }

    /**
     * Connector calls, one at a time: a profile switch ends the old profile's registration on the app scope while the new
     * profile registers on its own.
     */
    private val connector = Any()

    internal fun distributorChosen(context: Context): Boolean = synchronized(connector) { UnifiedPush.getSavedDistributor(context) != null }

    internal fun register(context: Context, instance: String, label: String) = synchronized(connector) {
        UnifiedPush.register(context, instance, label)
    }

    /**
     * Ends [instance]'s registration. The connector also forgets the chosen distributor with its last registration; the
     * person's choice is kept, for the profile switched to and for turning wake-ups back on.
     */
    internal fun unregister(context: Context, instance: String) = synchronized(connector) {
        val chosen = UnifiedPush.getSavedDistributor(context)
        UnifiedPush.unregister(context, instance)
        if (chosen != null && UnifiedPush.getSavedDistributor(context) == null) UnifiedPush.saveDistributor(context, chosen)
    }

    private fun container(context: Context): AppContainer? = (context.applicationContext as GriipApp).container
}

/** The distributor through the UnifiedPush connector. [label] names the registration in the distributor's own UI. */
class ConnectorDistributor(private val context: Context, private val label: () -> String) : PushDistributor {
    override fun available(): Boolean = UnifiedPushDelivery.distributorChosen(context)

    override fun register(instance: String) = UnifiedPushDelivery.register(context, instance, label())

    override fun unregister(instance: String) = UnifiedPushDelivery.unregister(context, instance)
}

/** Where a distributor event goes (pure; `BackgroundDeliveryTest`). Each server profile is its own registration. */
object PushEvents {
    enum class Route { Handle, EndStale, Ignore }

    fun route(instance: String, activeProfile: String?, signedIn: Boolean): Route = when {
        activeProfile == null -> Route.Ignore
        instance != activeProfile -> Route.EndStale
        !signedIn -> Route.Ignore
        else -> Route.Handle
    }
}
