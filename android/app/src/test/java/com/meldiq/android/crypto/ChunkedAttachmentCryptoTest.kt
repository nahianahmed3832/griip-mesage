package com.meldiq.android.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/** Chunked encryption for files over 25 MB (2026-10-17, docs/ATTACHMENTS.md › Large files). */
class ChunkedAttachmentCryptoTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun roundTrip(size: Int, chunk: Int): ByteArray {
        val data = Random(size).nextBytes(size)
        val plain = tmp.newFile().apply { writeBytes(data) }
        val ct = tmp.newFile()
        val meta = ChunkedAttachmentCrypto.encryptFile(plain, ct, chunk)
        val out = File(tmp.root, "out-${System.nanoTime()}")
        ChunkedAttachmentCrypto.decryptFile(ct, meta.key, meta.nonce, meta.sha256, chunk, out)
        assertArrayEquals(data, out.readBytes())
        // Each chunk carries its own 16-byte tag; an empty file is one empty chunk.
        val chunks = if (size == 0) 1 else (size + chunk - 1) / chunk
        assertEquals((size + 16L * chunks), ct.length())
        return ct.readBytes()
    }

    @Test
    fun roundTripsAtEverySizeEdge() {
        for (size in listOf(0, 1, 1023, 1024, 1025, 4096, 10_000)) roundTrip(size, 1024)
        roundTrip(3 * 1024 * 1024 + 7, ChunkedAttachmentCrypto.DEFAULT_CHUNK)
    }

    @Test
    fun tamperingCuttingAndReorderingAreDetected() {
        val data = Random(1).nextBytes(5000)
        val plain = tmp.newFile().apply { writeBytes(data) }
        val ct = tmp.newFile()
        val meta = ChunkedAttachmentCrypto.encryptFile(plain, ct, 1024)
        val bytes = ct.readBytes()
        val block = 1024 + 16
        fun rejects(b: ByteArray, sha: String) {
            val f = tmp.newFile().apply { writeBytes(b) }
            val out = File(tmp.root, "x-${System.nanoTime()}")
            try {
                ChunkedAttachmentCrypto.decryptFile(f, meta.key, meta.nonce, sha, 1024, out)
                fail("accepted a modified file")
            } catch (e: EnvelopeException) {
                assertFalse("no partial output", out.exists())
            }
        }
        // A changed byte: the hash check fails first.
        rejects(bytes.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }, meta.sha256)
        // Even with a matching hash (an attacker who also controls the message), the chunks themselves reject it:
        val swapped = bytes.copyOfRange(block, 2 * block) + bytes.copyOfRange(0, block) + bytes.copyOfRange(2 * block, bytes.size)
        rejects(swapped, Primitives_sha(swapped))
        val truncated = bytes.copyOfRange(0, 2 * block) // the last full chunk wasn't marked final
        rejects(truncated, Primitives_sha(truncated))
    }

    @Test
    fun chunkSizesAreBounded() {
        assertTrue(ChunkedAttachmentCrypto.validChunk(ChunkedAttachmentCrypto.DEFAULT_CHUNK))
        assertFalse(ChunkedAttachmentCrypto.validChunk(16))
        assertFalse(ChunkedAttachmentCrypto.validChunk(64 * 1024 * 1024))
    }

    private fun Primitives_sha(b: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(b).b64()
}
