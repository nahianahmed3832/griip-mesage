package com.meldiq.android.data.model

import com.meldiq.android.data.db.MessageColumns
import com.meldiq.android.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessagePreviewTest {
    private val photo = AttachmentRef("a1", "IMG_1.jpg", "image/jpeg", 10, "k", "n", "s")
    private val video = photo.copy(id = "a2", name = "clip.mp4", mime = "video/mp4")
    private val doc = photo.copy(id = "a3", name = "report.pdf", mime = "application/pdf")
    private val voice = photo.copy(id = "a4", name = "voice.m4a", mime = "audio/mp4", voice = true, durationMs = 65_000)

    private fun row(text: String = "hi", refs: List<AttachmentRef> = emptyList(), rich: RichContent? = null, kind: String = "text", call: CallInfo? = null) =
        MessageEntity(
            "m1", "c1", "u1", "d1", outgoing = false, kind = kind, text = text, sentAt = 1, sortTs = 1, status = "RECEIVED",
            attachmentsJson = MessageColumns.encodeAttachments(refs), richJson = MessageColumns.encodeRich(rich), callJson = call?.let(MessageColumns::encodeCall),
        )

    @Test
    fun `text alone is the preview`() {
        assertEquals("hi", MessagePreview.of(row()))
        assertEquals("hi", MessagePreview.of(Payload(type = "text", text = "hi")))
    }

    @Test
    fun `a single photo or video reads the same for sender, receiver and restored history`() {
        assertEquals("📷 Photo", MessagePreview.of(row(text = "", refs = listOf(photo))))
        assertEquals("📷 Photo", MessagePreview.of(Payload(type = "text", text = "", attachments = listOf(photo))))
        assertEquals("📷 Photo", MessagePreview.of("", listOf(photo), null))
        assertEquals("🎬 Video", MessagePreview.of(row(text = "", refs = listOf(video))))
    }

    @Test
    fun `a single file is named, several attachments are counted`() {
        assertEquals("📎 report.pdf", MessagePreview.of(row(text = "", refs = listOf(doc))))
        assertEquals("📷 2 photos", MessagePreview.of(row(text = "", refs = listOf(photo, photo.copy(id = "b")))))
        assertEquals("📷 1 photo and 1 video", MessagePreview.of(row(text = "", refs = listOf(photo, video))))
        assertEquals("📎 3 attachments", MessagePreview.of(row(text = "", refs = listOf(photo, video, doc))))
    }

    @Test
    fun `a caption is shown with the attachment mark`() {
        assertEquals("📎 look", MessagePreview.of(row(text = "look", refs = listOf(photo))))
        assertEquals("📎 look", MessagePreview.of(Payload(type = "text", text = "look", attachments = listOf(photo))))
    }

    @Test
    fun `a voice message keeps its length`() {
        assertEquals("🎤 Voice message (1:05)", MessagePreview.of(row(text = "", refs = listOf(voice))))
    }

    @Test
    fun `cards, locations and animated emoji replace their fallback text`() {
        assertEquals("👤 Sam", MessagePreview.of(row(text = "Sam's card", rich = RichContent(contact = ContactCard("Sam")))))
        assertEquals("📍 Home", MessagePreview.of(row(text = "x", rich = RichContent(location = SharedLocation(1.0, 2.0, label = "Home")))))
        assertEquals("📍 Location", MessagePreview.of(row(text = "x", rich = RichContent(location = SharedLocation(1.0, 2.0)))))
        assertEquals("🎉", MessagePreview.of(row(text = "🎉", rich = RichContent(animoji = "🎉"))))
    }

    @Test
    fun `deleted messages and calls`() {
        assertEquals(MessagePreview.DELETED, MessagePreview.of(row().copy(deleted = true)))
        assertEquals("📞 Call", MessagePreview.of(row(text = "", kind = "call")))
        val missed = CallInfo("k1", video = false, outcome = CallHistory.MISSED)
        assertEquals(CallHistory.preview(missed, outgoing = false, fallback = ""), MessagePreview.of(row(text = "Voice call", kind = "call", call = missed)))
    }

    @Test
    fun `columns that don't decode read as empty`() {
        val broken = row().copy(attachmentsJson = "{nope", richJson = "[]", callJson = "")
        assertEquals(emptyList<AttachmentRef>(), MessageColumns.attachments(broken))
        assertNull(MessageColumns.rich(broken))
        assertNull(MessageColumns.call(broken))
        assertEquals("hi", MessagePreview.of(broken))
    }

    @Test
    fun `columns round-trip`() {
        val m = row(refs = listOf(photo, voice), rich = RichContent(animoji = "🎉"), call = CallInfo("k", true))
        assertEquals(listOf(photo, voice), MessageColumns.attachments(m))
        assertEquals("🎉", MessageColumns.rich(m)?.animoji)
        assertEquals("k", MessageColumns.call(m)?.callId)
        assertNull(MessageColumns.encodeRich(null))
    }
}
