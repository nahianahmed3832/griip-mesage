package com.meldiq.android.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.meldiq.android.GriipApp
import com.meldiq.android.MainActivity
import com.meldiq.android.R
import com.meldiq.android.data.api.SavedItemDto
import com.meldiq.android.data.repo.ConversationRepository
import java.util.concurrent.TimeUnit

/**
 * Reminders for messages saved for later (Home › Later, 2026-10-19). Each open saved message with a reminder gets one
 * WorkManager job at its time; the job shows a notification only if the reminder still stands (not done, not removed,
 * not already shown), so edits on another device simply win. The message text is read from this phone's encrypted
 * database when the reminder fires; nothing is sent anywhere.
 */
object LaterReminders {
    private const val TAG = "later-reminder"
    private const val SHOWN_KEY = "later.shown"
    private const val MAX_SHOWN = 200
    private const val PREVIEW_MAX = 200

    /** Schedules the open reminders in [saved] and drops the rest. Cheap enough to run whenever the list changes. */
    fun sync(context: Context, saved: List<SavedItemDto>, now: Long = System.currentTimeMillis()) {
        val wm = WorkManager.getInstance(context)
        wm.cancelAllWorkByTag(TAG)
        val shown = shown(context)
        for (item in saved) {
            val at = item.remindAt ?: continue
            if (item.done || key(item) in shown) continue
            val req = OneTimeWorkRequestBuilder<LaterReminderWorker>()
                .setInitialDelay((at - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("message" to item.messageId, "conversation" to item.conversationId, "at" to at))
                .addTag(TAG)
                .build()
            wm.enqueueUniqueWork("$TAG-${item.messageId}", ExistingWorkPolicy.REPLACE, req)
        }
    }

    fun key(item: SavedItemDto) = "${item.messageId}@${item.remindAt}"

    private fun prefs(context: Context) = context.getSharedPreferences("griip_settings", Context.MODE_PRIVATE)

    fun shown(context: Context): Set<String> = prefs(context).getString(SHOWN_KEY, "").orEmpty().split('\n').filter { it.isNotEmpty() }.toSet()

    internal fun markShown(context: Context, key: String) {
        val list = (prefs(context).getString(SHOWN_KEY, "").orEmpty().split('\n').filter { it.isNotEmpty() } + key).takeLast(MAX_SHOWN)
        prefs(context).edit().putString(SHOWN_KEY, list.joinToString("\n")).apply()
    }

    internal suspend fun show(context: Context, messageId: String, conversationId: String, at: Long) {
        val c = (context.applicationContext as GriipApp).container ?: return
        val item = c.navPrefs.saved.value.firstOrNull { it.messageId == messageId } ?: return
        if (item.done || item.remindAt != at) return
        val k = key(item)
        if (k in shown(context)) return
        markShown(context, k)
        val m = c.db.messages().get(messageId)
        val conv = c.db.conversations().get(conversationId)
        val title = conv?.let { ConversationRepository.title(it, c.session.session?.userId) } ?: context.getString(R.string.later_title)
        val previews = context.getSharedPreferences("griip_settings", Context.MODE_PRIVATE).getBoolean("notificationPreviews", true)
        val text = if (previews) m?.text?.takeIf { it.isNotBlank() }?.take(PREVIEW_MAX) ?: context.getString(R.string.later_reminder_body) else context.getString(R.string.later_reminder_body)
        val open = PendingIntent.getActivity(
            context, messageId.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_CONVERSATION, conversationId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, Channels.MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_meldiq)
            .setContentTitle(context.getString(R.string.later_reminder_title, title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(messageId.hashCode(), n) }
    }
}

class LaterReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val message = inputData.getString("message") ?: return Result.success()
        val conversation = inputData.getString("conversation") ?: return Result.success()
        LaterReminders.show(applicationContext, message, conversation, inputData.getLong("at", 0))
        return Result.success()
    }
}
