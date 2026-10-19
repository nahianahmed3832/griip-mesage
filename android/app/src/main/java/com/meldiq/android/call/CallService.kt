package com.meldiq.android.call

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
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
import com.meldiq.android.MainActivity
import com.meldiq.android.R
import com.meldiq.android.notify.Channels
import com.meldiq.android.notify.NotificationActionReceiver

/**
 * Foreground service that keeps microphone/camera capture alive while a call is in progress and
 * the app is backgrounded (Android 14+ requires typed foreground services for capture).
 * Screen capture uses LiveKit's own mediaProjection-typed ScreenCaptureService, with Meldiq's notification
 * ([shareNotification]).
 *
 * UI-003: the ongoing-call notification is the call's system-visible status when Meldiq isn't on screen (voice calls
 * don't go into picture-in-picture): who, voice/video, the connected time, and Mute, Hang up / Leave and Stop sharing.
 * Tapping it returns to the full call screen.
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(notifyLock) { running = true }
        val info = intent?.let(Info::from) ?: Info("Call", video = false, group = false)
        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (granted(Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (info.video && granted(Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(this, info), type)
        } catch (e: Exception) {
            // Not allowed to start capture FGS right now (e.g. permission revoked) — call continues while in foreground.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** What the ongoing-call notification shows. */
    data class Info(
        val title: String,
        val video: Boolean,
        val group: Boolean,
        val micOn: Boolean = true,
        val sharing: Boolean = false,
        /** When media started flowing (the notification's timer); null while connecting. */
        val connectedAt: Long? = null,
        val reconnecting: Boolean = false,
    ) {
        fun into(i: Intent): Intent = i.putExtra("title", title).putExtra("video", video).putExtra("group", group).putExtra("micOn", micOn)
            .putExtra("sharing", sharing).putExtra("connectedAt", connectedAt ?: 0L).putExtra("reconnecting", reconnecting)

        companion object {
            fun from(i: Intent) = Info(
                i.getStringExtra("title") ?: "Call", i.getBooleanExtra("video", false), i.getBooleanExtra("group", false),
                i.getBooleanExtra("micOn", true), i.getBooleanExtra("sharing", false),
                i.getLongExtra("connectedAt", 0L).takeIf { it > 0 }, i.getBooleanExtra("reconnecting", false),
            )

            /** Null when there's no call to show (idle, ringing or ended). */
            fun of(s: CallUiState): Info? = when (s.phase) {
                CallPhase.Idle, CallPhase.Incoming, CallPhase.Ended -> null
                else -> Info(
                    s.title, video = s.video || s.cameraOn, group = s.isGroup, micOn = s.micOn, sharing = s.screenSharing,
                    connectedAt = s.connectedAt, reconnecting = s.phase == CallPhase.Reconnecting || s.peerReconnecting,
                )
            }
        }
    }

    companion object {
        const val NOTIFICATION_ID = 4101
        /** LiveKit's screen-capture service shows this one while the screen is shared. */
        const val SHARE_NOTIFICATION_ID = 4102

        fun start(context: Context, info: Info) {
            runCatching {
                ContextCompat.startForegroundService(context, info.into(Intent(context, CallService::class.java)))
            }
        }

        /** Whether the service is running: only then is its notification refreshed (a late update can't leave one behind). */
        @Volatile private var running = false
        /**
         * Updates and the stop never interleave. On the phone (UI-003) the "Reconnecting…" update sent when the other
         * person hung up raced the stop and left the ongoing notification behind after the call had ended.
         */
        private val notifyLock = Any()

        /** Refreshes the running service's notification (never starts a service from the background). */
        fun update(context: Context, info: Info) = synchronized(notifyLock) {
            if (!running) return@synchronized
            runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(context, info)) }
        }

        fun stop(context: Context) = synchronized(notifyLock) {
            running = false
            context.stopService(Intent(context, CallService::class.java))
            cancelNotification(context)
        }

        /** Removes the ongoing-call notification (also swept when the call is reset, in case an update slipped through). */
        fun cancelNotification(context: Context) {
            runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
        }

        private fun openCall(context: Context) = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_CALL, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun action(context: Context, act: String, req: Int) = PendingIntent.getBroadcast(
            context, req, Intent(context, NotificationActionReceiver::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE,
        )

        fun build(context: Context, info: Info): Notification {
            val text = when {
                info.reconnecting -> "Reconnecting…"
                info.sharing -> "You're sharing your screen"
                info.connectedAt == null -> "Connecting…"
                info.video -> if (info.group) "Group video call" else "Video call"
                else -> if (info.group) "Group voice call" else "Voice call"
            } + if (!info.micOn) " · muted" else ""
            val b = NotificationCompat.Builder(context, Channels.ONGOING_CALL)
                .setSmallIcon(R.drawable.ic_stat_meldiq)
                .setContentTitle(info.title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setContentIntent(openCall(context))
                .setColor(0xFFF6A585.toInt()) // Cocoa accent (peach)
                .addAction(0, if (info.micOn) "Mute" else "Unmute", action(context, NotificationActionReceiver.ACTION_TOGGLE_MIC, 7201))
            if (info.sharing) b.addAction(0, "Stop sharing", action(context, NotificationActionReceiver.ACTION_STOP_SHARE, 7203))
            b.addAction(0, if (info.group) "Leave" else "Hang up", action(context, NotificationActionReceiver.ACTION_HANG_UP, 7202))
            info.connectedAt?.let { b.setWhen(it).setShowWhen(true).setUsesChronometer(true) }
            return b.build()
        }

        /** Shown by LiveKit's capture service for as long as the screen is shared: what's visible, and a Stop action. */
        fun shareNotification(context: Context, title: String): Notification =
            NotificationCompat.Builder(context, Channels.ONGOING_CALL)
                .setSmallIcon(R.drawable.ic_stat_meldiq)
                .setContentTitle("Sharing your screen")
                .setContentText("Everyone in the call with $title sees your screen, including notifications.")
                .setOngoing(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setContentIntent(openCall(context))
                .setColor(0xFFF6A585.toInt())
                .addAction(0, "Stop sharing", action(context, NotificationActionReceiver.ACTION_STOP_SHARE, 7204))
                .build()
    }
}
