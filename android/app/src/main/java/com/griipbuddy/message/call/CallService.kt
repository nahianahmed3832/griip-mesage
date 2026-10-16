package com.griipbuddy.message.call

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.griipbuddy.message.MainActivity
import com.griipbuddy.message.R
import com.griipbuddy.message.notify.Channels

/**
 * Foreground service that keeps microphone/camera capture alive while a call is in progress and
 * the app is backgrounded (Android 14+ requires typed foreground services for capture).
 * Screen capture uses LiveKit's own mediaProjection-typed ScreenCaptureService.
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra("title") ?: "Call"
        val video = intent?.getBooleanExtra("video", false) ?: false
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_CALL, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, Channels.ONGOING_CALL)
            .setSmallIcon(R.drawable.ic_stat_griip)
            .setContentTitle(title)
            .setContentText(if (video) "Video call in progress" else "Voice call in progress")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .setColor(0xFF1FA874.toInt())
            .build()
        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (granted(Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (video && granted(Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
        } catch (e: Exception) {
            // Not allowed to start capture FGS right now (e.g. permission revoked) — call continues while in foreground.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val NOTIFICATION_ID = 4101
        fun start(context: Context, title: String, video: Boolean) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, CallService::class.java).putExtra("title", title).putExtra("video", video))
            }
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}
