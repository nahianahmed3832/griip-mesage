package com.griipbuddy.orbit.call

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import com.griipbuddy.orbit.R
import com.griipbuddy.orbit.notify.NotificationActionReceiver
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Picture-in-picture for an ongoing call (2026-10-17, docs/CALLING_AND_E2EE.md › Minimised calls). Leaving Orbit during
 * a connected call shrinks it into a small window with Mute and Hang up; tapping it returns to the full call screen.
 * It never starts on its own outside a call. Rules unit-tested in `CallVerificationTest`.
 */
object CallPip {
    /** Whether the activity is showing in picture-in-picture now (the UI draws only the call then). */
    val active = MutableStateFlow(false)

    /** Calls that get picture-in-picture: connected or reconnecting, not ringing. */
    fun eligible(phase: CallPhase): Boolean = phase == CallPhase.Connected || phase == CallPhase.Reconnecting

    fun supported(activity: Activity): Boolean =
        Build.VERSION.SDK_INT >= 26 && activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun params(activity: Activity, s: CallUiState): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val video = s.video && s.participants.any { !it.isLocal && (it.camera != null || it.screen != null) }
        fun action(icon: Int, title: String, act: String, req: Int) = RemoteAction(
            Icon.createWithResource(activity, icon), title, title,
            PendingIntent.getBroadcast(activity, req, Intent(activity, NotificationActionReceiver::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE),
        )
        val b = PictureInPictureParams.Builder()
            .setAspectRatio(if (video) Rational(9, 16) else Rational(1, 1))
            .setActions(
                listOf(
                    action(R.drawable.ic_stat_griip, if (s.micOn) "Mute" else "Unmute", NotificationActionReceiver.ACTION_TOGGLE_MIC, 7101),
                    action(android.R.drawable.ic_menu_close_clear_cancel, "Hang up", NotificationActionReceiver.ACTION_HANG_UP, 7102),
                ),
            )
        // Android 12+: gesture navigation enters it smoothly while a call is on, and never otherwise.
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(eligible(s.phase)).setSeamlessResizeEnabled(video)
        return b.build()
    }
}
