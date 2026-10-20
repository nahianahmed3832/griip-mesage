package com.meldiq.android.service

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.meldiq.android.GriipApp
import com.meldiq.android.data.repo.FcmTokenSource
import com.meldiq.android.data.repo.SessionState
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Firebase Cloud Messaging events (docs/PUSH_FCM.md). Like UnifiedPush ([MeldiqPushService]), a message only means
 * "fetch your queue": its payload is never read (Meldiq's server sends none), and the fetch runs over the app's own
 * authenticated connection ([WakeWorker]).
 */
class MeldiqFcmService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val c = (application as GriipApp).container ?: return
        c.scope.launch { c.fcm.onNewToken(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val c = (application as GriipApp).container ?: return
        if (c.session.state.value is SessionState.SignedIn) WakeWorker.wake(this, c)
    }
}

/** Firebase on this phone. Absent from builds without google-services.json, which then never register. */
class FirebaseTokenSource(private val context: Context) : FcmTokenSource {
    override fun available(): Boolean = runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)

    override suspend fun token(): String? = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) android.util.Log.w("MeldiqFcm", "no Firebase token: ${task.exception?.message ?: "unknown"}")
            if (cont.isActive) cont.resume(if (task.isSuccessful) task.result else null)
        }
    }
}
