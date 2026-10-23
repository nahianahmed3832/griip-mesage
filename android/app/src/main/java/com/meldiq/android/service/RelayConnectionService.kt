package com.meldiq.android.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.meldiq.android.GriipApp
import com.meldiq.android.MainActivity
import com.meldiq.android.R
import com.meldiq.android.notify.Channels

/**
 * Optional "Stay connected" mode: keeps the relay WebSocket open while the app is in the background
 * so messages and calls arrive without a third-party push service. Costs battery; can be disabled
 * in Settings. Does NOT survive force-stop, and the OS may still restrict it (docs/KNOWN_ISSUES.md).
 */
class RelayConnectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        // Android requires a notification while the connection runs; it is made as quiet as the system allows (the
        // owner, 2026-10-23): silent, no timer, hidden on the lock screen, and dismissible (Android 14+) or hidden
        // for good from its "Hide" action (the channel's settings). Delivery goes on either way.
        val hide = PendingIntent.getActivity(this, 1, Channels.settingsIntent(this, Channels.CONNECTION), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, Channels.CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_meldiq)
            .setContentTitle(getString(R.string.bg_conn_title))
            .setContentText(getString(R.string.bg_conn_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .setSilent(true)
            .setLocalOnly(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.bg_conn_hide), hide)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        try {
            ServiceCompat.startForeground(this, 4103, n, type)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        (application as GriipApp).container?.realtime?.start()
        return START_STICKY
    }

    companion object {
        fun start(context: Context) = runCatching { ContextCompat.startForegroundService(context, Intent(context, RelayConnectionService::class.java)) }
        fun stop(context: Context) = context.stopService(Intent(context, RelayConnectionService::class.java))
    }
}
