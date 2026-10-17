package com.griipbuddy.orbit.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.griipbuddy.orbit.GriipApp
import com.griipbuddy.orbit.data.repo.SessionState

/**
 * How the app keeps receiving messages and calls while it isn't on screen (T2.5). Two mechanisms, each with its own
 * setting, which may run side by side (docs/BACKGROUND_DELIVERY.md):
 * - [PersistentConnection], "Stay connected" (on by default): the relay connection kept open in a foreground service;
 * - [UnifiedPushDelivery], wake-ups through a UnifiedPush distributor (off by default).
 * Whatever the mechanism, no push may ever carry message content, sender or conversation ids, private keys or call keys.
 */
interface BackgroundDelivery {
    fun start(context: Context)
    fun stop(context: Context)

    companion object {
        /** A mechanism runs only for a signed-in user who left its setting on. */
        fun shouldRun(signedIn: Boolean, enabled: Boolean) = signedIn && enabled

        /** Applies the current session and settings: starts or stops each mechanism. */
        fun sync(context: Context) {
            val c = (context.applicationContext as GriipApp).container ?: return
            val signedIn = c.session.state.value is SessionState.SignedIn
            val s = c.settings.current
            apply(context, PersistentConnection, shouldRun(signedIn, s.backgroundConnection))
            apply(context, UnifiedPushDelivery, shouldRun(signedIn, s.pushWakeups))
        }

        private fun apply(context: Context, mechanism: BackgroundDelivery, run: Boolean) =
            if (run) mechanism.start(context) else mechanism.stop(context)
    }
}

/** The relay WebSocket kept open by [RelayConnectionService] (foreground service type remoteMessaging). */
object PersistentConnection : BackgroundDelivery {
    override fun start(context: Context) {
        RelayConnectionService.start(context)
    }

    override fun stop(context: Context) {
        RelayConnectionService.stop(context)
    }
}

/**
 * The connection service used to stay off after a reboot or an app update until the app was opened.
 * Both broadcasts allow starting a foreground service from the background. With wake-ups on, the app also fetches
 * once: wake-ups sent while the phone was off may have expired at the push server.
 */
class BackgroundDeliveryRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            BackgroundDelivery.sync(context)
            val c = (context.applicationContext as GriipApp).container ?: return
            if (BackgroundDelivery.shouldRun(c.session.state.value is SessionState.SignedIn, c.settings.current.pushWakeups)) WakeWorker.wake(context, c)
        }
    }
}
