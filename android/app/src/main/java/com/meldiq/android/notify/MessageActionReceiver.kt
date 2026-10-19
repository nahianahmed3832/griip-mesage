package com.meldiq.android.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.meldiq.android.GriipApp
import kotlinx.coroutines.launch

/**
 * The Reply and Mark as read actions on message notifications (T4.4). Not exported: only this app's
 * own notification PendingIntents reach it. Both actions require the phone to be unlocked.
 */
class MessageActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cid = intent.getStringExtra(EXTRA_CONVERSATION) ?: return
        val container = (context.applicationContext as GriipApp).container ?: return
        val reply = if (intent.action == ACTION_REPLY) RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim() else null
        if (intent.action != ACTION_REPLY && intent.action != ACTION_MARK_READ) return
        val done = goAsync()
        container.scope.launch {
            try {
                // Queued in the encrypted outbox, so it is sent once the connection allows, like any other message.
                if (!reply.isNullOrEmpty()) container.messages.sendText(cid, reply.take(MAX_REPLY_CHARS))
                container.notifier.clear(cid)
                container.messages.markConversationRead(cid)
            } catch (e: Exception) {
                Log.w("MessageActions", "notification action failed: ${e.javaClass.simpleName}")
                if (!reply.isNullOrEmpty()) container.notifier.showReplyFailed(cid) // never lose a reply silently
            } finally {
                done.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "com.meldiq.android.action.REPLY"
        const val ACTION_MARK_READ = "com.meldiq.android.action.MARK_READ"
        const val EXTRA_CONVERSATION = "conversation"
        const val KEY_REPLY = "reply"
        const val MAX_REPLY_CHARS = 4000
    }
}
