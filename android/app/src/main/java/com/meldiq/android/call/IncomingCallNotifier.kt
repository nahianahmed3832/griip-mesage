package com.meldiq.android.call

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.meldiq.android.MainActivity
import com.meldiq.android.R
import com.meldiq.android.notify.Channels
import com.meldiq.android.notify.NotificationActionReceiver

/**
 * Heads-up / full-screen incoming call notification. Only works while the relay connection is alive
 * (app open, or "Stay connected" background service on). There is no push wake-up for killed apps
 * in this beta (docs/KNOWN_ISSUES.md).
 */
object IncomingCallNotifier {
    private const val ID = 4102

    fun show(context: Context, callId: String, title: String, video: Boolean) {
        val open = PendingIntent.getActivity(
            context, 2,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_CALL, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val decline = PendingIntent.getBroadcast(
            context, 3,
            Intent(context, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_DECLINE_CALL).putExtra("callId", callId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, Channels.INCOMING_CALL)
            .setSmallIcon(R.drawable.ic_stat_meldiq)
            .setContentTitle(title)
            .setContentText(if (video) "Incoming video call" else "Incoming voice call")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(true)
            .setTimeoutAfter(45_000)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .addAction(0, "Decline", decline)
            .addAction(0, "Answer", open)
            .setColor(0xFF4441D0.toInt())
            .build()
        // Ring until answered, declined or timed out: a notification sound otherwise plays once and stops, which on
        // the test phones left most of a 45-second ring silent. Volume, silent and vibrate modes still apply.
        n.flags = n.flags or Notification.FLAG_INSISTENT
        runCatching { context.getSystemService(NotificationManager::class.java).notify(ID, n) }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }
}
