package com.meldiq.android.ui.media

import com.meldiq.android.data.model.AttachmentRef
import com.meldiq.android.ui.media.BulkSave.Plan
import com.meldiq.android.ui.media.BulkSave.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkSaveTest {
    private fun ref(id: String, name: String, mime: String = "image/jpeg", size: Long = 1000) =
        AttachmentRef(id = id, name = name, mime = mime, size = size, key = "k", nonce = "n", sha256 = "h")

    @Test
    fun `five albums of ten photos plan as one batch of fifty`() {
        val picked = (1..5).flatMap { album -> (1..10).map { i -> "m$album" to ref("a$album-$i", "IMG_$i.jpg") } }
        val plan = BulkSave.plan(picked) as Plan.Ready
        assertEquals(50, plan.items.size)
        assertEquals(50, plan.items.map { it.fileName.lowercase() }.toSet().size)
    }

    @Test
    fun `clashing names get a number before the extension`() {
        assertEquals(listOf("a.jpg", "a (1).jpg", "A (2).JPG", "report"), BulkSave.uniqueNames(listOf("a.jpg", "a.jpg", "A.JPG", "report")))
        assertEquals(listOf("notes", "notes (1)"), BulkSave.uniqueNames(listOf("notes", "notes")))
    }

    @Test
    fun `excluded files and duplicates are left out`() {
        val picked = listOf("m1" to ref("1", "a.jpg"), "m1" to ref("1", "a.jpg"), "m1" to ref("2", "b.jpg"), "m2" to ref("3", "c.pdf", "application/pdf"))
        val plan = BulkSave.plan(picked, excluded = setOf(BulkSave.key("m1", "2"))) as Plan.Ready
        assertEquals(listOf("m1/1", "m2/3"), plan.items.map { it.key })
    }

    @Test
    fun `mixed photos videos and documents keep sensible names`() {
        val plan = BulkSave.plan(listOf("m" to ref("1", "clip", "video/mp4"), "m" to ref("2", "../../evil.pdf", "application/pdf"))) as Plan.Ready
        assertEquals(listOf("clip.mp4", "evil.pdf"), plan.items.map { it.fileName })
    }

    @Test
    fun `limits are reported, not silently cut`() {
        val many = (1..BulkSave.MAX_ITEMS + 1).map { "m" to ref("$it", "f$it.jpg") }
        assertEquals(Plan.TooMany(BulkSave.MAX_ITEMS + 1, BulkSave.MAX_ITEMS), BulkSave.plan(many))
        val huge = listOf("m" to ref("1", "a.mp4", "video/mp4", BulkSave.MAX_BYTES), "m" to ref("2", "b.mp4", "video/mp4", 1))
        assertTrue(BulkSave.plan(huge) is Plan.TooLarge)
        assertEquals(Plan.Empty, BulkSave.plan(emptyList()))
    }

    @Test
    fun `summary tells partial success from running`() {
        val running = BulkSave.summary(mapOf("a" to Status.SAVED, "b" to Status.SAVING))
        assertTrue(running.running)
        assertFalse(running.finished)
        val partial = BulkSave.summary(mapOf("a" to Status.SAVED, "b" to Status.FAILED, "c" to Status.CANCELLED))
        assertTrue(partial.finished)
        assertEquals(2, partial.retryable)
        assertEquals(1, partial.saved)
    }
}
