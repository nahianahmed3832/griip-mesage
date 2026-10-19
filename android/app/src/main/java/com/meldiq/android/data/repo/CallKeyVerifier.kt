package com.meldiq.android.data.repo

import com.meldiq.android.data.api.CallDto

/**
 * A call's media key is accepted only from the member who started that call, and only in that call's
 * own conversation (T5.2). The envelope signature already proves who sent a `call_invite`; this checks
 * that the sender is the call's creator. Before, the first key to arrive won, so another member of the
 * conversation could send a key for someone else's call first and split or break it.
 *
 * [lookup] returns the server's record of the call, or null if the call doesn't exist for this user.
 * Any other failure should throw, so the queue item is redelivered and checked again later.
 */
class CallKeyVerifier(private val lookup: suspend (callId: String) -> CallDto?) {
    enum class Verdict { ACCEPT, NOT_CREATOR, WRONG_CONVERSATION, UNKNOWN_CALL }

    suspend fun check(callId: String, conversationId: String, senderUserId: String): Verdict {
        val call = lookup(callId) ?: return Verdict.UNKNOWN_CALL
        return when {
            call.conversationId != conversationId -> Verdict.WRONG_CONVERSATION
            call.createdBy != senderUserId -> Verdict.NOT_CREATOR
            else -> Verdict.ACCEPT
        }
    }
}
