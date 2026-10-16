package com.griipbuddy.orbit.data.model

import com.griipbuddy.orbit.data.model.AttachmentPolicy.Kind
import com.griipbuddy.orbit.data.model.AttachmentPolicy.Quality
import com.griipbuddy.orbit.data.model.AttachmentPolicy.Sniffed
import com.griipbuddy.orbit.data.model.AttachmentPolicy.Verdict
import com.griipbuddy.orbit.ui.screens.conversation.Album
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batch attachments: what may be sent and how files are checked (2026-10-17, docs/ATTACHMENTS.md). */
class AttachmentPolicyTest {
    private fun bytes(vararg b: Int) = ByteArray(32).also { a -> b.forEachIndexed { i, v -> a[i] = v.toByte() } }
    private fun ascii(at: Int, s: String, base: ByteArray = ByteArray(32)) = base.also { s.forEachIndexed { i, ch -> it[at + i] = ch.code.toByte() } }

    @Test
    fun theRealTypeComesFromTheFirstBytes() {
        assertEquals(Sniffed.JPEG, AttachmentPolicy.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(Sniffed.PNG, AttachmentPolicy.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals(Sniffed.GIF, AttachmentPolicy.sniff(ascii(0, "GIF89a")))
        assertEquals(Sniffed.WEBP, AttachmentPolicy.sniff(ascii(8, "WEBP", ascii(0, "RIFF"))))
        assertEquals(Sniffed.MP4, AttachmentPolicy.sniff(ascii(8, "isom", ascii(4, "ftyp"))))
        assertEquals(Sniffed.MP4, AttachmentPolicy.sniff(ascii(8, "qt  ", ascii(4, "ftyp")))) // iPhone .mov
        assertEquals(Sniffed.HEIF, AttachmentPolicy.sniff(ascii(8, "heic", ascii(4, "ftyp"))))
        assertEquals(Sniffed.PDF, AttachmentPolicy.sniff(ascii(0, "%PDF-1.7")))
        assertEquals(Sniffed.ZIP, AttachmentPolicy.sniff(bytes(0x50, 0x4B, 0x03, 0x04)))
        assertEquals(Sniffed.EXECUTABLE, AttachmentPolicy.sniff(ascii(0, "MZ")))
        assertEquals(Sniffed.EXECUTABLE, AttachmentPolicy.sniff(bytes(0x7F, 0x45, 0x4C, 0x46)))
        assertEquals(Sniffed.EXECUTABLE, AttachmentPolicy.sniff(ascii(0, "#!/bin/sh")))
        assertNull(AttachmentPolicy.sniff(ascii(0, "hello, world")))
        assertNull(AttachmentPolicy.sniff(ByteArray(0)))
        assertNull(AttachmentPolicy.sniff(bytes(0xFF, 0xD8))) // too short to be sure
    }

    @Test
    fun aDisguisedFileIsSentAsAFileNeverAsAPhoto() {
        // A program named and typed as a photo: a plain file, typed as one, and flagged.
        assertEquals(Kind.FILE, AttachmentPolicy.kind("image/jpeg", Sniffed.EXECUTABLE))
        assertEquals("application/octet-stream", AttachmentPolicy.sendMime("image/jpeg", Kind.FILE, Sniffed.EXECUTABLE))
        assertNotNull(AttachmentPolicy.risk("holiday.jpg", "image/jpeg", Sniffed.EXECUTABLE))
        // Real photos and videos keep their kind; the type follows the bytes.
        assertEquals(Kind.PHOTO, AttachmentPolicy.kind("image/jpeg", Sniffed.JPEG))
        assertEquals("image/png", AttachmentPolicy.sendMime("image/jpeg", Kind.PHOTO, Sniffed.PNG))
        assertEquals(Kind.VIDEO, AttachmentPolicy.kind("video/quicktime", Sniffed.MP4))
        assertEquals(Kind.PHOTO, AttachmentPolicy.kind("application/octet-stream", Sniffed.HEIF))
        // A PDF called .jpg is a file; a ZIP-based document keeps its own type.
        assertEquals(Kind.FILE, AttachmentPolicy.kind("image/jpeg", Sniffed.PDF))
        assertEquals("application/pdf", AttachmentPolicy.sendMime("application/pdf", Kind.FILE, Sniffed.PDF))
        assertEquals(Kind.FILE, AttachmentPolicy.kind("video/mp4", null))
    }

    @Test
    fun filesThatRunSoftwareAreFlagged() {
        assertNotNull(AttachmentPolicy.risk("app-release.apk", "application/vnd.android.package-archive", Sniffed.ZIP))
        assertNotNull(AttachmentPolicy.risk("update", "application/vnd.android.package-archive", null))
        assertNotNull(AttachmentPolicy.risk("invoice.pdf.exe", "application/octet-stream", null)) // a hidden second extension
        assertNotNull(AttachmentPolicy.risk("SETUP.MSI", "application/octet-stream", null))
        assertNotNull(AttachmentPolicy.risk("budget.xlsm", "application/vnd.ms-excel", Sniffed.ZIP))
        assertNotNull(AttachmentPolicy.risk("notes.txt", "text/plain", Sniffed.EXECUTABLE)) // content wins over the name
        assertNull(AttachmentPolicy.risk("report.pdf", "application/pdf", Sniffed.PDF))
        assertNull(AttachmentPolicy.risk("budget.xlsx", "application/vnd.ms-excel", Sniffed.ZIP))
        assertNull(AttachmentPolicy.risk("photo.jpg", "image/jpeg", Sniffed.JPEG))
    }

    @Test
    fun namesAreSafeToShowAndSave() {
        assertEquals("report.pdf", AttachmentPolicy.safeName("/storage/emulated/0/Download/report.pdf"))
        assertEquals("file", AttachmentPolicy.safeName(null))
        assertEquals("file", AttachmentPolicy.safeName("..."))
        assertEquals("env", AttachmentPolicy.safeName(".env")) // no hidden files
        assertEquals("a_b_c.txt", AttachmentPolicy.safeName("a:b*c.txt"))
        // A right-to-left override makes "photo‮gpj.exe" look like "photoexe.jpg": the override is removed.
        assertEquals("photogpj.exe", AttachmentPolicy.safeName("photo‮gpj.exe"))
        assertEquals("ab.txt", AttachmentPolicy.safeName("a\u0000b​.txt"))
        val long = AttachmentPolicy.safeName("x".repeat(300) + ".pdf")
        assertEquals(120, long.length)
        assertTrue(long.endsWith(".pdf"))
        assertEquals("IMG_1.jpg", AttachmentPolicy.withExtension("IMG_1.HEIC", "jpg"))
        assertEquals("clip.mp4", AttachmentPolicy.withExtension("clip", "mp4"))
    }

    @Test
    fun sizeLimitsDependOnWhetherTheFileIsCompressed() {
        val mb = 1024L * 1024
        assertEquals(Verdict.Ok, AttachmentPolicy.check(Kind.FILE, Sniffed.PDF, 25 * mb, Quality.STANDARD))
        assertTrue(AttachmentPolicy.check(Kind.FILE, Sniffed.PDF, 25 * mb + 1, Quality.STANDARD) is Verdict.TooLarge)
        // A 300 MB video can be sent at Standard (it's compressed first), not as the original.
        assertEquals(Verdict.Ok, AttachmentPolicy.check(Kind.VIDEO, Sniffed.MP4, 300 * mb, Quality.STANDARD))
        assertTrue(AttachmentPolicy.check(Kind.VIDEO, Sniffed.MP4, 300 * mb, Quality.ORIGINAL) is Verdict.TooLarge)
        assertTrue(AttachmentPolicy.check(Kind.VIDEO, Sniffed.MP4, 2048 * mb, Quality.HD) is Verdict.TooLarge)
        // GIFs and WebM videos are never re-encoded, so the 25 MB limit applies at every quality.
        assertTrue(AttachmentPolicy.check(Kind.PHOTO, Sniffed.GIF, 30 * mb, Quality.STANDARD) is Verdict.TooLarge)
        assertTrue(AttachmentPolicy.check(Kind.VIDEO, Sniffed.WEBM, 30 * mb, Quality.STANDARD) is Verdict.TooLarge)
        assertTrue(AttachmentPolicy.check(Kind.FILE, null, 0, Quality.STANDARD) is Verdict.TooLarge)
        assertEquals(Verdict.Ok, AttachmentPolicy.check(Kind.FILE, null, -1, Quality.STANDARD)) // size unknown: enforced while copying
        assertFalse(AttachmentPolicy.compressible(Kind.FILE, Sniffed.PDF))
        assertTrue(AttachmentPolicy.compressible(Kind.PHOTO, Sniffed.HEIF))
    }

    @Test
    fun qualitiesAreRealDifferences() {
        assertTrue(Quality.STANDARD.photoLongEdge < Quality.HD.photoLongEdge)
        assertTrue(Quality.STANDARD.videoShortSide < Quality.HD.videoShortSide)
        assertTrue(Quality.STANDARD.videoBitrate < Quality.HD.videoBitrate)
        assertEquals(0, Quality.ORIGINAL.photoLongEdge) // never resized
        assertEquals(10, AttachmentPolicy.MAX_ITEMS)
    }

    @Test
    fun previewsAndAlbumLayout() {
        assertEquals("📷 Photo", AttachmentPolicy.preview(listOf(Kind.PHOTO)))
        assertEquals("📷 3 photos", AttachmentPolicy.preview(List(3) { Kind.PHOTO }))
        assertEquals("🎬 2 videos", AttachmentPolicy.preview(List(2) { Kind.VIDEO }))
        assertEquals("📷 2 photos and 1 video", AttachmentPolicy.preview(listOf(Kind.PHOTO, Kind.VIDEO, Kind.PHOTO)))
        assertEquals("📎 4 files", AttachmentPolicy.preview(List(4) { Kind.FILE }))
        assertEquals("📎 3 attachments", AttachmentPolicy.preview(listOf(Kind.PHOTO, Kind.FILE, Kind.VIDEO)))
        assertEquals(Kind.PHOTO, AttachmentPolicy.kindOfMime("image/heic"))
        assertEquals(listOf(listOf(0, 1)), Album.rows(2))
        assertEquals(listOf(listOf(0), listOf(1, 2)), Album.rows(3))
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), Album.rows(10))
        assertEquals(6, Album.overflow(10))
        assertEquals(0, Album.overflow(4))
    }
}
