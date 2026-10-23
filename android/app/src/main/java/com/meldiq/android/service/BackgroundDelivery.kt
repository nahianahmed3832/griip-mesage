package com.meldiq.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meldiq.android.GriipApp
import com.meldiq.android.data.repo.FcmState
import com.meldiq.android.data.repo.SessionState

/**
 * How the app keeps receiving messages and calls while it isn't on screen (T2.5). Two mechanisms, each with its own
 * setting, which may run side by side (docs/BACKGROUND_DELIVERY.md):
 * - [PersistentConnection], "Stay connected" (on by default): the relay connection kept open in a foreground service,
 *   only while Firebase wake-ups don't work on this phone (they reach messages and calls without its notification);
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
            apply(context, PersistentConnection, PersistentConnection.needed(signedIn, s.backgroundConnection, c.fcm.state.value == FcmState.Active))
            apply(context, UnifiedPushDelivery, shouldRun(signedIn, s.pushWakeups))
        }

        private fun apply(context: Context, mechanism: BackgroundDelivery, run: Boolean) =
            if (run) mechanism.start(context) else mechanism.stop(context)
    }
}

/** The relay WebSocket kept open by [RelayConnectionService] (foreground service type remoteMessaging). */
object PersistentConnection : BackgroundDelivery {
    /**
     * Whether the connection must be kept open: with Firebase wake-ups working, messages and calls wake the phone
     * without it, and without its notification or battery cost (the owner, 2026-10-23: the always-on "Meldiq is
     * connected" notification). Without them (phones with no Google services) it stays, as the only way in.
     */
    fun needed(signedIn: Boolean, enabled: Boolean, fcmActive: Boolean) = BackgroundDelivery.shouldRun(signedIn, enabled) && !fcmActive

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
