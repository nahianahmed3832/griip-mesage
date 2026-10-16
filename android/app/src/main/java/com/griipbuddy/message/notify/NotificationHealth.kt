package com.griipbuddy.message.notify

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Why notifications might not appear or make a sound, told apart (physical-device report 2026-10-16: "no
 * notification sound" on both phones). Each link in the chain is checked separately:
 * delivery (the app's own connection, kept alive in the background, or not), display (Android's permission and the
 * app's notification switch), and sound (each channel's importance and sound, which the person or the phone maker
 * controls, and which no app may override). Pure rules here; [read] gathers the phone's state.
 */
object NotificationHealth {
    data class ChannelState(val id: String, val name: String, val importance: Int, val hasSound: Boolean)

    data class State(
        val sdk: Int,
        val permissionGranted: Boolean,
        val appNotificationsEnabled: Boolean,
        val channels: List<ChannelState>,
        /** null below Android 6, where there is no battery optimisation to check. */
        val batteryOptimized: Boolean?,
        val inAppNotifications: Boolean,
        val backgroundConnection: Boolean,
    )

    enum class Fix { AppNotificationSettings, ChannelSettings, BatterySettings, InAppSetting }

    data class Problem(val title: String, val detail: String, val fix: Fix, val channelId: String? = null, val severe: Boolean = true)

    /** The channels whose sound matters, with the name the person sees in Android's settings. */
    val ALERTING = listOf(Channels.MESSAGES to "Messages", Channels.MENTIONS to "Mentions", Channels.INCOMING_CALL to "Incoming calls")

    fun problems(s: State): List<Problem> {
        val out = mutableListOf<Problem>()
        if (!s.permissionGranted || !s.appNotificationsEnabled) {
            out += Problem(
                "Notifications are turned off for Orbit",
                "You won't see new messages or incoming calls while the app is closed. Turn notifications on in Android settings.",
                Fix.AppNotificationSettings,
            )
            return out // nothing below matters until this is fixed
        }
        if (!s.inAppNotifications) {
            out += Problem("Message notifications are off in Orbit", "Turn on \"Message notifications\" above.", Fix.InAppSetting, severe = false)
        }
        for ((id, label) in ALERTING) {
            val ch = s.channels.firstOrNull { it.id == id } ?: continue
            when {
                ch.importance == NotificationManager.IMPORTANCE_NONE ->
                    out += Problem("\"$label\" notifications are blocked", "Android won't show them. Allow them in this channel's settings.", Fix.ChannelSettings, id)
                ch.importance < NotificationManager.IMPORTANCE_DEFAULT || !ch.hasSound ->
                    out += Problem(
                        "\"$label\" notifications are silent",
                        "They appear without a sound${if (ch.importance < NotificationManager.IMPORTANCE_HIGH) " or pop-up" else ""}. Set a sound and allow pop-ups in this channel's settings.",
                        Fix.ChannelSettings, id,
                    )
            }
        }
        if (!s.backgroundConnection) {
            out += Problem(
                "Background connection is off",
                "Orbit doesn't use a push service, so messages and calls only arrive while the app is open. Turn on \"Stay connected in background\".",
                Fix.InAppSetting,
            )
        } else if (s.batteryOptimized == true) {
            out += Problem(
                "Battery optimisation may stop background delivery",
                "Some phones close Orbit's background connection to save battery, which delays messages and calls. Allow Orbit to run in the background (\"Unrestricted\" or \"No restrictions\").",
                Fix.BatterySettings, severe = false,
            )
        }
        return out
    }

    fun read(context: Context, inAppNotifications: Boolean, backgroundConnection: Boolean): State {
        val nm = context.getSystemService(NotificationManager::class.java)
        val permission = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val channels = ALERTING.mapNotNull { (id, name) ->
            nm.getNotificationChannel(id)?.let { ChannelState(id, name, it.importance, it.sound != null) }
        }
        val power = context.getSystemService(PowerManager::class.java)
        return State(
            sdk = Build.VERSION.SDK_INT,
            permissionGranted = permission,
            appNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            channels = channels,
            batteryOptimized = power?.let { !it.isIgnoringBatteryOptimizations(context.packageName) },
            inAppNotifications = inAppNotifications,
            backgroundConnection = backgroundConnection,
        )
    }

    /** The Android settings screen that fixes [p] (never changes a setting itself). Null for in-app settings. */
    fun intentFor(context: Context, p: Problem): Intent? = when (p.fix) {
        Fix.AppNotificationSettings -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        Fix.ChannelSettings -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, p.channelId)
        // The app's own details page: battery settings live there on most phones (no special permission needed).
        Fix.BatterySettings -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        Fix.InAppSetting -> null
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
