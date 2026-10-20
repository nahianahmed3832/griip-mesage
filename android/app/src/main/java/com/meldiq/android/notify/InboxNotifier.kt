package com.meldiq.android.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.meldiq.android.MainActivity
import com.meldiq.android.R

/**
 * Customer inbox notices (round 8, docs/UNIFIED_INBOX.md): escalations, overdue customers, undelivered replies and
 * accounts that need attention. The server sends only ids, so a notice never carries the customer's name or text.
 */
class InboxNotifier(private val context: Context) {
    fun notice(kind: String, conversationId: String?) {
        val text = when (kind) {
            "inbox.escalated" -> R.string.ib_notify_escalated
            "inbox.overdue" -> R.string.ib_notify_overdue
            "inbox.delivery_failed" -> R.string.ib_notify_failed
            "inbox.account_failed" -> R.string.ib_notify_account
            else -> return
        }
        val id = (kind + (conversationId ?: "")).hashCode()
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, Channels.REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_meldiq)
            .setContentTitle(context.getString(R.string.ib_notify_title))
            .setContentText(context.getString(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, n) }
    }
}
