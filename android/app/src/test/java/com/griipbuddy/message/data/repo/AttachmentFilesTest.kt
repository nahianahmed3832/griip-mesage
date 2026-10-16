package com.griipbuddy.message.data.repo

import com.griipbuddy.message.data.model.AttachmentRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** T1.10: deleting a message removes its decrypted files, and nothing outside the attachments folder. */
class AttachmentFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun ref(id: String, name: String, sha: String = "h") = AttachmentRef(id, name, "application/octet-stream", 1, "k", "n", sha)

    @Test
    fun deletesTheMessagesFilesAndKeepsEverythingElse() {
        val dir = tmp.newFolder("attachments")
        val files = AttachmentFiles(dir)
        val photo = files.fileFor("a_server1", "photo 1.jpg").apply { writeText("decrypted") }
        val other = files.fileFor("a_server2", "keep.pdf").apply { writeText("someone else's") }
        val pending = files.fileFor("m-local", "draft.txt").apply { writeText("never uploaded") }

        assertEquals(1, files.deleteFor("m1", listOf(ref("a_server1", "photo 1.jpg"))))
        assertFalse(photo.exists())
        // A failed upload is named after the message id; a pending placeholder records the file name.
        assertEquals(1, files.deleteFor("m-local", listOf(ref(AttachmentFiles.PENDING, "draft.txt", sha = pending.name))))
        assertFalse(pending.exists())
        assertTrue(other.exists())
    }

    @Test
    fun namesFromAMessageCannotReachOutsideTheFolder() {
        val dir = tmp.newFolder("attachments")
        val files = AttachmentFiles(dir)
        val db = File(tmp.root, "griip.db").apply { writeText("encrypted history") }
        val traversal = listOf(
            ref("../griip.db", "x"),
            ref(AttachmentFiles.PENDING, "x", sha = "../griip.db"),
            ref(AttachmentFiles.PENDING, "x", sha = "/etc/passwd"),
            ref(AttachmentFiles.PENDING, "x", sha = ".."),
        )
        assertEquals(0, files.deleteFor("m1", traversal))
        assertTrue(db.exists())
        assertNull(files.child("../griip.db"))
        assertEquals(dir.canonicalFile, files.fileFor("../../griip", "../x").parentFile)
    }

    @Test
    fun keepsExistingFileNamesForServerIds() {
        val files = AttachmentFiles(tmp.newFolder("attachments"))
        assertEquals("a_Ab-9_x-report_final_.pdf", files.fileFor("a_Ab-9_x", "report final!.pdf").name)
    }
}
