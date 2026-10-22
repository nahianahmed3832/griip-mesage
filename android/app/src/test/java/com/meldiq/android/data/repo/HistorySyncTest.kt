package com.meldiq.android.data.repo

import com.meldiq.android.crypto.HistoryKeys
import com.meldiq.android.data.api.GriipJson
import com.meldiq.android.data.db.ConversationEntity
import com.meldiq.android.data.db.MessageEntity
import com.meldiq.android.data.model.AttachmentRef
import com.meldiq.android.data.model.ContactCard
import com.meldiq.android.data.model.RichContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySyncTest {
    private fun msg(id: String = "m1", status: String = "DELIVERED", kind: String = "text", text: String = "hi") =
        MessageEntity(id, "c1", "u1", "d1", outgoing = false, kind = kind, text = text, sentAt = 1, sortTs = 1, status = status)

    private val photo = AttachmentRef("a1", "p.jpg", "image/jpeg", 10, "k", "n", "s")
    private val pending = AttachmentRef("pending", "p.jpg", "image/jpeg", 10, "", "", "f")

    @Test
    fun onlySettledMessagesAreUploaded() {
        assertTrue(HistoryCodec.uploadable(msg(), emptyList()))
        assertTrue(HistoryCodec.uploadable(msg(status = "SENT"), listOf(photo)))
        assertFalse(HistoryCodec.uploadable(msg(status = "SENDING"), emptyList()))
        assertFalse(HistoryCodec.uploadable(msg(status = "FAILED"), emptyList()))
        assertFalse(HistoryCodec.uploadable(msg(kind = "undecryptable"), emptyList()))
        assertFalse(HistoryCodec.uploadable(msg(status = "SENT"), listOf(photo, pending)))
    }

    @Test
    fun localOnlyStateIsLeftOut() {
        assertNull(HistoryCodec.forUpload(msg().copy(errorText = "boom")).errorText)
        val c = ConversationEntity("c1", "DM", null, null, null, null, null, null, null, false, 2, "[]", true, null, true, false, null, false, 1, draft = "half a sentence", unreadCount = 4, mentionCount = 1, muted = true)
        val up = HistoryCodec.forUpload(c)
        assertNull(up.draft)
        assertEquals(0, up.unreadCount)
        assertTrue(up.muted) // preferences travel; drafts and counters don't
    }

    @Test
    fun downloadsAddMissingMessagesAndOnlyLaterEditsOrDeletionsChangeExistingOnes() {
        val remote = msg(text = "original")
        assertEquals(remote, HistoryCodec.merge(null, remote))
        assertNull(HistoryCodec.merge(remote, remote))
        val edited = remote.copy(text = "edited", editedAt = 5)
        assertEquals("edited", HistoryCodec.merge(remote, edited)!!.text)
        assertNull(HistoryCodec.merge(edited, remote.copy(text = "older", editedAt = 2)))
        val gone = HistoryCodec.merge(remote.copy(reactionsJson = """{"👍":["u2"]}"""), remote.copy(deleted = true))!!
        assertTrue(gone.deleted)
        assertEquals("", gone.text)
        assertEquals("""{"👍":["u2"]}""", gone.reactionsJson) // this phone's own state is kept
        assertNull(HistoryCodec.merge(remote.copy(deleted = true, text = ""), edited))
    }

    @Test
    fun recordsSurviveEncryptionAndJson() {
        val keys = HistoryKeys(ByteArray(32) { 3 }, "u_me")
        val m = msg().copy(
            attachmentsJson = """[{"id":"a1","name":"p.jpg","mime":"image/jpeg","size":10,"key":"k","nonce":"n","sha256":"s"}]""",
            richJson = GriipJson.encodeToString(RichContent.serializer(), RichContent(contact = ContactCard("Sam", listOf("+880 1700")))),
            replyToId = "m0", forwarded = true, pinned = true,
        )
        val tag = keys.messageTag(m.id)
        val sealed = keys.seal(tag, GriipJson.encodeToString(HistoryRecord.serializer(), HistoryRecord(message = m)).toByteArray())
        val back = GriipJson.decodeFromString(HistoryRecord.serializer(), String(keys.open(tag, sealed)))
        assertEquals(m, back.message)
        assertNull(back.conversation)
    }
}
