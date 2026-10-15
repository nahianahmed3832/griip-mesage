package com.griipbuddy.message.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.griipbuddy.message.MainActivity
import com.griipbuddy.message.R
import com.griipbuddy.message.data.repo.Notice

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
        nm.createNotificationChannel(NotificationChannel(CONNECTION, "Background connection", NotificationManager.IMPORTANCE_MIN).apply { description = "Shown while Griip Message stays connected for background delivery" })
    }
}

/** Posts message notifications. Content shown is decrypted on-device; nothing is fetched from a push service. */
class MessageNotifier(private val context: Context) {
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
            .addMessage(n.text.take(400), System.currentTimeMillis(), Person.Builder().setName(n.sender).build())
        val notif = NotificationCompat.Builder(context, if (n.mention) Channels.MENTIONS else Channels.MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_griip)
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setColor(0xFF1FB5A6.toInt())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, Channels.MESSAGES).setSmallIcon(R.drawable.ic_stat_griip).setContentTitle("Griip Message").setContentText("New message").build(),
            )
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(n.conversationId.hashCode(), notif) }
    }

    fun clear(conversationId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(conversationId.hashCode())
    }
}
