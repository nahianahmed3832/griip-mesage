package com.griipbuddy.orbit.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.griipbuddy.orbit.GriipApp
import com.griipbuddy.orbit.data.repo.SessionState

/**
 * How the app keeps receiving messages and calls while it isn't on screen (T2.5). Today there's one
 * mechanism: a persistent relay connection in a foreground service. A wake-up push (self-hosted
 * UnifiedPush, or FCM) would be another implementation (docs/BACKGROUND_DELIVERY.md). Whatever the
 * mechanism, no push may ever carry message content, private keys or call keys.
 */
interface BackgroundDelivery {
    fun start(context: Context)
    fun stop(context: Context)

    companion object {
        /** Runs only for a signed-in user who left "Stay connected" on. */
        fun shouldRun(signedIn: Boolean, enabled: Boolean) = signedIn && enabled

        /** Applies the current session and setting: starts or stops [mechanism]. */
        fun sync(context: Context, mechanism: BackgroundDelivery = PersistentConnection) {
            val c = (context.applicationContext as GriipApp).container ?: return
            if (shouldRun(c.session.state.value is SessionState.SignedIn, c.settings.current.backgroundConnection)) mechanism.start(context)
            else mechanism.stop(context)
        }
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
 * Both broadcasts allow starting a foreground service from the background.
 */
class BackgroundDeliveryRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            BackgroundDelivery.sync(context)
        }
    }
}
