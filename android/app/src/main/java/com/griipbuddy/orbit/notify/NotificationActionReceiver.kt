package com.griipbuddy.orbit.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.griipbuddy.orbit.GriipApp

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val calls = (context.applicationContext as GriipApp).container?.calls ?: return
        when (intent.action) {
            ACTION_DECLINE_CALL -> calls.decline()
            // Picture-in-picture controls (MainActivity).
            ACTION_HANG_UP -> calls.hangup()
            ACTION_TOGGLE_MIC -> calls.toggleMic()
            // The ongoing-call and screen-sharing notifications (UI-003): stops sharing only, the call goes on.
            ACTION_STOP_SHARE -> calls.stopScreenShare()
        }
    }

    companion object {
        const val ACTION_DECLINE_CALL = "com.griipbuddy.orbit.DECLINE_CALL"
        const val ACTION_HANG_UP = "com.griipbuddy.orbit.HANG_UP"
        const val ACTION_TOGGLE_MIC = "com.griipbuddy.orbit.TOGGLE_MIC"
        const val ACTION_STOP_SHARE = "com.griipbuddy.orbit.STOP_SHARE"
    }
}
