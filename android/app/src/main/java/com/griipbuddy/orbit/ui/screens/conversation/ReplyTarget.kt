package com.griipbuddy.orbit.ui.screens.conversation

import com.griipbuddy.orbit.data.db.MessageEntity

/**
 * The composer's reply target (unit-tested in `ReplyTargetTest`). Physical-device report (2026-10-17): after a swipe
 * to reply, the quoted message could stay stuck above the composer. Causes: photos, files and voice messages ignored
 * and never cleared the target; the target was read after the tap (a quick second swipe changed what was sent); it
 * was cleared even when sending failed; and mention lookup could wait on the network before clearing it.
 */
object ReplyTarget {
    /**
     * The target after a send that captured [sentId] when the user tapped send: cleared only on success and only if
     * it is still that same message (choosing another message meanwhile keeps the new choice); kept when sending
     * failed, so the user can send again.
     */
    fun afterSend(current: MessageEntity?, sentId: String?, success: Boolean): MessageEntity? =
        if (success && current != null && current.id == sentId) null else current

    /**
     * The target checked against the conversation's latest copy of that message: one from another conversation, or one
     * that has since been deleted, is dropped; otherwise the fresh copy (an edit shows in the preview).
     */
    fun refreshed(current: MessageEntity?, conversationId: String, latest: MessageEntity?): MessageEntity? = when {
        current == null -> null
        current.conversationId != conversationId -> null
        latest == null -> current // not loaded in this page of messages: keep it
        latest.deleted -> null
        else -> latest
    }
}
