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
        val n = NotificationCompat.Builder(this, Channels.CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_meldiq)
            .setContentTitle("Meldiq is connected")
            .setContentText("Receiving messages and calls in the background")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
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
