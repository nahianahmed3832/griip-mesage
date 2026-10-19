package com.meldiq.android.ui.screens.conversation

import com.meldiq.android.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The swipe-to-reply target (2026-10-17 fix: it stayed stuck after sending, or vanished when sending failed). */
class ReplyTargetTest {
    private fun msg(id: String, conv: String = "c1", text: String = "hi", deleted: Boolean = false) = MessageEntity(
        id = id, conversationId = conv, senderUserId = "u1", senderDeviceId = "d1", outgoing = false, kind = "text",
        text = text, sentAt = 1, sortTs = 1, status = "RECEIVED", deleted = deleted,
    )

    @Test
    fun clearedOnlyAfterTheReplySendsSuccessfully() {
        val target = msg("m1")
        assertNull(ReplyTarget.afterSend(target, "m1", success = true))
        assertEquals(target, ReplyTarget.afterSend(target, "m1", success = false)) // failed: keep it for the retry
    }

    @Test
    fun aNewerSelectionSurvivesTheEarlierSend() {
        // Replying to m1, then swiping m2 while m1's reply is still sending: m2 stays selected.
        val newer = msg("m2")
        assertEquals(newer, ReplyTarget.afterSend(newer, "m1", success = true))
        // A plain message (no target at the tap) never clears a target picked afterwards.
        assertEquals(newer, ReplyTarget.afterSend(newer, null, success = true))
        assertNull(ReplyTarget.afterSend(null, "m1", success = true))
    }

    @Test
    fun theTargetNeverLeaksIntoAnotherConversationAndFollowsEditsAndDeletes() {
        val target = msg("m1", conv = "c1")
        assertNull(ReplyTarget.refreshed(target, "c2", msg("m1", conv = "c1")))
        assertEquals("edited", ReplyTarget.refreshed(target, "c1", msg("m1", text = "edited"))!!.text)
        assertNull(ReplyTarget.refreshed(target, "c1", msg("m1", deleted = true))) // deleted for everyone: drop it
        assertEquals(target, ReplyTarget.refreshed(target, "c1", null)) // older than the loaded page: keep it
        assertNull(ReplyTarget.refreshed(null, "c1", msg("m1")))
    }
}
