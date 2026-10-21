package com.meldiq.android.ui

import android.content.Context
import android.widget.Toast
import androidx.navigation.NavHostController
import com.meldiq.android.R
import com.meldiq.android.data.model.MessageLinks
import com.meldiq.android.di.AppContainer

/**
 * Opens a message link (batch B5): the conversation at that message (inside its thread when it's a reply) when this
 * phone is in the conversation; the conversation alone when the message isn't on this phone; otherwise a short notice
 * and nothing else. The link alone never fetches anything about a conversation you aren't in.
 */
object MessageLinkOpener {
    sealed interface Result {
        data class Open(val route: String) : Result
        data class OpenWithoutMessage(val route: String) : Result
        data object NotMember : Result
    }

    suspend fun resolve(c: AppContainer, t: MessageLinks.Target): Result {
        val conv = c.db.conversations().get(t.conversationId)
        if (conv == null || conv.removed) return Result.NotMember
        val m = c.db.messages().get(t.messageId)?.takeIf { it.conversationId == t.conversationId && !it.deleted }
            ?: return Result.OpenWithoutMessage(Routes.conversation(t.conversationId))
        return Result.Open(Routes.conversation(t.conversationId, thread = m.threadRootId, message = m.id))
    }

    suspend fun open(c: AppContainer, nav: NavHostController, ctx: Context, t: MessageLinks.Target) {
        when (val r = resolve(c, t)) {
            is Result.Open -> nav.navigate(r.route)
            is Result.OpenWithoutMessage -> {
                nav.navigate(r.route)
                Toast.makeText(ctx, ctx.getString(R.string.b5_link_missing), Toast.LENGTH_SHORT).show()
            }
            Result.NotMember -> Toast.makeText(ctx, ctx.getString(R.string.b5_link_not_member), Toast.LENGTH_LONG).show()
        }
    }
}
