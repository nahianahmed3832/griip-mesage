package com.meldiq.android.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.meldiq.android.MainActivity
import com.meldiq.android.R
import com.meldiq.android.data.db.GriipDatabase
import com.meldiq.android.data.model.Reminder
import com.meldiq.android.data.repo.ConversationRepository

/**
 * Shows reminders the server fired (docs/REMINDERS.md), once per occurrence on this phone. The text comes from this
 * phone's own decrypted copy (and, for a saved message, from its encrypted database); the push that woke the phone
 * carried nothing. Lock-screen previews follow Settings › Notifications › Show previews.
 */
class ReminderNotifier(private val context: Context, private val db: GriipDatabase, private val myUserId: () -> String?) {
    private val prefs = context.getSharedPreferences("griip_settings", Context.MODE_PRIVATE)

    /** True when [r]'s current occurrence was shown now; false when it was already shown here (or can't be). */
    suspend fun show(r: Reminder): Boolean {
        val firedAt = r.firedAt ?: return false
        val key = "${r.id}@$firedAt"
        if (key in shown()) return false
        markShown(key)
        val previews = prefs.getBoolean("notificationPreviews", true)
        val content = r.content
        val ref = content?.ref
        val conv = ref?.conversationId?.let { db.conversations().get(it) }
        val message = ref?.messageId?.let { db.messages().get(it) }
        val title = when {
            content == null -> context.getString(R.string.rem_notification_title)
            content.source == "later" -> context.getString(R.string.later_reminder_title, conv?.let { ConversationRepository.title(it, myUserId()) } ?: context.getString(R.string.later_title))
            previews -> content.title
            else -> context.getString(R.string.rem_notification_title)
        }
        val text = when {
            !previews -> context.getString(R.string.rem_notification_private)
            content?.source == "later" -> message?.text?.takeIf { it.isNotBlank() }?.take(PREVIEW_MAX) ?: context.getString(R.string.later_reminder_body)
            content?.note != null -> content.note
            message != null && message.text.isNotBlank() -> message.text.take(PREVIEW_MAX)
            content == null -> context.getString(R.string.rem_unreadable)
            else -> context.getString(R.string.rem_notification_body)
        }
        val target = ref?.conversationId ?: MainActivity.TARGET_REMINDERS
        val open = PendingIntent.getActivity(
            context, key.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_CONVERSATION, target).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, Channels.REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_meldiq)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        return runCatching { context.getSystemService(NotificationManager::class.java).notify(r.id.hashCode(), n) }.isSuccess
    }

    private fun shown(): Set<String> = prefs.getString(SHOWN_KEY, "").orEmpty().split('\n').filter { it.isNotEmpty() }.toSet()

    private fun markShown(key: String) {
        val list = (prefs.getString(SHOWN_KEY, "").orEmpty().split('\n').filter { it.isNotEmpty() } + key).takeLast(MAX_SHOWN)
        prefs.edit().putString(SHOWN_KEY, list.joinToString("\n")).apply()
    }

    private companion object {
        const val SHOWN_KEY = "reminders.shown"
        const val MAX_SHOWN = 300
        const val PREVIEW_MAX = 200
    }
}
