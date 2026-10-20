package com.meldiq.android.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.meldiq.android.MainActivity
import com.meldiq.android.R
import com.meldiq.android.data.api.ApiClient
import com.meldiq.android.data.api.MeetingRes

/**
 * "Starting soon" for a scheduled meeting (round 7 slice C5, docs/WORKSPACE_C5.md): the server sends a live event ten
 * minutes ahead; this asks for the meeting (only someone who may see it gets it) and shows one notification. Tapping
 * it opens the meeting's voice room. Lock-screen previews follow Settings › Notifications › Show previews.
 */
class MeetingNotifier(private val context: Context, private val api: ApiClient) {
    private val prefs = context.getSharedPreferences("griip_settings", Context.MODE_PRIVATE)

    suspend fun startingSoon(meetingId: String) {
        val m = runCatching { api.get<MeetingRes>("/v1/meetings/$meetingId").meeting }.getOrNull() ?: return
        if (m.cancelled || m.myResponse == "declined") return
        val previews = prefs.getBoolean("notificationPreviews", true)
        val title = if (previews) context.getString(R.string.mtg_soon_title, m.title) else context.getString(R.string.mtg_soon_private)
        val text = m.room?.name?.let { context.getString(R.string.mtg_soon_body, it) } ?: context.getString(R.string.mtg_soon_private)
        val open = PendingIntent.getActivity(
            context, meetingId.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_CONVERSATION, m.room?.id ?: MainActivity.TARGET_REMINDERS).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, Channels.REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_meldiq)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(meetingId.hashCode(), n) }
    }
}
