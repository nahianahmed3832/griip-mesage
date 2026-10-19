package com.meldiq.android.service

import com.meldiq.android.GriipApp
import com.meldiq.android.data.repo.SessionState
import com.meldiq.android.di.AppContainer
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * UnifiedPush events from the distributor app (docs/BACKGROUND_DELIVERY.md). The instance is the server profile's id.
 * A push message only ever means "fetch your queue": its content is never read (Meldiq's server sends none), and the
 * fetch runs over the app's own authenticated connection ([WakeWorker]). Called on the main thread or the connector's
 * own thread, so nothing here blocks.
 */
class MeldiqPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val c = handled(instance) ?: return
        c.scope.launch { c.push.onNewEndpoint(endpoint.url) }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val c = handled(instance) ?: return
        WakeWorker.wake(this, c)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        handled(instance)?.push?.onRegistrationFailed(reason.name)
    }

    override fun onUnregistered(instance: String) {
        val c = handled(instance) ?: return
        c.scope.launch { c.push.onUnregistered() }
    }

    /** The active, signed-in profile's container for this event; a registration of another profile is ended. */
    private fun handled(instance: String): AppContainer? {
        val c = (application as GriipApp).container
        return when (PushEvents.route(instance, c?.profile?.id, c?.session?.state?.value is SessionState.SignedIn)) {
            PushEvents.Route.Handle -> c
            PushEvents.Route.EndStale -> {
                UnifiedPushDelivery.endLocally(this, instance)
                null
            }
            PushEvents.Route.Ignore -> null
        }
    }
}
