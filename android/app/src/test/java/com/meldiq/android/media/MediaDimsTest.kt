package com.meldiq.android.media

import com.meldiq.android.data.api.GriipJson
import com.meldiq.android.data.model.AttachmentRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batch B16: a photo's size as shown travels with it, so the chat keeps its space while it loads. */
class MediaDimsTest {
    @Test
    fun `a quarter turn swaps width and height, a half turn doesn't`() {
        assertEquals(3000 to 4000, MediaDims.oriented(4000, 3000, 90))
        assertEquals(3000 to 4000, MediaDims.oriented(4000, 3000, 270))
        assertEquals(4000 to 3000, MediaDims.oriented(4000, 3000, 180))
        assertEquals(4000 to 3000, MediaDims.oriented(4000, 3000, -360))
        assertNull(MediaDims.oriented(0, 3000, 0))
    }

    @Test
    fun `EXIF orientations, the mirrored ones included`() {
        assertEquals(0, MediaDims.exifDegrees(1))
        assertEquals(90, MediaDims.exifDegrees(6))
        assertEquals(90, MediaDims.exifDegrees(5))
        assertEquals(180, MediaDims.exifDegrees(3))
        assertEquals(270, MediaDims.exifDegrees(8))
        assertEquals(270, MediaDims.exifDegrees(7))
    }

    @Test
    fun `a size from another phone is used only when believable`() {
        assertTrue(MediaDims.valid(1080, 2400))
        assertFalse(MediaDims.valid(null, 2400))
        assertFalse(MediaDims.valid(0, 2400))
        assertFalse(MediaDims.valid(1080, 50_000))
    }

    @Test
    fun `the size is left out of the message when unknown, so older apps see the same format`() {
        val plain = AttachmentRef("a1", "f.jpg", "image/jpeg", 10, "k", "n", "s")
        assertFalse(GriipJson.encodeToString(AttachmentRef.serializer(), plain).contains("\"w\""))
        val sized = plain.copy(w = 1080, h = 2400)
        val json = GriipJson.encodeToString(AttachmentRef.serializer(), sized)
        assertTrue(json.contains("\"w\":1080") && json.contains("\"h\":2400"))
        assertEquals(sized, GriipJson.decodeFromString(AttachmentRef.serializer(), json))
    }
}
