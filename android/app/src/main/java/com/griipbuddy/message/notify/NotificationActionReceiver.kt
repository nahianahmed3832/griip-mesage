package com.griipbuddy.message.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.griipbuddy.message.GriipApp

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DECLINE_CALL) {
            (context.applicationContext as GriipApp).container?.calls?.decline()
        }
    }

    companion object {
        const val ACTION_DECLINE_CALL = "com.griipbuddy.message.DECLINE_CALL"
    }
}
