package com.griipbuddy.orbit.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.griipbuddy.orbit.MainActivity
import com.griipbuddy.orbit.R
import com.griipbuddy.orbit.data.repo.Notice

object Channels {
    const val MESSAGES = "messages"
    const val MENTIONS = "mentions"
    const val INCOMING_CALL = "incoming_call_v2"
    const val ONGOING_CALL = "ongoing_call"
    const val CONNECTION = "connection"

    fun create(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply { description = "New messages in your conversations" })
        nm.createNotificationChannel(NotificationChannel(MENTIONS, "Mentions", NotificationManager.IMPORTANCE_HIGH).apply { description = "When someone @mentions you" })
        nm.createNotificationChannel(
            NotificationChannel(INCOMING_CALL, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
                enableVibration(true)
            },
        )
        nm.createNotificationChannel(NotificationChannel(ONGOING_CALL, "Ongoing call", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CONNECTION, "Background connection", NotificationManager.IMPORTANCE_MIN).apply { description = "Shown while Orbit stays connected for background delivery" })
    }
}

/** Posts message notifications. Content shown is decrypted on-device; nothing is fetched from a push service. */
class MessageNotifier(private val context: Context) {
    private val history = NotificationHistory()

    fun show(n: Notice) {
        val open = PendingIntent.getActivity(
            context, n.conversationId.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_CONVERSATION, n.conversationId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val me = Person.Builder().setName("You").build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(if (n.isGroup) n.title else null)
            .setGroupConversation(n.isGroup)
        for (l in history.add(n.conversationId, NotificationHistory.Line(n.sender, n.text.take(400), System.currentTimeMillis()))) {
            style.addMessage(l.text, l.at, Person.Builder().setName(l.sender).build())
        }
        val builder = NotificationCompat.Builder(context, if (n.mention) Channels.MENTIONS else Channels.MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_griip)
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setColor(0xFF1FA874.toInt())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, Channels.MESSAGES).setSmallIcon(R.drawable.ic_stat_griip).setContentTitle("Orbit").setContentText("New message").build(),
            )
        // Reply only where the user may post; both actions need the phone unlocked (T4.4).
        if (n.canReply) {
            val input = RemoteInput.Builder(MessageActionReceiver.KEY_REPLY).setLabel("Reply").build()
            builder.addAction(
                NotificationCompat.Action.Builder(0, "Reply", action(n.conversationId, MessageActionReceiver.ACTION_REPLY, mutable = true))
                    .addRemoteInput(input).setAllowGeneratedReplies(false).setAuthenticationRequired(true)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).setShowsUserInterface(false).build(),
            )
        }
        builder.addAction(
            NotificationCompat.Action.Builder(0, "Mark as read", action(n.conversationId, MessageActionReceiver.ACTION_MARK_READ, mutable = false))
                .setAuthenticationRequired(true).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).setShowsUserInterface(false).build(),
        )
        runCatching { context.getSystemService(NotificationManager::class.java).notify(n.conversationId.hashCode(), builder.build()) }
    }

    /** An explicit intent to this app's own receiver; only the reply needs to be mutable (RemoteInput fills it). */
    private fun action(conversationId: String, action: String, mutable: Boolean): PendingIntent = PendingIntent.getBroadcast(
        context, 31 * conversationId.hashCode() + action.hashCode(),
        Intent(context, MessageActionReceiver::class.java).setAction(action).putExtra(MessageActionReceiver.EXTRA_CONVERSATION, conversationId),
        (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE) or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Replaces the conversation's notification when a reply from it couldn't be queued. */
    fun showReplyFailed(conversationId: String) {
        history.clear(conversationId)
        val open = PendingIntent.getActivity(
            context, conversationId.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_CONVERSATION, conversationId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(context, Channels.MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_griip)
            .setContentTitle("Reply not sent")
            .setContentText("Open the conversation to send it again.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(conversationId.hashCode(), notif) }
    }

    fun clear(conversationId: String) {
        history.clear(conversationId)
        context.getSystemService(NotificationManager::class.java).cancel(conversationId.hashCode())
    }

    /** Removes every message notification and its history, for sign-out and erase. Call notifications are left to the call code. */
    fun clearAll() {
        history.clearAll()
        val nm = context.getSystemService(NotificationManager::class.java)
        runCatching { nm.activeNotifications.filter { it.notification.channelId == Channels.MESSAGES || it.notification.channelId == Channels.MENTIONS }.forEach { nm.cancel(it.tag, it.id) } }
    }
}
