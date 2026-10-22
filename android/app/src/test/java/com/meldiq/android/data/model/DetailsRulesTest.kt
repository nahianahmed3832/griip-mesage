package com.meldiq.android.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Batch B14: the DM and group details tabs. */
class DetailsRulesTest {
    private fun ref(id: String, mime: String, voice: Boolean? = null) = AttachmentRef(id, "$id.bin", mime, 10, "k", "n", "s", voice = voice)
    private val isVisual: (AttachmentRef) -> Boolean = { it.mime.startsWith("image/") || it.mime.startsWith("video/") }
    private val isVoice: (AttachmentRef) -> Boolean = { it.voice == true }

    private val messages = listOf(
        DetailsRules.Msg("m1", 100, "ada", "see https://example.com/a and www.meldiq.app", listOf(ref("p1", "image/jpeg"))),
        DetailsRules.Msg("m2", 200, "bob", "", listOf(ref("doc", "application/pdf"), ref("vn", "audio/ogg", voice = true), ref("v1", "video/mp4"))),
        DetailsRules.Msg("m3", 300, "ada", "again https://example.com/a", emptyList()),
        DetailsRules.Msg("m4", 400, "bob", "gone https://deleted.example", listOf(ref("p2", "image/png")), deleted = true),
        DetailsRules.Msg("m5", 500, "ada", "", listOf(ref("pending", "image/png"))),
    )

    @Test
    fun `a DM has no members tab, a group starts on members`() {
        assertEquals(DetailsRules.Tab.MEDIA, DetailsRules.tabs("DM").first())
        assertEquals(false, DetailsRules.Tab.MEMBERS in DetailsRules.tabs("DM"))
        assertEquals(DetailsRules.Tab.MEMBERS, DetailsRules.tabs("GROUP").first())
    }

    @Test
    fun `a channel starts on members and lists its threads, a forum doesn't repeat its posts`() {
        assertEquals(
            listOf(DetailsRules.Tab.MEMBERS, DetailsRules.Tab.MEDIA, DetailsRules.Tab.PINS, DetailsRules.Tab.THREADS, DetailsRules.Tab.LINKS, DetailsRules.Tab.FILES),
            DetailsRules.tabs("CHANNEL"),
        )
        assertEquals(false, DetailsRules.Tab.THREADS in DetailsRules.tabs("CHANNEL", forum = true))
        assertEquals(false, DetailsRules.Tab.THREADS in DetailsRules.tabs("GROUP"))
    }

    @Test
    fun `photos and videos are media, newest first, without deleted or unsent ones`() {
        assertEquals(listOf("v1", "p1"), DetailsRules.media(messages, isVisual).map { it.value.id })
        assertEquals("m2", DetailsRules.media(messages, isVisual).first().messageId)
    }

    @Test
    fun `files leave out photos, videos and voice notes`() {
        assertEquals(listOf("doc"), DetailsRules.files(messages, isVisual, isVoice).map { it.value.id })
    }

    @Test
    fun `each link once, at its latest sharing, and none from deleted messages`() {
        val links = DetailsRules.links(messages)
        assertEquals(listOf("https://example.com/a", "https://www.meldiq.app"), links.map { it.value })
        assertEquals("m3", links.first().messageId)
        assertEquals("example.com", DetailsRules.host("https://example.com/a"))
        assertEquals("meldiq.app", DetailsRules.host("https://www.meldiq.app"))
    }
}

/** Batch B14: photo and video previews keep their shape within 3:4 and 16:9. */
class PreviewRatioTest {
    @Test
    fun `a phone screenshot is held at 3 to 4, a panorama at 16 to 9, a square stays square`() {
        assertEquals(0.75f, com.meldiq.android.ui.media.MediaGallery.previewRatio(1080, 2400), 0.001f)
        assertEquals(16f / 9f, com.meldiq.android.ui.media.MediaGallery.previewRatio(4000, 1000), 0.001f)
        assertEquals(1f, com.meldiq.android.ui.media.MediaGallery.previewRatio(500, 500), 0.001f)
        assertEquals(1f, com.meldiq.android.ui.media.MediaGallery.previewRatio(0, 0), 0.001f)
    }
}
